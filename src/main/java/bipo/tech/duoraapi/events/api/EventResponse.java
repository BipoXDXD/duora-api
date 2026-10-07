package bipo.tech.duoraapi.events.api;

import java.time.Instant;
import java.util.UUID;

import bipo.tech.duoraapi.events.application.EventView;
import bipo.tech.duoraapi.events.domain.EventDescription;
import bipo.tech.duoraapi.events.domain.EventTitle;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * O evento para quem está logado. Sem capacidade, contagem nem inscritos: quem vai a um encontro não
 * aparece para os outros (docs/adr/0016). {@code status}: PUBLISHED ou CANCELLED.
 */
record EventResponse(
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
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"PUBLISHED", "CANCELLED"},
                description = "Publicado ou cancelado; em andamento e encerrado se leem dos horários")
        String status) {

    static EventResponse of(EventView event) {
        return new EventResponse(event.id(), event.title(), event.description(), event.startsAt(), event.endsAt(),
                event.status().name());
    }

}
