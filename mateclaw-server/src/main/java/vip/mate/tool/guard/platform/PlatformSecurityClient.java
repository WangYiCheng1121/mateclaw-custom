package vip.mate.tool.guard.platform;

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
import vip.mate.llm.platform.PlatformMachineTokenProvider;
import vip.mate.skill.platform.PlatformResponse;
import vip.mate.tool.guard.model.ToolGuardAuditLogEntity;
import vip.mate.tool.guard.model.ToolGuardConfigEntity;
import vip.mate.tool.guard.model.ToolGuardRuleEntity;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台端安全模块 HTTP 客户端
 * <p>
 * 负责：
 * <ul>
 *   <li>从平台端拉取安全配置（ToolGuardConfig）</li>
 *   <li>从平台端拉取安全规则列表（ToolGuardRule）</li>
 *   <li>向平台端推送审计日志（ToolGuardAuditLog）</li>
 *   <li>向平台端推送审批记录 + 状态更新（ToolApproval）</li>
 * </ul>
 * <p>
 * 所有调用均静默失败（仅打 debug/warn 日志），不中断客户端主流程。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class PlatformSecurityClient {

    private final PlatformSecurityProperties securityProperties;
    private final PlatformOAuth2Config platformConfig;
    private final PlatformNacosService nacosService;
    private final PlatformTokenHolder tokenHolder;
    private final PlatformMachineTokenProvider machineTokenProvider;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PlatformSecurityClient(PlatformSecurityProperties securityProperties,
                                   PlatformOAuth2Config platformConfig,
                                   PlatformNacosService nacosService,
                                   PlatformTokenHolder tokenHolder,
                                   PlatformMachineTokenProvider machineTokenProvider,
                                   RestTemplateBuilder restTemplateBuilder,
                                   ObjectMapper objectMapper) {
        this.securityProperties = securityProperties;
        this.platformConfig = platformConfig;
        this.nacosService = nacosService;
        this.tokenHolder = tokenHolder;
        this.machineTokenProvider = machineTokenProvider;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofMillis(securityProperties.getConnectTimeout()))
                .readTimeout(Duration.ofMillis(securityProperties.getReadTimeout()))
                .build();
    }

    // ==================== 配置拉取 ====================

    /**
     * 从平台端拉取当前安全配置
     */
    public ToolGuardConfigEntity fetchConfig() {
        if (!isEnabled()) return null;
        String url = buildUrl(securityProperties.getConfigPath());
        try {
            HttpHeaders headers = buildAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                PlatformResponse<ToolGuardConfigEntity> pr = objectMapper.readValue(
                        response.getBody(), new TypeReference<PlatformResponse<ToolGuardConfigEntity>>() {});
                if (pr.isSuccess()) return pr.getData();
                log.warn("[PlatformSecurity] fetchConfig error: code={}, msg={}", pr.getCode(), pr.getMessage());
            }
        } catch (Exception e) {
            log.warn("[PlatformSecurity] fetchConfig failed: {}", e.getMessage());
        }
        return null;
    }

    // ==================== 规则拉取 ====================

    /**
     * 从平台端拉取全部安全规则
     */
    public List<ToolGuardRuleEntity> fetchRules() {
        if (!isEnabled()) return Collections.emptyList();
        String url = buildUrl(securityProperties.getRulesPath());
        try {
            HttpHeaders headers = buildAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                PlatformResponse<List<ToolGuardRuleEntity>> pr = objectMapper.readValue(
                        response.getBody(), new TypeReference<PlatformResponse<List<ToolGuardRuleEntity>>>() {});
                if (pr.isSuccess() && pr.getData() != null) return pr.getData();
                log.warn("[PlatformSecurity] fetchRules error: code={}, msg={}", pr.getCode(), pr.getMessage());
            }
        } catch (Exception e) {
            log.warn("[PlatformSecurity] fetchRules failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    // ==================== 审计日志推送 ====================

    /**
     * 向平台端推送单条审计日志
     */
    public boolean pushAuditLog(ToolGuardAuditLogEntity entity) {
        return postJson(securityProperties.getAuditPath(), entity);
    }

    // ==================== 审批记录推送 ====================

    /**
     * 向平台端推送审批记录（创建）
     */
    public boolean pushApproval(Map<String, Object> payload) {
        return postJson(securityProperties.getApprovalPath(), payload);
    }

    /**
     * 向平台端推送审批状态更新
     *
     * @param pendingId  审批唯一标识
     * @param status     新状态（APPROVED/DENIED/TIMEOUT/SUPERSEDED/CONSUMED）
     * @param resolvedBy 审批人 ID（可为 null）
     * @param userId     审批发起人 ID（可为 null）
     */
    public boolean pushApprovalStatus(String pendingId, String status, String resolvedBy, String userId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pendingId", pendingId);
        payload.put("status", status);
        if (resolvedBy != null) {
            payload.put("resolvedBy", resolvedBy);
        }
        if (userId != null) {
            payload.put("userId", userId);
        }
        return postJson(securityProperties.getApprovalStatusPath(), payload);
    }

    // ==================== 内部方法 ====================

    @SuppressWarnings("unchecked")
    private boolean postJson(String path, Object payload) {
        if (!isEnabled()) return false;
        String url = buildUrl(path);
        try {
            HttpHeaders headers = buildAuthHeaders();
            String json = objectMapper.writeValueAsString(payload);
            HttpEntity<String> httpEntity = new HttpEntity<>(json, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, httpEntity, String.class);
            if (response.getStatusCode().is2xxSuccessful()) return true;
            log.debug("[PlatformSecurity] POST {} failed: status={}", path, response.getStatusCode());
        } catch (Exception e) {
            log.debug("[PlatformSecurity] POST {} error: {}", path, e.getMessage());
        }
        return false;
    }

    private boolean isEnabled() {
        return securityProperties.isEnabled() && platformConfig.isEnabled();
    }

    private HttpHeaders buildAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String token = tokenHolder.getAccessToken();
        if (token == null && machineTokenProvider != null) {
            token = machineTokenProvider.getAccessToken();
        }
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return headers;
    }

    private String buildUrl(String path) {
        String baseUrl = nacosService.resolveServiceUrl(securityProperties.getServiceId());
        if (baseUrl == null) baseUrl = platformConfig.getGatewayUrl();
        if (baseUrl == null) {
            throw new IllegalStateException(
                    "[PlatformSecurity] 无法发现服务 [" + securityProperties.getServiceId() + "] 且无可用网关地址");
        }
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        return baseUrl + path;
    }
}
