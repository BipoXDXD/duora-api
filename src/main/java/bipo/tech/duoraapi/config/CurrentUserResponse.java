package bipo.tech.duoraapi.config;

/**
 * Só o que o front exibe: e-mail, oid e papéis ficam no servidor. displayName é null quando o
 * usuário não tem nome no Entra.
 */
record CurrentUserResponse(String displayName) {
}
