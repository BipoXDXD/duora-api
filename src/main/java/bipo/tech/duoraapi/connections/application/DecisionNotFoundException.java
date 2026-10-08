package bipo.tech.duoraapi.connections.application;

/** Quem chama ainda não decidiu nessa rodada, ou não formou par nela. */
public class DecisionNotFoundException extends RuntimeException {

    public DecisionNotFoundException() {
        super("the caller has no decision in this round");
    }

}
