package bipo.tech.duoraapi.matching.domain;

/** A rodada anterior ainda não existe: as rodadas de um evento começam em 1 e seguem uma a uma. */
public class RoundOutOfSequenceException extends RuntimeException {

    public RoundOutOfSequenceException() {
        super("the previous round has not started yet");
    }

}
