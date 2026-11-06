package uzelok.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.PreviewView;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.SourceItem;
import uzelok.domain.model.SourceKind;
import uzelok.support.InMemoryJobRepository;
import uzelok.support.InMemoryPendingActionRepository;
import uzelok.support.InMemorySourceItemRepository;
import uzelok.support.InMemoryTelegramGateway;
import uzelok.support.MutableClock;

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
                "x".repeat(5000),
                List.of(), 0);

        notifier.preview(view);

        String text = telegram.lastSent().orElseThrow().text();
        assertThat(text.length()).isLessThanOrEqualTo(3800);
        assertThat(text).endsWith("\n…");
    }

    @Test
    void regeneratedPreviewMentionsVersion() {
        InMemoryTelegramGateway telegram = new InMemoryTelegramGateway();
        TelegramJobNotifier notifier = new TelegramJobNotifier(
                telegram, new InMemorySourceItemRepository());
        PreviewView view = new PreviewView(
                UUID.randomUUID(), 42L, null,
                "40_Knowledge/Algorithms/Graphs/Test.md", "Graphs", "Короткое описание",
                "коротко", "коротко", List.of(), 2);

        notifier.preview(view);

        String text = telegram.lastSent().orElseThrow().text();
        assertThat(text).startsWith("Черновик заметки v3 (перегенерирован).");
    }

    @Test
    void previewAttachesButtonsWithOneTimeTokens() {
        InMemoryTelegramGateway telegram = new InMemoryTelegramGateway();
        InMemorySourceItemRepository source = new InMemorySourceItemRepository();
        InMemoryJobRepository jobs = new InMemoryJobRepository(source);
        InMemoryPendingActionRepository actions = new InMemoryPendingActionRepository();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-01T12:00:00Z"));
        UUID tenantId = UUID.randomUUID();
        SourceItem item = source.ensure(new SourceItem(UUID.randomUUID(), tenantId,
                UUID.randomUUID(), 42L, 100L, SourceKind.TEXT, null, "текст", clock.instant()))
                .item();
        ProcessingJob job = jobs.createIfAbsent(new ProcessingJob(UUID.randomUUID(), tenantId,
                item.id(), item.knowledgeBaseId(), JobStatus.AWAITING_CONFIRM, null, 0,
                clock.instant(), null, 55L, null, null, null, clock.instant(), clock.instant(),
                0, false)).job();
        TelegramJobNotifier notifier = new TelegramJobNotifier(telegram, source, jobs, actions, clock);
        PreviewView view = new PreviewView(job.id(), 42L, 55L,
                "40_Knowledge/Algorithms/Graphs/Test.md", "Graphs", "summary", "коротко",
                "коротко", List.of(), 0);

        notifier.preview(view);

        var keyboard = telegram.edited().getFirst().keyboard();
        assertThat(keyboard.rows()).hasSize(2);
        List<String> callbackData = keyboard.rows().stream().flatMap(List::stream)
                .map(button -> button.callbackData()).toList();
        assertThat(callbackData).hasSize(4);
        for (String data : callbackData) {
            assertThat(data).startsWith("act:");
            assertThat(actions.findByToken(data.substring(4))).isPresent();
        }
    }

    @Test
    void newPreviewInvalidatesPreviousTokens() {
        InMemoryTelegramGateway telegram = new InMemoryTelegramGateway();
        InMemorySourceItemRepository source = new InMemorySourceItemRepository();
        InMemoryJobRepository jobs = new InMemoryJobRepository(source);
        InMemoryPendingActionRepository actions = new InMemoryPendingActionRepository();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-01T12:00:00Z"));
        UUID tenantId = UUID.randomUUID();
        SourceItem item = source.ensure(new SourceItem(UUID.randomUUID(), tenantId,
                UUID.randomUUID(), 42L, 100L, SourceKind.TEXT, null, "текст", clock.instant()))
                .item();
        ProcessingJob job = jobs.createIfAbsent(new ProcessingJob(UUID.randomUUID(), tenantId,
                item.id(), item.knowledgeBaseId(), JobStatus.AWAITING_CONFIRM, null, 0,
                clock.instant(), null, 55L, null, null, null, clock.instant(), clock.instant(),
                0, false)).job();
        TelegramJobNotifier notifier = new TelegramJobNotifier(telegram, source, jobs, actions, clock);
        PreviewView view = new PreviewView(job.id(), 42L, 55L,
                "40_Knowledge/Algorithms/Graphs/Test.md", "Graphs", "summary", "коротко",
                "коротко", List.of(), 0);

        notifier.preview(view);
        String oldToken = telegram.edited().getFirst().keyboard().rows().getFirst().getFirst()
                .callbackData().substring(4);
        notifier.preview(view);

        assertThat(actions.consume(oldToken, 42L, clock.instant())).isEmpty();
    }
}
