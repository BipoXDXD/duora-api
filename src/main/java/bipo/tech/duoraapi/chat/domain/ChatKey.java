package bipo.tech.duoraapi.chat.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * O que identifica um chat no negócio: o par que o sorteio juntou numa rodada de um evento (docs/adr/0021).
 *
 * @param roundNumber o número da rodada no evento, a partir de 1; o teto é do matching
 */
public record ChatKey(UUID eventId, int roundNumber, ChatPair pair) {

    public ChatKey {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(pair, "pair");
        if (roundNumber < 1) {
            throw new IllegalArgumentException("the round number starts at 1");
        }
    }

}
