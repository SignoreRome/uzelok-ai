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
import uzelok.domain.model.OnboardingResult;
import uzelok.domain.model.PendingAction;
import uzelok.domain.port.AccessRepository;
import uzelok.domain.port.ActionHandler;
import uzelok.domain.port.AuditRepository;
import uzelok.domain.port.OnboardingHandler;
import uzelok.domain.port.PendingActionRepository;
import uzelok.domain.port.TelegramGateway;

public final class CallbackHandler {

    private static final String PREFIX = "act:";
    private static final String ONBOARDING_PREFIX = "onb:";

    private final AccessRepository access;
    private final PendingActionRepository actions;
    private final ActionHandler handler;
    private final OnboardingHandler onboarding;
    private final TelegramGateway telegram;
    private final Messages messages;
    private final AuditRepository audit;
    private final Clock clock;

    public CallbackHandler(AccessRepository access, PendingActionRepository actions,
                           ActionHandler handler, OnboardingHandler onboarding,
                           TelegramGateway telegram, Messages messages, AuditRepository audit,
                           Clock clock) {
        this.access = access;
        this.actions = actions;
        this.handler = handler;
        this.onboarding = onboarding;
        this.telegram = telegram;
        this.messages = messages;
        this.audit = audit;
        this.clock = clock;
    }

    public void handle(CallbackQuery callback) {
        Long chatId = callback.getMessage() == null ? null : callback.getMessage().getChatId();
        if (chatId == null) {
            answer(callback, messages.text("action.ignored"));
            audit.record(new AuditRecord(null, null, "callback_rejected", null, null,
                    Map.of("reason", "no_message")));
            return;
        }

        Optional<ChatAccess> found = access.findByChatId(chatId);
        if (found.isEmpty()) {
            answer(callback, messages.text("access.unknown_chat"));
            audit.record(new AuditRecord(null, chatId, "auth_denied", null, null,
                    Map.of("reason", "unknown_chat")));
            return;
        }

        ChatAccess chatAccess = found.get();
        if (!chatAccess.allowed()) {
            answer(callback, messages.text("access.denied"));
            audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "auth_denied", null, null,
                    Map.of("reason", "not_allowed")));
            return;
        }

        Long fromId = callback.getFrom() == null ? null : callback.getFrom().getId();
        if (fromId == null || fromId != chatAccess.telegramUserId()) {
            answer(callback, messages.text("access.denied"));
            audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "auth_denied", null, null,
                    Map.of("reason", "user_mismatch")));
            return;
        }

        String data = callback.getData();
        if (data != null && data.startsWith(ONBOARDING_PREFIX)) {
            handleOnboarding(callback, chatId, chatAccess.tenantId(),
                    data.substring(ONBOARDING_PREFIX.length()));
            return;
        }

        String token = token(data);
        if (token == null) {
            answer(callback, messages.text("action.ignored"));
            audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "callback_rejected",
                    null, null, Map.of("reason", "token_invalid")));
            return;
        }

        Instant now = clock.instant();
        Optional<PendingAction> consumed = actions.consume(token, chatId, now);
        if (consumed.isEmpty()) {
            answer(callback, messages.text("action.ignored"));
            audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "callback_rejected",
                    null, null, Map.of("reason", "token_invalid")));
            return;
        }

        answer(callback, "");
        PendingAction action = consumed.get();
        ActionOutcome outcome = handler.handle(action);
        if (!outcome.handled()) {
            telegram.sendMessage(chatId, messages.text("action.ignored"), InlineKeyboard.empty());
            return;
        }
        if (outcome.userMessage() != null) {
            telegram.sendMessage(chatId, outcome.userMessage(), InlineKeyboard.empty());
        }
        audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "callback_handled",
                "pending_action", action.jobId(),
                Map.of("type", action.type().name())));
    }

    private void handleOnboarding(CallbackQuery callback, long chatId, java.util.UUID tenantId,
                                  String action) {
        if (!"create".equals(action) && !"connect".equals(action)) {
            answer(callback, messages.text("action.ignored"));
            audit.record(new AuditRecord(tenantId, chatId, "callback_rejected", null, null,
                    Map.of("reason", "onboarding_action")));
            return;
        }
        answer(callback, "");
        OnboardingResult result = "create".equals(action)
                ? onboarding.createInternal(chatId, tenantId)
                : onboarding.expectPath(chatId, tenantId);
        InlineKeyboard keyboard = "base.create_failed".equals(result.messageKey())
                || "base.connect_failed".equals(result.messageKey())
                ? Keyboards.onboarding(messages)
                : InlineKeyboard.empty();
        telegram.sendMessage(chatId, messages.text(result.messageKey(),
                result.args().toArray()), keyboard);
    }

    private void answer(CallbackQuery callback, String text) {
        String callbackId = callback.getId();
        if (callbackId != null) {
            telegram.answerCallback(callbackId, text);
        }
    }

    private static String token(String data) {
        if (data == null || !data.startsWith(PREFIX)) {
            return null;
        }
        String token = data.substring(PREFIX.length());
        return token.isBlank() ? null : token;
    }
}
