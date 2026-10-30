package uzelok.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class UzelokPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "uzelok.telegram.bot-token=test-token",
                    "uzelok.telegram.bot-username=test-bot",
                    "uzelok.llm.providers.openrouter.base-url=https://openrouter.example/api/v1",
                    "uzelok.llm.providers.openrouter.api-key=test-openrouter-key",
                    "uzelok.llm.providers.opencode-go.base-url=https://opencode.example/v1",
                    "uzelok.llm.providers.opencode-go.api-key=test-opencode-key",
                    "uzelok.llm.steps.extract.provider=openrouter",
                    "uzelok.llm.steps.extract.model=vision-model",
                    "uzelok.llm.steps.extract.fallback-model=fallback-model",
                    "uzelok.llm.steps.draft.provider=opencode-go",
                    "uzelok.llm.steps.draft.model=draft-model");

    @Test
    void bindsEveryConfigurationSection() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            UzelokProperties properties = context.getBean(UzelokProperties.class);

            assertThat(properties.telegram().botToken()).isEqualTo("test-token");
            assertThat(properties.telegram().botUsername()).isEqualTo("test-bot");
            assertThat(properties.telegram().pollTimeoutSeconds()).isEqualTo(30);
            assertThat(properties.telegram().mediaGroupWindowMs()).isEqualTo(2000);

            assertThat(properties.llm().providers()).containsOnlyKeys("openrouter", "opencode-go");
            assertThat(properties.llm().providers().get("openrouter").baseUrl())
                    .isEqualTo("https://openrouter.example/api/v1");
            assertThat(properties.llm().providers().get("openrouter").apiKey())
                    .isEqualTo("test-openrouter-key");
            assertThat(properties.llm().providers().get("opencode-go").baseUrl())
                    .isEqualTo("https://opencode.example/v1");
            assertThat(properties.llm().providers().get("opencode-go").apiKey())
                    .isEqualTo("test-opencode-key");

            assertThat(properties.llm().steps()).containsOnlyKeys("extract", "draft");
            assertThat(properties.llm().steps().get("extract").provider()).isEqualTo("openrouter");
            assertThat(properties.llm().steps().get("extract").model()).isEqualTo("vision-model");
            assertThat(properties.llm().steps().get("extract").fallbackModel())
                    .isEqualTo("fallback-model");
            assertThat(properties.llm().steps().get("draft").provider()).isEqualTo("opencode-go");
            assertThat(properties.llm().steps().get("draft").model()).isEqualTo("draft-model");
            assertThat(properties.llm().promptVersion()).isEqualTo("v1");
            assertThat(properties.llm().dailyBudgetUsd())
                    .isEqualByComparingTo(new BigDecimal("2.00"));
            assertThat(properties.llm().timeoutSeconds()).isEqualTo(120);

            assertThat(properties.knowledge().lintCommand()).isEmpty();
            assertThat(properties.knowledge().lintParityFixtures())
                    .isEqualTo("src/test/resources/lint-fixtures");
            assertThat(properties.knowledge().tmpDir()).isEqualTo(".uzelok-tmp");
            assertThat(properties.knowledge().confidenceThreshold()).isEqualTo(0.55);
            assertThat(properties.knowledge().maxTitleLength()).isEqualTo(80);

            assertThat(properties.worker().leaseMinutes()).isEqualTo(10);
            assertThat(properties.worker().maxAttempts()).isEqualTo(3);
            assertThat(properties.worker().pollIntervalMs()).isEqualTo(2000);

            assertThat(properties.otel().enabled()).isFalse();
            assertThat(properties.debug().dumpArtifacts()).isFalse();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(UzelokProperties.class)
    static class TestConfiguration {
    }
}
