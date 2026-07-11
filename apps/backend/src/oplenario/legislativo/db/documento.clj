(ns oplenario.legislativo.db.documento
  "Persistencia do DOCUMENTO gerado (F3.9b). `gerar!` aplica o MERGE (logic/renderizar-documento: template do
  modelo + `dados` resolvidos UPSTREAM) e insere 'rascunho' — atomico na tx; guarda o snapshot `dados_merge`
  (auditoria). `emitir!` move rascunho -> emitido (o trigger congela o conteudo). `editar-rascunho!` reescreve
  o corpo enquanto rascunho (CAS). Sobre a `tx` do tenant; HoneySQL schema-qualified; ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :modelo_id :tipo_documento :assunto :corpo :estado :protocolo_geral_id
   :emitido_em :emitido_por :lock_version :criado_em])

(defn gerar!
  "Gera um documento a partir do modelo: renderiza o corpo (merge de `dados` no `corpo-template`), grava o
  snapshot `dados-merge` e insere 'rascunho'. `tipo-documento`/`corpo-template` vem do modelo (resolvido
  UPSTREAM). `renderizar-documento` lanca se faltar campo (fail-closed). Devolve {:id :corpo}."
  [tx {:keys [id ente-id modelo-id tipo-documento corpo-template assunto dados protocolo-geral-id created-by]}]
  (let [dados-ef (or dados {})
        corpo (logic/renderizar-documento corpo-template dados-ef)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :legislativo.documento
                   :values [{:id id :ente_id ente-id :modelo_id modelo-id :tipo_documento tipo-documento
                             :assunto assunto :corpo corpo :dados_merge (comum/->jsonb dados-ef)
                             :estado "rascunho" :protocolo_geral_id protocolo-geral-id
                             :created_by created-by :efetivado_em [:now]}]}))
    {:id id :corpo corpo}))

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.documento]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn listar-por-modelo [tx ente-id modelo-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas :from [:legislativo.documento]
                  :where [:and [:= :ente_id ente-id] [:= :modelo_id modelo-id]]
                  :order-by [[:criado_em :desc]]}))))

(defn- estado+lock [tx ente-id id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version] :from [:legislativo.documento]
                     :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
      comum/linha->kebab))

(defn editar-rascunho!
  "Reescreve o corpo/assunto/protocolo enquanto 'rascunho' (CAS). Guard fail-closed: documento emitido nao
  edita (o trigger tambem barra, mas a mensagem aqui e' a causa real). `:tipo :validacao/invalido` no guard
  de estado e no conflito de CAS (nao na inexistencia — essa e' pre-checada pelo diplomat antes de chamar
  esta fn, mesmo contrato de db/proposicao.clj/editar!): sem a tag, o interceptor global `erro` so' mapeia
  :validacao/invalido -> 400 e o resto cai no fallback -> 500 opaco, num caminho (CAS perdido por concorrencia,
  ou reenvio apos emissao) que e' fluxo normal de PATCH, nao bug de servidor. Devolve {:id}."
  [tx {:keys [id ente-id corpo assunto protocolo-geral-id updated-by lock-version]}]
  (let [{:keys [estado]} (estado+lock tx ente-id id)]
    (when (nil? estado)
      (throw (ex-info "editar-rascunho!: documento inexistente" {:id id :ente-id ente-id})))
    (when (not= "rascunho" estado)
      (throw (ex-info "editar-rascunho!: so se edita um documento 'rascunho'"
                      {:tipo :validacao/invalido :id id :estado estado})))
    (let [r (jdbc/execute-one! tx
              (sql/format {:update :legislativo.documento
                           :set (cond-> {:updated_by updated-by :atualizado_em [:now] :lock_version [:+ :lock_version 1]}
                                  (some? corpo)              (assoc :corpo corpo)
                                  (some? assunto)            (assoc :assunto assunto)
                                  (some? protocolo-geral-id) (assoc :protocolo_geral_id protocolo-geral-id))
                           :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
      (when (zero? (:next.jdbc/update-count r 0))
        (throw (ex-info "editar-rascunho!: conflito de lock_version ou inexistente"
                        {:tipo :validacao/invalido :id id :lock-version lock-version})))
      {:id id})))

(defn emitir!
  "Emite o documento: 'rascunho' -> 'emitido' (carimba emitido_em/por; o trigger congela o conteudo). CAS por
  lock_version. Guard fail-closed: so se emite um 'rascunho'. `protocolo-geral-id` OPCIONAL (Onda B Slice 6):
  quando a emissao acontece como parte de Repo/protocolar-documento! (o CTA 'Protocolar e numerar' compoe
  protocolo-geral/protocolar! + este emitir! NUMA SO tx), o vinculo ao Protocolo Geral e' setado na MESMA
  UPDATE — aditivo, o SET so' inclui a coluna quando o valor vem presente (nil = omitido, mesma disciplina
  do cond-> em editar-rascunho!); quem emite sem protocolar (nao ha' fluxo assim nesta fatia, mas o contrato
  fica correto) simplesmente nao passa o campo. Devolve {:id :estado}."
  [tx {:keys [id ente-id emitido-por updated-by lock-version protocolo-geral-id]}]
  (let [{:keys [estado]} (estado+lock tx ente-id id)]
    (when (nil? estado)
      (throw (ex-info "emitir!: documento inexistente" {:id id :ente-id ente-id})))
    (when (not= "rascunho" estado)
      (throw (ex-info "emitir!: so se emite um documento 'rascunho'"
                      {:tipo :validacao/invalido :id id :estado estado})))
    (let [r (jdbc/execute-one! tx
              (sql/format {:update :legislativo.documento
                           :set (cond-> {:estado "emitido" :emitido_em [:now] :emitido_por emitido-por
                                         :updated_by updated-by :atualizado_em [:now] :lock_version [:+ :lock_version 1]}
                                  (some? protocolo-geral-id) (assoc :protocolo_geral_id protocolo-geral-id))
                           :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
      (when (zero? (:next.jdbc/update-count r 0))
        (throw (ex-info "emitir!: conflito de lock_version ou inexistente"
                        {:tipo :validacao/invalido :id id :lock-version lock-version})))
      {:id id :estado "emitido"})))
