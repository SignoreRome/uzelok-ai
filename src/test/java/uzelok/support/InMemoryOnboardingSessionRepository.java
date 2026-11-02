package uzelok.support;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import uzelok.domain.port.OnboardingSessionRepository;

public final class InMemoryOnboardingSessionRepository implements OnboardingSessionRepository {

    private record Session(UUID tenantId, Instant expiresAt) {
    }

    private final Map<Long, Session> sessions = new HashMap<>();

    @Override
    public synchronized void awaitPath(UUID tenantId, long chatId, Instant expiresAt) {
        sessions.put(chatId, new Session(tenantId, expiresAt));
    }

    @Override
    public synchronized Optional<UUID> consumeAwaitPath(long chatId, Instant now) {
        Session session = sessions.get(chatId);
        if (session == null || !session.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        sessions.remove(chatId);
        return Optional.of(session.tenantId());
    }

    @Override
    public synchronized boolean isAwaitingPath(long chatId, Instant now) {
        Session session = sessions.get(chatId);
        return session != null && session.expiresAt().isAfter(now);
    }

    @Override
    public synchronized void clear(long chatId) {
        sessions.remove(chatId);
    }

    public synchronized int size() {
        return sessions.size();
    }

    public synchronized Instant expiresAt(long chatId) {
        Session session = sessions.get(chatId);
        return session == null ? null : session.expiresAt();
    }
}
