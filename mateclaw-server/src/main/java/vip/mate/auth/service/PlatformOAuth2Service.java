package vip.mate.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;
import vip.mate.auth.config.PlatformOAuth2Config;
import vip.mate.exception.MateClawException;

import javax.crypto.Cipher;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * 平台端 OAuth2 认证服务
 * <p>
 * 复刻 esp-user-center 的 ClawLoginController 认证流程：
 * 1. POST /uni/login/system → 平台登录获取 Cookie
 * 2. GET  /uni/oauth/authorize → 获取授权码
 * 3. POST /uni/oauth/token → 用授权码换取 access_token
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
     * 执行平台 OAuth2 认证流程
     */
    public PlatformAuthResult authenticate(String username, String password) {
        if (!config.isEnabled()) {
            return null;
        }

        String gatewayUrl = nacosService.resolveGatewayUrl();
        log.info("[PlatformOAuth2] 开始平台认证: username={}, gateway={}", username, gatewayUrl);

        RestTemplate restTemplate = createRestTemplate();

        // 关键：清除全局 CookieHandler，防止 HttpURLConnection 吐掉 Set-Cookie 头
        CookieHandler previousHandler = CookieHandler.getDefault();
        CookieHandler.setDefault(null);

        try {
            // ========== Step 1: 平台登录（完全复刻 ClawLoginController） ==========
            // 密码必须用 RSA 公钥加密后传输（平台 auth 服务用私钥解密）
            String encryptedPassword = rsaEncrypt(password, config.getRsaPublicKey());

            LinkedMultiValueMap<String, Object> param = new LinkedMultiValueMap<>();
            param.add("username", username);
            param.add("password", encryptedPassword);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            ResponseEntity<String> loginResponse = restTemplate.exchange(
                    gatewayUrl + config.getLoginPath(),
                    HttpMethod.POST,
                    new HttpEntity<>(param, headers),
                    String.class);

            log.debug("[PlatformOAuth2] 登录响应: status={}, body={}, Set-Cookie={}",
                    loginResponse.getStatusCode(),
                    loginResponse.getBody(),
                    loginResponse.getHeaders().get(HttpHeaders.SET_COOKIE));

            if (loginResponse.getStatusCode() != HttpStatus.OK) {
                log.error("[PlatformOAuth2] 平台登录HTTP失败: status={}", loginResponse.getStatusCode());
                throw new MateClawException("err.auth.platform_login_failed", "平台认证失败，用户名或密码错误");
            }

            // 关键：检查响应体中的业务码（平台返回 HTTP 200 但业务码非0表示登录失败）
            String loginBody = loginResponse.getBody();
            if (loginBody != null) {
                try {
                    JsonNode loginJson = objectMapper.readTree(loginBody);
                    JsonNode codeNode = loginJson.path("code");
                    int bizCode = codeNode.asInt();
                    if (codeNode.isNumber() && bizCode != 0 && bizCode != 200) {
                        String serverMsg = loginJson.path("message").asText("未知错误");
                        log.error("[PlatformOAuth2] 平台登录业务失败: code={}, message={}", codeNode.asInt(), serverMsg);
                        throw new MateClawException("err.auth.platform_login_failed", "平台认证失败: " + serverMsg);
                    }
                } catch (MateClawException e) {
                    throw e;
                } catch (Exception e) {
                    log.warn("[PlatformOAuth2] 登录响应体解析跳过: {}", e.getMessage());
                }
            }

            // ========== Step 2: 获取授权码（复刻 ClawLoginController 的 Cookie 传递方式） ==========
            HttpHeaders authHeaders = new HttpHeaders();
            List<String> setCookies = loginResponse.getHeaders().get(HttpHeaders.SET_COOKIE);
            String cookieValue = (setCookies != null) ? String.join(",", setCookies) : "";
            authHeaders.set(HttpHeaders.COOKIE, cookieValue);

            log.debug("[PlatformOAuth2] 设置Cookie头: {}", cookieValue);

            String authorizeUrl = gatewayUrl + config.getAuthorizePath()
                    + "?client_id=" + config.getClientId()
                    + "&response_type=code"
                    + "&redirect_uri=" + config.getRedirectUri();

            log.debug("[PlatformOAuth2] 请求授权码: {}", authorizeUrl);

            ResponseEntity<String> authResponse = restTemplate.exchange(
                    authorizeUrl,
                    HttpMethod.GET,
                    new HttpEntity<>(new LinkedMultiValueMap<>(), authHeaders),
                    String.class);

            // 从重定向 Location 头中提取 code
            URI location = authResponse.getHeaders().getLocation();
            if (location == null) {
                log.error("[PlatformOAuth2] 授权码获取失败：无 Location 头, status={}, headers={}",
                        authResponse.getStatusCode(), authResponse.getHeaders());
                throw new MateClawException("err.auth.platform_auth_failed", "平台授权失败，无法获取授权码");
            }

            String locationStr = location.toString();
            String code = extractCode(locationStr);
            if (code == null || code.isEmpty()) {
                log.error("[PlatformOAuth2] 授权码解析失败: location={}", locationStr);
                throw new MateClawException("err.auth.platform_auth_failed", "平台授权失败，无法解析授权码");
            }
            log.debug("[PlatformOAuth2] 获取到授权码");

            // ========== Step 3: 用授权码换取 Token ==========
            String tokenUrl = gatewayUrl + config.getTokenPath()
                    + "?grant_type=authorization_code"
                    + "&code=" + code
                    + "&client_id=" + config.getClientId()
                    + "&client_secret=" + config.getClientSecret()
                    + "&redirect_uri=" + config.getRedirectUri();

            log.debug("[PlatformOAuth2] 交换Token: {}", tokenUrl);

            // Token 请求也需要携带 Session Cookie（服务端需要会话来关联授权码上下文）
            HttpHeaders tokenHeaders = new HttpHeaders();
            tokenHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
            tokenHeaders.set(HttpHeaders.COOKIE, cookieValue);

            ResponseEntity<String> tokenResponse = restTemplate.exchange(
                    tokenUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(param, tokenHeaders),
                    String.class);

            JsonNode tokenJson = objectMapper.readTree(tokenResponse.getBody());
            String accessToken = tokenJson.path("access_token").asText(null);
            String tokenType = tokenJson.path("token_type").asText("bearer");

            if (accessToken == null || accessToken.isEmpty()) {
                log.error("[PlatformOAuth2] Token交换失败: response={}", tokenResponse.getBody());
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
        } finally {
            CookieHandler.setDefault(previousHandler);
        }
    }

    /**
     * 从重定向 URL 中提取 code 参数
     */
    private String extractCode(String locationUrl) {
        if (locationUrl == null) return null;
        int idx = locationUrl.indexOf("code=");
        if (idx < 0) return null;
        String code = locationUrl.substring(idx + 5);
        int ampIdx = code.indexOf('&');
        if (ampIdx > 0) {
            code = code.substring(0, ampIdx);
        }
        return code;
    }

    /**
     * RSA 公钥加密（与 esp-auth 的 RsaUtil.encrypt 一致）
     */
    private String rsaEncrypt(String plainText, String publicKeyString) {
        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyString);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            Cipher cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            log.error("[PlatformOAuth2] RSA加密失败: {}", e.getMessage());
            throw new MateClawException("err.auth.rsa_encrypt_failed", "RSA加密失败");
        }
    }

    /**
     * 创建 RestTemplate（禁止自动重定向）—— 与 ClawLoginController.createRestTemplate() 一致
     */
    private RestTemplate createRestTemplate() {
        return new RestTemplate(new org.springframework.http.client.SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        });
    }
}
