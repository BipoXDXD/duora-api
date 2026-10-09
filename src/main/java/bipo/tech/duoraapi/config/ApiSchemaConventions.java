package bipo.tech.duoraapi.config;

/**
 * Os tetos e referências que valem para a spec de todas as rotas; o lint OWASP exige limite em todo texto
 * (docs/adr/0012). Cada módulo junta os seus no próprio {@code ApiSchemas}.
 */
public final class ApiSchemaConventions {

    public static final String PROBLEM_JSON = "application/problem+json";
    public static final String PROBLEM_SCHEMA = "#/components/schemas/" + OpenApiConfiguration.PROBLEM_SCHEMA;

    /** Um UUID no formato canônico, com hífens. */
    public static final int UUID_LENGTH = 36;

    /** Um instante ISO 8601 com nanossegundos e fuso: 2026-11-01T22:00:00.123456789-03:00 tem 35. */
    public static final int INSTANT_MAX_LENGTH = 35;

    /** Teto do Location: só a rota da API, um UUID e números, bem abaixo disso. */
    public static final int LOCATION_MAX_LENGTH = 2048;

    private ApiSchemaConventions() {
    }

}
