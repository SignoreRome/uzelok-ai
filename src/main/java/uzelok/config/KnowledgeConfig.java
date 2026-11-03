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
import uzelok.knowledge.KnowledgeStoreRouter;
import uzelok.knowledge.LinterRunner;
import uzelok.knowledge.NameUniqueness;
import uzelok.knowledge.NoteRenderer;
import uzelok.knowledge.PlainVault;
import uzelok.knowledge.PlainVaultStore;
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
        return new VaultOptions(properties.knowledge().confidenceThreshold());
    }

    @Bean
    public PlainVault plainVault(UzelokProperties properties) {
        return new PlainVault(Path.of(properties.storage().vaultsDir()));
    }

    @Bean
    public NameUniqueness nameUniqueness() {
        return new NameUniqueness();
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
    public PlainVaultStore plainVaultStore(VaultOptions options,
                                           ContractValidator validator,
                                           NameUniqueness uniqueness,
                                           FileNamePolicy names,
                                           ContentSanitizer sanitizer,
                                           LinterRunner linter,
                                           VaultLockRegistry locks,
                                           Clock clock) {
        return new PlainVaultStore(options, validator, uniqueness, names, sanitizer,
                linter, locks, clock);
    }

    @Bean
    @Primary
    public KnowledgeStore knowledgeStore(ExternalObsidianStore external,
                                         PlainVaultStore internal) {
        return new KnowledgeStoreRouter(external, internal);
    }

    @Bean
    public VaultExporter vaultExporter(UzelokProperties properties, Clock clock) {
        return new ExportArchiver(
                Path.of(properties.storage().exportsDir()),
                properties.knowledge().exportMaxBytes(),
                properties.knowledge().exportMaxFiles(),
                clock);
    }
}
