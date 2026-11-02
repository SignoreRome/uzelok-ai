package uzelok.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.ProcessingJob;
import uzelok.support.InMemoryTelegramGateway;

class StatusMessageTest {

    @Test
    void editIsThrottledToOncePerTwoSeconds() {
        InMemoryTelegramGateway telegram = new InMemoryTelegramGateway();
        long[] now = {0L};
        StatusMessage statusMessage = new StatusMessage(telegram, () -> now[0], 2_000_000_000L);
        ProcessingJob job = job(42L);

        statusMessage.edit(100L, job, "первая");
        now[0] = 1_000_000_000L;
        statusMessage.edit(100L, job, "вторая");
        now[0] = 2_000_000_001L;
        statusMessage.edit(100L, job, "третья");

        assertThat(telegram.edited()).hasSize(2);
        assertThat(telegram.edited().get(0).text()).isEqualTo("первая");
        assertThat(telegram.edited().get(1).text()).isEqualTo("третья");
    }

    @Test
    void sendReturnsMessageId() {
        InMemoryTelegramGateway telegram = new InMemoryTelegramGateway();
        StatusMessage statusMessage = new StatusMessage(telegram);

        long messageId = statusMessage.send(100L, job(null), "статус");

        assertThat(messageId).isPositive();
        assertThat(telegram.sent()).hasSize(1);
    }

    private static ProcessingJob job(Long statusMessageId) {
        Instant now = Instant.parse("2026-01-15T10:00:00Z");
        return new ProcessingJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                JobStatus.RECEIVED,
                null,
                0,
                now,
                null,
                statusMessageId,
                null,
                null,
                null,
                now,
                now,
                0,
                false);
    }
}
