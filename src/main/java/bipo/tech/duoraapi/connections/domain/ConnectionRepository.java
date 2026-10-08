package bipo.tech.duoraapi.connections.domain;

import java.util.List;

import bipo.tech.duoraapi.identity.AccountId;

/** As conexões, uma por par normalizado (docs/adr/0019). */
public interface ConnectionRepository {

    /** Grava a conexão; se o par já está conectado, mantém a data da primeira. */
    void addIfAbsent(Connection connection);

    /**
     * As conexões da conta, da mais recente para a mais antiga (a outra conta desempata), a partir da
     * primeira.
     */
    List<ConnectedAccount> findFirstOf(AccountId account, int limit);

    /** Como {@link #findFirstOf}, começando logo depois de {@code after}. */
    List<ConnectedAccount> findOfAfter(AccountId account, ConnectedAccount after, int limit);

}
