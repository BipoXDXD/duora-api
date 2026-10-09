package bipo.tech.duoraapi.events.api;

import bipo.tech.duoraapi.config.ApiSchemaConventions;

/** Tetos e textos repetidos da spec das rotas de eventos; o lint OWASP exige limite em todo texto (docs/adr/0012). */
final class ApiSchemas {

    /* As convenções de todas as rotas, com o nome curto que as anotações deste pacote usam. */
    static final String PROBLEM_JSON = ApiSchemaConventions.PROBLEM_JSON;
    static final String PROBLEM_SCHEMA = ApiSchemaConventions.PROBLEM_SCHEMA;
    static final int UUID_LENGTH = ApiSchemaConventions.UUID_LENGTH;
    static final int INSTANT_MAX_LENGTH = ApiSchemaConventions.INSTANT_MAX_LENGTH;
    static final int LOCATION_MAX_LENGTH = ApiSchemaConventions.LOCATION_MAX_LENGTH;

    private ApiSchemas() {
    }

}
