package uzelok.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.PendingAction;
import uzelok.domain.port.PendingActionRepository;

public final class InMemoryPendingActionRepository implements PendingActionRepository {

    private final Map<String, PendingAction> actions = new LinkedHashMap<>();

    @Override
    public synchronized PendingAction create(PendingAction action) {
        actions.put(action.token(), action);
        return action;
    }

    @Override
    public synchronized Optional<PendingAction> consume(String token, long chatId, Instant now) {
        PendingAction existing = actions.get(token);
        if (existing == null || existing.usedAt() != null || existing.chatId() != chatId
                || !existing.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        PendingAction used = new PendingAction(existing.token(), existing.jobId(),
                existing.tenantId(), existing.type(), existing.chatId(), existing.messageId(),
                existing.expiresAt(), now);
        actions.put(token, used);
        return Optional.of(used);
    }

    @Override
    public synchronized Optional<PendingAction> findByToken(String token) {
        return Optional.ofNullable(actions.get(token));
    }

    @Override
    public synchronized void invalidateForJob(UUID jobId) {
        Instant now = Instant.now();
        for (PendingAction existing : java.util.List.copyOf(actions.values())) {
            if (existing.jobId().equals(jobId) && existing.usedAt() == null) {
                actions.put(existing.token(), new PendingAction(existing.token(), existing.jobId(),
                        existing.tenantId(), existing.type(), existing.chatId(),
                        existing.messageId(), existing.expiresAt(), now));
            }
        }
    }
}
