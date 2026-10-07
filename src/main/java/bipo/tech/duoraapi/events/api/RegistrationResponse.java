package bipo.tech.duoraapi.events.api;

import java.time.Instant;
import java.util.UUID;

import bipo.tech.duoraapi.events.application.RegistrationView;
import io.swagger.v3.oas.annotations.media.Schema;

/** A própria inscrição: o evento e quando ela foi feita. */
record RegistrationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = ApiSchemas.UUID_LENGTH,
                maxLength = ApiSchemas.UUID_LENGTH, description = "Id do evento")
        UUID eventId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = ApiSchemas.INSTANT_MAX_LENGTH,
                description = "Quando a inscrição foi feita; não muda nas repetições do PUT")
        Instant registeredAt) {

    static RegistrationResponse of(RegistrationView registration) {
        return new RegistrationResponse(registration.eventId(), registration.registeredAt());
    }

}
