package uzelok.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import uzelok.config.UzelokProperties;

public final class LlmRouter {

    private static final Set<String> STEPS = Set.of("extract", "draft");

    public record Route(String provider, String baseUrl, String apiKey, List<String> models) {

        public Route {
            models = List.copyOf(models);
        }
    }

    private final UzelokProperties.Llm properties;

    public LlmRouter(UzelokProperties.Llm properties) {
        this.properties = properties;
        for (String step : STEPS) {
            UzelokProperties.Step configured = properties.steps().get(step);
            if (configured == null) {
                throw new IllegalStateException("не настроен шаг LLM: " + step);
            }
            if (configured.model() == null || configured.model().isBlank()) {
                throw new IllegalStateException("не задана модель шага LLM: " + step);
            }
            if (configured.provider() == null || configured.provider().isBlank()
                    || !properties.providers().containsKey(configured.provider())) {
                throw new IllegalStateException("не настроен провайдер шага LLM: " + step);
            }
        }
    }

    public Route route(String step) {
        String key = step == null ? "" : step.toLowerCase(Locale.ROOT);
        UzelokProperties.Step configured = properties.steps().get(key);
        if (configured == null) {
            throw new IllegalStateException("неизвестный шаг LLM: " + step);
        }
        UzelokProperties.Provider provider = properties.providers().get(configured.provider());
        if (provider == null) {
            throw new IllegalStateException("неизвестный провайдер LLM: " + configured.provider());
        }
        List<String> models = new ArrayList<>();
        models.add(configured.model());
        if (configured.fallbackModel() != null && !configured.fallbackModel().isBlank()) {
            models.add(configured.fallbackModel());
        }
        return new Route(configured.provider(), provider.baseUrl(), provider.apiKey(),
                List.copyOf(models));
    }
}
