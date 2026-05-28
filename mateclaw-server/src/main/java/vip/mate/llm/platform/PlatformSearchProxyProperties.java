package vip.mate.llm.platform;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 平台端搜索代理配置
 * <p>
 * 当启用时，MateClaw 客户端的搜索流量统一走平台代理
 * （而非直连搜索提供商），由平台端统一管理 API Key 和用量统计。
 * <p>
 * 设计上与 {@link PlatformLlmProxyProperties} 保持对称，
 * 复用相同的认证体系（PlatformTokenHolder / PlatformMachineTokenProvider）。
 *
 * @author MateClaw Team
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mateclaw.platform.search-proxy")
public class PlatformSearchProxyProperties {

    /** 是否启用搜索代理（默认启用，通过环境变量 PLATFORM_SEARCH_PROXY_ENABLED 覆盖） */
    private boolean enabled = true;

    /** 平台端代理的搜索路径 */
    private String searchPath = "/api/proxy/search";

    /** 服务 ID（用于通过网关路由到 ai-manage 服务） */
    private String serviceId = "ai-manage";

    /** HTTP 连接超时（毫秒） */
    private int connectTimeout = 5000;

    /** HTTP 读取超时（毫秒） */
    private int readTimeout = 15000;

}
