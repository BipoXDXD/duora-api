package bipo.tech.duoraapi.trustsafety.application;

/** Não deu para contar a cota (banco fora, timeout): indisponibilidade, e não defeito. */
public class ReportQuotaUnavailableException extends RuntimeException {

    public ReportQuotaUnavailableException(Throwable cause) {
        super("report quota store unavailable", cause);
    }

}
