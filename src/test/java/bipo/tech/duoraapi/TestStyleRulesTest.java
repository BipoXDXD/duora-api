package bipo.tech.duoraapi;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.importer.ClassFileImporter;

import bipo.tech.testfixtures.teststyle.ClassLevelMockitoBeanFixture;

/** As regras do TestStyleTest pegam a violação que dizem pegar, e não só passam por falta de exemplo. */
class TestStyleRulesTest {

    @Test
    void classLevelSpringMockIsRejected() {
        var classes = new ClassFileImporter().importClasses(ClassLevelMockitoBeanFixture.class);

        assertThatThrownBy(() -> TestStyleTest.springMocksAreNeverDeclaredOnTheClass.check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(ClassLevelMockitoBeanFixture.class.getName());
    }

}
