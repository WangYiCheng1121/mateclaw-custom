 package vip.mate.tool.builtin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.TokenEstimator;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.runtime.SkillCatalogSort;
import vip.mate.skill.runtime.SkillCatalogSorter;
import vip.mate.skill.runtime.SkillFileAccessPolicy;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.usage.SkillUsageService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 技能文件读取工具
 * 允许 Agent 在运行时读取 skill 内部文件
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillFileTool {

    private static final int DEFAULT_MAX_LINES = 200;
    private static final int MAX_OUTPUT_CHARS = 8_000;

    private final SkillRuntimeService runtimeService;
    private final SkillFileAccessPolicy accessPolicy;
    private final SkillUsageService usageService;
    private final AgentBindingService agentBindingService;

    @Tool(description = """
        Read a file from a skill's directory (SKILL.md, references/, scripts/, or templates/).
        Use this when you need to access skill documentation or reference files.

        Parameters:
        - skillName: Name of the skill (e.g., "channel_message")
        - filePath: Relative path within skill directory, must start with "references/", "scripts/",
                    or "templates/" (e.g., "references/config.md", "scripts/helper.py",
                    "templates/template.html")
                    To read SKILL.md itself, use "SKILL.md" as filePath

        Returns: File content as string, or error message if file not found or access denied.

        Security: Only files under references/, scripts/, and templates/ can be accessed. Path traversal is blocked.
        """)
    public String readSkillFile(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Skill name")
        String skillName,

        @JsonProperty(required = true)
        @JsonPropertyDescription("Relative file path (e.g., 'references/doc.md', 'scripts/run.py', or 'templates/template.html')")
        String filePath,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Start line number (1-based). Omit to start from line 1")
        Integer startLine,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Maximum number of lines to read, default 300")
        Integer maxLines,

        @Nullable ToolContext ctx
    ) {
        log.info("Reading skill file: skill={}, path={}", skillName, filePath);

        // 查找 active skill
        ResolvedSkill skill = runtimeService.findActiveSkill(skillName);
        if (skill == null) {
            return "Error: Skill '" + skillName + "' not found or not enabled";
        }
        String bindingError = checkAgentBinding(skill, ctx);
        if (bindingError != null) {
            return bindingError;
        }

        // 特殊处理：读取 SKILL.md
        if ("SKILL.md".equals(filePath)) {
            if (skill.getContent() != null && !skill.getContent().isBlank()) {
                log.info("Skill loaded: skill={}, path=SKILL.md, bytes={}, estimatedTokens={}",
                        skillName, skill.getContent().length(), TokenEstimator.estimateTokens(skill.getContent()));
                recordLoaded(skill, "SKILL.md", skill.getContent(), ctx);
                // SKILL.md is the model's primary contract for using a skill —
                // pagination by default would let the model see only the first
                // 200 lines / 8KB and silently miss later mandatory sections.
                // Return the full content unless the caller explicitly requested
                // pagination via startLine or maxLines. References / scripts are
                // still paginated below because they can be large supplementary
                // material the model loads on demand.
                boolean paginationRequested = startLine != null || maxLines != null;
                if (!paginationRequested) {
                    return wrapWithRuntimeNotice(skill, skill.getContent());
                }
                return paginateSkillContent(skillName, "SKILL.md", skill.getContent(), startLine, maxLines);
            }
            return "Error: SKILL.md content not available";
        }

        // 目录型 skill
        if (skill.getSkillDir() == null) {
            return "Error: Skill '" + skillName + "' is database-based, no file system access available";
        }

        // 验证路径安全性
        Path resolvedPath = accessPolicy.validateAndResolve(skill.getSkillDir(), filePath);
        if (resolvedPath == null) {
            return "Error: Invalid or unsafe file path: " + filePath;
        }

        // 读取文件
        try {
            if (!Files.exists(resolvedPath)) {
                return "Error: File not found: " + filePath;
            }

            if (!Files.isRegularFile(resolvedPath)) {
                return "Error: Path is not a file: " + filePath;
            }

            String content = Files.readString(resolvedPath);
            log.info("Skill loaded: skill={}, path={}, bytes={}, estimatedTokens={}",
                    skillName, filePath, content.length(), TokenEstimator.estimateTokens(content));
            recordLoaded(skill, filePath, content, ctx);
            return paginateSkillContent(skillName, filePath, content, startLine, maxLines);

        } catch (Exception e) {
            log.error("Failed to read skill file {}/{}: {}", skillName, filePath, e.getMessage());
            return "Error: Failed to read file: " + e.getMessage();
        }
    }

    private String paginateSkillContent(String skillName, String filePath, String content,
                                        Integer startLine, Integer maxLines) {
        int safeStart = startLine == null || startLine <= 0 ? 1 : startLine;
        int safeMaxLines = maxLines == null || maxLines <= 0
                ? DEFAULT_MAX_LINES
                : Math.min(maxLines, DEFAULT_MAX_LINES);
        String[] lines = content.split("\\R", -1);
        if (safeStart > lines.length) {
            return "Error: startLine " + safeStart + " exceeds total lines " + lines.length;
        }

        StringBuilder out = new StringBuilder();
        int emitted = 0;
        int lineIndex = safeStart - 1;
        boolean truncated = false;
        boolean longLineSplit = false;
        while (lineIndex < lines.length && emitted < safeMaxLines) {
            String rendered = lines[lineIndex] + "\n";
            if (out.length() + rendered.length() > MAX_OUTPUT_CHARS) {
                // P2 fix: a single line longer than MAX_OUTPUT_CHARS would
                // otherwise loop forever — the model gets a banner saying
                // "next startLine=N" but N still points at the same long line,
                // so the next call yields the same banner with zero content.
                // Big JSON / minified scripts / base64 fixtures all hit this.
                // When we have already emitted some shorter lines this round,
                // stop and let the caller re-request from this line. When the
                // FIRST attempted line is the over-long one, head-truncate it
                // verbatim into the remaining budget so the model sees real
                // content and can advance lineIndex on the next call.
                if (emitted == 0) {
                    int budget = Math.max(0, MAX_OUTPUT_CHARS - out.length());
                    if (budget > 0) {
                        out.append(rendered, 0, Math.min(budget, rendered.length()));
                    }
                    emitted = 1;
                    lineIndex++;
                    longLineSplit = true;
                }
                truncated = true;
                break;
            }
            out.append(rendered);
            emitted++;
            lineIndex++;
        }
        if (lineIndex < lines.length) {
            truncated = true;
        }
        if (truncated) {
            int nextLine = safeStart + emitted;
            out.append("\n[Skill file truncated: skill=").append(skillName)
                    .append(", path=").append(filePath)
                    .append(", shownLines=").append(safeStart).append("-").append(nextLine - 1)
                    .append(", totalLines=").append(lines.length);
            if (longLineSplit) {
                // Tell the model the truncation crossed a single long line so
                // it knows the displayed text for that line is not the whole
                // line — it should switch tools (e.g. an external read with a
                // byte range) rather than just paginating again.
                out.append(", note=\"line ").append(safeStart)
                        .append(" exceeds per-call budget; shown content is head-truncated\"");
            }
            out.append(". Continue with readSkillFile(skillName=\"").append(skillName)
                    .append("\", filePath=\"").append(filePath)
                    .append("\", startLine=").append(nextLine)
                    .append(", maxLines=").append(safeMaxLines).append(").]");
        }
        return out.toString();
    }

    private void recordLoaded(ResolvedSkill skill, String filePath, String content, @Nullable ToolContext ctx) {
        ChatOrigin origin = ChatOrigin.from(ctx);
        usageService.recordLoaded(
                skill,
                origin.agentId(),
                origin.conversationId(),
                filePath,
                TokenEstimator.estimateTokens(content));
    }

    /**
     * 当技能包含 scripts/ 目录时，在 SKILL.md 内容前自动注入运行时调用提示。
     * <p>
     * 引导 Agent 使用 {@code runSkillScript} 而非直接执行 shell 命令
     * （如 {@code python3 scripts/...}）。只有通过 {@code runSkillScript}
     * 调用，系统才会自动从数据库解密密钥并注入到脚本子进程的环境变量中。
     * <p>
     * 提示也注明技能声明的环境变量（来自 frontmatter）会被自动注入，
     * 避免 Agent 因找不到环境变量而反复尝试配置。
     * <p>
     * 如果 SKILL.md body 中包含 shell 调用示例（如
     * {@code python3 scripts/xxx.py '{"query":"hello"}'}），
     * 自动提取 JSON 参数并转换为 {@code runSkillScript} 调用示例。
     */
    private String wrapWithRuntimeNotice(ResolvedSkill skill, String content) {
        boolean hasScripts = skill.getScripts() != null && !skill.getScripts().isEmpty();
        if (!hasScripts) {
            return content;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("> **Runtime Notice** — This skill has executable scripts.\n");
        sb.append("> Use `runSkillScript(skillName=\"").append(skill.getName())
                .append("\", scriptPath=\"scripts/...\", args=...)`\n");
        sb.append("> to execute them. Do **not** run shell commands like `python3 scripts/...` or `bash scripts/...`\n");
        sb.append("> directly — only `runSkillScript` injects the skill's configured API keys as environment\n");
        sb.append("> variables into the script subprocess.\n");

        // Extract concrete call examples from the SKILL.md body.
        // Matches lines like: python3 scripts/search.py '{"query":"hello"}'
        // and converts them to runSkillScript calls with correct args format.
        List<String> examples = extractRunSkillExamples(skill.getName(), content);
        if (!examples.isEmpty()) {
            sb.append("> \n");
            sb.append("> **Examples from SKILL.md** (translated to runSkillScript):\n");
            for (String ex : examples) {
                sb.append(">   ").append(ex).append("\n");
            }
        }

        // If manifest declares env vars, mention they are auto-injected
        if (skill.getManifest() != null && skill.getManifest().getRequires() != null) {
            List<String> envKeys = skill.getManifest().getRequires().stream()
                    .filter(r -> "env_var".equals(r.getType()))
                    .map(SkillManifest.RequirementDef::getKey)
                    .filter(k -> k != null && !k.isBlank())
                    .distinct()
                    .toList();
            if (!envKeys.isEmpty()) {
                sb.append("> Environment variables auto-injected: `")
                        .append(String.join("`, `", envKeys)).append("`\n");
            }
        }

        sb.append(">\n");
        sb.append(content);
        return sb.toString();
    }

    /**
     * 从 SKILL.md body 中提取 shell 调用示例，转换为 runSkillScript 调用格式。
     * <p>
     * 匹配模式：{@code python3 scripts/<name>.py '<json>'} 或
     * {@code python scripts/<name>.py '<json>'}，提取 JSON 参数字符串。
     */
    private static final java.util.regex.Pattern SHELL_SCRIPT_EXAMPLE =
            java.util.regex.Pattern.compile(
                    "(?:python3?|python)\\s+scripts/([\\w.-]+)\\s+'([^']+)'",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private List<String> extractRunSkillExamples(String skillName, String skillMdContent) {
        if (skillMdContent == null || skillMdContent.isBlank()) return List.of();
        java.util.regex.Matcher m = SHELL_SCRIPT_EXAMPLE.matcher(skillMdContent);
        List<String> examples = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        while (m.find()) {
            String scriptFile = m.group(1);
            String jsonArgs = m.group(2);
            // Normalize the JSON: SKILL.md often has multi-line JSON with
            // trailing commas; trim and compact for the example line.
            String compacted = compactJsonString(jsonArgs);
            if (compacted == null) continue;
            StringBuilder ex = new StringBuilder()
                .append("runSkillScript(skillName=")
                .append('"').append(skillName).append('"')
                .append(", scriptPath=")
                .append('"').append("scripts/").append(scriptFile).append('"')
                .append(", args=")
                .append('"').append(escapeForNotice(compacted)).append('"')
                .append(")");
            String example = ex.toString();
            if (seen.add(example)) {
                examples.add(example);
            }
        }
        return examples;
    }

    /**
     * 尝试将可能的多行/带尾逗号的 JSON 压缩为紧凑形式。
     * 返回 {@code null} 表示无法压缩（非 JSON 或格式错误）。
     */
    private String compactJsonString(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode node = new ObjectMapper()
                    .readTree(raw);
            return node.toString();
        } catch (Exception e) {
            // Not valid JSON (e.g., a plain string parameter), return as-is.
            return raw.trim();
        }
    }

    /** Escape double quotes for embedding in a Markdown code block / notice text. */
    private static String escapeForNotice(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Tool(description = """
        List all files in a skill's references/ and scripts/ directories.
        Use this to explore what files are available in a skill before reading them.

        Parameters:
        - skillName: Name of the skill (e.g., "channel_message")

        Returns: A tree listing of files under references/ and scripts/.
        """)
    public String listSkillFiles(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Skill name")
        String skillName
    ) {
        log.info("Listing skill files: skill={}", skillName);

        ResolvedSkill skill = runtimeService.findActiveSkill(skillName);
        if (skill == null) {
            return "Error: Skill '" + skillName + "' not found or not enabled";
        }
        // listSkillFiles has no ToolContext — check via ThreadLocal fallback
        String bindingError = checkAgentBinding(skill, null);
        if (bindingError != null) {
            return bindingError;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Skill: ").append(skillName).append("\n\n");

        if (skill.getSkillDir() != null) {
            sb.append("Source: directory (").append(skill.getSkillDir()).append(")\n\n");
        } else {
            sb.append("Source: database (no file system directory)\n\n");
        }

        // References
        sb.append("references/\n");
        if (skill.getReferences() != null && !skill.getReferences().isEmpty()) {
            formatTree(sb, skill.getReferences(), "  ");
        } else {
            sb.append("  (empty)\n");
        }

        // Scripts
        sb.append("\nscripts/\n");
        if (skill.getScripts() != null && !skill.getScripts().isEmpty()) {
            formatTree(sb, skill.getScripts(), "  ");
        } else {
            sb.append("  (empty)\n");
        }

        return sb.toString();
    }

    @Tool(description = """
        List currently available Skills (documentation packages).

        IMPORTANT: Skills are NOT directly callable as tools. Each name
        returned here is a `skillName` argument, not a tool name. To use
        a skill, call `readSkillFile(skillName="<name>", filePath="SKILL.md")`
        first to read its instructions, then follow what SKILL.md tells you.
        Calling a skill name as a tool will fail with "Tool not found".

        Search strategy when looking for a specific skill:
        - The default page is 20 of N — if "Showing: 20 of <larger>" appears
          and you don't see what you're after, retry with `keyword=<name fragment>`
          (matched against name + description, case-insensitive) or raise `limit`
          up to 50.
        - If the user mentions an exact skill name (e.g. "tencent-meeting-mcp"),
          skip this tool and go straight to
          `readSkillFile(skillName="<exact-name>", filePath="SKILL.md")` —
          that bypasses the catalog truncation entirely and either returns
          the skill's instructions or a clear "skill not found" error.

        Note: this returns Skills (vendor-installable docs), not Agents.
        For Agents, use `listAvailableAgents`.

        Returns: A formatted list of active skills with name, icon, and description.
        """)
    public String listAvailableSkills(
        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional keyword matched against skill name or description (case-insensitive). Use this when a specific skill name was mentioned but didn't appear in the default page.")
        String keyword,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional source filter: all, builtin, dynamic, mcp, acp")
        String source,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional status filter: all, ready, setup_needed, disabled, blocked")
        String status,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Maximum number of skills to return, default 20, max 50")
        Integer limit,

        @Nullable ToolContext ctx
    ) {
        log.info("Listing available skills");

        int safeLimit = limit == null || limit <= 0 ? 20 : Math.min(limit, 50);
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        Set<Long> boundIds = resolveBoundSkillIds(ctx);
        java.time.LocalDateTime recencyCutoff = java.time.LocalDateTime.now()
                .minus(SkillRuntimeService.NEW_SKILL_BOOST_WINDOW);
        List<ResolvedSkill> activeSkills = SkillCatalogSorter.sortResolved(
                runtimeService.getActiveSkills().stream()
                        .filter(s -> SkillCatalogSorter.sourceMatches(s, source))
                        .filter(s -> SkillCatalogSorter.runtimeMatches(s, status))
                        .filter(s -> boundIds == null || (s.getId() != null && boundIds.contains(s.getId())))
                        .filter(s -> kw.isEmpty()
                                || containsIgnoreCase(s.getName(), kw)
                                || containsIgnoreCase(s.getDescription(), kw))
                        .toList(),
                SkillCatalogSort.RECOMMENDED).stream()
                .sorted(java.util.Comparator.comparingInt((ResolvedSkill s) ->
                        SkillRuntimeService.isRecentlyInstalled(s, recencyCutoff) ? 0 : 1))
                .toList();

        if (activeSkills.isEmpty()) {
            return "No skills are currently available.";
        }

        // Issue #46: render as a table with the call pattern stated up front,
        // instead of a `- **Name** — desc` list that primes the LLM to call
        // the names directly as tools.
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️  These are Skills (documentation packages), NOT directly callable tools.\n");
        sb.append("To use any of them, call:\n");
        sb.append("  readSkillFile(skillName=\"<name from below>\", filePath=\"SKILL.md\")\n");
        sb.append("then follow what SKILL.md tells you (typically `runSkillScript`).\n\n");
        sb.append("| Skill name | Status | Description |\n");
        sb.append("|------------|--------|-------------|\n");
        for (ResolvedSkill skill : activeSkills.stream().limit(safeLimit).toList()) {
            sb.append("| `").append(skill.getName()).append("`");
            if (skill.getIcon() != null && !skill.getIcon().isBlank()) {
                sb.append(" ").append(skill.getIcon());
            }
            sb.append(" | ").append(statusToken(skill)).append(" | ");
            if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
                String desc = skill.getDescription();
                if (desc.length() > 120) {
                    desc = desc.substring(0, 120) + "...";
                }
                sb.append(desc.replace("|", "\\|").replace("\n", " "));
            }
            sb.append(" |\n");
        }
        int shown = Math.min(safeLimit, activeSkills.size());
        sb.append("\nShowing: ").append(shown)
                .append(" of ").append(activeSkills.size()).append(" skill(s).");
        if (shown < activeSkills.size()) {
            // Surface the truncation hint so the LLM knows how to widen the
            // search instead of concluding the missing skill doesn't exist.
            sb.append(" Result truncated — retry with `keyword=<part of name>` ")
                    .append("to search the full catalog, or `limit=50` to see more rows. ")
                    .append("If the user gave an exact skill name, prefer ")
                    .append("`readSkillFile(skillName=\"<name>\", filePath=\"SKILL.md\")` directly.");
        }
        return sb.toString();
    }

    private static boolean containsIgnoreCase(String value, String lowerCaseNeedle) {
        return value != null && value.toLowerCase().contains(lowerCaseNeedle);
    }

    private static String statusToken(ResolvedSkill skill) {
        if (skill.isSecurityBlocked()) return "blocked";
        if (!skill.isEnabled()) return "disabled";
        if (!SkillRuntimeService.passesActiveGate(skill)) return "setup-needed";
        return "ready";
    }

    private String checkAgentBinding(ResolvedSkill skill, @Nullable ToolContext ctx) {
        if (skill == null) return null;
        Long agentId = null;
        if (ctx != null) {
            ChatOrigin origin = ChatOrigin.from(ctx);
            agentId = origin.agentId();
        }
        if (agentId == null) return null;
        Set<Long> boundIds = agentBindingService.getBoundSkillIds(agentId);
        if (boundIds == null) return null;
        if (skill.getId() != null && boundIds.contains(skill.getId())) return null;
        log.info("Skill '{}' (id={}) is not bound to agent {}; access denied",
                skill.getName(), skill.getId(), agentId);
        return "Error: Skill '" + skill.getName()
                + "' is not assigned to this agent. "
                + "The agent's bound skill set does not include it.";
    }

    private Set<Long> resolveBoundSkillIds(@Nullable ToolContext ctx) {
        if (ctx == null) return null;
        ChatOrigin origin = ChatOrigin.from(ctx);
        Long agentId = origin.agentId();
        if (agentId == null) return null;
        return agentBindingService.getBoundSkillIds(agentId);
    }

    @SuppressWarnings("unchecked")
    private void formatTree(StringBuilder sb, Map<String, Object> tree, String indent) {
        for (Map.Entry<String, Object> entry : tree.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                sb.append(indent).append(name).append("/\n");
                formatTree(sb, (Map<String, Object>) value, indent + "  ");
            } else {
                sb.append(indent).append(name).append("\n");
            }
        }
    }
}
