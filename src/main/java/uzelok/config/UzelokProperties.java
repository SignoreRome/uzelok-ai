package uzelok.config;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("uzelok")
public record UzelokProperties(
        @DefaultValue Telegram telegram,
        @DefaultValue Storage storage,
        @DefaultValue Limits limits,
        @DefaultValue Llm llm,
        @DefaultValue Knowledge knowledge,
        @DefaultValue Worker worker,
        @DefaultValue Otel otel,
        @DefaultValue Debug debug) {

    public record Telegram(
            String botToken,
            String botUsername,
            @DefaultValue("") String proxyUrl,
            @DefaultValue("30") int pollTimeoutSeconds,
            @DefaultValue("2000") int mediaGroupWindowMs,
            @DefaultValue("8000") int maxTextChars,
            @DefaultValue("15728640") long maxPhotoBytes,
            @DefaultValue("1048576") long maxDocumentBytes,
            @DefaultValue("30000000") long maxImagePixels) {
    }

    public record Storage(
            @DefaultValue("/var/lib/uzelok/artifacts") String artifactsDir,
            @DefaultValue("/var/lib/uzelok/vaults") String vaultsDir,
            @DefaultValue("/var/lib/uzelok/exports") String exportsDir) {
    }

    public record Limits(
            @DefaultValue("20") int jobsPerDay,
            @DefaultValue("5") int jobsPerHour,
            @DefaultValue("72") int attachmentTtlHours) {
    }

    public record Llm(
            Map<String, Provider> providers,
            Map<String, Step> steps,
            Map<String, Pricing> pricing,
            @DefaultValue("v1") String promptVersion,
            @DefaultValue("2.00") BigDecimal dailyBudgetUsd,
            @DefaultValue("120") int timeoutSeconds,
            @DefaultValue("") String proxyUrl) {

        public Llm {
            providers = providers == null ? Map.of() : Map.copyOf(providers);
            steps = steps == null ? Map.of() : Map.copyOf(steps);
            pricing = pricing == null ? Map.of() : Map.copyOf(pricing);
        }
    }

    public record Provider(String baseUrl, String apiKey) {
    }

    public record Step(String provider, String model, String fallbackModel) {
    }

    public record Pricing(
            @DefaultValue("0") BigDecimal inputPerMillion,
            @DefaultValue("0") BigDecimal outputPerMillion) {
    }

    public record Knowledge(
            @DefaultValue("") String lintCommand,
            @DefaultValue("60") int lintTimeoutSeconds,
            @DefaultValue("src/test/resources/lint-fixtures") String lintParityFixtures,
            @DefaultValue("0.55") double confidenceThreshold,
            @DefaultValue("60") int maxFileNameLength,
            @DefaultValue("50000000") long exportMaxBytes,
            @DefaultValue("5000") int exportMaxFiles) {
    }

    public record Worker(
            @DefaultValue("10") int leaseMinutes,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("2000") int pollIntervalMs) {
    }

    public record Otel(@DefaultValue("false") boolean enabled) {
    }

    public record Debug(@DefaultValue("false") boolean dumpArtifacts) {
    }
}
