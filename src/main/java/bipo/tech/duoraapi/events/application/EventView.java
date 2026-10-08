package bipo.tech.duoraapi.events.application;

import java.time.Instant;
import java.util.OptionalInt;
import java.util.UUID;

import bipo.tech.duoraapi.events.domain.Event;
import bipo.tech.duoraapi.events.domain.EventStatus;

/**
 * O evento como qualquer pessoa logada o vê: sem capacidade, contagem nem inscritos. {@code status} é
 * PUBLISHED ou CANCELLED, porque rascunho não chega até aqui. {@code currentRound} é o número da última
 * rodada iniciada, ou null antes da primeira (docs/adr/0017).
 */
public record EventView(UUID id, String title, String description, Instant startsAt, Instant endsAt,
        EventStatus status, Integer currentRound) {

    /**
     * Para a lista dos que ainda vão começar: rodada só começa com o evento em andamento (docs/adr/0017), então
     * nenhum deles tem uma, e a lista não consulta as rodadas.
     */
    static EventView upcoming(Event event) {
        return of(event, OptionalInt.empty());
    }

    static EventView of(Event event, OptionalInt currentRound) {
        return new EventView(event.id(), event.title().value(), event.description().value(),
                event.schedule().startsAt(), event.schedule().endsAt(), event.status(),
                currentRound.isPresent() ? currentRound.getAsInt() : null);
    }

    PageCursor cursor() {
        return new PageCursor(startsAt, id);
    }

}
