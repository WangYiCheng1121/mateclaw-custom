package vip.mate.system.controller;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import vip.mate.common.result.R;
import vip.mate.config.DatabaseBootstrapRunner;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelDiscoveryService;
import vip.mate.llm.service.ModelProviderService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Setup API for first-run initialization.
 * <p>
 * Called by the Desktop splash screen to initialize the database
 * with the user's chosen language before navigating to the main UI.
 * These endpoints require no authentication.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/setup")
@RequiredArgsConstructor
public class SetupController {

    private final DatabaseBootstrapRunner bootstrapRunner;
    private final ModelConfigService modelConfigService;
    private final ModelDiscoveryService modelDiscoveryService;
    private final ModelProviderService modelProviderService;
    private final JdbcTemplate jdbcTemplate;


    /**
     * Check whether the application has been initialized.
     *
     * @return { "initialized": true/false }
     */
    @GetMapping("/status")
    public R<SetupStatus> getStatus() {
        return R.ok(new SetupStatus(bootstrapRunner.isInitialized()));
    }

    /**
     * Initialize the application with the chosen language.
     * This seeds the database with locale-specific data (agents, tools, descriptions).
     *
     * @param request { "language": "zh-CN" | "en-US" }
     * @return success or conflict
     */
    @PostMapping("/init")
    public R<String> init(@RequestBody InitRequest request) {
        String language = request.getLanguage();
        if (language == null || language.isBlank()) {
            language = "zh-CN";
        }
        if (!"zh-CN".equals(language) && !"en-US".equals(language)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported language: " + language);
        }

        boolean success = bootstrapRunner.initWithLocale(language);
        if (!success) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Application already initialized");
        }

        log.info("Application initialized with language={}", language);
        return R.ok("Initialized with " + language);
    }

    /**
     * Onboarding status: whether the system has a usable model configured.
     * Used by the frontend to decide whether to show the onboarding wizard.
     */
    @GetMapping("/onboarding-status")
    public R<Map<String, Object>> getOnboardingStatus() {
        boolean hasDefaultModel = false;
        try {
            modelConfigService.getDefaultModel();
            hasDefaultModel = true;
        } catch (Exception e) {
            // no default model configured
        }

        boolean ollamaOnline = false;
        try {
            ollamaOnline = modelDiscoveryService.testConnection("ollama").isSuccess();
        } catch (Exception e) {
            // Ollama not available
        }

        List<String> configuredProviders = modelProviderService.listProviders().stream()
                .filter(p -> Boolean.TRUE.equals(p.getConfigured()))
                .map(p -> p.getId())
                .toList();

        return R.ok(Map.of(
                "hasDefaultModel", hasDefaultModel,
                "ollamaOnline", ollamaOnline,
                "configuredProviders", configuredProviders
        ));
    }

    /**
     * Database diagnostic endpoint (no auth required).
     * Use this to check if the database is properly initialized on a remote installation.
     * Access via browser: http://localhost:{port}/api/v1/setup/diagnose
     */
    @GetMapping("/diagnose")
    public R<Map<String, Object>> diagnose() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("initialized", bootstrapRunner.isInitialized());

        try {
            Integer userCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM mate_user", Integer.class);
            result.put("userCount", userCount);
        } catch (Exception e) {
            result.put("userCount", "ERROR: " + e.getMessage());
        }

        try {
            Integer wsCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM mate_workspace", Integer.class);
            result.put("workspaceCount", wsCount);
            // 检查 ID=1 的默认工作区
            List<Map<String, Object>> defaultWs = jdbcTemplate.queryForList(
                    "SELECT id, name, slug, owner_id FROM mate_workspace WHERE id = 1");
            result.put("defaultWorkspace", defaultWs.isEmpty() ? "MISSING" : defaultWs.get(0));
        } catch (Exception e) {
            result.put("workspaceCount", "ERROR: " + e.getMessage());
        }

        try {
            Integer memberCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM mate_workspace_member WHERE workspace_id = 1", Integer.class);
            result.put("defaultWorkspaceMemberCount", memberCount);
        } catch (Exception e) {
            result.put("defaultWorkspaceMemberCount", "ERROR: " + e.getMessage());
        }

        try {
            Integer agentCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM mate_agent", Integer.class);
            result.put("agentCount", agentCount);
        } catch (Exception e) {
            result.put("agentCount", "ERROR: " + e.getMessage());
        }

        try {
            // 检查 Flyway 版本
            List<Map<String, Object>> flyway = jdbcTemplate.queryForList(
                    "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank");
            result.put("flywayMigrations", flyway);
        } catch (Exception e) {
            result.put("flywayMigrations", "ERROR: " + e.getMessage());
        }

        return R.ok(result);
    }


    @Data
    public static class InitRequest {
        private String language;
    }

    @Data
    public static class SetupStatus {
        private final boolean initialized;
    }
}
