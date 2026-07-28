package vip.mate.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.AgentService;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.model.TemplateDTO;
import vip.mate.agent.platform.PlatformAgentClient;
import vip.mate.agent.platform.PlatformServiceException;
import vip.mate.exception.MateClawException;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.tool.model.AvailableToolDTO;
import vip.mate.tool.service.AvailableToolService;
import vip.mate.workspace.document.WorkspaceFileService;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent 模板服务
 * <p>
 * 从平台端实时拉取预置助手模板列表（不再读取本地 classpath JSON）。
 * 支持列出模板和应用模板创建 Agent。
 * <p>
 * 助手创建后由客户端完全自治管理，与平台端模板不再有关联。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final AgentService agentService;
    private final WorkspaceFileService workspaceFileService;
    private final AgentBindingService agentBindingService;
    private final SkillMapper skillMapper;
    private final AvailableToolService availableToolService;
    private final PlatformAgentClient platformAgentClient;

    /**
     * 列出所有可用模板（从平台端实时拉取）。
     * <p>
     * 平台不可达时抛出 PlatformServiceException，前端可捕获并显示具体错误信息。
     *
     * @throws PlatformServiceException 平台服务调用失败时抛出
     */
    public List<TemplateDTO> listTemplates() {
        try {
            List<TemplateDTO> templates = platformAgentClient.fetchPresets(null);
            if (templates.isEmpty()) {
                log.debug("No preset templates returned from platform");
            }
            templates.sort(Comparator.comparing(TemplateDTO::getId));
            return templates;
        } catch (PlatformServiceException e) {
            log.warn("Failed to fetch templates from platform: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 按关键词搜索模板（从平台端实时拉取）。
     *
     * @throws PlatformServiceException 平台服务调用失败时抛出
     */
    public List<TemplateDTO> listTemplates(String keyword) {
        try {
            List<TemplateDTO> templates = platformAgentClient.fetchPresets(keyword);
            templates.sort(Comparator.comparing(TemplateDTO::getId));
            return templates;
        } catch (PlatformServiceException e) {
            log.warn("Failed to fetch templates from platform with keyword '{}': {}", keyword, e.getMessage());
            throw e;
        }
    }

    /**
     * Backwards-compatible overload that defaults to the template's English
     * display strings (existing callers without locale context).
     */
    @Transactional
    public AgentEntity applyTemplate(String templateId, Long workspaceId, Long creatorUserId) {
        return applyTemplate(templateId, workspaceId, creatorUserId, null);
    }

    /**
     * Apply a template, picking name/description in the caller's preferred
     * language so the resulting agent reads natively in their locale. Falls
     * back to the template's primary (English) fields when the localized
     * variant is missing or no locale was supplied.
     *
     * @param templateId      template ID
     * @param workspaceId     target workspace ID (from X-Workspace-Id header)
     * @param creatorUserId   current user ID (creator attribution)
     * @param acceptLanguage  raw Accept-Language header; null/blank → English
     * @throws PlatformServiceException 平台服务调用失败时抛出（无法获取模板列表）
     * @throws MateClawException 模板不存在时抛出
     */
    @Transactional
    public AgentEntity applyTemplate(String templateId, Long workspaceId, Long creatorUserId, String acceptLanguage) {
        TemplateDTO template = listTemplates().stream()
                .filter(t -> t.getId().equals(templateId))
                .findFirst()
                .orElseThrow(() -> new MateClawException("err.agent.template_not_found", "模板不存在: " + templateId));

        boolean preferZh = isChineseLocale(acceptLanguage);
        String displayName = preferZh && template.getNameZh() != null && !template.getNameZh().isBlank()
                ? template.getNameZh()
                : template.getName();
        String displayDesc = preferZh && template.getDescriptionZh() != null && !template.getDescriptionZh().isBlank()
                ? template.getDescriptionZh()
                : template.getDescription();

        // 1. Create the Agent. workspaceId/creatorUserId are passed in
        // explicitly so the DB default does not silently fall back to 1.
        AgentEntity agent = new AgentEntity();
        agent.setName(displayName);
        agent.setDescription(displayDesc);
        agent.setAgentType(template.getAgentType());
        agent.setIcon(template.getIcon());
        agent.setTags(template.getTags());
        agent.setMaxIterations(template.getMaxIterations());
        if (template.getSystemPrompt() != null && !template.getSystemPrompt().isBlank()) {
            agent.setSystemPrompt(template.getSystemPrompt());
        }
        agent.setWorkspaceId(workspaceId);
        agent.setCreatorUserId(creatorUserId);
        agent.setPresetId(templateId);  // trace: records which platform preset created this instance
        AgentEntity created = agentService.createAgent(agent);

        // 2. 创建工作区文件
        if (template.getWorkspaceFiles() != null && !template.getWorkspaceFiles().isEmpty()) {
            for (TemplateDTO.WorkspaceFileTemplate wf : template.getWorkspaceFiles()) {
                workspaceFileService.saveFile(created.getId(), wf.getFilename(), wf.getContent());
            }

            // 3. 启用指定文件并按 sortOrder 排序（setPromptFiles 用列表索引作为排序值）
            List<String> enabledFilenames = template.getWorkspaceFiles().stream()
                    .filter(wf -> Boolean.TRUE.equals(wf.getEnabled()))
                    .sorted((a, b) -> {
                        int sa = a.getSortOrder() != null ? a.getSortOrder() : 0;
                        int sb = b.getSortOrder() != null ? b.getSortOrder() : 0;
                        return Integer.compare(sa, sb);
                    })
                    .map(TemplateDTO.WorkspaceFileTemplate::getFilename)
                    .toList();

            if (!enabledFilenames.isEmpty()) {
                workspaceFileService.setPromptFiles(created.getId(), enabledFilenames);
            }
        }

        // 4. Pre-bind skills the template declares so a hired agent is
        // usable out of the box ("数据分析师" already knows SQL, "代码审查员"
        // already has the test-driven-development playbook). Resolution
        // failures (slug missing in this workspace) are skipped with a
        // warning; bind-service exceptions still propagate and roll back
        // the @Transactional hire — see helper Javadoc.
        applyDefaultSkillBindings(template, created);

        // 5. Pre-bind any standalone tools the template wants. Picker
        // outage and unknown names are dropped with a warning; a
        // setToolBindings exception still propagates (same contract as
        // skills) — see helper Javadoc.
        applyDefaultToolBindings(template, created);

        // 6. Pre-bind provider preferences the template declares.
        applyDefaultProviderPreferences(template, created);

        // 7. Pre-bind platform knowledge bases the template declares.
        applyDefaultKnowledgeBaseBindings(template, created);

        // 8. Pre-bind platform MCPs the template declares.
        applyDefaultMcpBindings(template, created);

        return created;
    }

    /**
     * Resolve {@link TemplateDTO#getDefaultSkillSlugs()} to skill ids inside
     * the agent's own workspace and pre-bind them.
     *
     * <p><b>Failure contract — read carefully.</b>
     * <ul>
     *   <li><b>Resolution failures</b> (slug not present in the agent's
     *       workspace, blank entries) → logged and dropped. A template MUST
     *       stay applyable on an offline upgrade or partial-seed install
     *       where some bundled skills haven't landed yet.</li>
     *   <li><b>Service-layer failures</b>
     *       ({@link AgentBindingService#setSkillBindings} throws — e.g. a
     *       race deletes the skill row between resolve and bind, or the
     *       workspace check rejects it) → <em>propagate</em>. Because
     *       {@link #applyTemplate} runs under {@code @Transactional}, this
     *       rolls back the whole hire. That's deliberate: such a throw is
     *       a real wiring/race problem, and pretending the hire succeeded
     *       would leave the user with a half-configured agent.</li>
     * </ul>
     *
     * <p>Reads the workspace off the just-persisted {@link AgentEntity}
     * rather than a separate parameter so the lookup and the validator
     * inside {@code AgentBindingService.requireSameWorkspace} can never
     * disagree on which workspace they're talking about.
     */
    private void applyDefaultSkillBindings(TemplateDTO template, AgentEntity created) {
        List<String> slugs = template.getDefaultSkillSlugs();
        if (slugs == null || slugs.isEmpty()) return;

        // Mirror the fallback inside AgentBindingService.requireSameWorkspace:
        // a null workspace_id on a row is treated as workspace 1, so the
        // lookup needs to agree or we'd silently turn `eq(workspaceId, null)`
        // into `IS NULL` and match nothing.
        Long workspaceId = created.getWorkspaceId() == null ? 1L : created.getWorkspaceId();

        List<Long> resolvedIds = new ArrayList<>();
        for (String slug : slugs) {
            if (slug == null || slug.isBlank()) continue;
            SkillEntity skill = skillMapper.selectOne(new LambdaQueryWrapper<SkillEntity>()
                    .eq(SkillEntity::getName, slug.trim())
                    .eq(SkillEntity::getWorkspaceId, workspaceId));
            if (skill == null) {
                log.warn("[Template] template {} requested skill slug '{}' not found in workspace {}; skipping",
                        template.getId(), slug, workspaceId);
                continue;
            }
            resolvedIds.add(skill.getId());
        }
        if (resolvedIds.isEmpty()) return;

        agentBindingService.setSkillBindings(created.getId(), resolvedIds);
        log.info("[Template] template {} pre-bound {} skill(s) on agent {}",
                template.getId(), resolvedIds.size(), created.getId());
    }

    /**
     * Pre-filter the template's tool names through the picker so
     * {@link AgentBindingService#setToolBindings} sees only resolvable names
     * — its own validation would otherwise abort the call on the first
     * unknown name and leave the agent with no tool bindings at all.
     *
     * <p>Failure contract mirrors {@link #applyDefaultSkillBindings}:
     * picker outage and unknown names are dropped with a warning; an
     * exception from {@code setToolBindings} itself still propagates and
     * rolls back the hire.
     */
    private void applyDefaultToolBindings(TemplateDTO template, AgentEntity created) {
        List<String> names = template.getDefaultToolNames();
        if (names == null || names.isEmpty()) return;

        Set<String> bindable;
        try {
            bindable = availableToolService.listAvailable().stream()
                    .filter(AvailableToolDTO::isAvailable)
                    .map(AvailableToolDTO::getName)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("[Template] picker unavailable during template {} apply; skipping tool pre-bind: {}",
                    template.getId(), e.getMessage());
            return;
        }

        List<String> filtered = new ArrayList<>();
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            String trimmed = name.trim();
            if (bindable.contains(trimmed)) {
                filtered.add(trimmed);
            } else {
                log.warn("[Template] template {} requested tool '{}' not currently bindable; skipping",
                        template.getId(), trimmed);
            }
        }
        if (filtered.isEmpty()) return;

        agentBindingService.setToolBindings(created.getId(), filtered);
        log.info("[Template] template {} pre-bound {} tool(s) on agent {}",
                template.getId(), filtered.size(), created.getId());
    }

    /**
     * True when the raw Accept-Language header best-matches a Chinese locale.
     * Implementation is intentionally simple — we only need to disambiguate
     * "Chinese vs not" for picking nameZh / descriptionZh.
     */
    private boolean isChineseLocale(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) return false;
        String first = acceptLanguage.split(",")[0].trim().toLowerCase();
        return first.startsWith("zh");
    }

    /**
     * Pre-bind provider preferences declared by the template.
     *
     * <p>Simply passes through to {@link AgentBindingService#setProviderPreferences}.
     * Provider IDs are used as-is; any provider that doesn't exist locally simply
     * won't match at runtime (same behavior as the UI picker).
     */
    private void applyDefaultProviderPreferences(TemplateDTO template, AgentEntity created) {
        List<String> providerIds = template.getDefaultProviderIds();
        if (providerIds == null || providerIds.isEmpty()) return;

        List<String> validIds = new ArrayList<>();
        for (String providerId : providerIds) {
            if (providerId == null || providerId.isBlank()) continue;
            validIds.add(providerId.trim());
        }
        if (validIds.isEmpty()) return;

        agentBindingService.setProviderPreferences(created.getId(), validIds);
        log.info("[Template] template {} pre-bound {} provider(s) on agent {}",
                template.getId(), validIds.size(), created.getId());
    }

    /**
     * Pre-bind platform knowledge bases declared by the template.
     *
     * <p>Failure contract mirrors {@link #applyDefaultSkillBindings}:
     * missing IDs are logged and skipped; a service-layer exception propagates
     * and rolls back the hire.
     */
    private void applyDefaultKnowledgeBaseBindings(TemplateDTO template, AgentEntity created) {
        List<String> kbIds = template.getDefaultKnowledgeIds();
        if (kbIds == null || kbIds.isEmpty()) return;

        List<String> validIds = new ArrayList<>();
        for (String kbId : kbIds) {
            if (kbId == null || kbId.isBlank()) continue;
            validIds.add(kbId.trim());
        }
        if (validIds.isEmpty()) return;

        // Build kbRefId → kbName map from the new full-info field
        Map<String, String> kbNames = null;
        if (template.getDefaultKnowledgeBases() != null) {
            kbNames = new java.util.LinkedHashMap<>();
            for (TemplateDTO.KnowledgeBaseRef kb : template.getDefaultKnowledgeBases()) {
                if (kb.getKbRefId() != null) {
                    kbNames.put(kb.getKbRefId(), kb.getKbName());
                }
            }
        }

        agentBindingService.setKnowledgeBaseBindings(created.getId(), validIds, kbNames);
        log.info("[Template] template {} pre-bound {} knowledge base(s) on agent {}",
                template.getId(), validIds.size(), created.getId());
    }

    /**
     * Pre-bind platform MCPs declared by the template.
     *
     * <p>Failure contract mirrors {@link #applyDefaultSkillBindings}:
     * missing IDs are logged and skipped; a service-layer exception propagates
     * and rolls back the hire.
     */
    private void applyDefaultMcpBindings(TemplateDTO template, AgentEntity created) {
        List<Integer> mcpIds = template.getDefaultMcpIds();
        if (mcpIds == null || mcpIds.isEmpty()) return;

        List<Integer> validIds = new ArrayList<>();
        for (Integer mcpId : mcpIds) {
            if (mcpId == null) continue;
            validIds.add(mcpId);
        }
        if (validIds.isEmpty()) return;

        // Build mcpRefId → mcpName map from the new full-info field
        Map<Integer, String> mcpNames = null;
        if (template.getDefaultMcps() != null) {
            mcpNames = new java.util.LinkedHashMap<>();
            for (TemplateDTO.McpRef mcp : template.getDefaultMcps()) {
                if (mcp.getMcpRefId() != null) {
                    mcpNames.put(mcp.getMcpRefId(), mcp.getMcpName());
                }
            }
        }

        agentBindingService.setMcpBindings(created.getId(), validIds, mcpNames);
        log.info("[Template] template {} pre-bound {} MCP(s) on agent {}",
                template.getId(), validIds.size(), created.getId());
    }
}
