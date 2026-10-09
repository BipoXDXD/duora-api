package bipo.tech.duoraapi.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Id da conta interna de uma pessoa no Duora. É a API publicada do módulo identity: os outros módulos
 * guardam só este id, e um controller recebe a conta de quem fez a requisição declarando um parâmetro
 * deste tipo, sem anotação (a conta é aberta no primeiro acesso, docs/adr/0011).
 */
public record AccountId(UUID value) implements Comparable<AccountId> {

    public AccountId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * A ordem do texto do UUID, que coincide com a do tipo {@code uuid} no PostgreSQL (bytes sem sinal): é a
     * que normaliza os pares de contas gravados com {@code first < second}. {@link UUID#compareTo} compara
     * com sinal e discordaria do banco.
     */
    @Override
    public int compareTo(AccountId other) {
        return value.toString().compareTo(other.value.toString());
    }

}
