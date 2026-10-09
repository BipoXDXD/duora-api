package bipo.tech.duoraapi.config;

/**
 * O {@code maxPageSize} da query das listas paginadas (docs/adr/0005): o padrão da lista quando o cliente não
 * diz, de 1 ao teto da lista quando diz. Lido como texto porque o Spring trataria {@code maxPageSize=} vazio
 * como ausente, e o contrato o recusa como qualquer valor que não é inteiro.
 */
public final class MaxPageSize {

    private MaxPageSize() {
    }

    /**
     * @param text o valor da query, ou null quando ausente
     * @throws InvalidPageParameterException se o valor não é um inteiro de 1 a {@code max}
     */
    public static int parse(String text, int defaultSize, int max) {
        if (text == null) {
            return defaultSize;
        }
        try {
            int size = Integer.parseInt(text);
            if (size >= 1 && size <= max) {
                return size;
            }
        } catch (NumberFormatException e) {
            // cai no 400 abaixo, como um número fora da faixa
        }
        throw new InvalidPageParameterException("maxPageSize must be between 1 and " + max);
    }

}
