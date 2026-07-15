package vip.mate.llm.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 当前 Agent 请求的平台绑定上下文（ThreadLocal）。
 * <p>
 * 在 {@code AgentService.chat()} 入口处查询 agent 绑定的知识库 / MCP
 * 引用 ID 并设置，供 {@code OpenAiCompatibleChatModelBuilder} /
 * {@code AnthropicChatModelBuilder} 在构建 LLM 代理请求时注入
 * {@code knowledgeBaseIds} / {@code mcpIds} 字段，使平台代理能按
 * agent 粒度注入知识库上下文和注册 MCP 工具。
 *
 * <p>使用模式（对称于 {@link LlmUserContextHolder}）：
 * <pre>{@code
 *   try {
 *       AgentPlatformBindingContext.set(kbRefIds, mcpRefIds);
 *       // ... chat / chatStream ...
 *   } finally {
 *       AgentPlatformBindingContext.clear();
 *   }
 * }</pre>
 *
 * @author MateClaw Team
 */
public final class AgentPlatformBindingContext {

    private AgentPlatformBindingContext() {}

    private static final ThreadLocal<List<String>> kbRefIdsHolder = new ThreadLocal<>();
    private static final ThreadLocal<List<Integer>> mcpRefIdsHolder = new ThreadLocal<>();

    /**
     * 设置当前请求的 agent 平台绑定 ID。
     *
     * @param kbRefIds  知识库引用 ID 列表（可为空）
     * @param mcpRefIds MCP 引用 ID 列表（可为空）
     */
    public static void set(List<String> kbRefIds, List<Integer> mcpRefIds) {
        kbRefIdsHolder.set(kbRefIds != null ? new ArrayList<>(kbRefIds) : Collections.emptyList());
        mcpRefIdsHolder.set(mcpRefIds != null ? new ArrayList<>(mcpRefIds) : Collections.emptyList());
    }

    /** 清除当前线程的绑定上下文（必须在 finally 块中调用） */
    public static void clear() {
        kbRefIdsHolder.remove();
        mcpRefIdsHolder.remove();
    }

    /** 获取知识库引用 ID 列表，可能为空列表（不为 null） */
    public static List<String> getKbRefIds() {
        List<String> val = kbRefIdsHolder.get();
        return val != null ? val : Collections.emptyList();
    }

    /** 获取 MCP 引用 ID 列表，可能为空列表（不为 null） */
    public static List<Integer> getMcpRefIds() {
        List<Integer> val = mcpRefIdsHolder.get();
        return val != null ? val : Collections.emptyList();
    }

    /** 是否有任何绑定数据 */
    public static boolean hasBindings() {
        List<String> kb = kbRefIdsHolder.get();
        List<Integer> mcp = mcpRefIdsHolder.get();
        return (kb != null && !kb.isEmpty()) || (mcp != null && !mcp.isEmpty());
    }
}
