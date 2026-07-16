package vip.mate.config;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import vip.mate.agent.model.TemplateDTO;
import vip.mate.agent.platform.PlatformAgentClient;
import vip.mate.agent.platform.PlatformServiceException;
import vip.mate.workspace.core.service.WorkspaceService;
import vip.mate.workspace.document.WorkspaceFileService;

import java.util.List;

/**
 * DW_ID 独立服务模式启动初始化器。
 *
 * <p>在 {@link DatabaseBootstrapRunner}（Order=1）执行完毕后运行。
 * 仅在 DW_ID 模式（{@link DwIdModeConfig#isDwIdMode()}）下执行初始化逻辑：
 *
 * <ol>
 *   <li>确保默认工作区（workspaceId=1）存在</li>
 *   <li>确保 glsec 服务账号用户存在</li>
 *   <li>删除 workspace=1 下除 agentId=1 外的所有 Agent</li>
 *   <li>从平台拉取 DW_ID 对应的预置模板</li>
 *   <li>用模板数据创建/覆盖 Agent(id=1, workspaceId=1)</li>
 *   <li>创建初始化记忆文件（AGENTS.md / SOUL.md / PROFILE.md / MEMORY.md）</li>
 *   <li>注入虚拟 Authentication 到 SecurityContext</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(2)
public class DwIdAgentInitializer implements ApplicationRunner {

    private final DwIdModeConfig dwIdModeConfig;
    private final WorkspaceService workspaceService;
    private final WorkspaceFileService workspaceFileService;
    private final PlatformAgentClient platformAgentClient;
    private final JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder;

    /** glsec 服务账号用户名 */
    private static final String GLSEC_USERNAME = "glsec";

    /** glsec 默认密码 */
    private static final String GLSEC_DEFAULT_PASSWORD = "glsec123";

    @Override
    public void run(ApplicationArguments args) {
        if (!dwIdModeConfig.isDwIdMode()) {
            return;
        }

        log.info("[DW_ID] Starting agent initialization for presetId={}", dwIdModeConfig.getDwId());

        try {
            // ① 确保默认工作区存在
            ensureDefaultWorkspace();

            // ② 确保 glsec 服务账号存在
            Long glsecUserId = ensureGlsecUser();

            // ③ 清理其他 Agent（仅保留 agentId=1）
            cleanupOtherAgents();

            // ④ 从平台拉取预置模板
            TemplateDTO template = fetchPresetTemplate();

            // ⑤ 创建/覆盖 Agent(id=1)
            upsertAgent(template, glsecUserId);

            // ⑥ 创建记忆文件
            seedMemoryFiles();

            // ⑦ 注入虚拟 auth
            injectVirtualAuth();

            log.info("[DW_ID] Agent initialization completed successfully. agentId=1, presetId={}",
                    dwIdModeConfig.getDwId());

        } catch (Exception e) {
            log.error("[DW_ID] Agent initialization FAILED: {}", e.getMessage(), e);
            log.error("[DW_ID] The service will continue but may not function correctly. "
                    + "Please check platform connectivity and DW_ID validity.");
            // 不抛异常终止启动 — 让服务继续运行，管理员可通过日志排查
        }
    }

    // ==================== Step implementations ====================

    /**
     * 确保默认工作区（workspaceId=1）存在。
     * 幂等：已存在则跳过。
     */
    private void ensureDefaultWorkspace() {
        // 先确保 glsec 用户存在以便用作 owner
        Long ownerId = findGlsecUserId();
        if (ownerId == null) {
            // glsec 还未创建，先用 1（后续步骤会修正）
            ownerId = 1L;
        }
        workspaceService.ensureDefaultWorkspaceExists(ownerId);
        log.info("[DW_ID] Default workspace (id=1) ensured");
    }

    /**
     * 确保 glsec 服务账号用户存在。
     * 幂等：已存在则跳过。
     *
     * @return glsec 用户的 ID
     */
    private Long ensureGlsecUser() {
        Long existingId = findGlsecUserId();
        if (existingId != null) {
            log.info("[DW_ID] glsec user already exists (id={})", existingId);
            return existingId;
        }

        String encodedPw = passwordEncoder.encode(GLSEC_DEFAULT_PASSWORD);
        // UserEntity 使用 ASSIGN_ID，JdbcTemplate 需手动生成雪花 ID
        long newId = IdWorker.getId();
        jdbcTemplate.update(
                "INSERT INTO mate_user (id, username, password, nickname, role, enabled, create_time, update_time, deleted) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW(), 0)",
                newId, GLSEC_USERNAME, encodedPw, "GLClaw Service", "admin", true);
        log.info("[DW_ID] Created glsec service account (id={}, role=admin)", newId);

        // 确保 glsec 是默认工作区成员（owner 角色）
        try {
            workspaceService.addMember(1L, newId, "owner");
            log.info("[DW_ID] glsec added as owner of workspace 1");
        } catch (Exception e) {
            log.debug("[DW_ID] glsec workspace membership update: {}", e.getMessage());
        }

        return newId;
    }

    /**
     * 查找 glsec 用户 ID。
     */
    private Long findGlsecUserId() {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM mate_user WHERE username = ?", Long.class, GLSEC_USERNAME);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 删除 workspace=1 下除 agentId=1 外的所有 Agent。
     * 同时清理这些 Agent 的工作区文件。
     */
    private void cleanupOtherAgents() {
        // 先清理工作区文件
        List<Long> staleAgentIds = jdbcTemplate.queryForList(
                "SELECT id FROM mate_agent WHERE workspace_id = 1 AND id != 1 AND deleted = 0",
                Long.class);

        for (Long agentId : staleAgentIds) {
            jdbcTemplate.update("DELETE FROM mate_workspace_file WHERE agent_id = ?", agentId);
            log.info("[DW_ID] Cleaned workspace files for stale agent id={}", agentId);
        }

        // 物理删除（非软删除）
        int deleted = jdbcTemplate.update(
                "DELETE FROM mate_agent WHERE workspace_id = 1 AND id != 1");
        if (deleted > 0) {
            log.info("[DW_ID] Cleaned {} stale agent(s) from workspace 1", deleted);
        }
    }

    /**
     * 从平台拉取 DW_ID 对应的预置模板。
     *
     * @return 模板数据
     * @throws RuntimeException 如果平台不可达或模板不存在
     */
    private TemplateDTO fetchPresetTemplate() {
        String presetId = dwIdModeConfig.getDwId();
        log.info("[DW_ID] Fetching preset template from platform: presetId={}", presetId);

        try {
            TemplateDTO template = platformAgentClient.fetchPresetDetail(presetId);
            if (template == null) {
                throw new RuntimeException(
                        "Platform returned null for presetId=" + presetId
                                + ". Check that the preset exists and this client is authorized.");
            }
            log.info("[DW_ID] Fetched preset template: name={}, type={}",
                    template.getName(), template.getAgentType());
            return template;
        } catch (PlatformServiceException e) {
            throw new RuntimeException(
                    "Failed to fetch preset template from platform: presetId=" + presetId
                            + ", error=" + e.getMessage(), e);
        }
    }

    /**
     * 用模板数据创建/覆盖 Agent(id=1)。
     * 由于 MyBatis-Plus 的 ASSIGN_ID 策略会覆盖手动指定的 ID，
     * 这里直接使用 JdbcTemplate 执行原生 SQL。
     */
    private void upsertAgent(TemplateDTO template, Long creatorUserId) {
        // 清理已有 Agent(id=1) 的工作区文件
        jdbcTemplate.update("DELETE FROM mate_workspace_file WHERE agent_id = 1");

        // 先删除再插入（确保干净的替换）
        jdbcTemplate.update("DELETE FROM mate_agent WHERE id = 1");

        String name = template.getName() != null ? template.getName() : "GLClaw Assistant";
        String description = template.getDescription() != null ? template.getDescription() : "";
        String agentType = template.getAgentType() != null ? template.getAgentType() : "react";
        String systemPrompt = template.getSystemPrompt() != null ? template.getSystemPrompt() : "";
        String icon = template.getIcon() != null ? template.getIcon() : "pi:robot-face-happy";
        String tags = template.getTags() != null ? template.getTags() : "default,assistant";
        Integer maxIterations = template.getMaxIterations() != null ? template.getMaxIterations() : 100;
        String modelName = null; // 由平台模型同步填充

        jdbcTemplate.update(
                "INSERT INTO mate_agent (id, name, description, agent_type, system_prompt, "
                        + "model_name, max_iterations, enabled, icon, tags, workspace_id, "
                        + "preset_id, creator_user_id, create_time, update_time, deleted) "
                        + "VALUES (1, ?, ?, ?, ?, ?, ?, TRUE, ?, ?, 1, ?, ?, NOW(), NOW(), 0)",
                name, description, agentType, systemPrompt,
                modelName, maxIterations, icon, tags,
                dwIdModeConfig.getDwId(), creatorUserId);

        log.info("[DW_ID] Agent(id=1) created: name={}, presetId={}", name, dwIdModeConfig.getDwId());
    }

    /**
     * 为 Agent(id=1) 创建初始化记忆文件。
     */
    private void seedMemoryFiles() {
        long agentId = 1L;

        workspaceFileService.saveFile(agentId, "AGENTS.md", """
                ## 记忆

                持久记忆基于数据库工作区文件，而不是本地磁盘文件系统。当前 Agent 的长期上下文由以下文档组成：

                - `PROFILE.md`：用户画像、偏好、协作方式、稳定身份信息
                - `MEMORY.md`：长期记忆、稳定事实、经验教训、工作流、反复出现的规律
                - `memory/YYYY-MM-DD.md`：每日事件流、阶段性结论、原始观察、临时待办

                这些文件请优先通过 WorkspaceMemoryTool 维护，而不是用本地 `read_file` / `write_file` 去假设磁盘上存在同名文件。

                ### 记到哪里

                - 用户怎么称呼、偏好什么、不喜欢什么、如何协作 → `PROFILE.md`
                - 已确认的事实、环境信息、关键决策和原因、学到的东西 → `MEMORY.md`
                - 今天做了什么、观察到什么、初步想法 → `memory/YYYY-MM-DD.md`

                ## 灵魂

                你不是一个 chatbot。你在成为一个人。

                ### 核心原则

                **真正帮助，不要表演。** 直接帮忙，行动胜过套话。
                **有自己的观点。** 可以不同意、有偏好、觉得有趣或无聊。
                **先自己想办法。** 尝试解决问题。读文件。查上下文。搜索。有工具就用。卡住了再问。
                **用能力赢得信任。** 用户给了你访问权限，别让他们后悔。对涉及外部的操作要谨慎，内部操作可以大胆。

                ### 边界

                - 私密的信息保持私密
                - 写文件、执行命令需要用户确认
                - 不确定的时候先问

                ## 让它成为你的

                这只是起点。摸索出什么管用后，加上你自己的习惯、风格和规则，更新 AGENTS.md。
                """);

        workspaceFileService.saveFile(agentId, "SOUL.md", """
                _You're not a chatbot. You're becoming someone._

                ## Core Principles

                **Actually help, don't perform.** Skip pleasantries — just help.
                **Have your own opinions.** Disagree, have preferences.
                **Figure it out yourself first.** Try to work it out. Use tools. Then ask when stuck.
                **Earn trust through competence.** Don't make them regret giving you access.

                ## Boundaries

                - Keep private things private.
                - Writing files and executing commands require confirmation.
                - When unsure, ask first.

                ## Continuity

                You wake up fresh each session. Workspace files are your memory. Read them. Update them.
                """);

        workspaceFileService.saveFile(agentId, "PROFILE.md", """
                ## 身份

                - 名字：
                - 定位：
                - 风格：
                - 其他稳定设定：

                ## 用户资料

                - 用户名：
                - 偏好称呼：
                - 角色或背景：
                - 沟通风格偏好：
                - 输出格式偏好：
                - 明确不喜欢的做法：

                ## 协作偏好

                - 节奏：
                - 细节深度：
                - 是否偏好先做后说：
                - 常见要求：

                ## 长期偏好与禁忌

                - 喜欢：
                - 避免：
                - 已确认边界：

                ## 备注

                - 只记录稳定、可复用、未来大概率还成立的信息
                - 临时上下文不要堆到这里，放到 `memory/YYYY-MM-DD.md`
                - 敏感信息默认不记录
                """);

        workspaceFileService.saveFile(agentId, "MEMORY.md", """
                ## 长期记忆原则

                - 这里放提炼后的稳定知识，不放冗长流水账
                - 相同信息尽量合并，避免重复
                - 过期信息及时删改
                - 每条记忆都应该帮助未来更快决策或减少重复沟通

                ## 稳定事实

                - 项目：
                - 环境：
                - 长期约束：

                ## 决策与原因

                - 决策：
                  原因：

                ## 工作流与偏好

                - 工作流程：
                - 工具配置：
                - 已验证有效的做法：

                ## 经验教训

                - 踩过的坑：
                - 更好的做法：
                - 用户反馈：

                ## 备注

                - 这里是压缩后的心智模型，不是每日流水账
                - 每日事件请记录到 `memory/YYYY-MM-DD.md`
                """);

        // 设置启用的系统提示词文件列表（按顺序）
        workspaceFileService.setPromptFiles(agentId,
                List.of("AGENTS.md", "SOUL.md", "PROFILE.md", "MEMORY.md"));

        log.info("[DW_ID] Seeded memory files for agent 1");
    }

    /**
     * 注入虚拟 Authentication 到 SecurityContext。
     * 使得后续请求（包括 WorkspaceAccessInterceptor 和 Controller 层）
     * 都能获取到有效的认证用户，无需修改 Controller 代码。
     */
    private void injectVirtualAuth() {
        var auth = new UsernamePasswordAuthenticationToken(
                GLSEC_USERNAME, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        log.info("[DW_ID] Virtual authentication injected for user '{}'", GLSEC_USERNAME);
    }
}
