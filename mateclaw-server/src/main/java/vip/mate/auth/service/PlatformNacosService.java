package vip.mate.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.auth.config.PlatformOAuth2Config;
import vip.mate.config.DwIdModeConfig;

/**
 * 平台服务地址解析（静态配置模式 / 双模部署）
 * <p>
 * 通过服务名解析对应的服务地址。
 * <p>
 * <b>非 DW_ID 模式（默认）</b>：通过网关访问
 * <ul>
 *   <li>esp-user 登录接口：直连网关根路径（gatewayUrl/uni/login/system 等）</li>
 *   <li>ai-manage：gatewayUrl/ai-manage/...</li>
 * </ul>
 * <p>
 * <b>DW_ID 模式（集群内部直连）</b>：绕过网关，直连 ai-manage 服务。
 * 目标地址优先级：环境变量 AI_MANAGE_CLUSTER_URL > 默认 K8S Service DNS。
 * <ul>
 *   <li>K8S 模式：http://glsec-ai-manage:20008（默认值，K8S Service DNS）</li>
 *   <li>VPC 模式：http://127.0.0.1:{port}（通过 AI_MANAGE_CLUSTER_URL 覆盖）</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformNacosService {

    private final PlatformOAuth2Config config;
    private final DwIdModeConfig dwIdModeConfig;

    /**
     * 服务名到路径前缀的映射（非 DW_ID 模式，通过网关）
     */
    private static final String AI_MANAGE_PATH = "/ai-manage";

    /**
     * DW_ID 模式下 ai-manage 集群内直连默认地址（K8S Service DNS）。
     * 可通过环境变量 AI_MANAGE_CLUSTER_URL 覆盖（VPC 模式）。
     */
    private static final String DEFAULT_AI_MANAGE_CLUSTER_URL = "http://glsec-ai-manage:20008";

    /**
     * 集群内部 OAuth 服务地址（DW_ID 模式，K8s Service DNS）
     */
    private static final String UNI_URL_CLUSTER = "http://glsec-auth:50010";


    /**
     * 解析平台网关地址（用于 OAuth2 登录认证 / 机器令牌）
     * <p>
     * 非 DW_ID 模式：返回外网网关根地址
     * <br>DW_ID 模式：返回集群内部 uni 服务地址（http://uni）
     */
    public String resolveGatewayUrl() {
        if (dwIdModeConfig.isDwIdMode()) {
            return UNI_URL_CLUSTER;
        }
        return config.getGatewayUrl();
    }

    /**
     * 根据服务名解析服务地址
     * <p>
     * 支持的服务名：
     * - ai-manage：技能同步、日志上报、模型同步、渠道同步
     * - esp-user：OAuth2 登录认证（直连网关根路径，无需路径前缀）
     * <p>
     * DW_ID 模式下：
     * <ul>
     *   <li>ai-manage → http://glsec-ai-manage:20008（集群内部 K8s Service DNS）</li>
     *   <li>esp-user → ""（集群内部直连）</li>
     * </ul>
     *
     * @param serviceName 服务名（如 ai-manage、esp-user）
     * @return 对应服务的完整地址
     */
    public String resolveServiceUrl(String serviceName) {
        boolean dwIdMode = dwIdModeConfig.isDwIdMode();
        String baseUrl = dwIdMode ? "" : config.getGatewayUrl();
        String path;

        if ("ai-manage".equalsIgnoreCase(serviceName)) {
            path = dwIdMode ? resolveAiManageClusterUrl() : AI_MANAGE_PATH;
        } else if ("esp-user".equalsIgnoreCase(serviceName)) {
            log.debug("[PlatformService] esp-user服务直连网关根路径");
            return baseUrl;
        } else {
            log.warn("[PlatformService] 未知服务名 [{}]，默认使用 ai-manage", serviceName);
            path = dwIdMode ? resolveAiManageClusterUrl() : AI_MANAGE_PATH;
        }

        String fullUrl = baseUrl + path;
        log.debug("[PlatformService] 解析服务 [{}] → {} (dwIdMode={})", serviceName, fullUrl, dwIdMode);
        return fullUrl;
    }

    /**
     * 解析 ai-manage 集群内直连地址。
     * 优先读环境变量 AI_MANAGE_CLUSTER_URL（VPC 模式覆盖），兜底 K8S Service DNS。
     */
    private String resolveAiManageClusterUrl() {
        String env = System.getenv("AI_MANAGE_CLUSTER_URL");
        if (env != null && !env.isBlank()) {
            log.info("[PlatformService] AI_MANAGE_CLUSTER_URL from env: {}", env);
            return env;
        }
        return DEFAULT_AI_MANAGE_CLUSTER_URL;
    }
}
