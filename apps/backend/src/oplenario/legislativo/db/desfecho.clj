(ns oplenario.legislativo.db.desfecho
  "Os atos da materia DEPOIS do plenario, lidos das tabelas donas de cada um (docs/16, retriagem linhas 18 e 30):
  a votacao que a aprovou ou rejeitou, o autografo enviado ao Executivo, a resposta do Executivo, a apreciacao do
  veto e a norma (promulgacao e publicacao). Nenhum desses atos move o `estado` do rito, entao a ficha que so' le'
  o historico de tramitacao parava em 'Aguardando pauta'. Daqui sai o payload de `proposicao.desfecho-registrado`,
  montado NA tx do proprio ato (o instante e' o gravado), que o portal projeta. A ficha interna le' os mesmos atos
  pela rota de pos-aprovacao (PR #158), nao por aqui.

  So' votacao ENCERRADA de objeto que carrega a propria materia (proposicao/redacao final) e EFETIVADA (nao a de
  lote de importacao em staging). Votacao de sessao secreta entra: o RESULTADO e' publico, so' o voto e' secreto.

  Materia de DOIS TURNOS (a emenda a Lei Organica, CF art. 29 — `turnos` da regra de votacao, dado): a votacao de
  turno leva `:turno` (1 ou 2, de `logic/turnos/com-turno`), e o portal diz 'Aprovada em 1º turno' em vez de
  'Aprovada em plenario' — a materia so' esta' aprovada depois do ultimo turno."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.db.regra-votacao :as regra-votacao]
            [oplenario.legislativo.db.votacao :as votacao]
            [oplenario.legislativo.logic.turnos :as turnos]))

(set! *warn-on-reflection* true)

(defn- votacoes-encerradas [tx ente-id pid]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :objeto_tipo :resultado :atualizado_em] :from [:legislativo.votacoes]
                  :where [:and [:= :ente_id ente-id] [:= :objeto_id pid] [:= :estado "encerrada"]
                          [:in :objeto_tipo votacao/objetos-que-carregam-a-materia-sql]
                          [:is-not :efetivado_em nil]]
                  :order-by [[:atualizado_em :asc] [:id :asc]]}))))

(defn- autografo [tx ente-id pid]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [:id :numero :ano :enviado_em] :from [:legislativo.autografo]
                  :where [:and [:= :ente_id ente-id] [:= :proposicao_id pid] [:is-not :efetivado_em nil]]}))))

(defn- executivo [tx ente-id autografo-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [:estado :veto_tipo :respondido_em :apreciado_em] :from [:legislativo.tramitacao_executiva]
                  :where [:and [:= :ente_id ente-id] [:= :autografo_id autografo-id]]}))))

(defn- norma [tx ente-id pid]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [:tipo_norma :numero :ano :promulgado_em :publicado_em] :from [:legislativo.norma]
                  :where [:and [:= :ente_id ente-id] [:= :proposicao_id pid]]}))))

(defn turno-por-votacao
  "{votacao-id turno} das votacoes de turno da materia de mais de um turno; {} na materia sem regra ou de um turno.
  Conta sobre `votacao/votacoes-da-materia` (sem as corrigidas por outra), a mesma lista de `aprovacao-vigente`."
  [tx ente-id pid]
  (let [regra (regra-votacao/regra-da-materia tx ente-id pid)]
    (if (< 1 (or (:turnos regra) 1))
      (into {} (keep #(when (:turno %) [(:id %) (:turno %)]))
            (turnos/com-turno (:turnos regra) (votacao/votacoes-da-materia tx ente-id pid)))
      {})))

(defn- ato-da-votacao [turno-de v]
  (cond-> {:ato (:resultado v) :ocorrido-em (:atualizado-em v)}
    (= "redacao_final" (:objeto-tipo v)) (assoc :redacao-final true)
    (turno-de (:id v)) (assoc :turno (turno-de (:id v)))))

(def ^:private resposta-do-executivo #{"sancionado" "sancao_tacita" "vetado"})

(defn- atos-do-executivo
  "A resposta (sancao/veto) e, se houve, a apreciacao do veto. `estado` guarda so' o ULTIMO: depois da apreciacao
  ele e' veto_mantido/derrubado, e a resposta que o precedeu so' pode ter sido o veto (o rito so' aprecia veto)."
  [{:keys [estado veto-tipo respondido-em apreciado-em]}]
  (cond-> []
    respondido-em (conj (cond-> {:ato (if (resposta-do-executivo estado) estado "vetado") :ocorrido-em respondido-em}
                          veto-tipo (assoc :veto-tipo veto-tipo)))
    apreciado-em (conj {:ato estado :ocorrido-em apreciado-em})))

(defn atos-da-proposicao
  "Os atos depois do plenario, do mais antigo ao mais novo: [{:ato :ocorrido-em ...}]. `ato` e' o vocabulario de
  events.proposicao/atos-de-desfecho mais 'publicada'. Vazio = a materia ainda nao foi a votos."
  [tx ente-id pid]
  (let [aut (autografo tx ente-id pid)
        n (norma tx ente-id pid)]
    (->> (concat
          (map (partial ato-da-votacao (turno-por-votacao tx ente-id pid)) (votacoes-encerradas tx ente-id pid))
          (when aut [{:ato "autografo_enviado" :ocorrido-em (:enviado-em aut) :numero (:numero aut) :ano (:ano aut)}])
          (when aut (some-> (executivo tx ente-id (:id aut)) atos-do-executivo))
          (when n [{:ato "promulgada" :ocorrido-em (:promulgado-em n)
                    :tipo-norma (:tipo-norma n) :numero (:numero n) :ano (:ano n)}])
          (when (and n (:publicado-em n))
            [{:ato "publicada" :ocorrido-em (:publicado-em n)
              :tipo-norma (:tipo-norma n) :numero (:numero n) :ano (:ano n)}]))
         (sort-by :ocorrido-em)
         vec)))

(defn ato-da-votacao-encerrada
  "O ato (aprovada/rejeitada) da votacao `votacao-id` da materia `pid`, ou nil se ela nao carrega a materia."
  [tx ente-id pid votacao-id]
  (some->> (votacoes-encerradas tx ente-id pid)
           (filter #(= votacao-id (:id %)))
           first
           (ato-da-votacao (turno-por-votacao tx ente-id pid))))

(defn ultimo-ato
  "O ato de `ato-nome` mais recente da materia, ja' na forma do payload do evento (sem chaves nil), ou nil."
  [tx ente-id pid ato-nome]
  (some->> (atos-da-proposicao tx ente-id pid)
           (filter #(= ato-nome (:ato %)))
           last))
