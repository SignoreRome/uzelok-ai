package uzelok.pipeline;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import uzelok.domain.model.JobStatus;

public final class JobStateMachine {

    private static final Map<JobStatus, Set<JobStatus>> TRANSITIONS = transitions();

    public boolean canTransition(JobStatus from, JobStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    private static Map<JobStatus, Set<JobStatus>> transitions() {
        Map<JobStatus, Set<JobStatus>> map = new EnumMap<>(JobStatus.class);
        map.put(JobStatus.RECEIVED, EnumSet.of(JobStatus.EXTRACTING, JobStatus.FAILED));
        map.put(JobStatus.EXTRACTING, EnumSet.of(JobStatus.DRAFTING, JobStatus.FAILED));
        map.put(JobStatus.DRAFTING, EnumSet.of(JobStatus.AWAITING_CONFIRM, JobStatus.FAILED));
        map.put(JobStatus.AWAITING_CONFIRM, EnumSet.of(JobStatus.DRAFTING, JobStatus.WRITING,
                JobStatus.REJECTED, JobStatus.CANCELLED, JobStatus.FAILED));
        map.put(JobStatus.WRITING, EnumSet.of(JobStatus.DONE, JobStatus.FAILED));
        map.put(JobStatus.FAILED, EnumSet.of(JobStatus.RECEIVED, JobStatus.EXTRACTING,
                JobStatus.DRAFTING, JobStatus.WRITING));
        map.put(JobStatus.DONE, EnumSet.noneOf(JobStatus.class));
        map.put(JobStatus.REJECTED, EnumSet.noneOf(JobStatus.class));
        map.put(JobStatus.CANCELLED, EnumSet.noneOf(JobStatus.class));
        return Map.copyOf(map);
    }
}
