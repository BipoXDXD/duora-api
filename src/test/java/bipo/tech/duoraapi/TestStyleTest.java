package bipo.tech.duoraapi;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoBeans;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBeans;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Estilo de testes da docs/adr/0003: mock só de dependência externa, nunca de classe do próprio sistema. */
@AnalyzeClasses(packages = "bipo.tech.duoraapi", importOptions = ImportOption.OnlyIncludeTests.class)
class TestStyleTest {

    private static final String OWN_CODE = "bipo.tech.duoraapi..";

    /** Slice com serviço mockado testa a conversa entre classes, não o comportamento da API. */
    @ArchTest
    static final ArchRule noWebMvcSlices = noClasses()
            .should().beAnnotatedWith(WebMvcTest.class)
            .because("controllers são testados com @SpringBootTest e serviços reais (docs/adr/0003)");

    @ArchTest
    static final ArchRule springMocksOnlyReplaceExternalDependencies = noFields()
            .that().areAnnotatedWith(MockitoBean.class)
            .or().areAnnotatedWith(MockitoSpyBean.class)
            .should().haveRawType(resideInAPackage(OWN_CODE))
            .because("o banco e os serviços da própria API são reais nos testes (docs/adr/0003)")
            .allowEmptyShould(true);

    /**
     * Na classe, {@code @MockitoBean(types = ...)} troca um bean sem campo, e a regra acima não o vê.
     * Mock de dependência externa vai num campo, onde o tipo fica visível.
     */
    @ArchTest
    static final ArchRule springMocksAreNeverDeclaredOnTheClass = noClasses()
            .should().beAnnotatedWith(MockitoBean.class)
            .orShould().beAnnotatedWith(MockitoBeans.class)
            .orShould().beAnnotatedWith(MockitoSpyBean.class)
            .orShould().beAnnotatedWith(MockitoSpyBeans.class)
            .because("um mock na classe escaparia da regra dos campos (docs/adr/0003)");

    @ArchTest
    static final ArchRule mockitoMocksOnlyReplaceExternalDependencies = noFields()
            .that().areAnnotatedWith(Mock.class)
            .or().areAnnotatedWith(Spy.class)
            .should().haveRawType(resideInAPackage(OWN_CODE))
            .because("o domínio é testado sem mocks (docs/adr/0003)")
            .allowEmptyShould(true);

}
