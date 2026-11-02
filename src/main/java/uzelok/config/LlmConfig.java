package uzelok.config;

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
        return new HttpLlmClient(jsonMapper, properties.llm().timeoutSeconds());
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
