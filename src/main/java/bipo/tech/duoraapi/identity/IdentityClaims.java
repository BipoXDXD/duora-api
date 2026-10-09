package bipo.tech.duoraapi.identity;

/** As claims do Entra External ID que identificam a pessoa, nas duas portas de entrada (docs/adr/0002). */
public final class IdentityClaims {

    /** O id do usuário no tenant; com o iss, forma a {@code ExternalIdentity} da conta. */
    public static final String OBJECT_ID = "oid";

    private IdentityClaims() {
    }

}
