(ns oplenario.comunicacao.db.comunicado
  "Persistencia dos comunicados internos (ADR-0020, mig 20261003000181) — funcoes sobre a `tx` do tenant (FORCE RLS
  isola). HoneySQL schema-qualified; `ente_id` em toda query (defesa em profundidade). Tudo e' INSERT: o comunicado, os
  destinos, a lista congelada, as marcas e os anexos sao append-only no banco. IMPL atras do RepoComunicacao
  (ADR-0001 §3-bis)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.comunicacao.logic :as logic]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.sequencial :as sequencial]))

(set! *warn-on-reflection* true)

(def teto-da-lista
  "As listas (caixa, enviados) devolvem no maximo isto, mais recentes primeiro — a contagem que importa (nao lidos,
  pendentes de ciencia) e' sempre o TOTAL, fora do teto, para a tela nunca mentir."
  50)

(def ^:private cols
  [:c.id :c.ente_id :c.ano :c.numero :c.protocolo :c.remetente_identidade_id :c.remetente_nome :c.assunto :c.corpo
   :c.exige_ciencia :c.ciencia_ate :c.substitui_id :c.objeto_tipo :c.objeto_id :c.enviado_em])

(defn- linhas [tx q] (comum/linhas->kebab (jdbc/execute! tx (sql/format q))))
(defn- linha [tx q] (comum/linha->kebab (jdbc/execute-one! tx (sql/format q))))

;; ---------- enviar ----------

(defn inserir!
  "O ATO de enviar, na tx do chamador: o numero gapless da Casa no ano (`shared.sequencial`, escopo `comunicado:<ano>`),
  o protocolo, o comunicado, os destinos e a lista congelada. Devolve o id."
  [tx {:keys [id ente-id ano remetente-identidade-id remetente-nome assunto corpo exige-ciencia ciencia-ate
              substitui-id objeto-tipo objeto-id enviado-em]}
   destinos destinatarios]
  (let [numero (sequencial/proximo! tx (logic/escopo-da-numeracao ano))]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :comunicacao.comunicado
                   :values [{:id id :ente_id ente-id :ano ano :numero numero
                             :protocolo (logic/protocolo ano numero)
                             :remetente_identidade_id remetente-identidade-id :remetente_nome remetente-nome
                             :assunto assunto :corpo corpo :exige_ciencia (boolean exige-ciencia)
                             :ciencia_ate ciencia-ate :substitui_id substitui-id
                             :objeto_tipo objeto-tipo :objeto_id objeto-id
                             ;; o instante do relogio do ATO (o mesmo que decide o ano do protocolo e a janela de anexos)
                             :enviado_em (or enviado-em [:now])}]}))
    (jdbc/execute-one! tx
      (sql/format {:insert-into :comunicacao.destino
                   :values (mapv (fn [{:keys [ordem tipo alvo-id alvo-nome]}]
                                   {:ente_id ente-id :comunicado_id id :ordem ordem :tipo tipo :alvo_id alvo-id
                                    :alvo_nome alvo-nome})
                                 destinos)}))
    (jdbc/execute-one! tx
      (sql/format {:insert-into :comunicacao.destinatario
                   :values (mapv (fn [{:keys [identidade-id nome via]}]
                                   {:ente_id ente-id :comunicado_id id :identidade_id identidade-id :nome nome
                                    :via via})
                                 destinatarios)}))
    id))

;; ---------- ler ----------

(defn buscar [tx ente-id id]
  (linha tx {:select cols :from [[:comunicacao.comunicado :c]]
             :where [:and [:= :c.ente_id ente-id] [:= :c.id id]]}))

(defn substituido-por
  "{substituido-id {:id :protocolo}} — quem substituiu cada um dos `ids` (lote)."
  [tx ente-id ids]
  (let [ids (vec (distinct (remove nil? ids)))]
    (if (empty? ids)
      {}
      (into {} (map (fn [r] [(:substitui-id r) {:id (:id r) :protocolo (:protocolo r)}]))
            (linhas tx {:select [:id :protocolo :substitui_id] :from [:comunicacao.comunicado]
                        :where [:and [:= :ente_id ente-id] [:in :substitui_id ids]]})))))

(defn protocolos
  "{id protocolo} dos `ids` (o \"substitui COM-…\")."
  [tx ente-id ids]
  (let [ids (vec (distinct (remove nil? ids)))]
    (if (empty? ids)
      {}
      (into {} (map (juxt :id :protocolo))
            (linhas tx {:select [:id :protocolo] :from [:comunicacao.comunicado]
                        :where [:and [:= :ente_id ente-id] [:in :id ids]]})))))

(defn destinos-de
  "{comunicado-id [{:tipo :alvo-id :alvo-nome}]} em ordem (lote)."
  [tx ente-id ids]
  (let [ids (vec (distinct (remove nil? ids)))]
    (if (empty? ids)
      {}
      (reduce (fn [m r] (update m (:comunicado-id r) (fnil conj []) (select-keys r [:tipo :alvo-id :alvo-nome])))
              {}
              (linhas tx {:select [:comunicado_id :tipo :alvo_id :alvo_nome] :from [:comunicacao.destino]
                          :where [:and [:= :ente_id ente-id] [:in :comunicado_id ids]]
                          :order-by [[:comunicado_id :asc] [:ordem :asc]]})))))

(defn anexos-de [tx ente-id comunicado-id]
  (linhas tx {:select [:id :comunicado_id :nome :tipo_midia :bytes :sha256 :chave_objeto :enviado_em]
              :from [:comunicacao.anexo]
              :where [:and [:= :ente_id ente-id] [:= :comunicado_id comunicado-id]]
              :order-by [[:enviado_em :asc] [:id :asc]]}))

(defn contar-anexos
  "{comunicado-id n} (lote)."
  [tx ente-id ids]
  (let [ids (vec (distinct (remove nil? ids)))]
    (if (empty? ids)
      {}
      (into {} (map (juxt :comunicado-id :n))
            (linhas tx {:select [:comunicado_id [[:count :*] :n]] :from [:comunicacao.anexo]
                        :where [:and [:= :ente_id ente-id] [:in :comunicado_id ids]]
                        :group-by [:comunicado_id]})))))

(defn anexo [tx ente-id comunicado-id anexo-id]
  (linha tx {:select [:id :comunicado_id :nome :tipo_midia :bytes :sha256 :chave_objeto :enviado_em]
             :from [:comunicacao.anexo]
             :where [:and [:= :ente_id ente-id] [:= :comunicado_id comunicado-id] [:= :id anexo-id]]}))

(defn destinatario
  "A linha da pessoa na lista congelada do comunicado, ou nil (ela nao e' destinataria)."
  [tx ente-id comunicado-id identidade-id]
  (linha tx {:select [:identidade_id :nome :via] :from [:comunicacao.destinatario]
             :where [:and [:= :ente_id ente-id] [:= :comunicado_id comunicado-id] [:= :identidade_id identidade-id]]}))

(defn contar-destinatarios [tx ente-id comunicado-id]
  (:n (linha tx {:select [[[:count :*] :n]] :from [:comunicacao.destinatario]
                 :where [:and [:= :ente_id ente-id] [:= :comunicado_id comunicado-id]]})))

;; ---------- marcas (Eixo 4) ----------

(def ^:private tipos-que-gravar
  "Gravar `lido` grava `recebido` se faltar; `ciente` grava os dois."
  {"recebido" ["recebido"] "lido" ["recebido" "lido"] "ciente" ["recebido" "lido" "ciente"]})

(defn marcar!
  "Grava a marca `tipo` (e as anteriores que faltarem) para as pessoas destinatarias dos `comunicado-ids` — so' a
  PRIMEIRA ocorrencia vale (ON CONFLICT DO NOTHING). So' grava para quem esta' na lista (o SELECT casa a lista; a FK
  tambem garante)."
  [tx ente-id identidade-id comunicado-ids tipo]
  (let [ids (vec (distinct (remove nil? comunicado-ids)))]
    (when (seq ids)
      (doseq [t (tipos-que-gravar tipo)]
        (let [[sel & params] (sql/format {:select [:d.ente_id :d.comunicado_id :d.identidade_id [[:inline t]]]
                                          :from [[:comunicacao.destinatario :d]]
                                          :where [:and [:= :d.ente_id ente-id] [:= :d.identidade_id identidade-id]
                                                  [:in :d.comunicado_id ids]]})]
          (jdbc/execute-one! tx (into [(str "INSERT INTO comunicacao.marca (ente_id, comunicado_id, identidade_id, tipo) "
                                            sel " ON CONFLICT (ente_id, comunicado_id, identidade_id, tipo) DO NOTHING")]
                                      params)))))))

(defn marcas-da-pessoa
  "{comunicado-id {:recebido-em :lido-em :ciente-em}} da pessoa nos `ids`."
  [tx ente-id identidade-id ids]
  (let [ids (vec (distinct (remove nil? ids)))]
    (if (empty? ids)
      {}
      (reduce (fn [m {:keys [comunicado-id tipo em]}]
                (assoc-in m [comunicado-id (keyword (str tipo "-em"))] em))
              {}
              (linhas tx {:select [:comunicado_id :tipo :em] :from [:comunicacao.marca]
                          :where [:and [:= :ente_id ente-id] [:= :identidade_id identidade-id]
                                  [:in :comunicado_id ids]]})))))

;; ---------- a caixa ----------

(defn caixa
  "Os comunicados em que a pessoa e' destinataria, mais recentes primeiro (teto), cada um com o caminho dela."
  [tx ente-id identidade-id limite]
  (linhas tx {:select (conj cols :d.via)
              :from [[:comunicacao.destinatario :d]]
              :join [[:comunicacao.comunicado :c] [:and [:= :c.ente_id :d.ente_id] [:= :c.id :d.comunicado_id]]]
              :where [:and [:= :d.ente_id ente-id] [:= :d.identidade_id identidade-id]]
              :order-by [[:c.enviado_em :desc] [:c.ano :desc] [:c.numero :desc]]
              :limit limite}))

(defn- sem-marca [tipo]
  [:not [:exists {:select [1] :from [[:comunicacao.marca :m]]
                  :where [:and [:= :m.ente_id :d.ente_id] [:= :m.comunicado_id :d.comunicado_id]
                          [:= :m.identidade_id :d.identidade_id] [:= :m.tipo tipo]]}]])

(defn resumo-da-caixa
  "Os TOTAIS da caixa (fora do teto da lista): `:nao-lidos` e os comunicados que ainda pedem a ciencia da pessoa
  (`:pendentes` [{:id :ciencia-ate}]) — a tela mostra o numero e o prazo mais proximo."
  [tx ente-id identidade-id]
  {:nao-lidos (:n (linha tx {:select [[[:count :*] :n]] :from [[:comunicacao.destinatario :d]]
                             :where [:and [:= :d.ente_id ente-id] [:= :d.identidade_id identidade-id]
                                     (sem-marca "lido")]}))
   :pendentes (linhas tx {:select [:c.id :c.ciencia_ate]
                          :from [[:comunicacao.destinatario :d]]
                          :join [[:comunicacao.comunicado :c] [:and [:= :c.ente_id :d.ente_id] [:= :c.id :d.comunicado_id]]]
                          :where [:and [:= :d.ente_id ente-id] [:= :d.identidade_id identidade-id]
                                  [:= :c.exige_ciencia true] (sem-marca "ciente")]})})

;; ---------- enviados e leitura ----------

(defn- contagem [tipo]
  {:select [[[:count :*]]] :from [[:comunicacao.marca :m]]
   :where [:and [:= :m.ente_id :c.ente_id] [:= :m.comunicado_id :c.id] [:= :m.tipo tipo]]})

(defn enviados
  "Os comunicados enviados pela pessoa (`remetente-id`), ou os da Casa inteira (nil), mais recentes primeiro (teto),
  cada um com as contagens do painel: destinatarios, recebidos, lidos, cientes."
  [tx ente-id remetente-id limite]
  (linhas tx {:select (into cols
                            [[{:select [[[:count :*]]] :from [[:comunicacao.destinatario :d]]
                               :where [:and [:= :d.ente_id :c.ente_id] [:= :d.comunicado_id :c.id]]}
                              :destinatarios]
                             [(contagem "recebido") :recebidos]
                             [(contagem "lido") :lidos]
                             [(contagem "ciente") :cientes]])
              :from [[:comunicacao.comunicado :c]]
              :where (cond-> [:and [:= :c.ente_id ente-id]]
                       remetente-id (conj [:= :c.remetente_identidade_id remetente-id]))
              :order-by [[:c.enviado_em :desc] [:c.ano :desc] [:c.numero :desc]]
              :limit limite}))

(defn leitura
  "Uma linha por destinatario, por nome: o caminho e a hora de cada marca."
  [tx ente-id comunicado-id]
  (let [marca (fn [tipo] {:select [:m.em] :from [[:comunicacao.marca :m]]
                          :where [:and [:= :m.ente_id :d.ente_id] [:= :m.comunicado_id :d.comunicado_id]
                                  [:= :m.identidade_id :d.identidade_id] [:= :m.tipo tipo]]})]
    (linhas tx {:select [:d.identidade_id :d.nome :d.via
                         [(marca "recebido") :recebido_em] [(marca "lido") :lido_em] [(marca "ciente") :ciente_em]]
                :from [[:comunicacao.destinatario :d]]
                :where [:and [:= :d.ente_id ente-id] [:= :d.comunicado_id comunicado-id]]
                :order-by [[[:lower :d.nome] :asc] [:d.identidade_id :asc]]})))

;; ---------- anexos (fatia 2) ----------

(defn travar-anexos!
  "Serializa os anexos do MESMO comunicado nesta tx (trava consultiva de transacao — o comunicado e' INSERT-only, sem
  GRANT de UPDATE para um `FOR UPDATE`): dois envios ao mesmo tempo nao passam juntos do limite de 5."
  [tx ente-id comunicado-id]
  (jdbc/execute-one! tx ["SELECT pg_advisory_xact_lock(hashtextextended(?, 2020100300181))"
                         (str "anexos-do-comunicado:" ente-id ":" comunicado-id)]))

(defn inserir-anexo! [tx {:keys [id ente-id comunicado-id nome tipo-midia bytes sha256 chave-objeto]}]
  (linha tx {:insert-into :comunicacao.anexo
             :values [{:id id :ente_id ente-id :comunicado_id comunicado-id :nome nome :tipo_midia tipo-midia
                       :bytes bytes :sha256 sha256 :chave_objeto chave-objeto}]
             :returning [:id :comunicado_id :nome :tipo_midia :bytes :sha256 :chave_objeto :enviado_em]}))
