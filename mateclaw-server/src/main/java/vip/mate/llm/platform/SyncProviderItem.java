package vip.mate.llm.platform;

import lombok.Data;

/**
 * 平台端同步的 Provider 数据项 —— 与平台端 SyncProviderItem 字段一一对应。
 * <p>
 * 作为反序列化 DTO 使用，确保 Jackson 能正确映射平台 JSON 中的所有字段，
 * 再通过 ModelSyncService 转换为本地实体。
 */
@Data
public class SyncProviderItem {

    private String providerId;
    private String name;
    private String apiKeyPrefix;
    private String chatModel;
    private String apiKey;
    private String baseUrl;
    private String generateKwargs;
    private Boolean isCustom;
    private Boolean isLocal;
    private Boolean supportConnectionCheck;
    private Boolean freezeUrl;
    private Boolean requireApiKey;
    private String authType;
}
