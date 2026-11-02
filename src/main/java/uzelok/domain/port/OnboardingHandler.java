package uzelok.domain.port;

import java.util.UUID;
import uzelok.domain.model.OnboardingResult;

public interface OnboardingHandler {

    boolean awaitingPath(long chatId);

    /** Запоминает ожидание пути. Если база уже подключена — вернуть ("base.already", [root]). */
    OnboardingResult expectPath(long chatId, UUID tenantId);

    OnboardingResult createInternal(long chatId, UUID tenantId);

    OnboardingResult connect(long chatId, UUID tenantId, String pathText);

    void reset(long chatId);
}
