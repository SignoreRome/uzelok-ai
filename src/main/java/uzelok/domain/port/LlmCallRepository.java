package uzelok.domain.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.LlmCallRecord;

public interface LlmCallRepository {

    Optional<String> findSuccessfulResponse(String requestKey);

    void record(LlmCallRecord call);

    BigDecimal spentSince(UUID tenantId, Instant since);
}
