package uzelok.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

public record LlmCallRecord(
        UUID tenantId,
        UUID jobId,
        String step,
        String provider,
        String model,
        String promptVersion,
        String requestKey,
        int inputTokens,
        int outputTokens,
        BigDecimal costUsd,
        int latencyMs,
        boolean success,
        String responseText) {
}
