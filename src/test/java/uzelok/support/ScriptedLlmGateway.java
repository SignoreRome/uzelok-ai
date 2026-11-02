package uzelok.support;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import uzelok.domain.model.Extraction;
import uzelok.domain.model.LlmContext;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.NoteType;
import uzelok.domain.model.Taxonomy;
import uzelok.domain.model.Thesis;
import uzelok.domain.port.LlmGateway;

public final class ScriptedLlmGateway implements LlmGateway {

    private final AtomicInteger extractCalls = new AtomicInteger();
    private final AtomicInteger draftCalls = new AtomicInteger();
    private final List<LlmContext> draftContexts = new ArrayList<>();
    private RuntimeException extractFailure;
    private RuntimeException draftFailure;
    private Extraction extraction = new Extraction(
            "распознанный текст",
            List.of(new Thesis("тезис", 0.9)),
            List.of(),
            0.9);
    private NoteDraft draft = new NoteDraft(
            "Тестовая заметка",
            NoteType.concept,
            "Graphs",
            "Короткое описание",
            "## Тело\n\nТекст заметки.",
            0.9);

    public ScriptedLlmGateway failExtract(RuntimeException failure) {
        this.extractFailure = failure;
        return this;
    }

    public ScriptedLlmGateway failDraft(RuntimeException failure) {
        this.draftFailure = failure;
        return this;
    }

    public ScriptedLlmGateway withExtraction(Extraction value) {
        this.extraction = value;
        return this;
    }

    public ScriptedLlmGateway withDraft(NoteDraft value) {
        this.draft = value;
        return this;
    }

    public int extractCalls() {
        return extractCalls.get();
    }

    public int draftCalls() {
        return draftCalls.get();
    }

    public List<LlmContext> draftContexts() {
        return List.copyOf(draftContexts);
    }

    @Override
    public Extraction extract(LlmContext context, List<Path> images, String text) {
        extractCalls.incrementAndGet();
        if (extractFailure != null) {
            throw extractFailure;
        }
        return extraction;
    }

    @Override
    public NoteDraft draft(LlmContext context, Extraction value, Taxonomy taxonomy,
                           Set<String> existingNames) {
        draftCalls.incrementAndGet();
        draftContexts.add(context);
        if (draftFailure != null) {
            throw draftFailure;
        }
        return draft;
    }
}
