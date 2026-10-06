package bipo.tech.duoraapi.trustsafety.domain;

/** A mensagem vai ao cliente. */
public class SelfBlockException extends RuntimeException {

    public SelfBlockException() {
        super("an account cannot block itself");
    }

}
