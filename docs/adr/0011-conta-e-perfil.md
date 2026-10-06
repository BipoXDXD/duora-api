# 0011. Conta interna e perfil do próprio usuário

- **Status:** Aceita, provisória. Decidido na sessão autônoma de 2026-10-05; revisar com o usuário.
- **Data:** 2026-10-05
- **Relacionadas:** [ADR 0002](0002-front-web-com-bff.md) (identidade = `oid`),
  [ADR 0004](0004-identificadores-e-unicidade.md), [ADR 0005](0005-contrato-da-api.md),
  [ADR 0007](0007-estilo-por-modulo.md)

## Contexto

A etapa 1 do plano (§3) pede os módulos `identity` e `profiles`: conta, identidade externa, perfil e
elegibilidade. Até aqui a API só conhecia as claims do token; nada no banco representava a pessoa. As
próximas etapas (eventos, pareamento, conexões, moderação) precisam de um id interno estável para
guardar inscrições, decisões e bloqueios, e o produto é 18+, com dado pessoal desde o primeiro campo.

Requisitos que vieram da etapa:

- conta criada no primeiro acesso autenticado, pelas duas portas de entrada (sessão web e bearer),
  identificada por emissor + sujeito (hoje o `oid`), nunca pelo e-mail; criação segura sob
  concorrência;
- perfil do próprio usuário, com leitura e edição parcial, limites em todo campo e DTO estrito;
- elegibilidade 18+ calculada com relógio injetado, nunca guardada como booleano; região aproximada,
  nunca localização precisa.

## Alternativas e decisões

### Recorte dos módulos

| Opção | Prós | Contras |
|---|---|---|
| **Dois módulos: `identity` (conta) e `profiles` (perfil)** | Cada um tem o seu modelo de pessoa: a conta autenticável não é o perfil apresentável. Os próximos módulos dependem só do id da conta, sem conhecer o perfil | Dois módulos e uma regra de fronteira entre eles |
| Um módulo `profiles` com conta e perfil | Menos pacotes | Todo módulo futuro dependeria de `profiles` para saber quem chama; conta e perfil mudam por motivos diferentes (exclusão LGPD, troca de provedor × campos do produto) |

**Decisão:** dois módulos, ambos **supporting** (camadas simples, ADR 0007): nenhum tem regra complexa
o bastante para ports & adapters. As regras 18+ moram no domínio de `profiles`, em Java puro, e são
testadas sem Spring. Se a elegibilidade crescer (verificação de documento, regras por evento),
`profiles` é reclassificado.

A API publicada de cada módulo é a raiz do seu pacote: hoje só `identity.AccountId`. As camadas
(`api`, `application`, `domain`) são internas, e o `ArchitectureTest` passa a cobrar isso de todos os
módulos (`modulesUseOnlyPublishedApisOfOtherModules`).

### Conta: tabela e criação

| Opção | Prós | Contras |
|---|---|---|
| **Tabela `account` com `issuer` e `subject` e `UNIQUE (issuer, subject)`** | Uma linha por pessoa; criação atômica com `insert ... on conflict do nothing` | Uma pessoa com duas identidades (outro provedor) exigiria separar a tabela depois |
| `users` + `external_identities` (como lista o plano §4) | Várias identidades por conta desde já | Duas tabelas na criação: com `on conflict` na segunda, a primeira deixa conta órfã na corrida; nenhum requisito hoje de segunda identidade |

| Momento da criação | Prós | Contras |
|---|---|---|
| **Na primeira rota que declara a conta (`AccountId` no controller)** | Funciona igual nas duas portas; só abre conta quem usa uma rota de usuário | O `GET /api/me` grava no primeiro acesso (efeito interno e idempotente, que o cliente não vê) |
| No sucesso do login web | Uma vez por sessão | Não cobre a porta bearer; exigiria um segundo caminho |
| Filtro em toda requisição autenticada | Cobertura total | Uma consulta a mais até nas rotas que não precisam da conta (admin, logout) |

**Decisão:** tabela única `account (id uuid default uuidv7(), issuer, subject, created_at)`, com
`UNIQUE (issuer, subject)`. Sem e-mail, nome nem papéis: continuam no Entra. A conta é aberta pelo
`CurrentAccountArgumentResolver` quando um controller declara um parâmetro `AccountId`: lê `iss` e
`oid` do principal (o `OidcUser` da sessão ou o `Jwt` do bearer, já validados) e chama
`AccountService.findOrOpenAccount`, que consulta, faz `insert ... on conflict (issuer, subject) do
nothing` e consulta de novo. Com duas requisições simultâneas, o insert da segunda espera o commit da
primeira e não faz nada; a segunda leitura, em READ COMMITTED, já vê a conta.

### Perfil: recurso e contrato

| Opção | Prós | Contras |
|---|---|---|
| **Recurso singular `/api/me/profile`, existente para toda conta (vazio até a primeira edição), com `GET` e `PATCH`** | Sem create nem 201/409 de "já existe"; o front preenche aos poucos; não há rota com id de outro perfil, então não há BOLA a testar por id | O perfil "existe" antes de ter linha no banco; a completude é estado calculado |
| `POST /api/profiles` (create) + `PATCH /api/profiles/{id}` | REST comum | Dois fluxos no front, 409 no segundo create, e o id do perfil na URL convida a tentar o de outra pessoa |

**Decisão:** recurso singular sob `/api/me`, seguindo o prefixo `/api` do código atual (o `/api/v1`
do plano §2 não foi adotado em nenhuma rota até aqui).

- `GET /api/me/profile`: `200` com `{displayName, birthDate, bio, region, complete}`, chaves sempre
  presentes (null quando vazio), e `ETag` forte com a versão (`"0"` para o perfil nunca editado).
- `PATCH /api/me/profile`: JSON Merge Patch de um nível. Campo ausente não muda, `null` apaga, valor
  troca; `""` na bio apaga. O DTO é uma classe com setters, porque o Jackson só chama o setter das
  chaves presentes, inclusive com `null`, e vira um `FieldChange` de três estados (manter, apagar,
  trocar) por campo. Chave desconhecida → `400` (`fail-on-unknown-properties`).
- **`If-Match` obrigatório** (regra geral de segurança do projeto; a ADR 0005 reserva o 412 para esse
  caso): sem ele, `428`; com ETag diferente da versão atual (inclusive fraco, lista ou `*`), `412`. Edições
  simultâneas que passem pela conferência perdem no flush pela versão otimista do JPA (`@Version`),
  também com `412`.
- Erros em `ProblemDetail`: `400` para valor inválido, `409` para trocar a data de nascimento, `412` e
  `428` acima. O `detail` nunca traz o valor recebido.
- O perfil é gravado só na primeira edição: `insert ... on conflict (account_id) do nothing` cria a linha
  vazia na versão 0 dentro da mesma transação, e a edição segue pelo JPA.
- `GET /api/me` ganha `profileComplete` (mudança aditiva para o `duora-web`); `displayName` continua sendo
  o nome do Entra. O id da conta não é exposto: o front não precisa dele hoje.

### Campos e regras do perfil

| Campo | Regra | Por quê |
|---|---|---|
| `displayName` | 1 a 50 caracteres (code points, após NFC e `strip`), uma linha; recusa controle, NUL, invisíveis e controles de direção (aceita o ZWJ dos emojis compostos); não pode ser apagado | Outras pessoas vão ler: invisíveis e controles de direção servem para se passar por outro nome |
| `birthDate` | ISO 8601 estrito; só de maior de idade (18 anos completos) e até 120 anos; informada **uma vez** (trocar → `409`; reenviar a mesma é aceito); não pode ser apagada | Produto 18+. Trocar a data depois liberaria quem mentiu a idade |
| `bio` | Até 300 caracteres, parágrafos com `\n`; mesmas proibições do nome; opcional | Texto livre curto |
| `region` | Lista fechada das 27 UFs em ISO 3166-2 (`BR-SP`); não pode ser apagada | Região aproximada; texto livre poderia trazer endereço |
| `complete` | Calculado: nome, data de maior de idade e região | Nunca guardado |

**Idade:** `AgePolicy.isAdult(birthDate, now)` calcula na hora, com o `Instant` do `Clock` injetado,
no fuso **America/Rio_Branco** (UTC-5, o mais a oeste do Brasil): ninguém completa 18 anos no Duora
antes de completá-los em qualquer parte do país. O nascido em 29 de fevereiro completa anos em 1º de
março nos anos não bissextos (Código Civil, art. 132, § 3º), o que o `Period` já faz.

**Data de menor:** recusada com `400` e **não guardada** (minimização de dados de criança e
adolescente). Consequência: nada impede tentar de novo com outra data; ver pendências.

### Modelo de dados do perfil

`profile (account_id uuid primary key references account on delete restrict, display_name, birth_date,
bio, region, version bigint not null default 0)`, com `CHECK` repetindo os limites como última defesa.

- **PK = `account_id`, sem UUIDv7 próprio**, contrariando a regra geral da ADR 0004: o perfil é 1:1 com
  a conta e nunca aparece numa URL; um segundo id só criaria um mapeamento. Quando listas mostrarem
  outras pessoas, a referência pública será o id da conta.
- **`on delete restrict`** em vez de `cascade`: apagar uma conta vai exigir que cada módulo apague os
  próprios dados (inclusive fora do banco, como fotos no Blob Storage), e uma cascata esconderia isso. A FK
  entre tabelas de módulos diferentes é só integridade: nenhum módulo lê ou escreve a tabela do outro.

## Pendente com o usuário (decisões críticas, só o mínimo implementado)

1. **Verificação real de idade.** Hoje a data é autodeclarada e o login social não comprova maioridade
   (plano §7). Falta decidir se e como verificar (documento, selfie, provedor terceirizado), em que
   momento (cadastro, primeiro evento) e o que acontece com quem não passa.
2. **Retenção e exclusão (LGPD).** Não há fluxo de exclusão de conta, prazo de retenção de conta e
   perfil, nem política para backups. A FK `restrict` obriga esse fluxo a passar por cada módulo. Base
   legal e consentimento para os dados do perfil (`consent_records` do plano §4) também estão em aberto.
3. **Suspeita de menor.** O plano §7 pede um fluxo de proteção. Hoje a data de menor só é recusada,
   sem registro: a pessoa pode tentar de novo com outra data. Guardar a tentativa permitiria bloquear,
   mas é guardar dado de menor. Decidir com apoio jurídico.
4. **Correção da data de nascimento.** Fica travada depois de informada (`409`). Um erro de digitação
   precisa de um canal (suporte, revisão manual), ainda não definido.

## Consequências

- Toda rota que declara `AccountId` faz uma consulta por requisição; a primeira de cada pessoa também
  insere. Sem cache por enquanto: medir no k6 antes de otimizar.
- Os próximos módulos recebem a conta só pelo parâmetro `AccountId`; o id é o único dado de `identity`
  que eles guardam.
- O front precisa ler o `ETag` do `GET` e devolvê-lo no `If-Match` do `PATCH`; em `412`, ler de novo e
  reaplicar.
- Trocar de provedor de identidade muda o `issuer` e o `subject`: exigiria migrar as contas, como a
  ADR 0001 já previa.
- `identity` depende de `config` só pelo nome da claim (`WebLoginConfiguration.OBJECT_ID_CLAIM`), para a
  identidade ter uma fonte só.

## Compliance

STRIDE do fluxo (dado pessoal):

| Ameaça | Mitigação | Teste |
|---|---|---|
| Spoofing: conta ligada pelo e-mail, herdada por quem registra o e-mail de outra pessoa no Entra | Conta pela identidade externa (emissor + `oid`); e-mail não é guardado | `AccountProvisioningIT.newEmailForTheSameObjectIdKeepsTheAccount`, `sameEmailForAnotherObjectIdOpensAnotherAccount`, `sameObjectIdFromAnotherIssuerOpensAnotherAccount` |
| Spoofing: a mesma pessoa com duas contas (corrida no primeiro acesso, ou sessão × bearer), dividindo perfil e futuros bloqueios | `UNIQUE (issuer, subject)` + `insert ... on conflict do nothing` | `AccountProvisioningIT.concurrentFirstRequestsOpenASingleAccount`, `webSessionAndBearerTokenOfTheSamePersonShareTheAccount`, `laterRequestsKeepTheSameAccount` |
| Spoofing: nome de exibição com invisíveis ou controles de direção para se passar por outra pessoa | Allowlist de caracteres no `DisplayName` | `DisplayNameTest.rejectsControlAndInvisibleCharacters`, `ProfileIT.invalidInputIsRejectedWithoutWriting` |
| Tampering: CSRF edita o perfil pela sessão web | Token CSRF obrigatório no `PATCH` | `ProfileIT.webSessionEditWithoutCsrfTokenIsRejectedWithoutWriting` |
| Tampering: mass assignment (`complete`, `version`, `accountId`) | DTO só com os quatro campos editáveis; chave desconhecida → 400 | `ProfileIT.unknownFieldIsRejectedWithoutWriting` |
| Tampering: edição de uma aba apaga em silêncio a de outra (lost update) | `If-Match` obrigatório + `@Version` | `ProfileIT.editWithoutIfMatchIsRejectedWithoutWriting`, `editBasedOnAnOutdatedReadIsRejectedAndKeepsTheNewerEdit`, `ifMatchThatIsNotExactlyTheCurrentETagIsRejected`, `concurrentEditsFromTheSameVersionKeepOnlyOne` |
| Elevation of privilege: menor declara a própria data, ou troca a data depois para passar do 18+ | Só data de maior, gravada uma vez (verificação real pendente) | `ProfileTest.birthDateOfAMinorIsRejected`, `birthDateCannotChangeOnceSet`, `AgePolicyTest`, `ProfileIT.changingTheBirthDateIsAConflict` |
| Information disclosure: usuário B lê ou edita o perfil de A (BOLA) | Sem rota com id de perfil; o serviço só recebe a conta autenticada | `ProfileIT.anotherUserNeitherSeesNorChangesTheProfile`, `editByAnotherUserChangesOnlyTheirOwnProfile` |
| Information disclosure: localização precisa | Região só como UF, de lista fechada | `RegionTest.rejectsAnythingOutsideTheStateCodes`, `ProfileIT.invalidInputIsRejectedWithoutWriting` |
| Information disclosure: valor recusado (dado pessoal) ecoa na resposta ou no log | Mensagens de erro sem o valor; `toString` sem PII nos tipos com dado pessoal | `ProfileIT.rejectedValueIsNotEchoedInTheResponseOrTheLog`, `DisplayNameTest.doesNotExposeTheNameInToString`, `BioTest.doesNotExposeTheTextInToString` |
| Information disclosure: `/api/me` expõe id da conta, e-mail ou papéis | Allowlist `displayName` + `profileComplete` | `WebLoginIT.currentUserExposesOnlyDisplayNameAndProfileStatus`, `BearerTokenValidationIT.currentUserFromBearerTokenExposesOnlyDisplayNameAndProfileStatus` |
| Information disclosure: guardar mais do que o necessário | `account` só com emissor, sujeito e data | `AccountProvisioningIT.accountStoresOnlyTheExternalIdentity` |
| Denial of service: entrada inválida ou grande vira 500 | Limites em todo campo; 400 sem gravar | `ProfileIT.invalidInputIsRejectedWithoutWriting`, `sqlInTheNameIsStoredAsPlainText` |
| Elevation of privilege: rota nova pública por engano | Negar por padrão | `DenyByDefaultIT` (inclui `GET` e `PATCH /api/me/profile`), `ProfileIT.anonymousCannotReadOrEditProfiles`, `AccountProvisioningIT.anonymousRequestOpensNoAccount` |

Repudiation (quem mudou o perfil e quando) não é tratada: não há trilha de auditoria de edição. Entra
junto com a moderação, se ela precisar do histórico.

Fronteira entre módulos: `ArchitectureTest.modulesUseOnlyPublishedApisOfOtherModules`, com
`ArchitectureRulesTest` conferindo que a regra pega a violação.
