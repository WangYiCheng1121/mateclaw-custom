package vip.mate.llm.platform;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 平台端 LLM 代理配置
 * <p>
 * 当启用时，MateClaw 客户端的大模型调用流量统一走平台代理
 * （而非直连 LLM 提供商），由平台端统一管理 API Key 和用量统计。
 *
 * @author MateClaw Team
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mateclaw.platform.llm-proxy")
public class PlatformLlmProxyProperties {

    /** 是否启用 LLM 代理（默认启用，通过环境变量 PLATFORM_LLM_PROXY_ENABLED 覆盖） */
    private boolean enabled = true;

    /** 平台端代理的 completions 路径 */
    private String completionsPath = "/api/proxy/chat/completions";

    /** 服务 ID（用于通过网关路由到 ai-manage 服务） */
    private String serviceId = "ai-manage";

}
