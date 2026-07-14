(ns oplenario.cadastros.db.vereador
  "Persistencia de vereador + mandato (entidade com estado, §22.5 eixo C) + licenca + suplencia.
  Funcoes sobre a `tx` do tenant (RLS isola). HoneySQL."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

;; ---- vereador (registro institucional; identidade-id = guard ref ao modulo identidade) ----
(defn inserir!
  ;; criacao NATIVA nasce efetivada (efetivado_em = now()); import (admin_sistema) e' que estaga. Fundacao #2.
  [tx {:keys [id ente-id identidade-id nome nome-parlamentar]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.vereador
                 :values [{:id id :ente_id ente-id :identidade_id identidade-id
                           :nome nome :nome_parlamentar nome-parlamentar :efetivado_em [:now]}]})))

(defn buscar [tx id]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :ente_id :identidade_id :nome :nome_parlamentar]
                   :from [:cadastros.vereador] :where [:= :id id]}))))

(defn por-identidade
  "O vereador vinculado a uma identidade (CPF) neste ente — base da autorizacao por relacao (F2)."
  [tx ente-id identidade-id]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :ente_id :identidade_id :nome :nome_parlamentar]
                   :from [:cadastros.vereador]
                   :where [:and [:= :ente_id ente-id] [:= :identidade_id identidade-id]]}))))

;; ---- mandato ----
(defn inserir-mandato!
  [tx {:keys [id ente-id vereador-id legislatura-id partido estado natureza
              vigencia-inicio vigencia-fim fim-efetivo]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.mandato
                 :values [{:id id :ente_id ente-id :vereador_id vereador-id :legislatura_id legislatura-id
                           :partido partido :estado (or estado "vigente") :natureza (or natureza "titular")
                           :vigencia_inicio vigencia-inicio :vigencia_fim vigencia-fim
                           :fim_efetivo fim-efetivo :efetivado_em [:now]}]})))

(defn mudar-estado!
  "Transicao de estado do mandato (cassacao/renuncia/licenca/...). fim-efetivo opcional."
  [tx {:keys [id estado fim-efetivo]}]
  (jdbc/execute-one! tx
    (sql/format {:update :cadastros.mandato
                 :set {:estado estado :fim_efetivo [:coalesce fim-efetivo :fim_efetivo]}
                 :where [:= :id id]})))

(defn mandatos-do-vereador [tx ente-id vereador-id]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:id :ente_id :vereador_id :legislatura_id :partido :estado :natureza
                            :vigencia_inicio :vigencia_fim :fim_efetivo]
                   :from [:cadastros.mandato]
                   :where [:and [:= :ente_id ente-id] [:= :vereador_id vereador-id]]
                   :order-by [[:vigencia_inicio]]}))))

(defn mandato-vigente
  "O mandato do vereador que COBRE `data` pela vigencia (qualquer estado — licenciado ainda e' o corrente).
   O mais recente se houver mais de um. nil se nenhum cobre `data`."
  [tx ente-id vereador-id data]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :vereador_id :legislatura_id :partido :estado :natureza
                            :vigencia_inicio :vigencia_fim :fim_efetivo]
                   :from [:cadastros.mandato]
                   :where [:and [:= :ente_id ente-id] [:= :vereador_id vereador-id]
                           [:<= :vigencia_inicio data]
                           [:or [:is :vigencia_fim nil] [:>= :vigencia_fim data]]]
                   :order-by [[:vigencia_inicio :desc]] :limit 1}))))

(defn listar
  "Lista de vereadores da Casa com o mandato que cobre `data` (partido/estado) e o cargo na Mesa vigente.
   Vereador sem mandato corrente aparece so' com o nome (estado-mandato/partido nil). Ordena por nome."
  [tx ente-id data]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format
        {:select [:v.id :v.nome :v.nome_parlamentar :m.partido
                  [:m.estado :estado_mandato] [:cc.cargo :cargo_mesa]]
         :from [[:cadastros.vereador :v]]
         :left-join [[:cadastros.mandato :m]
                     [:and [:= :m.vereador_id :v.id] [:= :m.ente_id :v.ente_id]
                      [:<= :m.vigencia_inicio data]
                      [:or [:is :m.vigencia_fim nil] [:>= :m.vigencia_fim data]]]
                     [:cadastros.comissao :mesa]
                     [:and [:= :mesa.tipo "mesa"] [:= :mesa.ente_id :v.ente_id]
                      [:<= :mesa.vigencia_inicio data]
                      [:or [:is :mesa.vigencia_fim nil] [:>= :mesa.vigencia_fim data]]]
                     [:cadastros.comissao_cargo :cc]
                     [:and [:= :cc.comissao_id :mesa.id] [:= :cc.vereador_id :v.id] [:= :cc.ente_id :v.ente_id]
                      [:<= :cc.vigencia_inicio data]
                      [:or [:is :cc.vigencia_fim nil] [:>= :cc.vigencia_fim data]]]]
         :where [:= :v.ente_id ente-id]
         :order-by [[:v.nome :asc]]}))))

;; ---- licenca + suplencia ----
(defn inserir-licenca! [tx {:keys [id ente-id mandato-id mandato-suplente-id inicio fim motivo]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.mandato_licenca
                 :values [{:id id :ente_id ente-id :mandato_id mandato-id :mandato_suplente_id mandato-suplente-id
                           :inicio inicio :fim fim :motivo motivo :efetivado_em [:now]}]})))

(defn inserir-suplencia! [tx {:keys [id ente-id legislatura-id partido vereador-id ordem]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.suplencia
                 :values [{:id id :ente_id ente-id :legislatura_id legislatura-id :partido partido
                           :vereador_id vereador-id :ordem ordem :efetivado_em [:now]}]})))
