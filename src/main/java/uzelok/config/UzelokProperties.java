package uzelok.config;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("uzelok")
public record UzelokProperties(
        @DefaultValue Telegram telegram,
        @DefaultValue Llm llm,
        @DefaultValue Knowledge knowledge,
        @DefaultValue Worker worker,
        @DefaultValue Otel otel) {

    public record Telegram(
            String botToken,
            String botUsername,
            @DefaultValue("30") int pollTimeoutSeconds,
            @DefaultValue("1500") int mediaGroupWindowMs) {
    }

    public record Llm(
            Map<String, Provider> providers,
            Map<String, Step> steps,
            @DefaultValue("v1") String promptVersion,
            @DefaultValue("2.00") BigDecimal dailyBudgetUsd,
            @DefaultValue("120") int timeoutSeconds) {

        public Llm {
            providers = providers == null ? Map.of() : Map.copyOf(providers);
            steps = steps == null ? Map.of() : Map.copyOf(steps);
        }
    }

    public record Provider(String baseUrl, String apiKey) {
    }

    public record Step(String provider, String model, String fallbackModel) {
    }

    public record Knowledge(
            @DefaultValue("") String lintCommand,
            @DefaultValue("src/test/resources/lint-fixtures") String lintParityFixtures,
            @DefaultValue(".uzelok-tmp") String tmpDir,
            @DefaultValue("0.55") double confidenceThreshold,
            @DefaultValue("80") int maxTitleLength) {
    }

    public record Worker(
            @DefaultValue("10") int leaseMinutes,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("2000") int pollIntervalMs) {
    }

    public record Otel(@DefaultValue("false") boolean enabled) {
    }
}
