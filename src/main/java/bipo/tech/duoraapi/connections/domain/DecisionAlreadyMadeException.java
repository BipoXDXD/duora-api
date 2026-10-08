package bipo.tech.duoraapi.connections.domain;

import bipo.tech.duoraapi.ActionRefusedException;
import bipo.tech.duoraapi.RefusalReason;

/** A pessoa já decidiu sobre este par, com a outra escolha; a decisão é final (docs/adr/0019). */
public class DecisionAlreadyMadeException extends ActionRefusedException {

    public DecisionAlreadyMadeException() {
        super(RefusalReason.DECISION_ALREADY_MADE, "the decision for this round was already made and cannot change");
    }

}
