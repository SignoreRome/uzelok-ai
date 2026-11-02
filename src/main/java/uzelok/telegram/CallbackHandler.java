package uzelok.telegram;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import uzelok.domain.model.ActionOutcome;
import uzelok.domain.model.AuditRecord;
import uzelok.domain.model.ChatAccess;
import uzelok.domain.model.InlineKeyboard;
import uzelok.domain.model.PendingAction;
import uzelok.domain.port.AccessRepository;
import uzelok.domain.port.ActionHandler;
import uzelok.domain.port.AuditRepository;
import uzelok.domain.port.PendingActionRepository;
import uzelok.domain.port.TelegramGateway;

public final class CallbackHandler {

    private static final String PREFIX = "act:";

    private final AccessRepository access;
    private final PendingActionRepository actions;
    private final ActionHandler handler;
    private final TelegramGateway telegram;
    private final AuditRepository audit;
    private final Clock clock;

    public CallbackHandler(AccessRepository access, PendingActionRepository actions,
                           ActionHandler handler, TelegramGateway telegram, AuditRepository audit,
                           Clock clock) {
        this.access = access;
        this.actions = actions;
        this.handler = handler;
        this.telegram = telegram;
        this.audit = audit;
        this.clock = clock;
    }

    public void handle(CallbackQuery callback) {
        Long chatId = callback.getMessage() == null ? null : callback.getMessage().getChatId();
        if (chatId == null) {
            audit.record(new AuditRecord(null, null, "callback_rejected", null, null,
                    Map.of("reason", "no_message")));
            return;
        }

        Optional<ChatAccess> found = access.findByChatId(chatId);
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

        String token = token(callback.getData());
        if (token == null) {
            audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "callback_rejected",
                    null, null, Map.of("reason", "token_invalid")));
            return;
        }

        Instant now = clock.instant();
        Optional<PendingAction> consumed = actions.consume(token, chatId, now);
        if (consumed.isEmpty()) {
            audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "callback_rejected",
                    null, null, Map.of("reason", "token_invalid")));
            return;
        }

        PendingAction action = consumed.get();
        ActionOutcome outcome = handler.handle(action);
        if (!outcome.handled()) {
            return;
        }
        if (outcome.userMessage() != null) {
            telegram.sendMessage(chatId, outcome.userMessage(), InlineKeyboard.empty());
        }
        audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "callback_handled",
                "pending_action", action.jobId(),
                Map.of("type", action.type().name())));
    }

    private static String token(String data) {
        if (data == null || !data.startsWith(PREFIX)) {
            return null;
        }
        String token = data.substring(PREFIX.length());
        return token.isBlank() ? null : token;
    }
}
