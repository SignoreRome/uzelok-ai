package uzelok.config;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;
import uzelok.domain.port.LlmCallRepository;
import uzelok.domain.port.LlmGateway;
import uzelok.llm.HttpLlmClient;
import uzelok.llm.LlmRouter;
import uzelok.llm.OpenAiCompatibleLlmGateway;
import uzelok.llm.PromptCatalog;
import uzelok.llm.StructuredSchemas;

@Configuration(proxyBeanMethods = false)
public class LlmConfig {

    @Bean
    public PromptCatalog promptCatalog(UzelokProperties properties) {
        return new PromptCatalog(properties.llm().promptVersion());
    }

    @Bean
    public StructuredSchemas structuredSchemas() {
        return new StructuredSchemas();
    }

    @Bean
    public HttpLlmClient httpLlmClient(JsonMapper jsonMapper, UzelokProperties properties) {
        ProxySupport.ProxyConfig proxy = ProxySupport.parse(properties.llm().proxyUrl())
                .orElse(null);
        int timeoutSeconds = properties.llm().timeoutSeconds();
        if (proxy == null) {
            return new HttpLlmClient(ProxySelector.getDefault(), jsonMapper, timeoutSeconds);
        }
        ProxySelector selector = ProxySelector.of((InetSocketAddress) proxy.proxy().address());
        Authenticator authenticator = proxy.hasCredentials()
                ? proxyAuthenticator(proxy.user(), proxy.password()) : null;
        return new HttpLlmClient(selector, authenticator, jsonMapper, timeoutSeconds);
    }

    private static Authenticator proxyAuthenticator(String user, char[] password) {
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) {
                    return null;
                }
                return new PasswordAuthentication(user, password);
            }
        };
    }

    @Bean
    public LlmRouter llmRouter(UzelokProperties properties) {
        return new LlmRouter(properties.llm());
    }

    @Bean
    public LlmGateway llmGateway(HttpLlmClient client, LlmRouter router, PromptCatalog prompts,
                                 StructuredSchemas schemas, LlmCallRepository calls,
                                 JsonMapper jsonMapper, UzelokProperties properties, Clock clock) {
        return new OpenAiCompatibleLlmGateway(client, router, prompts, schemas, calls, jsonMapper,
                properties.llm(), clock);
    }
}
