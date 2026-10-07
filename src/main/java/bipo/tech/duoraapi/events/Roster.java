package bipo.tech.duoraapi.events;

import java.util.List;
import java.util.Objects;

import bipo.tech.duoraapi.identity.AccountId;

/** O que {@link EventRoster} sabe de um evento num instante: se ele existe, se está em andamento e quem foi. */
public sealed interface Roster {

    /** Não há evento com esse id. */
    record UnknownEvent() implements Roster {
    }

    /** O evento existe, mas é rascunho, foi cancelado, ainda não começou ou já acabou. */
    record NotUnderway() implements Roster {
    }

    /** O evento está em andamento; quem está inscrito, na ordem dos ids. */
    record Underway(List<AccountId> registrants) implements Roster {

        public Underway {
            registrants = List.copyOf(Objects.requireNonNull(registrants, "registrants"));
        }

    }

}
