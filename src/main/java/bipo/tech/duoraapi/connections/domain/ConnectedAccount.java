package bipo.tech.duoraapi.connections.domain;

import java.time.Instant;
import java.util.Objects;

import bipo.tech.duoraapi.identity.AccountId;

/** Uma conexão vista por um dos lados: só a outra conta e quando a conexão se formou. */
public record ConnectedAccount(AccountId account, Instant connectedAt) {

    public ConnectedAccount {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(connectedAt, "connectedAt");
    }

}
