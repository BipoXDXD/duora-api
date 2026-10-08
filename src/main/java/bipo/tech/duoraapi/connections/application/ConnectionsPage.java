package bipo.tech.duoraapi.connections.application;

import java.util.List;
import java.util.Optional;

import bipo.tech.duoraapi.connections.domain.ConnectedAccount;

/**
 * Uma página das conexões de quem chama.
 *
 * @param next a última conexão mostrada, onde começa a próxima página, ou null na última
 */
public record ConnectionsPage(List<ConnectedAccount> connections, ConnectedAccount next) {

    public ConnectionsPage {
        connections = List.copyOf(connections);
    }

    public Optional<ConnectedAccount> nextPosition() {
        return Optional.ofNullable(next);
    }

}
