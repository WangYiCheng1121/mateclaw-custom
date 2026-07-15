package vip.mate.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;
import vip.mate.workspace.core.service.WorkspaceService;

import java.util.List;
import java.util.Map;

/**
 * Workspace 访问拦截器
 * <p>
 * 对标注了 {@link RequireWorkspaceRole} 的 Controller 方法，自动校验：
 * 1. 当前用户已认证
 * 2. 请求中有 X-Workspace-Id header（否则使用默认 workspace=1）
 * 3. 用户是该 workspace 的成员且角色 ≥ 注解要求的最低角色
 * <p>
 * 成员资格查询使用 Caffeine 缓存（60s TTL），避免每次请求查库。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceAccessInterceptor implements HandlerInterceptor {

    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AgentMapper agentMapper;
    private final DwIdModeConfig dwIdModeConfig;

    /** 默认 workspace ID（未传 header 时使用） */
    private static final long DEFAULT_WORKSPACE_ID = 1L;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // DW_ID 模式：注入虚拟认证并跳过所有权限检查
        if (dwIdModeConfig.isDwIdMode()) {
            injectVirtualAuth();
            return true;
        }

        // 只拦截 Controller 方法
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 检查注解：@RequireGlobalAdmin 与 @RequireWorkspaceRole 二选一
        RequireGlobalAdmin globalAdmin = handlerMethod.getMethodAnnotation(RequireGlobalAdmin.class);
        RequireWorkspaceRole annotation = handlerMethod.getMethodAnnotation(RequireWorkspaceRole.class);
        if (globalAdmin == null && annotation == null) {
            return true;
        }

        // 获取当前认证用户
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            // 未认证的请求由 Spring Security 处理，这里不拦截
            return true;
        }

        String username = auth.getName();
        UserEntity user = authService.findByUsername(username);
        if (user == null) {
            sendForbidden(response, "User not found");
            return false;
        }

        boolean isGlobalAdmin = "admin".equalsIgnoreCase(user.getRole());

        // 全局 admin 注解：必须是 mate_user.role=admin，与工作区无关
        if (globalAdmin != null && !isGlobalAdmin) {
            log.warn("Global admin access denied: user={}, path={}", username, request.getRequestURI());
            sendForbidden(response, "Global administrator role required");
            return false;
        }
        if (globalAdmin != null) {
            return true;
        }

        // @RequireWorkspaceRole 分支：全局 admin 跳过
        if (isGlobalAdmin) {
            return true;
        }

        long workspaceId = resolveWorkspaceId(request);
        String minRole = annotation.value();
        if (!workspaceService.hasPermissionCached(workspaceId, user.getId(), minRole)) {
            // 兜底：已认证用户自动加入默认工作区（适用于新部署/数据迁移场景）
            if (workspaceId == DEFAULT_WORKSPACE_ID) {
                try {
                    // 确保默认工作区存在（防御旧数据库未种子初始化的情况）
                    workspaceService.ensureDefaultWorkspaceExists(user.getId());
                    // 桌面安装包：平台用户给 admin 角色，本地 admin 给 owner
                    String expectedRole = "admin".equalsIgnoreCase(user.getRole()) ? "owner" : "admin";
                    try {
                        workspaceService.addMember(DEFAULT_WORKSPACE_ID, user.getId(), expectedRole);
                        log.info("Auto-joined user to default workspace: user={}, role={}", username, expectedRole);
                    } catch (Exception addEx) {
                        // 已存在，尝试升级角色（兼容旧版本分配的 member 角色）
                        upgradeRoleIfNeeded(user.getId(), expectedRole, username);
                    }
                    workspaceService.evictMembershipCache(DEFAULT_WORKSPACE_ID, user.getId());
                    // 重新检查权限
                    if (workspaceService.hasPermissionCached(DEFAULT_WORKSPACE_ID, user.getId(), minRole)) {
                        return true;
                    }
                } catch (Exception e) {
                    log.debug("Auto-join default workspace failed: user={}, msg={}", username, e.getMessage());
                    workspaceService.evictMembershipCache(DEFAULT_WORKSPACE_ID, user.getId());
                    if (workspaceService.hasPermissionCached(DEFAULT_WORKSPACE_ID, user.getId(), minRole)) {
                        return true;
                    }
                }
            }
            log.warn("Workspace access denied: user={}, workspaceId={}, requiredRole={}", username, workspaceId, minRole);
            sendForbidden(response, "Workspace permission denied: requires " + minRole + " role");
            return false;
        }

        // The role check above only proves the user belongs to the *header*
        // workspace — not that a path-bound {agentId} actually lives there.
        // Without this a member of workspace A could read workspace B's agent
        // memory / context files by supplying B's agent id with their own header.
        if (!agentBelongsToWorkspace(request, workspaceId)) {
            log.warn("Cross-workspace agent access denied: user={}, workspaceId={}, path={}",
                    username, workspaceId, request.getRequestURI());
            sendForbidden(response, "Agent does not belong to the current workspace");
            return false;
        }

        return true;
    }

    /**
     * When the matched route carries an {@code {agentId}} path variable, verify
     * that agent belongs to the resolved workspace. Allows the request through
     * when there is no agent id, the id is unparseable, the agent does not
     * exist (so the handler can return its own 404), or the agent has not been
     * assigned a workspace.
     */
    @SuppressWarnings("unchecked")
    private boolean agentBelongsToWorkspace(HttpServletRequest request, long workspaceId) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attr instanceof Map)) {
            return true;
        }
        Object rawAgentId = ((Map<String, String>) attr).get("agentId");
        if (rawAgentId == null) {
            return true;
        }
        long agentId;
        try {
            agentId = Long.parseLong(rawAgentId.toString());
        } catch (NumberFormatException e) {
            return true;
        }
        AgentEntity agent = agentMapper.selectById(agentId);
        if (agent == null || agent.getWorkspaceId() == null) {
            return true;
        }
        return agent.getWorkspaceId() == workspaceId;
    }

    /**
     * 自动升级角色（兼容旧安装包中角色为 member 的已有用户）
     */
    private void upgradeRoleIfNeeded(Long userId, String expectedRole, String username) {
        try {
            var membership = workspaceService.getMembership(DEFAULT_WORKSPACE_ID, userId);
            if (membership == null || "owner".equals(membership.getRole())) {
                return;
            }
            int currentLevel = roleLevel(membership.getRole());
            int expectedLevel = roleLevel(expectedRole);
            if (currentLevel < expectedLevel) {
                workspaceService.updateMemberRole(DEFAULT_WORKSPACE_ID, userId, expectedRole);
                log.info("Auto-upgraded workspace role: user={}, {} -> {}", username, membership.getRole(), expectedRole);
            }
        } catch (Exception e) {
            log.debug("Role upgrade check skipped: user={}, msg={}", username, e.getMessage());
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


    private long resolveWorkspaceId(HttpServletRequest request) {
        String header = request.getHeader("X-Workspace-Id");
        if (header != null && !header.isBlank()) {
            try {
                return Long.parseLong(header.trim());
            } catch (NumberFormatException e) {
                // fall through to resolve from user
            }
        }
        // 兜底：从认证用户的 JWT 中解析 userId，查找其个人工作区
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            try {
                UserEntity user = authService.findByUsername(auth.getName());
                if (user != null) {
                    return workspaceService.getDefaultWorkspaceId(user.getId());
                }
            } catch (Exception e) {
                log.debug("Failed to resolve default workspace for user {}: {}", auth.getName(), e.getMessage());
            }
        }
        return DEFAULT_WORKSPACE_ID;
    }

    private void sendForbidden(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"msg\":\"" + message + "\",\"data\":null}");
    }

    /**
     * DW_ID 模式：注入虚拟认证用户（glsec），使后续 Controller 层能正常获取 Authentication。
     */
    private void injectVirtualAuth() {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            return; // 已有认证，跳过
        }
        UserEntity glsec = authService.findByUsername("glsec");
        if (glsec == null) return;
        var auth = new UsernamePasswordAuthenticationToken(
                glsec.getUsername(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + glsec.getRole().toUpperCase())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
