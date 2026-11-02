package uzelok.pipeline;

import uzelok.domain.model.JobStatus;

public record StepOutcome(boolean advanced, JobStatus next) {
}
