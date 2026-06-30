package vip.mate.workspace.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.exception.MateClawException;
import vip.mate.i18n.I18nService;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.core.model.WorkspaceAccessVO;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.model.WorkspaceWithRoleVO;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;
import vip.mate.workspace.core.security.RoleCapabilities;
import vip.mate.workspace.document.WorkspaceFileService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作区业务服务
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final ConversationMapper conversationMapper;
    private final AgentMapper agentMapper;
    private final WikiKnowledgeBaseService wikiKnowledgeBaseService;
    private final I18nService i18n;
    private final WorkspaceFileService workspaceFileService;

    /** 默认工作区 slug */
    public static final String DEFAULT_SLUG = "default";

    /** 用户个人工作区 slug 前缀 */
    private static final String USER_WORKSPACE_PREFIX = "user_";

    /** 成员资格缓存：key = "workspaceId:userId"，value = role string（null 表示非成员） */
    private final Cache<String, String> membershipCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(1000)
            .build();

    /** 用户默认工作区缓存：key = userId，value = workspaceId */
    private final Cache<Long, Long> userWorkspaceCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(500)
            .build();

    // ==================== 工作区 CRUD ====================

    public List<WorkspaceEntity> listAll() {
        return workspaceMapper.selectList(
                new LambdaQueryWrapper<WorkspaceEntity>().orderByAsc(WorkspaceEntity::getCreateTime));
    }

    /**
     * 查询用户可见的工作区列表（用户是其成员的所有工作区）
     */
    public List<WorkspaceEntity> listByUserId(Long userId) {
        List<WorkspaceMemberEntity> memberships = memberMapper.selectList(
                new LambdaQueryWrapper<WorkspaceMemberEntity>()
                        .eq(WorkspaceMemberEntity::getUserId, userId));
        if (memberships.isEmpty()) {
            // 至少返回默认工作区
            WorkspaceEntity defaultWs = getBySlug(DEFAULT_SLUG);
            return defaultWs != null ? List.of(defaultWs) : List.of();
        }
        List<Long> wsIds = memberships.stream().map(WorkspaceMemberEntity::getWorkspaceId).toList();
        return workspaceMapper.selectBatchIds(wsIds);
    }

    /**
     * List workspaces visible to a user, each annotated with the user's membership
     * role. Global admins see every workspace (memberRole reflects their real
     * membership, or null when they are not actually a member).
     */
    public List<WorkspaceWithRoleVO> listWithRoleByUserId(Long userId, boolean isGlobalAdmin) {
        List<WorkspaceMemberEntity> memberships = memberMapper.selectList(
                new LambdaQueryWrapper<WorkspaceMemberEntity>()
                        .eq(WorkspaceMemberEntity::getUserId, userId));
        Map<Long, String> roleByWorkspaceId = new HashMap<>();
        for (WorkspaceMemberEntity m : memberships) {
            roleByWorkspaceId.put(m.getWorkspaceId(), m.getRole());
        }

        List<WorkspaceEntity> entities;
        if (isGlobalAdmin) {
            entities = listAll();
        } else if (memberships.isEmpty()) {
            WorkspaceEntity defaultWs = getBySlug(DEFAULT_SLUG);
            entities = defaultWs != null ? List.of(defaultWs) : List.of();
        } else {
            entities = workspaceMapper.selectBatchIds(roleByWorkspaceId.keySet());
        }

        List<WorkspaceWithRoleVO> result = new ArrayList<>(entities.size());
        for (WorkspaceEntity ws : entities) {
            String role = roleByWorkspaceId.get(ws.getId());
            result.add(WorkspaceWithRoleVO.from(ws, role, isGlobalAdmin));
        }
        return result;
    }

    /**
     * Resolve the user's access summary for a workspace. Used by
     * {@code GET /api/v1/workspaces/&#123;id&#125;/access} so the frontend can
     * refresh its capability set after a role change without reloading the page.
     */
    public WorkspaceAccessVO getAccess(Long workspaceId, Long userId, boolean isGlobalAdmin) {
        WorkspaceMemberEntity member = getMembership(workspaceId, userId);
        String memberRole = member != null ? member.getRole() : null;
        String effective = isGlobalAdmin ? "owner" : memberRole;
        Set<String> capabilities = effective != null
                ? RoleCapabilities.forRole(effective)
                : Set.of();
        return new WorkspaceAccessVO(workspaceId, memberRole, isGlobalAdmin, effective, capabilities);
    }

    public WorkspaceEntity getById(Long id) {
        WorkspaceEntity entity = workspaceMapper.selectById(id);
        if (entity == null) {
            throw new MateClawException("err.workspace.not_found", "工作区不存在: " + id);
        }
        return entity;
    }

    public WorkspaceEntity getBySlug(String slug) {
        return workspaceMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceEntity>()
                        .eq(WorkspaceEntity::getSlug, slug));
    }

    /**
     * Derive a URL-safe, unique slug from a workspace name. Non-alphanumeric
     * runs collapse to a single hyphen; a name with no ASCII alphanumerics
     * (e.g. a purely Chinese name) falls back to a generic base. A numeric
     * suffix is appended until the slug is free.
     */
    private String generateUniqueSlug(String name) {
        String base = (name == null ? "" : name.toLowerCase())
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (base.isBlank()) {
            base = "workspace";
        }
        String slug = base;
        int n = 1;
        while (getBySlug(slug) != null) {
            slug = base + "-" + (++n);
        }
        return slug;
    }

    @Transactional
    public WorkspaceEntity create(WorkspaceEntity entity, Long creatorUserId) {
        // Auto-derive a slug from the name when the caller did not supply one,
        // so workspace creation never fails on the NOT NULL slug column.
        if (entity.getSlug() == null || entity.getSlug().isBlank()) {
            entity.setSlug(generateUniqueSlug(entity.getName()));
        }
        // 验证 slug 唯一
        if (getBySlug(entity.getSlug()) != null) {
            throw new MateClawException("err.workspace.slug_exists", "工作区标识已存在: " + entity.getSlug());
        }
        entity.setOwnerId(creatorUserId);
        workspaceMapper.insert(entity);

        // 创建者自动成为 owner
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspaceId(entity.getId());
        member.setUserId(creatorUserId);
        member.setRole("owner");
        memberMapper.insert(member);

        // Seed the per-workspace tasks conversation so cron output (now routed
        // there by CronConversationResolver) shows up in the sidebar from day
        // one. The V65 migration handles existing workspaces; this hook covers
        // workspaces created post-upgrade.
        seedTasksConversation(entity.getId());

        log.info("Created workspace: {} (slug={}, owner={})", entity.getName(), entity.getSlug(), creatorUserId);
        return entity;
    }

    private void seedTasksConversation(Long workspaceId) {
        if (workspaceId == null) return;
        ConversationEntity tasks = new ConversationEntity();
        tasks.setConversationId("tasks_" + workspaceId);
        tasks.setTitle(i18n != null ? i18n.msg("cron.tasks_conversation.title") : "📋 Scheduled Tasks");
        tasks.setUsername("system");
        tasks.setMessageCount(0);
        tasks.setLastActiveTime(java.time.LocalDateTime.now());
        tasks.setStreamStatus("idle");
        tasks.setWorkspaceId(workspaceId);
        try {
            conversationMapper.insert(tasks);
        } catch (Exception e) {
            // Non-fatal: workspace creation succeeds even if the seed fails;
            // the conversation will be lazy-created on the first cron save
            // since saveMessage upserts the conversation row.
            log.warn("[WorkspaceService] Failed to seed tasks conversation for workspace {}: {}",
                    workspaceId, e.getMessage());
        }
    }

    /**
     * 为新工作区种子一个默认「通用助手」Agent。
     * <p>
     * 从默认工作区（workspace_id=1）复制通用助手的基本配置，仅修改 workspace_id。
     * 使用 MyBatis Plus 的 ASSIGN_ID 策略自动生成新 ID。
     *
     * @param workspaceId 目标工作区 ID
     */
    private void seedDefaultAgent(Long workspaceId) {
        if (workspaceId == null) return;
        try {
            // 查找默认工作区的通用助手（id=1000000001，种子数据固定 ID）
            AgentEntity template = agentMapper.selectById(1000000001L);
            if (template == null) {
                log.warn("[WorkspaceService] Default agent (id=1000000001) not found, skip seeding for workspace {}",
                        workspaceId);
                return;
            }
            AgentEntity agent = new AgentEntity();
            agent.setName(template.getName());
            agent.setDescription(template.getDescription());
            agent.setAgentType(template.getAgentType());
            agent.setSystemPrompt(template.getSystemPrompt());
            agent.setModelName(template.getModelName());
            agent.setMaxIterations(template.getMaxIterations());
            agent.setEnabled(true);
            agent.setIcon(template.getIcon());
            agent.setTags(template.getTags());
            agent.setWorkspaceId(workspaceId);
            agentMapper.insert(agent);
            log.info("[WorkspaceService] Seeded default agent '{}' (id={}) for workspace {}",
                    agent.getName(), agent.getId(), workspaceId);

            // 为新Agent种子初始化的记忆文件（PROFILE.md、MEMORY.md、AGENTS.md）
            seedDefaultMemoryFiles(agent.getId());
        } catch (Exception e) {
            log.warn("[WorkspaceService] Failed to seed default agent for workspace {}: {}",
                    workspaceId, e.getMessage());
        }
    }

    /**
     * 为新创建的Agent种子初始化的记忆文件。
     * <p>
     * 创建 PROFILE.md、MEMORY.md、AGENTS.md 三个核心记忆文件，
     * 并设置 PROFILE.md 和 MEMORY.md 为 enabled=true，使其自动纳入系统提示词。
     *
     * @param agentId Agent ID
     */
    private void seedDefaultMemoryFiles(Long agentId) {
        if (agentId == null) return;
        try {
            // 创建 AGENTS.md（工具与行为配置）- 默认启用，作为系统提示词的一部分
            workspaceFileService.saveFile(agentId, "AGENTS.md", """
                    ## 记忆

                    持久记忆基于数据库工作区文件，而不是本地磁盘文件系统。当前 Agent 的长期上下文由以下文档组成：

                    - `PROFILE.md`：用户画像、偏好、协作方式、稳定身份信息
                    - `MEMORY.md`：长期记忆、稳定事实、经验教训、工作流、反复出现的规律
                    - `memory/YYYY-MM-DD.md`：每日事件流、阶段性结论、原始观察、临时待办

                    这些文件请优先通过 WorkspaceMemoryTool 维护，而不是用本地 `read_file` / `write_file` 去假设磁盘上存在同名文件。

                    ### 记到哪里

                    - 用户怎么称呼、偏好什么、不喜欢什么、如何协作 → `PROFILE.md`
                    - 稳定项目事实、关键决策、工具配置、路径、经验教训、长期约束 → `MEMORY.md`
                    - 今天发生了什么、刚做出的决定、阶段性上下文、待跟进事项 → `memory/YYYY-MM-DD.md`

                    ### 写下来

                    - 记忆有限，想保留就写入工作区记忆文件
                    - 当用户说"记住这个"或表达明确偏好时，优先更新 `PROFILE.md` 或 `MEMORY.md`
                    - 当你完成任务、学到教训、发现稳定工作流时，及时更新 `MEMORY.md`
                    - 当出现一次性事件或当天上下文时，记录到 `memory/YYYY-MM-DD.md`
                    - 为避免覆盖信息，修改已有记忆前先读取原内容，再做增量编辑

                    ### 主动记录

                    不要总等用户明确下命令。如果信息大概率会在未来有价值，主动沉淀：

                    - 用户偏好、习惯、常用术语、合作边界
                    - 重要结论、架构决策、已确认约束
                    - 常用路径、工具配置、部署环境、排障经验
                    - 用户反复强调的标准、讨厌的做法、期待的输出形式

                    ### 记忆涌现

                    把 `memory/YYYY-MM-DD.md` 看作原始经历，把 `MEMORY.md` 看作提炼后的心智模型。

                    - 如果同类偏好、约束、流程、问题或教训重复出现，就把它们从每日笔记上提为 `MEMORY.md` 中的长期规律
                    - 长期记忆追求去重、抽象、压缩，不要堆原始流水账
                    - 发现旧记忆已经失效时，及时删除或改写，而不是继续叠加矛盾内容
                    - 优先维护已有 section，不要反复创建语义重复的新 section

                    ### 主动召回

                    在回答以下问题前，优先利用工作区记忆：

                    - 涉及用户偏好、历史决策、既有约束、项目惯例
                    - 涉及之前做过什么、踩过什么坑、为什么这样做
                    - 涉及日期、事件、待办延续时，先看 `memory/YYYY-MM-DD.md`

                    能从长期记忆回答的问题，就不要假装第一次见。能从每日笔记恢复上下文的问题，就不要只靠猜。

                    ## 安全

                    - 绝不泄露私密数据。绝不。
                    - 运行破坏性命令（写文件、执行 Shell）前，等待用户审批确认。
                    - `trash` > `rm`（能恢复总比永久删除好）
                    - 拿不准的事情，先和用户确认。

                    ## 内部 vs 外部

                    **可以自由做的：**

                    - 读文件、探索、整理、学习
                    - 搜索网页、查时间
                    - 在工作区内阅读和分析

                    **先问一声：**

                    - 本地文件系统写文件、编辑文件
                    - 执行 Shell 命令
                    - 任何会影响外部系统的操作
                    - 任何你不确定的事

                    ## 工具

                    优先用 WorkspaceMemoryTool 读写 `PROFILE.md`、`MEMORY.md` 和 `memory/*.md`。
                    通过 SkillFileTool 查看可用技能（Skills）的 SKILL.md 了解具体用法。
                    本地配置（SSH 信息、常用路径等）记在 `MEMORY.md` 的工具设置 section。
                    身份和用户资料记在 `PROFILE.md`。

                    ## 让它成为你的

                    这只是起点。摸索出什么管用后，加上你自己的习惯、风格和规则，更新 AGENTS.md。
                    """);

            // 创建 PROFILE.md（用户档案）- 默认启用
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
                    - 临时上下文不要堆在这里，放到 `memory/YYYY-MM-DD.md`
                    - 敏感信息默认不记录
                    """);

            // 创建 MEMORY.md（长期记忆）- 默认启用
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
            workspaceFileService.setPromptFiles(agentId, List.of("AGENTS.md", "PROFILE.md", "MEMORY.md"));

            log.info("[WorkspaceService] Seeded default memory files for agent {}", agentId);
        } catch (Exception e) {
            log.warn("[WorkspaceService] Failed to seed default memory files for agent {}: {}",
                    agentId, e.getMessage());
        }
    }

    public WorkspaceEntity update(WorkspaceEntity entity) {
        WorkspaceEntity existing = getById(entity.getId());
        // slug 为 null 时保留原值，不做修改
        if (entity.getSlug() == null) {
            entity.setSlug(existing.getSlug());
        }
        // 不允许修改默认工作区的 slug
        if (DEFAULT_SLUG.equals(existing.getSlug()) && !DEFAULT_SLUG.equals(entity.getSlug())) {
            throw new MateClawException("err.workspace.cannot_modify_default", "不能修改默认工作区的标识");
        }
        // 验证 slug 唯一性（如果修改了 slug）
        if (!entity.getSlug().equals(existing.getSlug())) {
            if (getBySlug(entity.getSlug()) != null) {
                throw new MateClawException("err.workspace.slug_exists", "工作区标识已存在: " + entity.getSlug());
            }
        }
        workspaceMapper.updateById(entity);
        return entity;
    }

    public void delete(Long id) {
        WorkspaceEntity existing = getById(id);
        if (DEFAULT_SLUG.equals(existing.getSlug())) {
            throw new MateClawException("err.workspace.cannot_delete_default", "不能删除默认工作区");
        }
        // Refuse to delete a workspace that still owns wiki knowledge bases —
        // dropping the workspace row would orphan them. The caller must delete
        // the knowledge bases first.
        int kbCount = wikiKnowledgeBaseService.listByWorkspace(id).size();
        if (kbCount > 0) {
            throw new MateClawException("err.workspace.not_empty", 409,
                    "工作区下还有 " + kbCount + " 个知识库，请先删除知识库再删除工作区");
        }
        workspaceMapper.deleteById(id);
        log.info("Deleted workspace: {} (id={})", existing.getName(), id);
    }

    // ==================== 成员管理 ====================


    /**
     * 确保默认工作区（ID=1）存在。
     * 用于兜底旧数据库未种子初始化的场景（安装包分发、数据迁移等）。
     * 幂等操作：如果已存在则跳过。
     *
     * @param currentUserId 当前操作用户 ID（用作默认 owner）
     */
    @Transactional
    public void ensureDefaultWorkspaceExists(Long currentUserId) {
        WorkspaceEntity existing = workspaceMapper.selectById(1L);
        if (existing != null) {
            return;
        }
        // 默认工作区不存在，自动创建
        WorkspaceEntity ws = new WorkspaceEntity();
        ws.setId(1L);
        ws.setName("默认工作区");
        ws.setSlug(DEFAULT_SLUG);
        ws.setDescription("系统默认工作区，所有用户自动加入");
        ws.setOwnerId(currentUserId);
        workspaceMapper.insert(ws);
        log.info("Auto-created default workspace (id=1, owner={})", currentUserId);
    }

    /**
     * 获取或创建用户个人工作区。
     * <p>
     * 每个用户首次登录时自动创建专属工作区（slug = "user_{userId}"），
     * 后续登录时直接返回已有工作区。工作区以用户昵称或用户名命名。
     * <p>
     * 使用 Caffeine 缓存（5 分钟 TTL）减少高频登录场景下的数据库查询。
     *
     * @param userId   用户 ID
     * @param nickname 用户昵称（用于工作区名称），可为 null
     * @return 用户个人工作区 ID（总是 > 0）
     */
    @Transactional
    public Long getOrCreateUserWorkspace(Long userId, String nickname) {
        // 先查缓存
        Long cached = userWorkspaceCache.getIfPresent(userId);
        if (cached != null) {
            return cached;
        }

        String slug = USER_WORKSPACE_PREFIX + userId;
        WorkspaceEntity ws = getBySlug(slug);
        if (ws != null) {
            // 已存在：刷新缓存并返回
            userWorkspaceCache.put(userId, ws.getId());
            return ws.getId();
        }

        // 不存在：创建个人工作区
        String displayName = (nickname != null && !nickname.isBlank())
                ? nickname + "的工作区"
                : "用户" + userId + "的工作区";

        WorkspaceEntity entity = new WorkspaceEntity();
        entity.setName(displayName);
        entity.setSlug(slug);
        entity.setDescription("用户个人工作区，自动隔离");
        entity.setOwnerId(userId);
        workspaceMapper.insert(entity);

        // 用户自动成为 owner
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspaceId(entity.getId());
        member.setUserId(userId);
        member.setRole("owner");
        memberMapper.insert(member);

        // 从默认工作区复制「通用助手」Agent 到新工作区
        seedDefaultAgent(entity.getId());

        userWorkspaceCache.put(userId, entity.getId());
        log.info("Created personal workspace for user {}: id={}, slug={}", userId, entity.getId(), slug);
        return entity.getId();
    }

    /**
     * 获取用户的默认工作区 ID（带缓存）。
     * 优先返回用户个人工作区，如果不存在则尝试按 slug 查找。
     *
     * @param userId 用户 ID
     * @return 工作区 ID，兜底返回 1L
     */
    public Long getDefaultWorkspaceId(Long userId) {
        Long cached = userWorkspaceCache.getIfPresent(userId);
        if (cached != null) {
            return cached;
        }
        // 尝试按 slug 查找
        WorkspaceEntity ws = getBySlug(USER_WORKSPACE_PREFIX + userId);
        if (ws != null) {
            userWorkspaceCache.put(userId, ws.getId());
            return ws.getId();
        }
        // 兜底：返回默认工作区（旧数据/未迁移场景）
        return 1L;
    }

    public List<WorkspaceMemberEntity> listMembers(Long workspaceId) {
        return memberMapper.selectList(
                new LambdaQueryWrapper<WorkspaceMemberEntity>()
                        .eq(WorkspaceMemberEntity::getWorkspaceId, workspaceId)
                        .orderByAsc(WorkspaceMemberEntity::getCreateTime));
    }

    public WorkspaceMemberEntity getMembership(Long workspaceId, Long userId) {
        return memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMemberEntity>()
                        .eq(WorkspaceMemberEntity::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMemberEntity::getUserId, userId));
    }

    @Transactional
    public WorkspaceMemberEntity addMember(Long workspaceId, Long userId, String role) {
        // 验证工作区存在
        getById(workspaceId);
        // 检查是否已是成员
        WorkspaceMemberEntity existing = getMembership(workspaceId, userId);
        if (existing != null) {
            throw new MateClawException("err.workspace.member_exists", 409, "用户已经是该工作区的成员");
        }
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setRole(normalizeAssignableRole(role));
        memberMapper.insert(member);
        evictMembershipCache(workspaceId, userId);
        log.info("Added member to workspace: userId={}, workspaceId={}, role={}", userId, workspaceId, member.getRole());
        return member;
    }

    public WorkspaceMemberEntity updateMemberRole(Long workspaceId, Long userId, String role) {
        WorkspaceMemberEntity member = getMembership(workspaceId, userId);
        if (member == null) {
            throw new MateClawException("err.workspace.not_member", 404, "用户不是该工作区的成员");
        }
        if ("owner".equals(member.getRole())) {
            throw new MateClawException("err.workspace.cannot_modify_owner", 400, "不能修改工作区拥有者的角色");
        }
        member.setRole(normalizeAssignableRole(role));
        memberMapper.updateById(member);
        evictMembershipCache(workspaceId, userId);
        return member;
    }

    private String normalizeAssignableRole(String role) {
        String normalized = role == null || role.isBlank() ? "member" : role.trim();
        return switch (normalized) {
            case "admin", "member", "viewer" -> normalized;
            case "owner" -> throw new MateClawException(
                    "err.workspace.invalid_member_role", 400, "不能通过成员管理授予 owner 角色");
            default -> throw new MateClawException(
                    "err.workspace.invalid_member_role", 400, "无效的成员角色: " + normalized);
        };
    }

    public void removeMember(Long workspaceId, Long userId) {
        WorkspaceMemberEntity member = getMembership(workspaceId, userId);
        if (member == null) {
            throw new MateClawException("err.workspace.not_member", 404, "用户不是该工作区的成员");
        }
        if ("owner".equals(member.getRole())) {
            throw new MateClawException("err.workspace.cannot_remove_owner", 400, "不能移除工作区拥有者");
        }
        memberMapper.deleteById(member.getId());
        evictMembershipCache(workspaceId, userId);
        log.info("Removed member from workspace: userId={}, workspaceId={}", userId, workspaceId);
    }

    // ==================== 权限检查 ====================

    /**
     * 检查用户是否有指定工作区的最低角色权限
     *
     * @param workspaceId 工作区 ID
     * @param userId      用户 ID
     * @param minRole     最低角色要求：owner > admin > member > viewer
     * @return true 如果用户有足够权限
     */
    public boolean hasPermission(Long workspaceId, Long userId, String minRole) {
        WorkspaceMemberEntity member = getMembership(workspaceId, userId);
        if (member == null) {
            return false;
        }
        return roleLevel(member.getRole()) >= roleLevel(minRole);
    }

    /**
     * 断言用户有指定权限，否则抛异常
     */
    public void requirePermission(Long workspaceId, Long userId, String minRole) {
        if (!hasPermission(workspaceId, userId, minRole)) {
            throw new MateClawException("err.workspace.insufficient_permission", 403, "权限不足：需要 " + minRole + " 或更高角色");
        }
    }

    /**
     * 带缓存的权限检查（拦截器高频调用，避免每次请求查库）
     */
    public boolean hasPermissionCached(Long workspaceId, Long userId, String minRole) {
        String cacheKey = workspaceId + ":" + userId;
        String role = membershipCache.get(cacheKey, k -> {
            WorkspaceMemberEntity member = getMembership(workspaceId, userId);
            return member != null ? member.getRole() : "";
        });
        if (role == null || role.isEmpty()) {
            return false;
        }
        return roleLevel(role) >= roleLevel(minRole);
    }

    /**
     * 清除指定 workspace + user 的成员资格缓存（成员变更时调用）
     */
    public void evictMembershipCache(Long workspaceId, Long userId) {
        membershipCache.invalidate(workspaceId + ":" + userId);
    }

    private int roleLevel(String role) {
        return switch (role) {
            case "owner" -> 4;
            case "admin" -> 3;
            case "member" -> 2;
            case "viewer" -> 1;
            default -> 0;
        };
    }
}
