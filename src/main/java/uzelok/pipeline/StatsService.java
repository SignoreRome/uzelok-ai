package uzelok.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import uzelok.domain.model.TenantStats;
import uzelok.domain.port.JobRepository;
import uzelok.domain.port.LlmCallRepository;
import uzelok.domain.port.NoteRepository;
import uzelok.domain.port.StatsProvider;

public final class StatsService implements StatsProvider {

    private final NoteRepository notes;
    private final JobRepository jobs;
    private final LlmCallRepository calls;

    public StatsService(NoteRepository notes, JobRepository jobs, LlmCallRepository calls) {
        this.notes = notes;
        this.jobs = jobs;
        this.calls = calls;
    }

    @Override
    public TenantStats forTenant(UUID tenantId, Instant now) {
        Instant dayAgo = now.minus(Duration.ofDays(1));
        Instant monthStart = now.atZone(ZoneOffset.UTC)
                .withDayOfMonth(1)
                .truncatedTo(ChronoUnit.DAYS)
                .toInstant();
        return new TenantStats(
                notes.countByTenant(tenantId),
                jobs.countForTenantSince(tenantId, dayAgo),
                calls.spentSince(tenantId, dayAgo),
                calls.spentSince(tenantId, monthStart));
    }
}
