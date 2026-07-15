package vip.mate.log.platform;

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

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 平台端日志上报 HTTP 客户端
 * <p>
 * 负责将本地运行日志通过 HTTP POST 发送到平台端 /claw/logs 接口。
 * 平台端接口字段：
 * - level (必填): info / warn / error
 * - detail (必填): 详细信息
 * - logType (可选): 日志类型（runtime=运行日志, operation=操作日志）
 * - username (可选): 操作用户名
 * - logTime (可选): yyyy-MM-dd HH:mm:ss
 * - subsystem (可选): 子系统标识
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class PlatformLogClient {

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PlatformLogProperties logProperties;
    private final PlatformOAuth2Config platformConfig;
    private final PlatformNacosService nacosService;
    private final PlatformTokenHolder tokenHolder;
    private final PlatformMachineTokenProvider machineTokenProvider;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PlatformLogClient(PlatformLogProperties logProperties,
                             PlatformOAuth2Config platformConfig,
                             PlatformNacosService nacosService,
                             PlatformTokenHolder tokenHolder,
                             PlatformMachineTokenProvider machineTokenProvider,
                             RestTemplateBuilder restTemplateBuilder,
                             ObjectMapper objectMapper) {
        this.logProperties = logProperties;
        this.platformConfig = platformConfig;
        this.nacosService = nacosService;
        this.tokenHolder = tokenHolder;
        this.machineTokenProvider = machineTokenProvider;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofMillis(logProperties.getConnectTimeout()))
                .readTimeout(Duration.ofMillis(logProperties.getReadTimeout()))
                .build();
    }

    /**
     * 上报单条日志到平台端
     *
     * @param level   日志级别（info/warn/error）
     * @param detail  详细信息
     * @param logTime 日志发生时间
     * @param logType 日志类型（runtime=运行日志, operation=操作日志）
     * @return true=上报成功, false=上报失败
     */
    public boolean reportLog(String level, String detail, LocalDateTime logTime, String logType) {
        if (!logProperties.isEnabled() || !platformConfig.isEnabled()) {
            return false;
        }

        String url = buildUrl(logProperties.getReportPath());

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("level", level);
            body.put("detail", detail);
            body.put("subsystem", logProperties.getSubsystem());
            if (logType != null) {
                body.put("logType", logType);
            }
            if (logTime != null) {
                body.put("logTime", logTime.format(DATETIME_FMT));
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            applyAuth(headers);

            String json = objectMapper.writeValueAsString(body);
            HttpEntity<String> entity = new HttpEntity<>(json, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return true;
            } else {
                log.debug("[PlatformLog] Log report failed, HTTP status: {}", response.getStatusCode());
                return false;
            }
        } catch (Exception e) {
            log.debug("[PlatformLog] Log report error: {}", e.getMessage());
            return false;
        }
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
        String baseUrl = nacosService.resolveServiceUrl(logProperties.getServiceId());
        if (baseUrl == null) {
            // 降级到平台网关地址
            baseUrl = platformConfig.getGatewayUrl();
        }
        if (baseUrl == null) {
            throw new IllegalStateException(
                    "[PlatformLog] 无法发现服务 [" + logProperties.getServiceId() + "] 且无可用网关地址");
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path;
    }
}
