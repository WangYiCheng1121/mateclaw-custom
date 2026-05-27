package vip.mate.auth.model;

import lombok.Data;

/**
 * 登录请求
 *
 * @author MateClaw Team
 */
@Data
public class LoginRequest {
    private String username;
    private String password;
    /** 验证码标识（获取验证码时返回的 key） */
    private String captchaKey;
    /** 用户输入的验证码 */
    private String captchaCode;
}
