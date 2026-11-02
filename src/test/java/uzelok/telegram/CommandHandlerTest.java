package uzelok.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uzelok.config.UzelokProperties;
import uzelok.support.InMemoryTelegramGateway;

class CommandHandlerTest {

    private static final long CHAT_ID = 100L;

    private InMemoryTelegramGateway telegram;
    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        telegram = new InMemoryTelegramGateway();
        UzelokProperties.Telegram props = new UzelokProperties.Telegram(
                "token", "test_bot", 30, 2000, 8000, 15_728_640L, 1_048_576L, 30_000_000L);
        handler = new CommandHandler(telegram, new Messages(), props);
    }

    @Test
    void startCommandSendsInstructions() {
        assertThat(handler.handle(CHAT_ID, "/start")).isTrue();
        assertThat(telegram.lastSent().orElseThrow().text()).contains("Привет");
    }

    @Test
    void helpCommandSendsInstructions() {
        assertThat(handler.handle(CHAT_ID, "/help")).isTrue();
        assertThat(telegram.lastSent().orElseThrow().text()).contains("Отправьте");
    }

    @Test
    void startWithBotUsernameIsAccepted() {
        assertThat(handler.handle(CHAT_ID, "/start@test_bot")).isTrue();
        assertThat(telegram.lastSent().orElseThrow().text()).contains("Привет");
    }

    @Test
    void commandForAnotherBotSaysLater() {
        assertThat(handler.handle(CHAT_ID, "/start@other_bot")).isTrue();
        assertThat(telegram.lastSent().orElseThrow().text()).contains("позже");
    }

    @Test
    void unknownCommandSaysLater() {
        assertThat(handler.handle(CHAT_ID, "/unknown")).isTrue();
        assertThat(telegram.lastSent().orElseThrow().text()).contains("позже");
    }

    @Test
    void statsCommandSaysLater() {
        assertThat(handler.handle(CHAT_ID, "/stats")).isTrue();
        assertThat(telegram.lastSent().orElseThrow().text()).contains("позже");
    }

    @Test
    void plainTextIsNotACommand() {
        assertThat(handler.handle(CHAT_ID, "просто текст")).isFalse();
        assertThat(telegram.sent()).isEmpty();
    }
}
