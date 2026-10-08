package bipo.tech.duoraapi.profiles;

/** Se a pessoa já pode participar de eventos, ou o que falta (docs/adr/0011, docs/adr/0020). */
public enum Eligibility {

    /** Nome, região e data de nascimento de quem tem 18 anos completos. */
    ELIGIBLE,

    /** Falta nome, data de nascimento ou região. */
    PROFILE_INCOMPLETE,

    /**
     * Tudo preenchido, mas menor de 18 anos. O perfil não aceita essa data pela API, então só acontece com
     * dado que chegou ao banco por outro caminho; a regra vale do mesmo jeito.
     */
    UNDERAGE

}
