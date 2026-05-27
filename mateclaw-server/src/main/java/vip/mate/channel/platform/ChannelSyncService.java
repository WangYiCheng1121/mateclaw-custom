package vip.mate.channel.platform;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vip.mate.auth.config.PlatformOAuth2Config;
import vip.mate.channel.ChannelManager;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.service.ChannelService;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 渠道同步服务
 * <p>
 * 定时/手动从平台端拉取渠道启用/禁用状态，并联动本地 ChannelManager 执行启停操作。
 * <p>
 * 同步策略：
 * <ul>
 *   <li>平台启用 → 本地若有该类型渠道且配置完整，则启动 Adapter</li>
 *   <li>平台禁用 → 本地若正在运行该类型渠道，则停止 Adapter</li>
 *   <li>本地 enabled 字段由平台同步覆盖（平台端对启停有最终决定权）</li>
 *   <li>本地 configJson 不受平台影响（配置由客户端自行维护）</li>
 * </ul>
 * <p>
 * 特殊处理：
 * - 平台不可达时跳过同步，保留上次状态（不做降级停止）
 * - 同步前校验渠道是否有有效配置（configJson 非空），无配置则不启动
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelSyncService {

    private final PlatformChannelClient platformChannelClient;
    private final PlatformChannelProperties syncProperties;
    private final PlatformOAuth2Config platformConfig;
    private final ChannelService channelService;
    private final ChannelManager channelManager;

    /** 平台端渠道状态缓存：channelType -> enabled */
    private volatile Map<String, Boolean> platformStatusCache = new HashMap<>();

    private volatile LocalDateTime lastSyncTime;
    private volatile String lastSyncStatus = "NEVER";

    @PostConstruct
    public void init() {
        if (syncProperties.isEnabled() && platformConfig.isEnabled()) {
            log.info("ChannelSyncService initialized, sync interval: {}s",
                    syncProperties.getSyncIntervalSeconds());
        } else {
            log.info("Platform channel sync disabled, running in local-only mode");
        }
    }

    /**
     * 定时同步（间隔由配置控制，默认 60 秒）
     */
    @Scheduled(fixedDelayString = "${mateclaw.channel.sync.sync-interval-seconds:60}000")
    public void scheduledSync() {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            return;
        }
        syncFromPlatform();
    }

    /**
     * 手动触发同步（Controller 调用）
     *
     * @return 同步结果摘要
     */
    public SyncResult syncFromPlatform() {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            return new SyncResult(false, "Platform sync disabled", 0, 0);
        }

        log.info("Starting channel status sync from platform...");
        List<PlatformChannelClient.ChannelStatusDTO> remoteStatuses =
                platformChannelClient.fetchChannelStatus();

        if (remoteStatuses.isEmpty() && !platformChannelClient.isReachable()) {
            lastSyncStatus = "FAILED";
            log.warn("Platform unreachable, skip channel sync");
            return new SyncResult(false, "Platform unreachable", 0, 0);
        }

        // 更新缓存
        Map<String, Boolean> newCache = remoteStatuses.stream()
                .collect(Collectors.toMap(
                        PlatformChannelClient.ChannelStatusDTO::getChannelType,
                        PlatformChannelClient.ChannelStatusDTO::isEnabled,
                        (a, b) -> a));
        this.platformStatusCache = newCache;

        // 联动本地渠道状态
        // 规则：平台启用只是前提（不自动启动本地），平台禁用则强制停止本地
        int started = 0, stopped = 0;
        List<ChannelEntity> localChannels = channelService.listChannels();

        for (ChannelEntity local : localChannels) {
            Boolean platformEnabled = newCache.get(local.getChannelType());
            if (platformEnabled == null) {
                // 平台未返回该类型（可能是非标准渠道如 webchat/web），保持本地状态
                continue;
            }

            boolean wasEnabled = Boolean.TRUE.equals(local.getEnabled());

            if (!platformEnabled && wasEnabled) {
                // 平台禁用 → 强制停止本地渠道
                channelManager.stopChannel(local.getId());
                channelService.toggleChannel(local.getId(), false);
                stopped++;
                log.info("Channel {} disabled by platform sync", local.getName());
            }
            // 平台启用时不自动启动本地渠道，由客户端自主决定启停
        }

        lastSyncTime = LocalDateTime.now();
        lastSyncStatus = "SUCCESS";
        log.info("Channel sync completed: {} started, {} stopped", started, stopped);

        return new SyncResult(true, "Sync completed", started, stopped);
    }

    /**
     * 校验指定渠道类型是否被平台启用
     * <p>
     * 在执行通讯操作前调用此方法，如果平台禁用则应拒绝执行。
     * 缓存为空时（首次启动尚未同步 / 同步失败）默认放行，避免误阻塞。
     *
     * @param channelType 渠道类型
     * @return true=平台已启用或缓存未填充，false=平台已明确禁用
     */
    public boolean isPlatformEnabled(String channelType) {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            // 同步未启用时，默认允许（本地自治模式）
            return true;
        }
        // 缓存为空时默认放行（首次启动尚未同步 / 同步失败），避免误阻塞
        if (platformStatusCache.isEmpty()) {
            return true;
        }
        return Boolean.TRUE.equals(platformStatusCache.get(channelType));
    }

    /**
     * 获取同步状态（供管理页面展示）
     */
    public Map<String, Object> getSyncStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", syncProperties.isEnabled() && platformConfig.isEnabled());
        status.put("lastSyncTime", lastSyncTime);
        status.put("lastSyncStatus", lastSyncStatus);
        status.put("platformReachable", syncProperties.isEnabled() && platformChannelClient.isReachable());
        status.put("syncIntervalSeconds", syncProperties.getSyncIntervalSeconds());
        status.put("platformStatusCache", platformStatusCache);
        return status;
    }

    /**
     * 获取平台端渠道状态缓存（供 Controller 返回给前端展示）
     */
    public Map<String, Boolean> getPlatformStatusCache() {
        return Collections.unmodifiableMap(platformStatusCache);
    }

    private static final Set<String> MANAGED_CHANNEL_TYPES =
            Set.of("weixin", "qq", "dingtalk", "feishu");

    /**
     * 获取平台端启用的渠道类型集合
     * <p>
     * 返回平台端当前 enabled=true 的渠道类型，最多只包含
     * {@link #MANAGED_CHANNEL_TYPES} 范围内的类型。
     * 如果同步未启用（本地自治模式），返回所有平台管控类型（默认全部可见）。
     * 如果同步已启用但缓存尚未填充（首次启动/同步失败），同样返回所有平台管控类型。
     *
     * @return 启用的类型集合（不会为 null，最多包含 weixin/qq/dingtalk/feishu）
     */
    public Set<String> getPlatformEnabledTypes() {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            // 本地自治模式：所有平台管控类型默认可见
            return MANAGED_CHANNEL_TYPES;
        }
        // 缓存为空时（首次启动尚未同步 / 同步失败），默认展示全部平台管控类型
        if (platformStatusCache.isEmpty()) {
            return MANAGED_CHANNEL_TYPES;
        }
        return platformStatusCache.entrySet().stream()
                .filter(e -> Boolean.TRUE.equals(e.getValue()) && MANAGED_CHANNEL_TYPES.contains(e.getKey()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * 校验渠道是否有有效配置
     */
    private boolean hasValidConfig(ChannelEntity channel) {
        return channel.getConfigJson() != null && !channel.getConfigJson().isBlank();
    }

    /**
     * 同步结果
     */
    public record SyncResult(boolean success, String message, int started, int stopped) {}
}
