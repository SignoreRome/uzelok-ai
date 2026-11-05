package uzelok.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import uzelok.config.UzelokProperties;
import uzelok.domain.model.ActionOutcome;
import uzelok.domain.model.ChatAccess;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.MaterialHash;
import uzelok.domain.model.NoteRecord;
import uzelok.domain.model.NoteType;
import uzelok.domain.model.PendingAction;
import uzelok.domain.model.PendingActionType;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.SourceItem;
import uzelok.domain.port.SourceItemCreation;
import uzelok.domain.port.SourceItemRepository;
import uzelok.pipeline.ActionService;
import uzelok.pipeline.JobService;
import uzelok.pipeline.JobStateMachine;
import uzelok.pipeline.StatsService;
import uzelok.support.FixedKnowledgeBaseRepository;
import uzelok.support.InMemoryAccessRepository;
import uzelok.support.InMemoryAttachmentRepository;
import uzelok.support.InMemoryJobRepository;
import uzelok.support.InMemoryLlmCallRepository;
import uzelok.support.InMemoryNoteRepository;
import uzelok.support.InMemoryPendingActionRepository;
import uzelok.support.InMemorySourceItemRepository;
import uzelok.support.InMemoryTelegramGateway;
import uzelok.support.InMemoryTelegramUpdateRepository;
import uzelok.support.MutableClock;
import uzelok.support.RecordingAuditRepository;
import uzelok.support.TelegramUpdates;
import uzelok.support.TestImages;
import uzelok.support.StubOnboardingHandler;
import uzelok.support.StubVaultExporter;

class MessageHandlerTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID APP_USER_ID = UUID.randomUUID();
    private static final UUID KNOWLEDGE_BASE_ID = UUID.randomUUID();
    private static final long CHAT_ID = 100L;
    private static final long FROM_ID = 200L;

    @TempDir
    Path artifacts;

    private InMemorySourceItemRepository source;
    private SourceItemRepository sourceRepository;
    private InMemoryAttachmentRepository attachments;
    private InMemoryJobRepository jobs;
    private RecordingAuditRepository audit;
    private InMemoryTelegramGateway telegram;
    private InMemoryAccessRepository access;
    private InMemorySourceItemRepository sharedSource;
    private InMemoryNoteRepository notes;
    private InMemoryPendingActionRepository actions;
    private UzelokProperties.Telegram telegramProps;
    private UzelokProperties.Limits limits;
    private Clock clock;
    private MessageHandler handler;

    @BeforeEach
    void setUp() {
        source = new InMemorySourceItemRepository();
        sourceRepository = source;
        sharedSource = source;
        attachments = new InMemoryAttachmentRepository();
        jobs = new InMemoryJobRepository(sharedSource);
        audit = new RecordingAuditRepository();
        telegram = new InMemoryTelegramGateway();
        notes = new InMemoryNoteRepository();
        actions = new InMemoryPendingActionRepository();
        access = new InMemoryAccessRepository()
                .add(CHAT_ID, new ChatAccess(TENANT_ID, APP_USER_ID, FROM_ID, true));
        telegramProps = new UzelokProperties.Telegram(
                "token", "test_bot", "", 30, 2000, 8000, 15_728_640L, 1_048_576L, 30_000_000L);
        limits = new UzelokProperties.Limits(20, 5, 72);
        clock = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
        handler = handlerWith(new FixedKnowledgeBaseRepository(knowledgeBase()));
    }

    @Test
    void allowedTextCreatesSourceItemAndJobAndSendsStatus() {
        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "конспект"));

        assertThat(source.size()).isEqualTo(1);
        assertThat(jobs.size()).isEqualTo(1);
        ProcessingJob job = jobs.all().get(0);
        assertThat(job.status()).isEqualTo(JobStatus.RECEIVED);
        assertThat(job.statusMessageId()).isNotNull();
        assertThat(telegram.sent()).hasSize(1);
        assertThat(telegram.lastSent().orElseThrow().text()).contains("Принял");
        assertThat(audit.actions()).contains("job_created");
    }

    @Test
    void notAllowedChatGetsDeniedAndNothingIsCreated() {
        access.add(999L, new ChatAccess(TENANT_ID, APP_USER_ID, FROM_ID, false));

        handler.handle(TelegramUpdates.text(1, 999L, 500, FROM_ID, "текст"));

        assertThat(telegram.lastSent().orElseThrow().text()).isEqualTo("Доступ не выдан.");
        assertThat(source.size()).isZero();
        assertThat(jobs.size()).isZero();
        assertThat(audit.actions()).contains("auth_denied");
    }

    @Test
    void unknownChatIsSilentlyAudited() {
        handler.handle(TelegramUpdates.text(1, 777L, 500, FROM_ID, "текст"));

        assertThat(telegram.sent()).isEmpty();
        assertThat(source.size()).isZero();
        assertThat(audit.actions()).contains("auth_denied");
    }

    @Test
    void foreignUserGetsNoReplyButAudit() {
        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, 999L, "текст"));

        assertThat(telegram.sent()).isEmpty();
        assertThat(source.size()).isZero();
        assertThat(audit.actions()).contains("auth_denied");
        assertThat(audit.records().get(0).detail()).containsEntry("reason", "user_mismatch");
    }

    @Test
    void tooLongTextIsRejectedWithoutJob() {
        String text = "а".repeat(8001);

        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, text));

        assertThat(source.size()).isZero();
        assertThat(jobs.size()).isZero();
        assertThat(telegram.lastSent().orElseThrow().text()).contains("слишком длинный");
    }

    @Test
    void missingKnowledgeBaseStopsIngest() {
        handler = handlerWith(new FixedKnowledgeBaseRepository(null));

        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "текст"));

        assertThat(source.size()).isZero();
        assertThat(jobs.size()).isZero();
        assertThat(telegram.lastSent().orElseThrow().text())
                .isEqualTo(new Messages().text("base.prompt"));
        assertThat(audit.actions()).contains("onboarding_required");
    }

    @Test
    void repeatedMessageDoesNotCreateSecondJob() {
        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "текст"));
        handler.handle(TelegramUpdates.text(2, CHAT_ID, 500, FROM_ID, "текст"));

        assertThat(source.size()).isEqualTo(1);
        assertThat(jobs.size()).isEqualTo(1);
        assertThat(telegram.sent()).hasSize(1);
        assertThat(audit.actions()).contains("duplicate_delivery");
    }

    @Test
    void downloadDeliveryFailureIsAuditedAndReportedToUser() {
        telegram.failDownload(new TelegramDeliveryException("сбой сети"));

        handler.handle(TelegramUpdates.photo(1, CHAT_ID, 500, FROM_ID, null, "photo", 100));

        assertThat(audit.actions()).contains("ingest_failed");
        assertThat(audit.records()).anySatisfy(record -> {
            if ("ingest_failed".equals(record.action())) {
                assertThat(record.detail()).containsEntry("reason", "TelegramDeliveryException");
            }
        });
        assertThat(telegram.lastSent().orElseThrow().text())
                .isEqualTo(new Messages().text("attachment.failed"));
        assertThat(jobs.size()).isZero();
    }

    @Test
    void repeatedTextMaterialCreatesSingleSourceItemAndJob() {
        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "материал"));
        ProcessingJob first = jobs.all().get(0);
        notes.upsert(new NoteRecord(UUID.randomUUID(), TENANT_ID, KNOWLEDGE_BASE_ID,
                first.id(), NoteType.source_note, "Заголовок", null, null,
                "Zametka.md", "40_Knowledge/Zametka.md", "hash"));

        handler.handle(TelegramUpdates.text(2, CHAT_ID, 501, FROM_ID, "  материал  "));

        assertThat(source.size()).isEqualTo(1);
        assertThat(jobs.size()).isEqualTo(1);
        assertThat(audit.actions()).contains("duplicate_material");
        assertThat(audit.records()).anySatisfy(record -> {
            if ("duplicate_material".equals(record.action())) {
                assertThat(record.detail()).containsEntry("existing_note", "Zametka.md");
                assertThat(record.detail()).containsKey("source_item_id");
            }
        });
        assertThat(telegram.lastSent().orElseThrow().text()).contains("Zametka.md");
        assertThat(telegram.lastSent().orElseThrow().keyboard().rows()).isNotEmpty();
    }

    @Test
    void repeatedPhotoMaterialCreatesSingleSourceItemAndJob() {
        byte[] jpeg = TestImages.jpeg(10, 10);
        telegram.putFile("photo-a", jpeg);
        telegram.putFile("photo-b", jpeg);

        handler.handle(TelegramUpdates.photo(1, CHAT_ID, 500, FROM_ID, null, "photo-a", jpeg.length));
        handler.handle(TelegramUpdates.photo(2, CHAT_ID, 501, FROM_ID, null, "photo-b", jpeg.length));

        assertThat(source.size()).isEqualTo(1);
        assertThat(jobs.size()).isEqualTo(1);
        UUID sourceItemId = source.all().get(0).id();
        assertThat(attachments.countBySourceItem(sourceItemId)).isEqualTo(1);
        assertThat(audit.actions()).contains("duplicate_material");
    }

    @Test
    void materialHashDiffersForDifferentTexts() {
        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "первый"));
        handler.handle(TelegramUpdates.text(2, CHAT_ID, 501, FROM_ID, "второй"));

        assertThat(source.size()).isEqualTo(2);
        String first = source.materialHashOf(source.findByChatAndMessage(CHAT_ID, 500)
                .orElseThrow().id()).orElseThrow();
        String second = source.materialHashOf(source.findByChatAndMessage(CHAT_ID, 501)
                .orElseThrow().id()).orElseThrow();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void forceRepeatBypassesDedupAndCreatesJob() {
        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "материал"));
        handler.handle(TelegramUpdates.text(2, CHAT_ID, 501, FROM_ID, "материал"));
        assertThat(jobs.size()).isEqualTo(1);

        handler.handle(TelegramUpdates.text(3, CHAT_ID, 502, FROM_ID, "материал"), true);

        assertThat(source.size()).isEqualTo(2);
        assertThat(jobs.size()).isEqualTo(2);
    }

    @Test
    void photoCaptionIsStoredAndIncludedInMaterialHash() {
        byte[] jpeg = TestImages.jpeg(10, 10);
        telegram.putFile("photo-caption", jpeg);

        handler.handle(TelegramUpdates.photo(1, CHAT_ID, 500, FROM_ID, null, "photo-caption",
                jpeg.length, "Подпись к фото"));

        SourceItem item = source.findByChatAndMessage(CHAT_ID, 500).orElseThrow();
        assertThat(item.textBody()).isEqualTo("Подпись к фото");
        String sha = attachments.listBySourceItem(item.id()).get(0).sha256();
        assertThat(item.materialHash())
                .isEqualTo(MaterialHash.ofHashes(List.of(sha), "Подпись к фото"));
    }

    @Test
    void documentCaptionIsStoredTogetherWithDocumentText() {
        byte[] content = "тело документа".getBytes(StandardCharsets.UTF_8);
        telegram.putFile("doc-caption", content);

        handler.handle(TelegramUpdates.document(1, CHAT_ID, 500, FROM_ID, "doc-caption",
                "note.txt", "text/plain", content.length, "Подпись к документу"));

        SourceItem item = source.findByChatAndMessage(CHAT_ID, 500).orElseThrow();
        assertThat(item.textBody()).isEqualTo("Подпись к документу\nтело документа");
        String sha = attachments.listBySourceItem(item.id()).get(0).sha256();
        assertThat(item.materialHash())
                .isEqualTo(MaterialHash.ofHashes(List.of(sha), item.textBody()));
    }

    @Test
    void albumMemberMatchingSavedPhotoIsAppendedWithoutDedup() {
        byte[] saved = TestImages.jpeg(11, 11);
        byte[] albumFirst = TestImages.jpeg(12, 12);
        telegram.putFile("saved", saved);
        telegram.putFile("album-first", albumFirst);
        telegram.putFile("album-member", saved);

        handler.handle(TelegramUpdates.photo(1, CHAT_ID, 500, FROM_ID, null, "saved", saved.length));
        handler.handle(TelegramUpdates.photo(2, CHAT_ID, 501, FROM_ID, "album", "album-first",
                albumFirst.length));
        handler.handle(TelegramUpdates.photo(3, CHAT_ID, 502, FROM_ID, "album", "album-member",
                saved.length));

        assertThat(source.size()).isEqualTo(2);
        SourceItem album = source.findByChatAndMediaGroup(CHAT_ID, "album").orElseThrow();
        assertThat(attachments.countBySourceItem(album.id())).isEqualTo(2);
        assertThat(jobs.size()).isEqualTo(2);
        assertThat(audit.actions()).doesNotContain("duplicate_material");
    }

    @Test
    void albumFirstMemberMatchingSavedPhotoStartsAlbum() {
        byte[] saved = TestImages.jpeg(13, 13);
        byte[] second = TestImages.jpeg(14, 14);
        telegram.putFile("saved", saved);
        telegram.putFile("album-second", second);

        handler.handle(TelegramUpdates.photo(1, CHAT_ID, 500, FROM_ID, null, "saved", saved.length));
        handler.handle(TelegramUpdates.photo(2, CHAT_ID, 501, FROM_ID, "album", "saved", saved.length));
        handler.handle(TelegramUpdates.photo(3, CHAT_ID, 502, FROM_ID, "album", "album-second",
                second.length));

        assertThat(source.size()).isEqualTo(2);
        SourceItem album = source.findByChatAndMediaGroup(CHAT_ID, "album").orElseThrow();
        assertThat(attachments.countBySourceItem(album.id())).isEqualTo(2);
        assertThat(jobs.size()).isEqualTo(2);
        assertThat(audit.actions()).doesNotContain("duplicate_material");
    }

    @Test
    void duplicateOfUnfinishedJobHasNoRegenerateButton() {
        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "материал"));
        handler.handle(TelegramUpdates.text(2, CHAT_ID, 501, FROM_ID, "материал"));

        assertThat(jobs.size()).isEqualTo(1);
        assertThat(audit.actions()).contains("duplicate_material");
        assertThat(telegram.lastSent().orElseThrow().keyboard().rows()).isEmpty();
    }

    @Test
    void materialHashConflictIsReportedAsDuplicateWithoutNewJob() {
        InMemorySourceItemRepository original = source;
        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "материал"));
        ProcessingJob first = jobs.all().get(0);
        SourceItem firstItem = original.findByChatAndMessage(CHAT_ID, 500).orElseThrow();
        notes.upsert(new NoteRecord(UUID.randomUUID(), TENANT_ID, KNOWLEDGE_BASE_ID,
                first.id(), NoteType.source_note, "Заголовок", null, null,
                "First.md", "40_Knowledge/First.md", "hash"));

        sourceRepository = new ConflictingSourceItemRepository(original, TENANT_ID,
                firstItem.materialHash(), firstItem.id());
        handler = handlerWith(new FixedKnowledgeBaseRepository(knowledgeBase()));

        handler.handle(TelegramUpdates.text(2, CHAT_ID, 501, FROM_ID, "материал"));

        assertThat(jobs.size()).isEqualTo(1);
        assertThat(notes.noteCount()).isEqualTo(1);
        assertThat(original.findByTenantAndMaterialHash(TENANT_ID, firstItem.materialHash())
                .orElseThrow().id()).isEqualTo(firstItem.id());
        assertThat(audit.actions()).contains("duplicate_material");
        assertThat(telegram.lastSent().orElseThrow().text()).contains("First.md");
    }

    @Test
    void duplicateRegenerateKeepsFirstNoteAsDuplicateTarget() {
        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "материал"));
        ProcessingJob first = jobs.all().get(0);
        notes.upsert(new NoteRecord(UUID.randomUUID(), TENANT_ID, KNOWLEDGE_BASE_ID,
                first.id(), NoteType.source_note, "Заголовок", null, null,
                "First.md", "40_Knowledge/First.md", "hash"));

        ActionService actionService = new ActionService(jobs,
                new JobService(jobs, new JobStateMachine()), 3, source, attachments, clock,
                limits.jobsPerDay(), limits.jobsPerHour(), audit, new Messages());
        ActionOutcome outcome = actionService.handle(new PendingAction("token", first.id(),
                TENANT_ID, PendingActionType.DUP_REGEN, CHAT_ID, null,
                clock.instant().plusSeconds(60), null));

        assertThat(outcome.handled()).isTrue();
        ProcessingJob copy = jobs.all().stream()
                .filter(candidate -> !candidate.id().equals(first.id()))
                .findFirst().orElseThrow();
        assertThat(source.findById(copy.sourceItemId()).orElseThrow().materialHash()).isNull();

        handler.handle(TelegramUpdates.text(2, CHAT_ID, 502, FROM_ID, "материал"));

        assertThat(source.size()).isEqualTo(2);
        assertThat(jobs.size()).isEqualTo(2);
        assertThat(notes.noteCount()).isEqualTo(1);
        assertThat(telegram.lastSent().orElseThrow().text()).contains("First.md");
        assertThat(audit.actions()).contains("duplicate_material");
    }

    @Test
    void dailyQuotaRejectsSecondMessageWithinSameDay() {
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-01-15T10:00:00Z"));
        handler = handlerWith(new FixedKnowledgeBaseRepository(knowledgeBase()),
                new UzelokProperties.Limits(1, 5, 72), mutableClock);

        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "первое"));
        mutableClock.setInstant(Instant.parse("2026-01-15T12:00:00Z"));
        handler.handle(TelegramUpdates.text(2, CHAT_ID, 501, FROM_ID, "второе"));

        assertThat(source.size()).isEqualTo(1);
        assertThat(jobs.size()).isEqualTo(1);
        assertThat(audit.actions()).contains("quota_exceeded");
        assertThat(audit.records()).anySatisfy(record -> {
            if ("quota_exceeded".equals(record.action())) {
                assertThat(record.detail()).containsEntry("reason", "jobs_per_day");
            }
        });
        assertThat(telegram.lastSent().orElseThrow().text())
                .isEqualTo(new Messages().text("quota.day"));
    }

    @Test
    void quotaRejectionLeavesNoSourceItemOrJobButRegistersUpdate() {
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-01-15T10:00:00Z"));
        handler = handlerWith(new FixedKnowledgeBaseRepository(knowledgeBase()),
                new UzelokProperties.Limits(1, 5, 72), mutableClock);
        InMemoryTelegramUpdateRepository updates = new InMemoryTelegramUpdateRepository();
        CallbackHandler callbacks = new CallbackHandler(access,
                new InMemoryPendingActionRepository(), action -> ActionOutcome.ignored(),
                new StubOnboardingHandler(), telegram, new Messages(), audit, mutableClock);
        UpdateRouter router = new UpdateRouter(updates, handler, callbacks, audit);

        router.consume(List.of(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "первое")));
        int sourceAfterFirst = source.size();
        int jobsAfterFirst = jobs.size();
        assertThat(sourceAfterFirst).isEqualTo(1);
        assertThat(jobsAfterFirst).isEqualTo(1);

        mutableClock.setInstant(Instant.parse("2026-01-15T12:00:00Z"));
        router.consume(List.of(TelegramUpdates.text(2, CHAT_ID, 501, FROM_ID, "второе")));

        assertThat(source.size()).isEqualTo(sourceAfterFirst);
        assertThat(jobs.size()).isEqualTo(jobsAfterFirst);
        assertThat(source.findByChatAndMessage(CHAT_ID, 501)).isEmpty();
        assertThat(audit.actions()).contains("quota_exceeded");
        assertThat(updates.size()).isEqualTo(2);
        assertThat(updates.typeOf(2)).isEqualTo("message");
    }

    private MessageHandler handlerWith(uzelok.domain.port.KnowledgeBaseRepository bases) {
        return handlerWith(bases, limits, clock);
    }

    private MessageHandler handlerWith(uzelok.domain.port.KnowledgeBaseRepository bases,
                                       UzelokProperties.Limits usedLimits, Clock usedClock) {
        Messages messages = new Messages();
        CommandHandler commands = new CommandHandler(telegram, messages, "test_bot", Map.of(),
                access, jobs, new StatsService(notes, jobs,
                        new InMemoryLlmCallRepository()), bases, new StubVaultExporter(), audit,
                usedClock);
        AttachmentReceiver receiver = new AttachmentReceiver(
                telegramProps,
                new UzelokProperties.Storage(artifacts.toString(),
                        "/var/lib/uzelok/vaults", "/var/lib/uzelok/exports"),
                attachments,
                telegram,
                messages);
        return new MessageHandler(access, bases, sourceRepository, attachments, jobs, audit, telegram,
                commands, receiver, usedClock, usedLimits, telegramProps, messages,
                new StubOnboardingHandler(), notes, actions);
    }


    private static KnowledgeBase knowledgeBase() {
        return new KnowledgeBase(
                KNOWLEDGE_BASE_ID,
                KnowledgeBaseKind.EXTERNAL_OBSIDIAN,
                artifactsPath(),
                "40_Knowledge/_meta/taxonomy.yaml",
                "40_Knowledge/_Inbox");
    }

    private static String artifactsPath() {
        return "/tmp/uzelok-test-vault";
    }

    private static final class ConflictingSourceItemRepository implements SourceItemRepository {

        private final InMemorySourceItemRepository delegate;
        private final UUID tenantId;
        private final String hiddenHash;
        private final UUID firstItemId;
        private final AtomicBoolean hidden = new AtomicBoolean();

        private ConflictingSourceItemRepository(InMemorySourceItemRepository delegate, UUID tenantId,
                                                String hiddenHash, UUID firstItemId) {
            this.delegate = delegate;
            this.tenantId = tenantId;
            this.hiddenHash = hiddenHash;
            this.firstItemId = firstItemId;
        }

        @Override
        public SourceItemCreation ensure(SourceItem candidate) {
            return delegate.ensure(candidate);
        }

        @Override
        public Optional<SourceItem> findById(UUID id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<SourceItem> findByChatAndMessage(long chatId, long telegramMessageId) {
            return delegate.findByChatAndMessage(chatId, telegramMessageId);
        }

        @Override
        public Optional<SourceItem> findByChatAndMediaGroup(long chatId, String mediaGroupId) {
            return delegate.findByChatAndMediaGroup(chatId, mediaGroupId);
        }

        @Override
        public Optional<SourceItem> findByTenantAndMaterialHash(UUID tenant, String materialHash) {
            if (tenant.equals(tenantId) && materialHash.equals(hiddenHash)
                    && hidden.compareAndSet(false, true)) {
                return Optional.empty();
            }
            return delegate.findByTenantAndMaterialHash(tenant, materialHash);
        }

        @Override
        public boolean setMaterialHash(UUID id, String materialHash) {
            if (!id.equals(firstItemId)
                    && delegate.findByTenantAndMaterialHash(tenantId, materialHash).isPresent()) {
                throw new DuplicateKeyException("duplicate material_hash");
            }
            return delegate.setMaterialHash(id, materialHash);
        }

        @Override
        public boolean setTextBody(UUID id, String text) {
            return delegate.setTextBody(id, text);
        }
    }
}
