package bipo.tech.duoraapi.profiles.domain;

/**
 * Região aproximada: a unidade da federação, pelo código ISO 3166-2. Lista fechada de propósito: texto
 * livre poderia trazer endereço, e o Duora não guarda localização precisa.
 */
public enum Region {

    AC("BR-AC"), AL("BR-AL"), AP("BR-AP"), AM("BR-AM"), BA("BR-BA"), CE("BR-CE"), DF("BR-DF"),
    ES("BR-ES"), GO("BR-GO"), MA("BR-MA"), MT("BR-MT"), MS("BR-MS"), MG("BR-MG"), PA("BR-PA"),
    PB("BR-PB"), PR("BR-PR"), PE("BR-PE"), PI("BR-PI"), RJ("BR-RJ"), RN("BR-RN"), RS("BR-RS"),
    RO("BR-RO"), RR("BR-RR"), SC("BR-SC"), SP("BR-SP"), SE("BR-SE"), TO("BR-TO");

    /** Valor gravado no banco e trocado com o cliente; não muda se a constante for renomeada. */
    private final String code;

    Region(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Region fromCode(String code) {
        for (Region region : values()) {
            if (region.code.equals(code)) {
                return region;
            }
        }
        throw new InvalidProfileException("region must be the ISO 3166-2 code of a Brazilian state, like BR-SP");
    }

}
