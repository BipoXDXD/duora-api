package bipo.tech.duoraapi.connections.api;

import bipo.tech.duoraapi.config.ApiSchemaConventions;
import bipo.tech.duoraapi.matching.Pairings;

/** Tetos e textos repetidos da spec das rotas de conexões; o lint OWASP exige limite em todo texto (docs/adr/0012). */
final class ApiSchemas {

    /* As convenções de todas as rotas, com o nome curto que as anotações deste pacote usam. */
    static final String PROBLEM_JSON = ApiSchemaConventions.PROBLEM_JSON;
    static final String PROBLEM_SCHEMA = ApiSchemaConventions.PROBLEM_SCHEMA;
    static final int UUID_LENGTH = ApiSchemaConventions.UUID_LENGTH;
    static final int INSTANT_MAX_LENGTH = ApiSchemaConventions.INSTANT_MAX_LENGTH;
    static final int LOCATION_MAX_LENGTH = ApiSchemaConventions.LOCATION_MAX_LENGTH;

    static final String FIRST_ROUND = "" + Pairings.FIRST_ROUND;
    static final String LAST_ROUND = "" + Pairings.LAST_ROUND;

    static final String EVENT_ID_DESCRIPTION = "Id do evento";
    static final String ROUND_NUMBER_DESCRIPTION = "Número da rodada no evento, a partir de 1";

    private ApiSchemas() {
    }

}
