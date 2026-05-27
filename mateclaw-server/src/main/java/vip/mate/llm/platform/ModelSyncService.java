package vip.mate.llm.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import vip.mate.auth.config.PlatformOAuth2Config;
import vip.mate.llm.event.ModelConfigChangedEvent;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.platform.PlatformModelClient.AssignedModelsResponse;
import vip.mate.llm.repository.ModelConfigMapper;
import vip.mate.llm.repository.ModelProviderMapper;
import vip.mate.system.model.SystemSettingEntity;
import vip.mate.system.repository.SystemSettingMapper;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 模型同步服务
 * <p>
 * 定时/手动从平台端拉取被分配的 Provider + Model 配置，同步到本地数据库。
 * 同步策略：
 * <ul>
 *   <li>Provider：平台端有 → upsert；平台端无且非本地类型 → 删除</li>
 *   <li>Model：平台端有 → upsert；平台端无且所属 Provider 非本地 → 删除</li>
 *   <li>本地 Provider（is_local=true，如 Ollama/LM Studio）不参与平台同步</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelSyncService {

    private final PlatformModelClient platformModelClient;
    private final PlatformModelProperties syncProperties;
    private final PlatformOAuth2Config platformConfig;
    private final ModelProviderMapper modelProviderMapper;
    private final ModelConfigMapper modelConfigMapper;
    private final SystemSettingMapper systemSettingMapper;
    private final ApplicationEventPublisher eventPublisher;

    private static final String SYSTEM_SETTING_DEFAULT_EMBEDDING_ID = "embedding.default.model.id";

    private volatile LocalDateTime lastSyncTime;
    private volatile String lastSyncStatus = "NEVER";

    @PostConstruct
    public void init() {
        if (syncProperties.isEnabled() && platformConfig.isEnabled()) {
            log.info("ModelSyncService initialized, sync interval: {}s",
                    syncProperties.getSyncIntervalSeconds());
        } else {
            log.info("Platform model sync disabled, running in local-only mode");
        }
    }

    /**
     * 定时同步
     */
    @Scheduled(fixedDelayString = "${mateclaw.model.sync.sync-interval-seconds:300}000")
    public void scheduledSync() {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            return;
        }
        syncFromPlatform();
    }

    /**
     * 手动触发同步（Controller 调用）
     */
    public SyncResult syncFromPlatform() {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            return new SyncResult(false, "Platform sync disabled", 0, 0, 0);
        }

        log.info("Starting model sync from platform...");
        AssignedModelsResponse remoteData = platformModelClient.fetchAssignedModels();

        if (remoteData == null && !platformModelClient.isReachable()) {
            lastSyncStatus = "FAILED";
            log.warn("Platform unreachable, skip model sync");
            return new SyncResult(false, "Platform unreachable", 0, 0, 0);
        }

        if (remoteData == null) {
            lastSyncStatus = "FAILED";
            return new SyncResult(false, "Failed to fetch data from platform", 0, 0, 0);
        }

        int upsertedProviders = 0, upsertedModels = 0, deletedModels = 0, deletedProviders = 0;

        // ==================== 同步 Provider ====================
        List<ModelProviderEntity> remoteProviders = remoteData.getProviders();
        if (remoteProviders == null) remoteProviders = Collections.emptyList();

        Set<String> remoteProviderIds = new HashSet<>();
        for (ModelProviderEntity remote : remoteProviders) {
            remoteProviderIds.add(remote.getProviderId());
            ModelProviderEntity local = modelProviderMapper.selectById(remote.getProviderId());
            if (local == null) {
                // 新增
                modelProviderMapper.insert(remote);
                upsertedProviders++;
                log.debug("Synced new provider from platform: {}", remote.getProviderId());
            } else if (!Boolean.TRUE.equals(local.getIsLocal())) {
                // 更新非本地 Provider（本地 Provider 不被覆盖）
                remote.setCreateTime(local.getCreateTime());
                modelProviderMapper.updateById(remote);
                upsertedProviders++;
                log.debug("Synced updated provider from platform: {}", remote.getProviderId());
            }
        }

        // ==================== 同步 Model ====================
        List<ModelConfigEntity> remoteModels = remoteData.getModels();
        if (remoteModels == null) remoteModels = Collections.emptyList();

        Set<String> remoteModelKeys = new HashSet<>();
        for (ModelConfigEntity remote : remoteModels) {
            String key = remote.getProvider() + "/" + remote.getModelName();
            remoteModelKeys.add(key);

            ModelConfigEntity local = modelConfigMapper.selectOne(
                    new LambdaQueryWrapper<ModelConfigEntity>()
                            .eq(ModelConfigEntity::getProvider, remote.getProvider())
                            .eq(ModelConfigEntity::getModelName, remote.getModelName())
                            .last("LIMIT 1"));

            if (local == null) {
                // 新增：但需检查ID是否已存在（避免主键冲突）
                ModelConfigEntity existingById = modelConfigMapper.selectById(remote.getId());
                if (existingById != null) {
                    // ID已存在，执行更新操作
                    existingById.setName(remote.getName());
                    existingById.setDescription(remote.getDescription());
                    existingById.setTemperature(remote.getTemperature());
                    existingById.setMaxTokens(remote.getMaxTokens());
                    existingById.setMaxInputTokens(remote.getMaxInputTokens());
                    existingById.setTopP(remote.getTopP());
                    existingById.setEnableSearch(remote.getEnableSearch());
                    existingById.setSearchStrategy(remote.getSearchStrategy());
                    existingById.setBuiltin(remote.getBuiltin());
                    existingById.setEnabled(remote.getEnabled());
                    existingById.setIsDefault(remote.getIsDefault());
                    existingById.setModelType(remote.getModelType());
                    modelConfigMapper.updateById(existingById);
                    log.debug("Updated existing model by ID from platform: {}/{} (id={})", 
                            remote.getProvider(), remote.getModelName(), remote.getId());
                } else {
                    // ID也不存在，执行插入；兜底处理 @TableLogic 逻辑删除导致的主键冲突
                    try {
                        modelConfigMapper.insert(remote);
                        log.debug("Synced new model from platform: {}/{}", remote.getProvider(), remote.getModelName());
                    } catch (Exception e) {
                        // selectById 因 @TableLogic 过滤了已删除记录，但物理主键仍存在，触发唯一约束冲突
                        log.info("Resurrecting logically deleted model: {}/{} (id={})",
                                remote.getProvider(), remote.getModelName(), remote.getId());
                        modelConfigMapper.physicalRestore(remote.getId());
                        modelConfigMapper.updateById(remote);
                    }
                }
                upsertedModels++;
            } else {
                // 更新：同步关键配置字段
                local.setName(remote.getName());
                local.setDescription(remote.getDescription());
                local.setTemperature(remote.getTemperature());
                local.setMaxTokens(remote.getMaxTokens());
                local.setMaxInputTokens(remote.getMaxInputTokens());
                local.setTopP(remote.getTopP());
                local.setEnableSearch(remote.getEnableSearch());
                local.setSearchStrategy(remote.getSearchStrategy());
                local.setBuiltin(remote.getBuiltin());
                local.setEnabled(remote.getEnabled());
                local.setIsDefault(remote.getIsDefault());
                local.setModelType(remote.getModelType());
                modelConfigMapper.updateById(local);
                upsertedModels++;
            }
        }

        // 删除：本地有但平台端已不再分配的非本地 Provider 模型
        List<ModelConfigEntity> allLocalModels = modelConfigMapper.selectList(
                new LambdaQueryWrapper<ModelConfigEntity>());
        for (ModelConfigEntity local : allLocalModels) {
            // 跳过本地 Provider 的模型
            ModelProviderEntity provider = modelProviderMapper.selectById(local.getProvider());
            if (provider != null && Boolean.TRUE.equals(provider.getIsLocal())) {
                continue;
            }
            String key = local.getProvider() + "/" + local.getModelName();
            if (!remoteModelKeys.contains(key)) {
                modelConfigMapper.deleteById(local.getId());
                deletedModels++;
                log.debug("Deleted model — no longer assigned by platform: {}", key);
            }
        }

        // ==================== 同步默认模型设置 ====================
        if (StringUtils.hasText(remoteData.getDefaultProvider())
                && StringUtils.hasText(remoteData.getDefaultModelName())) {
            syncDefaultModel(remoteData.getDefaultProvider(), remoteData.getDefaultModelName());
        }

        // 同步默认 Embedding 设置
        if (StringUtils.hasText(remoteData.getDefaultEmbeddingModelId())) {
            syncDefaultEmbedding(remoteData.getDefaultEmbeddingModelId());
        }

        // 删除：本地有但平台端已不再分配的非本地 Provider
        List<ModelProviderEntity> allLocalProviders = modelProviderMapper.selectList(
                new LambdaQueryWrapper<ModelProviderEntity>());
        for (ModelProviderEntity local : allLocalProviders) {
            // 跳过本地 Provider
            if (Boolean.TRUE.equals(local.getIsLocal())) {
                continue;
            }
            if (!remoteProviderIds.contains(local.getProviderId())) {
                modelProviderMapper.deleteById(local.getProviderId());
                deletedProviders++;
                log.debug("Deleted provider — no longer assigned by platform: {}", local.getProviderId());
            }
        }

        // 发布变更事件 → 刷新 Agent 缓存
        eventPublisher.publishEvent(new ModelConfigChangedEvent("platform-sync"));

        lastSyncTime = LocalDateTime.now();
        lastSyncStatus = "SUCCESS";
        log.info("Model sync completed: {} providers upserted, {} models upserted, {} models deleted, {} providers deleted",
                upsertedProviders, upsertedModels, deletedModels, deletedProviders);

        return new SyncResult(true, "Sync completed", upsertedProviders, upsertedModels, deletedModels);
    }

    /**
     * 获取同步状态
     */
    public Map<String, Object> getSyncStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", syncProperties.isEnabled() && platformConfig.isEnabled());
        status.put("lastSyncTime", lastSyncTime);
        status.put("lastSyncStatus", lastSyncStatus);
        status.put("platformReachable", syncProperties.isEnabled() && platformModelClient.isReachable());
        status.put("syncIntervalSeconds", syncProperties.getSyncIntervalSeconds());
        return status;
    }

    // ==================== 内部方法 ====================

    private void syncDefaultModel(String provider, String modelName) {
        // 清除所有旧默认
        List<ModelConfigEntity> oldDefaults = modelConfigMapper.selectList(
                new LambdaQueryWrapper<ModelConfigEntity>().eq(ModelConfigEntity::getIsDefault, true));
        for (ModelConfigEntity old : oldDefaults) {
            old.setIsDefault(false);
            modelConfigMapper.updateById(old);
        }
        // 设置新默认
        ModelConfigEntity target = modelConfigMapper.selectOne(
                new LambdaQueryWrapper<ModelConfigEntity>()
                        .eq(ModelConfigEntity::getProvider, provider)
                        .eq(ModelConfigEntity::getModelName, modelName)
                        .last("LIMIT 1"));
        if (target != null) {
            target.setIsDefault(true);
            target.setEnabled(true);
            modelConfigMapper.updateById(target);
            log.info("Synced default model from platform: {}/{}", provider, modelName);
        }
    }

    private void syncDefaultEmbedding(String modelId) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SystemSettingEntity> qw =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SystemSettingEntity>()
                        .eq(SystemSettingEntity::getSettingKey, SYSTEM_SETTING_DEFAULT_EMBEDDING_ID)
                        .last("LIMIT 1");
        SystemSettingEntity existing = systemSettingMapper.selectOne(qw);
        if (existing != null) {
            existing.setSettingValue(modelId);
            systemSettingMapper.updateById(existing);
        } else {
            SystemSettingEntity fresh = new SystemSettingEntity();
            fresh.setSettingKey(SYSTEM_SETTING_DEFAULT_EMBEDDING_ID);
            fresh.setSettingValue(modelId);
            fresh.setDescription("Default embedding model id for wiki semantic search");
            systemSettingMapper.insert(fresh);
        }
        log.debug("Synced default embedding model from platform: {}", modelId);
    }

    /**
     * 同步结果
     */
    public record SyncResult(boolean success, String message, int upsertedProviders, int upsertedModels, int deletedModels) {}
}
