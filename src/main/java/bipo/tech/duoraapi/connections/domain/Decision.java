package bipo.tech.duoraapi.connections.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * A decisão privada de uma pessoa sobre o par de uma rodada: continuar em contato ou não (docs/adr/0019).
 * Uma por pessoa e rodada, e final: só a própria pessoa a lê.
 *
 * @param roundNumber o número da rodada no evento, a partir de 1; o teto é do matching
 * @param partner o par da rodada, como o matching o informou
 */
public record Decision(UUID eventId, int roundNumber, AccountId decider, AccountId partner, boolean interested,
        Instant decidedAt) {

    public Decision {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(decider, "decider");
        Objects.requireNonNull(partner, "partner");
        Objects.requireNonNull(decidedAt, "decidedAt");
        if (roundNumber < 1) {
            throw new IllegalArgumentException("the round number starts at 1");
        }
        if (decider.equals(partner)) {
            throw new IllegalArgumentException("a decision is about another account");
        }
    }

    /**
     * Repetir a mesma escolha é a mesma decisão; mudar de ideia não é aceito.
     *
     * @throws DecisionAlreadyMadeException se a escolha pedida é outra
     */
    public Decision confirm(boolean requestedInterest) {
        if (requestedInterest != interested) {
            throw new DecisionAlreadyMadeException();
        }
        return this;
    }

    /** Se a outra decisão é a do par desta, sobre esta pessoa, na mesma rodada do mesmo evento. */
    boolean answers(Decision other) {
        return eventId.equals(other.eventId) && roundNumber == other.roundNumber
                && decider.equals(other.partner) && partner.equals(other.decider);
    }

}
