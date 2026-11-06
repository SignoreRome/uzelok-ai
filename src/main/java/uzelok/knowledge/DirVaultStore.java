package uzelok.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.RenderedNote;
import uzelok.domain.model.Taxonomy;
import uzelok.domain.port.KnowledgeStore;
import uzelok.domain.port.PreparedNote;
import uzelok.domain.port.WrittenNote;

class DirVaultStore implements KnowledgeStore {

    private static final Set<String> LINT_COPY_SKIPPED = Set.of(".obsidian", ".trash");

    protected final FileNamePolicy names;
    private final ContractValidator validator;
    private final NameUniqueness uniqueness;
    private final NoteRenderer renderer;
    private final LinterRunner linter;
    private final VaultLockRegistry locks;
    private final Clock clock;

    DirVaultStore(
            VaultOptions options,
            ContractValidator validator,
            NameUniqueness uniqueness,
            NoteRenderer renderer,
            LinterRunner linter,
            VaultLockRegistry locks,
            Clock clock) {
        this(validator, uniqueness, null, renderer, linter, locks, clock);
    }

    DirVaultStore(
            VaultOptions options,
            ContractValidator validator,
            NameUniqueness uniqueness,
            FileNamePolicy names,
            ContentSanitizer sanitizer,
            LinterRunner linter,
            VaultLockRegistry locks,
            Clock clock) {
        this(validator, uniqueness, names,
                new NoteRenderer(names, sanitizer, options.confidenceThreshold()),
                linter, locks, clock);
    }

    private DirVaultStore(
            ContractValidator validator,
            NameUniqueness uniqueness,
            FileNamePolicy names,
            NoteRenderer renderer,
            LinterRunner linter,
            VaultLockRegistry locks,
            Clock clock) {
        this.validator = validator;
        this.uniqueness = uniqueness;
        this.names = names;
        this.renderer = renderer;
        this.linter = linter;
        this.locks = locks;
        this.clock = clock;
    }

    protected record Layout(Path knowledgeRoot, Taxonomy taxonomy, List<String> warnings) {

        protected Layout {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    protected Layout layout(KnowledgeBase base) throws ContractViolation {
        ContractValidator.ContractReport report = validator.validate(base);
        return new Layout(report.knowledgeRoot(), report.taxonomy(), report.warnings());
    }

    protected boolean lintRequired() {
        return true;
    }

    protected void verifyParent(KnowledgeBase base, Layout layout, Path relativeParent) {
        verifyParentSegments(base, layout.taxonomy(), relativeParent);
    }

    @Override
    public PreparedNote prepare(
            KnowledgeBase base, String jobRef, NoteDraft draft, boolean forceInbox)
            throws ContractViolation {
        Layout layout = layout(base);
        Path root = Path.of(base.rootPath()).toAbsolutePath().normalize();
        Map<String, String> canonicalNames = uniqueness.canonicalNames(layout.knowledgeRoot());
        UUID noteId = jobRef != null && !jobRef.isBlank()
                ? UUID.nameUUIDFromBytes(
                        ("uzelok-note:" + jobRef).getBytes(StandardCharsets.UTF_8))
                : UUID.randomUUID();
        LocalDate today = LocalDate.now(clock);

        RenderedNote rendered = renderer.render(
                layout.taxonomy(), base, noteId, jobRef, draft, forceInbox, today, canonicalNames);
        Path target = resolveTarget(root, layout.knowledgeRoot(), rendered.relPath());

        Set<String> warnings = new LinkedHashSet<>(layout.warnings());
        warnings.addAll(rendered.warnings());
        if (Files.isRegularFile(target) && rendered.contentHash().equals(sha256(target))) {
            warnings.add("заметка уже записана, повтор не нужен");
        }
        return new PreparedNote(noteId, rendered.relPath(), rendered.fileName(),
                rendered.markdown(), rendered.contentHash(), List.copyOf(warnings));
    }

    @Override
    public WrittenNote write(KnowledgeBase base, PreparedNote note) throws ContractViolation {
        return locks.withLock(base.id(), () -> writeLocked(base, note));
    }

    @Override
    public Set<String> existingNoteNames(KnowledgeBase base) throws ContractViolation {
        Layout layout = layout(base);
        return uniqueness.existingNames(layout.knowledgeRoot());
    }

    @Override
    public Taxonomy taxonomy(KnowledgeBase base) throws ContractViolation {
        return layout(base).taxonomy();
    }

    private WrittenNote writeLocked(KnowledgeBase base, PreparedNote note) {
        Layout layout = layout(base);
        Path root = Path.of(base.rootPath()).toAbsolutePath().normalize();
        Path knowledgeRoot = layout.knowledgeRoot().toAbsolutePath().normalize();
        Path target = resolveTarget(root, knowledgeRoot, note.relPath());
        Path targetParent = target.getParent();
        if (targetParent == null) {
            throw new ContractViolation("не удалось определить каталог заметки: " + note.relPath());
        }

        verifyParent(base, layout, knowledgeRoot.relativize(targetParent));
        verifyNoSymlinks(knowledgeRoot, targetParent);

        if (Files.isRegularFile(target)) {
            String actualHash = sha256(target);
            if (actualHash.equals(note.contentHash())) {
                return new WrittenNote(note.noteId(), note.relPath(), note.contentHash(), false);
            }
            throw new ContractViolation("файл уже существует с другим содержимым: " + note.relPath());
        }
        if (Files.exists(target)) {
            throw new ContractViolation("файл уже существует с другим содержимым: " + note.relPath());
        }

        if (lintRequired()) {
            runLint(root, knowledgeRoot, note);
        }
        createDirectories(targetParent, note.relPath());
        writeAtomically(target, note);
        return new WrittenNote(note.noteId(), note.relPath(), note.contentHash(), true);
    }

    private static Path resolveTarget(Path root, Path knowledgeRoot, String relPath) {
        if (relPath == null || relPath.isBlank()) {
            throw new ContractViolation("путь заметки не задан");
        }
        Path target = root.resolve(relPath).normalize();
        Path knowledge = knowledgeRoot.toAbsolutePath().normalize();
        if (!target.startsWith(root) || !target.startsWith(knowledge)) {
            throw new ContractViolation("путь выходит за корень базы: " + relPath);
        }
        return target;
    }

    private static void verifyParentSegments(
            KnowledgeBase base, Taxonomy taxonomy, Path relativeParent) {
        List<String> segments = new ArrayList<>();
        for (Path segment : relativeParent) {
            segments.add(segment.toString());
        }
        if (segments.size() == 1) {
            String inboxFolder = Path.of(base.inboxRelPath()).getFileName() == null
                    ? "_Inbox"
                    : Path.of(base.inboxRelPath()).getFileName().toString();
            if (!segments.getFirst().equals(inboxFolder)) {
                throw new ContractViolation("запись разрешена только в _Inbox или в каталог темы: "
                        + relativeParent);
            }
            return;
        }
        if (segments.size() == 2 && taxonomy.hasTopic(segments.get(0), segments.get(1))) {
            return;
        }
        throw new ContractViolation("каталог заметки не разрешён словарём: " + relativeParent);
    }

    private static void verifyNoSymlinks(Path knowledgeRoot, Path targetParent) {
        Path current = knowledgeRoot;
        if (Files.isSymbolicLink(current)) {
            throw new ContractViolation(
                    "путь заметки проходит через символическую ссылку: " + current);
        }
        for (Path segment : knowledgeRoot.relativize(targetParent)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new ContractViolation(
                        "путь заметки проходит через символическую ссылку: " + segment);
            }
        }
    }

    private static void createDirectories(Path directory, String relPath) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new ContractViolation("не удалось создать каталог для заметки "
                    + relPath + ": " + exception.getMessage());
        }
    }

    private void runLint(Path root, Path knowledgeRoot, PreparedNote note) {
        Path tmpRoot = null;
        try {
            tmpRoot = Files.createTempDirectory("uzelok-lint-");
            Path fileName = knowledgeRoot.getFileName();
            Path stagedKnowledge = tmpRoot.resolve(
                    fileName == null ? "knowledge" : fileName.toString());
            Files.createDirectories(stagedKnowledge);
            copyTree(knowledgeRoot, stagedKnowledge);
            Path relative = knowledgeRoot.relativize(root.resolve(note.relPath()).normalize());
            Path stagedFile = stagedKnowledge.resolve(relative);
            Files.createDirectories(stagedFile.getParent());
            Files.writeString(stagedFile, note.markdown(), StandardCharsets.UTF_8);

            LinterRunner.LintResult result = linter.run(tmpRoot);
            if (!result.ok()) {
                throw new ContractViolation("линтер базы отклонил заметку " + note.relPath()
                        + ": " + result.output());
            }
        } catch (IOException exception) {
            throw new ContractViolation("не удалось подготовить временную копию для линтера: "
                    + exception.getMessage());
        } finally {
            if (tmpRoot != null) {
                deleteRecursively(tmpRoot);
            }
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                String name = directory.getFileName() == null ? "" : directory.getFileName().toString();
                if (!directory.equals(source) && LINT_COPY_SKIPPED.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (Files.isSymbolicLink(file)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    return FileVisitResult.CONTINUE;
                }
                Files.copy(file, target.resolve(source.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // временная копия будет удалена следующим прогоном
                        }
                    });
        } catch (IOException ignored) {
            // временная копия будет удалена следующим прогоном
        }
    }

    private void writeAtomically(Path target, PreparedNote note) {
        Path parent = target.getParent();
        if (parent == null) {
            throw new ContractViolation("не удалось определить каталог заметки: " + note.relPath());
        }
        Path temporary = parent.resolve(".uzelok-tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, note.markdown(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                if (Files.exists(target)) {
                    throw new ContractViolation("файл уже существует с другим содержимым: "
                            + note.relPath());
                }
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new ContractViolation("не удалось записать заметку " + note.relPath()
                    + ": " + exception.getMessage());
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // временный файл не останется в целевом каталоге
            }
        }
    }

    private static String sha256(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new ContractViolation("не удалось прочитать файл " + file + ": "
                    + exception.getMessage());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 недоступен", exception);
        }
    }
}
