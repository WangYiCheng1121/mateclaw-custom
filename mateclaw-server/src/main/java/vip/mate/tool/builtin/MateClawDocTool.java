package vip.mate.tool.builtin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MateClaw 项目文档读取工具
 * 允许 Agent 在运行时读取内置项目文档（classpath:docs/ 下的 Markdown 文件）
 */
@Slf4j
@Component
public class MateClawDocTool {

    private static final Pattern VALID_PATH = Pattern.compile("^(zh|en)/[a-z0-9_-]+\\.md$");
    private static final String DOCS_BASE = "docs/";

    @Tool(description = """
        Read GLClaw project documentation.
        Use this tool to look up information about GLClaw's features, configuration, and usage.

        Parameters:
        - action: "list" to list all available doc files, "read" to read a specific doc
        - path: (required when action="read") Relative path like "zh/config.md" or "en/quickstart.md"

        Returns: For "list", a list of available doc files grouped by language.
                 For "read", the full markdown content of the specified doc.
        """)
    public String readMateClawDoc(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Action to perform: 'list' or 'read'")
        String action,

        @JsonProperty
        @JsonPropertyDescription("Doc path relative to docs/, e.g. 'zh/config.md' or 'en/quickstart.md'. Required when action='read'.")
        String path
    ) {
        if ("list".equalsIgnoreCase(action)) {
            return listDocs();
        } else if ("read".equalsIgnoreCase(action)) {
            return readDoc(path);
        } else {
            return "Error: Unknown action '" + action + "'. Use 'list' or 'read'.";
        }
    }

    private String listDocs() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            List<String> zhDocs = new ArrayList<>();
            List<String> enDocs = new ArrayList<>();

            // Scan zh/ docs
            try {
                Resource[] zhResources = resolver.getResources("classpath:docs/zh/*.md");
                for (Resource r : zhResources) {
                    String filename = r.getFilename();
                    if (filename != null) {
                        zhDocs.add(filename);
                    }
                }
            } catch (IOException e) {
                log.debug("No zh docs found: {}", e.getMessage());
            }

            // Scan en/ docs
            try {
                Resource[] enResources = resolver.getResources("classpath:docs/en/*.md");
                for (Resource r : enResources) {
                    String filename = r.getFilename();
                    if (filename != null) {
                        enDocs.add(filename);
                    }
                }
            } catch (IOException e) {
                log.debug("No en docs found: {}", e.getMessage());
            }

            StringBuilder sb = new StringBuilder();
            sb.append("GLClaw Documentation\n\n");

            sb.append("## 中文文档 (zh/)\n");
            if (zhDocs.isEmpty()) {
                sb.append("  (none)\n");
            } else {
                zhDocs.sort(String::compareTo);
                for (String doc : zhDocs) {
                    sb.append("  - zh/").append(doc).append("\n");
                }
            }

            sb.append("\n## English Docs (en/)\n");
            if (enDocs.isEmpty()) {
                sb.append("  (none)\n");
            } else {
                enDocs.sort(String::compareTo);
                for (String doc : enDocs) {
                    sb.append("  - en/").append(doc).append("\n");
                }
            }

            sb.append("\nUse readMateClawDoc(action=\"read\", path=\"zh/config.md\") to read a specific doc.");
            return sb.toString();

        } catch (Exception e) {
            log.error("Failed to list docs: {}", e.getMessage());
            return "Error: Failed to list documentation files: " + e.getMessage();
        }
    }

    private String readDoc(String path) {
        if (path == null || path.isBlank()) {
            return "Error: 'path' is required when action='read'. Example: 'zh/config.md'";
        }

        // Security: validate path format
        if (!VALID_PATH.matcher(path).matches()) {
            return "Error: Invalid path format. Expected pattern: (zh|en)/<topic>.md, e.g. 'zh/config.md'";
        }

        try {
            ClassPathResource resource = new ClassPathResource(DOCS_BASE + path);
            if (!resource.exists()) {
                return "Error: Document not found: " + path;
            }

            try (InputStream is = resource.getInputStream()) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                log.info("Read doc {}: {} bytes", path, content.length());
                return content;
            }
        } catch (IOException e) {
            log.error("Failed to read doc {}: {}", path, e.getMessage());
            return "Error: Failed to read document: " + e.getMessage();
        }
    }
}
