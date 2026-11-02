package uzelok.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uzelok.domain.port.KnowledgeStore;
import uzelok.knowledge.ContentSanitizer;
import uzelok.knowledge.ContractValidator;
import uzelok.knowledge.ExternalObsidianStore;
import uzelok.knowledge.FileNamePolicy;
import uzelok.knowledge.GitIndex;
import uzelok.knowledge.LinterRunner;
import uzelok.knowledge.NameUniqueness;
import uzelok.knowledge.NoteRenderer;
import uzelok.knowledge.VaultLockRegistry;

@Configuration(proxyBeanMethods = false)
public class KnowledgeConfig {

    @Bean
    public ContractValidator contractValidator() {
        return new ContractValidator();
    }

    @Bean
    public NameUniqueness nameUniqueness() {
        return new NameUniqueness(new GitIndex());
    }

    @Bean
    public FileNamePolicy fileNamePolicy(UzelokProperties properties) {
        return new FileNamePolicy(properties.knowledge().maxFileNameLength());
    }

    @Bean
    public ContentSanitizer contentSanitizer() {
        return new ContentSanitizer();
    }

    @Bean
    public NoteRenderer noteRenderer(FileNamePolicy names, ContentSanitizer sanitizer,
                                     UzelokProperties properties) {
        return new NoteRenderer(names, sanitizer, properties.knowledge().confidenceThreshold());
    }

    @Bean
    public LinterRunner linterRunner(UzelokProperties properties) {
        return new LinterRunner(properties.knowledge().lintCommand(),
                properties.knowledge().lintTimeoutSeconds());
    }

    @Bean
    public VaultLockRegistry vaultLockRegistry() {
        return new VaultLockRegistry();
    }

    @Bean
    public KnowledgeStore knowledgeStore(UzelokProperties properties, ContractValidator validator,
                                         NameUniqueness uniqueness, NoteRenderer renderer,
                                         LinterRunner linter, VaultLockRegistry locks, Clock clock) {
        return new ExternalObsidianStore(properties.knowledge(), validator, uniqueness, renderer,
                linter, locks, clock);
    }
}
