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
import vip.mate.auth.service.PlatformHeaderBuilder;
import vip.mate.auth.service.PlatformNacosService;
import vip.mate.auth.service.PlatformTokenHolder;
import vip.mate.llm.platform.PlatformMachineTokenProvider;
import vip.mate.skill.platform.PlatformResponse;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Platform MCP catalog client.
 * <p>
 * Fetches the list of MCP servers available to this client from the
 * platform, used by the assistant editor's MCP binding picker.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class PlatformMcpClient {

    private static final String SERVICE_ID = "ai-manage";
    private static final String CATALOG_PATH = "/api/mcps/catalog";

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    private final PlatformOAuth2Config platformConfig;
    private final PlatformNacosService nacosService;
    private final PlatformHeaderBuilder headerBuilder;
    private final PlatformTokenHolder tokenHolder;
    private final PlatformMachineTokenProvider machineTokenProvider;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PlatformMcpClient(PlatformOAuth2Config platformConfig,
                             PlatformNacosService nacosService,
                             PlatformHeaderBuilder headerBuilder,
                             PlatformTokenHolder tokenHolder,
                             PlatformMachineTokenProvider machineTokenProvider,
                             RestTemplateBuilder restTemplateBuilder,
                             ObjectMapper objectMapper) {
        this.platformConfig = platformConfig;
        this.nacosService = nacosService;
        this.headerBuilder = headerBuilder;
        this.tokenHolder = tokenHolder;
        this.machineTokenProvider = machineTokenProvider;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .readTimeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .build();
    }

    /**
     * Fetch MCP catalog from platform.
     *
     * @param keyword optional name search filter
     * @return catalog item list; empty list on failure
     */
    public List<McpCatalogItem> fetchCatalog(String keyword) {
        if (!platformConfig.isEnabled()) {
            log.debug("Platform config disabled, skip MCP catalog fetch");
            return Collections.emptyList();
        }

        String url = buildUrl(CATALOG_PATH);
        if (keyword != null && !keyword.isBlank()) {
            url += "?keyword=" + java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8);
        }

        try {
            HttpHeaders headers = headerBuilder.buildHeaders();
            applyAuth(headers);

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                try {
                    PlatformResponse<McpCatalogPage> pageResp = objectMapper.readValue(
                            response.getBody(),
                            new TypeReference<PlatformResponse<McpCatalogPage>>() {});
                    if (pageResp.isSuccess() && pageResp.getData() != null
                            && pageResp.getData().getItems() != null) {
                        return pageResp.getData().getItems();
                    }
                } catch (Exception ignored) {
                    // fall through to plain-list parse
                }
                PlatformResponse<List<McpCatalogItem>> listResp = objectMapper.readValue(
                        response.getBody(),
                        new TypeReference<PlatformResponse<List<McpCatalogItem>>>() {});
                if (listResp.isSuccess() && listResp.getData() != null) {
                    return listResp.getData();
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch MCP catalog from platform [{}]: {}", url, e.getMessage());
        }
        return Collections.emptyList();
    }

    private void applyAuth(HttpHeaders headers) {
        String token = tokenHolder.getAccessToken();
        if (token == null && machineTokenProvider != null) {
            token = machineTokenProvider.getAccessToken();
        }
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
    }

    private String buildUrl(String path) {
        String baseUrl = nacosService.resolveServiceUrl(SERVICE_ID);
        if (baseUrl == null) {
            throw new IllegalStateException(
                    "[McpCatalog] Cannot resolve service [" + SERVICE_ID + "]");
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path;
    }

    /** A single MCP entry from the platform catalog. */
    @Data
    public static class McpCatalogItem {
        private Integer mcpRefId;
        private String mcpName;
        private String description;
        private String transport;
        private String status;
        private Integer toolCount;
        private String createTime;
    }

    /** Paginated envelope for MCP catalog. */
    @Data
    public static class McpCatalogPage {
        private Integer total;
        private List<McpCatalogItem> items;
    }
}
