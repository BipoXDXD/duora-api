package bipo.tech.duoraapi.matching.application;

/** O evento não tem rodada com esse número, ou o evento não existe. */
public class RoundNotFoundException extends RuntimeException {

    public RoundNotFoundException() {
        super("round not found");
    }

}
