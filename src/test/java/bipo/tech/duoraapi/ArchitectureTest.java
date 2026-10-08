package bipo.tech.duoraapi;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.util.Arrays;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

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
    private static final String[] SUPPORTING_MODULES = {"waitlist", "identity", "profiles", "events", "notifications"};

    /** Infraestrutura compartilhada e composition root, sem regra de negócio. */
    private static final String[] INFRASTRUCTURE = {"config", "migration"};

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

    /**
     * Um módulo só enxerga de outro a API publicada: as classes na raiz do pacote do módulo (como
     * {@code identity.AccountId}). As camadas (api, application, domain) são internas, e uma tabela só é
     * lida ou escrita pelo módulo dono (docs/adr/0011).
     */
    @ArchTest
    static final ArchRule modulesUseOnlyPublishedApisOfOtherModules = modulesUseOnlyPublishedApisOfOtherModules(ROOT);

    static ArchRule modulesUseOnlyPublishedApisOfOtherModules(String root) {
        return classes().should(new ArchCondition<JavaClass>("use only the published API of other modules") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String originModule = moduleOf(root, origin.getPackageName());
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    String targetModule = moduleOf(root, targetPackage);
                    boolean internalOfAnotherModule = targetModule != null && !targetModule.equals(originModule)
                            && targetPackage.startsWith(root + "." + targetModule + ".");
                    if (internalOfAnotherModule) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        }).because("módulos conversam por operações explícitas, não pelos internos um do outro (docs/adr/0011)");
    }

    /**
     * Dependência entre módulos num sentido só: hoje matching usa events, profiles e trustsafety, e events usa
     * profiles. Quando o de baixo precisa de algo do de cima, declara a interface e o de cima a implementa,
     * como {@code events.RoundProgress} (docs/adr/0017).
     */
    @ArchTest
    static final ArchRule modulesAreFreeOfCycles = slices().matching(ROOT + ".(*)..")
            .should().beFreeOfCycles()
            .because("um ciclo entre módulos impede separá-los e testá-los um sem o outro (docs/adr/0007)");

    /** O primeiro segmento depois da raiz, ou null fora dela. */
    private static String moduleOf(String root, String packageName) {
        if (!packageName.startsWith(root + ".")) {
            return null;
        }
        String relative = packageName.substring(root.length() + 1);
        int dot = relative.indexOf('.');
        return dot < 0 ? relative : relative.substring(0, dot);
    }

    private static Stream<String> packagesOf(String[] modules) {
        return Arrays.stream(modules).map(module -> ROOT + "." + module + "..");
    }

    private static String[] subpackagesOf(String[] modules, String layer) {
        return Arrays.stream(modules).map(module -> ROOT + "." + module + "." + layer + "..").toArray(String[]::new);
    }

}
