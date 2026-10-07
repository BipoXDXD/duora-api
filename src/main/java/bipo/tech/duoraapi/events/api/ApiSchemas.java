package bipo.tech.duoraapi.events.api;

/** Tetos e textos repetidos da spec das rotas de eventos; o lint OWASP exige limite em todo texto (docs/adr/0012). */
final class ApiSchemas {

    static final String PROBLEM_JSON = "application/problem+json";
    static final String PROBLEM_SCHEMA = "#/components/schemas/ProblemDetail";

    /** Um UUID no formato canônico, com hífens. */
    static final int UUID_LENGTH = 36;

    /** Um instante ISO 8601 com nanossegundos e fuso: 2026-11-01T22:00:00.123456789-03:00 tem 35. */
    static final int INSTANT_MAX_LENGTH = 35;

    /** Teto do Location: só a rota da API e um UUID, bem abaixo disso. */
    static final int LOCATION_MAX_LENGTH = 2048;

    private ApiSchemas() {
    }

}
