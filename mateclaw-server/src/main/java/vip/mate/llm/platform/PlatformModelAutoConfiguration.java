package vip.mate.llm.platform;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 平台端模型同步自动配置
 * <p>
 * 注册 {@link PlatformModelProperties} 配置绑定。
 *
 * @author MateClaw Team
 */
@Configuration
@EnableConfigurationProperties(PlatformModelProperties.class)
public class PlatformModelAutoConfiguration {
}
