package uzelok.knowledge;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.ExportBundle;
import uzelok.domain.model.ExportPart;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.port.VaultExporter;

public final class ExportArchiver implements VaultExporter {

    private static final long ENTRY_OVERHEAD = 64L * 1024;
    private static final int MAX_SPLIT_DEPTH = 8;
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ContractValidator validator;
    private final Path exportsDir;
    private final long maxBytes;
    private final int maxFiles;
    private final Clock clock;

    public ExportArchiver(ContractValidator validator, Path exportsDir, long maxBytes,
                          int maxFiles, Clock clock) {
        this.validator = validator;
        this.exportsDir = exportsDir.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.maxFiles = maxFiles;
        this.clock = clock;
    }

    @Override
    public ExportBundle export(KnowledgeBase base) throws ContractViolation {
        if (base.kind() != KnowledgeBaseKind.INTERNAL_DIR) {
            throw new ContractViolation("экспорт доступен только внутренней базе");
        }
        ContractValidator.ContractReport report = validator.validate(base);
        Path root = Path.of(base.rootPath()).toAbsolutePath().normalize();
        Path knowledgeRoot = report.knowledgeRoot().toAbsolutePath().normalize();

        List<String> warnings = new ArrayList<>();
        List<FileEntry> included = collect(root, knowledgeRoot, warnings);
        if (included.size() > maxFiles) {
            throw new ContractViolation("в базе слишком много файлов для экспорта: "
                    + included.size() + " > " + maxFiles);
        }
        if (included.isEmpty()) {
            return new ExportBundle(null, List.of(), 0, warnings);
        }

        Path tempRoot = createTempRoot();
        try {
            List<PendingPart> pending = new ArrayList<>();
            int fileCount = 0;
            for (List<FileEntry> group : greedyGroups(included)) {
                fileCount += writeGroup(tempRoot, group, 0, pending, warnings);
            }

            List<ExportPart> parts = nameParts(pending, base.id());
            return new ExportBundle(tempRoot, parts, fileCount, warnings);
        } catch (RuntimeException exception) {
            deleteRecursively(tempRoot);
            throw exception;
        }
    }

    @Override
    public void cleanup(ExportBundle bundle) {
        if (bundle == null || bundle.tempDirectory() == null) {
            return;
        }
        deleteRecursively(bundle.tempDirectory());
    }

    private List<FileEntry> collect(Path root, Path knowledgeRoot, List<String> warnings) {
        List<FileEntry> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(knowledgeRoot)) {
            for (Path path : walk.toList()) {
                if (path.equals(knowledgeRoot)) {
                    continue;
                }
                Path relativeToKnowledge = knowledgeRoot.relativize(path);
                if (insideHiddenDirectory(relativeToKnowledge)) {
                    continue;
                }
                if (Files.isSymbolicLink(path)
                        || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String relative = root.relativize(path).toString();
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    warnings.add("нерегулярный файл: " + relative);
                    continue;
                }
                if (invalidArchivePath(relative)) {
                    warnings.add("недопустимый путь в архиве: " + relative);
                    continue;
                }
                long size = Files.size(path);
                if (size > maxBytes) {
                    warnings.add("файл больше лимита: " + relative);
                    continue;
                }
                files.add(new FileEntry(path, relative, size));
            }
        } catch (IOException exception) {
            throw new ContractViolation("не удалось прочитать файлы базы: "
                    + exception.getMessage());
        }
        files.sort(Comparator.comparing(FileEntry::relativePath));
        return files;
    }

    private static boolean invalidArchivePath(String relative) {
        if (relative.startsWith("/") || relative.contains("\\")) {
            return true;
        }
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static boolean insideHiddenDirectory(Path relativeToKnowledge) {
        Path parent = relativeToKnowledge.getParent();
        if (parent == null) {
            return false;
        }
        for (Path segment : parent) {
            if (segment.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private List<List<FileEntry>> greedyGroups(List<FileEntry> files) {
        long capacity = maxBytes * 4 / 5;
        List<List<FileEntry>> groups = new ArrayList<>();
        List<FileEntry> current = new ArrayList<>();
        long currentSize = 0;
        for (FileEntry file : files) {
            long added = file.size() + ENTRY_OVERHEAD;
            if (!current.isEmpty() && currentSize + added > capacity) {
                groups.add(current);
                current = new ArrayList<>();
                currentSize = 0;
            }
            current.add(file);
            currentSize += added;
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    private int writeGroup(Path tempRoot, List<FileEntry> group, int depth,
                           List<PendingPart> pending, List<String> warnings) {
        Path partDirectory;
        Path zip;
        try {
            partDirectory = Files.createTempDirectory(tempRoot, "part-");
            zip = partDirectory.resolve("bundle.zip");
            writeZip(zip, group, warnings);
            long size = Files.size(zip);
            if (size <= maxBytes) {
                pending.add(new PendingPart(zip, size));
                return group.size();
            }
            deleteRecursively(partDirectory);
        } catch (IOException exception) {
            throw new ContractViolation("не удалось собрать архив базы: "
                    + exception.getMessage());
        }

        if (group.size() == 1) {
            warnings.add("файл больше лимита: " + group.getFirst().relativePath());
            return 0;
        }
        if (depth >= MAX_SPLIT_DEPTH) {
            for (FileEntry file : group) {
                warnings.add("файл больше лимита: " + file.relativePath());
            }
            return 0;
        }
        int middle = group.size() / 2;
        return writeGroup(tempRoot, group.subList(0, middle), depth + 1, pending, warnings)
                + writeGroup(tempRoot, group.subList(middle, group.size()), depth + 1,
                        pending, warnings);
    }

    private static void writeZip(Path zip, List<FileEntry> group, List<String> warnings)
            throws IOException {
        try (OutputStream raw = Files.newOutputStream(zip);
             ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(raw))) {
            for (FileEntry file : group) {
                if (Files.isSymbolicLink(file.path())
                        || !Files.isRegularFile(file.path(), LinkOption.NOFOLLOW_LINKS)) {
                    warnings.add("нерегулярный файл: " + file.relativePath());
                    continue;
                }
                ZipEntry entry = new ZipEntry(file.relativePath());
                out.putNextEntry(entry);
                Files.copy(file.path(), out);
                out.closeEntry();
            }
        }
    }

    private List<ExportPart> nameParts(List<PendingPart> pending, UUID id) {
        Instant now = clock.instant();
        String stamp = TIMESTAMP.format(now);
        String kb8 = firstEight(id == null ? "00000000" : id.toString());
        List<ExportPart> parts = new ArrayList<>();
        for (int index = 0; index < pending.size(); index++) {
            PendingPart part = pending.get(index);
            String fileName = pending.size() == 1
                    ? "uzelok-export-" + kb8 + "-" + stamp + ".zip"
                    : "uzelok-export-" + kb8 + "-" + stamp + "-part" + (index + 1) + ".zip";
            Path target = part.zip().resolveSibling(fileName);
            try {
                Files.move(part.zip(), target);
            } catch (IOException exception) {
                throw new ContractViolation("не удалось сохранить часть архива: "
                        + exception.getMessage());
            }
            parts.add(new ExportPart(fileName, target, part.sizeBytes()));
        }
        return parts;
    }

    private static String firstEight(String value) {
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private Path createTempRoot() {
        try {
            Files.createDirectories(exportsDir);
            restrictPermissions(exportsDir);
            return Files.createTempDirectory(exportsDir, "export-");
        } catch (IOException exception) {
            throw new ContractViolation("не удалось создать каталог экспорта: "
                    + exception.getMessage());
        }
    }

    private static void restrictPermissions(Path directory) {
        try {
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(directory,
                        PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException | UnsupportedOperationException exception) {
            // не POSIX-файловая система: права выставляются по умолчанию
        }
    }

    private static void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exception)
                        throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // временный каталог будет удалён следующим прогоном
        }
    }

    private record FileEntry(Path path, String relativePath, long size) {
    }

    private record PendingPart(Path zip, long sizeBytes) {
    }
}
