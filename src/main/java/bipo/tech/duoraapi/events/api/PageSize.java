package bipo.tech.duoraapi.events.api;

import bipo.tech.duoraapi.config.MaxPageSize;

/** Os limites do {@code maxPageSize} das listas de eventos: o padrão quando o cliente não diz, e o teto. */
final class PageSize {

    static final int DEFAULT = 10;
    /** A tabela do ADMIN mostra mais linhas por tela que a lista do participante. */
    static final int ADMIN_DEFAULT = 20;
    static final int MAX = 50;

    private PageSize() {
    }

    static int of(String requested) {
        return of(requested, DEFAULT);
    }

    /** @throws bipo.tech.duoraapi.config.InvalidPageParameterException fora de 1 a {@link #MAX} */
    static int of(String requested, int defaultSize) {
        return MaxPageSize.parse(requested, defaultSize, MAX);
    }

}
