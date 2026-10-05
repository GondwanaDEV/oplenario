# ADR-0008 — Fronteira core ↔ IA: eventos de integração por feed e caixa de entrada, conteúdo sob demanda

- **Status:** Aceito · 2026-09-26
- **Decisor:** Daouda Traore (CTO) — execução da fatia **A.3** do plano da Track IA (`docs/26`), sob o "Confirmo"
  dado com o merge do PR #38.
- **Fonte canônica:** §22.3 (contrato core↔IA: §22.3.1 topologia híbrida, §22.3.2 protocolo, §22.3.3 eventos de
  integração, §22.3.4 propriedade de dados, §22.3.5 erros), §22.6 eixo D/E (transcrição, Caminho C), §22.9 Eixo 11
  (transporte), §22.10 (monólito modular). Em conflito, a SSOT prevalece.
- **Aplica-se a:** `apps/backend` (módulo novo `integracao_ia`; `sessoes` ganha o ponteiro da transcrição) e
  `apps/ia` (cliente do core, armazenamento próprio, trabalhador de transcrição).

## Contexto

O core emite `gravacao.segmento-vinculado` desde a F4 e ninguém consome. A §22.3 decidiu **o quê** atravessa a
fronteira (eventos de integração pequenos e versionados, distintos dos eventos de domínio; conteúdo grande por URI;
idempotência; `ente_id` + `correlation_id`; 6 categorias de erro) e que "separação lógica não exige separação
física". Faltava decidir **como** o evento sai do core e como a resposta da IA volta, sem acoplar a disponibilidade
de um lado à do outro.

## Decisão

1. **Módulo `integracao_ia` no core** (bounded context da fronteira; entra na matriz do import-lint). Só ele fala
   com a IA. Dados de outros módulos chegam por **funções injetadas pelo host** (mesmo padrão dos seams existentes);
   ele nunca importa `sessoes`.
2. **Promoção explícita (core → IA).** Um consumidor do outbox traduz evento de domínio em **evento de integração**
   e o grava num **feed append-only** (`integracao_ia.evento_saida`: `seq` crescente, `ente_id`, `tipo`, `versao`,
   `chave` de idempotência única, `payload`). A lista de promoções é código revisado: promover um evento é decisão
   de contrato (§22.3.3). Primeira promoção: **`GravacaoVinculada` v1**, a partir de `gravacao.segmento-vinculado` e
   também de `gravacao.segmento-captado` quando a gravação já chega com sessão (o utilitário enviou com `--sessao`;
   o core não emite "vinculado" para ela). Mesma chave nas duas: o feed tem um evento só.
3. **O satélite puxa (feed com cursor).** `GET /integracao/ia/v1/eventos?depois=<seq>` devolve os próximos eventos;
   o satélite guarda o cursor e é idempotente pela `chave`. O core **nunca** chama o satélite para entregar evento:
   IA fora do ar não trava o relay nem o ato de ninguém; ao voltar, ela retoma do cursor.
4. **Conteúdo sob demanda, no tenant do evento.** O evento carrega URIs, não conteúdo. O satélite lê pela API do core,
   sempre com o `ente_id` explícito no caminho — o core abre a transação daquele tenant (RLS) para responder:
   - `GET /integracao/ia/v1/entes/:ente/gravacoes/:seg/conteudo` — o arquivo, em streaming, do object storage (o
     satélite não recebe credencial do storage);
   - `GET /integracao/ia/v1/entes/:ente/sessoes/:id/contexto` — a sessão, os segmentos e as **falas** (orador, nome,
     tipo, início, fim) que o Caminho C usa.
5. **A IA devolve por caixa de entrada.** `POST /integracao/ia/v1/eventos` recebe `TranscricaoConcluida` v1 /
   `TranscricaoFalhou` v1 (e, nas fatias seguintes, os de ata e resumo). O core valida o schema de `(tipo, versao)`,
   deduplica pela `chave` (`integracao_ia.evento_entrada`, que é também o registro de auditoria do que chegou) e
   aplica o efeito **na mesma transação** do registro, no tenant do evento. Reenvio = 200 sem efeito novo.
6. **Sigilo fail-closed na fronteira.** Gravação de acesso restrito (sessão secreta) **não é promovida** e as rotas de
   conteúdo e contexto a **recusam** — mesmo que alguém peça pelo id. A ata de sessão secreta segue manual na V1.
7. **Credencial de serviço.** As rotas `/integracao/ia/*` não usam o login de pessoas: exigem um segredo
   compartilhado core↔satélite (`OPLENARIO_IA_SEGREDO`, do cofre — Eixo 11f), comparado em tempo constante. Sem o
   segredo configurado, as rotas respondem 503 (desligadas). Transporte: TLS server-side + NetworkPolicy (§22.3.2,
   Eixo 11); mTLS bilateral segue diferido à Rota D.
8. **Versão no nome do contrato e no envelope.** Todo evento de integração tem `tipo` + `versao` inteira; mudança
   incompatível = versão nova convivendo com a antiga durante a transição (§22.3.3).
9. **Satélite com armazenamento próprio** (§22.3.4: a transcrição vive na IA): schema `ia` no Postgres, com papel
   próprio em produção. Guarda o cursor, a fila de trabalhos (idempotente pelo segmento) e as transcrições. O core
   guarda só o **ponteiro** (`sessoes.transcricao_sessao`: situação, métricas, modelos usados — nunca o texto).

## Consequências

- O primeiro consumidor real dos eventos de gravação existe, e a fronteira fica pronta para ata, resumo e
  embeddings sem mudar de forma.
- Latência de entrega = intervalo de consulta do satélite (segundos) — irrelevante para trabalho em lote (transcrição
  e ata levam minutos); o síncrono interativo (copiloto, busca) usa HTTP direto, como a §22.3.1 já previa.
- O feed cresce sem parar; a limpeza (reter N dias depois de todo consumidor confirmar) é operação futura, junto
  da limpeza do outbox.

## Alternativas descartadas

- **Core empurra (webhook para o satélite)** — acopla o relay à disponibilidade da IA (fila travada ou evento
  perdido quando ela cai) e exige retry/DLQ no core. Puxar põe o controle de ritmo em quem faz o trabalho.
- **Satélite lê direto as tabelas do core** — quebra a propriedade de dados (§22.3.4), a RLS e o import-lint.
- **Credencial de storage no satélite** — mais um segredo em mais um lugar, e o satélite poderia ler qualquer
  objeto (inclusive gravação restrita); pelo core, cada leitura passa pelo tenant e pelo sigilo.
- **Broker de mensagens dedicado** — infra nova sem necessidade: o volume é de dezenas de eventos por sessão, e o
  Postgres já é a fila da plataforma (§22.9).

## Adendo (26/09/2026) — a ata na mesma fronteira (Faixa A / A.6b)

Extensão sem conceito novo: dois eventos a mais em cada sentido, mesmos mecanismos (feed, caixa de entrada, leitura
sob demanda).

- **core → IA: `AtaSolicitada` v1** (`solicitacao-id`, `sessao-id`, `contexto-uri`), promovido de
  `ata.rascunho-solicitado`. O gatilho é o **pedido explícito da secretaria**, não o `SessaoEncerrada` da lista do
  §22.3.3: no encerramento as transcrições ainda não existem, e rascunho redigido sem elas seria rascunho vazio. O
  pedido só é aceito com ≥1 transcrição concluída, sessão não secreta (o sigilo tem duas travas: o controller recusa e
  o contexto responde 403) e sem outro pedido em curso nos últimos 30 min (decidido na tx).
- **IA → core: `AtaRascunhoPronta` v1 / `AtaFalhou` v1**. Só metadados: o id do rascunho **na IA**, modelo, versão do
  prompt e os sinais da Camada de Confiança (incerteza, citações conferidas, parágrafos sem fonte, pontos a
  confirmar). O texto fica no satélite (`ia.rascunho_ata`, §22.3.4: "ata em rascunho" é transitória da IA) e o core o
  lê sob demanda (`GET /v1/entes/{ente}/atas/rascunhos/{id}`), só para um id que ele mesmo registrou para a sessão.
- **A promoção rascunho → publicado** é o POST da ata no core com `origem_redacao = gerada_automaticamente` e o
  `rascunho-id`; modelo e versão do prompt vêm do ponteiro do core, nunca do cliente.
- **core → IA: `AtaRevisadaEPublicada` v1 (A.6c)**, promovido de `ata.publicada` (que toda publicação emite, na mesma
  tx) **só quando a versão partiu de um rascunho da IA**. Leva o hash e a `conteudo-uri` da versão; o texto é lido em
  `GET /integracao/ia/v1/entes/{ente}/sessoes/{sessao}/atas/{versao}` (sessão secreta: 403). A IA confere o hash,
  compara com o texto LIMPO do rascunho e registra `RevisaoHumana` (aprovado/editado + proporção alterada) — a
  métrica fica no registro de confiança do satélite, uma vez por (rascunho, versão). A coluna `proporcao_alterada` de
  `sessoes.ata` fica nula: a medida é da IA e não volta ao artefato legal.

## Adendo (27/09/2026) — o índice de busca na mesma fronteira (Faixa A / A.4)

O índice único (§22.3.4: embeddings são do satélite) é alimentado pelos mesmos mecanismos; a busca é o caminho
síncrono que a §22.3.1 já previa.

- **core → IA: `ProposicaoProtocolada` v1 / `ProposicaoAtualizada` v1** (`proposicao-id`, `ementa`, `autor-texto`),
  promovidos de `proposicao.protocolada` / `proposicao.editada`. Só o texto público: número, tipo e estado o core
  completa na hidratação. A chave de `ProposicaoAtualizada` inclui o hash de ementa+autoria, então a mesma edição
  re-emitida não duplica o feed e uma edição real passa.
- **Transcrição:** sem evento novo — o próprio satélite indexa ao concluir uma transcrição (trabalho
  `indexar:transcricao:<id>`), em trechos por segmento.
- **Carga inicial:** `clojure -M -m oplenario.main ia-republicar-proposicoes <ente-id>` reempurra as proposições já
  existentes de uma Casa como `ProposicaoAtualizada` (idempotente pela chave); `oplenario-ia-trabalhador
  --reindexar` refaz o índice das transcrições guardadas no satélite (troca de modelo de embedding).
- **Busca: `POST /v1/entes/{ente}/busca`** (segredo de serviço), `{consulta, tipos?, limite?}` →
  `{modelo, resultados: [{tipo, ref-id, parte, texto, meta, score}]}`. Híbrida (full-text português + cosseno no
  pgvector, fundidas por RRF), com corte de distância por modelo para não devolver "o menos distante" de um índice sem
  nada parecido. O satélite devolve ids e trechos; **quem decide o que o usuário vê é o core**, que hidrata das suas
  tabelas com a visibilidade dele (§22.3.4).
- **Embeddings:** fake determinístico por padrão (CI e deploy), adaptador self-host em ONNX na CPU (`fastembed`,
  extra `embeddings`) por config. O corte de distância do adaptador real ainda não foi calibrado com consultas reais.

## Adendo (27/09/2026) — o resumo cidadão na mesma fronteira (Faixa A / A.8)

- **Gatilho:** os mesmos `ProposicaoProtocolada` / `ProposicaoAtualizada` do índice (§22.3.3; a feature 5.4 nasce do
  protocolo). A IA lê o texto em `GET /integracao/ia/v1/entes/{ente}/proposicoes/{id}/texto` (ementa, autoria, texto
  vigente e `texto-sha256` da versão) e só redige quando aquela versão ainda não tem rascunho. Editar só o texto passa
  a mudar a chave de `ProposicaoAtualizada`: `proposicao.editada` leva `hash-texto` quando o texto muda.
- **IA → core: `ResumoCidadaoPronto` v1 / `ResumoFalhou` v1**, só metadados (id do rascunho na IA, a versão do texto
  resumida, modelo, prompt e os sinais da Camada de Confiança). O texto fica em `ia.rascunho_resumo` (§22.3.4) e o
  core o lê sob demanda em `GET /v1/entes/{ente}/resumos/rascunhos/{id}`, só para um id que ele registrou para a
  proposição. O texto da proposição entra no núcleo como conteúdo de terceiro (§22.11.4).
- **Publicado:** `legislativo.resumo_cidadao`, versionado; a versão que partiu da IA leva o `rascunho-id`, com modelo e
  prompt vindos do ponteiro. Cada publicação emite `proposicao.resumo-publicado` na mesma tx; a transparência projeta a
  versão mais nova em `transparencia.materia` e a ficha pública do portal mostra o resumo com o selo de revisão humana
  (A.8b). O portal nunca fala com a IA: só mostra o que a Casa publicou.

## Adendo (05/10/2026) — o resultado das votações na ata (Faixa A / A.6)

O contexto da sessão (`GET /integracao/ia/v1/entes/{ente}/sessoes/{id}/contexto`) ganha a lista `votacoes`; o rascunho da
ata deixa de escrever o resultado como `[confirmar: …]` quando o sistema já o sabe. Sem evento novo, sem rota nova, sem
migration: é o mesmo caminho dos oradores e da pauta.

- **O que entra:** só votação **encerrada** da sessão (a aberta e a anulada nunca saem), na ordem em que encerrou:
  `objeto` em palavras ("PL 008/2026", "redação final do PL 008/2026"; emenda, parecer e requerimento vão pelo tipo,
  sem inventar título), `modalidade`, `quorum-tipo`, `votos-necessarios` (a aritmética do core, nula na maioria
  simples), `base-membros`, `resultado`, os três totais (nulos na simbólica, que não conta voto) e `encerrada-em`.
  Montado em `legislativo` (`db/votacao_para_ia`, `logic/votacao_ia`, protocolo `RepoVotacaoIA`) e entregue pelo host
  (`rotas/contexto-da-sessao-para-ia`). Acima de 500 votações numa sessão o contexto falha alto, nunca trunca.
- **Sigilo:** o voto de cada vereador **não entra, nem na votação nominal** — a consulta não lê `votos` nem
  `votos_secretos`, o mapa é uma allowlist e o `wire/out` é fechado. Votação secreta leva só o resultado e os totais
  que o sistema já publica. Sessão secreta continua 403 e nem consulta votação.
- **No satélite:** cada votação vira uma fonte `votacao:<id>` estruturada (`Fonte.estruturada`), pública e **não** de
  terceiro, que passa pelo filtro B1–B4 como as demais. O texto da fonte traz as linhas `Frase do resultado`, `Frase da
  unanimidade` (só se o dado é unânime) e `Frase do quórum`: o conjunto FECHADO de frases canônicas, geradas do dado por
  uma função pura (`ata/redacao.py:frases_canonicas`, a mesma que o redator fake usa para escrever). Prompt `ata-v2`.
- **O que a Camada de Confiança confere (`confianca/numeros.py`), sem interpretar prosa:** o parágrafo que cita
  `votacao:<id>` (sem as marcas de citação, que o texto limpo da ata também tira) confere se (a) contém, por substring
  exata, uma frase canônica DAQUELA votação e (b) tiradas as frases canônicas e o identificador canônico da matéria
  ("PL 008/2026"), também por substring exata, NÃO sobra nenhum sinal numérico: qualquer caractere de categoria N
  (sobrescrito, romano Unicode, fração, largura total, outros alfabetos), marca combinante, caractere invisível ou letra
  fora do alfabeto latino (homóglifo), uma lista fechada de palavras (zero…dezenove, dezenas, centenas, mil, milhão,
  meia, meio, dúzia, dobro, metade, terço, quarto, maioria, minoria, unanim*, nenhum*, todos, ambos, vários, empate,
  ordinais por extenso) e algarismos romanos isolados. Uma só normalização (NFKC, minúsculas, espaços colapsados) para o
  parágrafo, as frases e os identificadores. Nada é mascarado: data e hora que sobram reprovam; o `[confirmar: …]`
  (dúvida declarada) é a única exclusão, por delimitador exato, e o resto do parágrafo ainda tem de passar. Duas votações
  citadas no mesmo parágrafo: nenhuma confere. Reprovado sai `trecho_nao_encontrado` (vira ponto a confirmar na revisão).
- **A sobra é lista de PERMITIDOS:** depois de tiradas as frases canônicas e os identificadores, o que resta no
  parágrafo só pode ser a moldura que apresenta a votação (lista fechada em `numeros.py`: "votação nominal", "a
  matéria", "foi", "resultado", pontuação comum). Qualquer outra palavra ou símbolo reprova. Só a lista de proibidos
  deixava passar o que muda o sentido sem número: "não foi aprovada por…", "desaprovada por…" (a canônica casa dentro
  da palavra), "rejeitada" ao lado da frase de "aprovada", número colado em palavra.
- **O que isto garante:** o parágrafo de uma votação só contém o placar como o SISTEMA o escreveria, dentro de uma
  moldura conhecida. **O que NÃO garante:** o conferidor não entende português; a ordem das palavras da moldura não é
  conferida. A revisão humana do rascunho continua obrigatória. O custo é ruído aceito e fail-closed: o modelo tem de
  citar a votação num parágrafo próprio, sem mais nada; o que ele acrescentar vira ponto a confirmar.
- **Gravação que contradiz o dado:** vale o dado, e a instrução manda `[confirmar: a gravação indica X; o sistema
  registra Y]`; o roteiro do fake faz isso para algarismos e com uma votação só.
- **Core antigo:** contexto sem `votacoes` vale lista vazia (a ata sai como antes).
