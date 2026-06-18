package vip.mate.skill.model;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 技能实体
 * 技能实体：可扩展的功能模块
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_skill")
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 技能名称 — immutable slug, used as primary identifier */
    private String name;

    /**
     * RFC-042 §2.2 — locale-specific display name for zh-CN.
     * {@code null} → UI falls back to {@code name}.
     */
    @TableField(value = "name_zh", updateStrategy = FieldStrategy.ALWAYS)
    private String nameZh;

    /**
     * RFC-042 §2.2 — locale-specific display name for en-US.
     * {@code null} → UI falls back to {@code name}.
     */
    @TableField(value = "name_en", updateStrategy = FieldStrategy.ALWAYS)
    private String nameEn;

    /** 技能描述 */
    private String description;

    /**
     * 中文显示描述（平台同步字段）。
     * {@code null} → 前端回退显示 {@code description}。
     */
    @TableField(value = "description_zh", updateStrategy = FieldStrategy.ALWAYS)
    private String descriptionZh;

    /**
     * 所属目录ID（关联tree表，从平台同步）
     */
    private String categoryId;

    /**
     * 所属目录名称（从平台同步，冗余存储方便展示）
     */
    private String categoryName;

    /** 技能类型：builtin（内置）/ custom（自定义）/ mcp（MCP协议） */
    private String skillType;

    /** 技能图标（emoji 或 URL） */
    private String icon;

    /** 技能版本 */
    private String version;

    /** 技能作者 */
    private String author;

    /** 技能配置（JSON） */
    @TableField(value = "config_json", updateStrategy = FieldStrategy.ALWAYS)
    private String configJson;

    /** 技能代码/脚本内容（旧字段，保留兼容） */
    @TableField(value = "source_code", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceCode;

    /**
     * SKILL.md 完整内容 — 技能执行协议
     * <p>
     * 采用 SKILL.md 格式：YAML frontmatter + Markdown 正文。
     * Agent 通过阅读此内容理解技能的用途、执行方式和注意事项。
     * <p>
     * 格式示例:
     * <pre>
     * ---
     * name: pdf
     * description: PDF 处理技能
     * metadata: { "builtin_skill_version": "1.0" }
     * ---
     * # PDF Processing Guide
     * ## Prerequisites
     * ...（详细使用说明）
     * </pre>
     */
    @TableField(value = "skill_content", updateStrategy = FieldStrategy.ALWAYS)
    private String skillContent;

    /**
     * RFC-090 Phase 2 — full parsed SKILL.md frontmatter as JSON.
     * Source of truth (§14.6); existing columns (skill_type/icon/version/
     * author) become index projections written by
     * {@code SkillPackageResolver} after each resolve.
     */
    @TableField(value = "manifest_json", updateStrategy = FieldStrategy.ALWAYS)
    private String manifestJson;

    /** 是否启用 */
    private Boolean enabled;

    /** 是否已安装（平台同步的技能默认未安装，需用户手动安装后才能启用/禁用） */
    private Boolean installed;

    /** 是否系统内置（不可删除） */
    private Boolean builtin;

    /**
     * 平台端可见性状态（客户端本地跟踪字段，非平台下发）。
     * NULL 正常 / "REMOVED" 平台已移除（禁用/删除/取消分配）。
     * 非空时 toggle/install 被拦截，重新出现时重置为全新技能。
     */
    private String platformStatus;

    /** 标签（逗号分隔） */
    private String tags;

    /**
     * 技能密钥（平台同步字段）。
     * 部分技能需要的 API Key 等凭证，从平台端同步下发。
     * {@code null} 表示该技能无需密钥。
     */
    @TableField(value = "secret", updateStrategy = FieldStrategy.ALWAYS)
    private String secret;

    /**
     * Owning workspace. The DB column has existed since the baseline schema
     * (default = 1) but the field was missing from the entity, so MyBatis
     * Plus silently ignored both reads and writes. Surfacing it here lets
     * binding-time tenancy checks see the value; default behavior on insert
     * remains "fall through to the column DEFAULT" because the field stays
     * {@code null} in the no-arg create path.
     */
    private Long workspaceId;

    /** 来源对话 ID（Agent 自治合成时记录） */
    private String sourceConversationId;

    /**
     * RFC-023：安全扫描状态。
     * NULL = 旧数据或手动创建（不受扫描约束），PASSED = 扫描通过，FAILED = 扫描拦截。
     * listEnabledSkills 过滤条件：NULL 或 PASSED 才加载。
     */
    private String securityScanStatus;

    /**
     * RFC-042 §2.3 — persisted JSON array of the last scan's findings
     * ({@code [{ruleId,severity,category,title,description,filePath,
     * lineNumber,snippet,remediation}]}). Populated by
     * {@code SkillPackageResolver} after every scan so the admin UI can
     * render "why blocked" without re-resolving.
     */
    @TableField(value = "security_scan_result", updateStrategy = FieldStrategy.ALWAYS)
    private String securityScanResult;

    /** RFC-042 §2.3 — wall-clock time of the last scan write-back. */
    private LocalDateTime securityScanTime;

    /**
     * Lifecycle state for the time-window archival state machine:
     * {@code active} / {@code stale} / {@code archived}. Defaults to
     * {@code active} via the column DEFAULT.
     */
    private String lifecycleState;

    /** User-pinned skill — exempt from automatic archival. */
    private Boolean pinned;

    /**
     * Activity anchor, cached from {@code mate_skill_usage_stat.last_loaded_at}
     * so the daily lifecycle sweep is a single indexed select instead of a
     * join. {@code null} falls back to {@code createTime} as the anchor.
     */
    private LocalDateTime lastActivityAt;

    /** Wall-clock time the skill entered the archived state. */
    private LocalDateTime archivedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
