package bipo.tech.duoraapi;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.Arrays;
import java.util.stream.Stream;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Estilo de cada módulo conforme a classificação da docs/adr/0007. Módulo novo entra numa das
 * listas abaixo antes de ter código, senão {@link #everyClassBelongsToAClassifiedModule} falha.
 */
@AnalyzeClasses(packages = "bipo.tech.duoraapi", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "bipo.tech.duoraapi";

    /** Subdomínios centrais: ports & adapters, domínio sem framework. */
    private static final String[] CORE_MODULES = {"experiences", "matching", "connections", "trustsafety"};

    /** Subdomínios de apoio: camadas simples (api, application, domain). */
    private static final String[] SUPPORTING_MODULES = {"waitlist", "profiles", "notifications"};

    /** Infraestrutura compartilhada e composition root, sem regra de negócio. */
    private static final String[] INFRASTRUCTURE = {"config"};

    @ArchTest
    static final ArchRule everyClassBelongsToAClassifiedModule = classes()
            .should().resideInAnyPackage(Stream.of(
                            Stream.of(ROOT),
                            packagesOf(CORE_MODULES),
                            packagesOf(SUPPORTING_MODULES),
                            packagesOf(INFRASTRUCTURE))
                    .flatMap(packages -> packages)
                    .toArray(String[]::new))
            .because("todo módulo é classificado como core ou supporting antes de ganhar código (docs/adr/0007)");

    /** A regra de negócio não pode mudar quando muda o formato HTTP. */
    @ArchTest
    static final ArchRule domainDependsOnNoOuterLayer = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..api..", "..application..", "..adapter..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnDelivery = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..api..", "..adapter..");

    /**
     * No core, o domínio é Java puro: Spring, Spring Data, web e SDKs ficam nos adapters. Anotações
     * jakarta.persistence são toleradas, porque entidade e modelo de persistência são o mesmo objeto.
     */
    @ArchTest
    static final ArchRule coreDomainIsFrameworkFree = noClasses()
            .that().resideInAnyPackage(subpackagesOf(CORE_MODULES, "domain"))
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.servlet..", "jakarta.transaction..", "com.azure..",
                    "io.github.bucket4j..", "com.fasterxml.jackson..", "tools.jackson..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule coreApplicationTalksToInfrastructureThroughPorts = noClasses()
            .that().resideInAnyPackage(subpackagesOf(CORE_MODULES, "application"))
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.data..", "org.springframework.web..", "jakarta.servlet..", "com.azure..")
            .allowEmptyShould(true);

    private static Stream<String> packagesOf(String[] modules) {
        return Arrays.stream(modules).map(module -> ROOT + "." + module + "..");
    }

    private static String[] subpackagesOf(String[] modules, String layer) {
        return Arrays.stream(modules).map(module -> ROOT + "." + module + "." + layer + "..").toArray(String[]::new);
    }

}
