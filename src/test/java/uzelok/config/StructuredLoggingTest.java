package uzelok.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.MDC;

/** Проверяет, что logback-spring.xml действительно выдаёт JSON-строки с MDC-полями из NFR-8. */
class StructuredLoggingTest {

    @Test
    void writesOneJsonLineWithMdcFields() throws Exception {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(MDC.getMDCAdapter());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream config = getClass().getResourceAsStream("/logback-spring.xml")) {
            assertThat(config).isNotNull();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(config);
        }

        OutputStreamAppender<ILoggingEvent> console = findConsoleAppender(context);
        console.setOutputStream(new PrintStream(output, true, StandardCharsets.UTF_8));

        try {
            MDC.put("job_id", "j1");
            MDC.put("step", "EXTRACT");
            MDC.put("tenant", "roman");
            context.getLogger("uzelok.logging-test").info("structured logging test message");
        } finally {
            MDC.clear();
            context.stop();
        }

        String logged = output.toString(StandardCharsets.UTF_8).trim();
        List<String> lines = logged.lines().toList();

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
                .startsWith("{")
                .contains("\"job_id\":\"j1\"")
                .contains("\"step\":\"EXTRACT\"")
                .contains("\"tenant\":\"roman\"")
                .contains("\"message\":\"structured logging test message\"");
    }

    private static OutputStreamAppender<ILoggingEvent> findConsoleAppender(LoggerContext context) {
        var appender = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
        assertThat(appender).isInstanceOf(OutputStreamAppender.class);
        @SuppressWarnings("unchecked")
        OutputStreamAppender<ILoggingEvent> console = (OutputStreamAppender<ILoggingEvent>) appender;
        return console;
    }
}
