package vip.mate.agent.model;

import lombok.Data;

import java.util.List;

/**
 * Agent 模板 DTO
 *
 * @author MateClaw Team
 */
@Data
public class TemplateDTO {

    private String id;
    private String name;
    private String nameZh;
    private String description;
    private String descriptionZh;
    private String icon;
    private String agentType;
    private String tags;
    private Integer maxIterations;
    /**
     * Optional pre-rendered system prompt seeded into the new agent. Templates
     * use H2 sections (## Role / ## Goal / ## Backstory / ## Additional
     * Instructions) so the editor UI can split the prompt into structured
     * fields and derive a one-line tagline for the agent card.
     */
    private String systemPrompt;
    private List<WorkspaceFileTemplate> workspaceFiles;

    /**
     * Skill slugs (matching {@code mate_skill.name}) to pre-bind to the newly
     * hired agent. Resolved against the target workspace at apply time; any
     * slug whose row is missing in that workspace is logged and skipped so a
     * partially-installed environment can still hire the agent. Templates ship
     * with classpath-stable slugs, not numeric IDs, because skill ids vary per
     * install.
     */
    private List<String> defaultSkillSlugs;

    /**
     * Tool names to pre-bind directly (bypassing the skill layer). Filtered
     * against {@code AvailableToolService.listAvailable()} at apply time —
     * names the picker can't resolve are dropped with a warning rather than
     * aborting the hire. Use for capabilities that aren't owned by any skill,
     * not for system-level tools that are already universally available.
     */
    private List<String> defaultToolNames;

    /**
     * Provider IDs to pre-bind as the agent's preferred fallback order.
     * Index 0 = highest preference, matching the wire format of
     * {@code PUT /agents/{agentId}/provider-preferences}.
     * Providers not currently available are silently skipped.
     */
    private List<String> defaultProviderIds;

    /**
     * Platform knowledge base reference IDs to pre-bind on the new assistant.
     * IDs must exist in the platform KB catalog at apply time; missing IDs
     * are logged and skipped.
     */
    private List<String> defaultKnowledgeIds;

    /**
     * Platform MCP reference IDs to pre-bind on the new assistant.
     * IDs must exist in the platform MCP catalog at apply time; missing IDs
     * are logged and skipped.
     */
    private List<Integer> defaultMcpIds;

    /**
     * Platform knowledge base full info (id + name + description) for client-side display.
     * Populated by the platform preset detail API alongside the legacy ID-only fields.
     * The client uses {@link #defaultKnowledgeIds} for API calls;
     * this field provides human-readable labels for selection UIs.
     */
    private List<KnowledgeBaseRef> defaultKnowledgeBases;

    /**
     * Platform MCP full info (id + name + description) for client-side display.
     * Populated by the platform preset detail API alongside the legacy ID-only fields.
     * The client uses {@link #defaultMcpIds} for API calls;
     * this field provides human-readable labels for selection UIs.
     */
    private List<McpRef> defaultMcps;

    // ==================== 内嵌类型 ====================

    @Data
    public static class WorkspaceFileTemplate {
        private String filename;
        private String content;
        private Boolean enabled;
        private Integer sortOrder;
    }

    @Data
    public static class KnowledgeBaseRef {
        /** 知识库引用ID（对应 {@link #defaultKnowledgeIds} 中的值） */
        private String kbRefId;
        /** 知识库名称 */
        private String kbName;
        /** 知识库描述 */
        private String description;
    }

    @Data
    public static class McpRef {
        /** MCP 引用ID（对应 {@link #defaultMcpIds} 中的值） */
        private Integer mcpRefId;
        /** MCP 服务名称 */
        private String mcpName;
        /** MCP 服务描述 */
        private String description;
    }
}
