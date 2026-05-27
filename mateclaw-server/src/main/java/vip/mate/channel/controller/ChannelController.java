package vip.mate.channel.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import vip.mate.channel.ChannelManager;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.service.ChannelService;
import vip.mate.channel.platform.ChannelSyncService;
import vip.mate.channel.verifier.ChannelVerifierRegistry;
import vip.mate.channel.verifier.VerificationRequest;
import vip.mate.channel.verifier.VerificationResult;
import vip.mate.audit.service.AuditEventService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 渠道管理接口（客户端侧）
 * <p>
 * 渠道的可见性由平台端控制：平台启用的渠道才在客户端展示。
 * 客户端在平台启用的前提下，可进一步控制渠道的本地启停。
 * <p>
 * 层级关系：平台启用 → 客户端可见/可操作 → 客户端本地启停控制
 * <p>
 * 已移除的接口（迁移至平台端或废弃）：
 * - DELETE /api/v1/channels/{id}      → 删除渠道（平台端固定渠道，无需删除）
 * @author WYC
 */
@Slf4j
@Tag(name = "渠道管理")
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;
    private final ChannelManager channelManager;
    private final ChannelSyncService channelSyncService;
    private final AuditEventService auditEventService;
    private final ChannelVerifierRegistry verifierRegistry;
    private final ObjectMapper objectMapper;

    @RequireWorkspaceRole("admin")
    @Operation(summary = "获取渠道列表仅平台启用的渠道）")
    @GetMapping
    public R<List<ChannelEntity>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        // 只返回平台启用的渠道（缓存未填充时不限制）
        Set<String> enabledTypes = channelSyncService.getPlatformEnabledTypes();
        List<ChannelEntity> channels = channelService.listPlatformChannelsByWorkspace(wsId);
        if (enabledTypes == null) {
            return R.ok(channels);
        }
        List<ChannelEntity> filtered = channels.stream()
                .filter(ch -> enabledTypes.contains(ch.getChannelType()))
                .collect(Collectors.toList());
        return R.ok(filtered);
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "按类型获取渠道列表")
    @GetMapping("/type/{channelType}")
    public R<List<ChannelEntity>> listByType(@PathVariable String channelType,
                                              @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        // 平台未启用的类型直接返回空
        if (!channelSyncService.isPlatformEnabled(channelType)) {
            return R.ok(List.of());
        }
        long wsId = workspaceId != null ? workspaceId : 1L;
        return R.ok(channelService.listChannelsByTypeAndWorkspace(channelType, wsId));
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "获取渠道详情")
    @GetMapping("/{id}")
    public R<ChannelEntity> get(@PathVariable Long id,
                                @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        ChannelEntity channel = channelService.getChannel(id);
        verifyResourceWorkspace(channel.getWorkspaceId(), workspaceId);
        return R.ok(channel);
    }

    // ==================== 本地启停控制 API ====================


    @RequireWorkspaceRole("admin")
    @Operation(summary = "启用/禁用渠道（客户端本地控制，前提是平台已启用该渠道）")
    @PutMapping("/{id}/toggle")
    public R<ChannelEntity> toggle(@PathVariable Long id, @RequestParam boolean enabled,
                                   @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        ChannelEntity existing = channelService.getChannel(id);
        verifyResourceWorkspace(existing.getWorkspaceId(), workspaceId);

        // 校验平台端是否启用该渠道类型
        if (!channelSyncService.isPlatformEnabled(existing.getChannelType())) {
            throw new MateClawException("err.channel.platform_disabled", "该渠道已被平台禁用，无法操作");
        }

        // 启用时校验是否有有效配置
        if (enabled && (existing.getConfigJson() == null || existing.getConfigJson().isBlank())) {
            throw new MateClawException("err.channel.no_config", "该渠道尚未配置，请先完成配置");
        }

        ChannelEntity channel = channelService.toggleChannel(id, enabled);
        // 联动 ChannelManager
        if (enabled) {
            try {
                channelManager.startChannel(channel);
            } catch (Exception e) {
                // 启动失败：回滚 DB 状态，避免 DB enabled=true 但实际未运行的不一致
                channelService.toggleChannel(id, false);
                throw new MateClawException("err.channel.start_failed",
                        "渠道启动失败: " + e.getMessage());
            }
        } else {
            channelManager.stopChannel(id);
        }
        return R.ok(channel);
    }

    // ==================== 本地配置管理 API ====================

    @RequireWorkspaceRole("admin")
    @Operation(summary = "更新渠道本地配置（仅 configJson，不影响启停状态）")
    @PutMapping("/{id}/config")
    public R<ChannelEntity> updateConfig(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                         @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        ChannelEntity existing = channelService.getChannel(id);
        verifyResourceWorkspace(existing.getWorkspaceId(), workspaceId);

        String oldConfigJson = existing.getConfigJson();
        String configJson = null;
        if (body.containsKey("configJson")) {
            Object configObj = body.get("configJson");
            if (configObj instanceof String s) {
                configJson = s;
            } else if (configObj != null) {
                try {
                    configJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(configObj);
                } catch (Exception e) {
                    throw new MateClawException("err.channel.invalid_config", "无效的配置格式");
                }
            }
        }

        existing.setConfigJson(configJson);
        if (body.containsKey("botPrefix")) {
            existing.setBotPrefix((String) body.get("botPrefix"));
        }
        if (body.containsKey("description")) {
            existing.setDescription((String) body.get("description"));
        }

        ChannelEntity updated = channelService.updateChannel(existing);
        // 配置变更后，如果渠道正在运行且 configJson 实际变化了，才触发热替换
        if (Boolean.TRUE.equals(updated.getEnabled()) && !Objects.equals(oldConfigJson, configJson)) {
            channelManager.restartChannel(id);
        }
        return R.ok(updated);
    }

    // ==================== 运行状态 API ====================

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取渠道运行状态（仅平台启用的渠道）")
    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        Set<String> enabledTypes = channelSyncService.getPlatformEnabledTypes();
        return R.ok(channelManager.getStatusFiltered(enabledTypes));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取平台端渠道启停状态（缓存）")
    @GetMapping("/platform-status")
    public R<Map<String, Boolean>> platformStatus() {
        return R.ok(channelSyncService.getPlatformStatusCache());
    }

    // ==================== Platform Sync API ====================

    @Operation(summary = "手动触发从平台端同步渠道启停状态")
    @PostMapping("/sync")
    public R<Map<String, Object>> syncFromPlatform() {
        ChannelSyncService.SyncResult result = channelSyncService.syncFromPlatform();
        return R.ok(Map.of(
                "success", result.success(),
                "message", result.message(),
                "started", result.started(),
                "stopped", result.stopped()
        ));
    }

    @Operation(summary = "获取平台同步状态")
    @GetMapping("/sync/status")
    public R<Map<String, Object>> getSyncStatus() {
        return R.ok(channelSyncService.getSyncStatus());
    }


    @RequireWorkspaceRole("admin")
    @Operation(summary = "创建渠道（仅需名称和智能体，配置信息后续填写）")
    @PostMapping
    public R<ChannelEntity> create(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestBody ChannelEntity channel) {
        // 1. 渠道类型必须由平台管控
        if (channel.getChannelType() == null || channel.getChannelType().isBlank()) {
            throw new MateClawException("err.channel.type_required", "渠道类型不能为空");
        }
        if (!channelSyncService.isPlatformEnabled(channel.getChannelType())) {
            throw new MateClawException("err.channel.platform_disabled",
                    "该渠道类型 " + channel.getChannelType() + " 当前未被平台启用，无法创建");
        }
        // 2. 创建时强制 disabled（无有效凭据时不能启动），configJson 按前端传入保存
        channel.setEnabled(false);
        channel.setWorkspaceId(workspaceId != null ? workspaceId : 1L);
        ChannelEntity created = channelService.createChannel(channel);
        auditEventService.record("CREATE", "CHANNEL", String.valueOf(created.getId()), created.getName(), null);
        return R.ok(created);
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "更新渠道（全量更新、无条件重启）")
    @PutMapping("/{id}")
    public R<ChannelEntity> update(@PathVariable Long id, @RequestBody ChannelEntity channel,
                                   @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        ChannelEntity existing = channelService.getChannel(id);
        verifyResourceWorkspace(existing.getWorkspaceId(), workspaceId);
        channel.setId(id);
        channel.setWorkspaceId(existing.getWorkspaceId());
        ChannelEntity updated = channelService.updateChannel(channel);
        channelManager.restartChannel(id);
        auditEventService.record("UPDATE", "CHANNEL", String.valueOf(id), updated.getName(), null);
        return R.ok(updated);
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "删除渠道")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id,
                          @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        ChannelEntity channel = channelService.getChannel(id);
        verifyResourceWorkspace(channel.getWorkspaceId(), workspaceId);
        channelManager.stopChannel(id);
        channelService.deleteChannel(id);
        auditEventService.record("DELETE", "CHANNEL", String.valueOf(id), channel.getName(), null);
        return R.ok();
    }

//    @RequireWorkspaceRole("admin")
//    @Operation(summary = "启用/禁用渠道")
//    @PutMapping("/{id}/toggle")
//    public R<ChannelEntity> toggle(@PathVariable Long id, @RequestParam boolean enabled,
//                                   @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
//        ChannelEntity existing = channelService.getChannel(id);
//        verifyResourceWorkspace(existing.getWorkspaceId(), workspaceId);
//        ChannelEntity channel = channelService.toggleChannel(id, enabled);
//        // 联动 ChannelManager：启用时启动，禁用时停止
//        if (enabled) {
//            channelManager.startChannel(channel);
//        } else {
//            channelManager.stopChannel(id);
//        }
//        auditEventService.record(enabled ? "ENABLE" : "DISABLE", "CHANNEL", String.valueOf(id), channel.getName(), null);
//        return R.ok(channel);
//    }
//
//    @RequireWorkspaceRole("admin")
//    @Operation(summary = "获取渠道运行状态（全局系统视图，仅管理员可见）")
//    @GetMapping("/status")
//    public R<Map<String, Object>> status() {
//        return R.ok(channelManager.getStatus());
//    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "获取指定渠道的实时健康状态（真连接状态，前端绿点应该绑这个）")
    @GetMapping("/{id}/health")
    public R<Map<String, Object>> health(@PathVariable Long id,
                                          @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        ChannelEntity channel = channelService.getChannel(id);
        verifyResourceWorkspace(channel.getWorkspaceId(), workspaceId);
        return R.ok(channelManager.getAdapter(id)
                .map(adapter -> adapter.health().toMap())
                .orElseGet(() -> {
                    // Adapter not in active map: either disabled, never started,
                    // or still booting. Surface as OUT_OF_SERVICE so the frontend
                    // dot stays gray instead of red.
                    Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("channelType", channel.getChannelType());
                    body.put("channelId", id);
                    body.put("status", "OUT_OF_SERVICE");
                    body.put("detail", Boolean.TRUE.equals(channel.getEnabled())
                            ? "channel enabled but adapter not active" : "channel disabled");
                    return body;
                }));
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "批量获取所有渠道健康状态")
    @GetMapping("/health")
    public R<List<Map<String, Object>>> healthAll(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long ws = workspaceId != null ? workspaceId : 1L;
        return R.ok(channelService.listChannelsByWorkspace(ws).stream()
                .map(c -> {
                    Map<String, Object> body = channelManager.getAdapter(c.getId())
                            .map(a -> a.health().toMap())
                            .orElseGet(() -> {
                                Map<String, Object> m = new java.util.LinkedHashMap<>();
                                m.put("channelType", c.getChannelType());
                                m.put("channelId", c.getId());
                                m.put("status", "OUT_OF_SERVICE");
                                m.put("detail", Boolean.TRUE.equals(c.getEnabled())
                                        ? "channel enabled but adapter not active" : "channel disabled");
                                return m;
                            });
                    body.put("name", c.getName());
                    body.put("enabled", Boolean.TRUE.equals(c.getEnabled()));
                    body.put("identity", parseIdentity(c.getIdentityJson()));
                    return body;
                })
                .toList());
    }

    /**
     * Parse identity_json into a map for the list-page card. Returns an
     * empty map for legacy rows that have not been re-verified yet, so the
     * frontend can render the type-level description as a fallback.
     */
    private Map<String, Object> parseIdentity(String identityJson) {
        if (identityJson == null || identityJson.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(identityJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("identity_json parse failed (treating as empty): {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "Pre-flight: validate draft channel config without persisting")
    @PostMapping("/preflight")
    public R<VerificationResult> preflight(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestBody PreflightRequest body) {
        long ws = workspaceId != null ? workspaceId : 1L;
        Map<String, Object> config = parseConfigJson(body.configJson());
        return verifierRegistry.find(body.channelType())
                .map(v -> R.ok(v.verify(new VerificationRequest(body.channelType(), config, ws))))
                .orElseGet(() -> R.ok(VerificationResult.skipped(
                        "No verifier registered for channel type '" + body.channelType()
                                + "' — skipping live check.")));
    }

    private Map<String, Object> parseConfigJson(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("preflight: invalid configJson, treating as empty: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Wizard Step 2 payload — channel type + draft configJson, no entity yet. */
    public record PreflightRequest(String channelType, String configJson) {}

    // ==================== Internal ====================

    private void verifyResourceWorkspace(Long resourceWorkspaceId, Long headerWorkspaceId) {
        long requestedWs = headerWorkspaceId != null ? headerWorkspaceId : 1L;
        if (resourceWorkspaceId != null && !resourceWorkspaceId.equals(requestedWs)) {
            throw new MateClawException("err.common.wrong_workspace", 403, "资源不属于当前工作区");
        }
    }
}
