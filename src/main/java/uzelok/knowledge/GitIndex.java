package uzelok.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class GitIndex {

    private static final long TIMEOUT_SECONDS = 5;

    public Set<String> trackedFiles(Path vaultRoot) {
        if (vaultRoot == null || !Files.isDirectory(vaultRoot.resolve(".git"))) {
            return Set.of();
        }
        Process process;
        try {
            process = new ProcessBuilder(
                    "git", "-C", vaultRoot.toString(), "ls-files", "-z")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException exception) {
            return Set.of();
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream input = process.getInputStream()) {
                input.transferTo(buffer);
            } catch (IOException ignored) {
                // вывод git недоступен — считаем индекс пустым
            }
        });

        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                reader.join(1000);
                return Set.of();
            }
            reader.join(2000);
            if (process.exitValue() != 0) {
                return Set.of();
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return Set.of();
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        Set<String> files = new LinkedHashSet<>();
        for (String entry : output.split("\u0000")) {
            String cleaned = entry.trim().replace('\\', '/');
            if (!cleaned.isEmpty()) {
                files.add(cleaned);
            }
        }
        return Collections.unmodifiableSet(files);
    }
}
