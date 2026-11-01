package uzelok.telegram;

import java.util.Map;
import java.util.Optional;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import uzelok.domain.model.AuditRecord;
import uzelok.domain.model.ChatAccess;
import uzelok.domain.port.AccessRepository;
import uzelok.domain.port.AuditRepository;

public final class CallbackHandler {

    private final AuditRepository audit;
    private final AccessRepository access;

    public CallbackHandler(AuditRepository audit, AccessRepository access) {
        this.audit = audit;
        this.access = access;
    }

    public void handle(CallbackQuery callback) {
        Long chatId = callback.getMessage() == null ? null : callback.getMessage().getChatId();
        Optional<ChatAccess> found = chatId == null
                ? Optional.empty()
                : access.findByChatId(chatId);
        if (found.isEmpty()) {
            audit.record(new AuditRecord(null, chatId, "auth_denied", null, null,
                    Map.of("reason", "unknown_chat")));
            return;
        }

        ChatAccess chatAccess = found.get();
        if (!chatAccess.allowed()) {
            audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "auth_denied", null, null,
                    Map.of("reason", "not_allowed")));
            return;
        }

        Long fromId = callback.getFrom() == null ? null : callback.getFrom().getId();
        if (fromId == null || fromId != chatAccess.telegramUserId()) {
            audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "auth_denied", null, null,
                    Map.of("reason", "user_mismatch")));
            return;
        }

        audit.record(new AuditRecord(
                null,
                chatId,
                "callback_rejected",
                null,
                null,
                Map.of("reason", "callback-действия появятся на этапе 3b")));
    }
}
