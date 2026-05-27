package vip.mate.channel.platform;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 渠道同步配置
 * <p>
 * 绑定前缀 mateclaw.channel.sync，控制客户端从平台端同步渠道启用/禁用状态的行为。
 * 平台网关地址等通用信息通过 {@link vip.mate.auth.config.PlatformOAuth2Config} 获取。
 * <p>
 * 渠道架构：
 * - 平台端：固定4种渠道(weixin/qq/dingtalk/feishu)，仅提供启用/禁用控制
 * - 客户端：从平台同步启停状态，本地维护渠道配置(configJson)，负责执行通讯
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mateclaw.channel.sync")
public class PlatformChannelProperties {

    /**
     * 是否启用渠道同步（false 退化为本地自治模式，不校验平台状态）
     */
    private boolean enabled = true;

    /**
     * 平台端渠道服务名（Nacos 服务发现用）
     * 优先从 Nacos 发现该服务实例地址，失败降级到 mateclaw.platform.gateway-url
     */
    private String serviceId = "ai-manage";

    /**
     * 获取渠道启用状态列表的接口路径
     * 平台端返回所有渠道类型及其 enabled 状态
     */
    private String channelStatusPath = "/api/channels/status";

    /**
     * 同步间隔（秒），默认 60 秒（1分钟）
     * 渠道启停状态变化频率较低，1分钟足够
     */
    private int syncIntervalSeconds = 60;

    /**
     * HTTP 连接超时（毫秒）
     */
    private int connectTimeout = 5000;

    /**
     * HTTP 读取超时（毫秒）
     */
    private int readTimeout = 10000;
}
