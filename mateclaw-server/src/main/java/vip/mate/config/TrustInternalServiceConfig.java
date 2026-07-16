package vip.mate.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 集群内部服务互信配置。
 *
 * <p>当环境变量 {@code TRUST_INTERNAL_SERVICE=true} 时，
 * 表示当前部署在 K8s 集群内部，Claw 与 ai-manage 之间无需
 * 通过 OAuth2 client_credentials 进行认证，直接以服务间互信
 * 方式调用平台 API。
 *
 * <p>仅在 {@code DW_ID} 模式（{@link DwIdModeConfig#isDwIdMode()}）
 * 下生效，独立安装包模式不受影响。
 */
@Slf4j
@Component
public class TrustInternalServiceConfig {

    /** 集群内服务互信环境变量名 */
    private static final String TRUST_ENV = "TRUST_INTERNAL_SERVICE";

    /** 是否启用集群内免认证 */
    @Getter
    private final boolean enabled;

    public TrustInternalServiceConfig() {
        String raw = System.getenv(TRUST_ENV);
        this.enabled = "true".equalsIgnoreCase(raw);
        if (enabled) {
            log.info("[TrustInternal] TRUST_INTERNAL_SERVICE=true — 集群内服务互信已启用，跳过 OAuth2 token 获取");
        }
    }
}
