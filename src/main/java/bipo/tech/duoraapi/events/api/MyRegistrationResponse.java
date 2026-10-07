package bipo.tech.duoraapi.events.api;

import java.time.Instant;
import java.util.UUID;

import bipo.tech.duoraapi.events.domain.EventTitle;
import bipo.tech.duoraapi.events.domain.RegisteredEvent;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Item de "as minhas inscrições": a referência ao evento e o resumo mínimo para a tela, sem outra
 * requisição por item (docs/adr/0005). {@code eventStatus}: PUBLISHED ou CANCELLED.
 */
record MyRegistrationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = ApiSchemas.UUID_LENGTH,
                maxLength = ApiSchemas.UUID_LENGTH, description = "Id do evento")
        UUID eventId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = EventTitle.MAX_LENGTH,
                description = "Título do evento")
        String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = ApiSchemas.INSTANT_MAX_LENGTH,
                description = "Início do evento, em UTC")
        Instant startsAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = ApiSchemas.INSTANT_MAX_LENGTH,
                description = "Fim do evento, em UTC")
        Instant endsAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"PUBLISHED", "CANCELLED"},
                description = "Estado do evento: um cancelado continua na lista, para a pessoa saber")
        String eventStatus,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = ApiSchemas.INSTANT_MAX_LENGTH,
                description = "Quando a inscrição foi feita")
        Instant registeredAt) {

    static MyRegistrationResponse of(RegisteredEvent registration) {
        return new MyRegistrationResponse(registration.eventId(), registration.title(), registration.startsAt(),
                registration.endsAt(), registration.eventStatus().name(), registration.registeredAt());
    }

}
