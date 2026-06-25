package vip.mate.tool.builtin;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.tool.browser.UrlSafetyChecker;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * 内置工具：网页抓取
 * <p>
 * 通过直接 HTTP 请求抓取 URL 内容，比 browser_use 轻量得多。
 * 适合读取公开网页的文本内容、文档、API 响应等场景。
 * 不适用于需要 JavaScript 渲染的 SPA 页面（此时应使用 browser_use）。
 *
 * <p>安全说明：
 * <ul>
 *   <li>URL 经过 {@link UrlSafetyChecker} SSRF 防护检查（初始 URL + 所有重定向目标）</li>
 *   <li>仅允许 http:// 和 https:// 协议</li>
 *   <li>禁止访问内网地址、云元数据端点、本地回环地址等</li>
 *   <li>手动处理重定向链（最多 5 次），每次跳转都进行安全校验</li>
 *   <li>响应体最大 100KB，超时 15 秒（可配置 1-30 秒）</li>
 *   <li>支持三种提取模式：text（纯文本）、markdown、html（原始源码）</li>
 * </ul>
 *
 * @author GLClaw Team
 */
@Slf4j
@Component
public class WebFetchTool {

    private static final int DEFAULT_TIMEOUT_MS = 15_000;
    private static final int MAX_TIMEOUT_MS = 30_000;
    private static final int MAX_CONTENT_BYTES = 100_000;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /**
     * HTML 标签黑名单 — 提取纯文本时移除这些元素。
     * script/style/noscript/iframe 是噪声源，nav/footer/header/aside 是导航/装饰内容。
     */
    private static final Set<String> REMOVE_TAGS = Set.of(
            "script", "style", "noscript", "iframe", "nav", "footer", "header", "aside",
            "form", "button", "select", "textarea", "svg", "canvas"
    );

    @Tool(description = """
            Fetch the text content of a web page via direct HTTP request. \
            Use this for reading public web pages, documentation, API responses, \
            or any publicly-accessible URL that doesn't need JavaScript rendering.

            PREFER this over browser_use when you only need page text — it's \
            much faster and cheaper (no browser launch). Use browser_use only \
            when you need to click, type, or interact with a page.

            Returns structured JSON with: url, statusCode, contentType, \
            contentLength, content (the extracted text/markdown/html).

            extractMode options:
            - text (default): clean plain text with basic structure preserved
            - markdown: best-effort markdown conversion (headings, links, images)
            - html: raw HTML source
            
            Security: All URLs and redirect targets are validated against SSRF attacks.
            """)
    public String web_fetch(
            @ToolParam(description = "Full URL to fetch (must start with http:// or https://)") String url,
            @ToolParam(description = "Extraction mode: text (clean plain text), markdown (best-effort conversion), html (raw source). Default is text.", required = false) String extractMode,
            @ToolParam(description = "Request timeout in seconds, 1-30, default 15", required = false) Integer timeoutSeconds) {

        JSONObject result = new JSONObject();
        result.set("url", url);

        // ── 1. 参数校验 ──
        if (url == null || url.isBlank()) {
            return errorResult(url, "URL is required");
        }
        String trimmedUrl = url.trim();

        // SSRF 安全校验（初始 URL）
        try {
            UrlSafetyChecker.check(trimmedUrl);
        } catch (SecurityException e) {
            log.warn("[WebFetch] SSRF blocked: {} — {}", trimmedUrl, e.getMessage());
            return errorResult(trimmedUrl, "Security blocked: " + e.getMessage());
        }

        int timeout = (timeoutSeconds != null && timeoutSeconds > 0)
                ? Math.min(timeoutSeconds, 30) * 1000
                : DEFAULT_TIMEOUT_MS;

        String mode = (extractMode != null && !extractMode.isBlank())
                ? extractMode.trim().toLowerCase()
                : "text";
        if (!Set.of("text", "markdown", "html").contains(mode)) {
            mode = "text";
        }

        // ── 2. 发起 HTTP 请求（手动处理重定向以确保安全） ──
        log.info("[WebFetch] Fetching URL: {}, mode={}, timeout={}ms", trimmedUrl, mode, timeout);

        HttpResponse response;
        String finalUrl = trimmedUrl;
        int redirectCount = 0;
        final int MAX_REDIRECTS = 5;

        try {
            // 禁用自动重定向，手动校验每个跳转目标
            response = HttpRequest.get(trimmedUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,text/plain,*/*;q=0.8")
                    .timeout(timeout)
                    .setFollowRedirects(false)  // 关键：禁用自动重定向
                    .execute();

            // 手动处理重定向链
            while (isRedirect(response.getStatus()) && redirectCount < MAX_REDIRECTS) {
                String location = response.header("Location");
                if (location == null || location.isBlank()) {
                    log.warn("[WebFetch] Redirect without Location header at {}", finalUrl);
                    break;
                }

                // 解析重定向目标（可能是相对路径）
                String redirectUrl = resolveRedirectUrl(finalUrl, location);
                log.info("[WebFetch] Redirect {} -> {} (count={})", finalUrl, redirectUrl, redirectCount + 1);

                // SSRF 校验重定向目标
                try {
                    UrlSafetyChecker.check(redirectUrl);
                } catch (SecurityException e) {
                    log.warn("[WebFetch] SSRF blocked on redirect: {} — {}", redirectUrl, e.getMessage());
                    return errorResult(trimmedUrl, 
                            "Redirect blocked to unsafe target: " + redirectUrl + " — " + e.getMessage());
                }

                // 额外检查：如果重定向目标是 IP 地址，进行二次校验
                String redirectHost = extractHost(redirectUrl);
                if (redirectHost != null && isIpAddress(redirectHost)) {
                    if (UrlSafetyChecker.isUnsafeIp(redirectHost)) {
                        log.warn("[WebFetch] Redirect blocked to unsafe IP: {}", redirectHost);
                        return errorResult(trimmedUrl,
                                "Redirect blocked to restricted IP: " + redirectHost);
                    }
                }

                finalUrl = redirectUrl;
                redirectCount++;

                // 发起下一次请求
                response.close();  // 关闭前一个响应
                response = HttpRequest.get(finalUrl)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,text/plain,*/*;q=0.8")
                        .timeout(timeout)
                        .setFollowRedirects(false)
                        .execute();
            }

            if (redirectCount >= MAX_REDIRECTS) {
                log.warn("[WebFetch] Max redirects exceeded: {}", trimmedUrl);
                return errorResult(trimmedUrl, 
                        "Too many redirects (max " + MAX_REDIRECTS + ")");
            }

        } catch (Exception e) {
            log.error("[WebFetch] HTTP request failed: {} — {}", finalUrl, e.getMessage());
            return errorResult(trimmedUrl, "HTTP request failed: " + e.getMessage());
        }

        // ── 3. 处理响应 ──
        int statusCode = response.getStatus();
        String contentType = response.header("Content-Type");
        if (contentType == null || contentType.isBlank()) {
            contentType = "unknown";
        }
        result.set("statusCode", statusCode);
        result.set("contentType", contentType);
        
        // 记录最终 URL（如果有重定向）
        if (!finalUrl.equals(trimmedUrl)) {
            result.set("finalUrl", finalUrl);
            result.set("redirectCount", redirectCount);
        }

        if (statusCode < 200 || statusCode >= 400) {
            if (statusCode >= 300 && statusCode < 400) {
                String location = response.header("Location");
                result.set("redirectLocation", location != null ? location : "");
            }
            response.close();
            return errorResult(trimmedUrl, "HTTP " + statusCode);
        }

        // 读取响应体（截断）
        byte[] bodyBytes = response.bodyBytes();
        int contentLength = bodyBytes.length;
        result.set("contentLength", contentLength);

        boolean truncated = false;
        if (contentLength > MAX_CONTENT_BYTES) {
            byte[] trimmed = new byte[MAX_CONTENT_BYTES];
            System.arraycopy(bodyBytes, 0, trimmed, 0, MAX_CONTENT_BYTES);
            bodyBytes = trimmed;
            truncated = true;
        }

        java.nio.charset.Charset charset = parseCharset(contentType);
        String rawContent = new String(bodyBytes, charset);

        // ── 4. 根据模式提取内容 ──
        String content;
        String actualMode;
        boolean isHtml = isHtmlContent(contentType);

        if ("html".equals(mode)) {
            content = rawContent;
            actualMode = "html";
        } else if (!isHtml) {
            // 非 HTML 内容（纯文本/JSON/XML 等），原样返回
            content = rawContent;
            actualMode = "text";
        } else {
            // HTML → text/markdown
            try {
                Document doc = Jsoup.parse(rawContent);

                // 提取 <title>
                String title = doc.title();
                if (title != null && !title.isBlank()) {
                    result.set("title", title);
                }

                if ("markdown".equals(mode)) {
                    content = htmlToMarkdown(doc);
                    actualMode = "markdown";
                } else {
                    content = htmlToText(doc);
                    actualMode = "text";
                }
            } catch (Exception e) {
                log.warn("[WebFetch] HTML parsing failed, returning raw: {}", e.getMessage());
                content = rawContent;
                actualMode = "raw";
            }
        }

        if (truncated) {
            content += "\n\n... [content truncated, exceeds " + MAX_CONTENT_BYTES + " byte limit]";
        }

        result.set("extractMode", actualMode);
        result.set("content", content);

        log.info("[WebFetch] Fetched {}: status={}, contentType={}, length={}, mode={}, redirects={}",
                finalUrl, statusCode, contentType, content.length(), actualMode, redirectCount);

        response.close();
        return JSONUtil.toJsonPrettyStr(result);
    }

    // ──────────────── 重定向处理方法 ────────────────

    /**
     * 判断 HTTP 状态码是否为重定向
     */
    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 ||
               statusCode == 307 || statusCode == 308;
    }

    /**
     * 解析重定向 URL（处理相对路径和绝对路径）
     */
    private static String resolveRedirectUrl(String baseUrl, String location) {
        try {
            java.net.URI baseUri = java.net.URI.create(baseUrl);
            java.net.URI locationUri = java.net.URI.create(location);
            
            // 如果是相对路径，解析为绝对路径
            if (!locationUri.isAbsolute()) {
                locationUri = baseUri.resolve(locationUri);
            }
            
            return locationUri.toString();
        } catch (Exception e) {
            log.warn("[WebFetch] Failed to resolve redirect URL: {} + {} — {}", 
                    baseUrl, location, e.getMessage());
            return location;  // 降级：直接返回原始 location
        }
    }

    /**
     * 从 URL 中提取 host
     */
    private static String extractHost(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断字符串是否为 IP 地址（IPv4 或 IPv6）
     */
    private static boolean isIpAddress(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        // IPv4 简单检查
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return true;
        }
        // IPv6 简单检查（包含冒号）
        if (host.contains(":") || (host.startsWith("[") && host.endsWith("]"))) {
            return true;
        }
        return false;
    }

    // ──────────────── HTML 提取方法 ────────────────

    /**
     * 从 HTML 提取干净的纯文本，保留基本结构（标题、段落、链接）。
     */
    private String htmlToText(Document doc) {
        // 移除不需要的标签
        for (String tag : REMOVE_TAGS) {
            doc.select(tag).remove();
        }

        // 移除注释
        doc.select("*").forEach(el -> {
            el.childNodes().stream()
                    .filter(n -> n instanceof org.jsoup.nodes.Comment)
                    .forEach(n -> n.remove());
        });

        StringBuilder sb = new StringBuilder();

        // 标题
        for (int i = 1; i <= 6; i++) {
            for (Element h : doc.select("h" + i)) {
                String text = h.wholeText().trim();
                if (!text.isEmpty()) {
                    sb.append("#".repeat(i)).append(" ").append(text).append("\n\n");
                }
            }
        }

        // 段落
        for (Element p : doc.select("p")) {
            String text = extractBlockText(p);
            if (!text.isEmpty()) {
                sb.append(text).append("\n\n");
            }
        }

        // 列表项
        Elements liAll = doc.select("li");
        if (!liAll.isEmpty()) {
            for (Element li : liAll) {
                String text = extractBlockText(li);
                if (!text.isEmpty()) {
                    // 判断是否在有序列表中
                    boolean ordered = li.parents().stream()
                            .anyMatch(p -> p.tagName().matches("ol"));
                    sb.append(ordered ? "1. " : "- ").append(text).append("\n");
                }
            }
            sb.append("\n");
        }

        // 链接汇总
        Elements links = doc.select("a[href]");
        if (!links.isEmpty()) {
            Set<String> seen = new HashSet<>();
            sb.append("--- Links ---\n");
            for (Element a : links) {
                String href = a.absUrl("href");
                String linkText = a.wholeText().trim();
                if (!href.isEmpty() && !href.startsWith("javascript:") && seen.add(href)) {
                    sb.append("- ");
                    if (!linkText.isEmpty()) {
                        sb.append("[").append(linkText).append("](").append(href).append(")");
                    } else {
                        sb.append(href);
                    }
                    sb.append("\n");
                }
            }
        }

        if (sb.isEmpty()) {
            // 兜底：取 body 全部文本
            Element body = doc.body();
            String text = (body != null) ? body.wholeText().trim() : doc.wholeText().trim();
            return condenseWhitespace(text);
        }

        return sb.toString().trim();
    }

    /**
     * 从 HTML 转换为简化的 Markdown。
     */
    private String htmlToMarkdown(Document doc) {
        // 移除不需要的标签
        for (String tag : REMOVE_TAGS) {
            doc.select(tag).remove();
        }

        doc.select("*").forEach(el -> {
            el.childNodes().stream()
                    .filter(n -> n instanceof org.jsoup.nodes.Comment)
                    .forEach(n -> n.remove());
        });

        StringBuilder sb = new StringBuilder();

        for (Element child : doc.body().children()) {
            convertElement(child, sb);
        }

        String result = sb.toString().trim();
        return condenseWhitespace(result);
    }

    private void convertElement(Element el, StringBuilder sb) {
        String tag = el.tagName().toLowerCase();

        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = tag.charAt(1) - '0';
                String text = el.wholeText().trim();
                if (!text.isEmpty()) {
                    sb.append("#".repeat(level)).append(" ").append(text).append("\n\n");
                }
            }
            case "p" -> {
                String text = extractBlockText(el);
                if (!text.isEmpty()) {
                    sb.append(text).append("\n\n");
                }
            }
            case "ul", "ol" -> {
                for (Element li : el.children()) {
                    if (li.tagName().equals("li")) {
                        String prefix = tag.equals("ol") ? "1. " : "- ";
                        String text = convertInlineElements(li);
                        if (!text.isEmpty()) {
                            sb.append(prefix).append(text).append("\n");
                        }
                    }
                }
                sb.append("\n");
            }
            case "pre" -> {
                Element code = el.selectFirst("code");
                String codeText = (code != null) ? code.wholeText() : el.wholeText();
                sb.append("```\n").append(codeText.trim()).append("\n```\n\n");
            }
            case "blockquote" -> {
                String text = el.wholeText().trim();
                for (String line : text.split("\n")) {
                    sb.append("> ").append(line).append("\n");
                }
                sb.append("\n");
            }
            case "hr" -> sb.append("---\n\n");
            case "table" -> {
                convertTable(el, sb);
            }
            default -> {
                // div / section / article / main 等容器：递归子元素
                for (Element child : el.children()) {
                    convertElement(child, sb);
                }
            }
        }
    }

    private void convertTable(Element table, StringBuilder sb) {
        Elements rows = table.select("tr");
        if (rows.isEmpty()) return;

        for (Element row : rows) {
            sb.append("| ");
            Elements cells = row.select("th, td");
            for (Element cell : cells) {
                sb.append(cell.wholeText().trim()).append(" | ");
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    /**
     * 转换内联元素（链接、加粗、斜体、图片、代码等）为 Markdown。
     */
    private String convertInlineElements(Element el) {
        StringBuilder sb = new StringBuilder();
        for (org.jsoup.nodes.Node node : el.childNodes()) {
            if (node instanceof org.jsoup.nodes.TextNode) {
                sb.append(((org.jsoup.nodes.TextNode) node).getWholeText());
            } else if (node instanceof Element child) {
                String childTag = child.tagName().toLowerCase();
                switch (childTag) {
                    case "a" -> {
                        String href = child.absUrl("href");
                        String text = convertInlineElements(child);
                        if (!href.isEmpty() && !href.startsWith("javascript:")) {
                            sb.append("[").append(text).append("](").append(href).append(")");
                        } else {
                            sb.append(text);
                        }
                    }
                    case "strong", "b" -> sb.append("**").append(convertInlineElements(child)).append("**");
                    case "em", "i" -> sb.append("*").append(convertInlineElements(child)).append("*");
                    case "code" -> sb.append("`").append(child.wholeText()).append("`");
                    case "img" -> {
                        String alt = child.attr("alt");
                        String src = child.absUrl("src");
                        if (!src.isEmpty()) {
                            sb.append("![").append(alt.isEmpty() ? "image" : alt)
                                    .append("](").append(src).append(")");
                        }
                    }
                    case "br" -> sb.append("\n");
                    default -> sb.append(convertInlineElements(child));
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * 提取块级元素的文本，同时转换内联 Markdown 元素。
     */
    private String extractBlockText(Element el) {
        // 移除内部不需要的元素
        el.select(String.join(",", REMOVE_TAGS)).remove();
        return convertInlineElements(el);
    }

    // ──────────────── 工具方法 ────────────────

    private String errorResult(String url, String message) {
        JSONObject result = new JSONObject();
        result.set("url", url);
        result.set("error", true);
        result.set("message", message);
        return JSONUtil.toJsonPrettyStr(result);
    }

    /**
     * 判断 Content-Type 是否为 HTML。
     */
    private static boolean isHtmlContent(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase();
        return lower.contains("text/html") || lower.contains("application/xhtml");
    }

    /**
     * 从 Content-Type 中解析字符集，默认 UTF-8。
     */
    private static java.nio.charset.Charset parseCharset(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        String lower = contentType.toLowerCase();
        int idx = lower.indexOf("charset=");
        if (idx >= 0) {
            String cs = lower.substring(idx + 8).trim();
            // 去掉分号和后续参数
            int semi = cs.indexOf(';');
            if (semi >= 0) cs = cs.substring(0, semi);
            cs = cs.trim();
            try {
                return java.nio.charset.Charset.forName(cs);
            } catch (Exception ignored) {
                // 不支持的字符集，回退 UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * 压缩多余空白字符（多个空行合并，行首尾去空格）。
     */
    private static String condenseWhitespace(String text) {
        if (text == null || text.isEmpty()) return text;
        // 将连续 3 个以上的换行替换为 2 个换行
        return text.replaceAll("\n{3,}", "\n\n").trim();
    }
}
