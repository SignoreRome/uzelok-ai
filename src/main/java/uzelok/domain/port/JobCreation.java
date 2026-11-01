package uzelok.domain.port;

import uzelok.domain.model.ProcessingJob;

public record JobCreation(ProcessingJob job, boolean created) {
}
