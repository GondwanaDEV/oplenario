(ns oplenario.legislativo.db.regra-votacao
  "A regra de votacao por classe de materia (`legislativo.regra_votacao_materia`), conferida ao ABRIR a votacao, na tx
  do INSERT. Hoje duas classes: as contas do Prefeito (CF art. 31 §2, ADR-0021 B2) e a emenda a Lei Organica (CF art.
  29). A guarda e' DSL do MESMO motor da tramitacao (`motor/guarda-dsl`, disciplina 5) sobre
  {\"votacao\" {:quorum_tipo :modalidade}}; a escolha da linha pela materia e as frases moram em
  `logic/regra_votacao`. Fora da regra -> `:conflito/regra-de-votacao` (a borda responde 422 com a regra em palavras).
  Regra ausente = fail-closed (a mesma recusa, com a causa). Materia sem regra: no-op."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.db.prestacao-contas :as prestacao-contas]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.legislativo.logic.regra-votacao :as regra-votacao]
            [oplenario.motor.api :as motor]))

(set! *warn-on-reflection* true)

(defn regra
  "A regra de votacao da classe de materia `chave` ({:chave :guarda :referencia :turnos :intersticio-dias}), ou nil."
  [tx chave]
  (comum/linha->kebab
   (jdbc/execute-one! tx (sql/format {:select [:chave :guarda :referencia :turnos :intersticio_dias]
                                      :from [:legislativo.regra_votacao_materia]
                                      :where [:= :chave chave]}))))

(defn chave-da-materia
  "A chave da regra que vale para o objeto da votacao, ou nil. So' a proposicao tem regra (emenda, parecer e
  requerimento votam com o quorum da Mesa)."
  [tx ente-id objeto-tipo objeto-id]
  (when (= "proposicao" objeto-tipo)
    (regra-votacao/chave-da-materia (proposicao/buscar tx ente-id objeto-id)
                                    (prestacao-contas/da-proposicao tx ente-id objeto-id))))

(defn regra-da-materia
  "A regra que vale para a proposicao `proposicao-id`, ou nil. Para quem precisa dos turnos fora da abertura."
  [tx ente-id proposicao-id]
  (some->> (chave-da-materia tx ente-id "proposicao" proposicao-id) (regra tx)))

(defn conferir!
  "Ao ABRIR a votacao: a abertura tem de passar na guarda da regra da materia. Ver o docstring do ns."
  [tx ente-id registro {:keys [objeto-tipo objeto-id quorum-tipo modalidade]}]
  (when-let [chave (chave-da-materia tx ente-id objeto-tipo objeto-id)]
    (let [r (regra tx chave)
          recusa (fn [motivo]
                   (throw (ex-info motivo {:tipo :conflito/regra-de-votacao :regra chave :referencia (:referencia r)})))]
      (when-not r
        (recusa (regra-votacao/regra-ausente chave)))
      (when-not ((motor/guarda-dsl {:registro registro :tx tx :expr (:guarda r) :agora nil :ente-id ente-id})
                 {"votacao" {:quorum_tipo quorum-tipo :modalidade modalidade}})
        (recusa (regra-votacao/recusa-da-guarda chave (:referencia r))))
      r)))
