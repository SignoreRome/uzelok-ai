package uzelok.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;
import uzelok.support.InMemoryTelegramUpdateRepository;
import uzelok.support.RecordingAuditRepository;
import uzelok.support.TelegramUpdates;

class UpdateRouterTest {

    private final InMemoryTelegramUpdateRepository updates = new InMemoryTelegramUpdateRepository();
    private final MessageHandler messages = mock(MessageHandler.class);
    private final CallbackHandler callbacks = mock(CallbackHandler.class);
    private final RecordingAuditRepository audit = new RecordingAuditRepository();
    private final UpdateRouter router = new UpdateRouter(updates, messages, callbacks, audit);

    @Test
    void successfulHandlerRegistersUpdate() {
        Update update = TelegramUpdates.text(1, 10, 100, 1, "текст");

        router.consume(List.of(update));

        verify(messages, times(1)).handle(update);
        assertThat(updates.size()).isEqualTo(1);
        assertThat(updates.typeOf(1)).isEqualTo("message");
    }

    @Test
    void handlerFailureDoesNotRegisterUpdateAndIsAudited() {
        Update update = TelegramUpdates.text(6, 10, 100, 1, "сбойный");

        doThrow(new IllegalStateException("сбой")).when(messages).handle(update);

        router.consume(List.of(update));

        verify(messages).handle(update);
        assertThat(updates.size()).isZero();
        assertThat(audit.actions()).contains("update_failed");
        assertThat(audit.records()).anySatisfy(record -> {
            if ("update_failed".equals(record.action())) {
                assertThat(record.detail()).containsEntry("reason", "IllegalStateException");
            }
        });
    }

    @Test
    void handlerFailureDoesNotStopLoop() {
        Update first = TelegramUpdates.text(1, 10, 100, 1, "первый");
        Update second = TelegramUpdates.text(2, 10, 101, 1, "второй");
        doThrow(new RuntimeException("сбой")).when(messages).handle(first);

        router.consume(List.of(first, second));

        verify(messages).handle(first);
        verify(messages).handle(second);
    }

    @Test
    void editedMessageIsOnlyRegistered() {
        Update edited = TelegramUpdates.edited(3, 10, 100, 1, "правка");

        router.consume(List.of(edited));

        verify(messages, never()).handle(any());
        assertThat(updates.typeOf(3)).isEqualTo("edited_message");
    }

    @Test
    void callbackIsRoutedToCallbackHandler() {
        Update callback = TelegramUpdates.callback(4, 10, 100, 1, "token");

        router.consume(List.of(callback));

        verify(callbacks).handle(callback.getCallbackQuery());
        assertThat(updates.typeOf(4)).isEqualTo("callback_query");
    }

    @Test
    void unknownUpdateIsOnlyRegistered() {
        Update other = TelegramUpdates.other(5);

        router.consume(List.of(other));

        verify(messages, never()).handle(any());
        assertThat(updates.typeOf(5)).isEqualTo("other");
    }
}
