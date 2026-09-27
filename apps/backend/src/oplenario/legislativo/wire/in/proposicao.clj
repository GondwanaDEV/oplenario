(ns oplenario.legislativo.wire.in.proposicao
  "Representacao EXTERNA de ENTRADA da proposicao (§22.10 wire/in, ADR-0001, Onda B Slice 2) — os corpos de
  POST/PATCH. `:closed true` recusa campo extra; tenant/autor NAO vem do corpo (vem do ator resolvido na
  auth); o `id` (PATCH) vem do path. Enums saem de legislativo.logic (fonte unica; espelham os CHECK das
  migrations 20260620000013/20260620000015)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

;; Limites de tamanho: mesmo rigor dos filtros de GET nesta borda de escrita (o body-cap global de 256KiB
;; nao substitui bound por campo). autor-id/destinatario-id sao UUID em string (36 chars); o adapter ainda
;; os converte e rejeita shape invalido. `texto` nao ganha :max aqui — o limite REAL e' 32KB em BYTES,
;; imposto por logic/decidir-armazenamento no Repo (spec §6), nao em caracteres.
(def ^:private campos-metadados-opcionais
  "Campos de metadados opcionais compartilhados por criar/editar (mesmos limites; evita drift)."
  [[:autor-tipo {:optional true} [:maybe (km/enum-de logic/autor-tipos)]]
   [:autor-id {:optional true} [:maybe [:string {:max 36}]]]
   [:autor-texto {:optional true} [:maybe [:string {:max 300}]]]
   [:objeto-indicacao {:optional true} [:maybe [:string {:max 500}]]]
   [:destinatario-id {:optional true} [:maybe [:string {:max 36}]]]
   [:destinatario-texto {:optional true} [:maybe [:string {:max 300}]]]
   [:tipo-requerimento {:optional true} [:maybe [:string {:max 200}]]]
   [:categoria-mocao {:optional true} [:maybe [:string {:max 200}]]]
   [:texto {:optional true} [:maybe :string]]])

(def CriarProposicao
  "Corpo de POST /legislativo/proposicoes. `texto` e' OPCIONAL (corpo integral markdown, inline <=32KB —
  overflow p/ objeto_store fica de carry, spec §6)."
  (into [:map {:closed true}
         [:tipo (km/enum-de logic/tipos)]
         [:ano [:int {:min 1900 :max 2200}]]
         [:ementa [:string {:max 2000}]]]
        campos-metadados-opcionais))

(def EditarProposicao
  "Corpo de PATCH /legislativo/proposicoes/:id. PATCH parcial: so' os campos presentes mudam.
  `lock-version` e' obrigatorio (CAS). Se `texto` presente, promove uma nova versao (origem 'edicao')."
  (into [:map {:closed true}
         [:lock-version :int]
         [:ementa {:optional true} [:maybe [:string {:max 2000}]]]]
        campos-metadados-opcionais))

;; ---------- Fatia 2: a borda da TRAMITACAO (eixo C) ----------

(def ^:private contexto-max-chaves
  "Teto de chaves do `contexto`. [REVERTIDO por ADR-0004] Ate' 11/09/2026 o contexto era ARGUMENTO DE
  GUARD (o avaliador lia `alegado.x`, o nome de dominio para este mesmo campo — ver `contexto->alegado`);
  hoje NAO chega mais ao avaliador de jeito nenhum, so' vira carga do ato persistida em
  `proposicao_transicao_historico.contexto` (jsonb append-only, Inv.10 — o que entra la' nunca sai; ver
  auditoria-continua-gravando-o-corpo-mesmo-que-a-guarda-nao-o-leia-mais). O teto de 20 chaves segue
  existindo pelo MESMO motivo de sempre, so' que agora contra o historico, nao contra o guard: nao virar
  canal de blob para dentro de uma tabela append-only."
  20)

(def ^:private ChaveContexto
  "Chave de contexto: minuscula, ASCII, <=60 chars. [REVERTIDO por ADR-0004] NAO e' cosmetica: o
  adapters/in continua INTERNANDO cada chave como keyword (`contexto->alegado`) para popular o `:alegado`
  que `registrar-transicao!` grava na auditoria — nao mais porque um guard vai le-la (nenhum le'). O
  charset fechado segue limitando o alfabeto ao que uma chave de auditoria plausivel usaria, mesmo sem
  guard nenhum do outro lado."
  [:re #"^[a-z][a-z0-9_-]{0,59}$"])

(def ^:private ValorContexto
  "Valor de contexto: ESCALAR (ou nulo). [REVERTIDO por ADR-0004] Aninhamento fica de fora de proposito —
  nao porque o avaliador da DSL so' saiba acessar um nivel (nenhum guard le' este campo mais), mas porque
  mapa/vetor aqui so' engordaria o historico sem proposito auditavel. String limitada a 500 chars pelo
  mesmo motivo."
  [:or [:string {:max 500}] :int :double :boolean :nil])

(def TramitarProposicao
  "Corpo de POST /legislativo/proposicoes/:id/tramitacao.

  `gatilho` e' o UNICO verbo — string livre (:min 1), NUNCA enum: o vocabulario de gatilhos e' DADO do
  tenant (`legislativo.template_transicao.gatilho`), Inv.4. Cravar um enum aqui quebraria a primeira Casa
  cujo regimento nomeia o ato de outro jeito.

  O QUE ESTE SCHEMA DELIBERADAMENTE NAO TEM: estado-destino, sob qualquer nome (`para`, `estado`,
  `para-estado`) — e `template-id`. Quem decide PARA ONDE a materia vai e' o template avaliando o guard; o
  rito sob o qual ela corre e' a coluna da propria linha. Deixar o cliente nomear qualquer um dos dois e' a
  classe de defeito do T3-A (o chamador escolhendo a regra). `:closed true` faz a recusa ser mecanica: o
  campo extra e' 400 e a engine nunca roda.

  `contexto` (opcional) e' a CARGA do gatilho — entrada de cliente que o historico persiste
  (`proposicao_transicao_historico.contexto`, Inv.10). O schema limita a FORMA (escalares, teto de
  chaves, charset) pelo mesmo motivo de sempre: nao virar canal de blob.

  [REVERTIDO por ADR-0004] Este paragrafo dizia que o GUARD podia ler este campo (sob o nome `alegado`,
  fatia 4 antiga) e que proibir seria decidir pelo regimento. O ADR-0004 (frente `guarda-so-apurado`,
  11/09/2026) pesou esse argumento e o derrubou — ver o ADR, secao \"O precedente que esta ADR reverte\":
  o unico uso legitimo citado (escolher destino por `alegado.comissao`) e' melhor modelado como
  GATILHO-POR-DESTINO (atos distintos no regimento), nao como guard lendo o corpo. HOJE `contexto` NAO
  chega ao guard sob nome NENHUM — nem `contexto`, nem `alegado`. Um rito que ainda referencie qualquer um
  dos dois (por escrita nova, bloqueada no cadastro por `criar-transicao!`, ou por linha gravada fora dele
  — import, SQL direto) lanca `{:erro :runtime}` e a materia NAO tramita, nunca le' o corpo em silencio.
  O campo continua existindo no corpo e sendo AUDITADO integralmente (Inv.10) — deixou de DECIDIR, nao
  deixou de ser REGISTRADO. Quem o guard pode ler hoje e' so' `proposicao`/`parecer` (a linha, lida pelo
  servidor) e os fatos por nome (`aprovada_em_votacao(proposicao.id)` — decisao 3-B), os canais APURADOS.
  Ver `db/tramitacao/transicionar!`, secao OS DOIS CANAIS APURADOS DO `amb`."
  [:map {:closed true}
   [:gatilho [:string {:min 1 :max 100}]]
   [:contexto {:optional true} [:maybe [:map-of {:max contexto-max-chaves} ChaveContexto ValorContexto]]]])

(def ReceberMovimentacao
  "Corpo de POST /legislativo/proposicoes/:id/recebimento (fatia 2b) — o recebimento ASSINADO da carga.

  So' `movimentacao-id`: a movimentacao que a pessoa VIU na tela e esta' recebendo. O servidor confere que
  ela ainda e' a pendente (senao 409 — nao se assina o que nao foi visto). Quem recebe vem do token, nunca
  do corpo; o estado e a hora, da linha e do banco. `:closed` pelo mesmo motivo de TramitarProposicao."
  [:map {:closed true}
   [:movimentacao-id [:string {:min 36 :max 36}]]])
