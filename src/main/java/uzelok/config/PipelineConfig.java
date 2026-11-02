package uzelok.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uzelok.domain.port.ArtifactRepository;
import uzelok.domain.port.AttachmentRepository;
import uzelok.domain.port.JobNotifier;
import uzelok.domain.port.JobRepository;
import uzelok.domain.port.JsonCodec;
import uzelok.domain.port.KnowledgeBaseRepository;
import uzelok.domain.port.KnowledgeStore;
import uzelok.domain.port.LlmCallRepository;
import uzelok.domain.port.LlmGateway;
import uzelok.domain.port.NoteRepository;
import uzelok.domain.port.SourceItemRepository;
import uzelok.domain.port.TelegramGateway;
import uzelok.pipeline.ActionService;
import uzelok.pipeline.DraftStep;
import uzelok.pipeline.ExtractStep;
import uzelok.pipeline.JobService;
import uzelok.pipeline.JobStateMachine;
import uzelok.pipeline.JobWorker;
import uzelok.pipeline.RenderStep;
import uzelok.pipeline.StatsService;
import uzelok.pipeline.WriteStep;
import uzelok.domain.port.PendingActionRepository;
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
                                             UzelokProperties properties) {
        return new ActionDispatcher(
                new ActionService(jobs, state, properties.worker().maxAttempts()));
    }

    @Bean
    public StatsService statsService(NoteRepository notes, JobRepository jobs,
                                     LlmCallRepository calls) {
        return new StatsService(notes, jobs, calls);
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
