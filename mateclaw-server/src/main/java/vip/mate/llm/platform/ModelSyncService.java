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

/**
 * 模型同步服务
 * <p>
 * 定时/手动从平台端拉取被分配的 Provider + Model 配置，同步到本地数据库。
 * 同步策略：
 * <ul>
 *   <li>Provider：平台端有 → upsert（enabled 强制 true）；平台端无 → 删除</li>
 *   <li>Model：平台端有 → upsert；平台端无 → 删除</li>
 *   <li>所有 Provider 和 Model 均以平台端数据为准，无例外</li>
 *   <li>敏感凭据（apiKey、baseUrl）平台未下发时保留本地已有值</li>
 * </ul>
 * <p>
 * 平台端返回的是独立 DTO（{@code SyncProviderItem} / {@code ModelConfigVO}），
 * 此处负责转换到本地实体后再持久化。
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

    @Scheduled(fixedDelayString = "${mateclaw.model.sync.sync-interval-seconds:300}000")
    public void scheduledSync() {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            return;
        }
        syncFromPlatform();
    }

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
        List<SyncProviderItem> remoteProviders = remoteData.getProviders();
        if (remoteProviders == null) remoteProviders = Collections.emptyList();

        Set<String> remoteProviderIds = new HashSet<>();
        for (SyncProviderItem remote : remoteProviders) {
            remoteProviderIds.add(remote.getProviderId());
            ModelProviderEntity local = modelProviderMapper.selectById(remote.getProviderId());
            if (local == null) {
                modelProviderMapper.insert(toProviderEntity(remote));
                upsertedProviders++;
                log.debug("Synced new provider from platform: {}", remote.getProviderId());
            } else {
                mergeProviderFields(local, remote);
                modelProviderMapper.updateById(local);
                upsertedProviders++;
                log.debug("Synced updated provider from platform: {}", remote.getProviderId());
            }
        }

        // ==================== 同步 Model ====================
        List<ModelConfigVO> remoteModels = remoteData.getModels();
        if (remoteModels == null) remoteModels = Collections.emptyList();

        Set<String> remoteModelKeys = new HashSet<>();
        for (ModelConfigVO remote : remoteModels) {
            String key = remote.getProvider() + "/" + remote.getModelName();
            remoteModelKeys.add(key);

            ModelConfigEntity local = modelConfigMapper.selectOne(
                    new LambdaQueryWrapper<ModelConfigEntity>()
                            .eq(ModelConfigEntity::getProvider, remote.getProvider())
                            .eq(ModelConfigEntity::getModelName, remote.getModelName())
                            .last("LIMIT 1"));

            if (local == null) {
                ModelConfigEntity newEntity = toModelEntity(remote);

                ModelConfigEntity existingById = modelConfigMapper.selectById(newEntity.getId());
                if (existingById != null) {
                    applyModelFields(existingById, remote);
                    modelConfigMapper.updateById(existingById);
                    log.debug("Updated existing model by ID from platform: {}/{} (id={})",
                            remote.getProvider(), remote.getModelName(), remote.getId());
                } else {
                    try {
                        modelConfigMapper.insert(newEntity);
                        log.debug("Synced new model from platform: {}/{}", remote.getProvider(), remote.getModelName());
                    } catch (Exception e) {
                        log.info("Resurrecting logically deleted model: {}/{} (id={})",
                                remote.getProvider(), remote.getModelName(), remote.getId());
                        modelConfigMapper.physicalRestore(remote.getId());
                        modelConfigMapper.updateById(newEntity);
                    }
                }
                upsertedModels++;
            } else {
                applyModelFields(local, remote);
                modelConfigMapper.updateById(local);
                upsertedModels++;
            }
        }

        // 删除：本地有但平台端已不再分配的模型
        List<ModelConfigEntity> allLocalModels = modelConfigMapper.selectList(
                new LambdaQueryWrapper<ModelConfigEntity>());
        for (ModelConfigEntity local : allLocalModels) {
            String key = local.getProvider() + "/" + local.getModelName();
            if (!remoteModelKeys.contains(key)) {
                modelConfigMapper.deleteById(local.getId());
                deletedModels++;
                log.debug("Deleted model - no longer assigned by platform: {}", key);
            }
        }

        // ==================== 同步默认模型设置 ====================
        if (StringUtils.hasText(remoteData.getDefaultProvider())
                && StringUtils.hasText(remoteData.getDefaultModelName())) {
            syncDefaultModel(remoteData.getDefaultProvider(), remoteData.getDefaultModelName());
        }

        if (StringUtils.hasText(remoteData.getDefaultEmbeddingModelId())) {
            syncDefaultEmbedding(remoteData.getDefaultEmbeddingModelId());
        }

        // 删除：本地有但平台端已不再分配的 Provider
        List<ModelProviderEntity> allLocalProviders = modelProviderMapper.selectList(
                new LambdaQueryWrapper<ModelProviderEntity>());
        for (ModelProviderEntity local : allLocalProviders) {
            if (!remoteProviderIds.contains(local.getProviderId())) {
                modelProviderMapper.deleteById(local.getProviderId());
                deletedProviders++;
                log.debug("Deleted provider - no longer assigned by platform: {}", local.getProviderId());
            }
        }

        eventPublisher.publishEvent(new ModelConfigChangedEvent("platform-sync"));

        lastSyncTime = LocalDateTime.now();
        lastSyncStatus = "SUCCESS";
        log.info("Model sync completed: {} providers upserted, {} models upserted, {} models deleted, {} providers deleted",
                upsertedProviders, upsertedModels, deletedModels, deletedProviders);

        return new SyncResult(true, "Sync completed", upsertedProviders, upsertedModels, deletedModels);
    }

    public Map<String, Object> getSyncStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", syncProperties.isEnabled() && platformConfig.isEnabled());
        status.put("lastSyncTime", lastSyncTime);
        status.put("lastSyncStatus", lastSyncStatus);
        status.put("platformReachable", syncProperties.isEnabled() && platformModelClient.isReachable());
        status.put("syncIntervalSeconds", syncProperties.getSyncIntervalSeconds());
        return status;
    }

    // ==================== DTO -> Entity ====================

    private ModelProviderEntity toProviderEntity(SyncProviderItem dto) {
        ModelProviderEntity entity = new ModelProviderEntity();
        entity.setProviderId(dto.getProviderId());
        entity.setName(dto.getName());
        entity.setApiKeyPrefix(dto.getApiKeyPrefix());
        entity.setChatModel(dto.getChatModel());
        entity.setApiKey(dto.getApiKey());
        entity.setBaseUrl(dto.getBaseUrl());
        entity.setGenerateKwargs(dto.getGenerateKwargs());
        entity.setIsCustom(dto.getIsCustom());
        entity.setIsLocal(dto.getIsLocal());
        entity.setSupportConnectionCheck(dto.getSupportConnectionCheck());
        entity.setFreezeUrl(dto.getFreezeUrl());
        entity.setRequireApiKey(dto.getRequireApiKey());
        entity.setAuthType(dto.getAuthType());
        entity.setEnabled(true);
        return entity;
    }

    private ModelConfigEntity toModelEntity(ModelConfigVO dto) {
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setId(dto.getId());
        applyModelFields(entity, dto);
        return entity;
    }

    // ==================== 字段合并 ====================

    private void mergeProviderFields(ModelProviderEntity local, SyncProviderItem remote) {
        local.setName(remote.getName());
        local.setApiKeyPrefix(remote.getApiKeyPrefix());
        local.setChatModel(remote.getChatModel());
        local.setGenerateKwargs(remote.getGenerateKwargs());
        local.setIsCustom(remote.getIsCustom());
        local.setIsLocal(remote.getIsLocal());
        local.setSupportConnectionCheck(remote.getSupportConnectionCheck());
        local.setFreezeUrl(remote.getFreezeUrl());
        local.setRequireApiKey(remote.getRequireApiKey());
        local.setAuthType(remote.getAuthType());
        local.setEnabled(true);

        if (StringUtils.hasText(remote.getApiKey())) {
            local.setApiKey(remote.getApiKey());
        }
        if (StringUtils.hasText(remote.getBaseUrl())) {
            local.setBaseUrl(remote.getBaseUrl());
        }
    }

    private void applyModelFields(ModelConfigEntity target, ModelConfigVO remote) {
        target.setName(remote.getName());
        target.setProvider(remote.getProvider());
        target.setModelName(remote.getModelName());
        target.setDescription(remote.getDescription());
        target.setTemperature(remote.getTemperature());
        target.setMaxTokens(remote.getMaxTokens());
        target.setMaxInputTokens(remote.getMaxInputTokens());
        target.setTopP(remote.getTopP());
        target.setEnableSearch(remote.getEnableSearch());
        target.setSearchStrategy(remote.getSearchStrategy());
        target.setBuiltin(remote.getBuiltin());
        target.setEnabled(remote.getEnabled());
        target.setIsDefault(remote.getIsDefault());
        target.setModelType(remote.getModelType());
    }

    // ==================== 默认值同步 ====================

    private void syncDefaultModel(String provider, String modelName) {
        List<ModelConfigEntity> oldDefaults = modelConfigMapper.selectList(
                new LambdaQueryWrapper<ModelConfigEntity>().eq(ModelConfigEntity::getIsDefault, true));
        for (ModelConfigEntity old : oldDefaults) {
            old.setIsDefault(false);
            modelConfigMapper.updateById(old);
        }
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
        LambdaQueryWrapper<SystemSettingEntity> qw =
                new LambdaQueryWrapper<SystemSettingEntity>()
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

    public record SyncResult(boolean success, String message, int upsertedProviders, int upsertedModels, int deletedModels) {}
}
