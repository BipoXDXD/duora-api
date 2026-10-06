package bipo.tech.duoraapi.waitlist.api;

record JoinWaitlistRequest(String email) {

    /** Em DEBUG, o Spring MVC registra o corpo lido; o e-mail é dado pessoal e não vai para o log. */
    @Override
    public String toString() {
        return "JoinWaitlistRequest[email=<redacted>]";
    }

}
