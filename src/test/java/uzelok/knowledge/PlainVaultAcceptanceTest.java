package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.NoteType;
import uzelok.domain.port.PreparedNote;
import uzelok.domain.port.WrittenNote;

class PlainVaultAcceptanceTest {

    private static final String JOB_REF = "abcdef12-0000-0000-0000-000000000000";

    @TempDir
    Path root;

    @Test
    void createsNeutralVaultWritesNoteAndRepeatsIdempotently() throws IOException {
        Path vaultRoot = new PlainVault(root).create(root.resolve("internal"));
        KnowledgeBase base = new KnowledgeBase(UUID.randomUUID(), KnowledgeBaseKind.INTERNAL_DIR,
                vaultRoot.toString(), null, null);
        PlainVaultStore store = new PlainVaultStore(
                new VaultOptions(0.55),
                new ContractValidator(),
                new NameUniqueness(),
                new FileNamePolicy(60),
                new ContentSanitizer(),
                new LinterRunner("", 60),
                new VaultLockRegistry(),
                Clock.systemUTC());
        NoteDraft draft = new NoteDraft("Acceptance Note", NoteType.concept, "Kafka Streams",
                "Приёмочная заметка нейтральной базы",
                "## Идея\n\nЗаметка лежит в notes/<Topic>/<Name>.md.", 0.93);

        PreparedNote prepared = store.prepare(base, JOB_REF, draft, false);
        WrittenNote written = store.write(base, prepared);

        assertThat(prepared.relPath()).isEqualTo("notes/Kafka_Streams/Acceptance_Note.md");
        assertThat(prepared.markdown()).contains("topic: Kafka Streams").doesNotContain("domain:");
        assertThat(written.created()).isTrue();
        assertThat(vaultRoot.resolve(written.relPath())).isRegularFile();
        assertThat(Files.readString(vaultRoot.resolve(written.relPath())))
                .isEqualTo(prepared.markdown());
        assertThat(vaultRoot.resolve("40_Knowledge")).doesNotExist();
        assertThat(vaultRoot.resolve("notes/Kafka_Streams")).isDirectory();

        WrittenNote repeated = store.write(base, prepared);
        assertThat(repeated.created()).isFalse();
        assertThat(repeated.contentHash()).isEqualTo(written.contentHash());

        try (var files = Files.walk(vaultRoot.resolve("notes"))) {
            assertThat(files.map(path -> path.getFileName().toString())
                    .noneMatch(name -> name.startsWith(".uzelok-tmp"))).isTrue();
        }
    }
}
