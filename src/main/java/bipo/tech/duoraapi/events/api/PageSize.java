package bipo.tech.duoraapi.events.api;

/**
 * Quantos itens uma página traz, pelo {@code maxPageSize} da query: o padrão da lista quando o cliente não
 * diz, de 1 a 50 quando diz. Lido como texto porque o Spring trataria {@code maxPageSize=} vazio como ausente, e o
 * contrato o recusa como qualquer valor que não é inteiro (o mesmo de /api/me/blocked-accounts).
 */
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

    static int of(String requested, int defaultSize) {
        if (requested == null) {
            return defaultSize;
        }
        try {
            int size = Integer.parseInt(requested);
            if (size >= 1 && size <= MAX) {
                return size;
            }
        } catch (NumberFormatException e) {
            // cai no 400 abaixo, como um número fora da faixa
        }
        throw new InvalidPageRequestException("maxPageSize must be between 1 and " + MAX);
    }

}
