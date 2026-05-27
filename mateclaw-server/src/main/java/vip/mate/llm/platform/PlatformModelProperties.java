package vip.mate.llm.platform;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型同步配置
 * <p>
 * 绑定前缀 mateclaw.model.sync，平台端服务地址通过 Nacos 服务发现获取。
 * 模型的增删改由平台端统一管理，客户端只读 + 同步。
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mateclaw.model.sync")
public class PlatformModelProperties {

    /**
     * 是否启用模型同步（false 退化为本地自治模式）
     */
    private boolean enabled = true;

    /**
     * 平台端模型服务名（Nacos 服务发现用）
     * 优先从 Nacos 发现该服务实例地址
     */
    private String serviceId = "ai-manage";

    /**
     * 获取已分配 Provider + Model 列表的接口路径
     */
    private String assignedModelsPath = "/api/models/assigned";

    /**
     * 获取默认 Embedding 模型 ID 的接口路径
     */
    private String embeddingDefaultPath = "/api/models/settings/embedding-default";

    /**
     * 同步间隔（秒），默认 300 秒（5分钟）
     */
    private int syncIntervalSeconds = 300;

    /**
     * HTTP 连接超时（毫秒）
     */
    private int connectTimeout = 5000;

    /**
     * HTTP 读取超时（毫秒）
     */
    private int readTimeout = 15000;

    /**
     * 同步失败后的重试次数
     */
    private int retryCount = 3;
}
