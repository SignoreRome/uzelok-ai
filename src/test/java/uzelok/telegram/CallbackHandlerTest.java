package uzelok.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uzelok.domain.model.ChatAccess;
import uzelok.support.InMemoryAccessRepository;
import uzelok.support.RecordingAuditRepository;
import uzelok.support.TelegramUpdates;

class CallbackHandlerTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID APP_USER_ID = UUID.randomUUID();
    private static final long CHAT_ID = 100L;
    private static final long FROM_ID = 200L;

    private RecordingAuditRepository audit;
    private InMemoryAccessRepository access;
    private CallbackHandler handler;

    @BeforeEach
    void setUp() {
        audit = new RecordingAuditRepository();
        access = new InMemoryAccessRepository()
                .add(CHAT_ID, new ChatAccess(TENANT_ID, APP_USER_ID, FROM_ID, true));
        handler = new CallbackHandler(audit, access);
    }

    @Test
    void unknownChatIsAuditedWithoutReply() {
        handler.handle(TelegramUpdates.callback(1, 999L, 500, FROM_ID, "token").getCallbackQuery());

        assertThat(audit.actions()).containsExactly("auth_denied");
        assertThat(audit.records().get(0).detail()).containsEntry("reason", "unknown_chat");
    }

    @Test
    void allowedUserGetsCallbackRejected() {
        handler.handle(TelegramUpdates.callback(2, CHAT_ID, 500, FROM_ID, "token").getCallbackQuery());

        assertThat(audit.actions()).containsExactly("callback_rejected");
    }

    @Test
    void foreignUserIsAuditedWithoutReply() {
        handler.handle(TelegramUpdates.callback(3, CHAT_ID, 500, 999L, "token").getCallbackQuery());

        assertThat(audit.actions()).containsExactly("auth_denied");
        assertThat(audit.records().get(0).detail()).containsEntry("reason", "user_mismatch");
    }
}
