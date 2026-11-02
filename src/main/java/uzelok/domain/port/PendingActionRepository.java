package uzelok.domain.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.PendingAction;

public interface PendingActionRepository {

    PendingAction create(PendingAction action);

    /**
     * Атомарно помечает токен использованным. Пустой результат означает, что токен
     * просрочен, уже использован или принадлежит другому чату.
     */
    Optional<PendingAction> consume(String token, long chatId, Instant now);

    Optional<PendingAction> findByToken(String token);

    /**
     * Помечает использованными все неиспользованные токены задачи.
     */
    void invalidateForJob(UUID jobId);
}
