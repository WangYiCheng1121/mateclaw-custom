package vip.mate.llm.platform;

/**
 * 当前请求的终端用户身份上下文（ThreadLocal）
 * <p>
 * 在每个聊天入口（Controller / ChannelMessageRouter / Cron / Wiki）设置，
 * 供 {@code OpenAiCompatibleChatModelBuilder} 在构建 HTTP 请求时注入
 * {@code X-User-Id} 和 {@code X-User-Name} Header，使平台端能按用户粒度
 * 做用量统计和审计。
 *
 * @author MateClaw Team
 */
public final class LlmUserContextHolder {

    private LlmUserContextHolder() {}

    private static final ThreadLocal<String> userIdHolder = new ThreadLocal<>();
    private static final ThreadLocal<String> userNameHolder = new ThreadLocal<>();

    /**
     * 设置当前线程的用户上下文。
     *
     * @param userId   用户 ID（不可为空）
     * @param userName 用户名（不可为空）
     */
    public static void set(String userId, String userName) {
        userIdHolder.set(userId);
        userNameHolder.set(userName);
    }

    /** 清除当前线程的用户上下文（必须在 finally 块中调用） */
    public static void clear() {
        userIdHolder.remove();
        userNameHolder.remove();
    }

    /** 获取当前用户 ID，可能为 null */
    public static String getUserId() {
        return userIdHolder.get();
    }

    /** 获取当前用户名，可能为 null */
    public static String getUserName() {
        return userNameHolder.get();
    }
}
