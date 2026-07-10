package vip.mate.agent.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * Platform knowledge base catalog client.
 * <p>
 * Fetches the list of knowledge bases available to this client from the
 * platform, used by the assistant editor's knowledge-base binding picker.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class PlatformKnowledgeBaseClient {

    private static final String SERVICE_ID = "ai-manage";
    private static final String CATALOG_PATH = "/api/knowledge-bases/catalog";

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    private final PlatformOAuth2Config platformConfig;
    private final PlatformNacosService nacosService;
    private final PlatformTokenHolder tokenHolder;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PlatformKnowledgeBaseClient(PlatformOAuth2Config platformConfig,
                                       PlatformNacosService nacosService,
                                       PlatformTokenHolder tokenHolder,
                                       RestTemplateBuilder restTemplateBuilder,
                                       ObjectMapper objectMapper) {
        this.platformConfig = platformConfig;
        this.nacosService = nacosService;
        this.tokenHolder = tokenHolder;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .readTimeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .build();
    }

    /**
     * Fetch knowledge base catalog from platform.
     *
     * @param keyword optional name search filter
     * @return catalog item list; empty list on failure
     */
    public List<KbCatalogItem> fetchCatalog(String keyword) {
        if (!platformConfig.isEnabled()) {
            log.debug("Platform config disabled, skip KB catalog fetch");
            return Collections.emptyList();
        }

        String url = buildUrl(CATALOG_PATH);
        if (keyword != null && !keyword.isBlank()) {
            url += "?keyword=" + java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            applyAuth(headers);

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                // The catalog may be wrapped in a paginated envelope or a plain list.
                // Try paginated envelope first, then fall back to plain list.
                try {
                    PlatformResponse<KbCatalogPage> pageResp = objectMapper.readValue(
                            response.getBody(),
                            new TypeReference<PlatformResponse<KbCatalogPage>>() {});
                    if (pageResp.isSuccess() && pageResp.getData() != null
                            && pageResp.getData().getItems() != null) {
                        return pageResp.getData().getItems();
                    }
                } catch (Exception ignored) {
                    // fall through to plain-list parse
                }
                PlatformResponse<List<KbCatalogItem>> listResp = objectMapper.readValue(
                        response.getBody(),
                        new TypeReference<PlatformResponse<List<KbCatalogItem>>>() {});
                if (listResp.isSuccess() && listResp.getData() != null) {
                    return listResp.getData();
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch KB catalog from platform [{}]: {}", url, e.getMessage());
        }
        return Collections.emptyList();
    }

    private void applyAuth(HttpHeaders headers) {
        String token = tokenHolder.getAccessToken();
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
    }

    private String buildUrl(String path) {
        String baseUrl = nacosService.resolveServiceUrl(SERVICE_ID);
        if (baseUrl == null) {
            throw new IllegalStateException(
                    "[KbCatalog] Cannot resolve service [" + SERVICE_ID + "]");
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path;
    }

    /** A single knowledge base entry from the platform catalog. */
    @Data
    public static class KbCatalogItem {
        private String kbRefId;
        private String kbName;
        private String description;
        private Integer documentCount;
        private String status;
        private String createTime;
    }

    /** Paginated envelope for KB catalog. */
    @Data
    public static class KbCatalogPage {
        private Integer total;
        private List<KbCatalogItem> items;
    }
}
