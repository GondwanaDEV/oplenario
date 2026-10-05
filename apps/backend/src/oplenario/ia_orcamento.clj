(ns oplenario.ia-orcamento
  "Host (§22.10: raiz de composicao): o OPERADOR define o orcamento de IA de uma Casa (ADR-0014) e o ato fica na atuacao
  da Operacao (ADR-0016). O orcamento mora no `integracao_ia` (role de tenant, RLS) e a atuacao no `admin_sistema` (role
  do operador): as duas escritas NAO cabem na mesma transacao sem atravessar a fronteira, e cada modulo so' se fala por
  seu Repo. O registro e' por isso o PAR da ADR-0017 (adendo de 05/10/2026, 'comando de operador com Casa'):

  1. a TENTATIVA vai para a atuacao ANTES do efeito, com o que se pretende e o que valia (`antes`). Se ela nao grava, o
     comando nao roda: comando de operador sem registro e' exatamente a lacuna que isto fecha (nao e' o plenario ao
     vivo: nada urgente fica trancado atras disso);
  2. o efeito (orcamento + `OrcamentoIADefinido` no feed, numa tx, como sempre);
  3. o DESFECHO (`ia-orcamento-definido`, ou `ia-orcamento-falhou` com o motivo), apontando a tentativa em
     `detalhe.tentativa`. Se o desfecho nao grava, o comando acusa em voz alta que o orcamento FOI definido e a
     tentativa fica sem desfecho: `repo-admin/tentativas-sem-desfecho` a acusa na conferencia.

  A linha de comando nao tem pessoa: `:operador-id` fica nulo e o registro diz `origem: linha-de-comando` (a ficha da
  Casa le isso e nao confunde com 'pela propria camara', que e' o que o operador nulo significa sozinho). Valores como
  texto decimal: o selo da atuacao e' recalculado a partir do jsonb e numero muda de forma na ida e volta."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [oplenario.admin-sistema.components.repositorio :as repo-admin]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]))

(def acao-tentativa "ia-orcamento-iniciado")
(def acao-definido "ia-orcamento-definido")
(def acao-falhou "ia-orcamento-falhou")

(defn- texto [v] (some-> v bigdec .stripTrailingZeros .toPlainString))

(defn- valores
  "{:mensal :teto-duro :moeda} em texto, ou nil quando nao ha' valor (a Casa so' mede)."
  [{:keys [mensal teto-duro moeda]}]
  (when (some? mensal) {:mensal (texto mensal) :teto-duro (texto teto-duro) :moeda moeda}))

(defn- primeira-linha [^Throwable e]
  (let [m (or (ex-message e) (.getName (class e)))]
    (subs (first (str/split-lines m)) 0 (min 200 (count (first (str/split-lines m)))))))

(defn- resumo [antes depois origem]
  (str "Orcamento de IA: mensal "
       (if antes (str "de " (:mensal antes) " para ") "definido em ") (:mensal depois)
       "; teto duro " (if antes (str "de " (:teto-duro antes) " para ") "definido em ") (:teto-duro depois)
       " (" (:moeda depois) "). "
       (if (= "linha-de-comando" origem)
         "Definido pela linha de comando, sem pessoa identificada."
         "Definido pelo operador.")))

(defn definir!
  "Define o orcamento de IA da Casa e deixa o par na atuacao. `deps` = {:repo-ia :repo-op}. Devolve a definicao.
  `:operador-id` (opcional) e `:origem` (padrao `linha-de-comando`) dizem quem e de onde."
  [{:keys [repo-ia repo-op]} {:keys [ente-id mensal teto-duro moeda definido-por operador-id origem]
                              :or {origem "linha-de-comando"}}]
  (let [moeda (or moeda "USD")
        pedido {:ente-id ente-id :mensal mensal :teto-duro teto-duro :moeda moeda :definido-por definido-por}
        antes (valores (repo-ia/orcamento-atual repo-ia ente-id))
        pretendido (valores pedido)
        base {:origem origem :antes antes :depois pretendido}
        tentativa (repo-admin/registrar-atuacao!
                   repo-op {:operador-id operador-id :ente-id ente-id :acao acao-tentativa :detalhe base})
        aponta {:tentativa (str (:id tentativa))}
        d (try (repo-ia/definir-orcamento! repo-ia pedido)
               (catch Exception e
                 (try (repo-admin/registrar-atuacao!
                       repo-op {:operador-id operador-id :ente-id ente-id :acao acao-falhou
                                :detalhe (assoc base :tentativa (:tentativa aponta) :motivo (primeira-linha e))})
                      (catch Exception e2
                        (log/error e2 "ia-orcamento: o desfecho de FALHA nao foi gravado na atuacao"
                                   {:ente-id ente-id :tentativa (:tentativa aponta)})))
                 (throw e)))
        depois (valores d)]
    (try (repo-admin/registrar-atuacao!
          repo-op {:operador-id operador-id :ente-id ente-id :acao acao-definido
                   :detalhe (assoc base :tentativa (:tentativa aponta) :depois depois
                                   :resumo (resumo antes depois origem))})
         (catch Exception e
           (log/error e "ia-orcamento: orcamento DEFINIDO, desfecho nao gravado na atuacao"
                      {:ente-id ente-id :tentativa (:tentativa aponta)})
           (throw (ex-info (str "o orcamento de IA FOI definido, mas o desfecho nao ficou registrado na atuacao da "
                                "Operacao (a tentativa " (:tentativa aponta) " fica sem desfecho): " (primeira-linha e))
                           {:ente-id ente-id :tentativa (:tentativa aponta)} e))))
    d))
