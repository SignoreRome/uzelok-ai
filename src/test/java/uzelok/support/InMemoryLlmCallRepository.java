package uzelok.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.LlmCallRecord;
import uzelok.domain.port.LlmCallRepository;

public final class InMemoryLlmCallRepository implements LlmCallRepository {

    private record StoredCall(LlmCallRecord call, Instant recordedAt) {
    }

    private final Map<String, StoredCall> calls = new LinkedHashMap<>();

    @Override
    public synchronized Optional<String> findSuccessfulResponse(String requestKey) {
        StoredCall stored = calls.get(requestKey);
        if (stored == null || !stored.call().success() || stored.call().responseText() == null) {
            return Optional.empty();
        }
        return Optional.of(stored.call().responseText());
    }

    @Override
    public synchronized void record(LlmCallRecord call) {
        calls.putIfAbsent(call.requestKey(), new StoredCall(call, Instant.now()));
    }

    public synchronized void recordAt(LlmCallRecord call, Instant at) {
        calls.putIfAbsent(call.requestKey(), new StoredCall(call, at));
    }

    @Override
    public synchronized BigDecimal spentSince(UUID tenantId, Instant since) {
        BigDecimal total = BigDecimal.ZERO;
        for (StoredCall stored : calls.values()) {
            LlmCallRecord call = stored.call();
            if (call.costUsd() != null && tenantId.equals(call.tenantId())
                    && !stored.recordedAt().isBefore(since)) {
                total = total.add(call.costUsd());
            }
        }
        return total;
    }

    public synchronized List<LlmCallRecord> all() {
        return calls.values().stream().map(StoredCall::call).toList();
    }

    public synchronized int size() {
        return calls.size();
    }

    public synchronized List<LlmCallRecord> successes() {
        List<LlmCallRecord> result = new ArrayList<>();
        for (StoredCall stored : calls.values()) {
            if (stored.call().success()) {
                result.add(stored.call());
            }
        }
        return List.copyOf(result);
    }
}
