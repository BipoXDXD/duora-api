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
  `client_id` e `post_logout_redirect_uri`, sem `id_token_hint`; ver "Logout sem ID token"), e o
  front navega até essa URL. `GET /api/me` diz se há sessão e devolve só o nome de exibição.

## Compliance

| Ameaça (STRIDE) | Mitigação | Teste |
|---|---|---|
| Information disclosure: XSS rouba credencial | Token só no servidor; cookie `HttpOnly`; a URL de logout entregue ao JavaScript não leva o ID token (ver "Logout sem ID token") | `WebLoginIT.sessionCookieIsHostOnlyHttpOnlySecureAndLax`, `logoutResponseDoesNotCarryTheIdToken` |
| Spoofing: fixação de sessão | Id da sessão trocado no login; sessão anterior não autentica | `WebLoginIT.loginRedirectsToFrontAndRotatesSessionId`, `preLoginSessionIsUselessAfterLogin` |
| Tampering: CSRF em mutação autenticada | Token CSRF obrigatório | `WebLoginIT.logoutWithoutCsrfTokenIsRejectedAndKeepsSession` |
| Spoofing: ID token de outro tenant, de outro app ou reaproveitado (nonce) | Validação de `iss`, `aud` e `nonce` | `WebLoginIT.rejectsLoginWithInvalidTokens` |
| Spoofing: access token de outra API, de outro usuário ou com chave forjada | Mesmo `JwtDecoder` da porta bearer e `oid` igual nos dois tokens | `WebLoginIT.rejectsLoginWithInvalidTokens` |
| Repudiation/elevation: sessão continua válida após sair | Logout invalida a sessão aqui e no Entra | `WebLoginIT.sessionCookieIsUselessAfterLogout`, `logoutEndsSessionHereAndAnswersTheEntraLogoutUrl` |
| Information disclosure: `/api/me` vaza e-mail, `oid` ou papéis | DTO com allowlist (`displayName`) | `WebLoginIT.currentUserExposesOnlyTheDisplayName`, `BearerTokenValidationIT.currentUserFromBearerTokenExposesOnlyTheDisplayName` |
| Configuração ausente | A subida falha sem as variáveis novas ou com elas em branco | `RequiredAuthenticationSettingsIT` |

### Logout sem ID token

Decidido em 2026-10-05 (usuário), depois da revisão dos PRs #1 e #2.

**Problema.** Na primeira versão, o `{"logoutUrl": "..."}` do `POST /logout` trazia `id_token_hint`
com o ID token inteiro, assinado pelo Entra e com `name`, `oid` e, se houver, `email`. Antes ele só
passava pelo `Location` de um 302, que o JavaScript não lê numa navegação. Com o JSON, a mitigação
"token só no servidor" da primeira linha da tabela deixava de valer para o ID token: um XSS no
`duora-web` que leia o cookie `XSRF-TOKEN` e chame o logout obteria a PII assinada e um
`id_token_hint` válido. O ID token não autoriza a API (`aud` é outra), mas pode servir de prova de
identidade a quem valide mal a `aud`.

| Opção | Prós | Contras |
|---|---|---|
| Manter o `id_token_hint` no JSON | Nenhuma mudança; o Entra identifica a sessão a encerrar sem perguntar | Entrega PII assinada e um token reutilizável ao JavaScript, ao alcance de um XSS |
| Voltar ao 302, com navegação top-level por formulário `POST` levando o token CSRF em parâmetro | O ID token só passa pelo `Location`, que o JavaScript não lê | Muda o contrato com o front (formulário em vez de `fetch`); o token CSRF passa a ir num parâmetro do corpo, pelo handler XOR do `csrf.spa()`; o ID token continua no histórico e nos logs do Entra como parâmetro de URL |
| **URL sem `id_token_hint`, só com `client_id` e `post_logout_redirect_uri`** | Nenhum token sai do servidor; o contrato com o front não muda | O Entra pode mostrar a seleção de conta ao sair |
| URL com `logout_hint` | Sai sem seleção de conta | Exige ligar a optional claim `login_hint` no registro `duora-web` e guardar o valor na sessão; o valor iria ao JavaScript do mesmo jeito (é opaco, mas identifica o usuário para o Entra) |

**Decisão.** O logout monta a URL do Entra **sem `id_token_hint`**, só com `client_id` e
`post_logout_redirect_uri`. O handler é próprio (`WebLoginConfiguration.entraLogoutSuccessHandler`)
porque o `OidcClientInitiatedLogoutSuccessHandler` do Spring sempre acrescenta o `id_token_hint`
quando o usuário entrou por OIDC.

`logout_hint` fica de fora: depende da optional claim `login_hint`, que precisa ser ligada no
registro do app (mais uma configuração do tenant a manter no `infra/entra/configure-tenant.sh`), e a
documentação da Microsoft não diz se ela existe em tenant externo. O ganho seria só evitar a
seleção de conta.

**Por que o Entra aceita.** Na documentação do Microsoft identity platform, que o External ID usa
para OpenID Connect ("OpenID Connect: Yes" em tenant externo), o `end_session_endpoint` só lista
`post_logout_redirect_uri` (recomendado, e precisa ser um redirect URI registrado no app) e
`logout_hint` (opcional); o exemplo de logout é um `GET` só com `post_logout_redirect_uri`. O
`id_token_hint` nem aparece. O `client_id` vem da especificação OpenID Connect RP-Initiated Logout
1.0: ele é opcional, e o uso mais comum que a especificação cita é justamente
`post_logout_redirect_uri` sem `id_token_hint`, para o provedor saber de que app validar o
redirect. A Microsoft não documenta o `client_id` no logout; o primeiro logout no tenant real
confirma que ele é aceito.

- <https://learn.microsoft.com/entra/identity-platform/v2-protocols-oidc#send-a-sign-out-request>
- <https://learn.microsoft.com/entra/external-id/customers/concept-supported-features-customers#openid-connect-and-oauth2-flows>
- <https://learn.microsoft.com/troubleshoot/entra/entra-id/app-integration/sign-out-of-openid-connect-oauth2-applications-without-user-selection-prompt>
- <https://openid.net/specs/openid-connect-rpinitiated-1_0.html#RPLogout>

**Consequências.**

- Sem o hint, o Entra pode pedir que o usuário escolha a conta a encerrar, sobretudo com mais de
  uma conta no navegador. A sessão do Duora já acabou antes disso, então a escolha só afeta a sessão
  do Entra.
- O comportamento foi confirmado na documentação, não contra o tenant real: o primeiro logout no
  `duoraapp` confere que o Entra volta ao `post_logout_redirect_uri`.
- A URL não depende mais do usuário autenticado: um `POST /logout` sem sessão (mas com o token
  CSRF) também recebe a URL do Entra, o que é inofensivo.

**Teste.** `WebLoginIT.logoutResponseDoesNotCarryTheIdToken` afirma que nem o corpo nem os
cabeçalhos da resposta trazem o payload do ID token emitido no login, nem `id_token_hint`;
`logoutEndsSessionHereAndAnswersTheEntraLogoutUrl` afirma que a URL tem só `client_id` e
`post_logout_redirect_uri`, com os valores certos.
