package bipo.tech.duoraapi.events.api;

import java.util.EnumSet;
import java.util.Set;

import bipo.tech.duoraapi.events.domain.EventStatus;

/**
 * O filtro {@code status} da lista do ADMIN: um dos estados guardados, escrito exatamente como a resposta o
 * escreve, ou nenhum, que vale todos. Lido como texto, como o {@link PageSize}, para o vazio ser recusado em vez
 * de tratado como ausente; um valor repetido ({@code status=A&status=B}) chega ao Spring como "A,B" e também cai
 * no 400.
 */
final class StatusFilter {

    private StatusFilter() {
    }

    static Set<EventStatus> of(String requested) {
        if (requested == null) {
            return EnumSet.allOf(EventStatus.class);
        }
        for (EventStatus status : EventStatus.values()) {
            if (status.name().equals(requested)) {
                return EnumSet.of(status);
            }
        }
        throw new InvalidPageRequestException("status must be one of DRAFT, PUBLISHED, CANCELLED");
    }

}
