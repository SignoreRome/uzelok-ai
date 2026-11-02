package uzelok.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import uzelok.domain.model.JobStatus;

class JobStateMachineTest {

    private final JobStateMachine machine = new JobStateMachine();

    @Test
    void allowsAllTransitionsFromSection11() {
        List<Transition> allowed = List.of(
                new Transition(JobStatus.RECEIVED, JobStatus.EXTRACTING),
                new Transition(JobStatus.EXTRACTING, JobStatus.DRAFTING),
                new Transition(JobStatus.DRAFTING, JobStatus.AWAITING_CONFIRM),
                new Transition(JobStatus.AWAITING_CONFIRM, JobStatus.DRAFTING),
                new Transition(JobStatus.AWAITING_CONFIRM, JobStatus.WRITING),
                new Transition(JobStatus.AWAITING_CONFIRM, JobStatus.REJECTED),
                new Transition(JobStatus.AWAITING_CONFIRM, JobStatus.CANCELLED),
                new Transition(JobStatus.WRITING, JobStatus.DONE),
                new Transition(JobStatus.WRITING, JobStatus.FAILED),
                new Transition(JobStatus.RECEIVED, JobStatus.FAILED),
                new Transition(JobStatus.EXTRACTING, JobStatus.FAILED),
                new Transition(JobStatus.DRAFTING, JobStatus.FAILED),
                new Transition(JobStatus.AWAITING_CONFIRM, JobStatus.FAILED),
                new Transition(JobStatus.FAILED, JobStatus.EXTRACTING),
                new Transition(JobStatus.FAILED, JobStatus.DRAFTING),
                new Transition(JobStatus.FAILED, JobStatus.WRITING));

        for (Transition transition : allowed) {
            assertThat(machine.canTransition(transition.from(), transition.to()))
                    .as("%s -> %s", transition.from(), transition.to())
                    .isTrue();
        }
    }

    @Test
    void rejectsReverseTransitions() {
        assertThat(machine.canTransition(JobStatus.EXTRACTING, JobStatus.RECEIVED)).isFalse();
        assertThat(machine.canTransition(JobStatus.DRAFTING, JobStatus.EXTRACTING)).isFalse();
        assertThat(machine.canTransition(JobStatus.WRITING, JobStatus.AWAITING_CONFIRM)).isFalse();
    }

    @Test
    void rejectsTransitionsThroughState() {
        assertThat(machine.canTransition(JobStatus.RECEIVED, JobStatus.DRAFTING)).isFalse();
        assertThat(machine.canTransition(JobStatus.RECEIVED, JobStatus.AWAITING_CONFIRM)).isFalse();
        assertThat(machine.canTransition(JobStatus.EXTRACTING, JobStatus.AWAITING_CONFIRM)).isFalse();
        assertThat(machine.canTransition(JobStatus.DRAFTING, JobStatus.WRITING)).isFalse();
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        for (JobStatus terminal : List.of(JobStatus.DONE, JobStatus.REJECTED,
                JobStatus.CANCELLED)) {
            for (JobStatus target : JobStatus.values()) {
                assertThat(machine.canTransition(terminal, target))
                        .as("%s -> %s", terminal, target)
                        .isFalse();
            }
        }
    }

    private record Transition(JobStatus from, JobStatus to) {
    }
}
