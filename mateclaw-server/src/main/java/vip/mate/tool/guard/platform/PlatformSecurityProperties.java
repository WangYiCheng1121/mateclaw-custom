package vip.mate.tool.guard.platform;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台端安全模块同步配置
 * <p>
 * 绑定前缀 mateclaw.security.platform，控制客户端从平台端拉取安全配置/规则，
 * 以及向平台端推送审计日志 / 审批记录的行为。
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mateclaw.security.platform")
public class PlatformSecurityProperties {

    /** 是否启用平台端安全模块（false 则回退到本地 DB） */
    private boolean enabled = true;

    /** 平台端安全服务名（Nacos 服务发现用） */
    private String serviceId = "ai-manage";

    /** 平台端安全配置拉取路径 */
    private String configPath = "/api/v1/platform/security/guard/config";

    /** 平台端安全规则拉取路径 */
    private String rulesPath = "/api/v1/platform/security/guard/rules";

    /** 平台端审计日志上报路径 */
    private String auditPath = "/api/v1/platform/security/audit/logs";

    /** 平台端审批记录上报路径 */
    private String approvalPath = "/api/v1/platform/security/approvals";

    /** 平台端审批状态更新路径 */
    private String approvalStatusPath = "/api/v1/platform/security/approvals/status";

    /** 配置缓存刷新间隔（秒），0 表示不自动刷新 */
    private int configRefreshIntervalSec = 60;

    /** 规则缓存刷新间隔（秒），0 表示不自动刷新 */
    private int rulesRefreshIntervalSec = 120;

    /** HTTP 连接超时（毫秒） */
    private int connectTimeout = 5000;

    /** HTTP 读取超时（毫秒） */
    private int readTimeout = 10000;
}
