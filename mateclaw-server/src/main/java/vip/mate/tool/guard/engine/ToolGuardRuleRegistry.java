package vip.mate.tool.guard.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.mate.tool.guard.model.ToolGuardRuleEntity;
import vip.mate.tool.guard.platform.PlatformSecurityClient;
import vip.mate.tool.guard.platform.PlatformSecurityProperties;
import vip.mate.tool.guard.repository.ToolGuardRuleMapper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 规则注册表
 * <p>
 * 统一管理内置规则 + DB 自定义规则。
 * 启动时加载，支持 reload() 热重载。
 */
@Slf4j
@Component
@Order(115) // 在 ToolGuardRuleSeedService(110) 之后，确保种子规则已写入
@RequiredArgsConstructor
public class ToolGuardRuleRegistry implements ApplicationRunner {

    private final ToolGuardRuleMapper ruleMapper;
    private final PlatformSecurityClient platformClient;
    private final PlatformSecurityProperties securityProperties;

    private volatile List<ToolGuardRuleEntity> allRules = List.of();
    private final Map<String, Pattern> compiledPatterns = new ConcurrentHashMap<>();

    @Override
    public void run(ApplicationArguments args) {
        reload();
    }

    /**
     * 定时从平台端刷新规则（60 秒间隔）。
     * <p>
     * 与 ToolGuardConfigService 的定时刷新保持一致的节奏，
     * 确保平台端对规则的增删改能在 60 秒内同步到客户端内存，无需重启。
     */
    @Scheduled(fixedDelayString = "${mateclaw.security.platform.rules-refresh-interval-sec:60}000")
    public void scheduledRefresh() {
        if (securityProperties.isEnabled()) {
            reload();
        }
    }

    /**
     * 重新加载所有规则：优先从平台端拉取 → 覆盖写入 H2 → 从 H2 加载到内存
     */
    public void reload() {
        boolean loadedFromPlatform = false;

        // 优先从平台端拉取
        if (securityProperties.isEnabled()) {
            try {
                List<ToolGuardRuleEntity> platformRules = platformClient.fetchRules();
                if (platformRules != null && !platformRules.isEmpty()) {
                    // 覆盖写入本地 H2（先清再插，保持与平台一致）
                    overwriteLocalRules(platformRules);
                    loadedFromPlatform = true;
                    log.info("[ToolGuardRuleRegistry] Overwrote {} rules from platform → H2", platformRules.size());
                }
            } catch (Exception e) {
                log.warn("[ToolGuardRuleRegistry] Failed to fetch rules from platform: {}", e.getMessage());
            }
        }

        // 从本地 H2 加载到内存
        try {
            List<ToolGuardRuleEntity> rules = ruleMapper.selectList(
                    new LambdaQueryWrapper<ToolGuardRuleEntity>()
                            .eq(ToolGuardRuleEntity::getEnabled, true)
                            .orderByDesc(ToolGuardRuleEntity::getPriority));
            this.allRules = List.copyOf(rules);
            log.info("[ToolGuardRuleRegistry] Loaded {} enabled rules from {} into memory",
                    rules.size(), loadedFromPlatform ? "H2 (synced from platform)" : "local H2");
        } catch (Exception e) {
            log.warn("[ToolGuardRuleRegistry] Failed to load rules from H2: {}", e.getMessage());
            this.allRules = List.of();
        }
    }

    /** 全量覆盖：清空本地规则表，插入平台规则 */
    private void overwriteLocalRules(List<ToolGuardRuleEntity> platformRules) {
        try {
            // 先清空全部已有规则
            ruleMapper.delete(new LambdaQueryWrapper<>());
            // 批量插入平台规则
            for (ToolGuardRuleEntity rule : platformRules) {
                rule.setId(null); // 让 MyBatis-Plus 自动生成新 ID
                ruleMapper.insert(rule);
            }
        } catch (Exception e) {
            log.warn("[ToolGuardRuleRegistry] Failed to overwrite local rules: {}", e.getMessage());
        }
    }

    /**
     * 获取适用于指定工具的规则
     */
    public List<ToolGuardRuleEntity> getRulesForTool(String toolName) {
        return allRules.stream()
                .filter(r -> r.getToolName() == null || r.getToolName().isEmpty()
                        || r.getToolName().equals(toolName))
                .collect(Collectors.toList());
    }

    /**
     * 按 category 取所有已启用规则（不限工具）。
     * 用于 alwaysRun 类的横切 Guardian（凭据扫描、PII 扫描等）。
     */
    public List<ToolGuardRuleEntity> getRulesByCategory(String category) {
        if (category == null || category.isEmpty()) {
            return List.of();
        }
        return allRules.stream()
                .filter(r -> category.equals(r.getCategory()))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有已启用规则
     */
    public List<ToolGuardRuleEntity> getAllEnabled() {
        return allRules;
    }

    /**
     * 获取编译后的正则模式
     */
    public Pattern getCompiledPattern(String regex) {
        return compiledPatterns.computeIfAbsent(regex,
                r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE));
    }

    /**
     * 获取编译后的排除模式
     */
    public Pattern getCompiledExcludePattern(String regex) {
        return compiledPatterns.computeIfAbsent("exclude:" + regex,
                r -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }
}
