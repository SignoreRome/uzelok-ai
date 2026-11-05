package uzelok.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uzelok.domain.port.ArtifactRepository;
import uzelok.domain.port.AttachmentRepository;
import uzelok.domain.port.AuditRepository;
import uzelok.domain.port.JobNotifier;
import uzelok.domain.port.JobRepository;
import uzelok.domain.port.JsonCodec;
import uzelok.domain.port.KnowledgeBaseRepository;
import uzelok.domain.port.KnowledgeStore;
import uzelok.domain.port.LlmCallRepository;
import uzelok.domain.port.LlmGateway;
import uzelok.domain.port.NoteRepository;
import uzelok.domain.port.OnboardingHandler;
import uzelok.domain.port.OnboardingSessionRepository;
import uzelok.domain.port.SourceItemRepository;
import uzelok.domain.port.TelegramGateway;
import uzelok.domain.port.VaultInspector;
import uzelok.domain.port.VaultProvisioner;
import uzelok.pipeline.ActionService;
import uzelok.pipeline.DraftStep;
import uzelok.pipeline.ExtractStep;
import uzelok.pipeline.JobService;
import uzelok.pipeline.JobStateMachine;
import uzelok.pipeline.JobWorker;
import uzelok.pipeline.OnboardingService;
import uzelok.pipeline.RenderStep;
import uzelok.pipeline.StatsService;
import uzelok.pipeline.WriteStep;
import uzelok.domain.port.PendingActionRepository;
import uzelok.telegram.Messages;
import java.nio.file.Path;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class PipelineConfig {

    @Bean
    public JobStateMachine jobStateMachine() {
        return new JobStateMachine();
    }

    @Bean
    public JobService jobService(JobRepository jobs, JobStateMachine stateMachine) {
        return new JobService(jobs, stateMachine);
    }

    @Bean
    public ExtractStep extractStep(SourceItemRepository source, AttachmentRepository attachments,
                                   ArtifactRepository artifacts, LlmGateway llm, JsonCodec json) {
        return new ExtractStep(source, attachments, artifacts, llm, json);
    }

    @Bean
    public DraftStep draftStep(ArtifactRepository artifacts, KnowledgeBaseRepository bases,
                               KnowledgeStore knowledge, LlmGateway llm, JsonCodec json) {
        return new DraftStep(artifacts, bases, knowledge, llm, json);
    }

    @Bean
    public RenderStep renderStep(ArtifactRepository artifacts, KnowledgeBaseRepository bases,
                                 KnowledgeStore knowledge, SourceItemRepository source,
                                 JobNotifier notifier, JsonCodec json) {
        return new RenderStep(artifacts, bases, knowledge, source, notifier, json);
    }

    @Bean
    public WriteStep writeStep(ArtifactRepository artifacts, KnowledgeBaseRepository bases,
                               KnowledgeStore knowledge, NoteRepository notes,
                               uzelok.domain.port.AuditRepository audit, JsonCodec json) {
        return new WriteStep(artifacts, bases, knowledge, notes, audit, json);
    }

    @Bean
    public ActionDispatcher actionDispatcher(JobRepository jobs, JobService state,
                                             SourceItemRepository source,
                                             AttachmentRepository attachments, Clock clock,
                                             UzelokProperties properties, AuditRepository audit,
                                             Messages messages) {
        return new ActionDispatcher(
                new ActionService(jobs, state, properties.worker().maxAttempts(), source,
                        attachments, clock, properties.limits().jobsPerDay(),
                        properties.limits().jobsPerHour(), audit, messages));
    }

    @Bean
    public StatsService statsService(NoteRepository notes, JobRepository jobs,
                                     LlmCallRepository calls) {
        return new StatsService(notes, jobs, calls);
    }

    @Bean
    public OnboardingHandler onboardingHandler(KnowledgeBaseRepository bases,
                                               OnboardingSessionRepository sessions,
                                               VaultProvisioner provisioner,
                                               VaultInspector inspector,
                                               AuditRepository audit,
                                               Clock clock,
                                               UzelokProperties properties) {
        return new OnboardingService(bases, sessions, provisioner, inspector, audit, clock,
                Path.of(properties.storage().vaultsDir()));
    }

    @Bean
    public JobNotifier jobNotifier(TelegramGateway telegram, SourceItemRepository source,
                                   JobRepository jobs, PendingActionRepository actions,
                                   Clock clock) {
        return new TelegramJobNotifier(telegram, source, jobs, actions, clock);
    }

    @Bean
    public JobWorker jobWorker(JobRepository jobs, JobService state, ExtractStep extract,
                               DraftStep draft, RenderStep render, WriteStep write,
                               JobNotifier notifier, UzelokProperties properties) {
        return new JobWorker(jobs, state, extract, draft, render, write, notifier,
                properties.worker().leaseMinutes(), properties.worker().maxAttempts());
    }
}
