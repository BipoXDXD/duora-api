# 0001. Autenticação com Microsoft Entra External ID e API como OAuth2 Resource Server

- **Status:** Aceita; complementada e corrigida pela [ADR 0002](0002-front-web-com-bff.md)
- **Data:** 2026-10-05

## Contexto

O Duora vai ter dados pessoais, matching, chat e pagamentos, então uma conta comprometida custa
caro. A API precisa saber quem chama e com que papel, sem guardar senha nem implementar login.
O deploy previsto é no Azure, e o projeto é mantido por uma pessoa só.

Alternativas consideradas para emitir as credenciais:

| Opção | Prós | Contras |
|---|---|---|
| **Microsoft Entra External ID** | Gratuito até 50 mil usuários ativos por mês; mesmo ecossistema do deploy no Azure; login social (Google, Facebook, Apple), MFA e telas hospedadas; nada para operar | Console mais complexo que os concorrentes; pouca customização das telas; segredo do login com Apple renovado à mão a cada 6 meses |
| Auth0 | Melhor experiência de desenvolvimento e documentação; telas muito customizáveis | Gratuito só até 25 mil usuários ativos por mês, e o plano pago sobe rápido; fora do Azure |
| Keycloak próprio | Código aberto, controle total, sem custo por usuário | Operar servidor, banco, atualizações e correções de segurança, o que contraria a preferência por serviço gerenciado |
| Login próprio no Spring | Nenhum fornecedor | Guardar senhas e implementar recuperação, MFA, login social e verificação de e-mail: construir autenticação própria |

## Decisão

Usamos o **Microsoft Entra External ID** como provedor de identidade. A API é um **OAuth2
Resource Server** sem sessão: aceita só `Authorization: Bearer <JWT>` e nunca participa do login.

Para cada token, a API valida:

- a assinatura, só `RS256`, com as chaves do JWKS do tenant;
- `iss` igual ao issuer do tenant;
- `aud` contendo o client id da API, para que um ID token ou um token de outro app seja recusado;
- `exp` presente e no futuro, além de `nbf`. O validador padrão aceitaria um token sem `exp`.

Os papéis vêm dos *app roles* do registro da API, na claim `roles`. O valor `ADMIN` vira
`ROLE_ADMIN`.

O issuer e o JWKS são configurados explicitamente, sem descoberta automática. No External ID, o
`iss` dos tokens (`https://{tenant-id}.ciamlogin.com/{tenant-id}/v2.0`) usa outro host que o
documento de descoberta (`https://{subdomínio}.ciamlogin.com/...`), e o Spring recusa essa
divergência.

O motivo técnico: o padrão OIDC deixa a API independente do fornecedor, e trocar de provedor
muda configuração, não código. O motivo de negócio: custo zero na escala atual e nenhum
servidor de identidade para operar.

## Consequências

- Login, cadastro, MFA, recuperação de senha e login social ficam com o Entra. A API não guarda
  credenciais.
- Três variáveis são obrigatórias (`DUORA_AUTH_ISSUER_URI`, `DUORA_AUTH_JWK_SET_URI`,
  `DUORA_AUTH_AUDIENCE`), e sem qualquer uma delas a aplicação não sobe.
- Rodar localmente com tokens de verdade exige um tenant. Sem ele, só as rotas públicas
  funcionam (`spring-boot:test-run` sobe com valores fictícios).
- Trocar de provedor depois exige migrar os usuários, mesmo com a API mudando pouco.
- Os clientes são um app Android (Kotlin, MSAL) e um app desktop (Java, MSAL4J), ambos clientes
  públicos sem segredo, com authorization code + PKCE. Sem front web, não há backend-for-frontend;
  sem iOS, a exigência da App Store de oferecer login com Apple não se aplica.
- O tenant (`duoraapp`) guarda os dados nos Estados Unidos: o Brasil ainda não é um país válido
  para tenant externo, e a escolha não pode ser alterada.
- A configuração do tenant é código: `infra/entra/configure-tenant.sh`.
- O `sub` do token passa a ser a identidade do usuário; quando houver perfil no Duora, ele se liga
  a esse `sub`, nunca ao e-mail, que pode mudar.

## Compliance

| Ameaça (STRIDE) | Mitigação | Teste |
|---|---|---|
| Spoofing: token forjado, `alg:none`/`nonE`, HS256 assinado com a chave pública, outra chave com o mesmo `kid` | Assinatura obrigatória e só `RS256` | `BearerTokenValidationTest.rejectsInvalidToken` |
| Spoofing: token de outro tenant ou emitido para outro app (ID token do front) | Validação de `iss` e `aud` | `BearerTokenValidationTest.rejectsInvalidToken` |
| Spoofing: token expirado ou sem expiração | Validação de `exp`, que passa a ser obrigatória | `BearerTokenValidationTest.rejectsInvalidToken` |
| Tampering: payload alterado para ganhar `ADMIN` | Assinatura cobre o payload | `BearerTokenValidationTest.rejectsInvalidToken` |
| Elevation of privilege: usuário comum em rota de admin | `hasRole("ADMIN")` a partir da claim `roles` | `BearerTokenValidationTest.forbidsValidTokenWithoutAdminRole`, `WaitlistSecurityTest` |
| Elevation of privilege: rota esquecida fica pública | Negar por padrão, com allowlist explícita | `WaitlistSecurityTest.anonymousIsRejectedOnRoutesOutsideAllowlist` |
| Configuração ausente deixa a API subir sem validar | A subida falha sem as variáveis ou com audience vazia | `RequiredAuthenticationSettingsIT` |
