package bipo.tech.duoraapi.profiles.api;

/**
 * Só o que o front exibe: e-mail, oid, id da conta e papéis ficam no servidor. displayName é o nome do
 * Entra, null quando o usuário não tem nome lá. profileComplete diz se o front deve levar ao cadastro
 * do perfil (docs/adr/0011); veio depois, como campo novo, sem mudar o que já existia.
 */
record CurrentUserResponse(String displayName, boolean profileComplete) {
}
