package uzelok.config;

import java.time.Clock;
import java.time.Duration;
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
        return new HttpLlmClient(okHttpClient(proxy, timeoutSeconds), jsonMapper, timeoutSeconds);
    }

    /**
     * java.net.http не умеет Basic в CONNECT-туннеле (JDK блокирует такие схемы), поэтому
     * прокси и Proxy-Authorization задаются явно на клиенте OkHttp.
     */
    private static okhttp3.OkHttpClient okHttpClient(ProxySupport.ProxyConfig proxy,
                                                    int timeoutSeconds) {
        okhttp3.OkHttpClient.Builder builder = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(timeoutSeconds))
                .writeTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(timeoutSeconds + 10L))
                .retryOnConnectionFailure(true);
        if (proxy != null) {
            builder.proxy(proxy.proxy());
            if (proxy.hasCredentials()) {
                String user = proxy.user();
                char[] password = proxy.password();
                builder.proxyAuthenticator((route, response) -> response.request().newBuilder()
                        .header("Proxy-Authorization",
                                okhttp3.Credentials.basic(user, new String(password)))
                        .build());
            }
        }
        return builder.build();
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
