package vip.mate.llm.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import vip.mate.auth.config.PlatformOAuth2Config;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 平台端机器令牌提供者（client_credentials 模式）
 * <p>
 * 在服务启动时（或首次调用时）自动向平台 OAuth2 端点申请机器间通信 token，
 * 并缓存在内存中，提前 60 秒刷新防止边界过期。
 * <p>
 * 用于 LLM 代理等需要服务间认证的场景，不依赖用户登录。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class PlatformMachineTokenProvider {

    private final PlatformOAuth2Config platformConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private volatile String cachedToken;
    private volatile long tokenExpireTime;
    private final ReentrantLock refreshLock = new ReentrantLock();

    /** 提前刷新缓冲（秒） */
    private static final long REFRESH_BUFFER_SECONDS = 60;

    public PlatformMachineTokenProvider(PlatformOAuth2Config platformConfig,
                                        ObjectMapper objectMapper) {
        this.platformConfig = platformConfig;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
        // 设置超时避免阻塞启动
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) platformConfig.getConnectTimeout());
        factory.setReadTimeout((int) platformConfig.getReadTimeout());
        this.restTemplate.setRequestFactory(factory);
    }

    /**
     * 获取有效的机器令牌（带缓存和自动刷新）
     *
     * @return access_token，失败时返回 null（不中断主流程）
     */
    public String getAccessToken() {
        // 快速路径：缓存有效
        if (cachedToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return cachedToken;
        }

        // 需要刷新
        refreshLock.lock();
        try {
            // 双重检查
            if (cachedToken != null && System.currentTimeMillis() < tokenExpireTime) {
                return cachedToken;
            }
            return refreshAccessToken();
        } finally {
            refreshLock.unlock();
        }
    }

    private String refreshAccessToken() {
        if (!platformConfig.isEnabled()) {
            return null;
        }

        String tokenUrl = platformConfig.getGatewayUrl() + "/uni/oauth/token"
                + "?grant_type=client_credentials"
                + "&client_id=" + platformConfig.getClientId()
                + "&client_secret=" + platformConfig.getClientSecret();

        try {
            log.info("[MachineToken] Requesting client_credentials token from platform...");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    tokenUrl, HttpMethod.POST, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[MachineToken] Token request failed: status={}", response.getStatusCode());
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(response.getBody(), Map.class);
            String token = (String) result.get("access_token");
            Object expiresIn = result.get("expires_in");

            if (token == null || token.isBlank()) {
                log.warn("[MachineToken] Empty access_token in response: {}", response.getBody());
                return null;
            }

            int ttl = 7200; // 默认 2 小时
            if (expiresIn instanceof Number n) {
                ttl = n.intValue();
            } else if (expiresIn instanceof String s) {
                try {
                    ttl = Integer.parseInt(s);
                } catch (NumberFormatException ignored) {}
            }

            this.cachedToken = token;
            // 提前 60 秒过期，防止边界失效
            this.tokenExpireTime = System.currentTimeMillis() + (ttl - REFRESH_BUFFER_SECONDS) * 1000L;
            log.info("[MachineToken] Token refreshed, expires in {}s (buffer={}s)", ttl, REFRESH_BUFFER_SECONDS);
            return token;

        } catch (Exception e) {
            log.warn("[MachineToken] Failed to refresh token: {}", e.getMessage());
            return null;
        }
    }
}
