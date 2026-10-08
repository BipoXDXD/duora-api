package bipo.tech.duoraapi.connections.api;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Só {@code true} e {@code false} do JSON viram booleano. O Jackson, por padrão, aceitaria {@code 1} e
 * {@code "true"}; aqui eles são o 400 de formato inválido (docs/adr/0018), como qualquer outro tipo errado.
 * O {@code null} não chega aqui: fica com o {@code @NotNull} do campo.
 */
final class StrictBooleanDeserializer extends ValueDeserializer<Boolean> {

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) {
        return switch (parser.currentToken()) {
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            default -> (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
        };
    }

}
