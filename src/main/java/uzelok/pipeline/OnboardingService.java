package uzelok.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.AuditRecord;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.OnboardingResult;
import uzelok.domain.port.AuditRepository;
import uzelok.domain.port.KnowledgeBaseRepository;
import uzelok.domain.port.OnboardingHandler;
import uzelok.domain.port.OnboardingSessionRepository;
import uzelok.domain.port.VaultInspector;
import uzelok.domain.port.VaultProvisioner;

public final class OnboardingService implements OnboardingHandler {

    private static final Duration PATH_TTL = Duration.ofMinutes(15);
    private static final String TAXONOMY_REL_PATH = "40_Knowledge/_meta/taxonomy.yaml";
    private static final String INBOX_REL_PATH = "40_Knowledge/_Inbox";

    private final KnowledgeBaseRepository bases;
    private final OnboardingSessionRepository sessions;
    private final VaultProvisioner provisioner;
    private final VaultInspector inspector;
    private final AuditRepository audit;
    private final Clock clock;
    private final Path vaultsRoot;

    public OnboardingService(KnowledgeBaseRepository bases, OnboardingSessionRepository sessions,
                             VaultProvisioner provisioner, VaultInspector inspector,
                             AuditRepository audit, Clock clock, Path vaultsRoot) {
        this.bases = bases;
        this.sessions = sessions;
        this.provisioner = provisioner;
        this.inspector = inspector;
        this.audit = audit;
        this.clock = clock;
        this.vaultsRoot = vaultsRoot.toAbsolutePath().normalize();
    }

    @Override
    public boolean awaitingPath(long chatId) {
        return sessions.isAwaitingPath(chatId, clock.instant());
    }

    @Override
    public OnboardingResult expectPath(long chatId, UUID tenantId) {
        Optional<KnowledgeBase> existing = bases.findByTenantId(tenantId);
        if (existing.isPresent()) {
            return new OnboardingResult("base.already",
                    List.of(existing.get().rootPath()));
        }
        sessions.awaitPath(tenantId, chatId, clock.instant().plus(PATH_TTL));
        return new OnboardingResult("base.ask_path", List.of());
    }

    @Override
    public OnboardingResult createInternal(long chatId, UUID tenantId) {
        Optional<KnowledgeBase> existing = bases.findByTenantId(tenantId);
        if (existing.isPresent()) {
            return new OnboardingResult("base.already", List.of(existing.get().rootPath()));
        }
        Path root = vaultsRoot.resolve(tenantId.toString());
        boolean existed = hasContent(root);
        Path created;
        try {
            created = provisioner.create(root);
        } catch (ContractViolation exception) {
            audit.record(new AuditRecord(tenantId, chatId, "vault_onboarding_failed",
                    null, null, Map.of("reason", exception.getMessage())));
            return new OnboardingResult("base.create_failed", List.of(exception.getMessage()));
        }

        KnowledgeBase saved = bases.save(tenantId, new KnowledgeBase(
                UUID.randomUUID(),
                KnowledgeBaseKind.INTERNAL_DIR,
                created.toString(),
                null,
                null));
        sessions.clear(chatId);
        audit.record(new AuditRecord(tenantId, chatId, "vault_created",
                "knowledge_base", saved.id(), Map.of(
                        "root", created.toString(),
                        "existed", Boolean.toString(existed))));
        String key = existed ? "base.exists" : "base.created";
        return new OnboardingResult(key, List.of(created.toString()));
    }

    private static boolean hasContent(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.findAny().isPresent();
        } catch (IOException exception) {
            return false;
        }
    }

    @Override
    public OnboardingResult connect(long chatId, UUID tenantId, String pathText) {
        String text = pathText == null ? "" : pathText.trim();
        if (text.isEmpty() || !text.startsWith("/")) {
            return new OnboardingResult("base.path_relative", List.of());
        }

        Path candidate;
        try {
            candidate = Path.of(text).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            return new OnboardingResult("base.path_missing", List.of(text));
        }
        if (!Files.isDirectory(candidate)) {
            return new OnboardingResult("base.path_missing", List.of(candidate.toString()));
        }

        Path canonical;
        try {
            canonical = candidate.toRealPath();
        } catch (IOException exception) {
            return new OnboardingResult("base.path_missing", List.of(candidate.toString()));
        }

        Path ownVault = vaultsRoot.resolve(tenantId.toString()).normalize();
        if (canonical.startsWith(vaultsRoot) && !canonical.equals(ownVault)) {
            audit.record(new AuditRecord(tenantId, chatId, "vault_onboarding_failed",
                    null, null, Map.of("reason", "foreign_vault")));
            return new OnboardingResult("base.foreign_vault", List.of(canonical.toString()));
        }

        Optional<KnowledgeBase> existing = bases.findByTenantId(tenantId);
        Optional<KnowledgeBase> registered = bases.findByRootPath(canonical.toString());
        if (registered.isPresent() && (existing.isEmpty()
                || !existing.get().rootPath().equals(canonical.toString()))) {
            audit.record(new AuditRecord(tenantId, chatId, "vault_onboarding_failed",
                    null, null, Map.of("reason", "foreign_vault")));
            return new OnboardingResult("base.foreign_vault", List.of(canonical.toString()));
        }
        if (existing.isPresent()) {
            return new OnboardingResult("base.already", List.of(existing.get().rootPath()));
        }

        Optional<UUID> expectation = sessions.consumeAwaitPath(chatId, clock.instant());
        if (expectation.isEmpty() || !expectation.get().equals(tenantId)) {
            return new OnboardingResult("base.expectation_expired", List.of());
        }

        List<String> violations = inspector.violations(canonical);
        if (!violations.isEmpty()) {
            String joined = violations.stream().limit(3).map(OnboardingService::truncate)
                    .collect(Collectors.joining("; "));
            audit.record(new AuditRecord(tenantId, chatId, "vault_onboarding_failed",
                    null, null, Map.of("reason", joined)));
            return new OnboardingResult("base.connect_failed", List.of(joined));
        }

        KnowledgeBaseKind kind = KnowledgeBaseKind.EXTERNAL_OBSIDIAN;
        KnowledgeBase saved = bases.save(tenantId, new KnowledgeBase(
                UUID.randomUUID(),
                kind,
                canonical.toString(),
                TAXONOMY_REL_PATH,
                INBOX_REL_PATH));
        sessions.clear(chatId);
        audit.record(new AuditRecord(tenantId, chatId, "vault_connected",
                "knowledge_base", saved.id(), Map.of(
                        "root", canonical.toString(),
                        "kind", kind.name())));
        return new OnboardingResult("base.connected", List.of(canonical.toString()));
    }

    @Override
    public void reset(long chatId) {
        sessions.clear(chatId);
    }

    private static String truncate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}
