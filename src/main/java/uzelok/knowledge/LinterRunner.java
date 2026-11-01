package uzelok.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class LinterRunner {

    private static final int OUTPUT_LIMIT = 4000;
    private static final int TIMEOUT_EXIT_CODE = 124;

    public record LintResult(int exitCode, String output) {

        public LintResult {
            output = output == null ? "" : output;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }

    private final String command;
    private final int timeoutSeconds;

    public LinterRunner(String command, int timeoutSeconds) {
        this.command = command == null ? "" : command.trim();
        this.timeoutSeconds = timeoutSeconds;
    }

    public LintResult run(Path vaultRoot) {
        if (command.isEmpty()) {
            return new LintResult(0, "");
        }
        List<String> parts = new ArrayList<>();
        boolean rootReplaced = false;
        for (String part : command.split("\\s+")) {
            if (part.contains("{root}")) {
                rootReplaced = true;
                parts.add(part.replace("{root}", vaultRoot.toString()));
            } else {
                parts.add(part);
            }
        }
        if (!rootReplaced) {
            parts.add("--root");
            parts.add(vaultRoot.toString());
        }

        Process process;
        try {
            process = new ProcessBuilder(parts).redirectErrorStream(true).start();
        } catch (IOException exception) {
            return new LintResult(127, "не удалось запустить линтер: " + exception.getMessage());
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream input = process.getInputStream()) {
                input.transferTo(buffer);
            } catch (IOException ignored) {
                // вывод процесса мог закрыться при принудительном завершении
            }
        });

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                reader.join(1000);
                return new LintResult(TIMEOUT_EXIT_CODE,
                        tail(buffer.toString(StandardCharsets.UTF_8)
                                + "\nлинтер не уложился в " + timeoutSeconds + " с"));
            }
            reader.join(2000);
            return new LintResult(process.exitValue(), tail(buffer.toString(StandardCharsets.UTF_8)));
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new LintResult(130, "запуск линтера прерван");
        }
    }

    private static String tail(String output) {
        return output.length() <= OUTPUT_LIMIT ? output : output.substring(output.length() - OUTPUT_LIMIT);
    }
}
