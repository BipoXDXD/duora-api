package bipo.tech.duoraapi;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.importer.ClassFileImporter;

import bipo.tech.testfixtures.modules.client.UsesInternalType;
import bipo.tech.testfixtures.modules.client.UsesPublishedType;
import bipo.tech.testfixtures.modules.owner.PublishedType;
import bipo.tech.testfixtures.modules.owner.internal.InternalType;

/** A regra de fronteira do ArchitectureTest pega a violação que diz pegar, e não só passa por falta de exemplo. */
class ArchitectureRulesTest {

    private static final String FIXTURE_ROOT = "bipo.tech.testfixtures.modules";

    @Test
    void reachingIntoAnotherModuleInternalsIsRejected() {
        var classes = new ClassFileImporter().importClasses(UsesInternalType.class, InternalType.class, PublishedType.class);

        assertThatThrownBy(() -> ArchitectureTest.modulesUseOnlyPublishedApisOfOtherModules(FIXTURE_ROOT).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(UsesInternalType.class.getName());
    }

    @Test
    void usingAnotherModulePublishedApiIsAllowed() {
        var classes = new ClassFileImporter().importClasses(UsesPublishedType.class, InternalType.class, PublishedType.class);

        assertThatCode(() -> ArchitectureTest.modulesUseOnlyPublishedApisOfOtherModules(FIXTURE_ROOT).check(classes))
                .doesNotThrowAnyException();
    }

}
