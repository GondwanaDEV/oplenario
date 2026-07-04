(ns oplenario.paineis.db.pendencia
  "Persistencia de 'paineis.pendencia' (F7 Slice 1, §16.11) — funcoes sobre a `tx` corrente (FORCE RLS
  isola, mig 0048). HoneySQL schema-qualified; ente_id em TODA query. Read-model PROJETADO — `inserir!`
  materializa o protocolo (`participacao.<especie>.protocolad[oa]`); `marcar-concluida!`/`marcar-vencida!`/
  `atualizar-vence-em!` aplicam fechamento/vencimento/prorrogacao. Chamado pelo CONSUMER (§22.10
  diplomat/consumers), dentro da tx do relay — NAO ha Repo-Component na escrita (a projecao roda inteira na
  tx do bus, atomica com o dedup, §22.9 E2); o Repo-Component (components/repositorio) so' serve a LEITURA
  interna do servidor."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:ente_id :objeto_tipo :objeto_id :protocolo :vence_em :estado :projetado_em :atualizado_em])

(def ^:private teto-listagem
  "Teto server-side (anti unbounded-read, mesmo racional de transparencia/db/materia) — sem paginacao nesta
  fatia (o painel 'o que vence' e' pensado p/ leitura de topo, nao acervo completo)."
  200)

(defn inserir!
  "Projeta o snapshot do protocolo (`participacao.<especie>.protocolad[oa]`). `ON CONFLICT
  (ente_id,objeto_tipo,objeto_id) DO NOTHING` (mesmo racional de transparencia/db/materia/inserir!): a dedup
  do bus (evento_consumido, §22.9 E2) ja' garante no-maximo-uma-vez por evento COMMITADO, mas o ON CONFLICT
  e' cinto-de-seguranca barato contra um futuro redrive/backfill que reemita o MESMO evento de dominio com
  uma idempotency-key NOVA — sem isto, o redrive lancaria PK-violation e envenenaria o RELAY COMPARTILHADO."
  [tx {:keys [ente-id objeto-tipo objeto-id protocolo vence-em]}]
  {:pre [(some? ente-id) (some? objeto-tipo) (some? objeto-id) (some? protocolo) (some? vence-em)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :paineis.pendencia
                  :values [{:ente_id ente-id :objeto_tipo objeto-tipo :objeto_id objeto-id
                            :protocolo protocolo :vence_em vence-em}]
                  :on-conflict [:ente_id :objeto_tipo :objeto_id]
                  :do-nothing []
                  :returning [:*]}))))

(defn marcar-concluida!
  "Projeta o fechamento (respondid[oa]/decidido/arquivada — qualquer desfecho fecha 'o que vence' desta
  pendencia). TOLERANTE (mesmo racional de transparencia/db/materia/atualizar-estado!, review architect
  HIGH-1): 0 linhas afetadas (pendencia ainda nao projetada) devolve nil em vez de lancar — um `throw` aqui
  rodaria DENTRO da tx do RELAY COMPARTILHADO por todos os modulos, fazendo o mesmo evento ser reprocessado
  para sempre (poison, head-of-line block). So' fecha se ainda estava aberta (pendente|vencido) — um
  fechamento redrived contra uma linha ja concluida e' no-op silencioso, nao regressao. `[:inline ...]` p/ o
  estado (mesmo padrao de participacao/db/prazo_ativo) — bind parameter aqui impediria o planner de provar o
  predicado do INDEX parcial idx_pendencia_o_que_vence (a mesma lista de valores, review database MEDIUM)."
  [tx {:keys [ente-id objeto-tipo objeto-id]}]
  {:pre [(some? ente-id) (some? objeto-tipo) (some? objeto-id)]}
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :paineis.pendencia
                         :set {:estado "concluido" :atualizado_em [:now]}
                         :where [:and [:= :ente_id ente-id] [:= :objeto_tipo objeto-tipo]
                                 [:= :objeto_id objeto-id] [:in :estado [[:inline "pendente"] [:inline "vencido"]]]]}))]
    (when-not (zero? (:next.jdbc/update-count r 0))
      {:objeto-tipo objeto-tipo :objeto-id objeto-id :estado "concluido"})))

(defn marcar-vencida!
  "Projeta `participacao.prazo.vencido`: pendente -> vencido. So' flipa a partir de 'pendente' (guarda
  defensiva contra reordenacao/redrive — nunca ressuscita uma linha ja concluida). TOLERANTE (mesmo racional
  de marcar-concluida!): 0 linhas -> nil, nunca lanca."
  [tx {:keys [ente-id objeto-tipo objeto-id]}]
  {:pre [(some? ente-id) (some? objeto-tipo) (some? objeto-id)]}
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :paineis.pendencia
                         :set {:estado "vencido" :atualizado_em [:now]}
                         :where [:and [:= :ente_id ente-id] [:= :objeto_tipo objeto-tipo]
                                 [:= :objeto_id objeto-id] [:= :estado [:inline "pendente"]]]}))]
    (when-not (zero? (:next.jdbc/update-count r 0))
      {:objeto-tipo objeto-tipo :objeto-id objeto-id :estado "vencido"})))

(defn atualizar-vence-em!
  "Projeta `participacao.prazo.prorrogado`: adota o novo vencimento (`para-data`) na pendencia aberta. Nao
  reabre uma pendencia ja concluida (WHERE estado != 'concluido') — uma prorrogacao redrived contra um
  desfecho ja fechado e' no-op. TAMBEM reabre p/ 'pendente' incondicionalmente (review database LOW-MEDIUM):
  sem isto, uma prorrogacao aplicada FORA DE ORDEM apos um `prazo.vencido` ja projetado (cenario de
  redrive/backfill, R-DR ainda sem ferramenta) deixaria a linha com `vence_em` no futuro mas `estado`
  travado em 'vencido' — inconsistencia visivel no painel. Setar 'pendente' e' no-op quando ja estava
  pendente, e auto-cura quando reabre de vencido. TOLERANTE (mesmo racional acima): 0 linhas -> nil, nunca
  lanca."
  [tx {:keys [ente-id objeto-tipo objeto-id vence-em]}]
  {:pre [(some? ente-id) (some? objeto-tipo) (some? objeto-id) (some? vence-em)]}
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :paineis.pendencia
                         :set {:vence_em vence-em :estado "pendente" :atualizado_em [:now]}
                         :where [:and [:= :ente_id ente-id] [:= :objeto_tipo objeto-tipo]
                                 [:= :objeto_id objeto-id] [:<> :estado [:inline "concluido"]]]}))]
    (when-not (zero? (:next.jdbc/update-count r 0))
      {:objeto-tipo objeto-tipo :objeto-id objeto-id :vence-em vence-em})))

(defn resumo
  "Rollup 'o que vence' (F7 dashboard da Mesa, §16.11): contagem por estado ABERTO do tenant (pendente/
  vencido). GROUP BY estado -> [{:estado :n}]; o adapters/out deriva abertas/vencidas/pendentes. Sem teto
  (cardinalidade = 2). Sem aritmetica de data aqui DE PROPOSITO — 'proximas a vencer' e' derivacao de leitura
  no FE (contra 'hoje') a partir do endpoint de detalhe (/paineis/pendencias), p/ o rollup ser deterministico
  (sem CURRENT_DATE congelado na tx influenciando a contagem).

  FILTRA `estado IN ('pendente','vencido')` com `[:inline ...]` (review database MAJOR, mesmo racional de
  listar-abertas): (1) `concluido` nunca e' lido pelo adapters/out (era grupo computado e descartado); (2) sem
  o predicado, a query nao casa o INDEX PARCIAL idx_pendencia_o_que_vence (mig 0048, `WHERE estado IN
  ('pendente','vencido')`) e cai p/ scan de TODA a pendencia do tenant — incluindo o `concluido` historico que
  cresce sem limite (todo e-SIC/LGPD/ouvidoria ja' fechado). `[:inline]` (nao bind param) e' obrigatorio p/ o
  planner provar o predicado do indice parcial (bind param -> plano generico -> Bitmap Heap Scan)."
  [tx ente-id]
  {:pre [(some? ente-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:estado [[:count :*] :n]]
                  :from [:paineis.pendencia]
                  :where [:and [:= :ente_id ente-id]
                          [:in :estado [[:inline "pendente"] [:inline "vencido"]]]]
                  :group-by [:estado]
                  :order-by [[:estado :asc]]}))))

(defn listar-abertas
  "'o que vence' (§16.11): as pendencias ABERTAS (pendente|vencido) do tenant, mais urgente (vencimento mais
  proximo) primeiro; `objeto_id` como desempate deterministico entre vencimentos iguais (mesmo padrao de
  compliance/db/obrigacao/listar-em-aberto). Com teto server-side (anti unbounded-read). `[:inline ...]` p/
  o estado — bind parameter impediria o planner de provar o predicado do indice PARCIAL
  idx_pendencia_o_que_vence contra um array `= ANY($n)` desconhecido em plano generico (verificado via
  EXPLAIN, review database MEDIUM): cairia p/ Bitmap Heap Scan + Filter, ~30x mais lento conforme o backlog
  de concluidos cresce."
  [tx ente-id limite]
  {:pre [(some? ente-id) (pos-int? limite)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:paineis.pendencia]
                  :where [:and [:= :ente_id ente-id] [:in :estado [[:inline "pendente"] [:inline "vencido"]]]]
                  :order-by [[:vence_em :asc] [:objeto_id :asc]]
                  :limit (min limite teto-listagem)}))))
