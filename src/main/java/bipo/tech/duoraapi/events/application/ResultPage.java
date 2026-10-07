package bipo.tech.duoraapi.events.application;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Uma página de uma lista e, se houver mais itens, onde a próxima começa. */
public final class ResultPage<T> {

    private final List<T> items;
    private final PageCursor next;

    private ResultPage(List<T> items, PageCursor next) {
        this.items = List.copyOf(items);
        this.next = next;
    }

    /**
     * A partir do que a consulta trouxe pedindo um item a mais que o tamanho da página: se o item extra
     * veio, há próxima página, e ela começa depois do último item mostrado.
     */
    static <T> ResultPage<T> fromOneMoreThan(int pageSize, List<T> fetched, Function<T, PageCursor> cursorOf) {
        if (fetched.size() <= pageSize) {
            return new ResultPage<>(fetched, null);
        }
        List<T> shown = fetched.subList(0, pageSize);
        return new ResultPage<>(shown, cursorOf.apply(shown.getLast()));
    }

    public List<T> items() {
        return items;
    }

    public Optional<PageCursor> next() {
        return Optional.ofNullable(next);
    }

}
