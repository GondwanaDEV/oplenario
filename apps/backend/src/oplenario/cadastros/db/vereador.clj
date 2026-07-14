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

(defn buscar
  "Defesa em profundidade (RLS ja isola por tenant): filtra tambem por `ente-id` explicito — o unico read
   de 1 vereador so' com `id` na aggregate; ambos os callers (repositorio.clj) ja tem `ente-id` em escopo."
  [tx ente-id id]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :ente_id :identidade_id :nome :nome_parlamentar]
                   :from [:cadastros.vereador]
                   :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

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
   O mais recente se houver mais de um; tie-break deterministico por :id (mesmo padrao de
   relacoes/cadastro.clj `quem-exerce-presidencia`) — sem DB constraint que impeca vigencias sobrepostas.
   nil se nenhum cobre `data`."
  [tx ente-id vereador-id data]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :vereador_id :legislatura_id :partido :estado :natureza
                            :vigencia_inicio :vigencia_fim :fim_efetivo]
                   :from [:cadastros.mandato]
                   :where [:and [:= :ente_id ente-id] [:= :vereador_id vereador-id]
                           [:<= :vigencia_inicio data]
                           [:or [:is :vigencia_fim nil] [:>= :vigencia_fim data]]]
                   :order-by [[:vigencia_inicio :desc] [:id]] :limit 1}))))

(defn listar
  "Lista de vereadores da Casa com o mandato que cobre `data` (partido/estado) e o cargo na Mesa vigente.
   Vereador sem mandato corrente aparece so' com o nome (estado-mandato/partido nil). Ordena por nome.

   Sem DB constraint que impeca vigencias sobrepostas (mandato/comissao_cargo), um vereador pode ter MAIS
   de uma linha cobrindo `data` — sem dedup, o LEFT JOIN plano faria FAN-OUT (o vereador apareceria
   duplicado na lista). Por isso o mandato e o cargo-na-Mesa vem cada um de um LEFT JOIN LATERAL que ja'
   escolhe o vencedor deterministico (mais recente por vigencia_inicio, tie-break por id — mesmo criterio
   de `mandato-vigente`/`quem-exerce-presidencia`), garantindo UMA linha por vereador."
  [tx ente-id data]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format
        {:select [:v.id :v.nome :v.nome_parlamentar :m.partido
                  [:m.estado :estado_mandato] [:cc.cargo :cargo_mesa]]
         :from [[:cadastros.vereador :v]]
         :left-join [[[:lateral
                       {:select [:mm.partido :mm.estado]
                        :from [[:cadastros.mandato :mm]]
                        :where [:and [:= :mm.vereador_id :v.id] [:= :mm.ente_id :v.ente_id]
                                [:<= :mm.vigencia_inicio data]
                                [:or [:is :mm.vigencia_fim nil] [:>= :mm.vigencia_fim data]]]
                        :order-by [[:mm.vigencia_inicio :desc] [:mm.id]]
                        :limit 1}]
                      :m] true
                     [[:lateral
                       {:select [:cc2.cargo]
                        :from [[:cadastros.comissao_cargo :cc2]]
                        :join [[:cadastros.comissao :mesa2]
                               [:and [:= :mesa2.id :cc2.comissao_id] [:= :mesa2.tipo "mesa"] [:= :mesa2.ente_id :v.ente_id]
                                [:<= :mesa2.vigencia_inicio data]
                                [:or [:is :mesa2.vigencia_fim nil] [:>= :mesa2.vigencia_fim data]]]]
                        :where [:and [:= :cc2.vereador_id :v.id] [:= :cc2.ente_id :v.ente_id]
                                [:<= :cc2.vigencia_inicio data]
                                [:or [:is :cc2.vigencia_fim nil] [:>= :cc2.vigencia_fim data]]]
                        :order-by [[:cc2.vigencia_inicio :desc] [:cc2.id]]
                        :limit 1}]
                      :cc] true]
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
