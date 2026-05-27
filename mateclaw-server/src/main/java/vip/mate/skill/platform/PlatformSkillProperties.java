package vip.mate.skill.platform;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 技能同步配置
 * <p>
 * 绑定前缀 mateclaw.skill.sync，平台网关地址等通用信息
 * 通过注入 {@link vip.mate.auth.config.PlatformOAuth2Config} 获取。
 * 技能的增删改由平台端统一管理，客户端只读 + 同步。
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mateclaw.skill.sync")
public class PlatformSkillProperties {

    /**
     * 是否启用技能同步（false 退化为本地自治模式）
     */
    private boolean enabled = true;

    /**
     * 平台端技能服务名（Nacos 服务发现用）
     * 优先从 Nacos 发现该服务实例地址，失败降级到 mateclaw.platform.gateway-url
     */
    private String serviceId = "ai-manage";

    /**
     * 获取已分配技能列表的接口路径
     */
    private String assignedSkillsPath = "/api/skills/assigned";

    /**
     * 获取技能详情的接口路径（{id} 占位）
     */
    private String skillDetailPath = "/api/skills/{id}";

    /**
     * 同步间隔（秒），默认 300 秒（5分钟）
     */
    private int syncIntervalSeconds = 300;

    /**
     * HTTP 连接超时（毫秒）
     */
    private int connectTimeout = 5000;

    /**
     * HTTP 读取超时（毫秒）
     */
    private int readTimeout = 10000;

    /**
     * 同步失败后的重试次数
     */
    private int retryCount = 3;
}
