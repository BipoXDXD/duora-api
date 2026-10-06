package bipo.tech.duoraapi.waitlist.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class EmailAddressTest {

    @Test
    void normalizesSurroundingSpacesAndCase() {
        var email = new EmailAddress("  Ana.Silva@Example.COM ");

        assertThat(email.value()).isEqualTo("ana.silva@example.com");
    }

    @Test
    void acceptsAddressAtMaximumLength() {
        var address = "a".repeat(242) + "@example.com";

        assertThat(new EmailAddress(address).value()).hasSize(254);
    }

    @Test
    void rejectsAddressAboveMaximumLength() {
        var address = "a".repeat(243) + "@example.com";

        assertThatThrownBy(() -> new EmailAddress(address))
                .isInstanceOf(InvalidEmailAddressException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "ana", "ana@", "@example.com", "ana@example", "ana@@example.com", "ana silva@example.com"})
    void rejectsMalformedAddress(String value) {
        assertThatThrownBy(() -> new EmailAddress(value))
                .isInstanceOf(InvalidEmailAddressException.class);
    }

    /** Invisíveis: entrariam na lista como lixo e escapariam do UNIQUE com um e-mail igual na tela. */
    @ParameterizedTest
    @ValueSource(strings = {"a\u0000b@example.com", "ana@example.com\u0000", "a\u0001b@example.com",
            "a\u001Fb@example.com", "a\u007Fb@example.com", "a\u00A0b@example.com", "a\u200Bb@example.com",
            "ana@exam\u2028ple.com", "ana@example.c\u3000om"})
    void rejectsControlAndInvisibleCharacters(String value) {
        assertThatThrownBy(() -> new EmailAddress(value))
                .isInstanceOf(InvalidEmailAddressException.class);
    }

    /** Dado pessoal: um log ou mensagem de erro que imprima o objeto não leva o endereço. */
    @Test
    void textRepresentationHidesTheAddress() {
        var email = new EmailAddress("ana.silva@example.com");

        assertThat(email.toString()).doesNotContain("ana.silva", "example.com");
    }

}
