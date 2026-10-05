# ADR-0005 — "Conceder acesso" pertence à área do `admin_ente`, não à tela de cadastro

- **Status:** Aceito · 2026-09-22
- **Decisor:** Daouda Traore (CTO) — decisão delegada nesta sessão.
- **Fonte canônica:** §22.5 do `documento-mestre-camaras.md` / `arquitetura/22-5-auth.md` (SSOT).
  Em conflito, a SSOT prevalece.
- **Aplica-se a:** a superfície de concessão de acesso (`apps/frontend/`) e aos gates dos endpoints
  de `identidade`/`cadastros` (`apps/backend/`).

## Contexto

O achado veio de um conserto de testes: `cadastros/vereadores/page.tsx` exige `secretario` na porta
(`<GuardSecretaria>`) **e** exige `admin_ente` por dentro, para mostrar o form "Conceder acesso"
(`podeConcederAcesso`). Só quem acumulasse os **dois** papéis alcançaria a função — e ninguém acumula.

A primeira leitura foi que a semente da demo estava incompleta. Está errada: é **estrutural**.

### O que o backend diz

Os gates são deliberados e estão documentados no próprio código:

| Rota | Papel | Origem |
|---|---|---|
| `GET/POST/PATCH /cadastros/vereadores…` (8 rotas) | `secretario` | `cadastros/diplomat/http/in.clj:199` |
| `PATCH /cadastros/vereadores/:id/identidade` | `admin_ente` | idem, `:218` |
| `POST /identidade/identidades` | `admin_ente` | `identidade/diplomat/http/in.clj:133` |
| `POST /identidade/acessos` | `admin_ente` | idem |

A razão está escrita em `ligar-identidade-handler`:

> Gated `admin_ente` (NÃO 'secretario'): ligar identidade é parte de CONCEDER ACESSO — um 'secretario'
> pode cadastrar um vereador mas **nunca deveria poder ligar a PRÓPRIA identidade a esse cadastro e
> votar**.

Isso é **segregação de funções**, e é uma propriedade de segurança real: quem mantém o cadastro não
pode fabricar para si um acesso de voto. O backend está certo.

### Por que ninguém é `admin_ente` hoje

A cadeia fecha sozinha:

1. `admin_ente` é o único papel que concede acesso.
2. `admin_ente` **não é concedível** pelo próprio fluxo — `identidade/wire/in/acesso.clj:6`:
   *"conceder o papel que concede papéis seria escalada de privilégio por autosserviço"*.
   `papeis-concediveis #{"vereador"}`.
3. O bootstrap previsto é via `admin_sistema`, cujo `diplomat/http/in.clj` tem **zero rotas** (é o stub
   de 3 linhas que o `CLAUDE.md` §3 já registra como frente aberta).

**Logo: nenhum `admin_ente` pode existir na plataforma hoje.** O guard da tela não é a causa — é sintoma.

## Decisão

### 1. Os gates do backend ficam como estão
Nenhuma flexibilização no lado de **escrita**. A segregação de funções é invariante.

### 2. Rejeitada: abrir a porta da tela para `secretario` OU `admin_ente`
Foi a recomendação inicial desta sessão e está **errada**, por dois motivos:

- As **8 rotas de dado** daquela página são `secretario`-only. Um `admin_ente` entraria numa tela que
  não consegue listar nem abrir ficha de ninguém — uma página de 403s.
- Dissolveria na UI exatamente a separação que o backend desenhou de propósito. Um gate de tela que
  contradiz o gate do endpoint não protege nada e ensina o modelo errado a quem lê o código.

### 3. `admin_ente` ganha área própria
É o que a SSOT já decidiu (`arquitetura/22-5-auth.md:44`):

> **A administração do ente não é módulo backend — é área de UI** (§22.10) que compõe esses endpoints
> via HTTP.

O form "Conceder acesso" migra para essa área. `/cadastros/vereadores` volta a ser **só** o cadastro,
e a secretaria deixa de ver uma função que nunca poderia executar.

### 4. Pré-requisitos, nesta ordem
- **P1 — bootstrap do 1º `admin_ente` (bloqueante).** Enquanto `admin_sistema` não tiver rota, a área
  não tem usuário possível. Construir a UI antes disto é construir para ninguém.
  **Resolvido pelo [ADR-0016](0016-operador-da-plataforma-e-registro-de-casas.md) (27/09/2026):** o operador
  provisiona a Casa pelo console e o 1º `admin_ente` nasce convidado, pelo CPF. A área própria do `admin_ente`
  deixa de ser construir para ninguém.
- **P2 — abrir `GET /cadastros/vereadores` e `GET /cadastros/vereadores/:id` a `admin_ente`.**
  Para conceder acesso é preciso escolher a quem. É **leitura**, não escrita: não toca a invariante, que
  é sobre *ligar identidade*. Mesma família do `feat(authz)` que já abriu leitura a vereador/presidente.

### 5. Até P1 e P2 existirem, o form fica onde está — marcado
**Executado (28/09/2026):** P1 e P2 existem, e o form mudou. A área é `/administracao` (`GuardAdminEnte`); quem só
tem `admin_ente` pousa nela depois do login (`destinoPorPapeis`) e a nav interna mostra a esse ator só as entradas
dele. `GET /cadastros/vereadores` e `/:id` aceitam `secretario` OU `admin_ente` (só leitura; as escritas do
cadastro seguem `secretario`, e ligar identidade segue `admin_ente`). `/cadastros/vereadores` voltou a ser só o
cadastro.
Não se move nem se apaga código testado para um destino que ainda não pode existir. Fica no lugar, com
ponteiro para esta ADR, para que ninguém "conserte" abrindo o guard — que é precisamente a correção
errada, e a mesma armadilha que já produziu teste obsoleto neste repositório.

## Consequências

- **A função segue inalcançável pela UI, e isso passa a ser explícito** em vez de acidental. Na
  apresentação guiada ela não existe — não está em nenhum dos 4 atos do `docs/21`.
- O caminho de conceder acesso a um vereador na demo permanece o da semente (`demo/semear-tudo.sh`),
  não a UI.
- Quando P1 entrar, esta ADR é o enunciado da fatia: uma área, três endpoints já prontos e testados,
  mais a abertura de leitura do P2.
- `rotulo-papel.ts` já assume que `secretario` e `admin_ente` não co-ocorrem; esta ADR confirma a
  premissa e explica por quê.

## Adendo — revogar acesso (04/10/2026)

A área do `admin_ente` concedia acesso e não tinha como tirá-lo: quem saía da Casa (mandato encerrado, servidor
desligado, contrato do jurídico acabado) ficava com a porta aberta até alguém mexer no banco.

### Decisão

- **O que se revoga:** o mesmo conjunto que a tela concede — `vereador`, `auditor`, `juridico`. `admin_ente` e
  `secretario` ficam de fora (decisão do fundador; o administrador não tira o próprio papel por esta rota). Consequência:
  **a Casa não perde o último administrador por aqui**, então não há guarda "último admin" a disparar; `papel` fora do
  conjunto é 400 na validação, e há teste.
- **Ato registrado, sem apagar:** `identidade.usuario_papel` ganha `revogado_em`, `revogado_por` e `motivo_revogacao`
  (obrigatório, 3 a 500 caracteres; CHECK no banco). Mesmo padrão da concessão do agente institucional (mig 0096): revogar
  FECHA a linha e conceder de novo ABRE outra. A unicidade (Casa, pessoa, papel) passa a valer só para os ATIVOS (índice
  único parcial); a linha revogada é imutável (trigger) e o `UPDATE` do papel de domínio fica restrito às três colunas.
- **Rotas (`admin_ente`):** `GET /identidade/acessos` (ativos e revogados da Casa do ator, com nome, nunca CPF) e
  `POST /identidade/acessos/:identidade-id/revogacao {papel, motivo}`. 404 para quem não tem esse papel ativo NESTA Casa
  (a RLS esconde a outra: o admin da Casa B não descobre nem revoga nada da A). Ambas ficam em `fora-do-catalogo.edn`
  (`:so-tela`): agente não administra acesso. Escrita passa pela trilha (`com-tentativa` é global).
- **Efeito imediato:** o ator é recalculado a cada requisição (`resolver-sessao` lê vínculo e papéis do banco), a sessão
  não guarda papel — a próxima chamada da pessoa já vem sem o papel. Se não sobrar papel ATIVO nenhum na Casa, a mesma
  tx encerra os vínculos dela (menos o de cidadão) e a sessão passa a dar 401. A credencial delegada do agente (ADR-0010)
  da pessoa nesta Casa é revogada junto. O Keycloak NÃO é tocado: o usuário e o token dele seguem válidos como identidade,
  mas sem vínculo ativo a plataforma não os resolve (fail-closed); conceder de novo reabre.
- **Reconceder:** `POST /identidade/acessos` de sempre. Vínculo `encerrado` (o que a revogação do último papel faz) é
  reaberto pela concessão; `suspenso` continua 409 (é outro ato, com reativação própria).
- **Tela:** "Quem tem acesso" em `/administracao`: lista, "Revogar acesso" com motivo e confirmação, estado revogado
  ("Revogado em … por …", com o motivo) e "Dar o acesso de novo" (vereador e controle interno; o jurídico volta pelo
  formulário próprio, que confirma qualificação e OAB).

### Rollback da migration

O `.down.sql` não pode ressuscitar acesso: antes de derrubar as colunas apaga as linhas revogadas (o mecanismo anterior é
"papel sem linha = sem papel") e recusa rodar (`RAISE EXCEPTION`) se ainda restar alguma. Há teste (up, revoga, down, a
pessoa segue sem o papel). O histórico revogado se perde no rollback; o ato segue na trilha de auditoria.

### Fora desta fatia

- Suspensa, a Casa responde 423 também à revogação (a allowlist da ADR-0018 não a inclui). Revogar durante um incidente é
  um caso a decidir, junto com o acesso de suporte.
- A lista de vereadores do cadastro mostra "Acesso concedido" pelo vínculo do cadastro com a identidade; depois de uma
  revogação o chip segue lá. O estado verdadeiro está em "Quem tem acesso" (o cadastro não carrega o id da identidade).
- Revogar o acesso não encerra o mandato nem altera o cadastro do vereador.
