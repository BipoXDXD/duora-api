# 0002. Front web autenticado por BFF no próprio Spring

- **Status:** Aceita
- **Data:** 2026-10-05
- **Complementa:** [ADR 0001](0001-autenticacao-entra-external-id.md)

## Contexto

O cliente principal do Duora passa a ser um front web (React, TypeScript, Vite e Tailwind CSS,
com uma interface para desktop e outra para smartphone), num repositório separado, `duora-web`.
O app Android em Kotlin ficou para depois. A ADR 0001 partia de clientes nativos e dizia que não
haveria backend-for-frontend; com um app no navegador, a pergunta volta: onde fica o token?

| Opção | Prós | Contras |
|---|---|---|
| **BFF no Spring** | O token nunca chega ao navegador: um XSS não consegue roubá-lo. É o modelo mais seguro da BCP da IETF para apps no navegador. Sem CORS: front e API no mesmo site | A API ganha sessão, CSRF e um segredo de cliente; precisa de armazenamento de sessão compartilhado |
| SPA com MSAL.js e bearer | API inalterada (só CORS); menos peças | Token no navegador, ao alcance de qualquer XSS; mitigação depende de CSP e de token em memória |

## Decisão

O front web se autentica por **BFF no próprio Spring**:

- O Spring é cliente confidencial do Entra External ID (registro `duora-web`) e faz o fluxo
  authorization code com PKCE. O navegador só recebe o cookie de sessão.
- O cookie se chama `__Host-DUORA_SESSION`: `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`, sem
  `Domain`. É `Lax`, e não `Strict`, para voltar no redirect do Entra que conclui o login.
- A sessão fica no PostgreSQL (Spring Session JDBC), para sobreviver a restart e valer entre
  instâncias, e expira após 30 minutos sem uso. O id muda no login.
- Toda mutação exige o token CSRF: cookie `XSRF-TOKEN`, devolvido pelo front no header
  `X-XSRF-TOKEN`. A exceção é a inscrição anônima na waitlist, que não tem sessão de vítima a
  explorar e também é chamada pelo app mobile.
- O logout encerra a sessão no Spring e também no Entra (RP-initiated logout).
- Os papéis vêm do **access token da API** obtido no login, validado pelo mesmo `JwtDecoder` da
  porta bearer. Assim a sessão web e o bearer concedem exatamente os mesmos papéis.
- A API passa a ter **duas portas de entrada** com as mesmas regras de rota: requisições com
  `Authorization: Bearer` vão para a cadeia stateless da ADR 0001; o resto vai para a cadeia de sessão.
- Em desenvolvimento, o Vite faz proxy da API, então front e API ficam na mesma origem
  (`http://localhost:5173`). Em produção, front e API ficam no mesmo site.

**Correção da ADR 0001:** a identidade do usuário é a claim `oid`, e não `sub`. No Entra o `sub`
muda conforme o app que recebe o token (o ID token do `duora-web` e o access token da `duora-api`
trazem `sub`s diferentes para a mesma pessoa), enquanto o `oid` é o mesmo em todo o tenant. As duas
portas de entrada exigem `oid` e o usam como nome do usuário; no login web, o `oid` do ID token
precisa ser igual ao do access token.

## Consequências

- Três variáveis novas e obrigatórias: `DUORA_AUTH_AUTHORITY`, `DUORA_AUTH_WEB_CLIENT_ID` e
  `DUORA_AUTH_WEB_CLIENT_SECRET`. O segredo vence em 180 dias; em desenvolvimento fica em
  `~/.config/duora/dev.env` (gerado por `infra/entra/configure-tenant.sh`) e em produção irá para o
  Key Vault.
- Os atributos da sessão são serializados pelo Java (padrão do Spring Session). Uma mudança
  incompatível nas classes guardadas derruba as sessões abertas, o que só obriga a novo login.
- O registro `duora-desktop` foi removido do tenant; `duora-android` continua para quando o app
  Kotlin for decidido.
- O front não guarda tokens nem conversa com o Entra: leva o usuário a `/oauth2/authorization/entra`
  para entrar e faz `POST /logout`, com o token CSRF, para sair. Como o `fetch` não segue um 302
  para outra origem, o logout responde `200` com `{"logoutUrl": "..."}` (o logout do Entra, com
  `post_logout_redirect_uri`), e o front navega até essa URL. `GET /api/me` diz se há sessão e
  devolve só o nome de exibição.

## Compliance

| Ameaça (STRIDE) | Mitigação | Teste |
|---|---|---|
| Information disclosure: XSS rouba credencial | Token só no servidor; cookie `HttpOnly` | `WebLoginIT.sessionCookieIsHostOnlyHttpOnlySecureAndLax` |
| Spoofing: fixação de sessão | Id da sessão trocado no login; sessão anterior não autentica | `WebLoginIT.loginRedirectsToFrontAndRotatesSessionId`, `preLoginSessionIsUselessAfterLogin` |
| Tampering: CSRF em mutação autenticada | Token CSRF obrigatório | `WebLoginIT.logoutWithoutCsrfTokenIsRejectedAndKeepsSession` |
| Spoofing: ID token de outro tenant, de outro app ou reaproveitado (nonce) | Validação de `iss`, `aud` e `nonce` | `WebLoginIT.rejectsLoginWithInvalidTokens` |
| Spoofing: access token de outra API, de outro usuário ou com chave forjada | Mesmo `JwtDecoder` da porta bearer e `oid` igual nos dois tokens | `WebLoginIT.rejectsLoginWithInvalidTokens` |
| Repudiation/elevation: sessão continua válida após sair | Logout invalida a sessão aqui e no Entra | `WebLoginIT.sessionCookieIsUselessAfterLogout`, `logoutEndsSessionHereAndAnswersTheEntraLogoutUrl` |
| Information disclosure: `/api/me` vaza e-mail, `oid` ou papéis | DTO com allowlist (`displayName`) | `WebLoginIT.currentUserExposesOnlyTheDisplayName`, `BearerTokenValidationIT.currentUserFromBearerTokenExposesOnlyTheDisplayName` |
| Configuração ausente | A subida falha sem as variáveis novas ou com elas em branco | `RequiredAuthenticationSettingsIT` |
