package bipo.tech.duoraapi.connections.domain;

/** A pessoa já decidiu sobre este par, com a outra escolha; a decisão é final (docs/adr/0019). */
public class DecisionAlreadyMadeException extends RuntimeException {

    public DecisionAlreadyMadeException() {
        super("the decision for this round was already made and cannot change");
    }

}
