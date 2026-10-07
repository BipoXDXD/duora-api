package bipo.tech.duoraapi.events.api;

import java.time.Instant;
import java.util.UUID;

import bipo.tech.duoraapi.events.application.AdminEventView;
import bipo.tech.duoraapi.events.domain.Capacity;
import bipo.tech.duoraapi.events.domain.EventDescription;
import bipo.tech.duoraapi.events.domain.EventTitle;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * O evento para o ADMIN. {@code status}: DRAFT, PUBLISHED ou CANCELLED; em andamento e encerrado se leem
 * de startsAt e endsAt. {@code registrationCount} é só a contagem: a lista de inscritos não sai da API.
 */
record AdminEventResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = ApiSchemas.UUID_LENGTH,
                maxLength = ApiSchemas.UUID_LENGTH, description = "Id do evento")
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = EventTitle.MAX_LENGTH,
                description = "Título, em uma linha")
        String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = EventDescription.MAX_LENGTH,
                description = "Descrição curta, em parágrafos")
        String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = ApiSchemas.INSTANT_MAX_LENGTH,
                description = "Início, em UTC; o evento começou neste instante")
        Instant startsAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = ApiSchemas.INSTANT_MAX_LENGTH,
                description = "Fim, em UTC; o evento já acabou neste instante")
        Instant endsAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "int32", minimum = "" + Capacity.MIN_PLACES,
                maximum = "" + Capacity.MAX_PLACES, description = "Quantas pessoas podem se inscrever")
        int capacity,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"DRAFT", "PUBLISHED", "CANCELLED"},
                description = "Estado guardado: rascunho, publicado ou cancelado")
        String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "int64", minimum = "0",
                maximum = "" + Capacity.MAX_PLACES, description = "Quantas pessoas se inscreveram")
        long registrationCount) {

    static AdminEventResponse of(AdminEventView event) {
        return new AdminEventResponse(event.id(), event.title(), event.description(), event.startsAt(),
                event.endsAt(), event.capacity(), event.status().name(), event.registrationCount());
    }

}
