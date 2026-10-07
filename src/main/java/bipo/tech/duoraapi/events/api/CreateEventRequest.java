package bipo.tech.duoraapi.events.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import bipo.tech.duoraapi.events.domain.Capacity;
import bipo.tech.duoraapi.events.domain.EventDescription;
import bipo.tech.duoraapi.events.domain.EventSchedule;
import bipo.tech.duoraapi.events.domain.EventTitle;
import bipo.tech.duoraapi.events.domain.InvalidEventException;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Corpo da criação. Só o que o ADMIN define: id, estado e contagem são do servidor, e chave desconhecida
 * é recusada com 400 (spring.jackson.deserialization.fail-on-unknown-properties). Os horários chegam como
 * texto: o Jackson aceitaria um número como segundos da época.
 */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
record CreateEventRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = EventTitle.MAX_LENGTH,
                description = "Título em uma linha, sem caracteres invisíveis; espaços nas pontas são removidos")
        String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = EventDescription.MAX_LENGTH,
                description = "Descrição curta em parágrafos, sem caracteres invisíveis")
        String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time",
                maxLength = ApiSchemas.INSTANT_MAX_LENGTH,
                description = "Início, ISO 8601 com fuso (Z ou -03:00); no futuro e até 365 dias à frente")
        String startsAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time",
                maxLength = ApiSchemas.INSTANT_MAX_LENGTH,
                description = "Fim, ISO 8601 com fuso; depois do início e no máximo 12 horas após ele")
        String endsAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "int32", minimum = "" + Capacity.MIN_PLACES,
                maximum = "" + Capacity.MAX_PLACES, description = "Quantas pessoas podem se inscrever")
        Integer capacity) {

    EventTitle eventTitle() {
        return new EventTitle(title);
    }

    EventDescription eventDescription() {
        return new EventDescription(description);
    }

    EventSchedule schedule() {
        return new EventSchedule(parseInstant("startsAt", startsAt), parseInstant("endsAt", endsAt));
    }

    Capacity eventCapacity() {
        if (capacity == null) {
            throw new InvalidEventException("capacity is required");
        }
        return new Capacity(capacity);
    }

    /** ISO 8601 com fuso explícito (Z ou -03:00): sem ele, o mesmo texto seria um instante em cada servidor. */
    private static Instant parseInstant(String field, String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidEventException(field + " must be a date and time with offset, like 2026-11-01T22:00:00Z");
        }
    }

}
