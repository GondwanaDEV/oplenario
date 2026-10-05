(ns oplenario.legislativo.db.regra-votacao
  "A regra de votacao por classe de materia (`legislativo.regra_votacao_materia`), conferida ao ABRIR a votacao, na tx
  do INSERT. Hoje duas classes: as contas do Prefeito (CF art. 31 §2, ADR-0021 B2) e a emenda a Lei Organica (CF art.
  29). A guarda e' DSL do MESMO motor da tramitacao (`motor/guarda-dsl`, disciplina 5) sobre
  {\"votacao\" {:quorum_tipo :modalidade}}; a escolha da linha pela materia e as frases moram em
  `logic/regra_votacao`. Fora da regra -> `:conflito/regra-de-votacao` (a borda responde 422 com a regra em palavras).
  Regra ausente = fail-closed (a mesma recusa, com a causa). Materia sem regra: no-op.

  Os TURNOS e o INTERSTICIO da regra (colunas `turnos`/`intersticio_dias`) nao cabem na guarda — sao contagem e
  calendario sobre as votacoes ja' encerradas da materia, nao campo da votacao que abre: `conferir-turno!` os confere
  na abertura, e `db/votacao/aprovacao-vigente` os usa para dizer se a Casa aprovou."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.db.prestacao-contas :as prestacao-contas]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.legislativo.logic.regra-votacao :as regra-votacao]
            [oplenario.legislativo.logic.turnos :as turnos]
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

(defn conferir-turno!
  "Ao ABRIR a votacao, depois de `conferir!` (que devolve a `regra`): na materia de mais de um turno (a emenda a Lei
  Organica: dois, com intersticio de dez dias), a votacao so' abre se ha' turno a votar e o intersticio ja' correu.
  `votacoes` sao as encerradas da materia (`db/votacao/votacoes-da-materia` — lidas pelo chamador: este ns nao le
  `db/votacao`, que le' a regra daqui); `aberta-em` e' o instante da abertura, do relogio da borda (o MESMO que
  `abrir!` grava), e o dia dele na Casa e' o 'hoje' da conta. Recusa = `:conflito/regra-de-votacao` (422 na borda),
  com a data em palavras. Regra nil ou de 1 turno: no-op."
  [regra votacoes aberta-em]
  (when (< 1 (or (:turnos regra) 1))
    (let [r (turnos/para-abrir regra votacoes (turnos/dia aberta-em))]
      (when (:recusa r)
        (throw (ex-info (regra-votacao/recusa-do-turno regra r)
                        (cond-> {:tipo :conflito/regra-de-votacao :regra (:chave regra) :referencia (:referencia regra)
                                 :recusa (:recusa r)}
                          (:a-partir-de r) (assoc :a-partir-de (str (:a-partir-de r))))))))))
