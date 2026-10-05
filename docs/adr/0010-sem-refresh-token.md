# 0010. Login web sem refresh token

- **Status:** Aceita; complementa a [ADR 0002](0002-front-web-com-bff.md)
- **Data:** 2026-10-05

## Contexto

No BFF da [ADR 0002](0002-front-web-com-bff.md), o Spring faz o login no Entra External ID e guarda
a sessão no PostgreSQL. O login pede `openid`, `profile` e o scope da API, sem `offline_access`, então
o Entra não emite refresh token. A sessão (30 minutos sem uso) controla o acesso, e os papéis são
lidos do access token uma vez, no login.

A rotação de refresh token de uso único, que protege contra o replay de um token vazado, não está
disponível no Entra: a documentação do Microsoft identity platform diz que o uso de um refresh
token não revoga os anteriores.

| Opção | Prós | Contras |
|---|---|---|
| **Sem refresh token** (sem `offline_access`) | Nada de longa duração guardado na sessão para vazar | Os papéis só mudam no próximo login; acabada a sessão, o usuário passa de novo pelo Entra (silencioso enquanto a sessão do Entra durar) |
| Pedir `offline_access` e guardar só o último refresh na sessão | Renova o access token sem novo login; papéis atualizados durante a sessão | Credencial de longa duração no banco de sessões; o Entra não invalida os refresh anteriores |

## Decisão

O BFF não pede `offline_access` e não guarda refresh token. A decisão é reaberta se o BFF precisar
chamar uma API externa em nome do usuário depois do login (Microsoft Graph, por exemplo).

O motivo técnico: hoje o BFF não usa o access token depois do login, e um token que não existe não
vaza. O motivo de negócio: uma sessão roubada do Duora (dados pessoais, chat, pagamentos) dura no
máximo o tempo da sessão, não a vida de um refresh token.

## Consequências

- Revogar o papel `ADMIN` de alguém só vale no próximo login dessa pessoa. Até lá, o caminho é
  apagar as sessões dela em `spring_session` (`principal_name` = `oid`).
- Sessão expirada leva a um novo login no Entra, normalmente sem pedir senha.

## Compliance

- `WebLoginIT.loginRequestsNoRefreshToken`: o pedido de autorização traz exatamente `openid`,
  `profile` e o scope da API, sem `offline_access`.
