package uzelok.config;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import uzelok.pipeline.JobWorker;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfig.class);

    private final JobWorker worker;
    private final Clock clock;

    public SchedulingConfig(JobWorker worker, Clock clock) {
        this.worker = worker;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${uzelok.worker.poll-interval-ms:2000}")
    public void poll() {
        try {
            worker.runOnce(Instant.now(clock));
        } catch (RuntimeException exception) {
            log.error("цикл воркера завершился ошибкой exception={}",
                    exception.getClass().getSimpleName());
        }
    }
}
