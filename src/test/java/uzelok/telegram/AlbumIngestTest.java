package uzelok.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uzelok.config.UzelokProperties;
import uzelok.domain.model.ChatAccess;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.ProcessingJob;
import uzelok.support.FixedKnowledgeBaseRepository;
import uzelok.support.InMemoryAccessRepository;
import uzelok.support.InMemoryAttachmentRepository;
import uzelok.support.InMemoryJobRepository;
import uzelok.support.InMemorySourceItemRepository;
import uzelok.support.InMemoryTelegramGateway;
import uzelok.support.MutableClock;
import uzelok.support.RecordingAuditRepository;
import uzelok.support.TelegramUpdates;
import uzelok.support.TestImages;

class AlbumIngestTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID APP_USER_ID = UUID.randomUUID();
    private static final UUID KNOWLEDGE_BASE_ID = UUID.randomUUID();
    private static final long CHAT_ID = 100L;
    private static final long FROM_ID = 200L;
    private static final String MEDIA_GROUP = "album-1";

    @TempDir
    Path artifacts;

    @Test
    void albumWithSameMediaGroupCreatesOneItemAndThreeAttachments() {
        InMemorySourceItemRepository source = new InMemorySourceItemRepository();
        InMemoryAttachmentRepository attachments = new InMemoryAttachmentRepository();
        InMemoryJobRepository jobs = new InMemoryJobRepository(source);
        RecordingAuditRepository audit = new RecordingAuditRepository();
        InMemoryTelegramGateway telegram = new InMemoryTelegramGateway();
        InMemoryAccessRepository access = new InMemoryAccessRepository()
                .add(CHAT_ID, new ChatAccess(TENANT_ID, APP_USER_ID, FROM_ID, true));
        UzelokProperties.Telegram telegramProps = new UzelokProperties.Telegram(
                "token", "test_bot", 30, 2000, 8000, 15_728_640L, 1_048_576L, 30_000_000L);
        UzelokProperties.Limits limits = new UzelokProperties.Limits(20, 5, 72);
        MutableClock clock = new MutableClock(Instant.parse("2026-01-15T10:00:00Z"));

        byte[] jpeg = TestImages.jpeg(10, 10);
        for (int index = 0; index < 3; index++) {
            telegram.putFile("photo-" + index, jpeg);
        }

        FixedKnowledgeBaseRepository bases = new FixedKnowledgeBaseRepository(new KnowledgeBase(
                KNOWLEDGE_BASE_ID,
                KnowledgeBaseKind.EXTERNAL_OBSIDIAN,
                "/tmp/uzelok-album-vault",
                "40_Knowledge/_meta/taxonomy.yaml",
                "40_Knowledge/_Inbox"));
        Messages messages = new Messages();
        CommandHandler commands = new CommandHandler(telegram, messages, telegramProps);
        AttachmentReceiver receiver = new AttachmentReceiver(
                telegramProps,
                new UzelokProperties.Storage(artifacts.toString()),
                attachments,
                telegram,
                messages);
        MessageHandler handler = new MessageHandler(access, bases, source, attachments, jobs, audit, telegram,
                commands, receiver, clock, limits, telegramProps, messages);

        Instant previousNextRun = null;
        for (int index = 0; index < 3; index++) {
            clock.setInstant(Instant.parse("2026-01-15T10:00:00Z").plusSeconds(index));
            handler.handle(TelegramUpdates.photo(
                    100 + index, CHAT_ID, 500 + index, FROM_ID, MEDIA_GROUP, "photo-" + index, jpeg.length));

            ProcessingJob job = jobs.all().get(0);
            if (previousNextRun != null) {
                assertThat(job.nextRunAt()).isAfter(previousNextRun);
            }
            previousNextRun = job.nextRunAt();
        }

        assertThat(source.size()).isEqualTo(1);
        UUID sourceItemId = source.all().get(0).id();
        assertThat(attachments.countBySourceItem(sourceItemId)).isEqualTo(3);
        assertThat(attachments.listBySourceItem(sourceItemId))
                .extracting(attachment -> attachment.position())
                .containsExactly(0, 1, 2);
        assertThat(jobs.size()).isEqualTo(1);
        assertThat(previousNextRun).isEqualTo(
                Instant.parse("2026-01-15T10:00:02Z").plusMillis(2000));
        assertThat(audit.actions()).contains("album_appended");
        assertThat(audit.actions()).doesNotContain("duplicate_delivery");
    }

    @Test
    void albumMembersBypassReachedHourlyQuotaWhileNewMessageIsRejected() {
        InMemorySourceItemRepository source = new InMemorySourceItemRepository();
        InMemoryAttachmentRepository attachments = new InMemoryAttachmentRepository();
        InMemoryJobRepository jobs = new InMemoryJobRepository(source);
        RecordingAuditRepository audit = new RecordingAuditRepository();
        InMemoryTelegramGateway telegram = new InMemoryTelegramGateway();
        InMemoryAccessRepository access = new InMemoryAccessRepository()
                .add(CHAT_ID, new ChatAccess(TENANT_ID, APP_USER_ID, FROM_ID, true));
        UzelokProperties.Telegram telegramProps = new UzelokProperties.Telegram(
                "token", "test_bot", 30, 2000, 8000, 15_728_640L, 1_048_576L, 30_000_000L);
        UzelokProperties.Limits limits = new UzelokProperties.Limits(20, 1, 72);
        MutableClock clock = new MutableClock(Instant.parse("2026-01-15T10:00:00Z"));

        byte[] jpeg = TestImages.jpeg(10, 10);
        for (int index = 0; index < 3; index++) {
            telegram.putFile("photo-" + index, jpeg);
        }

        FixedKnowledgeBaseRepository bases = new FixedKnowledgeBaseRepository(new KnowledgeBase(
                KNOWLEDGE_BASE_ID,
                KnowledgeBaseKind.EXTERNAL_OBSIDIAN,
                "/tmp/uzelok-album-vault",
                "40_Knowledge/_meta/taxonomy.yaml",
                "40_Knowledge/_Inbox"));
        Messages messages = new Messages();
        CommandHandler commands = new CommandHandler(telegram, messages, telegramProps);
        AttachmentReceiver receiver = new AttachmentReceiver(
                telegramProps,
                new UzelokProperties.Storage(artifacts.toString()),
                attachments,
                telegram,
                messages);
        MessageHandler handler = new MessageHandler(access, bases, source, attachments, jobs, audit, telegram,
                commands, receiver, clock, limits, telegramProps, messages);

        for (int index = 0; index < 3; index++) {
            clock.setInstant(Instant.parse("2026-01-15T10:00:00Z").plusSeconds(index));
            handler.handle(TelegramUpdates.photo(
                    100 + index, CHAT_ID, 500 + index, FROM_ID, MEDIA_GROUP, "photo-" + index, jpeg.length));
        }

        assertThat(source.size()).isEqualTo(1);
        assertThat(attachments.countBySourceItem(source.all().get(0).id())).isEqualTo(3);
        assertThat(jobs.size()).isEqualTo(1);
        assertThat(audit.actions()).doesNotContain("quota_exceeded");

        handler.handle(TelegramUpdates.text(200, CHAT_ID, 600, FROM_ID, "новое сообщение"));

        assertThat(jobs.size()).isEqualTo(1);
        assertThat(source.size()).isEqualTo(1);
        assertThat(audit.actions()).contains("quota_exceeded");
    }
}
