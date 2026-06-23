package vip.mate.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import vip.mate.auth.config.PlatformOAuth2Config;
import vip.mate.exception.MateClawException;

import java.util.HashMap;
import java.util.Map;

/**
 * 平台端登录认证服务
 * <p>
 * 通过内部登录接口直接获取 access_token：
 * POST /v1/user/claw/login → 返回 accessToken（免验证码）
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformOAuth2Service {

    private final PlatformOAuth2Config config;
    private final ObjectMapper objectMapper;
    private final PlatformNacosService nacosService;

    /**
     * 平台 OAuth2 认证结果
     */
    @Data
    public static class PlatformAuthResult {
        private String accessToken;
        private String tokenType;
    }

    /**
     * 执行平台登录认证（一步到位，直接返回 accessToken）
     */
    public PlatformAuthResult authenticate(String username, String password) {
        if (!config.isEnabled()) {
            return null;
        }

        String gatewayUrl = nacosService.resolveGatewayUrl();
        log.info("[PlatformOAuth2] 开始平台认证: username={}, gateway={}", username, gatewayUrl);

        RestTemplate restTemplate = new RestTemplate();

        try {
            // 构建 JSON 请求体
            Map<String, Object> body = new HashMap<>();
            body.put("username", username);
            body.put("password", password);
            body.put("code", "");
            body.put("autoLogin", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> loginResponse = restTemplate.exchange(
                    gatewayUrl + config.getLoginPath(),
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);

            log.debug("[PlatformOAuth2] 登录响应: status={}, body={}",
                    loginResponse.getStatusCode(),
                    loginResponse.getBody());

            if (loginResponse.getStatusCode() != HttpStatus.OK) {
                log.error("[PlatformOAuth2] 平台登录HTTP失败: status={}", loginResponse.getStatusCode());
                throw new MateClawException("err.auth.platform_login_failed", "平台认证失败，用户名或密码错误");
            }

            // 检查响应体中的业务码
            String loginBody = loginResponse.getBody();
            if (loginBody == null) {
                throw new MateClawException("err.auth.platform_login_failed", "平台认证失败，响应为空");
            }

            JsonNode loginJson = objectMapper.readTree(loginBody);
            JsonNode codeNode = loginJson.path("code");
            int bizCode = codeNode.asInt();
            // 平台端 code=0 或 code=200 都表示成功
            if (codeNode.isNumber() && bizCode != 0 && bizCode != 200) {
                String serverMsg = loginJson.path("message").asText("未知错误");
                log.error("[PlatformOAuth2] 平台登录业务失败: code={}, message={}", bizCode, serverMsg);
                throw new MateClawException("err.auth.platform_login_failed", "平台认证失败: " + serverMsg);
            }

            // 从 data 中提取 accessToken
            JsonNode dataNode = loginJson.path("data");
            String accessToken = dataNode.path("accessToken").asText(null);
            String tokenType = dataNode.path("tokenType").asText("bearer");

            if (accessToken == null || accessToken.isEmpty()) {
                log.error("[PlatformOAuth2] Token为空: response={}", loginBody);
                throw new MateClawException("err.auth.platform_token_failed", "平台认证失败，无法获取访问令牌");
            }

            log.info("[PlatformOAuth2] 平台认证成功: username={}", username);

            PlatformAuthResult result = new PlatformAuthResult();
            result.setAccessToken(accessToken);
            result.setTokenType(tokenType);
            return result;

        } catch (MateClawException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PlatformOAuth2] 平台认证异常: {}", e.getMessage(), e);
            throw new MateClawException("err.auth.platform_error", "平台认证服务不可用: " + e.getMessage());
        }
    }
}
