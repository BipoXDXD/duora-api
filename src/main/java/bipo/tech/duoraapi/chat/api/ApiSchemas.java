package bipo.tech.duoraapi.chat.api;

import bipo.tech.duoraapi.chat.domain.Chat;
import bipo.tech.duoraapi.matching.Pairings;

/** Tetos e textos repetidos da spec das rotas do chat; o lint OWASP exige limite em todo texto (docs/adr/0012). */
final class ApiSchemas {

    static final String PROBLEM_JSON = "application/problem+json";
    static final String PROBLEM_SCHEMA = "#/components/schemas/ProblemDetail";

    /** Um UUID no formato canônico, com hífens. */
    static final int UUID_LENGTH = 36;

    /** Um instante ISO 8601 com nanossegundos e fuso: 2026-11-01T22:00:00.123456789-03:00 tem 35. */
    static final int INSTANT_MAX_LENGTH = 35;

    /** Teto do Location: só a rota da API, um UUID e dois números, bem abaixo disso. */
    static final int LOCATION_MAX_LENGTH = 2048;

    static final String FIRST_ROUND = "" + Pairings.FIRST_ROUND;
    static final String LAST_ROUND = "" + Pairings.LAST_ROUND;
    static final String MAX_MESSAGES = "" + Chat.MAX_MESSAGES;

    static final String EVENT_ID_DESCRIPTION = "Id do evento";
    static final String ROUND_NUMBER_DESCRIPTION = "Número da rodada no evento, a partir de 1";

    private ApiSchemas() {
    }

}
