package bipo.tech.duoraapi.matching.adapter;

import java.util.OptionalInt;
import java.util.UUID;

import org.springframework.stereotype.Component;

import bipo.tech.duoraapi.events.RoundProgress;
import bipo.tech.duoraapi.matching.application.RoundService;

/**
 * Responde ao events qual é a rodada atual de um evento (docs/adr/0017). Adapter de entrada: o events chama
 * pela interface que ele mesmo publica, e não conhece o matching.
 */
@Component
class EventRoundProgress implements RoundProgress {

    private final RoundService rounds;

    EventRoundProgress(RoundService rounds) {
        this.rounds = rounds;
    }

    @Override
    public OptionalInt latestStartedRoundOf(UUID eventId) {
        return rounds.latestStartedOf(eventId)
                .map(number -> OptionalInt.of(number.value()))
                .orElseGet(OptionalInt::empty);
    }

}
