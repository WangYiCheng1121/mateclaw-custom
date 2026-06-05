package vip.mate.llm.platform;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台端同步的模型数据项 —— 与平台端 ModelConfigVO 字段一一对应。
 * <p>
 * 作为反序列化 DTO 使用，确保 Jackson 能正确映射平台 JSON 中的所有字段，
 * 再通过 ModelSyncService 转换为本地实体。
 */
@Data
public class ModelConfigVO {

    private Long id;
    private String name;
    private String provider;
    private String modelName;
    private String description;
    private Double temperature;
    private Integer maxTokens;
    private Integer maxInputTokens;
    private Double topP;
    private Boolean enableSearch;
    private String searchStrategy;
    private Boolean builtin;
    private Boolean enabled;
    private Boolean isDefault;
    private String modelType;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
