(ns oplenario.auditoria.controllers
  "Os casos de uso da trilha de auditoria (ADR-0017). Os seams do host entram por mapa (§22.10: este modulo nao
  importa identidade nem admin_sistema):
  - `:nome-de` (identidade-id -> nome | nil) — quem agiu, pelo nome;
  - `:atuacao-da-operacao` (ente-id limite -> [{:em :acao :operador-nome :selo}]) — a Operacao na Casa, espelhada;
  - `:ancorar!` (ente-id {:dia :seq :selo} -> _) — o selo do dia ancorado na corrente da Operacao."
  (:require [clojure.tools.logging :as log]
            [oplenario.auditoria.components.repositorio :as repo]
            [oplenario.auditoria.logic :as logic]))

(set! *warn-on-reflection* true)

(defn registrar-requisicao!
  "Do par requisicao/resposta ja' respondido -> o registro na corrente (quando entra na trilha). Uma falha aqui NAO
  desfaz o ato nem muda a resposta: e' logada como erro (ADR-0017, materializacao — o registro e' gravado logo depois
  do ato, na mesma requisicao, antes da resposta sair)."
  [repo-auditoria {:keys [ancorar!]} req resp acao]
  (try
    (when-let [r (logic/registro-da-requisicao req resp acao)]
      (let [{:keys [dia-fechado]} (repo/registrar! repo-auditoria r)]
        (when (and dia-fechado ancorar!)
          (try (ancorar! (:ente-id r) dia-fechado)
               (catch Exception e (log/error e "auditoria: falha ao ancorar o selo do dia" (:ente-id r)))))))
    (catch Exception e
      (log/error e "auditoria: registro NAO gravado" {:acao acao :status (:status resp)}))))

(defn- com-nomes
  "Os registros com o nome de quem agiu (pessoa) ou o pseudonimo (cidadao). Uma consulta por pessoa distinta."
  [{:keys [nome-de]} ente-id registros]
  (let [ids   (into #{} (comp (filter #(= "pessoa" (:ator-tipo %))) (keep :identidade-id)) registros)
        nomes (into {} (map (fn [id] [id (when nome-de (nome-de id))])) ids)]
    (mapv (fn [r]
            (cond-> r
              (= "pessoa" (:ator-tipo r)) (assoc :ator-nome (get nomes (:identidade-id r)))
              (= "cidadao" (:ator-tipo r)) (assoc :ator-pseudonimo (logic/pseudonimo ente-id (:identidade-id r)))))
          registros)))

(defn trilha
  "A pagina da trilha que o ator pode ver (escopo pelo papel). Para o `auditor`, vem tambem o total da corrente e a
  atuacao da Operacao nesta Casa."
  [repo-auditoria seams ator filtro limite]
  (let [ente   (:ente-id ator)
        escopo (logic/escopo ator)
        pagina (repo/trilha repo-auditoria ente (assoc filtro :escopo escopo) limite)]
    {:escopo    (name (:tipo escopo))
     :registros (com-nomes seams ente (:registros pagina))
     :total     (:total pagina)
     :total-da-casa (when (= :casa (:tipo escopo)) (repo/total repo-auditoria ente))
     :operacao  (when (and (= :casa (:tipo escopo)) (nil? (:antes-de filtro)) (:atuacao-da-operacao seams))
                  ((:atuacao-da-operacao seams) ente 20))
     :limite    limite}))

(defn integridade
  "A corrente da Casa conferida inteira + os ultimos 30 selos do dia (so' o `auditor`)."
  [repo-auditoria ente-id]
  (assoc (repo/verificar repo-auditoria ente-id) :selos-do-dia (repo/selos-do-dia repo-auditoria ente-id 30)))

(defn selos-publicos
  "O que o portal publica: os ultimos 30 selos do dia (sem nenhum registro)."
  [repo-auditoria ente-id]
  (repo/selos-do-dia repo-auditoria ente-id 30))

(def teto-exportacao 50000)

(defn exportar
  "Todos os registros do filtro (ate o teto), mais antigo primeiro, com nomes — para o CSV."
  [repo-auditoria seams ator filtro]
  (let [ente (:ente-id ator)
        {:keys [registros]} (repo/trilha repo-auditoria ente (assoc filtro :escopo (logic/escopo ator)) teto-exportacao)]
    (reverse (com-nomes seams ente registros))))
