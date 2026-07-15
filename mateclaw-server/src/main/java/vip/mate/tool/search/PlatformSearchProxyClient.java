package vip.mate.tool.search;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import vip.mate.auth.config.PlatformOAuth2Config;
import vip.mate.auth.service.PlatformHeaderBuilder;
import vip.mate.auth.service.PlatformNacosService;
import vip.mate.auth.service.PlatformTokenHolder;
import vip.mate.llm.platform.LlmUserContextHolder;
import vip.mate.llm.platform.PlatformMachineTokenProvider;
import vip.mate.llm.platform.PlatformSearchProxyProperties;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * Platform search proxy client.
 * <p>
 * Routes Tavily / Serper search requests through the platform proxy
 * ({@code /api/proxy/search}) instead of calling upstream APIs directly.
 * The platform manages API keys; the client only sends the provider ID.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class PlatformSearchProxyClient {

    private final PlatformSearchProxyProperties proxyProperties;
    private final PlatformOAuth2Config platformConfig;
    private final PlatformNacosService nacosService;
    private final PlatformHeaderBuilder headerBuilder;
    private final PlatformTokenHolder platformTokenHolder;
    private final PlatformMachineTokenProvider machineTokenProvider;
    private final RestTemplate restTemplate;

    public PlatformSearchProxyClient(
            PlatformSearchProxyProperties proxyProperties,
            PlatformOAuth2Config platformConfig,
            PlatformNacosService nacosService,
            PlatformHeaderBuilder headerBuilder,
            PlatformTokenHolder platformTokenHolder,
            PlatformMachineTokenProvider machineTokenProvider) {
        this.proxyProperties = proxyProperties;
        this.platformConfig = platformConfig;
        this.nacosService = nacosService;
        this.headerBuilder = headerBuilder;
        this.platformTokenHolder = platformTokenHolder;
        this.machineTokenProvider = machineTokenProvider;

        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(proxyProperties.getConnectTimeout()));
        factory.setReadTimeout(Duration.ofMillis(proxyProperties.getReadTimeout()));
        this.restTemplate = new RestTemplate(factory);

        // Startup self-check
        log.info("[SearchProxy] Proxy initialized: enabled={}, searchPath={}, serviceId={}, gatewayUrl={}",
                proxyProperties.isEnabled(), proxyProperties.getSearchPath(),
                proxyProperties.getServiceId(), platformConfig.getGatewayUrl());
        log.info("[SearchProxy] Auth sources: platformTokenHolder={}, machineTokenProvider={}",
                platformTokenHolder != null, machineTokenProvider != null);
    }

    /**
     * Whether the search proxy is ready to use.
     */
    public boolean isProxyEnabled() {
        boolean enabled = proxyProperties != null && proxyProperties.isEnabled()
                && platformConfig != null && platformConfig.isEnabled()
                && nacosService != null
                && (platformTokenHolder != null || machineTokenProvider != null);
        if (!enabled) {
            log.debug("[SearchProxy] Proxy NOT enabled: propsEnabled={}, platformEnabled={}, nacos={}, hasTokenSource={}",
                    proxyProperties != null && proxyProperties.isEnabled(),
                    platformConfig != null && platformConfig.isEnabled(),
                    nacosService != null,
                    platformTokenHolder != null || machineTokenProvider != null);
        }
        return enabled;
    }

    /**
     * Execute search through the platform proxy.
     *
     * @param providerId  search provider ID ("tavily" or "serper")
     * @param searchQuery search parameters
     * @return raw JSON response body from the upstream search API
     */
    public String search(String providerId, SearchQuery searchQuery) {
        String proxyUrl = buildProxyUrl();
        String token = resolveProxyToken();
        String tokenSource = (platformTokenHolder != null && platformTokenHolder.getAccessToken() != null)
                ? "PlatformTokenHolder" : (machineTokenProvider != null ? "MachineToken" : "NONE");

        // ===== DIAGNOSTIC: log everything before the call =====
        log.info("[SearchProxy] >>> OUTGOING REQUEST >>> provider={}, url={}, tokenSource={}, tokenPresent={}, query='{}'",
                providerId, proxyUrl, tokenSource, token != null && !token.isBlank(),
                searchQuery.query());

        JSONObject reqBody = new JSONObject()
                .set("provider", providerId)
                .set("query", searchQuery.query())
                .set("max_results", searchQuery.resolvedCount());

        if (searchQuery.hasFreshness()) {
            reqBody.set("freshness", searchQuery.freshness());
        }
        if (searchQuery.hasLanguage()) {
            reqBody.set("language", searchQuery.language());
        }

        HttpHeaders headers = headerBuilder.buildHeaders();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        } else {
            log.warn("[SearchProxy] NO TOKEN available — request will likely get 401");
        }
        injectUserContextHeaders(headers);

        HttpEntity<String> entity = new HttpEntity<>(JSONUtil.toJsonStr(reqBody), headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    proxyUrl, HttpMethod.POST, entity, String.class);

            HttpStatus statusCode = (HttpStatus) response.getStatusCode();
            String body = response.getBody();

            // ===== DIAGNOSTIC: log status + body preview =====
            if (statusCode.is2xxSuccessful()) {
                log.info("[SearchProxy] <<< SUCCESS <<< provider={}, status={}, bodyLength={}",
                        providerId, statusCode.value(), body != null ? body.length() : 0);
                log.debug("[SearchProxy] <<< body preview: {}",
                        body != null ? body.substring(0, Math.min(500, body.length())) : "null");
            } else {
                log.error("[SearchProxy] <<< NON-2xx <<< provider={}, status={}, body={}",
                        providerId, statusCode.value(),
                        body != null ? body.substring(0, Math.min(1000, body.length())) : "null");
                throw new RuntimeException(
                        "Search proxy returned HTTP " + statusCode.value() + ": " +
                        (body != null ? body.substring(0, Math.min(200, body.length())) : "empty body"));
            }

            // 平台端返回统一包装格式 {"code":"200","data":{...},"message":"成功"}
            // 解包 data 字段，返回上游原始响应给 Provider 层解析
            return unwrapData(body, providerId);

        } catch (ResourceAccessException e) {
            // ===== CONNECTION FAILURE: cannot reach platform =====
            Throwable cause = e.getCause();
            if (cause instanceof SocketTimeoutException) {
                log.error("[SearchProxy] <<< TIMEOUT <<< provider={}, url={}, msg={}",
                        providerId, proxyUrl, e.getMessage());
            } else if (cause instanceof ConnectException) {
                log.error("[SearchProxy] <<< CONNECTION REFUSED <<< provider={}, url={}, msg={}",
                        providerId, proxyUrl, e.getMessage());
            } else {
                log.error("[SearchProxy] <<< CONNECT FAILURE <<< provider={}, url={}, msg={}",
                        providerId, proxyUrl, e.getMessage());
            }
            throw new RuntimeException("Cannot reach search proxy at " + proxyUrl + ": " + e.getMessage(), e);

        } catch (Exception e) {
            log.error("[SearchProxy] <<< UNEXPECTED ERROR <<< provider={}, url={}, type={}, msg={}",
                    providerId, proxyUrl, e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    // ==================== internal helpers ====================

    /**
     * 解包平台端统一响应格式 {@code {"code":"200","data":{...},"message":"成功"}}
     * 提取 {@code data} 字段返回给 Provider 层解析。
     * <p>兼容非包装格式（如平台端后续改为直传上游响应），此时直接返回原 body。
     */
    private String unwrapData(String body, String providerId) {
        if (body == null || body.isBlank()) {
            log.warn("[SearchProxy] Empty response body for provider={}", providerId);
            return body;
        }
        try {
            JSONObject wrapped = JSONUtil.parseObj(body);
            Object data = wrapped.get("data");
            if (data != null) {
                log.debug("[SearchProxy] Unwrapped data field for provider={}", providerId);
                return data instanceof String ? (String) data : JSONUtil.toJsonStr(data);
            }
            // 无 data 字段 → 假定已经是上游原始响应，直接返回
            log.debug("[SearchProxy] No 'data' wrapper found, treating body as raw upstream response for provider={}",
                    providerId);
            return body;
        } catch (Exception e) {
            log.warn("[SearchProxy] Failed to unwrap response for provider={}, returning raw body: {}",
                    providerId, e.getMessage());
            return body;
        }
    }

    private String buildProxyUrl() {
        String baseUrl = nacosService.resolveServiceUrl(proxyProperties.getServiceId());
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + proxyProperties.getSearchPath();
    }

    /**
     * Resolve platform access token.
     * Priority: PlatformTokenHolder (authorization_code)
     *         > PlatformMachineTokenProvider (client_credentials fallback).
     */
    private String resolveProxyToken() {
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

    private void injectUserContextHeaders(HttpHeaders headers) {
        String userId = LlmUserContextHolder.getUserId();
        String userName = LlmUserContextHolder.getUserName();
        if (userId != null) {
            headers.set("X-User-Id", userId);
        }
        if (userName != null) {
            headers.set("X-User-Name", userName);
        }
    }
}
