package vip.mate.channel.platform;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 平台端渠道同步自动配置
 * <p>
 * 注册 {@link PlatformChannelProperties} 配置绑定。
 *
 * @author MateClaw Team
 */
@Configuration
@EnableConfigurationProperties(PlatformChannelProperties.class)
public class PlatformChannelAutoConfiguration {
}
