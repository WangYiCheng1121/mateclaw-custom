package vip.mate.log.platform;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 平台日志上报自动配置
 * <p>
 * 注册 {@link PlatformLogProperties} 配置绑定。
 * {@link PlatformLogClient} 和 {@link PlatformLogReporter} 通过 @Component 自动注册。
 *
 * @author MateClaw Team
 */
@Configuration
@EnableConfigurationProperties(PlatformLogProperties.class)
public class PlatformLogAutoConfiguration {
}
