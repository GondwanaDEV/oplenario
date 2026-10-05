# ADR-0021 — Audiência pública e julgamento das contas do Prefeito

- **Status:** ✅ **Aceita** (03/10/2026). As nove recomendações de [`docs/28`](../28-proposta-audiencia-publica-e-julgamento-de-contas.md)
  (A1–A4, B1–B4, B6) foram apresentadas eixo a eixo e confirmadas pelo Daouda ("Confirmo").
- **Origem:** as duas últimas telas da Onda E sem domínio no backend (`audiencia-publica.html`, `julgamento-contas.html`);
  `produto/13` 4.18 e 4.19; `produto/14` G9 e G10 (as duas entram na V1).
- **Relacionadas:** §22.6 eixo A (tipo de sessão com capabilities); §22.7 (motor de compliance, Invariante 4); §22.7.5
  S4 (quórum na guarda, não no envelope de compliance); ADR-0009 (catálogo); ADR-0015 (cidadão pelo gov.br); ADR-0018
  (Casa suspensa); ADR-0019 (regra da Casa como tabela do módulo).
- **Leitura jurídica:** entendimento prático, não parecer. O rito fino de cada LOM/RI vai ao especialista em regimento.

## Decisão

### Parte A — Audiência pública

- **A1 — Tipo de sessão `audiencia_publica`.** Mora em `sessoes`, com capabilities: não delibera, não exige quórum,
  aceita inscrição de cidadão, gera ata, é transmitida. A comissão que promove, o tema, a finalidade e o tempo de fala
  ficam numa tabela 1:1 (`sessoes.audiencia`). Herda pauta, presença, ata, livro de atas e transmissão.
  - **Consequência que corrige um furo:** sessão com `delibera = false` **não abre votação** (hoje abriria). Vale para
    solene e especial também.
  - A audiência **não conta** na assiduidade do vereador e **não é** a "sessão anterior" cuja ata a próxima ordinária lê.
- **A2 — Quem se inscreve.** O cidadão, pelo gov.br, no portal; o nome vem da identidade (não é digitado). A Mesa
  inscreve no dia quem está presente (`presencial_secretaria`). O formulário avisa antes de enviar que a fala é
  pública e entra na ata e na transmissão; quem desiste ou não fala não aparece em lugar público.
- **A3 — Ordem e tempo.** Ordem de inscrição; um tempo único por audiência, definido pela Mesa (padrão 5 min). A tela
  da Mesa chama, cronometra e encerra a fala, com o mesmo cronômetro visual da tribuna. A fala do cidadão fica na
  inscrição (chamada, encerrada, tempo usado); a tabela de falas dos vereadores não muda.
- **A4 — Audiências obrigatórias da LRF.** "Audiência de metas fiscais do quadrimestre" (LRF art. 9 §4: até o fim de
  maio, setembro e fevereiro) vira **regra-dado no motor**, de domínio federal, ligada a toda Casa. Ela é cumprida por
  uma audiência de finalidade `metas_fiscais` daquele quadrimestre, **encerrada e com ata publicada** — a prova para o
  TCE. LDO, LOA e PPA entram como finalidade da audiência, sem obrigação com prazo: a data varia por LOM (`[GAP]`).
- **A5 — Portal.** Lista de próximas e realizadas, a página da audiência com "Quero falar", e depois a ata no livro de
  atas. Fora da V1: fala remota do cidadão e consulta pública estruturada.

### Parte B — Julgamento das contas

- **B1 — Entidade `prestacao_contas` em `legislativo`.** Exercício, responsável (o prefeito **daquele** exercício),
  data de recebimento, processo no TCE, parecer prévio (`favoravel` · `favoravel_com_ressalvas` · `desfavoravel`) e os
  documentos (PDF do parecer e do relatório), **sem cravar valores** na tela. Registrar a prestação do Prefeito
  **protocola o Projeto de Decreto Legislativo**, de autoria da comissão escolhida, que tramita como qualquer outro.
- **B2 — Votação sobre o parecer, 2/3 dos membros para rejeitar.** A pergunta votada é "Rejeitar o parecer prévio do
  TCE?" (Sim = rejeitar). A regra (CF art. 31 §2) é **dado**: uma guarda DSL por classe de matéria
  (`legislativo.regra_votacao_materia`), avaliada pelo mesmo motor ao abrir a votação: quórum
  `maioria_qualificada_2_3` sobre os **membros**, votação **nominal**. Sem 2/3, o parecer prevalece, qualquer que seja
  o placar simples. A ficha e o placar dizem isso em palavras: "O parecer prevalece: 12 votos pela rejeição, eram
  precisos 14."
- **B3 — Defesa do responsável.** A notificação é registrada (data e meio). O prazo de defesa (dias por Casa, padrão
  15, a conferir na LOM) é congelado na notificação. A defesa escrita entra como documento. **A pauta só aceita o PDL
  depois que o prazo vence ou a defesa é juntada** (bloqueio, não aviso).
- **B4 — Prazo para julgar.** Obrigação no motor: N dias (por Casa, padrão 60, a conferir na LOM) contados do
  recebimento; avisa no painel de compliance. O efeito do vencimento é `[GAP]` por LOM: o motor avisa, não decide.
- **B6 — Contas da Mesa.** Só acompanhamento (`tipo = gestao_camara`): processo no TCE, situação e documentos. Sem
  PDL, sem votação. Confirmar com o jurídico se alguma LOM exige deliberação.
- **B5 (sem decisão, já previsto):** o DL é publicado pelo fluxo de norma existente; a comunicação do resultado ao TCE
  fica no `[GAP]` do layout do TCE-CE.

## O contrato

Convenções de sempre: JSON em kebab-case (o frontend camelciza); `ente_id` + RLS em toda tabela; referência entre
módulos por uuid sem FK (§22.10); módulos não se importam — o host injeta as costuras em `rotas.clj`; toda rota nova
entra no catálogo (ADR-0009) ou em `fora-do-catalogo.edn` com motivo. **Pedestal:** literal e curinga no mesmo nível
quebram o roteador — por isso os caminhos abaixo evitam `/x/:id` ao lado de `/x/literal`.

### Banco

**Migration `20261003000182-sessoes-audiencia-publica`**

- `sessoes.sessao`: o CHECK de `tipo_sessao` passa a aceitar `audiencia_publica`. Duas capabilities novas:
  `exige_quorum boolean NOT NULL` (backfill = `delibera`) e `aceita_inscricao_cidadao boolean NOT NULL DEFAULT false`.
  Defaults de `audiencia_publica`: delibera **false**, transmite true, gera ata **true**, voto secreto false, remota
  false, exige quórum **false**, aceita inscrição **true**.
- `sessoes.audiencia` (1:1, `sessao_id` PK/FK): `comissao_id uuid NOT NULL`, `tema text NOT NULL` (≤ 200),
  `local text NULL`, `proposicao_id uuid NULL`, `finalidade` ∈ `tematica|metas_fiscais|ldo|loa|ppa`,
  `referencia text NULL` (só e obrigatória em `metas_fiscais`, formato `AAAA-Q1|Q2|Q3`),
  `tempo_fala_segundos int NOT NULL DEFAULT 300` (60..1800), `inscricoes_abertas boolean NOT NULL DEFAULT true`,
  carimbos.
- `sessoes.inscricao_cidadao`: `id`, `ente_id`, `sessao_id` FK, `protocolo` (`AUD-AAAA-NNNNNN`, sequencial gapless
  `inscricao_audiencia:<ano>`, único por Casa), `origem` ∈ `portal_govbr|presencial_secretaria`, `identidade_id NULL`
  (obrigatória em `portal_govbr`), `nome`, `fala_como` ∈ `individual|entidade|conselho_movimento`, `entidade NULL`
  (obrigatória fora de `individual`), `tema` (≤ 200), `ordem int` (max+1 por sessão), `estado` ∈
  `inscrita|falando|falou|ausente|desistiu`, `chamada_em`, `encerrada_em`, `tempo_usado_segundos`, carimbos.
  Únicos: (`sessao_id`, `ordem`); (`sessao_id`, `identidade_id`) enquanto não desistiu. No máximo uma `falando` por
  sessão. Estados terminais: `falou|ausente|desistiu`.

**Migration `20261003000183-legislativo-prestacao-contas`**

- `legislativo.prestacao_contas`: `id`, `ente_id`, `tipo` ∈ `governo_prefeito|gestao_camara`, `exercicio int`,
  `responsavel text`, `recebida_em date`, `processo_tce text NULL`, `parecer_previo` (obrigatório em
  `governo_prefeito`), `proposicao_id uuid NULL` (o PDL; só governo), `notificado_em date NULL`,
  `notificacao_meio text NULL`, `prazo_defesa_ate date NULL` (congelado na notificação), `defesa_juntada_em
  timestamptz NULL`, `prazo_julgamento_ate date NULL` (congelado no registro), `resultado` ∈
  `parecer_mantido|parecer_rejeitado` NULL, `votacao_id uuid NULL`, `julgada_em timestamptz NULL`,
  `situacao_tce text NULL` (Mesa), carimbos, `lock_version`. Único (`ente_id`, `tipo`, `exercicio`).
- **Estado é derivado** (lógica pura, não coluna): governo → `aguardando_notificacao` → `prazo_de_defesa` →
  `pronta_para_pauta` (prazo vencido **ou** defesa juntada) → `julgada`; Mesa → `acompanhamento`.
- `legislativo.prestacao_contas_documento` (insert-only): `tipo` ∈
  `parecer_previo|relatorio_tce|notificacao|defesa|decisao_tce|outro`, `nome`, `tipo_midia`, `tamanho_bytes`, `sha256`,
  `chave_objeto` (`contas/<ente>/<prestacao>/<doc>`, com CHECK), carimbos. Mesmo padrão de upload dos comunicados
  (multipart, 10 MB, grava no objeto antes da linha e remove se a linha falhar).
- `legislativo.parametro_contas` (1 linha por Casa; ausência = padrão): `prazo_defesa_dias` (1..120, padrão 15),
  `prazo_julgamento_dias` (1..365, padrão 60).
- `legislativo.regra_votacao_materia`: `chave` PK (`contas_prefeito`), `guarda` (expressão DSL), `referencia`
  (`CF art. 31 §2`), sem `ente_id` (vale para todas). Semeada pela migration.

**Migration `20261003000184-compliance-audiencia-e-contas`** (Parte A4/B4, depois das duas acima): o que o motor
precisar para as duas regras novas (templates vigentes e vínculos às Casas existentes, se não forem garantidos em
código no boot).

### Rotas — Parte A (`sessoes`)

| Rota | Quem | O quê |
|---|---|---|
| `POST /sessoes` | secretario | aceita `audiencia {comissao-id, tema, local?, proposicao-id?, finalidade, referencia?, tempo-fala-segundos?}` — obrigatório se e só se `tipo-sessao = audiencia_publica`; a comissão tem de ser vigente da Casa (costura com `cadastros`) |
| `GET /sessoes/:id/audiencia` | secretario, vereador | `AudienciaOut` (abaixo) com a lista `inscricoes` em ordem |
| `PATCH /sessoes/:id/audiencia` | secretario | `{tempo-fala-segundos?, inscricoes-abertas?, local?}` |
| `POST /sessoes/:id/audiencia/inscricoes` | secretario | inscrição presencial `{nome, fala-como, entidade?, tema}` → `InscricaoOut` |
| `POST /sessoes/:id/audiencia/inscricoes/:insc-id/chamada` | secretario | `inscrita → falando` (sessão aberta; uma por vez) |
| `POST /sessoes/:id/audiencia/inscricoes/:insc-id/encerramento` | secretario | `{tempo-usado-segundos}`: `falando → falou` |
| `POST /sessoes/:id/audiencia/inscricoes/:insc-id/ausencia` | secretario | `inscrita → ausente` |
| `GET /portal/casa/:ente/audiencias` | público | `{proximas [ResumoAudiencia], realizadas [ResumoAudiencia]}` (só `transmite_publica`; realizadas = últimas 20) |
| `GET /portal/casa/:ente/audiencias/:sessao-id` | público | `AudienciaPublicaOut` |
| `POST /portal/audiencias/:sessao-id/inscricoes` | autenticado (Casa da sessão) | `{fala-como, entidade?, tema, ciente-publicidade: true}` → 201 `{protocolo, recibo-em, ordem}`; 409 se já inscrito ou inscrições fechadas |
| `GET /portal/minhas-inscricoes` | autenticado | `{inscricoes [{id, protocolo, sessao-id, tema, comissao-nome, agendada-para, ordem, estado, recibo-em}]}` |
| `POST /portal/minhas-inscricoes/:id/desistencia` | autenticado, dono | `inscrita → desistiu` |

- `InscricaoOut`: `{id, protocolo, ordem, nome, fala-como, entidade?, tema, origem, estado, chamada-em?, encerrada-em?,
  tempo-usado-segundos?}`.
- `AudienciaOut`: `{sessao-id, numero, estado, agendada-para, modalidade, comissao {id, nome}, tema, local?,
  proposicao {id, rotulo, ementa}?, finalidade, referencia?, tempo-fala-segundos, inscricoes-abertas, inscricoes [..]}`.
- `ResumoAudiencia`: `{sessao-id, tema, comissao-nome, agendada-para, estado, local?, finalidade}`.
- `AudienciaPublicaOut`: `ResumoAudiencia` + `{modalidade, proposicao {id, rotulo, ementa}?, referencia?,
  tempo-fala-segundos, inscricoes-abertas (efetivo: flag ∧ estado agendada|aberta|suspensa), inscritos (contagem,
  sem desistentes), ata-publicada, falaram [{nome, fala-como, entidade?}] (só depois de encerrada)}`.
- Inscrições do cidadão **não** entram na allowlist da Casa suspensa (não são protocolo com prazo legal: 423).
- Votação em sessão com `delibera = false` → 409 "esta sessão não delibera".
- `GET /sessoes/:id/quorum` passa a dizer `exige-quorum`.

### Rotas — Parte B (`legislativo`)

| Rota | Quem | O quê |
|---|---|---|
| `GET /contas` | secretario, vereador, juridico | `{prestacoes [PrestacaoResumo]}` |
| `POST /contas` | secretario | `{tipo, exercicio, responsavel, recebida-em, processo-tce?, parecer-previo?, comissao-autora-id?, situacao-tce?}`; governo exige parecer e comissão autora e protocola o PDL na mesma transação → `PrestacaoOut` |
| `GET /contas/:id` | secretario, vereador, juridico | `PrestacaoOut` |
| `PATCH /contas/:id` | secretario | `{processo-tce?, situacao-tce?}` |
| `POST /contas/:id/notificacao` | secretario | `{notificado-em, meio}` → congela `prazo-defesa-ate` |
| `POST /contas/:id/documentos?tipo=` | secretario | multipart (campo `arquivo`); `tipo=defesa` marca a defesa juntada |
| `GET /contas/:id/documentos/:doc-id` | secretario, vereador, juridico | o arquivo |
| `GET /contas-da-proposicao/:proposicao-id` | secretario, vereador | `PrestacaoOut` ou 404 — o painel de votação usa para travar quórum e pergunta |
| `GET /parametros-de-contas` · `PUT` | secretario/admin_ente · admin_ente | `{prazo-defesa-dias, prazo-julgamento-dias, padrao?}` |
| `GET /portal/casa/:ente/contas` | público | `{prestacoes [PrestacaoPublica]}` |
| `GET /portal/casa/:ente/contas/:id/documentos/:doc-id` | público | só `parecer_previo`, `relatorio_tce`, `decisao_tce` |

- `PrestacaoResumo`: `{id, tipo, exercicio, responsavel, parecer-previo?, estado, resultado?, prazo-julgamento-ate?}`.
- `PrestacaoOut`: resumo + `{recebida-em, processo-tce?, proposicao {id, rotulo, estado}?, notificado-em?,
  notificacao-meio?, prazo-defesa-ate?, defesa-juntada-em?, julgada-em?, situacao-tce?, quorum {base-membros,
  necessarios-para-rejeitar}, votacao {id, sim, nao, abstencao}?, frase-resultado?, pautavel, motivo-nao-pautavel?,
  documentos [{id, tipo, nome, tamanho-bytes, criado-em}]}`.
- `PrestacaoPublica`: `{id, tipo, exercicio, responsavel, parecer-previo?, estado, resultado?, julgada-em?,
  proposicao-rotulo?, situacao-tce?, frase-resultado?, documentos [{id, tipo, nome}] (só os do TCE)}`.
- **Costuras do host:** a inclusão de proposição na pauta (`sessoes`) pergunta a `legislativo` se a matéria é
  pautável (409 com o motivo: "o prazo de defesa vai até 20/10/2026"); abrir votação de matéria de contas avalia a
  guarda (422 se o quórum ou a modalidade não forem os da regra); encerrar a votação grava `resultado`, `votacao_id`
  e `julgada_em` na prestação, na mesma transação.

### Motor (A4 e B4)

- Fato `audiencia_publica_realizada(Texto, Competencia) → Booleano` (`sessoes`): a competência é o **último mês do
  quadrimestre** (04, 08, 12 → Q1, Q2, Q3).
- Builtin `prazo_metas_fiscais_lrf(Competencia) → Data` (o calendário da LRF: fim de maio, de setembro e de fevereiro
  do ano seguinte).
- Template `audiencia_metas_fiscais` (domínio federal, severidade aviso, `LRF art. 9 §4`): `exige:
  audiencia_publica_realizada("metas_fiscais", competencia)`, janela `prazo_metas_fiscais_lrf(competencia)`.
- Template `julgamento_contas_prefeito` (domínio regimento_tenant, severidade aviso, `CF art. 31 §2 + LOM`):
  `exige: contas_julgadas(prestacao)`, janela = `prazo_julgamento_ate` da prestação (fato de data).
- **Gatilho:** hoje nada em produção chama a avaliação. Um gatilho do host avalia, por Casa e de forma idempotente,
  as obrigações dessas duas regras quando o painel de compliance é lido e logo depois dos atos que as cumprem (ata
  publicada de audiência; registro e julgamento de prestação).

### Telas

- **Interno:** agendar sessão com o bloco de audiência; `/sessoes/[id]/audiencia` (Mesa: dados, inscritos em ordem,
  inscrição presencial, chamar/cronometrar/encerrar, ausente); `/contas` (lista) e `/contas/[id]` (ficha, porte de
  `julgamento-contas.html`: parecer, "como a Câmara decide", prazos, documentos, notificação e defesa, incluir em
  pauta, resultado em palavras); `/contas/nova`; prazos das contas em `/administracao`; no painel de votação, a matéria
  de contas trava quórum/modalidade e mostra a pergunta e o resultado em palavras.
- **Portal:** `/portal/casa/[ente]/audiencias` e `/audiencias/[sessao]` (porte de `audiencia-publica.html`, "Quero
  falar" pelo gov.br); `/portal/casa/[ente]/contas` (prestações e resultados); a área da cidadã mostra as inscrições.
- Rótulos legíveis das duas obrigações no painel de compliance e no calendário.

## Fatias

1. **A (audiência):** banco, rotas, telas internas e do portal, bloqueio de votação, demo.
2. **B (contas):** banco, PDL, documentos, defesa, pauta, votação 2/3, telas, demo.
3. **Motor:** as duas regras-dado, fatos, gatilho e rótulos.

## Consequências

- O tipo de sessão continua sendo dado; o comportamento vem das capabilities (duas novas).
- O furo "sessão que não delibera aceitava voto" fecha para todos os tipos.
- Pela primeira vez uma regra do motor tem gatilho em produção; o padrão serve às próximas.
- `[GAP]` que ficam: datas de LDO/LOA/PPA, prazos de defesa e de julgamento por LOM (há padrão editável), efeito do
  vencimento do prazo de julgamento, comunicação do resultado ao TCE, contas da Mesa com deliberação.

## Materialização (fatia 3 — motor)

- **Fatos:** `audiencia_publica_realizada(Texto, Competencia)` em `sessoes/relacoes/audiencia.clj` (encerrada|arquivada
  **e** com linha em `sessoes.ata`; competência fora de 04/08/12 = falso); `contas_julgadas`, `prazo_julgamento_contas`
  e `data_recebimento_contas` (o `a_partir_de` da janela) em `legislativo/relacoes.clj`, sobre o tipo opaco novo
  `PrestacaoContasId`. Prestação inexistente lança (fail-closed). Tudo aditivo no catálogo: a versão não sobe.
- **Catálogo e vínculo:** o template passa no verificador e é gravado `vigente` uma vez; a Casa ganha o vínculo ativo
  na primeira vez que o gatilho roda para ela; o opt-out existente (com motivo) é respeitado. O gatilho avalia o
  `fonte_yaml` vigente do catálogo, não uma cópia em código.
  - **Quem grava o catálogo (corrigido em 05/10/2026):** o texto original dizia que o próprio gatilho garantia o
    template, sem migration. Só funcionava nos testes, que conectam como dono. O gatilho roda no request com o role
    de runtime (`oplenario_app`), que nunca teve grant no catálogo do motor. O CI da Trilha 3 mostrou o efeito: toda
    leitura do painel logava `permission denied for table template_compliance` e não avaliava nada.
  - **Agora:** o `migrate`, como dono, chama `gatilho-compliance/garantir-catalogo!`. A migration `20261005000250` dá
    ao app **só leitura**, e só de `motor.template_compliance` e `motor.calendario_feriado`, as duas tabelas do
    catálogo que a avaliação lê. O catálogo é de todas as Casas, e o app não grava nele.
  - **Teste:** `gatilho_compliance_papel_test` roda o gatilho como `oplenario_pool` e confere que escrever no
    catálogo continua negado.
- **Gatilho:** composto em `rotas.clj` sobre as rotas montadas (interceptor antes do handler da leitura; depois do
  handler dos atos, só em 2xx): `GET /compliance/painel` e o card de `/paineis/mesa` (`sob_demanda`), `POST
  /sessoes/:id/ata` de sessão `audiencia_publica`, `POST /contas` e o encerramento de votação (`evento`). Obrigação
  cumprida/dispensada/cancelada não é reavaliada; na leitura, a aberta avaliada há menos de 10 min também não (a prova
  append-only não ganha uma linha a cada recarga). Falha loga e segue.
- **Prazo da LRF:** o contrato pedia `fim_do_mes_seguinte`, que daria 31/01 para o 3º quadrimestre; a LRF diz
  fevereiro. Corrigido na integração: o builtin é `prazo_metas_fiscais_lrf` (04 → 31/05, 08 → 30/09, 12 → fim de
  fevereiro do ano seguinte; outro mês lança).
- **Janela de avaliação:** quadrimestres terminados com prazo de até 365 dias atrás **e não anterior ao vínculo da Casa
  à regra** (`criado_em` do vínculo). Uma Casa que chega hoje não nasce com "vencidas" de antes de usar o sistema; a
  primeira cobrança é a do próximo prazo. A demo data o vínculo em 01/01/2026 para mostrar o 2º quadrimestre vencido.
