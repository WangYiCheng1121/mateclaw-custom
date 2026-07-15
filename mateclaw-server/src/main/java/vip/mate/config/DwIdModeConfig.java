package vip.mate.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DW_ID 独立服务模式配置。
 *
 * <p>当环境变量 {@code DW_ID} 存在时，系统进入无前端、全自动的独立服务模式：
 * <ul>
 *   <li>仅存在唯一助手（agentId=1），由 DW_ID 对应的平台预置模板创建</li>
 *   <li>跳过所有登录认证与工作区权限校验</li>
 *   <li>平台 API 调用使用 client_credentials 机器令牌</li>
 *   <li>无前端界面，纯后端服务，仅通过命令行启动</li>
 * </ul>
 *
 * <p>当环境变量 {@code DW_ID} 不存在时，行为与之前完全一致。
 */
@Slf4j
@Component
public class DwIdModeConfig {

    /** DW_ID 环境变量名 */
    private static final String DW_ID_ENV = "DW_ID";

    /** 是否处于 DW_ID 独立服务模式 */
    @Getter
    private final boolean dwIdMode;

    /** DW_ID 对应的平台预置助手模板 ID（presetId） */
    @Getter
    private final String dwId;

    public DwIdModeConfig() {
        this.dwId = System.getenv(DW_ID_ENV);
        this.dwIdMode = dwId != null && !dwId.isBlank();
        if (dwIdMode) {
            log.info("============================================================");
            log.info("  DW_ID mode ENABLED");
            log.info("  presetId = {}", dwId);
            log.info("  No frontend, no login required, fully autonomous service");
            log.info("============================================================");
        }
    }
}
