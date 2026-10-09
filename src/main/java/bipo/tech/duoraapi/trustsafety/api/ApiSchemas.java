package bipo.tech.duoraapi.trustsafety.api;

import bipo.tech.duoraapi.config.ApiSchemaConventions;

/** Tetos que o lint OWASP da spec exige em todo texto (docs/adr/0012). */
final class ApiSchemas {

    /* As convenções de todas as rotas, com o nome curto que as anotações deste pacote usam. */
    static final int UUID_LENGTH = ApiSchemaConventions.UUID_LENGTH;
    static final int INSTANT_MAX_LENGTH = ApiSchemaConventions.INSTANT_MAX_LENGTH;

    private ApiSchemas() {
    }

}
