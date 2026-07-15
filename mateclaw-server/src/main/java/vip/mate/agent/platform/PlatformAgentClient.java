package vip.mate.agent.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import vip.mate.agent.model.TemplateDTO;
import vip.mate.auth.config.PlatformOAuth2Config;
import vip.mate.auth.service.PlatformNacosService;
import vip.mate.auth.service.PlatformTokenHolder;
import vip.mate.llm.platform.PlatformMachineTokenProvider;
import vip.mate.skill.platform.PlatformResponse;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Platform preset agent remote client.
 * <p>
 * Fetches preset assistant templates from the platform on demand (when the
 * user opens the "New Assistant" dialog). No local sync — the preset list
 * is always fresh from the platform at the moment of creation.
 * <p>
 * Features:
 * - Configurable timeouts
 * - Automatic retry on network errors (max 3 attempts)
 * - Detailed error classification (UNREACHABLE, AUTH_ERROR, BUSINESS_ERROR, PARSE_ERROR)
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class PlatformAgentClient {

    private static final String SERVICE_ID = "ai-manage";
    private static final String PRESETS_LIST_PATH = "/api/agents/presets";
    private static final String PRESETS_DETAIL_PATH = "/api/agents/presets/{presetId}";

    /** 重试配置 */
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000;
    private static final long MAX_RETRY_DELAY_MS = 5000;

    private final PlatformOAuth2Config platformConfig;
    private final PlatformNacosService nacosService;
    private final PlatformTokenHolder tokenHolder;
    private final PlatformMachineTokenProvider machineTokenProvider;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PlatformAgentClient(PlatformOAuth2Config platformConfig,
                               PlatformNacosService nacosService,
                               PlatformTokenHolder tokenHolder,
                               PlatformMachineTokenProvider machineTokenProvider,
                               RestTemplateBuilder restTemplateBuilder,
                               ObjectMapper objectMapper) {
        this.platformConfig = platformConfig;
        this.nacosService = nacosService;
        this.tokenHolder = tokenHolder;
        this.machineTokenProvider = machineTokenProvider;
        this.objectMapper = objectMapper;

        // 使用配置的超时时间，如果未配置则使用默认值
        int connectTimeout = platformConfig.getConnectTimeout() > 0 ? platformConfig.getConnectTimeout() : 10_000;
        int readTimeout = platformConfig.getReadTimeout() > 0 ? platformConfig.getReadTimeout() : 30_000;

        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .readTimeout(Duration.ofMillis(readTimeout))
                .build();

        log.info("[PlatformAgentClient] Initialized with connectTimeout={}ms, readTimeout={}ms",
                connectTimeout, readTimeout);
    }

    /**
     * Fetch the list of preset assistant templates assigned to this client.
     *
     * @param keyword optional name/description search filter
     * @return preset template list
     * @throws PlatformServiceException on platform errors (unreachable, auth error, etc.)
     */
    public List<TemplateDTO> fetchPresets(String keyword) {
        if (!platformConfig.isEnabled()) {
            log.debug("Platform config disabled, skip preset fetch");
            return Collections.emptyList();
        }

        String baseUrl = buildUrl(PRESETS_LIST_PATH);
        String url = (keyword != null && !keyword.isBlank())
                ? baseUrl + "?keyword=" + java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8)
                : baseUrl;

        return executeWithRetry(() -> doFetchPresets(url), "fetchPresets");
    }

    private List<TemplateDTO> doFetchPresets(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        applyAuth(headers);

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, String.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            PlatformResponse<List<TemplateDTO>> platformResp;
            try {
                platformResp = objectMapper.readValue(
                        response.getBody(),
                        new TypeReference<PlatformResponse<List<TemplateDTO>>>() {});
            } catch (Exception e) {
                throw new PlatformServiceException(
                        PlatformServiceException.ErrorType.PARSE_ERROR,
                        "Failed to parse platform response: " + e.getMessage(), e);
            }

            if (!platformResp.isSuccess()) {
                throw new PlatformServiceException(
                        PlatformServiceException.ErrorType.BUSINESS_ERROR,
                        "Platform returned business error: code=" + platformResp.getCode()
                                + ", message=" + platformResp.getMessage());
            }

            List<TemplateDTO> presets = platformResp.getData();
            if (presets == null) {
                presets = Collections.emptyList();
            }
            log.debug("Fetched {} preset agents from platform", presets.size());
            return presets;
        }

        throw new PlatformServiceException(
                PlatformServiceException.ErrorType.BUSINESS_ERROR,
                "Platform returned non-success status: " + response.getStatusCode());
    }

    /**
     * Fetch a single preset assistant template detail by ID.
     *
     * @param presetId platform preset ID
     * @return template detail; null if not found
     * @throws PlatformServiceException on platform errors (unreachable, auth error, etc.)
     */
    public TemplateDTO fetchPresetDetail(String presetId) {
        if (!platformConfig.isEnabled() || presetId == null || presetId.isBlank()) {
            return null;
        }

        String path = PRESETS_DETAIL_PATH.replace("{presetId}", presetId);
        String url = buildUrl(path);

        return executeWithRetry(() -> doFetchPresetDetail(url, presetId), "fetchPresetDetail");
    }

    private TemplateDTO doFetchPresetDetail(String url, String presetId) {
        HttpHeaders headers = new HttpHeaders();
        applyAuth(headers);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, String.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            PlatformResponse<TemplateDTO> platformResp;
            try {
                platformResp = objectMapper.readValue(
                        response.getBody(),
                        new TypeReference<PlatformResponse<TemplateDTO>>() {});
            } catch (Exception e) {
                throw new PlatformServiceException(
                        PlatformServiceException.ErrorType.PARSE_ERROR,
                        "Failed to parse platform response for preset " + presetId + ": " + e.getMessage(), e);
            }

            if (platformResp.isSuccess()) {
                return platformResp.getData();
            }

            throw new PlatformServiceException(
                    PlatformServiceException.ErrorType.BUSINESS_ERROR,
                    "Platform returned error for preset " + presetId + ": " + platformResp.getMessage());
        }

        throw new PlatformServiceException(
                PlatformServiceException.ErrorType.BUSINESS_ERROR,
                "Platform returned non-success status for preset " + presetId + ": " + response.getStatusCode());
    }

    /**
     * 带重试的执行器
     */
    private <T> T executeWithRetry(PlatformCallable<T> callable, String operation) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                return callable.call();
            } catch (PlatformServiceException e) {
                lastException = e;
                // 业务错误和解析错误不重试
                if (e.getErrorType() == PlatformServiceException.ErrorType.BUSINESS_ERROR
                        || e.getErrorType() == PlatformServiceException.ErrorType.PARSE_ERROR) {
                    throw e;
                }
                // 认证错误不重试，但需要标记
                if (e.getErrorType() == PlatformServiceException.ErrorType.AUTH_ERROR) {
                    throw e;
                }
                // 网络错误可以重试
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    long delay = Math.min(RETRY_DELAY_MS * attempt, MAX_RETRY_DELAY_MS);
                    log.warn("[PlatformAgentClient] {} failed (attempt {}/{}), retrying in {}ms: {}",
                            operation, attempt, MAX_RETRY_ATTEMPTS, delay, e.getMessage());
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new PlatformServiceException(
                                PlatformServiceException.ErrorType.UNREACHABLE,
                                "Operation interrupted", ie);
                    }
                }
            } catch (ResourceAccessException e) {
                lastException = e;
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    long delay = Math.min(RETRY_DELAY_MS * attempt, MAX_RETRY_DELAY_MS);
                    log.warn("[PlatformAgentClient] {} failed (attempt {}/{}), retrying in {}ms: {}",
                            operation, attempt, MAX_RETRY_ATTEMPTS, delay, e.getMessage());
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new PlatformServiceException(
                                PlatformServiceException.ErrorType.UNREACHABLE,
                                "Operation interrupted", ie);
                    }
                }
            } catch (Exception e) {
                lastException = e;
                // 未知异常不重试
                throw new PlatformServiceException(
                        PlatformServiceException.ErrorType.UNREACHABLE,
                        "Unexpected error during " + operation + ": " + e.getMessage(), e);
            }
        }

        // 所有重试都失败了
        if (lastException instanceof PlatformServiceException) {
            throw (PlatformServiceException) lastException;
        }
        throw new PlatformServiceException(
                PlatformServiceException.ErrorType.UNREACHABLE,
                operation + " failed after " + MAX_RETRY_ATTEMPTS + " attempts: " + lastException.getMessage(),
                lastException);
    }

    private void applyAuth(HttpHeaders headers) {
        // 优先用户 token（authorization_code），降级机器 token（client_credentials）
        String token = tokenHolder.getAccessToken();
        if (token == null && machineTokenProvider != null) {
            token = machineTokenProvider.getAccessToken();
        }
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        } else {
            log.warn("[PlatformAgentClient] No valid access token available (neither user nor machine)");
        }
    }

    private String buildUrl(String path) {
        String baseUrl = nacosService.resolveServiceUrl(SERVICE_ID);
        if (baseUrl == null) {
            throw new PlatformServiceException(
                    PlatformServiceException.ErrorType.UNREACHABLE,
                    "Cannot resolve service [" + SERVICE_ID + "]");
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path;
    }

    /**
     * 平台调用接口
     */
    @FunctionalInterface
    private interface PlatformCallable<T> {
        T call() throws Exception;
    }
}
