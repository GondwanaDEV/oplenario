(ns oplenario.participacao.db.atendimento
  "Read-model do BALCAO interno de atendimento (6.1 e-SIC, 6.2 ouvidoria, 5.10 LGPD) — as tres filas da secretaria,
  sobre a `tx` do tenant (FORCE RLS isola; ente_id em TODA query). Uma consulta por fila: o objeto + o SEU prazo
  (prazo_ativo, pela UNIQUE (ente, objeto_tipo, objeto_id)) e, no e-SIC, o recurso que espera decisao + o prazo
  PROPRIO dele. So' leitura. IMPL atras do RepoParticipacao.

  ORDEM (a mesma nas tres): os abertos primeiro, pelo prazo que vence primeiro (o vencimento EFETIVO — COALESCE
  prorrogado_ate/vence_em, o mesmo de `logic/vencimento-efetivo` — e, no e-SIC com recurso pendente, o do recurso);
  depois os encerrados, os mais recentes primeiro (um encerrado nao tem prazo correndo, e a fila de respondidos
  cresce para sempre: ordenar pelo prazo esconderia os de ontem atras do teto). Teto server-side por fila."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def teto
  "Teto de itens por fila (anti unbounded-read). Os abertos vem primeiro, entao o teto so' corta encerrados antigos
  — uma Casa com mais de 500 pedidos ABERTOS e' outro problema."
  500)

(def ^:private abertos-pedido [[:inline "protocolado"] [:inline "em_analise"]])
(def ^:private abertos-fem [[:inline "protocolada"] [:inline "em_analise"]])

(defn- join-prazo
  "LEFT JOIN do prazo do objeto `alias-obj` (objeto_tipo `tipo`) sob o alias `alias-prazo`."
  [alias-prazo alias-obj tipo]
  [[:participacao.prazo_ativo alias-prazo]
   [:and [:= (keyword (str (name alias-prazo) ".ente_id")) (keyword (str (name alias-obj) ".ente_id"))]
    [:= (keyword (str (name alias-prazo) ".objeto_tipo")) [:inline tipo]]
    [:= (keyword (str (name alias-prazo) ".objeto_id")) (keyword (str (name alias-obj) ".id"))]]])

(defn- efetivo [alias-prazo]
  [:coalesce (keyword (str (name alias-prazo) ".prorrogado_ate")) (keyword (str (name alias-prazo) ".vence_em"))])

(defn- filtro-situacao [aberto situacao]
  (case situacao
    "abertos"     aberto
    "respondidos" [:not aberto]
    "todos"       true))

(defn- ordem [aberto vigente recibo id]
  [[aberto :desc] [[:case aberto vigente] :asc] [recibo :desc] [id :asc]])

(defn- prazo-de [linha prefixo]
  (let [k #(keyword (str prefixo %))]
    (when (some? ((k "vence-em") linha))
      {:vence-em ((k "vence-em") linha) :prorrogado-ate ((k "prorrogado-ate") linha) :estado ((k "prazo-estado") linha)})))

(defn fila-esic
  "Os pedidos e-SIC da Casa na `situacao` (abertos|respondidos|todos). ABERTO = o pedido sem resposta OU um recurso sem
  decisao (o recurso protocolado tem relogio proprio, e e' ele que vale enquanto espera). Devolve [{:item :prazo
  :recurso}] (recurso = o pendente, com o seu :prazo, ou nil)."
  [tx ente-id situacao]
  {:pre [(some? ente-id)]}
  (let [aberto  [:or [:in :p.estado abertos-pedido] [:is-not :r.id nil]]
        vigente [:coalesce (efetivo :rpz) (efetivo :pz)]]
    (mapv (fn [l]
            {:item    (select-keys l [:id :protocolo :assunto :estado :recibo-em])
             :prazo   (prazo-de l "")
             :recurso (when (:recurso-id l)
                        {:id (:recurso-id l) :protocolo (:recurso-protocolo l) :estado (:recurso-estado l)
                         :recibo-em (:recurso-recibo-em l) :prazo (prazo-de l "recurso-")})})
          (comum/linhas->kebab
           (jdbc/execute! tx
             (sql/format
              {:select    [:p.id :p.protocolo :p.assunto :p.estado :p.recibo_em
                           :pz.vence_em :pz.prorrogado_ate [:pz.estado :prazo_estado]
                           [:r.id :recurso_id] [:r.protocolo :recurso_protocolo] [:r.estado :recurso_estado]
                           [:r.recibo_em :recurso_recibo_em]
                           [:rpz.vence_em :recurso_vence_em] [:rpz.prorrogado_ate :recurso_prorrogado_ate]
                           [:rpz.estado :recurso_prazo_estado]]
               :from      [[:participacao.pedido_esic :p]]
               :left-join (concat (join-prazo :pz :p "pedido_esic")
                                  ;; o recurso PENDENTE (V1: uma instancia por pedido — UNIQUE (pedido, instancia))
                                  [[:participacao.recurso_esic :r]
                                   [:and [:= :r.ente_id :p.ente_id] [:= :r.pedido_id :p.id]
                                    [:= :r.estado [:inline "protocolado"]]]]
                                  (join-prazo :rpz :r "recurso_esic"))
               :where     [:and [:= :p.ente_id ente-id] (filtro-situacao aberto situacao)]
               :order-by  (ordem aberto vigente :p.recibo_em :p.id)
               :limit     teto}))))))

(defn fila-ouvidoria
  "As manifestacoes de ouvidoria da Casa na `situacao`. Le `anonima` (para dizer SE e' identificada) e NUNCA o
  manifestante (Lei 13.460 art. 10 §7º — a identificacao e' de acesso restrito; o balcao nao a materializa).
  Devolve [{:item :prazo}]."
  [tx ente-id situacao]
  {:pre [(some? ente-id)]}
  (let [aberto [:in :m.estado abertos-fem]]
    (mapv (fn [l] {:item (select-keys l [:id :protocolo :tipo :assunto :anonima :estado :recibo-em])
                   :prazo (prazo-de l "")})
          (comum/linhas->kebab
           (jdbc/execute! tx
             (sql/format
              {:select    [:m.id :m.protocolo :m.tipo :m.assunto :m.anonima :m.estado :m.recibo_em
                           :pz.vence_em :pz.prorrogado_ate [:pz.estado :prazo_estado]]
               :from      [[:participacao.manifestacao_ouvidoria :m]]
               :left-join (join-prazo :pz :m "manifestacao_ouvidoria")
               :where     [:and [:= :m.ente_id ente-id] (filtro-situacao aberto situacao)]
               :order-by  (ordem aberto (efetivo :pz) :m.recibo_em :m.id)
               :limit     teto}))))))

(defn fila-lgpd
  "As solicitacoes do titular (LGPD) da Casa na `situacao`. Devolve [{:item :prazo}] (sem o titular: a lista nao
  precisa de quem pediu; o detalhe o mostra, com o CPF mascarado)."
  [tx ente-id situacao]
  {:pre [(some? ente-id)]}
  (let [aberto [:in :s.estado abertos-fem]]
    (mapv (fn [l] {:item (select-keys l [:id :protocolo :tipo :estado :recibo-em])
                   :prazo (prazo-de l "")})
          (comum/linhas->kebab
           (jdbc/execute! tx
             (sql/format
              {:select    [:s.id :s.protocolo :s.tipo :s.estado :s.recibo_em
                           :pz.vence_em :pz.prorrogado_ate [:pz.estado :prazo_estado]]
               :from      [[:participacao.solicitacao_titular :s]]
               :left-join (join-prazo :pz :s "solicitacao_titular")
               :where     [:and [:= :s.ente_id ente-id] (filtro-situacao aberto situacao)]
               :order-by  (ordem aberto (efetivo :pz) :s.recibo_em :s.id)
               :limit     teto}))))))
