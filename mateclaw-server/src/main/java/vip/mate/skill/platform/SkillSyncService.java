package vip.mate.skill.platform;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vip.mate.auth.config.PlatformOAuth2Config;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.SkillFrontmatterParser;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.secret.SkillSecretService;
import vip.mate.skill.workspace.SkillFileSyncer;
import vip.mate.skill.workspace.SkillWorkspaceManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 技能同步服务
 * <p>
 * 定时/手动从平台端拉取被分配的技能，同步到本地数据库。
 * 同步策略：
 * <ul>
 *   <li>新增：平台端有、本地无 → 插入（默认 installed=false, enabled=false，需用户手动安装）</li>
 *   <li>更新：平台端有、本地有但内容不同 → 更新内容（不覆盖客户端本地 enabled 状态）</li>
 *   <li>移除：平台端无、本地有且非内置 → 标记 platformStatus="REMOVED" 并强制禁用（保留数据行，重新分配时恢复）</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillSyncService {

    private static final String PLATFORM_SECRET_KEY = "PLATFORM_SECRET";

    private final PlatformSkillClient platformSkillClient;
    private final PlatformSkillProperties syncProperties;
    private final PlatformOAuth2Config platformConfig;
    private final SkillMapper skillMapper;
    private final SkillRuntimeService runtimeService;
    private final SkillWorkspaceManager workspaceManager;
    private final SkillSecretService skillSecretService;
    private final SkillFileSyncer skillFileSyncer;
    private final SkillFrontmatterParser frontmatterParser;

    /**
     * AES 加密密钥，与 {@link SkillSecretService} 共用同一把 key，
     * 确保两处加解密结果一致。
     */
    @Value("${mateclaw.datasource.encrypt-key:MateClaw@2024Key!}")
    private String encryptKey;

    private volatile LocalDateTime lastSyncTime;
    private volatile String lastSyncStatus = "NEVER";

    /** 平台目录树缓存（定时同步刷新，Controller 查询时读取，避免每次请求调平台API） */
    private volatile PlatformTreeNode cachedCategoryTree;

    @PostConstruct
    public void init() {
        if (syncProperties.isEnabled() && platformConfig.isEnabled()) {
            log.info("SkillSyncService initialized, sync interval: {}s",
                    syncProperties.getSyncIntervalSeconds());
        } else {
            log.info("Platform skill sync disabled, running in local-only mode");
        }
    }

    /**
     * 定时同步（cron 由配置控制，默认 5 分钟）
     */
    @Scheduled(fixedDelayString = "${mateclaw.skill.sync.sync-interval-seconds:300}000")
    public void scheduledSync() {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            return;
        }
        syncFromPlatform();
        refreshCategoryTree();
    }

    /**
     * 获取平台目录树缓存（供 Controller 查询使用，避免每次请求调平台API）
     */
    public PlatformTreeNode getCategoryTree() {
        if (cachedCategoryTree == null) {
            refreshCategoryTree();
        }
        return cachedCategoryTree;
    }

    /**
     * 刷新目录树缓存（平台不可达时保留旧缓存）
     */
    private void refreshCategoryTree() {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            return;
        }
        try {
            PlatformTreeNode tree = platformSkillClient.fetchSkillCategories();
            if (tree != null) {
                this.cachedCategoryTree = tree;
                log.debug("Category tree cache refreshed");
            } else {
                log.debug("Category tree fetch returned null, keeping stale cache");
            }
        } catch (Exception e) {
            log.warn("Failed to refresh category tree cache: {}", e.getMessage());
        }
    }

    /**
     * 从平台同步技能数据
     *
     * @return 同步结果摘要
     */
    public SyncResult syncFromPlatform() {
        if (!syncProperties.isEnabled() || !platformConfig.isEnabled()) {
            return new SyncResult(false, "Platform sync disabled", 0, 0, 0);
        }

        log.info("Starting skill sync from platform...");
        List<SkillEntity> remoteSkills = platformSkillClient.fetchAssignedSkills();

        if (remoteSkills.isEmpty() && !platformSkillClient.isReachable()) {
            lastSyncStatus = "FAILED";
            log.warn("Platform unreachable, skip sync");
            return new SyncResult(false, "Platform unreachable", 0, 0, 0);
        }

        int inserted = 0, updated = 0, disabled = 0;

        // 获取本地所有非内置技能（内置技能不参与平台同步）
        List<SkillEntity> localSkills = skillMapper.selectList(
                new LambdaQueryWrapper<SkillEntity>()
                        .eq(SkillEntity::getBuiltin, false));

        Map<String, SkillEntity> localByName = localSkills.stream()
                .collect(java.util.stream.Collectors.toMap(SkillEntity::getName, s -> s, (a, b) -> a));

        Set<String> remoteNames = new HashSet<>();

        for (SkillEntity remote : remoteSkills) {
            // 跳过 builtin 技能：内置技能由种子数据管理，不参与平台同步
            if (Boolean.TRUE.equals(remote.getBuiltin())) {
                continue;
            }

            remoteNames.add(remote.getName());
            SkillEntity local = localByName.get(remote.getName());

            if (local == null) {
                // 新增：平台有，本地无
                remote.setBuiltin(false);
                // 平台同步过来的技能默认未安装且不启用，
                // 需用户在前端执行"安装"操作后（installed=true）方可启用/禁用。
                remote.setInstalled(false);
                remote.setEnabled(false);
                // 密钥：加密后存入 mate_skill.secret，同时写入 mate_skill_secret 供运行时使用
                // 注意：storePlatformSecret 必须先于 encryptAndStoreSecret 调用，
                // 因为后者会原地替换 remote.secret 为密文，导致 mate_skill_secret 二次加密。
                String plaintextSecret = remote.getSecret();
                encryptAndStoreSecret(remote);
                skillMapper.insert(remote);
                storePlatformSecret(remote.getId(), plaintextSecret);
                // 将平台密钥映射到脚本声明的 env var 名称（如 BAIDU_API_KEY），
                // 让脚本通过 os.getenv("BAIDU_API_KEY") 能拿到密钥。
                storePlatformSecretUnderEnvVars(remote.getId(), remote.getSkillContent(), plaintextSecret);
                // 初始化工作区
                if (remote.getSkillContent() != null) {
                    workspaceManager.initWorkspace(remote.getName(), remote.getSkillContent());
                }
                // 物化脚本/引用文件：将 configJson 中内联的 scripts/references
                // 提取到 mate_skill_file 表并写入磁盘 workspace 目录
                try {
                    skillFileSyncer.syncOne(remote);
                } catch (Exception e) {
                    log.warn("Skill file sync failed for new skill '{}': {}", remote.getName(), e.getMessage());
                }
                inserted++;
                log.debug("Synced new skill from platform: {}", remote.getName());
            } else {
                // === 平台恢复检测 ===
                // 如果之前被标记为 REMOVED，现在重新出现在平台列表中 → 当作全新技能处理
                boolean wasRemoved = "REMOVED".equals(local.getPlatformStatus());
                if (wasRemoved) {
                    // 重置为全新技能状态：清除标记、强制覆盖所有平台字段、重置安装状态
                    local.setPlatformStatus(null);
                    local.setInstalled(false);
                    local.setEnabled(false);
                    // 覆盖全部平台字段（不依赖 needsUpdate，强制全量覆盖）
                    local.setDescription(remote.getDescription());
                    local.setSkillContent(remote.getSkillContent());
                    local.setVersion(remote.getVersion());
                    local.setConfigJson(remote.getConfigJson());
                    local.setIcon(remote.getIcon());
                    local.setTags(remote.getTags());
                    local.setAuthor(remote.getAuthor());
                    local.setDescriptionZh(remote.getDescriptionZh());
                    local.setNameZh(remote.getNameZh());
                    local.setNameEn(remote.getNameEn());
                    local.setCategoryId(remote.getCategoryId());
                    local.setCategoryName(remote.getCategoryName());
                    if (remote.getSecret() != null) {
                        local.setSecret(encryptSecret(remote.getSecret()));
                    } else {
                        local.setSecret(null);
                    }
                    skillMapper.updateById(local);
                    // 物化脚本/引用文件
                    try {
                        skillFileSyncer.syncOne(local);
                    } catch (Exception e) {
                        log.warn("Skill file sync failed for restored skill '{}': {}", local.getName(), e.getMessage());
                    }
                    // 重新初始化工作区
                    if (remote.getSkillContent() != null) {
                        workspaceManager.initWorkspace(local.getName(), remote.getSkillContent());
                    }
                    updated++;
                    log.info("Skill {} restored by platform, treated as new install", local.getName());
                }

                // 更新：比对内容是否变化（不覆盖客户端本地的 enabled 状态，尊重用户本地决策）
                // 密钥：先加密远程明文再与本地密文比对
                if (!wasRemoved && needsUpdate(local, remote)) {
                    local.setDescription(remote.getDescription());
                    local.setSkillContent(remote.getSkillContent());
                    local.setVersion(remote.getVersion());
                    local.setConfigJson(remote.getConfigJson());
                    local.setIcon(remote.getIcon());
                    local.setTags(remote.getTags());
                    local.setAuthor(remote.getAuthor());
                    local.setDescriptionZh(remote.getDescriptionZh());
                    local.setCategoryId(remote.getCategoryId());
                    local.setCategoryName(remote.getCategoryName());
                    // 密钥：加密后存入本地
                    if (remote.getSecret() != null) {
                        local.setSecret(encryptSecret(remote.getSecret()));
                    } else {
                        local.setSecret(null);
                    }
                    // 注意：不设置 enabled，保留客户端本地状态
                    skillMapper.updateById(local);
                    // 物化脚本/引用文件
                    try {
                        skillFileSyncer.syncOne(local);
                    } catch (Exception e) {
                        log.warn("Skill file sync failed for updated skill '{}': {}", local.getName(), e.getMessage());
                    }
                    updated++;
                    log.debug("Synced updated skill from platform: {}", remote.getName());
                }
                // 密钥映射必须每次同步都执行（脱离 needsUpdate），
                // 确保即使 skillContent 未变更，env var 映射逻辑也能覆盖历史遗漏。
                // （例如 storePlatformSecretUnderEnvVars 功能上线前同步的技能，
                //  其 BAIDU_API_KEY 等 env var 映射从未被写入过）
                storePlatformSecret(local.getId(), remote.getSecret());
                if (remote.getSecret() != null && !remote.getSecret().isEmpty()) {
                    storePlatformSecretUnderEnvVars(local.getId(),
                            remote.getSkillContent() != null ? remote.getSkillContent() : local.getSkillContent(),
                            remote.getSecret());
                }
            }
        }

        // 移除：本地有但平台端已不再分配 → 标记 REMOVED + 强制禁用
        for (SkillEntity local : localSkills) {
            if (!remoteNames.contains(local.getName())) {
                // 不再物理删除，标记为平台已移除并强制禁用
                // 保留数据行以便用户看到状态（灰色/禁用），平台恢复后可重新安装
                local.setEnabled(false);
                local.setPlatformStatus("REMOVED");
                skillMapper.updateById(local);
                // 清理对应密钥（平台已不再授权，密钥也应失效）
                skillSecretService.remove(local.getId(), PLATFORM_SECRET_KEY);
                // 归档工作区
                workspaceManager.archiveWorkspace(local.getName());
                // 注销运行时包装器（knowledge/acp tools），避免已禁用的技能仍暴露工具
                runtimeService.deregisterSkillWrappers(local.getId());
                disabled++;
                log.info("Marked skill as platform-removed: {}", local.getName());
            }
        }

        // 刷新 runtime 缓存
        runtimeService.refreshActiveSkills();

        lastSyncTime = LocalDateTime.now();
        lastSyncStatus = "SUCCESS";
        log.info("Skill sync completed: {} inserted, {} updated, {} disabled",
                inserted, updated, disabled);

        return new SyncResult(true, "Sync completed", inserted, updated, disabled);
    }

    /**
     * 获取同步状态（供管理页面展示）
     */
    public Map<String, Object> getSyncStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", syncProperties.isEnabled() && platformConfig.isEnabled());
        status.put("lastSyncTime", lastSyncTime);
        status.put("lastSyncStatus", lastSyncStatus);
        status.put("platformReachable", syncProperties.isEnabled() && platformSkillClient.isReachable());
        status.put("syncIntervalSeconds", syncProperties.getSyncIntervalSeconds());
        return status;
    }

    /**
     * 判断平台端技能相对本地是否需要更新。
     * 比对全部可同步字段（含 null 覆盖逻辑）。
     * <p>
     * 密钥（secret）：远程是平台下发的明文，比对前需先加密再与本地密文比较。
     */
    private boolean needsUpdate(SkillEntity local, SkillEntity remote) {
        if (!Objects.equals(local.getVersion(), remote.getVersion())) {
            return true;
        }
        if (!Objects.equals(local.getSkillContent(), remote.getSkillContent())) {
            return true;
        }
        if (!Objects.equals(local.getDescription(), remote.getDescription())) {
            return true;
        }
        if (!Objects.equals(local.getIcon(), remote.getIcon())) {
            return true;
        }
        if (!Objects.equals(local.getConfigJson(), remote.getConfigJson())) {
            return true;
        }
        if (!Objects.equals(local.getTags(), remote.getTags())) {
            return true;
        }
        if (!Objects.equals(local.getAuthor(), remote.getAuthor())) {
            return true;
        }
        if (!Objects.equals(local.getDescriptionZh(), remote.getDescriptionZh())) {
            return true;
        }
        // 目录变更检测：平台端移动技能到其他目录时需要同步
        if (!Objects.equals(local.getCategoryId(), remote.getCategoryId())) {
            return true;
        }
        if (!Objects.equals(local.getCategoryName(), remote.getCategoryName())) {
            return true;
        }
        // 密钥：远程是明文，本地是密文，先加密再比对
        String remoteEncrypted = remote.getSecret() != null ? encryptSecret(remote.getSecret()) : null;
        if (!Objects.equals(local.getSecret(), remoteEncrypted)) {
            return true;
        }
        return false;
    }

    // ==================== 密钥处理 ====================

    /**
     * 将技能实体中的 secret 从明文替换为 AES 加密密文（原地修改）。
     * 用于 insert 路径：加密后存入 {@code mate_skill.secret} 列。
     */
    private void encryptAndStoreSecret(SkillEntity entity) {
        if (entity.getSecret() == null || entity.getSecret().isEmpty()) {
            return;
        }
        entity.setSecret(encryptSecret(entity.getSecret()));
    }

    /**
     * 将平台下发的明文密钥存入 {@code mate_skill_secret} 表（运行时路径）。
     * {@link vip.mate.tool.builtin.SkillScriptTool} 执行脚本时会自动读取。
     */
    private void storePlatformSecret(Long skillId, String plaintextSecret) {
        if (skillId == null) return;
        try {
            skillSecretService.put(skillId, PLATFORM_SECRET_KEY,
                    plaintextSecret != null && !plaintextSecret.isEmpty() ? plaintextSecret : null);
        } catch (Exception e) {
            log.warn("Failed to store platform secret for skill {}: {}", skillId, e.getMessage());
        }
    }

    /**
     * AES 加密明文密钥，返回 hex 密文。
     * 与 {@link SkillSecretService} 使用相同的 key 和算法，确保互操作性。
     */
    private String encryptSecret(String plaintext) {
        byte[] keyBytes = java.util.Arrays.copyOf(
                encryptKey.getBytes(StandardCharsets.UTF_8), 16);
        AES aes = SecureUtil.aes(keyBytes);
        return aes.encryptHex(plaintext);
    }

    /**
     * 将平台密钥按脚本声明的 env var 名称额外存储一份。
     * <p>
     * 平台下发密钥时统一以 {@value #PLATFORM_SECRET_KEY} 存储，
     * 但脚本实际通过特定环境变量名（如 {@code BAIDU_API_KEY}）读取。
     * 本方法解析 SKILL.md frontmatter 中的 env 声明，支持以下路径：
     * <ul>
     *   <li>{@code dependencies.env} — 标准声明格式</li>
     *   <li>{@code metadata.openclaw.requires.env} — OpenClaw 兼容格式</li>
     *   <li>{@code requires[].key}（type=env_var）— v3 清单格式</li>
     * </ul>
     * 为每个声明的 env var 名创建一条 {@code mate_skill_secret} 记录，
     * 值与主密钥相同，实现一次下发、多名称可用。
     */
    private void storePlatformSecretUnderEnvVars(Long skillId, String skillContent, String plaintextSecret) {
        if (skillId == null || plaintextSecret == null || plaintextSecret.isEmpty()) return;
        if (skillContent == null || skillContent.isBlank()) return;

        SkillFrontmatterParser.ParsedSkillMd parsed = frontmatterParser.parse(skillContent);
        List<String> envVars = new ArrayList<>();

        // 路径1: dependencies.env — 标准声明格式
        if (parsed.getDependencies() != null && !parsed.getDependencies().getEnv().isEmpty()) {
            envVars.addAll(parsed.getDependencies().getEnv());
        }

        // 路径2: metadata.openclaw.requires.env — OpenClaw 兼容格式
        //         也尝试顶层 requires（v3 清单）中的 type=env_var
        @SuppressWarnings("unchecked")
        Map<String, Object> fm = parsed.getFrontmatter();
        if (fm != null) {
            // 2a: metadata.openclaw.requires.env
            envVars.addAll(extractEnvFromOpenClawMetadata(fm));
            // 2b: 顶层 requires[] 中 type=env_var 的条目
            envVars.addAll(extractEnvFromV3Requires(fm));
        }

        // 去重
        List<String> distinctEnvVars = envVars.stream()
                .filter(v -> v != null && !v.isBlank() && !PLATFORM_SECRET_KEY.equals(v))
                .distinct()
                .toList();

        for (String envVar : distinctEnvVars) {
            try {
                skillSecretService.put(skillId, envVar, plaintextSecret);
                log.debug("Mapped platform secret to env var '{}' for skill {}", envVar, skillId);
            } catch (Exception e) {
                log.warn("Failed to map secret to env var '{}' for skill {}: {}", envVar, skillId, e.getMessage());
            }
        }

        if (!distinctEnvVars.isEmpty()) {
            log.info("Mapped platform secret to {} env var(s) for skill {}: {}",
                    distinctEnvVars.size(), skillId, distinctEnvVars);
        }
    }

    /**
     * 从 {@code metadata.openclaw.requires.env} 路径提取环境变量名列表。
     * 兼容 OpenClaw 风格的 SKILL.md frontmatter。
     */
    @SuppressWarnings("unchecked")
    private List<String> extractEnvFromOpenClawMetadata(Map<String, Object> frontmatter) {
        Object metadata = frontmatter.get("metadata");
        if (!(metadata instanceof Map<?, ?> metaMap)) return List.of();
        Object openclaw = ((Map<String, Object>) metaMap).get("openclaw");
        if (!(openclaw instanceof Map<?, ?> ocMap)) return List.of();
        Object requires = ((Map<String, Object>) ocMap).get("requires");
        if (!(requires instanceof Map<?, ?> reqMap)) return List.of();
        Object env = ((Map<String, Object>) reqMap).get("env");
        if (env instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        return List.of();
    }

    /**
     * 从顶层 {@code requires[]}（v3 清单格式）提取 type=env_var 的 key。
     */
    @SuppressWarnings("unchecked")
    private List<String> extractEnvFromV3Requires(Map<String, Object> frontmatter) {
        Object requires = frontmatter.get("requires");
        if (!(requires instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> m = (Map<String, Object>) map;
            if ("env_var".equals(m.get("type"))) {
                Object key = m.get("key");
                if (key != null) {
                    String keyStr = key.toString();
                    if (!keyStr.isBlank()) result.add(keyStr);
                }
            }
        }
        return result;
    }

    /**
     * 同步结果
     */
    public record SyncResult(boolean success, String message, int inserted, int updated, int disabled) {}
}
