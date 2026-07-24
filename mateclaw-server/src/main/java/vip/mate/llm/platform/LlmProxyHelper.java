package vip.mate.llm.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import vip.mate.auth.config.PlatformOAuth2Config;
import vip.mate.auth.service.PlatformNacosService;
import vip.mate.auth.service.PlatformTokenHolder;

import java.util.Map;

/**
 * 共享的 LLM 代理基础设施，供所有 {@code ChatModelBuilder} 实现使用。
 *
 * <p>职责：
 * <ul>
 *   <li>持有平台代理所需的所有可选依赖（代理配置、OAuth2 配置、Nacos 服务发现、Token 持有者）</li>
 *   <li>提供 {@link #isEnabled()} 判断代理是否可用</li>
 *   <li>提供 {@link #resolveToken()} 统一的 Token 获取逻辑（PlatformTokenHolder &gt; PlatformMachineTokenProvider）</li>
 *   <li>提供 {@link #injectUserContext(HttpHeaders)} / {@link #injectUserContext(Map)} 注入用户身份 Header</li>
 *   <li>提供 {@link #getProxyBaseUrl()} 拼接网关代理基础地址</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class LlmProxyHelper {

    /** LLM 代理配置（可选；未配置时退化为直连模式） */
    @Autowired(required = false)
    private PlatformLlmProxyProperties proxyProperties;

    /** 平台 OAuth2 配置（可选；用于机器令牌申请） */
    @Autowired(required = false)
    private PlatformOAuth2Config platformConfig;

    /** 平台服务地址解析（可选） */
    @Autowired(required = false)
    private PlatformNacosService nacosService;

    /** 平台 token 持有者（用户登录后缓存，authorization_code 模式，与渠道同步一致） */
    @Autowired(required = false)
    private PlatformTokenHolder platformTokenHolder;

    /** 机器令牌提供者（可选；client_credentials 模式，作为 fallback） */
    @Autowired(required = false)
    private PlatformMachineTokenProvider machineTokenProvider;

    // ==================== 公共 API ====================

    /**
     * 平台 LLM 代理是否已就绪可用。
     * <p>
     * 需同时满足：代理配置已启用、OAuth2 配置已启用、Nacos 服务可用、
     * 且至少有一个 Token 来源（用户登录 token 或机器 token）。
     */
    public boolean isEnabled() {
        return proxyProperties != null && proxyProperties.isEnabled()
                && platformConfig != null && platformConfig.isEnabled()
                && nacosService != null
                && (platformTokenHolder != null || machineTokenProvider != null);
    }

    /**
     * 获取平台访问 Token。
     * <p>
     * 优先级：PlatformTokenHolder（authorization_code，与渠道同步一致）
     * &gt; PlatformMachineTokenProvider（client_credentials fallback）。
     * 返回 null 表示当前无可用 Token。
     */
    public String resolveToken() {
        if (platformTokenHolder != null) {
            String token = platformTokenHolder.getAccessToken();
            if (token != null) {
                return token;
            }
        }
        if (machineTokenProvider != null) {
            return machineTokenProvider.getAccessToken();
        }
        return null;
    }

    /**
     * 获取平台代理的基础地址（网关 + 服务路径前缀）。
     *
     * @return 如 {@code http://gateway/ai-manage}
     */
    public String getProxyBaseUrl() {
        return nacosService.resolveServiceUrl(proxyProperties.getServiceId());
    }

    /**
     * 获取 OpenAI-compatible 协议的代理 completions 路径。
     *
     * @return 如 {@code /api/proxy/chat/completions}
     */
    public String getCompletionsPath() {
        return proxyProperties.getCompletionsPath();
    }

    // ==================== 用户上下文注入 ====================

    /**
     * 将当前请求的终端用户身份注入到 Spring {@link HttpHeaders} 中。
     * <p>
     * 注入的 Header：
     * <ul>
     *   <li>{@code X-User-Id} — 用户 ID</li>
     *   <li>{@code X-User-Name} — 用户名</li>
     * </ul>
     */
    public void injectUserContext(HttpHeaders headers) {
        String userId = LlmUserContextHolder.getUserId();
        String userName = LlmUserContextHolder.getUserName();
        if (userId != null) {
            headers.set("X-User-Id", userId);
        }
        if (userName != null) {
            headers.set("X-User-Name", userName);
        }
    }

    /**
     * 将当前请求的终端用户身份注入到通用 {@link Map} 中（供非 Spring HTTP 客户端使用）。
     *
     * @param headers 目标 Header Map
     */
    public void injectUserContext(Map<String, String> headers) {
        String userId = LlmUserContextHolder.getUserId();
        String userName = LlmUserContextHolder.getUserName();
        if (userId != null) {
            headers.put("X-User-Id", userId);
        }
        if (userName != null) {
            headers.put("X-User-Name", userName);
        }
    }

    // ==================== Token Key 工厂 ====================

    /**
     * 创建一个委托型 ApiKey，每次调用 {@code getValue()} 时动态获取最新 Token。
     * <p>
     * 适用于 Spring AI 的 {@code OpenAiApi} 等长生命周期客户端，
     * 确保每次请求携带的都是未过期的 access_token。
     *
     * @return 动态 Token Key 实例
     */
    public org.springframework.ai.model.ApiKey createDelegatingApiKey() {
        return new org.springframework.ai.model.ApiKey() {
            @Override
            public String getValue() {
                String token = resolveToken();
                return token != null ? token : "";
            }
        };
    }
}
