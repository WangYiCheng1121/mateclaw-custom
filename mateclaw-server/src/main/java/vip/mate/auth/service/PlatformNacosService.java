package vip.mate.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.auth.config.PlatformOAuth2Config;

/**
 * 平台服务地址解析（静态配置模式）
 * <p>
 * 通过服务名解析对应的服务地址：
 * - esp-user 登录接口：直连网关根路径（/uni/login/system 等）
 * - ai-manage：技能同步、日志上报、模型同步、渠道同步（/ai-manage/...）
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformNacosService {

    private final PlatformOAuth2Config config;

    /**
     * 服务名到路径前缀的映射
     */
    private static final String AI_MANAGE_PATH = "/ai-manage";

    /**
     * 解析平台网关地址（用于 OAuth2 登录认证）
     * 直接返回网关根地址，登录接口直连网关（/uni/login/system）
     */
    public String resolveGatewayUrl() {
        return config.getGatewayUrl();
    }

    /**
     * 根据服务名解析服务地址
     * <p>
     * 支持的服务名：
     * - ai-manage：技能同步、日志上报、模型同步、渠道同步
     * - esp-user：OAuth2 登录认证（直连网关根路径，无需路径前缀）
     *
     * @param serviceName 服务名（如 ai-manage、esp-user）
     * @return 对应服务的完整地址
     */
    public String resolveServiceUrl(String serviceName) {
        String baseUrl = config.getGatewayUrl();
        String path;

        if ("ai-manage".equalsIgnoreCase(serviceName)) {
            path = AI_MANAGE_PATH;
        } else if ("esp-user".equalsIgnoreCase(serviceName)) {
            // esp-user 登录接口直连网关根路径，不需要路径前缀
            log.debug("[PlatformService] esp-user服务直连网关根路径");
            return baseUrl;
        } else {
            // 未知服务名，默认使用 ai-manage
            log.warn("[PlatformService] 未知服务名 [{}]，默认使用 ai-manage", serviceName);
            path = AI_MANAGE_PATH;
        }

        String fullUrl = baseUrl + path;
        log.debug("[PlatformService] 解析服务 [{}] → {}", serviceName, fullUrl);
        return fullUrl;
    }
}
