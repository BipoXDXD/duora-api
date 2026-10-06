package bipo.tech.duoraapi.events.domain;

/**
 * Estado guardado do evento. "Em andamento" e "encerrado" não são guardados: vêm do horário
 * ({@link EventSchedule}) comparado ao relógio, para nunca ficarem desatualizados.
 */
public enum EventStatus {

    /** Só o ADMIN vê; para os usuários, o evento não existe. */
    DRAFT,

    /** Visível aos usuários e aberto a inscrições até começar ou lotar. */
    PUBLISHED,

    /** Continua visível, para quem se inscreveu saber, mas não aceita inscrições. */
    CANCELLED

}
