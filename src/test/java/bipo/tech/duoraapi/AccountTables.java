package bipo.tech.duoraapi;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Limpeza das contas nos testes de integração. As tabelas dos módulos referenciam account com
 * {@code on delete restrict} (docs/adr/0011), então os dados de cada módulo saem antes das contas.
 */
public final class AccountTables {

    private AccountTables() {
    }

    public static void deleteAccountsAndTheirData(JdbcClient jdbcClient) {
        jdbcClient.sql("delete from round_seat").update();
        jdbcClient.sql("delete from round").update();
        jdbcClient.sql("delete from registration").update();
        jdbcClient.sql("delete from event").update();
        jdbcClient.sql("delete from report").update();
        jdbcClient.sql("delete from account_block").update();
        jdbcClient.sql("delete from profile").update();
        jdbcClient.sql("delete from account").update();
    }

}
