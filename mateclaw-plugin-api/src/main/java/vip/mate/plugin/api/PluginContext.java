package vip.mate.plugin.api;

import org.slf4j.Logger;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.plugin.api.channel.PluginChannelAdapter;
import vip.mate.plugin.api.memory.PluginMemoryProvider;

import java.util.function.Supplier;

/**
 * Platform API provided to plugins for registering capabilities.
 *
 * @author GLClaw Team
 */
public interface PluginContext {

    /**
     * Register a tool that will be available to agents.
     *
     * @param tool the tool callback
     */
    void registerTool(ToolCallback tool);

    /**
     * Register a tool with an availability check.
     * <p>
     * The check function is evaluated lazily each time the agent tool set is built.
     * When it returns {@code false}, the tool is silently excluded from the agent's
     * available tools — useful for tools that require an external API key or dependency.
     *
     * @param tool              the tool callback
     * @param availabilityCheck returns true if the tool should be available
     */
    void registerTool(ToolCallback tool, Supplier<Boolean> availabilityCheck);

    /**
     * Register a custom LLM provider.
     *
     * @param providerId unique provider identifier
     * @param chatModel  the chat model implementation
     */
    void registerProvider(String providerId, ChatModel chatModel);

    /**
     * Register a messaging channel adapter.
     *
     * @param channel the channel adapter
     */
    void registerChannel(PluginChannelAdapter channel);

    /**
     * Register a memory provider.
     * <p>
     * Only one external memory provider is allowed at a time.
     * If another plugin has already registered one, a {@link PluginException} is thrown.
     *
     * @param provider the memory provider
     * @throws PluginException if an external memory provider is already registered
     */
    void registerMemoryProvider(PluginMemoryProvider provider);

    /**
     * Read a configuration value from the plugin's config.
     *
     * @param key  the config key
     * @param type the expected type
     * @param <T>  the type
     * @return the config value, or null if not set
     */
    <T> T getConfig(String key, Class<T> type);

    /**
     * Get a logger instance for this plugin.
     *
     * @return the logger
     */
    Logger getLogger();
}
