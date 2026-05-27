package vip.mate.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 平台 access_token 持有者（服务端缓存）
 * <p>
 * 用户通过 OAuth2 登录平台后，将获取到的 access_token 存储在此组件中，
 * 供所有 Platform*Client 在调用 ai-manage 等平台服务 API 时携带 Authorization 头。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class PlatformTokenHolder {

    private final AtomicReference<String> accessToken = new AtomicReference<>();

    /**
     * 存储 access_token（登录成功后调用）
     */
    public void setAccessToken(String token) {
        accessToken.set(token);
        log.info("[PlatformTokenHolder] Platform access_token updated");
    }

    /**
     * 获取当前缓存的 access_token
     *
     * @return access_token，未登录时返回 null
     */
    public String getAccessToken() {
        return accessToken.get();
    }

    /**
     * 清除 access_token（登出或 token 失效时调用）
     */
    public void clear() {
        accessToken.set(null);
        log.info("[PlatformTokenHolder] Platform access_token cleared");
    }

    /**
     * 是否有可用的 access_token
     */
    public boolean hasToken() {
        return accessToken.get() != null;
    }
}
