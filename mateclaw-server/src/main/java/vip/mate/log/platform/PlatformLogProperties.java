package vip.mate.log.platform;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台日志上报配置
 * <p>
 * 绑定前缀 mateclaw.log.report，控制客户端向平台端上报本地运行日志的行为。
 * 平台端接收接口：POST /claw/logs
 * <p>
 * 上报策略：
 * - 异步队列缓冲，不阻塞业务线程
 * - 批量上报（达到 batchSize 或 flushIntervalMs 触发）
 * - 仅上报 minLevel 及以上级别的日志
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mateclaw.log.report")
public class PlatformLogProperties {

    /**
     * 是否启用日志上报（false 则不上报任何日志到平台）
     */
    private boolean enabled = true;

    /**
     * 平台端日志服务名（Nacos 服务发现用）
     */
    private String serviceId = "ai-manage";

    /**
     * 平台端日志上报接口路径
     */
    private String reportPath = "/claw/logs";

    /**
     * 最低上报级别：WARN / ERROR（低于此级别的日志不上报）
     */
    private String minLevel = "WARN";

    /**
     * 子系统标识（标记日志来源，方便平台端区分）
     */
    private String subsystem = "mateclaw-client";

    /**
     * 异步队列容量（超出后丢弃最旧日志）
     */
    private int queueCapacity = 1024;

    /**
     * 批量上报大小（积累到该数量后触发一次上报）
     */
    private int batchSize = 20;

    /**
     * 定时刷新间隔（毫秒），即使未达到 batchSize 也会触发上报
     */
    private long flushIntervalMs = 5000;

    /**
     * HTTP 连接超时（毫秒）
     */
    private int connectTimeout = 5000;

    /**
     * HTTP 读取超时（毫秒）
     */
    private int readTimeout = 10000;

    /**
     * 上报失败后重试次数
     */
    private int retryCount = 2;
}
