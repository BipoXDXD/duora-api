package bipo.tech.duoraapi.config;

/** Não deu para contar o limite (banco fora, timeout): indisponibilidade, e não defeito. */
public class RateLimitUnavailableException extends RuntimeException {

    public RateLimitUnavailableException(Throwable cause) {
        super("rate limit store unavailable", cause);
    }

}
