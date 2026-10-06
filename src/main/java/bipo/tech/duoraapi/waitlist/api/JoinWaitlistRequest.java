package bipo.tech.duoraapi.waitlist.api;

import bipo.tech.duoraapi.waitlist.domain.EmailAddress;
import io.swagger.v3.oas.annotations.media.Schema;

/** Campo desconhecido é recusado (spring.jackson.deserialization.fail-on-unknown-properties). */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
record JoinWaitlistRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "email", maxLength = EmailAddress.MAX_LENGTH,
                description = "Endereço de e-mail; espaços nas pontas são ignorados e a caixa é normalizada",
                example = "ana@example.com")
        String email) {

    /** Em DEBUG, o Spring MVC registra o corpo lido; o e-mail é dado pessoal e não vai para o log. */
    @Override
    public String toString() {
        return "JoinWaitlistRequest[email=<redacted>]";
    }

}
