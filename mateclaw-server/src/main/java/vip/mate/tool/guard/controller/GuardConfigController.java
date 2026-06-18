package vip.mate.tool.guard.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.tool.guard.engine.ToolGuardRuleRegistry;
import vip.mate.tool.guard.service.ToolGuardConfigService;

import java.util.Map;

/**
 * 安全配置管理接口（客户端侧）
 * <p>
 * 配置以平台端为准，客户端通过定时任务自动拉取。
 * 此接口提供手动即时刷新能力。
 *
 * @author MateClaw Team
 */
@Slf4j
@Tag(name = "安全配置管理")
@RestController
@RequestMapping("/api/v1/security/guard/config")
@RequiredArgsConstructor
public class GuardConfigController {

    private final ToolGuardConfigService configService;
    private final ToolGuardRuleRegistry ruleRegistry;

    @Operation(summary = "手动从平台端刷新全部安全配置（敏感路径 + 安全规则）")
    @PostMapping("/refresh")
    public R<Map<String, Object>> refresh() {
        long before = System.currentTimeMillis();
        try {
            configService.refreshPlatformCache();
            ruleRegistry.reload();
            long cost = System.currentTimeMillis() - before;
            return R.ok(Map.of(
                    "success", true,
                    "message", "敏感路径 + 安全规则已从平台端刷新",
                    "costMs", cost
            ));
        } catch (Exception e) {
            log.error("[GuardConfigController] Manual refresh failed: {}", e.getMessage());
            return R.fail("刷新失败: " + e.getMessage());
        }
    }
}
