package vip.mate.skill.platform;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 平台端技能同步自动配置
 * <p>
 * 注册 {@link PlatformSkillProperties} 配置绑定。
 *
 * @author MateClaw Team
 */
@Configuration
@EnableConfigurationProperties(PlatformSkillProperties.class)
public class PlatformSkillAutoConfiguration {
}
