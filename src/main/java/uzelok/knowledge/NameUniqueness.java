package uzelok.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public final class NameUniqueness {

    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of("_meta", ".obsidian", ".trash");

    public Set<String> existingNames(Path knowledgeRoot) {
        Path knowledge = knowledgeRoot.toAbsolutePath().normalize();
        Set<String> names = new HashSet<>();

        if (Files.isDirectory(knowledge)) {
            try (Stream<Path> walk = Files.walk(knowledge)) {
                walk.filter(Files::isRegularFile).forEach(file -> {
                    Path relative = knowledge.relativize(file);
                    if (isIncluded(relative)) {
                        names.add(stem(relative));
                    }
                });
            } catch (IOException ignored) {
                // недоступные ветки дерева просто не попали в выборку
            }
        }

        return Collections.unmodifiableSet(names);
    }

    private static boolean isIncluded(Path relative) {
        for (Path segment : relative) {
            String name = segment.toString();
            if (name.startsWith(".") || SKIPPED_DIRECTORIES.contains(name)) {
                return false;
            }
        }
        String fileName = relative.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") && !fileName.endsWith("_Template.md");
    }

    private static String stem(Path relative) {
        String fileName = relative.getFileName().toString();
        return fileName.substring(0, fileName.length() - 3).toLowerCase(Locale.ROOT);
    }
}
