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

(defn ligar-identidade!
  "Liga o vereador a' identidade (Onda D Slice 5 Task 9 — passo (2) do provisionamento; GUARD ref, sem FK
   cross-schema, §22.10 — a existencia de `identidade-id` e' checada pelo guard de servico injetado na
   borda, nunca aqui). `ente_id` no WHERE alem da RLS (defesa em profundidade, mesmo padrao de atualizar!/
   mudar-estado!). Idempotente (re-ligar a MESMA identidade e' um no-op valido). Devolve o update-count (0 =
   vereador inexistente/de-outro-tenant). Colisao com o indice UNIQUE parcial (outro vereador desta Casa ja'
   ligado a esta identidade) sobe como PSQLException 23505 — o Repo (components/repositorio.clj) converte
   p/ :conflito/identidade-ja-vinculada, nunca tratada aqui (mesmo padrao de inserir-mandato!/
   registrar-mandato!)."
  [tx ente-id id identidade-id]
  (:next.jdbc/update-count
   (jdbc/execute-one! tx
     (sql/format {:update :cadastros.vereador
                  :set {:identidade_id identidade-id}
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn atualizar!
  "UPDATE de nome/nome-parlamentar da linha EFETIVADA do vereador. So' seta as chaves PRESENTES em `campos`
   (:nome / :nome-parlamentar) — um PATCH parcial nunca zera o campo que o cliente nao mandou. Devolve o
   update-count (0 = linha inexistente/outro-tenant, ja' filtrada pela RLS). Defesa em profundidade: casa
   ente_id explicito + efetivado_em NOT NULL (a RLS ja' esconde staging, isto e' cinto-e-suspensorio)."
  [tx ente-id id campos]
  (let [set-map (cond-> {}
                  (contains? campos :nome)             (assoc :nome (:nome campos))
                  (contains? campos :nome-parlamentar) (assoc :nome_parlamentar (:nome-parlamentar campos)))
        r (jdbc/execute-one! tx
            (sql/format {:update :cadastros.vereador
                         :set set-map
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:is-not :efetivado_em nil]]}))]
    (:next.jdbc/update-count r)))

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
  "Transicao de estado do mandato (cassacao/renuncia/licenca/...). fim-efetivo opcional. Defesa em
  profundidade: casa ente_id explicito no WHERE (a RLS ja' filtra o tenant — cinto-e-suspensorio, mesmo
  padrao de atualizar!)."
  [tx ente-id {:keys [id estado fim-efetivo]}]
  (jdbc/execute-one! tx
    (sql/format {:update :cadastros.mandato
                 :set {:estado estado :fim_efetivo [:coalesce fim-efetivo :fim_efetivo]}
                 :where [:and [:= :ente_id ente-id] [:= :id id]]})))

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

(defn mandato-vigente-de-vereador
  "O mandato com estado='vigente' que COBRE `data` (p/ a licenca resolver o alvo). nil se nenhum — cobre
   'sem mandato vigente' E 'ja' licenciado' (um licenciado tem estado != 'vigente'). Tie-break por :id."
  [tx ente-id vereador-id data]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :vereador_id :estado :vigencia_inicio :vigencia_fim]
                   :from [:cadastros.mandato]
                   :where [:and [:= :ente_id ente-id] [:= :vereador_id vereador-id]
                           [:= :estado "vigente"]
                           [:<= :vigencia_inicio data]
                           [:or [:is :vigencia_fim nil] [:>= :vigencia_fim data]]]
                   :order-by [[:vigencia_inicio :desc] [:id]] :limit 1}))))

(defn mandato-sobreposto?
  "True se JA' existe mandato 'vigente' efetivado do vereador cuja vigencia sobrepoe [inicio, fim] (fim nil
   = aberto = 'infinity'). Guard app-level (UX 409); o EXCLUDE (migration ...59) e' a rede. SQL cru
   parametrizado — o operador `&&` de daterange e' direto assim (o db_test ja' usa SQL cru p/ casos pontuais)."
  [tx ente-id vereador-id inicio fim]
  (some?
    (jdbc/execute-one! tx
      ["SELECT 1 FROM cadastros.mandato
        WHERE ente_id = ? AND vereador_id = ? AND estado = 'vigente' AND efetivado_em IS NOT NULL
          AND daterange(vigencia_inicio, COALESCE(vigencia_fim, 'infinity'::date), '[]')
              && daterange(?::date, COALESCE(?::date, 'infinity'::date), '[]')
        LIMIT 1"
       ente-id vereador-id inicio fim])))

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
(defn licencas-de-mandatos
  "As licencas registradas de um CONJUNTO de mandatos (I-5 fatia 3 — os buracos a subtrair da janela de
   exercicio). Servido pelo `idx_mandato_licenca_mandato (ente_id, mandato_id)` da mig 0010:253.

   `mandato-ids` VAZIO/nil devolve `[]` SEM tocar o banco: alem de `IN ()` ser SQL invalido, o caminho
   'vereador sem nenhum mandato' e' justamente o que nao deve gastar round-trip numa rota publica anonima.

   Devolve so' `{:mandato-id :inicio :fim}` — `fim` nil e' licenca EM CURSO (aberta), nao ausencia de dado
   (`mandato_licenca.fim` e' nullable na mig 0010; `inicio` e' `date NOT NULL`). Colunas `date` chegam como
   `java.time.LocalDate` (`kernel/db_tipos` estende ReadableColumn p/ `java.sql.Date`). NAO devolve
   `mandato_suplente_id` nem `motivo`: o unico leitor (a janela de exercicio) so' precisa do intervalo, e
   `motivo` e' dado potencialmente sensivel de saude que nao deve escorrer p/ uma leitura publica.

   Defesa em profundidade: `ente_id` explicito no WHERE alem da RLS (mesmo padrao de `buscar`/`atualizar!`).
   Nenhum teste COMPORTAMENTAL consegue falsificar esse predicado — a RLS (FORCE + policy sobre o GUC
   `app.ente_id`) ja' bloqueia o cross-tenant em qualquer formato de tx, e a mutacao `[:= 1 1]` deixa o ns
   verde; quem o pina e' o guard de FONTE `licencas-de-mandatos-tem-ente-id-explicito-no-where`.

   Ordem deterministica por (inicio, id): o consumidor previsto (`kernel/tempo/normalizar-intervalos`)
   ordena e nao depende dela, mas a ordem e' pinada por assercao em
   `ficha-e-mandatos-devolve-licencas-do-vereador-e-nenhuma-de-outro` (duas licencas inseridas fora de
   ordem) — remover o `:order-by` fica vermelho."
  [tx ente-id mandato-ids]
  (if (empty? mandato-ids)
    []
    (comum/linhas->kebab
      (jdbc/execute! tx
        (sql/format {:select [:mandato_id :inicio :fim]
                     :from [:cadastros.mandato_licenca]
                     :where [:and [:= :ente_id ente-id] [:in :mandato_id (vec mandato-ids)]]
                     :order-by [[:inicio] [:id]]})))))

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
