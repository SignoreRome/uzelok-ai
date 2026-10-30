package uzelok.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uzelok.UzelokApplication;

class LayerRulesTest {

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .importPath(mainClassesLocation());

    @Test
    void domainDependsOnlyOnJdk() {
        classes()
                .that().resideInAPackage("uzelok.domain..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "uzelok.domain..")
                .check(MAIN_CLASSES);
    }

    @Test
    void pipelineDependsOnlyOnDomainAndJdk() {
        classes()
                .that().resideInAPackage("uzelok.pipeline..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "uzelok.domain..", "uzelok.pipeline..")
                .allowEmptyShould(true)
                .check(MAIN_CLASSES);
    }

    @Test
    void peerPackagesDoNotDependOnEachOther() {
        noClasses()
                .that().resideInAPackage("uzelok.telegram..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("uzelok.llm..", "uzelok.knowledge..", "uzelok.persistence..")
                .allowEmptyShould(true)
                .check(MAIN_CLASSES);

        noClasses()
                .that().resideInAPackage("uzelok.llm..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("uzelok.telegram..", "uzelok.knowledge..", "uzelok.persistence..")
                .allowEmptyShould(true)
                .check(MAIN_CLASSES);

        noClasses()
                .that().resideInAPackage("uzelok.knowledge..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("uzelok.telegram..", "uzelok.llm..", "uzelok.persistence..")
                .allowEmptyShould(true)
                .check(MAIN_CLASSES);

        noClasses()
                .that().resideInAPackage("uzelok.persistence..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("uzelok.telegram..", "uzelok.llm..", "uzelok.knowledge..")
                .allowEmptyShould(true)
                .check(MAIN_CLASSES);
    }

    @Test
    void onlyConfigUsesConfigurationPropertiesApi() {
        noClasses()
                .that().resideOutsideOfPackage("uzelok.config..")
                .and().doNotHaveSimpleName(UzelokApplication.class.getSimpleName())
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.boot.context.properties..")
                .check(MAIN_CLASSES);
    }

    @Test
    void componentsAndScheduledClassesLiveInAllowedPackages() {
        classes()
                .that().areMetaAnnotatedWith(Component.class)
                .or().areAnnotatedWith(Scheduled.class)
                .and().doNotHaveSimpleName(UzelokApplication.class.getSimpleName())
                .should().resideInAnyPackage(
                        "uzelok.pipeline..",
                        "uzelok.telegram..",
                        "uzelok.llm..",
                        "uzelok.knowledge..",
                        "uzelok.config..")
                .check(MAIN_CLASSES);
    }

    @Test
    void onlyPersistenceUsesJdbcAndJpa() {
        noClasses()
                .that().resideOutsideOfPackage("uzelok.persistence..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "jakarta.persistence..")
                .check(MAIN_CLASSES);
    }

    @Test
    void onlyLlmAndTelegramUseHttpClients() {
        noClasses()
                .that().resideInAnyPackage(
                        "uzelok.domain..",
                        "uzelok.pipeline..",
                        "uzelok.knowledge..",
                        "uzelok.persistence..",
                        "uzelok.config..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "java.net.http..",
                        "okhttp3..",
                        "org.apache.hc..",
                        "org.springframework.http..",
                        "org.springframework.web..")
                .check(MAIN_CLASSES);
    }

    private static Path mainClassesLocation() {
        try {
            return Path.of(UzelokApplication.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Cannot resolve main classes location", exception);
        }
    }
}
