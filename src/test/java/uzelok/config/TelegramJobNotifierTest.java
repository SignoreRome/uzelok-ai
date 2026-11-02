package uzelok.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uzelok.domain.model.PreviewView;
import uzelok.support.InMemorySourceItemRepository;
import uzelok.support.InMemoryTelegramGateway;

class TelegramJobNotifierTest {

    @Test
    void previewTruncatesBodyToTelegramLimit() {
        InMemoryTelegramGateway telegram = new InMemoryTelegramGateway();
        TelegramJobNotifier notifier = new TelegramJobNotifier(
                telegram, new InMemorySourceItemRepository());
        PreviewView view = new PreviewView(
                UUID.randomUUID(),
                42L,
                null,
                "40_Knowledge/Algorithms/Graphs/Test.md",
                "Graphs",
                "Короткое описание",
                "x".repeat(5000),
                List.of());

        notifier.preview(view);

        String text = telegram.lastSent().orElseThrow().text();
        assertThat(text.length()).isLessThanOrEqualTo(3800);
        assertThat(text).endsWith("\n…");
    }
}
