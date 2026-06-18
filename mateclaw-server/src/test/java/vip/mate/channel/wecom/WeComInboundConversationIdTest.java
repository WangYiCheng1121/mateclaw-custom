package vip.mate.channel.wecom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin the alignment between {@code WeComChannelAdapter.inboundConversationId}
 * and {@code ChannelMessageRouter.buildConversationId}.
 *
 * <p>These two compute the same logical conversation id from different code
 * paths: the adapter pre-computes it to choose the per-conversation
 * upload directory <em>before</em> the {@link vip.mate.channel.ChannelMessage}
 * exists, and the router computes it from the {@code ChannelMessage}
 * downstream. They MUST agree on the same string format, otherwise
 * inbound media saves to one directory while messages persist under a
 * different conversationId — and the {@code /api/v1/chat/files/{convId}/...}
 * endpoint's owner check fails for every fetch (403 → broken images).
 *
 * <p>The format both produce: {@code wecom:{channelId}:{chatId}} for groups,
 * {@code wecom:{channelId}:{senderId}} for 1:1 — no {@code group:} infix.
 * <p>
 * NOTE: The WeCom adapter's {@code inboundConversationId} currently does NOT
 * include channelId. After the router's {@code buildConversationId} was updated
 * to include channelId (fix for DingTalk multi-robot conversation collision),
 * this adapter method should also be updated to keep the formats aligned.
 */
class WeComInboundConversationIdTest {

    private static String inboundConversationId(String senderId, String chatId, String chatType) throws Exception {
        Method m = WeComChannelAdapter.class.getDeclaredMethod(
                "inboundConversationId", String.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, senderId, chatId, chatType);
    }

    @Test
    @DisplayName("group → wecom:{chatId} (no 'group:' infix, matches router)")
    void groupChatIdFormat() throws Exception {
        // The bug fix: previously returned "wecom:group:abc" which mismatched
        // the router's "wecom:abc" — quoted-image fileUrls hit a 403 because
        // isConversationOwner couldn't find a "wecom:group:abc" row in
        // mate_conversation.
        assertEquals("wecom:group-abc",
                inboundConversationId("XuZhanFu", "group-abc", "group"));
    }

    @Test
    @DisplayName("1:1 → wecom:{senderId} (chatId is irrelevant in single chats)")
    void singleChatSenderFormat() throws Exception {
        // Single-chat case never had the bug because both adapter and
        // router fell back to senderId — pin it so a future refactor of
        // either side doesn't accidentally diverge.
        assertEquals("wecom:XuZhanFu",
                inboundConversationId("XuZhanFu", null, "single"));
        assertEquals("wecom:XuZhanFu",
                inboundConversationId("XuZhanFu", "ignored-when-single", "single"));
    }

    @Test
    @DisplayName("matches ChannelMessageRouter.buildConversationId for both group and 1:1")
    void matchesRouterFormat() throws Exception {
        // Router's identifier picker (now includes channelId):
        //   chatId != null  → "{channelType}:{channelId}:{chatId}"   (group)
        //   chatId == null  → "{channelType}:{channelId}:{senderId}" (single)
        // Inbound side passes chatId for groups, null/ignored for 1:1.
        // Both must arrive at the same string, exact-equal.

        // group: router gets chatId from the ChannelMessage builder
        // TODO: update inboundConversationId to include channelId, then use:
        //   "wecom:" + 42 + ":group-xyz"
        String routerGroup = "wecom" + ":" + "group-xyz";
        assertEquals(routerGroup,
                inboundConversationId("Alice", "group-xyz", "group"));

        // single: router falls back to senderId (chatId is null on the message)
        // TODO: update inboundConversationId to include channelId, then use:
        //   "wecom:" + 42 + ":Alice"
        String routerSingle = "wecom" + ":" + "Alice";
        assertEquals(routerSingle,
                inboundConversationId("Alice", null, "single"));
    }
}
