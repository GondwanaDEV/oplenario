# ADR-0020 — Comunicados internos da Casa, com setores e prova de leitura

- **Status:** ✅ **Aceita** (02/10/2026). As oito recomendações foram apresentadas ao Daouda eixo a eixo e
  confirmadas ("confirmo"). A implementação segue as fatias abaixo.
- **Origem:** pedido do Daouda (02/10/2026). A Câmara precisa de uma caixa, dentro do sistema e sem servidor de
  e-mail, para mandar comunicados: aviso de sessão, documento, requerimento, um passo a passo. O destino pode ser uma
  pessoa, um setor, todos os setores, um vereador ou uma comissão. E a Câmara precisa **responder por eles**: saber o
  que foi mandado, a quem, e se foi recebido, lido e reconhecido.
- **Já estava previsto:** `produto/16` C65 ("Comunicação interna Mesa↔vereadores (avisos/circulares)", **diferida**:
  "o canal técnico (11.6) entrega o aviso; falta a peça institucional com autoria da Mesa + arquivo + ciência"). Esta
  ADR é essa peça, estendida aos servidores e aos setores.
- **Relacionadas:** documento-mestre v1.33 (GAP 4: a vista de notificações é projeção em `paineis`; "entregue" não é
  "ciente"; a ciência ativa é a prova) e v1.42 (`CienciaRegistrada`); `arquitetura/22-9-stack.md` Eixo 12;
  ADR-0005 (área do `admin_ente`); ADR-0009 (catálogo de ações); ADR-0017 (trilha de auditoria); ADR-0018 (Casa
  suspensa/encerrada).

## Contexto

O que já existia e o que faltava (levantamento de 02/10/2026):

- **A caixa de notificações** (`paineis.notificacao_caixa`, `GET /meu/notificacoes`) é **projeção** de eventos do
  sistema. Só um produtor a usa ("sua proposição virou lei"), a tela existe só na área do vereador, e `lida_em` é uma
  marca que se sobrescreve, não uma prova.
- **Comissão com membros** e **vereador ligado à identidade** existem em `cadastros`.
- **Ciência** existe só para o vereador e só para o parecer publicado (`legislativo.ciencia_vereador`).
- **Setor não existe.** O sistema só tem papéis, e papel é permissão, não lotação.
- **Não existe aviso escrito por uma pessoa**, nem envio a grupo, nem histórico de leitura.

## Decisão

### Eixo 1 — Setor é cadastro da Casa (em `cadastros`)

O `admin_ente` cria os setores da Casa (Secretaria, Jurídico, Protocolo…) em `/administracao` e diz quem é de cada um.
Uma pessoa pode estar em mais de um setor. Setor **não dá permissão**: é endereço. Usar papel como grupo misturaria
quem pode fazer com onde a pessoa está.

### Eixo 2 — A lista de destinatários é congelada no envio

O comunicado guarda **como foi endereçado** (o setor X, a comissão Y) **e** a lista de pessoas que esse endereço
tinha **naquele instante**. Quem entra no setor depois não recebe o antigo; quem sai continua devendo a ciência do
que recebeu. É isso que prova quem deveria ter lido.

### Eixo 3 — Quem pode enviar

- **A uma pessoa ou a um vereador:** qualquer servidor ou vereador da Casa. Cidadão, nunca.
- **A um setor, a todos os setores ou a uma comissão:** só quem tem o papel `secretario` ou `admin_ente`, ou o
  vereador que é membro vigente da **Mesa**. É a "secretaria, presidência, administração" do pedido.
- Quem envia não entra na própria lista.

### Eixo 4 — Três marcas, nunca sobrescritas

Cada marca é a **primeira** ocorrência, gravada com data e hora numa tabela que só recebe inserções:

- **recebido:** o comunicado apareceu na caixa da pessoa. O servidor marca quando entrega a lista da caixa a ela. É o
  "chegou" do sistema e não depende de o navegador avisar;
- **lido:** a pessoa abriu o comunicado. O servidor marca quando entrega o detalhe a um destinatário;
- **ciente:** a pessoa confirmou com um toque ("Estou ciente"). Só existe quando quem enviou marcou **exige ciência**.
  É a única marca que vale como prova de conhecimento (documento-mestre v1.42: só a ciência ativa prova).

Quem enviou vê o **painel de leitura**: "12 de 15 leram, 3 faltam", com o nome, o caminho (setor/comissão) e a hora
de cada marca. A secretaria e o `admin_ente` veem os comunicados de toda a Casa: a Casa responde por eles, não só
quem clicou em enviar.

### Eixo 5 — Cada comunicado é um protocolo

- Número próprio por Casa e ano: **`COM-2026-000123`**.
- **Imutável depois de enviado**, garantido pelo banco. Para corrigir, envia-se um novo que **substitui** o anterior;
  o antigo passa a mostrar "substituído por COM-…", e as marcas dos dois ficam.
- Não se apaga.

### Eixo 6 — Conteúdo

Assunto, texto, anexos e, opcionalmente, um link para algo do sistema: uma sessão, uma proposição ou um protocolo.

### Eixo 7 — Uma caixa só

A caixa da pessoa junta os **comunicados** (este módulo) e os **avisos do sistema** (`paineis`, como hoje), com
filtro. A junção é feita na tela, não no banco: são donos diferentes. A caixa passa a existir **para todos**, não só
para o vereador, e o topo mostra quantos estão por ler.

### Eixo 8 — Sem resposta dentro do comunicado

Responder viraria uma conversa, o que dobra o escopo. Quem precisa responder escreve outro comunicado. Fica para
depois, se o uso pedir.

### Fora, de propósito

- **E-mail e push.** O ledger de entrega (`paineis.notificacao_entrega`) já tem os canais e entra quando houver SMTP.
  **Sem e-mail, quem não entra no sistema não vê o comunicado**: para o dia a dia basta.
- **Convocação oficial de sessão (4.15).** O Regimento exige prova da convocação, e convocação fora do prazo anula a
  sessão. Se "ciente na caixa" vale como essa prova é `[GAP]` jurídico (`22-9-stack.md:118`). A convocação usará este
  mesmo mecanismo quando um advogado confirmar.

## O contrato

### Banco

**`cadastros`** (migration `20261003000180`):

- `cadastros.setor` — `ente_id`, `id`, `nome` (único por Casa, sem diferença de caixa), `ativo` (padrão true),
  `criado_em`. RLS por Casa.
- `cadastros.setor_membro` — `ente_id`, `setor_id`, `identidade_id`, `desde`. Chave `(ente_id, setor_id,
  identidade_id)`. A lotação muda livremente; cada mudança fica na trilha de auditoria.

**`comunicacao`**, um módulo de domínio novo (migrations `20261003000181`…), RLS por Casa em todas as tabelas:

- `comunicacao.comunicado` — `ente_id`, `id`, `ano`, `numero`, `protocolo` (`COM-AAAA-NNNNNN`, único por Casa),
  `remetente_identidade_id`, `remetente_nome` (como era no envio), `assunto`, `corpo`, `exige_ciencia`,
  `ciencia_ate` (prazo, opcional — fatia 3), `substitui_id` (opcional), `objeto_tipo` + `objeto_id` (opcionais —
  fatia 2: `sessao`|`proposicao`|`protocolo`), `enviado_em`. **Sem UPDATE e sem DELETE** (trigger).
- `comunicacao.destino` — como foi endereçado: `ente_id`, `comunicado_id`, `tipo`
  (`pessoa`|`vereador`|`setor`|`comissao`|`todos`), `alvo_id` (opcional; nulo em `todos`), `alvo_nome` (como era no
  envio). Só inserção.
- `comunicacao.destinatario` — a lista congelada: `ente_id`, `comunicado_id`, `identidade_id`, `nome`, `via` (texto
  do caminho: "direto", "setor Jurídico", "Comissão de Finanças", "todos os setores"). Chave `(ente_id,
  comunicado_id, identidade_id)`; quem chega por dois caminhos aparece uma vez, com o primeiro. Só inserção.
- `comunicacao.marca` — `ente_id`, `comunicado_id`, `identidade_id`, `tipo` (`recebido`|`lido`|`ciente`), `em`.
  Única por `(ente_id, comunicado_id, identidade_id, tipo)`; inserção com `ON CONFLICT DO NOTHING`. Só inserção.
  Gravar `lido` grava `recebido` se faltar; gravar `ciente` grava os dois.
- `comunicacao.anexo` (fatia 2) — `ente_id`, `id`, `comunicado_id`, `nome`, `tipo_midia`, `bytes`, `sha256`,
  `chave_objeto` (`comunicados/<ente>/<comunicado>/<id>`). Só inserção. Até 5 anexos de até 10 MB, enviados junto
  com o comunicado.
- A numeração segue o padrão dos protocolos da `participacao`.

**"Todos os setores"** = toda pessoa com vínculo ativo `servidor` ou `admin_ente` na Casa (com ou sem setor). Não
inclui vereadores: para eles, o caminho é a Mesa, uma comissão ou o vereador.

### Rotas

Comunicados (módulo `comunicacao`; todas pedem login de pessoa da Casa, nunca de cidadão):

| Rota | Quem | O que faz |
|---|---|---|
| `GET /comunicados/destinos` | quem pode enviar | as opções do formulário: `setores` [{id, nome, membros}], `comissoes` [{id, nome, membros}], `vereadores` [{id, nome}], `pessoas` [{identidade-id, nome}], `pode-enviar-a-grupos` |
| `POST /comunicados` | quem pode enviar | corpo `{assunto, corpo, exige-ciencia, ciencia-ate?, substitui-id?, objeto?: {tipo, id}, destinos: [{tipo, alvo-id?}]}`. Resolve e congela a lista; 201 com o comunicado. 403 para grupo sem permissão; 422 se a lista resolver vazia |
| `POST /comunicados/:id/anexos` | o remetente, nos primeiros 10 min | um arquivo (multipart). Fatia 2 |
| `GET /comunicados/caixa` | qualquer pessoa da Casa | os comunicados em que a pessoa é destinatária, mais recentes primeiro (50), com as marcas dela; `nao-lidos`, `pendentes-ciencia`. **Grava `recebido`** dos que ainda não tinham |
| `GET /comunicados/:id` | destinatário, remetente, `secretario`, `admin_ente` | o comunicado, os destinos, os anexos, "substituído por". Para o destinatário, **grava `lido`** |
| `POST /comunicados/:id/ciencia` | destinatário, se `exige-ciencia` | grava `ciente`. Idempotente |
| `GET /comunicados/enviados` | qualquer pessoa da Casa | os que a pessoa enviou; `secretario` e `admin_ente` veem os da Casa (`?escopo=casa`). Cada um com `destinatarios`, `recebidos`, `lidos`, `cientes`, `pendentes-vencidos` |
| `GET /comunicados/:id/leitura` | remetente, `secretario`, `admin_ente` | uma linha por destinatário: nome, via, `recebido-em`, `lido-em`, `ciente-em`, `vencido?` |
| `GET /comunicados/:id/anexos/:anexo` | quem pode ver o comunicado | baixa o anexo. Fatia 2 |

Setores (`cadastros`, papel `admin_ente`):

| Rota | O que faz |
|---|---|
| `GET /administracao/setores` | [{id, nome, ativo, membros: [{identidade-id, nome}]}] |
| `POST /administracao/setores` | `{nome}` → cria |
| `PUT /administracao/setores/:id` | `{nome, ativo}` → renomeia ou desativa (o setor desativado some do formulário; os comunicados antigos ficam) |
| `PUT /administracao/setores/:id/membros` | `{identidades: [..]}` → troca a lotação inteira |

O módulo `comunicacao` não importa `cadastros` nem `identidade` (§22.10). O host injeta os seams: resolver um destino
em pessoas, os nomes, e se o ator pode enviar a grupos (papéis + membro vigente da Mesa).

**Casa suspensa (ADR-0018):** enviar comunicado fica bloqueado (423). `POST /comunicados/:id/ciencia` entra na
allowlist, como "marcar notificação como lida". A Casa encerrada responde 410, como tudo.

**Catálogo (ADR-0009):** `enviar_comunicado` (ato), `ler_caixa`, `ler_comunicado`, `ler_leitura_do_comunicado`
(leitura), `registrar_ciencia` (ato). O agente pode **propor** um comunicado (ADR-0012), nunca enviá-lo sozinho.

### Telas

- **Caixa** para todos: interno em `/caixa`; o vereador na aba "Avisos" que já existe. Comunicados e avisos do
  sistema juntos, com filtros Tudo / Não lidos / Para ciência / Do sistema. O topo mostra o número por ler.
- **Escrever comunicado** (`/comunicados/novo`): destinatários por tipo (pessoa, vereador, setor, comissão, todos),
  assunto, texto, "exige ciência" com prazo opcional, anexos e o link para sessão, proposição ou protocolo. Antes de
  enviar, mostra quantas pessoas vão receber.
- **Comunicado** (`/comunicados/[id]`): o texto, os anexos, o link, o botão "Estou ciente" e, para quem pode, o
  painel de leitura.
- **Enviados** (`/comunicados/enviados`): a lista com "lidos x de y" e os pendentes de ciência, com destaque para os
  vencidos.
- **Setores** em `/administracao`.

## Fatias

1. **Fatia 1 — o comunicado:** setores; enviar a pessoa, vereador, setor, todos ou comissão; caixa para todos;
   recebido, lido e ciente; painel de leitura; enviados; substituição.
2. **Fatia 2 — conteúdo e avisos automáticos:**
   - anexos;
   - link para sessão, proposição ou protocolo;
   - os primeiros **avisos automáticos** pela caixa do sistema (`notificacao.requisitada`, canal `in_app`): **pauta
     publicada** → todos os vereadores; **parecer jurídico pedido** → as pessoas com o papel `juridico`.
3. **Fatia 3 — prazo:** ciência com prazo; o painel e os enviados destacam os vencidos; a caixa avisa "ciência
   pendente até DD/MM" no topo. Sem agendador (produção não tem): o vencimento é calculado na leitura, como o selo do
   dia da trilha.

## Consequências

- Primeiro módulo de domínio novo depois da auditoria: entra no import-lint (§22.10), no teste de vazamento de três
  dimensões, no catálogo e na exportação/apagamento da Casa (ADR-0018). O inventário descobre as tabelas novas
  sozinho; os anexos seguem a convenção `<pasta>/<ente>/` e saem no apagamento.
- A caixa do vereador deixa de ser só dele: a mesma tela serve ao interno.
- O comunicado vale como registro de que a Casa **comunicou**. A ciência vale como registro de que a pessoa
  **reconheceu**. Nenhum dos dois é assinatura ICP-Brasil.
