package vip.mate.plugin.api;

/**
 * Plugin types covering the main extension scenarios.
 *
 * @author GLClaw Team
 */
public enum PluginType {

    /** Register new agent tools */
    TOOL,

    /** Register new LLM providers */
    PROVIDER,

    /** Register new messaging channels */
    CHANNEL,

    /** Register new memory providers */
    MEMORY
}
