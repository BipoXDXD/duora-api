package bipo.tech.duoraapi.matching.domain;

import bipo.tech.duoraapi.ActionRefusedException;
import bipo.tech.duoraapi.RefusalReason;

/** A rodada anterior ainda não existe: as rodadas de um evento começam em 1 e seguem uma a uma. */
public class RoundOutOfSequenceException extends ActionRefusedException {

    public RoundOutOfSequenceException() {
        super(RefusalReason.ROUND_OUT_OF_SEQUENCE, "the previous round has not started yet");
    }

}
