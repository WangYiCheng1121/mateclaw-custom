package vip.mate.llm.chatmodel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import vip.mate.exception.MateClawException;
import vip.mate.llm.gemini.GeminiChatModel;
import vip.mate.llm.gemini.GeminiNativeClient;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.platform.LlmProxyHelper;
import vip.mate.llm.service.ModelProviderService;

/**
 * Strategy implementation for {@link ModelProtocol#GEMINI_NATIVE}.
 *
 * <p>All traffic is routed through the platform LLM proxy
 * ({@code gatewayUrl/ai-manage/api/proxy/gemini}).
 * Direct provider connections are no longer supported;
 * API keys and base URLs are managed exclusively by the platform.
 *
 * <p>Builds a {@link GeminiChatModel} over the native Gemini
 * {@code generateContent} API.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiChatModelBuilder implements ChatModelBuilder {

    private final GeminiNativeClient geminiNativeClient;
    private final ModelProviderService modelProviderService;

    /** 共享的 LLM 代理基础设施（可选；未配置时退化为直连模式） */
    @Autowired(required = false)
    private LlmProxyHelper llmProxyHelper;

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.GEMINI_NATIVE;
    }

    @Override
    public ChatModel build(ModelConfigEntity model, ModelProviderEntity provider, RetryTemplate retry) {
        if (llmProxyHelper == null || !llmProxyHelper.isEnabled()) {
            throw new MateClawException("err.agent.proxy_not_available",
                    "平台 LLM 代理未就绪，请检查平台连接配置");
        }
        String proxyBaseUrl = llmProxyHelper.getProxyBaseUrl() + "/api/proxy/gemini";
        String token = llmProxyHelper.resolveToken();
        if (token == null) {
            throw new MateClawException("err.agent.proxy_token_unavailable",
                    "平台代理 Token 不可用，请检查平台连接");
        }
        String prefixedModel = provider != null && provider.getProviderId() != null
                ? provider.getProviderId() + "::" + model.getModelName()
                : model.getModelName();
        log.info("[LlmProxy] Routing Gemini through platform proxy: baseUrl={}", proxyBaseUrl);
        return new GeminiChatModel(geminiNativeClient, proxyBaseUrl, token,
                prefixedModel, model.getTemperature(), model.getMaxTokens());
    }
}
