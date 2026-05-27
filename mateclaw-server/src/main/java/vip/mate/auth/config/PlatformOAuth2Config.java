package vip.mate.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 平台端 OAuth2 认证配置
 * <p>
 * 对接 esp-user 服务，通过 OAuth2 授权码流程获取 claw_access_token。
 * 设置 enabled=false 可关闭平台认证，退化为 MateClaw 单机模式。
 * <p>
 * 地址配置：
 * - gateway-url：统一网关地址（通过路径前缀 /esp-user/ 路由到 esp-user 服务）
 *
 * @author MateClaw Team
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mateclaw.platform")
public class PlatformOAuth2Config {

    /** 是否启用平台 OAuth2 认证（默认开启，通过 application.yml 的 mateclaw.platform.enabled 或环境变量 PLATFORM_AUTH_ENABLED 控制） */
    private boolean enabled;

    /**
     * 统一网关地址（用于所有平台服务交互，通过路径前缀路由）。
     * <p>
     * 配置来源优先级（由高到低）：
     * <ol>
     *   <li>命令行参数：--mateclaw.platform.gateway-url=http://...</li>
     *   <li>环境变量：PLATFORM_GATEWAY_URL</li>
     *   <li>application.yml 默认值：http://192.168.10.225</li>
     * </ol>
     */
    private String gatewayUrl;

    /** OAuth2 客户端 ID（通过 application.yml 或环境变量 PLATFORM_CLIENT_ID 配置） */
    private String clientId;

    /** OAuth2 客户端密钥（通过 application.yml 或环境变量 PLATFORM_CLIENT_SECRET 配置） */
    private String clientSecret;

    /** OAuth2 回调地址 */
    private String redirectUri;

    /** 平台登录接口路径 */
    private String loginPath;

    /** 平台授权码接口路径 */
    private String authorizePath;

    /** 平台 Token 交换接口路径 */
    private String tokenPath;

    /** HTTP 连接超时（毫秒） */
    private int connectTimeout;

    /** HTTP 读取超时（毫秒） */
    private int readTimeout;

    /** 平台登录 RSA 公钥（用于加密密码，与 esp-auth 的 login.security.publicKey 一致） */
    private String rsaPublicKey = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCBVEDdj3a5u0S77txvjj+q0EzE7ugddAw7kXOBlijrkIEpBUXV6duux4CAbOpzbnXP1068xw9awOKKO5OqYc97f+p5MBj87wRDADnZqBWjAxTH4diGH4B4WRF+hx45aPnvzzyE0qfoc/8BlRHu2eo1XvEW077aiBFvq59szVns8QIDAQAB";
}
