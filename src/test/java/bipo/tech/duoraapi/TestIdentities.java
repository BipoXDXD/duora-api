package bipo.tech.duoraapi;

/**
 * A identidade externa que os testes usam ao montar o token ou a sessão de quem chama. O iss e o oid formam a
 * conta (docs/adr/0011): o mesmo emissor em todos os testes faz "oid-ana" ser a mesma conta pelo bearer e
 * pela sessão web.
 */
public final class TestIdentities {

    public static final String ISSUER = "https://tenant-id.ciamlogin.example/tenant-id/v2.0";

    private TestIdentities() {
    }

}
