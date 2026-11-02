package uzelok.config;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import uzelok.domain.port.KnowledgeStore;
import uzelok.domain.port.VaultExporter;
import uzelok.knowledge.ContentSanitizer;
import uzelok.knowledge.ContractValidator;
import uzelok.knowledge.ExportArchiver;
import uzelok.knowledge.ExternalObsidianStore;
import uzelok.knowledge.FileNamePolicy;
import uzelok.knowledge.GitIndex;
import uzelok.knowledge.InternalDirStore;
import uzelok.knowledge.KnowledgeStoreRouter;
import uzelok.knowledge.LinterRunner;
import uzelok.knowledge.NameUniqueness;
import uzelok.knowledge.NoteRenderer;
import uzelok.knowledge.TemplateVault;
import uzelok.knowledge.VaultLockRegistry;
import uzelok.knowledge.VaultOptions;

@Configuration(proxyBeanMethods = false)
public class KnowledgeConfig {

    @Bean
    public ContractValidator contractValidator() {
        return new ContractValidator();
    }

    @Bean
    public VaultOptions vaultOptions(UzelokProperties properties) {
        return new VaultOptions(properties.knowledge().tmpDir(),
                properties.knowledge().confidenceThreshold());
    }

    @Bean
    public TemplateVault templateVault(UzelokProperties properties) {
        return new TemplateVault(Path.of(properties.storage().vaultsDir()));
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
    public ExternalObsidianStore externalObsidianStore(VaultOptions options,
                                                       ContractValidator validator,
                                                       NameUniqueness uniqueness,
                                                       NoteRenderer renderer,
                                                       LinterRunner linter,
                                                       VaultLockRegistry locks,
                                                       Clock clock) {
        return new ExternalObsidianStore(options, validator, uniqueness, renderer,
                linter, locks, clock);
    }

    @Bean
    public InternalDirStore internalDirStore(VaultOptions options,
                                             ContractValidator validator,
                                             NameUniqueness uniqueness,
                                             NoteRenderer renderer,
                                             LinterRunner linter,
                                             VaultLockRegistry locks,
                                             Clock clock) {
        return new InternalDirStore(options, validator, uniqueness, renderer,
                linter, locks, clock);
    }

    @Bean
    @Primary
    public KnowledgeStore knowledgeStore(ExternalObsidianStore external,
                                         InternalDirStore internal) {
        return new KnowledgeStoreRouter(external, internal);
    }

    @Bean
    public VaultExporter vaultExporter(ContractValidator validator,
                                       UzelokProperties properties,
                                       Clock clock) {
        return new ExportArchiver(validator,
                Path.of(properties.storage().exportsDir()),
                properties.knowledge().exportMaxBytes(),
                properties.knowledge().exportMaxFiles(),
                clock);
    }
}
