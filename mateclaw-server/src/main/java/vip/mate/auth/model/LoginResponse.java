package vip.mate.auth.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录响应
 *
 * @author MateClaw Team
 */
@Data
@AllArgsConstructor
public class LoginResponse {
    private Long id;
    private String token;
    private String username;
    private String nickname;
    private String role;

    /** 平台端 OAuth2 access_token（仅启用平台认证时有值） */
    private String clawAccessToken;

    /** 兼容原有5参数构造（平台认证未启用时 clawAccessToken 为 null） */
    public LoginResponse(Long id, String token, String username, String nickname, String role) {
        this(id, token, username, nickname, role, null);
    }
}
