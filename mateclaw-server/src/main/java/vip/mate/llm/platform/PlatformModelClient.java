package vip.mate.llm.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import vip.mate.auth.config.PlatformOAuth2Config;
import vip.mate.auth.service.PlatformHeaderBuilder;
import vip.mate.auth.service.PlatformNacosService;
import vip.mate.auth.service.PlatformTokenHolder;
import vip.mate.skill.platform.PlatformResponse;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class PlatformModelClient {

    private final PlatformModelProperties syncProperties;
    private final PlatformOAuth2Config platformConfig;
    private final PlatformNacosService nacosService;
    private final PlatformHeaderBuilder headerBuilder;
    private final PlatformTokenHolder tokenHolder;
    private final PlatformMachineTokenProvider machineTokenProvider;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PlatformModelClient(PlatformModelProperties syncProperties,
                               PlatformOAuth2Config platformConfig,
                               PlatformNacosService nacosService,
                               PlatformHeaderBuilder headerBuilder,
                               PlatformTokenHolder tokenHolder,
                               PlatformMachineTokenProvider machineTokenProvider,
                               RestTemplateBuilder restTemplateBuilder,
                               ObjectMapper objectMapper) {
        this.syncProperties = syncProperties;
        this.platformConfig = platformConfig;
        this.nacosService = nacosService;
        this.headerBuilder = headerBuilder;
        this.tokenHolder = tokenHolder;
        this.machineTokenProvider = machineTokenProvider;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofMillis(syncProperties.getConnectTimeout()))
                .readTimeout(Duration.ofMillis(syncProperties.getReadTimeout()))
                .build();
    }

    public AssignedModelsResponse fetchAssignedModels() {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            log.debug("Platform model sync disabled, skip fetch");
            return null;
        }

        String url = buildUrl(syncProperties.getAssignedModelsPath());

        try {
            HttpHeaders headers = headerBuilder.buildHeaders();
            applyAuth(headers);

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                PlatformResponse<AssignedModelsResponse> platformResp = objectMapper.readValue(
                        response.getBody(),
                        new TypeReference<PlatformResponse<AssignedModelsResponse>>() {});

                if (!platformResp.isSuccess()) {
                    log.warn("Platform returned business error for models: code={}, message={}",
                            platformResp.getCode(), platformResp.getMessage());
                    return null;
                }

                AssignedModelsResponse data = platformResp.getData();
                if (data != null) {
                    log.info("Fetched {} providers, {} models from platform",
                            data.getProviders() != null ? data.getProviders().size() : 0,
                            data.getModels() != null ? data.getModels().size() : 0);
                }
                return data;
            } else {
                log.warn("Platform returned non-success HTTP status for models: {}", response.getStatusCode());
                return null;
            }
        } catch (Exception e) {
            log.error("Failed to fetch assigned models from platform [{}]", url, e);
            return null;
        }
    }

    public String fetchDefaultEmbeddingModelId() {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            return null;
        }

        String url = buildUrl(syncProperties.getEmbeddingDefaultPath());

        try {
            HttpHeaders headers = headerBuilder.buildHeaders();
            applyAuth(headers);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                PlatformResponse<Map<String, Object>> platformResp = objectMapper.readValue(
                        response.getBody(),
                        new TypeReference<PlatformResponse<Map<String, Object>>>() {});

                if (platformResp.isSuccess() && platformResp.getData() != null) {
                    Object v = platformResp.getData().get("defaultModelId");
                    return v != null ? v.toString() : null;
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch default embedding from platform [{}]: {}",
                    url, e.getMessage());
        }
        return null;
    }

    public boolean isReachable() {
        try {
            String url = buildUrl("/actuator/health");
            HttpHeaders headers = headerBuilder.buildHeaders();
            applyAuth(headers);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("Platform unreachable: {}", e.toString());
            return false;
        }
    }

    private void applyAuth(HttpHeaders headers) {
        // 优先用户 token，降级机器 token（client_credentials）
        String token = tokenHolder.getAccessToken();
        if (token == null && machineTokenProvider != null) {
            token = machineTokenProvider.getAccessToken();
        }
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
    }

    private String buildUrl(String path) {
        String baseUrl = nacosService.resolveServiceUrl(syncProperties.getServiceId());
        if (baseUrl == null) {
            throw new IllegalStateException(
                    "[ModelSync] 无法发现服务 [" + syncProperties.getServiceId() + "]，请检查 Nacos 注册中心中该服务是否已注册且健康");
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path;
    }

    @lombok.Data
    public static class AssignedModelsResponse {
        private List<SyncProviderItem> providers;
        private List<ModelConfigVO> models;
        private String defaultProvider;
        private String defaultModelName;
        private String defaultEmbeddingModelId;
    }
}
