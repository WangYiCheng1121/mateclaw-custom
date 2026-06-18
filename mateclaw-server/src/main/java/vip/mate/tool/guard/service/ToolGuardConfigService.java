package vip.mate.tool.guard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vip.mate.tool.guard.model.ToolGuardConfigEntity;
import vip.mate.tool.guard.platform.PlatformSecurityClient;
import vip.mate.tool.guard.platform.PlatformSecurityProperties;
import vip.mate.tool.guard.repository.ToolGuardConfigMapper;

import vip.mate.tool.guard.model.GuardSeverity;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具安全配置管理服务
 * <p>
 * 管理 mate_tool_guard_config 单行配置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolGuardConfigService {

    private final ToolGuardConfigMapper configMapper;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final PlatformSecurityClient platformClient;
    private final PlatformSecurityProperties securityProperties;

    /** 平台端配置本地缓存（volatile 保证可见性） */
    private volatile ToolGuardConfigEntity cachedPlatformConfig;

    /**
     * 启动时从平台端拉取配置到本地缓存
     */
    @PostConstruct
    void initPlatformCache() {
        if (securityProperties.isEnabled()) {
            refreshPlatformCache();
        }
    }

    /**
     * 定时刷新平台端配置缓存
     */
    @Scheduled(fixedDelayString = "${mateclaw.security.platform.config-refresh-interval-sec:60}000")
    void scheduledRefresh() {
        if (securityProperties.isEnabled()) {
            refreshPlatformCache();
        }
    }

    /**
     * 从平台端拉取配置，覆盖写入本地 H2，再刷新内存缓存
     */
    public void refreshPlatformCache() {
        try {
            ToolGuardConfigEntity platformConfig = platformClient.fetchConfig();
            if (platformConfig != null) {
                // 直接覆盖写入 H2，使 H2 与平台保持一致
                boolean isFirstLoad = this.cachedPlatformConfig == null;
                upsertToLocalDb(platformConfig);
                // 从 H2 重新读取作为内存缓存（确保内存=H2=平台）
                this.cachedPlatformConfig = readFromLocalDb();
                log.info("[ToolGuardConfig] Platform config {}refreshed → H2+memory (fileGuard={}, audit={}, sensitivePaths={})",
                        isFirstLoad ? "initialized: " : "",
                        platformConfig.getFileGuardEnabled(),
                        platformConfig.getAuditEnabled(),
                        platformConfig.getSensitivePathsJson() != null
                                ? platformConfig.getSensitivePathsJson()
                                : "empty");
            } else {
                log.warn("[ToolGuardConfig] Platform config fetch returned null — keeping existing data");
            }
        } catch (Exception e) {
            log.warn("[ToolGuardConfig] Failed to refresh platform config: {}", e.getMessage());
        }
    }

    /** 将平台配置覆盖写入本地 H2（若无则插入，有则全量更新） */
    private void upsertToLocalDb(ToolGuardConfigEntity platformConfig) {
        ToolGuardConfigEntity local = readFromLocalDb();
        if (local != null) {
            // 已有本地记录 → 全量覆盖更新
            platformConfig.setId(local.getId());
            platformConfig.setCreateTime(local.getCreateTime());
            configMapper.updateById(platformConfig);
        } else {
            configMapper.insert(platformConfig);
        }
    }

    /** 从本地 H2 读取单行配置 */
    private ToolGuardConfigEntity readFromLocalDb() {
        List<ToolGuardConfigEntity> configs = configMapper.selectList(
                new LambdaQueryWrapper<ToolGuardConfigEntity>().last("LIMIT 1"));
        return configs.isEmpty() ? null : configs.get(0);
    }

    /**
     * 获取配置，优先内存缓存，回退到本地 H2
     */
    public ToolGuardConfigEntity getConfig() {
        if (cachedPlatformConfig != null) {
            return cachedPlatformConfig;
        }
        // 回退到本地 H2
        ToolGuardConfigEntity local = readFromLocalDb();
        if (local != null) {
            return local;
        }
        return createDefaultConfig();
    }

    /**
     * 更新配置
     */
    public ToolGuardConfigEntity updateConfig(ToolGuardConfigEntity config) {
        ToolGuardConfigEntity existing = getConfig();
        if (config.getEnabled() != null) existing.setEnabled(config.getEnabled());
        if (config.getGuardScope() != null) existing.setGuardScope(config.getGuardScope());
        if (config.getGuardedToolsJson() != null) existing.setGuardedToolsJson(config.getGuardedToolsJson());
        if (config.getDeniedToolsJson() != null) existing.setDeniedToolsJson(config.getDeniedToolsJson());
        if (config.getFileGuardEnabled() != null) existing.setFileGuardEnabled(config.getFileGuardEnabled());
        if (config.getSensitivePathsJson() != null) existing.setSensitivePathsJson(config.getSensitivePathsJson());
        if (config.getAuditEnabled() != null) existing.setAuditEnabled(config.getAuditEnabled());
        if (config.getAuditMinSeverity() != null) existing.setAuditMinSeverity(config.getAuditMinSeverity());
        if (config.getAuditRetentionDays() != null) existing.setAuditRetentionDays(config.getAuditRetentionDays());
        configMapper.updateById(existing);
        // 通知 AgentService 刷新缓存（denied 工具列表变更需要重建 agent 的工具集）
        eventPublisher.publishEvent(new ToolGuardConfigChangedEvent(this));
        return existing;
    }

    /**
     * 配置变更事件，触发 agent 缓存刷新
     */
    public static class ToolGuardConfigChangedEvent extends org.springframework.context.ApplicationEvent {
        public ToolGuardConfigChangedEvent(Object source) {
            super(source);
        }
    }

    public boolean isEnabled() {
        // null → true（安全默认：未配置时启用 guard）
        return !Boolean.FALSE.equals(getConfig().getEnabled());
    }

    public Set<String> getDeniedTools() {
        String json = getConfig().getDeniedToolsJson();
        return parseJsonSet(json);
    }

    public Set<String> getGuardedTools() {
        ToolGuardConfigEntity config = getConfig();
        if ("all".equals(config.getGuardScope())) return null; // null = all tools
        return parseJsonSet(config.getGuardedToolsJson());
    }

    public boolean isFileGuardEnabled() {
        // null → true（安全默认：未配置时启用文件守卫）
        return !Boolean.FALSE.equals(getConfig().getFileGuardEnabled());
    }

    public List<String> getSensitivePaths() {
        String json = getConfig().getSensitivePathsJson();
        return parseJsonList(json);
    }

    // ==================== 审计配置 ====================

    public boolean isAuditEnabled() {
        // null → true（安全默认：未配置时启用审计）
        return !Boolean.FALSE.equals(getConfig().getAuditEnabled());
    }

    public GuardSeverity getAuditMinSeverity() {
        String s = getConfig().getAuditMinSeverity();
        if (s == null || s.isBlank()) return GuardSeverity.INFO;
        try {
            return GuardSeverity.valueOf(s);
        } catch (IllegalArgumentException e) {
            return GuardSeverity.INFO;
        }
    }

    public int getAuditRetentionDays() {
        Integer days = getConfig().getAuditRetentionDays();
        return days != null ? days : 90;
    }

    // ==================== 内部方法 ====================

    private ToolGuardConfigEntity createDefaultConfig() {
        ToolGuardConfigEntity config = new ToolGuardConfigEntity();
        config.setEnabled(true);
        config.setGuardScope("all");
        config.setFileGuardEnabled(true);
        configMapper.insert(config);
        log.info("[ToolGuardConfig] Created default config");
        return config;
    }

    private Set<String> parseJsonSet(String json) {
        if (json == null || json.isBlank()) return Set.of();
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<>() {});
            return Set.copyOf(list);
        } catch (JsonProcessingException e) {
            log.warn("[ToolGuardConfig] Failed to parse JSON set: {}", e.getMessage());
            return Set.of();
        }
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            // Windows paths (e.g. C:\Users\...) contain backslashes that are
            // invalid JSON escapes (\U, \l, \D, \A, etc.). When the JSON is stored
            // in H2, those backslashes are not escaped, which makes Jackson reject
            // the string. Retry after escaping lone backslashes.
            String fixed = fixJsonBackslashEscaping(json);
            try {
                List<String> result = objectMapper.readValue(fixed, new TypeReference<>() {});
                log.info("[ToolGuardConfig] Parsed JSON list after backslash fix: {} entries", result.size());
                return result;
            } catch (JsonProcessingException e2) {
                log.warn("[ToolGuardConfig] Failed to parse JSON list even after fixing backslashes: {}", e2.getMessage());
            }
            log.warn("[ToolGuardConfig] Failed to parse JSON list: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Escape lone backslashes in JSON that are not part of valid JSON escape sequences.
     * <p>
     * Valid JSON escapes: {@code \" \\ \/ \b \f \n \r \t} plus 4-hex-digit Unicode.
     * Any other backslash (e.g. Windows path {@code C:\Users}) is doubled so
     * Jackson can parse the string correctly.
     */
    static String fixJsonBackslashEscaping(String json) {
        // Match a backslash NOT followed by a valid JSON escape starter:
        //   \  /  b  f  n  r  t  u
        // Replace it with \\(doubled backslash).
        return BACKSLASH_FIX_PATTERN.matcher(json).replaceAll("\\\\\\\\");
    }

    /** @see #fixJsonBackslashEscaping(String) */
    private static final Pattern BACKSLASH_FIX_PATTERN =
            Pattern.compile("\\\\(?![\\\\/bfnrtu])");
}
