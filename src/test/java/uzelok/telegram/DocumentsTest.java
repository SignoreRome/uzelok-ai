package uzelok.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import uzelok.support.InMemoryTelegramGateway;

class DocumentsTest {

    private static final long CHAT_ID = 100L;

    @Test
    void longBodyIsSentAsMarkdownDocument() {
        InMemoryTelegramGateway telegram = new InMemoryTelegramGateway();
        Documents documents = new Documents(telegram);
        String markdown = "я".repeat(3501);

        documents.sendDraft(CHAT_ID, "40_Knowledge/Algorithms/Graphs/Test.md", markdown,
                "Путь: 40_Knowledge/Algorithms/Graphs/Test.md");

        InMemoryTelegramGateway.SentDocument sent = telegram.documents().getFirst();
        assertThat(sent.chatId()).isEqualTo(CHAT_ID);
        assertThat(sent.fileName()).isEqualTo("Test.md");
        assertThat(sent.caption()).contains("Путь");
        assertThat(new String(sent.content(), StandardCharsets.UTF_8)).isEqualTo(markdown);
    }

    @Test
    void shortBodyDoesNotSendDocument() {
        InMemoryTelegramGateway telegram = new InMemoryTelegramGateway();
        Documents documents = new Documents(telegram);

        documents.sendDraft(CHAT_ID, "Test.md", "x".repeat(3500), "caption");

        assertThat(telegram.documents()).isEmpty();
    }
}
