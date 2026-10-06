package bipo.tech.duoraapi.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Id da conta interna de uma pessoa no Duora. É a API publicada do módulo identity: os outros módulos
 * guardam só este id, e um controller recebe a conta de quem fez a requisição declarando um parâmetro
 * deste tipo, sem anotação (a conta é aberta no primeiro acesso, docs/adr/0011).
 */
public record AccountId(UUID value) {

    public AccountId {
        Objects.requireNonNull(value, "value");
    }

}
