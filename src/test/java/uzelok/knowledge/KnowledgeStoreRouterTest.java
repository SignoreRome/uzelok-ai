package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.NoteType;
import uzelok.domain.model.Taxonomy;
import uzelok.domain.port.KnowledgeStore;
import uzelok.domain.port.PreparedNote;
import uzelok.domain.port.WrittenNote;

class KnowledgeStoreRouterTest {

    private final RecordingStore external = new RecordingStore("External");
    private final RecordingStore internal = new RecordingStore("Internal");
    private final KnowledgeStoreRouter router = new KnowledgeStoreRouter(external, internal);
    private final NoteDraft draft = new NoteDraft("Router", NoteType.concept, "Topic",
            "Описание", "## Тело", 0.9);

    @Test
    void routesExternalBaseToExternalStore() {
        KnowledgeBase base = base(KnowledgeBaseKind.EXTERNAL_OBSIDIAN);

        assertThat(router.prepare(base, "job", draft, false).fileName()).isEqualTo("External");
        assertThat(router.write(base, prepared("External")).relPath()).isEqualTo("External");
        assertThat(router.existingNoteNames(base)).containsExactly("External");
        assertThat(router.taxonomy(base).domainIds()).containsExactly("External");
    }

    @Test
    void routesInternalBaseToInternalStore() {
        KnowledgeBase base = base(KnowledgeBaseKind.INTERNAL_DIR);

        assertThat(router.prepare(base, "job", draft, false).fileName()).isEqualTo("Internal");
        assertThat(router.write(base, prepared("Internal")).relPath()).isEqualTo("Internal");
        assertThat(router.existingNoteNames(base)).containsExactly("Internal");
        assertThat(router.taxonomy(base).domainIds()).containsExactly("Internal");
    }

    private static KnowledgeBase base(KnowledgeBaseKind kind) {
        return new KnowledgeBase(UUID.randomUUID(), kind, "/tmp/vault",
                "40_Knowledge/_meta/taxonomy.yaml", "40_Knowledge/_Inbox");
    }

    private static PreparedNote prepared(String name) {
        return new PreparedNote(UUID.randomUUID(), name, name, "", "hash", List.of());
    }

    private record RecordingStore(String name) implements KnowledgeStore {

        @Override
        public PreparedNote prepare(KnowledgeBase base, String jobRef, NoteDraft draft,
                                    boolean forceInbox) {
            return new PreparedNote(UUID.randomUUID(), name, name, "", "hash", List.of());
        }

        @Override
        public WrittenNote write(KnowledgeBase base, PreparedNote note) {
            return new WrittenNote(note.noteId(), name, "hash", true);
        }

        @Override
        public Set<String> existingNoteNames(KnowledgeBase base) {
            return Set.of(name);
        }

        @Override
        public Taxonomy taxonomy(KnowledgeBase base) {
            return new Taxonomy(2, List.of(
                    new Taxonomy.Domain(name, List.of(), List.of())));
        }
    }
}
