package bipo.tech.duoraapi;

/**
 * Por que uma ação válida no formato foi recusada pela regra de negócio: o {@code reason} dos 409 e dos
 * 403 de regra (docs/adr/0020). Lista fechada e parte do contrato: os nomes vão para a spec, então renomear
 * ou remover é breaking change, e acrescentar muda o enum da resposta.
 */
public enum RefusalReason {

    /** O evento é rascunho. Hoje não chega ao cliente: para quem não é ADMIN, rascunho responde 404. */
    EVENT_NOT_PUBLISHED,
    /** Publicar um evento que já foi publicado. */
    EVENT_ALREADY_PUBLISHED,
    /** O evento foi cancelado: não aceita inscrição, publicação nem outro cancelamento. */
    EVENT_CANCELLED,
    /** O evento já começou e ainda não acabou: inscrever-se, sair e publicar não valem mais. */
    EVENT_STARTED,
    /** O evento já acabou. */
    EVENT_ENDED,
    /** Todas as vagas do evento estão ocupadas. */
    EVENT_FULL,
    /** Rodada nova só com o evento publicado e dentro do horário. */
    EVENT_NOT_UNDERWAY,
    /** A rodada anterior ainda não existe: as rodadas começam em 1 e seguem uma a uma. */
    ROUND_OUT_OF_SEQUENCE,
    /** Falta nome, data de nascimento ou região no perfil de quem chama. */
    PROFILE_INCOMPLETE,
    /** O perfil de quem chama está preenchido, mas a pessoa ainda não tem 18 anos. */
    UNDERAGE,
    /** A data de nascimento já foi informada e não muda. */
    BIRTH_DATE_ALREADY_SET

}
