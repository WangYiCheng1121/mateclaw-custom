package vip.mate.tool.guard.platform;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 平台端安全模块自动配置
 * <p>
 * 注册 {@link PlatformSecurityProperties} 配置绑定。
 * {@link PlatformSecurityClient} 通过 @Component 自动注册。
 *
 * @author MateClaw Team
 */
@Configuration
@EnableConfigurationProperties(PlatformSecurityProperties.class)
public class PlatformSecurityAutoConfiguration {
}
