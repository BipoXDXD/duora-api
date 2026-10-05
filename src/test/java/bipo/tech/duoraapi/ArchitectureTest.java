package bipo.tech.duoraapi;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "bipo.tech.duoraapi", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** A regra de negócio não pode mudar quando muda o formato HTTP. */
    @ArchTest
    static final ArchRule domainDoesNotDependOnApi = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..api..");

}
