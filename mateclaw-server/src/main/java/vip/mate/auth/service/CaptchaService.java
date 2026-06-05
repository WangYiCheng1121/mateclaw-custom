package vip.mate.auth.service;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.core.lang.UUID;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 验证码服务
 * <p>
 * 使用 Hutool 生成图片验证码，Caffeine 本地缓存存储。
 * 每个验证码允许最多尝试 5 次或 5 分钟内有效（先到先失效）。
 * 验证成功或超限后自动清除，无需人工维护。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class CaptchaService {

    private static final int MAX_ATTEMPTS = 5;

    /** 验证码缓存：key -> CaptchaEntry，5 分钟过期 */
    private final Cache<String, CaptchaEntry> captchaCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(10000)
            .build();

    /**
     * 生成验证码
     *
     * @return CaptchaResult 包含 captchaKey 和 base64 图片
     */
    public CaptchaResult generate() {
        // 生成 4 位线段干扰验证码（宽 130，高 48）
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(130, 48, 4, 80);
        String code = captcha.getCode();
        String key = UUID.fastUUID().toString(true);

        // 存入缓存（忽略大小写比对，全部转小写存储）
        captchaCache.put(key, new CaptchaEntry(code.toLowerCase()));
        log.debug("[Captcha] 生成验证码: key={}", key);

        CaptchaResult result = new CaptchaResult();
        result.setCaptchaKey(key);
        // Hutool 的 getImageBase64Data() 返回带 data:image/png;base64, 前缀的完整格式
        result.setImage(captcha.getImageBase64Data());
        return result;
    }

    /**
     * 校验验证码（允许 5 次尝试，1 分钟内有效，成功或超限后立即销毁）
     *
     * @param key  验证码标识
     * @param code 用户输入的验证码
     * @return true=校验通过
     */
    public boolean validate(String key, String code) {
        if (key == null || code == null) {
            return false;
        }
        CaptchaEntry entry = captchaCache.getIfPresent(key);
        if (entry == null) {
            log.debug("[Captcha] 验证码已过期或不存在: key={}", key);
            return false;
        }
        boolean match = entry.getCode().equals(code.toLowerCase());
        if (match) {
            // 验证成功，立即销毁
            captchaCache.invalidate(key);
            return true;
        }
        // 验证失败，累计次数
        int used = entry.incrementAttempts();
        log.debug("[Captcha] 验证码错误: key={}, attempts={}/{}", key, used, MAX_ATTEMPTS);
        if (used >= MAX_ATTEMPTS) {
            // 达到上限，销毁
            captchaCache.invalidate(key);
            log.debug("[Captcha] 尝试次数耗尽，已销毁: key={}", key);
        }
        return false;
    }

    /** 缓存条目：验证码答案 + 已尝试次数 */
    @Data
    private static class CaptchaEntry {
        private final String code;
        private int attempts;

        CaptchaEntry(String code) {
            this.code = code;
            this.attempts = 0;
        }

        int incrementAttempts() {
            return ++attempts;
        }
    }

    @Data
    public static class CaptchaResult {
        /** 验证码标识（登录时需回传） */
        private String captchaKey;
        /** Base64 编码的验证码图片（含 data:image/png;base64, 前缀） */
        private String image;
    }
}
