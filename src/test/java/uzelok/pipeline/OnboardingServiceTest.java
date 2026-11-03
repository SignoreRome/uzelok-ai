package uzelok.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.OnboardingResult;
import uzelok.knowledge.ContractValidator;
import uzelok.knowledge.PlainVault;
import uzelok.support.InMemoryKnowledgeBaseRepository;
import uzelok.support.InMemoryOnboardingSessionRepository;
import uzelok.support.RecordingAuditRepository;

class OnboardingServiceTest {

    private static final long CHAT_ID = 100L;
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @TempDir
    Path root;

    private InMemoryKnowledgeBaseRepository bases;
    private InMemoryOnboardingSessionRepository sessions;
    private RecordingAuditRepository audit;
    private Path vaultsRoot;
    private OnboardingService service;

    @BeforeEach
    void setUp() {
        bases = new InMemoryKnowledgeBaseRepository();
        sessions = new InMemoryOnboardingSessionRepository();
        audit = new RecordingAuditRepository();
        vaultsRoot = root.resolve("vaults");
        service = new OnboardingService(bases, sessions, new PlainVault(vaultsRoot),
                new ContractValidator(), audit,
                Clock.fixed(NOW, ZoneOffset.UTC), vaultsRoot);
    }

    @Test
    void expectPathStoresSessionWithFifteenMinuteTtl() {
        OnboardingResult result = service.expectPath(CHAT_ID, TENANT_ID);

        assertThat(result.messageKey()).isEqualTo("base.ask_path");
        assertThat(result.args()).isEmpty();
        assertThat(service.awaitingPath(CHAT_ID)).isTrue();
        assertThat(sessions.expiresAt(CHAT_ID)).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void expectPathReportsAlreadyConnectedBase() {
        bases.save(TENANT_ID, new KnowledgeBase(UUID.randomUUID(),
                KnowledgeBaseKind.EXTERNAL_OBSIDIAN, "/tmp/existing",
                "40_Knowledge/_meta/taxonomy.yaml", "40_Knowledge/_Inbox"));

        OnboardingResult result = service.expectPath(CHAT_ID, TENANT_ID);

        assertThat(result.messageKey()).isEqualTo("base.already");
        assertThat(result.args()).containsExactly("/tmp/existing");
        assertThat(sessions.size()).isZero();
    }

    @Test
    void createInternalCreatesPlainVaultSavesBaseWithoutContractPathsAndAudits() {
        OnboardingResult result = service.createInternal(CHAT_ID, TENANT_ID);

        assertThat(result.messageKey()).isEqualTo("base.created");
        String savedRoot = result.args().getFirst();
        assertThat(Path.of(savedRoot).resolve("notes")).isDirectory();
        assertThat(Path.of(savedRoot).resolve("README.md")).isRegularFile();
        assertThat(Path.of(savedRoot).resolve("40_Knowledge")).doesNotExist();
        KnowledgeBase saved = bases.findByTenantId(TENANT_ID).orElseThrow();
        assertThat(saved.kind()).isEqualTo(KnowledgeBaseKind.INTERNAL_DIR);
        assertThat(saved.rootPath()).isEqualTo(savedRoot);
        assertThat(saved.taxonomyRelPath()).isNull();
        assertThat(saved.inboxRelPath()).isNull();
        assertThat(audit.actions()).contains("vault_created");
        assertThat(sessions.size()).isZero();

        OnboardingResult repeated = service.createInternal(CHAT_ID, TENANT_ID);

        assertThat(repeated.messageKey()).isEqualTo("base.already");
        assertThat(repeated.args()).containsExactly(saved.rootPath());
        assertThat(bases.size()).isEqualTo(1);
    }

    @Test
    void repeatedCreateInternalDoesNotCreateDirectoryOrSave() {
        String existingRoot = root.resolve("already-connected").toString();
        bases.save(TENANT_ID, new KnowledgeBase(UUID.randomUUID(),
                KnowledgeBaseKind.INTERNAL_DIR, existingRoot, null, null));
        int savesBefore = bases.saveCalls();

        OnboardingResult result = service.createInternal(CHAT_ID, TENANT_ID);

        assertThat(result.messageKey()).isEqualTo("base.already");
        assertThat(result.args()).containsExactly(existingRoot);
        assertThat(vaultsRoot.resolve(TENANT_ID.toString())).doesNotExist();
        assertThat(bases.saveCalls()).isEqualTo(savesBefore);
    }

    @Test
    void connectRejectsAnotherTenantVaultDirectory() {
        UUID otherTenant = UUID.randomUUID();
        Path foreign = new PlainVault(vaultsRoot)
                .create(vaultsRoot.resolve(otherTenant.toString()));
        service.expectPath(CHAT_ID, TENANT_ID);

        OnboardingResult result = service.connect(CHAT_ID, TENANT_ID, foreign.toString());

        assertThat(result.messageKey()).isEqualTo("base.foreign_vault");
        assertThat(bases.size()).isZero();
        assertThat(audit.records()).anySatisfy(record -> {
            if ("vault_onboarding_failed".equals(record.action())) {
                assertThat(record.detail()).containsEntry("reason", "foreign_vault");
            }
        });
    }

    @Test
    void connectRejectsRootPathRegisteredByAnotherTenant() throws IOException {
        UUID otherTenant = UUID.randomUUID();
        Path external = copyVault(root.resolve("foreign-external"));
        bases.save(otherTenant, new KnowledgeBase(UUID.randomUUID(),
                KnowledgeBaseKind.EXTERNAL_OBSIDIAN, external.toString(),
                "40_Knowledge/_meta/taxonomy.yaml", "40_Knowledge/_Inbox"));
        service.expectPath(CHAT_ID, TENANT_ID);

        OnboardingResult result = service.connect(CHAT_ID, TENANT_ID, external.toString());

        assertThat(result.messageKey()).isEqualTo("base.foreign_vault");
        assertThat(bases.size()).isEqualTo(1);
        assertThat(bases.findByTenantId(TENANT_ID)).isEmpty();
    }

    @Test
    void connectWithoutActiveSessionExpiresExpectation() throws IOException {
        Path external = copyVault(root.resolve("no-session"));

        OnboardingResult result = service.connect(CHAT_ID, TENANT_ID, external.toString());

        assertThat(result.messageKey()).isEqualTo("base.expectation_expired");
        assertThat(bases.size()).isZero();
    }

    @Test
    void connectWithExpiredSessionDoesNotSaveBase() throws IOException {
        Path external = copyVault(root.resolve("expired-session"));
        sessions.awaitPath(TENANT_ID, CHAT_ID, NOW.minusSeconds(1));

        OnboardingResult result = service.connect(CHAT_ID, TENANT_ID, external.toString());

        assertThat(result.messageKey()).isEqualTo("base.expectation_expired");
        assertThat(bases.size()).isZero();
    }

    @Test
    void connectRegistersExternalBaseEvenWithoutGitDirectory() throws IOException {
        Path external = copyVault(root.resolve("external"));
        assertThat(external.resolve(".git")).doesNotExist();
        service.expectPath(CHAT_ID, TENANT_ID);

        OnboardingResult result = service.connect(CHAT_ID, TENANT_ID, external.toString());

        assertThat(result.messageKey()).isEqualTo("base.connected");
        KnowledgeBase saved = bases.findByTenantId(TENANT_ID).orElseThrow();
        assertThat(saved.kind()).isEqualTo(KnowledgeBaseKind.EXTERNAL_OBSIDIAN);
        assertThat(saved.rootPath()).isEqualTo(external.toRealPath().toString());
        assertThat(audit.actions()).contains("vault_connected");
    }

    @Test
    void connectRejectsNeutralVaultWithoutContract() {
        Path internal = new PlainVault(root).create(root.resolve("internal"));
        service.expectPath(CHAT_ID, TENANT_ID);

        OnboardingResult result = service.connect(CHAT_ID, TENANT_ID, internal.toString());

        assertThat(result.messageKey()).isEqualTo("base.connect_failed");
        assertThat(bases.findByTenantId(TENANT_ID)).isEmpty();
    }

    @Test
    void connectRejectsRelativePathWithoutSaving() {
        OnboardingResult result = service.connect(CHAT_ID, TENANT_ID, "relative/vault");

        assertThat(result.messageKey()).isEqualTo("base.path_relative");
        assertThat(bases.size()).isZero();
    }

    @Test
    void connectRejectsMissingDirectoryWithoutSaving() {
        OnboardingResult result = service.connect(CHAT_ID, TENANT_ID,
                root.resolve("missing").toString());

        assertThat(result.messageKey()).isEqualTo("base.path_missing");
        assertThat(bases.size()).isZero();
    }

    @Test
    void connectRejectsVaultWithoutContract() throws IOException {
        Path broken = root.resolve("broken");
        Files.createDirectories(broken);
        service.expectPath(CHAT_ID, TENANT_ID);

        OnboardingResult result = service.connect(CHAT_ID, TENANT_ID, broken.toString());

        assertThat(result.messageKey()).isEqualTo("base.connect_failed");
        assertThat(bases.size()).isZero();
        assertThat(audit.actions()).contains("vault_onboarding_failed");
    }

    @Test
    void resetClearsAwaitingSession() {
        service.expectPath(CHAT_ID, TENANT_ID);
        assertThat(service.awaitingPath(CHAT_ID)).isTrue();

        service.reset(CHAT_ID);

        assertThat(service.awaitingPath(CHAT_ID)).isFalse();
    }

    private static Path copyVault(Path target) throws IOException {
        Path source;
        try {
            source = Path.of(OnboardingServiceTest.class.getResource("/vault-fixture").toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("фикстура vault-fixture недоступна", exception);
        }
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(path -> {
                Path destination = target.resolve(source.relativize(path).toString());
                try {
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
        return target;
    }
}
