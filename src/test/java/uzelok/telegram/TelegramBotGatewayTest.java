package uzelok.telegram;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import uzelok.domain.model.InlineKeyboard;

class TelegramBotGatewayTest {

    private final TelegramClient client = mock(TelegramClient.class);
    private final TelegramBotGateway gateway = new TelegramBotGateway(client, "token");

    @Test
    void editMessageIgnoresNotModifiedResponse() throws TelegramApiException {
        when(client.execute(any(EditMessageText.class)))
                .thenThrow(new TelegramApiException("Bad Request: message is not modified"));

        assertThatCode(() -> gateway.editMessage(1L, 2L, "текст", InlineKeyboard.empty()))
                .doesNotThrowAnyException();
    }

    @Test
    void editMessagePropagatesOtherErrors() throws TelegramApiException {
        when(client.execute(any(EditMessageText.class)))
                .thenThrow(new TelegramApiException("Bad Request: message to edit not found"));

        assertThatThrownBy(() -> gateway.editMessage(1L, 2L, "текст", InlineKeyboard.empty()))
                .isInstanceOf(TelegramDeliveryException.class);
    }

    @Test
    void answerCallbackSwallowsApiError() throws TelegramApiException {
        when(client.execute(any(AnswerCallbackQuery.class)))
                .thenThrow(new TelegramApiException("Bad Request: query is too old"));

        assertThatCode(() -> gateway.answerCallback("callback-1", ""))
                .doesNotThrowAnyException();
    }
}
