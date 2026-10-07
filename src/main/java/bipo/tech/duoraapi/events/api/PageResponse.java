package bipo.tech.duoraapi.events.api;

import java.util.List;
import java.util.function.Function;

import bipo.tech.duoraapi.events.application.ResultPage;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Envelope das listas (docs/adr/0005): os itens e o token da próxima página, null quando não há mais.
 * Sem total: contar a lista inteira a cada página custa caro e não serve à tela.
 */
record PageResponse<T>(
        @ArraySchema(maxItems = PageSize.MAX,
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Itens da página, pelo início do evento"))
        List<T> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                pattern = PageToken.PATTERN, maxLength = PageToken.MAX_LENGTH,
                description = "Token da próxima página, ou null na última")
        String nextPageToken) {

    static <V, T> PageResponse<T> of(ResultPage<V> page, Function<V, T> toResponse) {
        return new PageResponse<>(page.items().stream().map(toResponse).toList(),
                page.next().map(PageToken::encode).orElse(null));
    }

}
