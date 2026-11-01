package uzelok.telegram;

import java.util.function.LongSupplier;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.port.TelegramGateway;

public final class StatusMessage {

    private static final long DEFAULT_MIN_INTERVAL_NANOS = 2_000_000_000L;

    private final TelegramGateway telegram;
    private final LongSupplier nanoTime;
    private final long minIntervalNanos;
    private long lastEditNanos = Long.MIN_VALUE;

    public StatusMessage(TelegramGateway telegram) {
        this(telegram, System::nanoTime, DEFAULT_MIN_INTERVAL_NANOS);
    }

    StatusMessage(TelegramGateway telegram, LongSupplier nanoTime, long minIntervalNanos) {
        this.telegram = telegram;
        this.nanoTime = nanoTime;
        this.minIntervalNanos = minIntervalNanos;
    }

    public long send(long chatId, ProcessingJob job, String text) {
        return telegram.sendMessage(chatId, text, null);
    }

    public void edit(long chatId, ProcessingJob job, String text) {
        long now = nanoTime.getAsLong();
        if (lastEditNanos != Long.MIN_VALUE && now - lastEditNanos < minIntervalNanos) {
            return;
        }
        Long messageId = job.statusMessageId();
        if (messageId == null) {
            telegram.sendMessage(chatId, text, null);
            lastEditNanos = now;
            return;
        }
        telegram.editMessage(chatId, messageId, text, null);
        lastEditNanos = now;
    }
}
