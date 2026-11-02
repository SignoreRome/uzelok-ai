package uzelok.domain.model;

import java.util.UUID;

public record LlmContext(UUID tenantId, UUID jobId, String step, String inputHash, int draftVersion) {
}
