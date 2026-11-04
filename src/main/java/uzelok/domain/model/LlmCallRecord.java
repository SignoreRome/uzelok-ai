package uzelok.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

public record LlmCallRecord(
        UUID tenantId,
        UUID jobId,
        String step,
        String provider,
        String requestedModel,
        String model,
        String slot,
        String fallbackReason,
        String promptVersion,
        String requestKey,
        int inputTokens,
        int outputTokens,
        int cacheReadTokens,
        int cacheWriteTokens,
        int reasoningTokens,
        BigDecimal costUsd,
        int latencyMs,
        boolean success,
        String responseText) {
}
