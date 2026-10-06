package bipo.tech.duoraapi.events.api;

import java.util.List;
import java.util.function.Function;

import bipo.tech.duoraapi.events.application.ResultPage;

/**
 * Envelope das listas (docs/adr/0005): os itens e o token da próxima página, null quando não há mais.
 * Sem total: contar a lista inteira a cada página custa caro e não serve à tela.
 */
record PageResponse<T>(List<T> items, String nextPageToken) {

    static <V, T> PageResponse<T> of(ResultPage<V> page, Function<V, T> toResponse) {
        return new PageResponse<>(page.items().stream().map(toResponse).toList(),
                page.next().map(PageToken::encode).orElse(null));
    }

}
