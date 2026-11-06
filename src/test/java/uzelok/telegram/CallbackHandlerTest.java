package uzelok.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uzelok.domain.model.ActionOutcome;
import uzelok.domain.model.ChatAccess;
import uzelok.domain.model.PendingAction;
import uzelok.domain.model.PendingActionType;
import uzelok.domain.port.ActionHandler;
import uzelok.support.InMemoryAccessRepository;
import uzelok.support.InMemoryPendingActionRepository;
import uzelok.support.InMemoryTelegramGateway;
import uzelok.support.MutableClock;
import uzelok.support.RecordingAuditRepository;
import uzelok.support.TelegramUpdates;
import uzelok.support.StubOnboardingHandler;

class CallbackHandlerTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID APP_USER_ID = UUID.randomUUID();
    private static final long CHAT_ID = 100L;
    private static final long FROM_ID = 200L;
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    private RecordingAuditRepository audit;
    private InMemoryAccessRepository access;
    private InMemoryPendingActionRepository actions;
    private InMemoryTelegramGateway telegram;
    private AtomicInteger handled;
    private CallbackHandler handler;

    @BeforeEach
    void setUp() {
        audit = new RecordingAuditRepository();
        access = new InMemoryAccessRepository()
                .add(CHAT_ID, new ChatAccess(TENANT_ID, APP_USER_ID, FROM_ID, true));
        actions = new InMemoryPendingActionRepository();
        telegram = new InMemoryTelegramGateway();
        handled = new AtomicInteger();
        ActionHandler actionHandler = action -> {
            handled.incrementAndGet();
            return ActionOutcome.done("Выполнено.");
        };
        handler = new CallbackHandler(access, actions, actionHandler, new StubOnboardingHandler(),
                telegram, new Messages(), audit, new MutableClock(NOW));
    }

    @Test
    void unknownChatIsAuditedWithoutReply() {
        handler.handle(TelegramUpdates.callback(1, 999L, 500, FROM_ID, "act:token")
                .getCallbackQuery());

        assertThat(audit.actions()).containsExactly("auth_denied");
        assertThat(audit.records().get(0).detail()).containsEntry("reason", "unknown_chat");
    }

    @Test
    void invalidTokenIsRejectedWithoutReply() {
        handler.handle(TelegramUpdates.callback(2, CHAT_ID, 500, FROM_ID, "act:missing")
                .getCallbackQuery());

        assertThat(audit.actions()).containsExactly("callback_rejected");
        assertThat(audit.records().get(0).detail()).containsEntry("reason", "token_invalid");
        assertThat(handled).hasValue(0);
        assertThat(telegram.sent()).isEmpty();
    }

    @Test
    void foreignUserIsAuditedWithoutReply() {
        handler.handle(TelegramUpdates.callback(3, CHAT_ID, 500, 999L, "act:token")
                .getCallbackQuery());

        assertThat(audit.actions()).containsExactly("auth_denied");
        assertThat(audit.records().get(0).detail()).containsEntry("reason", "user_mismatch");
        assertThat(handled).hasValue(0);
    }

    @Test
    void ignoredOutcomeRepliesWithStaleActionMessage() {
        String token = "b".repeat(32);
        actions.create(new PendingAction(token, UUID.randomUUID(), TENANT_ID,
                PendingActionType.REGENERATE, CHAT_ID, 500L, NOW.plusSeconds(3600), null));
        CallbackHandler ignoring = new CallbackHandler(access, actions,
                action -> ActionOutcome.ignored(), new StubOnboardingHandler(), telegram,
                new Messages(), audit, new MutableClock(NOW));

        ignoring.handle(TelegramUpdates.callback(6, CHAT_ID, 500, FROM_ID, "act:" + token)
                .getCallbackQuery());

        assertThat(handled).hasValue(0);
        assertThat(telegram.lastSent().orElseThrow().text())
                .isEqualTo(new Messages().text("action.ignored"));
    }

    @Test
    void validTokenIsHandledOnceAndRepeatedClickIsIgnored() {
        String token = "a".repeat(32);
        actions.create(new PendingAction(token, UUID.randomUUID(), TENANT_ID,
                PendingActionType.SAVE, CHAT_ID, 500L, NOW.plusSeconds(3600), null));

        handler.handle(TelegramUpdates.callback(4, CHAT_ID, 500, FROM_ID, "act:" + token)
                .getCallbackQuery());
        handler.handle(TelegramUpdates.callback(5, CHAT_ID, 500, FROM_ID, "act:" + token)
                .getCallbackQuery());

        assertThat(handled).hasValue(1);
        assertThat(audit.actions()).containsExactly("callback_handled", "callback_rejected");
        assertThat(audit.records().get(0).detail()).containsEntry("type", "SAVE");
        assertThat(telegram.lastSent().orElseThrow().text()).isEqualTo("Выполнено.");
    }
}
