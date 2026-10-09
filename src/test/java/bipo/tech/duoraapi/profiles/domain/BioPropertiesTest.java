package bipo.tech.duoraapi.profiles.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import bipo.tech.duoraapi.FreeTextArbitraries;

class BioPropertiesTest {

    /**
     * O perfil é recriado a partir do valor gravado: a bio aceita uma vez precisa ser aceita de novo e sem mudar,
     * ou o perfil gravado deixa de abrir.
     */
    @Property
    void anAcceptedBioIsAcceptedAgainUnchanged(@ForAll("texts") String typed) {
        Optional<Bio> accepted = accepted(typed);
        Assume.that(accepted.isPresent());
        Bio bio = accepted.orElseThrow();

        assertThat(new Bio(bio.value())).isEqualTo(bio);
    }

    @Provide
    Arbitrary<String> texts() {
        return FreeTextArbitraries.paragraphs(Bio.MAX_LENGTH);
    }

    private static Optional<Bio> accepted(String typed) {
        try {
            return Optional.of(new Bio(typed));
        } catch (InvalidProfileException rejected) {
            return Optional.empty();
        }
    }

}
