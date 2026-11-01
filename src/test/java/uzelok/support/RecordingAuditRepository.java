package uzelok.support;

import java.util.ArrayList;
import java.util.List;
import uzelok.domain.model.AuditRecord;
import uzelok.domain.port.AuditRepository;

public final class RecordingAuditRepository implements AuditRepository {

    private final List<AuditRecord> records = new ArrayList<>();

    @Override
    public synchronized void record(AuditRecord record) {
        records.add(record);
    }

    public synchronized List<AuditRecord> records() {
        return List.copyOf(records);
    }

    public synchronized List<String> actions() {
        return records.stream().map(AuditRecord::action).toList();
    }
}
