package uzelok.domain.port;

import uzelok.domain.model.AuditRecord;

public interface AuditRepository {

    void record(AuditRecord record);
}
