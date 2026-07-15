package vip.mate.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * 平台 API 请求 Header 统一构建器。
 * <p>
 * 所有与平台交互的 HTTP 请求都通过此组件构建基础 Headers，确保全局一致的 Header 注入：
 * <ul>
 *   <li>{@code Content-Type: application/json}</li>
 *   <li>{@code projectId} — 当存在 {@code PROJECTID} 环境变量时注入，用于 K8s 集群内租户路由</li>
 * </ul>
 * <p>
 * 调用方构建 Headers 后，需自行调用各自的 {@code applyAuth(headers)} 补充 Bearer Token。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class PlatformHeaderBuilder {

    private static final String PROJECT_ID_ENV = "PROJECTID";
    private static final String HEADER_PROJECT_ID = "projectId";

    /** 缓存环境变量值，避免每次请求都读取系统环境 */
    private final String projectId;

    public PlatformHeaderBuilder() {
        String envValue = System.getenv(PROJECT_ID_ENV);
        this.projectId = StringUtils.isNotEmpty(envValue) ? envValue : null;
        if (projectId != null) {
            log.info("[PlatformHeader] PROJECTID detected: {} (will inject into all platform API requests)", projectId);
        }
    }

    /**
     * 构建平台 API 请求的基础 Headers。
     * <p>
     * 自动注入：
     * <ul>
     *   <li>{@code Content-Type: application/json}</li>
     *   <li>{@code projectId}（仅当 PROJECTID 环境变量存在时）</li>
     * </ul>
     *
     * @return 包含基础 Header 的 HttpHeaders 实例
     */
    public HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (projectId != null) {
            headers.set(HEADER_PROJECT_ID, projectId);
        }
        return headers;
    }
}
