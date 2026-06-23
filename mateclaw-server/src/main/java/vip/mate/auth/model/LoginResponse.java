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

    /** 用户个人工作区 ID（用于前端 X-Workspace-Id header） */
    private Long workspaceId;

    /** 平台端 OAuth2 access_token（仅启用平台认证时有值） */
    private String clawAccessToken;

    /** 兼容原有5参数构造（平台认证未启用时 clawAccessToken / workspaceId 为 null） */
    public LoginResponse(Long id, String token, String username, String nickname, String role) {
        this(id, token, username, nickname, role, null, null);
    }

    /** 兼容原有6参数构造（无 workspaceId） */
    public LoginResponse(Long id, String token, String username, String nickname, String role, String clawAccessToken) {
        this(id, token, username, nickname, role, null, clawAccessToken);
    }
}
