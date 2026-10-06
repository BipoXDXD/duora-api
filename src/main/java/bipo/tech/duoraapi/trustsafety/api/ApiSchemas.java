package bipo.tech.duoraapi.trustsafety.api;

/** Tetos que o lint OWASP da spec exige em todo texto (docs/adr/0012). */
final class ApiSchemas {

    /** Um UUID no formato canônico, com hífens. */
    static final int UUID_LENGTH = 36;

    /** Um instante ISO 8601 em UTC com nanossegundos: 2026-10-05T12:00:00.123456789Z tem 30. */
    static final int INSTANT_MAX_LENGTH = 35;

    private ApiSchemas() {
    }

}
