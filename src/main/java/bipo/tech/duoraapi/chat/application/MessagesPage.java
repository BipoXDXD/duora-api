package bipo.tech.duoraapi.chat.application;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

import bipo.tech.duoraapi.chat.domain.ChatMessage;

/**
 * Uma página de mensagens em ordem crescente de sequência.
 *
 * @param nextAfterSeq o cursor da próxima página, quando há mais mensagens já gravadas além desta
 */
public record MessagesPage(List<ChatMessage> messages, OptionalInt nextAfterSeq) {

    public MessagesPage {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(nextAfterSeq, "nextAfterSeq");
    }

    static MessagesPage empty() {
        return new MessagesPage(List.of(), OptionalInt.empty());
    }

    /** @param found até {@code maxPageSize + 1} mensagens: a sobra só diz que há outra página */
    static MessagesPage of(List<ChatMessage> found, int maxPageSize) {
        if (found.size() <= maxPageSize) {
            return new MessagesPage(found, OptionalInt.empty());
        }
        List<ChatMessage> page = found.subList(0, maxPageSize);
        return new MessagesPage(page, OptionalInt.of(page.getLast().seq()));
    }

}
