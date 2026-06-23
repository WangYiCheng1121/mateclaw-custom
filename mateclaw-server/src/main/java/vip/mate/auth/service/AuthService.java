package vip.mate.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import vip.mate.auth.config.PlatformOAuth2Config;
import vip.mate.auth.model.LoginRequest;
import vip.mate.auth.model.LoginResponse;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.repository.UserMapper;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.service.WorkspaceService;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * 认证服务（JWT）
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final PlatformOAuth2Config platformConfig;
    private final PlatformOAuth2Service platformOAuth2Service;
    private final PlatformTokenHolder platformTokenHolder;
    private final WorkspaceService workspaceService;

    @Value("${mateclaw.jwt.secret:MateClaw-Secret-Key-2024-Very-Long-String}")
    private String jwtSecret;

    @Value("${mateclaw.jwt.expiration:86400000}")
    private long jwtExpiration;

    @Value("${mateclaw.jwt.renewal-threshold:7200000}")
    private long renewalThreshold;


//    /**
//     * 登录
//     */
//    public LoginResponse login(LoginRequest request) {
//        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
//                .eq(UserEntity::getUsername, request.getUsername())
//                .eq(UserEntity::getEnabled, true));
//
//        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
//            throw new MateClawException("err.auth.invalid_credentials", 401, "用户名或密码错误");
//        }
//
//        String token = generateToken(user);
//        return new LoginResponse(user.getId(), token, user.getUsername(), user.getNickname(), user.getRole());
//    }


    /**
     * 登录
     * <p>
     * 启用平台认证时流程：
     * 1. 先调用平台 esp-auth OAuth2 验证用户名密码，获取 claw_access_token
     * 2. 同步本地用户（存在则复用，不存在则自动创建，角色默认 user）
     * 3. 生成 MateClaw 本地 JWT token
     * 4. 同时返回 claw_access_token 和 token
     * <p>
     * 未启用平台认证时：走原始本地密码验证逻辑
     */
    public LoginResponse login(LoginRequest request) {
        if (platformConfig.isEnabled()) {
            return loginWithPlatform(request);
        }
        return loginLocal(request);
    }

    /**
     * 平台 OAuth2 认证 + 本地用户同步登录
     */
    private LoginResponse loginWithPlatform(LoginRequest request) {
        // Step 1: 平台 OAuth2 认证（失败会直接抛异常）
        PlatformOAuth2Service.PlatformAuthResult platformResult =
                platformOAuth2Service.authenticate(request.getUsername(), request.getPassword());

        // Step 2: 同步本地用户
        UserEntity user = findByUsername(request.getUsername());
        boolean isNewUser = false;
        if (user == null) {
            // 本地不存在该用户 → 自动创建
            log.info("[PlatformLogin] 本地用户不存在，自动创建: {}", request.getUsername());
            user = new UserEntity();
            user.setUsername(request.getUsername());
            user.setNickname(request.getUsername());
            // 平台认证模式下本地密码仅作占位，实际验证由平台完成
            user.setPassword(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
            user.setRole("user");
            user.setEnabled(true);
            userMapper.insert(user);
            log.info("[PlatformLogin] 本地用户创建成功: id={}, username={}", user.getId(), user.getUsername());
            isNewUser = true;
        } else if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new MateClawException("err.auth.user_disabled", "用户已被禁用");
        }

        // Step 3: 获取或创建用户个人工作区（按用户隔离数据）
        Long workspaceId = workspaceService.getOrCreateUserWorkspace(user.getId(), user.getNickname());

        // Step 4: 生成 MateClaw 本地 JWT token
        String token = generateToken(user);

        // Step 5: 缓存平台 access_token，供 Platform*Client 调用平台 API 时携带认证头
        String clawAccessToken = platformResult != null ? platformResult.getAccessToken() : null;
        if (clawAccessToken != null) {
            platformTokenHolder.setAccessToken(clawAccessToken);
        }
        return new LoginResponse(user.getId(), token, user.getUsername(), user.getNickname(), user.getRole(),
                workspaceId, clawAccessToken);
    }

    /**
     * 原始本地密码验证登录（平台认证未启用时使用）
     */
    private LoginResponse loginLocal(LoginRequest request) {
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, request.getUsername())
                .eq(UserEntity::getEnabled, true));

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new MateClawException("err.auth.invalid_credentials", "用户名或密码错误");
        }

        String token = generateToken(user);

        // 获取或创建用户个人工作区（按用户隔离数据）
        Long workspaceId = workspaceService.getOrCreateUserWorkspace(user.getId(), user.getNickname());

        return new LoginResponse(user.getId(), token, user.getUsername(), user.getNickname(), user.getRole(),
                workspaceId, null);
    }

    /**
     * 退出登录：清除服务端缓存的平台 access_token
     */
    public void logout() {
        platformTokenHolder.clear();
        log.info("[Auth] 用户退出登录，平台 access_token 已清除");
    }


    /**
     * 获取用户列表（管理员）
     */
    public List<UserEntity> listUsers() {
        return userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getEnabled, true));
    }

    /**
     * 创建用户
     */
    public UserEntity createUser(UserEntity user) {
        // 检查用户名是否已存在
        Long count = userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, user.getUsername()));
        if (count > 0) {
            throw new MateClawException("err.auth.username_exists", "用户名已存在: " + user.getUsername());
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new MateClawException("err.auth.password_required", "Password is required");
        }
        user.setPassword(passwordEncoder.encode(user.getPassword().trim()));
        user.setEnabled(true);
        if (user.getRole() == null) {
            user.setRole("user");
        }
        userMapper.insert(user);
        // 自动创建用户个人工作区（按用户隔离数据）
        workspaceService.getOrCreateUserWorkspace(user.getId(), user.getNickname());
        user.setPassword(null);
        return user;
    }

    /**
     * Reset password (admin operation — no old password required).
     * Used when an admin wants to set/reset a member's password.
     */
    public void resetPassword(Long userId, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new MateClawException("err.auth.password_required", "Password is required");
        }
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new MateClawException("err.auth.user_not_found", "用户不存在");
        }
        user.setPassword(passwordEncoder.encode(newPassword.trim()));
        userMapper.updateById(user);
    }

    /**
     * 修改密码
     */
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new MateClawException("err.auth.user_not_found", "用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new MateClawException("err.auth.wrong_password", "原密码错误");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    /**
     * 解析 Token 获取用户名
     */
    public String parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSignKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 Token 获取完整 Claims（含过期时间）
     */
    public Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSignKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断 Token 是否接近过期（剩余有效期 < renewalThreshold）
     */
    public boolean isNearExpiry(Claims claims) {
        if (claims == null || claims.getExpiration() == null) {
            return false;
        }
        long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
        return remaining > 0 && remaining < renewalThreshold;
    }

    /**
     * 根据用户名续签 Token
     */
    public String renewToken(String username) {
        UserEntity user = findByUsername(username);
        if (user != null && Boolean.TRUE.equals(user.getEnabled())) {
            return generateToken(user);
        }
        return null;
    }

    /**
     * 根据用户名查询用户
     */
    public UserEntity findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username));
    }

    /**
     * 根据 ID 查询用户
     */
    public UserEntity findById(Long userId) {
        return userMapper.selectById(userId);
    }

    private String generateToken(UserEntity user) {
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("userId", user.getId())
                .claim("role", user.getRole())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(getSignKey())
                .compact();
    }

    /**
     * 确保用户拥有个人工作区（注册/登录时调用）。
     * <p>
     * 桌面安装包场景：每个用户首次登录时自动创建专属工作区，
     * 后续登录复用已有工作区。用户为 owner 角色。
     * <p>
     * 兼容旧版：如果用户已经存在于默认工作区（workspace=1），
     * 则自动迁移到个人工作区。
     */
    private void ensureDefaultWorkspaceMembership(UserEntity user) {
        try {
            // 确保默认工作区存在（防御旧数据库/安装包未种子初始化）
            workspaceService.ensureDefaultWorkspaceExists(user.getId());
            // 获取或创建用户个人工作区
            workspaceService.getOrCreateUserWorkspace(user.getId(), user.getNickname());
            log.info("[Auth] 用户个人工作区已就绪: userId={}", user.getId());
        } catch (Exception e) {
            log.warn("[Auth] 确保用户工作区成员关系失败: userId={}, msg={}", user.getId(), e.getMessage());
        }
    }

    /**
     * 如果用户当前角色低于期望角色，自动升级（兼容旧安装包升级场景）
     */
    private void upgradeWorkspaceRoleIfNeeded(Long userId, String expectedRole) {
        try {
            var membership = workspaceService.getMembership(1L, userId);
            if (membership == null) {
                return;
            }
            String currentRole = membership.getRole();
            // owner 不降级，其他角色如果低于期望则升级
            if ("owner".equals(currentRole)) {
                return;
            }
            if (roleLevel(currentRole) < roleLevel(expectedRole)) {
                workspaceService.updateMemberRole(1L, userId, expectedRole);
                log.info("[Auth] 用户工作区角色已自动升级: userId={}, {} -> {}", userId, currentRole, expectedRole);
            }
        } catch (Exception e) {
            log.debug("[Auth] 角色升级检查跳过: userId={}, msg={}", userId, e.getMessage());
        }
    }

    private int roleLevel(String role) {
        return switch (role) {
            case "owner" -> 4;
            case "admin" -> 3;
            case "member" -> 2;
            case "viewer" -> 1;
            default -> 0;
        };
    }


    private SecretKey getSignKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        // 确保密钥长度至少 32 字节（HMAC-SHA256）
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
