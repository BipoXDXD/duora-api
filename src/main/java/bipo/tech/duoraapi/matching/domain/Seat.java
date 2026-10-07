package bipo.tech.duoraapi.matching.domain;

import java.util.Objects;

import bipo.tech.duoraapi.identity.AccountId;

/** O lugar de uma pessoa numa rodada: com um par ou de fora. */
public sealed interface Seat {

    record Paired(AccountId partner) implements Seat {

        public Paired {
            Objects.requireNonNull(partner, "partner");
        }

    }

    record SittingOut() implements Seat {
    }

}
