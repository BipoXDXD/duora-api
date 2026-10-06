package bipo.tech.duoraapi.events.api;

/** Quantos itens uma página traz: 10 quando o cliente não diz, de 1 a 50 quando diz. */
final class PageSize {

    static final int DEFAULT = 10;
    static final int MAX = 50;

    private PageSize() {
    }

    static int of(Integer requested) {
        if (requested == null) {
            return DEFAULT;
        }
        if (requested < 1 || requested > MAX) {
            throw new InvalidPageRequestException("pageSize must be between 1 and " + MAX);
        }
        return requested;
    }

}
