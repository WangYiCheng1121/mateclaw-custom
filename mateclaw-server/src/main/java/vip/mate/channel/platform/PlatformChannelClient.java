package vip.mate.channel.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import vip.mate.auth.config.PlatformOAuth2Config;
import vip.mate.auth.service.PlatformNacosService;
import vip.mate.auth.service.PlatformTokenHolder;
import vip.mate.skill.platform.PlatformResponse;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 平台端渠道远程调用客户端
 * <p>
 * 负责从平台端拉取渠道的启用/禁用状态。
 * 平台端固定4种渠道类型（weixin/qq/dingtalk/feishu），只返回各渠道的启停状态。
 * 客户端根据平台返回的状态决定是否允许启动对应渠道的通讯。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class PlatformChannelClient {

    private final PlatformChannelProperties syncProperties;
    private final PlatformOAuth2Config platformConfig;
    private final PlatformNacosService nacosService;
    private final PlatformTokenHolder tokenHolder;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PlatformChannelClient(PlatformChannelProperties syncProperties,
                                 PlatformOAuth2Config platformConfig,
                                 PlatformNacosService nacosService,
                                 PlatformTokenHolder tokenHolder,
                                 RestTemplateBuilder restTemplateBuilder,
                                 ObjectMapper objectMapper) {
        this.syncProperties = syncProperties;
        this.platformConfig = platformConfig;
        this.nacosService = nacosService;
        this.tokenHolder = tokenHolder;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofMillis(syncProperties.getConnectTimeout()))
                .readTimeout(Duration.ofMillis(syncProperties.getReadTimeout()))
                .build();
    }

    /**
     * 从平台端拉取渠道启用状态列表
     * <p>
     * 返回格式：List of ChannelStatusDTO（channelType + enabled）
     *
     * @return 平台端返回的渠道状态列表；失败时返回空列表
     */
    public List<ChannelStatusDTO> fetchChannelStatus() {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            log.debug("Platform channel sync disabled, skip fetch");
            return Collections.emptyList();
        }

        String url = buildUrl(syncProperties.getChannelStatusPath());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            applyAuth(headers);

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                PlatformResponse<List<ChannelStatusDTO>> platformResp = objectMapper.readValue(
                        response.getBody(),
                        new TypeReference<PlatformResponse<List<ChannelStatusDTO>>>() {});

                if (!platformResp.isSuccess()) {
                    log.warn("Platform returned business error: code={}, message={}",
                            platformResp.getCode(), platformResp.getMessage());
                    return Collections.emptyList();
                }

                List<ChannelStatusDTO> statuses = platformResp.getData();
                if (statuses == null) {
                    statuses = Collections.emptyList();
                }
                log.debug("Fetched {} channel statuses from platform", statuses.size());
                return statuses;
            } else {
                log.warn("Platform returned non-success HTTP status: {}", response.getStatusCode());
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.error("Failed to fetch channel status from platform [{}]: {}",
                    url, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 检查平台端连通性
     */
    public boolean isReachable() {
        try {
            String url = buildUrl("/actuator/health");
            HttpHeaders headers = new HttpHeaders();
            applyAuth(headers);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("Platform unreachable: {}", e.getMessage());
            return false;
        }
    }

    private void applyAuth(HttpHeaders headers) {
        String token = tokenHolder.getAccessToken();
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
    }

    private String buildUrl(String path) {
        String baseUrl = nacosService.resolveServiceUrl(syncProperties.getServiceId());
        if (baseUrl == null) {
            throw new IllegalStateException(
                    "[ChannelSync] 无法发现服务 [" + syncProperties.getServiceId() + "]，请检查 Nacos 注册中心中该服务是否已注册且健康");
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path;
    }

    /**
     * 平台端渠道状态 DTO
     * <p>
     * 只包含渠道类型和启用状态，不包含配置信息（配置由客户端本地管理）
     */
    @lombok.Data
    public static class ChannelStatusDTO {
        /** 渠道类型：weixin / qq / dingtalk / feishu */
        private String channelType;
        /** 渠道名称（平台端展示用） */
        private String name;
        /** 是否启用 */
        private boolean enabled;
    }
}
