package bipo.tech.duoraapi.events.api;

/**
 * Quantos itens uma página traz, pelo {@code maxPageSize} da query: 10 quando o cliente não diz, de 1 a
 * 50 quando diz. Lido como texto porque o Spring trataria {@code maxPageSize=} vazio como ausente, e o
 * contrato o recusa como qualquer valor que não é inteiro (o mesmo de /api/me/blocked-accounts).
 */
final class PageSize {

    static final int DEFAULT = 10;
    static final int MAX = 50;

    private PageSize() {
    }

    static int of(String requested) {
        if (requested == null) {
            return DEFAULT;
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
