package vip.mate.skill.platform;

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
import vip.mate.llm.platform.PlatformMachineTokenProvider;
import vip.mate.skill.model.SkillEntity;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 平台端技能远程调用客户端
 * <p>
 * 负责从平台端拉取当前客户端被授权使用的技能列表。
 * 技能的增删改全部在平台端完成，客户端只做"拉取 + 本地缓存"。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class PlatformSkillClient {

    private final PlatformSkillProperties syncProperties;
    private final PlatformOAuth2Config platformConfig;
    private final PlatformNacosService nacosService;
    private final PlatformHeaderBuilder headerBuilder;
    private final PlatformTokenHolder tokenHolder;
    private final PlatformMachineTokenProvider machineTokenProvider;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PlatformSkillClient(PlatformSkillProperties syncProperties,
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

    /**
     * 从平台端拉取当前客户端被分配（授权）的技能列表
     *
     * @return 平台端返回的技能实体列表；失败时返回空列表
     */
    public List<SkillEntity> fetchAssignedSkills() {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            log.debug("Platform skill sync disabled, skip fetch");
            return Collections.emptyList();
        }

        String url = buildUrl(syncProperties.getAssignedSkillsPath());

        try {
            HttpHeaders headers = headerBuilder.buildHeaders();
            applyAuth(headers);

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                // 用 PlatformResponse 强类型解析（兼容 code=String/int, msg/message）
                PlatformResponse<List<SkillEntity>> platformResp = objectMapper.readValue(
                        response.getBody(),
                        new TypeReference<PlatformResponse<List<SkillEntity>>>() {});

                if (!platformResp.isSuccess()) {
                    log.warn("Platform returned business error: code={}, message={}",
                            platformResp.getCode(), platformResp.getMessage());
                    return Collections.emptyList();
                }

                List<SkillEntity> skills = platformResp.getData();
                if (skills == null) {
                    skills = Collections.emptyList();
                }
                log.info("Fetched {} assigned skills from platform", skills.size());
                return skills;
            } else {
                log.warn("Platform returned non-success HTTP status: {}", response.getStatusCode());
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.error("Failed to fetch assigned skills from platform [{}]: {}",
                    url, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 从平台端拉取单个技能详情
     *
     * @param platformSkillId 平台端的技能 ID
     * @return 技能实体，失败时返回 null
     */
    public SkillEntity fetchSkillDetail(Long platformSkillId) {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            return null;
        }

        String path = syncProperties.getSkillDetailPath()
                .replace("{id}", platformSkillId.toString());
        String url = buildUrl(path);

        try {
            HttpHeaders headers = headerBuilder.buildHeaders();
            applyAuth(headers);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                PlatformResponse<SkillEntity> platformResp = objectMapper.readValue(
                        response.getBody(),
                        new TypeReference<PlatformResponse<SkillEntity>>() {});

                if (platformResp.isSuccess()) {
                    return platformResp.getData();
                }
                log.warn("Platform returned error for skill [id={}]: {}",
                        platformSkillId, platformResp.getMessage());
            }
        } catch (Exception e) {
            log.error("Failed to fetch skill detail [id={}]: {}", platformSkillId, e.getMessage());
        }
        return null;
    }

    /**
     * 检查平台端连通性
     */
    public boolean isReachable() {
        try {
            String url = buildUrl("/actuator/health");
            HttpHeaders headers = headerBuilder.buildHeaders();
            applyAuth(headers);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("Platform unreachable: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从平台端获取技能目录树
     *
     * @return 目录树根节点，失败时返回 null
     */
    public PlatformTreeNode fetchSkillCategories() {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            return null;
        }

        String url = buildUrl(syncProperties.getCategoriesPath());

        try {
            HttpHeaders headers = headerBuilder.buildHeaders();
            applyAuth(headers);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                PlatformResponse<PlatformTreeNode> platformResp = objectMapper.readValue(
                        response.getBody(),
                        new TypeReference<PlatformResponse<PlatformTreeNode>>() {});

                if (platformResp.isSuccess()) {
                    return platformResp.getData();
                }
                log.warn("Platform returned error for skill categories: {}",
                        platformResp.getMessage());
            }
        } catch (Exception e) {
            log.error("Failed to fetch skill categories from platform [{}]: {}",
                    url, e.getMessage());
        }
        return null;
    }

    /**
     * 从平台端模糊搜索技能目录
     *
     * @param name 目录名称关键字（可选）
     * @return 匹配的目录节点列表，失败时返回空列表
     */
    public List<PlatformTreeNode> searchSkillCategories(String name) {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            return Collections.emptyList();
        }

        String url = buildUrl(syncProperties.getCategoriesSearchPath());
        if (name != null && !name.isBlank()) {
            url += "?name=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
        }

        try {
            HttpHeaders headers = headerBuilder.buildHeaders();
            applyAuth(headers);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                PlatformResponse<List<PlatformTreeNode>> platformResp = objectMapper.readValue(
                        response.getBody(),
                        new TypeReference<PlatformResponse<List<PlatformTreeNode>>>() {});

                if (platformResp.isSuccess() && platformResp.getData() != null) {
                    return platformResp.getData();
                }
                log.warn("Platform returned error for skill categories search: {}",
                        platformResp.getMessage());
            }
        } catch (Exception e) {
            log.error("Failed to search skill categories from platform [{}]: {}",
                    url, e.getMessage());
        }
        return Collections.emptyList();
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
                    "[SkillSync] 无法发现服务 [" + syncProperties.getServiceId() + "]，请检查 Nacos 注册中心中该服务是否已注册且健康");
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path;
    }
}
