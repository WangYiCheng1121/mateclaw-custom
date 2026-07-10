package vip.mate.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 平台 access_token 持有者（服务端缓存）
 * <p>
 * 用户通过 OAuth2 登录平台后，将获取到的 access_token 存储在此组件中，
 * 供所有 Platform*Client 在调用 ai-manage 等平台服务 API 时携带 Authorization 头。
 * <p>
 * 支持令牌过期检测：当令牌接近过期时（提前5分钟），会标记为需要刷新。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class PlatformTokenHolder {

    private final AtomicReference<String> accessToken = new AtomicReference<>();
    private final AtomicReference<Long> expiresAt = new AtomicReference<>();

    /** 提前刷新缓冲时间（毫秒）：5分钟 */
    private static final long REFRESH_BUFFER_MS = 5 * 60 * 1000;

    /**
     * 存储 access_token（登录成功后调用）
     *
     * @param token access_token
     * @param expiresAt 过期时间戳（毫秒），null表示未知
     */
    public void setAccessToken(String token, Long expiresAt) {
        accessToken.set(token);
        this.expiresAt.set(expiresAt);
        if (expiresAt != null) {
            long remainingSeconds = (expiresAt - System.currentTimeMillis()) / 1000;
            log.info("[PlatformTokenHolder] Platform access_token updated, expires in {}s", remainingSeconds);
        } else {
            log.info("[PlatformTokenHolder] Platform access_token updated (no expiry info)");
        }
    }

    /**
     * 存储 access_token（兼容旧接口，无过期时间）
     */
    public void setAccessToken(String token) {
        setAccessToken(token, null);
    }

    /**
     * 获取当前缓存的 access_token
     * <p>
     * 如果令牌已过期，返回 null 并清除缓存。
     *
     * @return access_token，未登录或已过期时返回 null
     */
    public String getAccessToken() {
        String token = accessToken.get();
        if (token == null) {
            return null;
        }

        Long expiry = expiresAt.get();
        if (expiry != null && System.currentTimeMillis() > expiry) {
            log.warn("[PlatformTokenHolder] Token expired, clearing cache");
            clear();
            return null;
        }

        return token;
    }

    /**
     * 检查令牌是否需要刷新（即将过期）
     *
     * @return true 如果令牌需要刷新（剩余时间少于5分钟），false 如果令牌仍然有效或无过期信息
     */
    public boolean needsRefresh() {
        String token = accessToken.get();
        if (token == null) {
            return false;
        }

        Long expiry = expiresAt.get();
        if (expiry == null) {
            return false;
        }

        return System.currentTimeMillis() > (expiry - REFRESH_BUFFER_MS);
    }

    /**
     * 获取令牌剩余有效时间（秒）
     *
     * @return 剩余秒数，如果已过期返回负数，无过期信息返回 null
     */
    public Long getRemainingSeconds() {
        Long expiry = expiresAt.get();
        if (expiry == null) {
            return null;
        }
        return (expiry - System.currentTimeMillis()) / 1000;
    }

    /**
     * 清除 access_token（登出或 token 失效时调用）
     */
    public void clear() {
        accessToken.set(null);
        expiresAt.set(null);
        log.info("[PlatformTokenHolder] Platform access_token cleared");
    }

    /**
     * 是否有可用的 access_token
     */
    public boolean hasToken() {
        return getAccessToken() != null;
    }
}
