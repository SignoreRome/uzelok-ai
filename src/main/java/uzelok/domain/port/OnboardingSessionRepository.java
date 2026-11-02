package uzelok.domain.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OnboardingSessionRepository {

    /**
     * Перед записью удаляет строки с истёкшим ожиданием (expires_at <= :now),
     * затем перезаписывает ожидание пути для чата (step = AWAITING_PATH).
     */
    void awaitPath(UUID tenantId, long chatId, Instant expiresAt);

    /**
     * Атомарно забирает ожидание и удаляет строку:
     * DELETE FROM onboarding_session WHERE chat_id = :chatId AND expires_at > :now
     * RETURNING tenant_id
     */
    Optional<UUID> consumeAwaitPath(long chatId, Instant now);

    boolean isAwaitingPath(long chatId, Instant now);

    void clear(long chatId);
}
