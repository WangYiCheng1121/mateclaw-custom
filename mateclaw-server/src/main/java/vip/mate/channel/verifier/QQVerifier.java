package vip.mate.channel.verifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates QQ Bot credentials via
 * {@code POST /app/getAppAccessToken} (the same handshake the production
 * adapter does in {@code QQChannelAdapter.validateCredentials()}).
 * A green Step 2 means the QQ Open Platform accepted the app credentials
 * and issued an access token.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QQVerifier implements ChannelVerifier {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";

    private final ObjectMapper objectMapper;

    @Override
    public String getChannelType() {
        return "qq";
    }

    @Override
    public VerificationResult verify(VerificationRequest request) {
        long t0 = System.currentTimeMillis();
        String appId = string(request.config(), "app_id");
        String clientSecret = string(request.config(), "client_secret");

        if (appId == null || appId.isBlank()) {
            return VerificationResult.failed(0, "App ID is required",
                    "app_id", "Scan the QQ QR (one-click bot creation) — App ID is filled automatically.");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            return VerificationResult.failed(0, "Client Secret is required",
                    "client_secret", "Scan the QQ QR — Client Secret is filled automatically.");
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "appId", appId,
                    "clientSecret", clientSecret));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            long ms = System.currentTimeMillis() - t0;

            // QQ returns {access_token, expires_in} on success, or
            // {message / msg} on failure.
            JsonNode root = objectMapper.readTree(resp.body());
            String accessToken = root.path("access_token").asText("");

            if (resp.statusCode() == 200 && !accessToken.isBlank()) {
                int expire = root.path("expires_in").asInt(7200);
                Map<String, Object> identity = new LinkedHashMap<>();
                identity.put("accountId", appId);
                identity.put("transport", "QQ Open Platform Bot");
                identity.put("tokenTtl", expire + "s");
                return VerificationResult.ok(ms,
                        "Connected — QQ issued an access token",
                        identity);
            }

            // Failure: extract error message
            String msg = root.path("message").asText(
                    root.path("msg").asText("auth failed"));
            return VerificationResult.failed(ms,
                    "QQ rejected the credentials: " + msg,
                    invalidFieldFor(msg),
                    hintFor(msg));
        } catch (java.net.http.HttpTimeoutException e) {
            return VerificationResult.failed(System.currentTimeMillis() - t0,
                    "Timed out talking to bots.qq.com", null,
                    "Network couldn't reach QQ Open Platform in 5s. Check egress to bots.qq.com (port 443).");
        } catch (Exception e) {
            log.debug("[qq-verify] error: {}", e.getMessage());
            return VerificationResult.failed(System.currentTimeMillis() - t0,
                    "Could not reach QQ Open Platform: " + e.getClass().getSimpleName(), null, e.getMessage());
        }
    }

    private static String invalidFieldFor(String msg) {
        if (msg == null) return null;
        String lower = msg.toLowerCase();
        if (lower.contains("appid") || lower.contains("app_id") || lower.contains("app id")) {
            return "app_id";
        }
        if (lower.contains("secret") || lower.contains("credential") || lower.contains("signature")) {
            return "client_secret";
        }
        return null;
    }

    private static String hintFor(String msg) {
        if (msg == null || msg.isBlank()) return "QQ auth failed.";
        if (msg.toLowerCase().contains("invalid") || msg.toLowerCase().contains("rejected")) {
            return "Credentials rejected. Re-scan the QR — QQ may have rotated the secret on app re-publish.";
        }
        if (msg.toLowerCase().contains("expired") || msg.toLowerCase().contains("timeout")) {
            return "Credential has expired. Re-scan the QR to obtain a fresh pair.";
        }
        return "QQ error — " + msg;
    }

    private static String string(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
