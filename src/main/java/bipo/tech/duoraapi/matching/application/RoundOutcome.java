package bipo.tech.duoraapi.matching.application;

import bipo.tech.duoraapi.matching.domain.RoundSummary;

/** A rodada pedida e se este pedido a criou ou ela já existia. */
public record RoundOutcome(RoundSummary round, boolean created) {

    static RoundOutcome created(RoundSummary round) {
        return new RoundOutcome(round, true);
    }

    static RoundOutcome existing(RoundSummary round) {
        return new RoundOutcome(round, false);
    }

}
