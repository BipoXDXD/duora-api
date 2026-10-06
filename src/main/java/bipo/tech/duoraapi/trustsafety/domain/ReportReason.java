package bipo.tech.duoraapi.trustsafety.domain;

/**
 * Motivos de denúncia, uma lista fechada para a moderação triar sem ler o texto livre. Mudar a lista
 * exige migration: o CHECK de report.reason repete estes nomes.
 */
public enum ReportReason {

    HARASSMENT,
    HATE_SPEECH,
    SEXUAL_CONTENT,
    VIOLENCE_OR_THREAT,
    SCAM_OR_SPAM,
    FAKE_PROFILE,
    /** Suspeita de menor de idade. O fluxo de proteção ainda está pendente (docs/adr/0012). */
    SUSPECTED_MINOR,
    /** Exige descrição: sozinho não diz nada à moderação. */
    OTHER

}
