(ns oplenario.paineis.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do modulo paineis (§22.10 diplomat/http/in, ADR-0001) — as rotas-dado
  Pedestal + os handlers (F7 Slice 1: o painel 'o que vence', §16.11; F7 Slice 2: board de tramitacao). O
  diplomat e' a UNICA camada que atravessa o gate de borda: chama adapters/out (models->wire) na saida; o
  controller trabalha so' em models. Le o `ator` (posto pela cadeia de auth em (:request :ator)), nunca fala
  com o Repo direto (depende do Repo-Component, injetado por closure via `rotas`)."
  (:require [clojure.tools.logging :as log]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.paineis.adapters.out.mesa :as adapters-out-mesa]
            [oplenario.paineis.adapters.out.pendencia :as adapters-out-pendencia]
            [oplenario.paineis.adapters.out.sli-sessao :as adapters-out-sli-sessao]
            [oplenario.paineis.adapters.out.tramitacao :as adapters-out-tramitacao]
            [oplenario.paineis.controllers :as controllers]))

(set! *warn-on-reflection* true)

(defn- pendencias-handler
  "GET /paineis/pendencias. O controller le' o read-model do tenant do ator; adapters/out projeta+valida."
  [repo-paineis]
  (fn [req]
    (http/json-resposta 200 (adapters-out-pendencia/o-que-vence->wire
                             (controllers/o-que-vence repo-paineis (:ator req))))))

(defn- tramitacao-handler
  "GET /paineis/tramitacao. O controller le' o board do tenant do ator; adapters/out projeta+valida."
  [repo-paineis]
  (fn [req]
    (http/json-resposta 200 (adapters-out-tramitacao/tramitacao-board->wire
                             (controllers/tramitacao-board repo-paineis (:ator req))))))

(defn- sli-sessoes-handler
  "GET /paineis/sli/sessoes. O controller le' o SLI do tenant do ator; adapters/out projeta+deriva+valida."
  [repo-paineis]
  (fn [req]
    (http/json-resposta 200 (adapters-out-sli-sessao/sli-sessoes->wire
                             (controllers/sli-sessoes repo-paineis (:ator req))))))

(def ^:private card-compliance-indisponivel
  "Sentinela do card de compliance quando a leitura cross-modulo FALHA (review architect MAJOR: degradacao por
  CARD, nunca 500 da pagina inteira). E' um mapa aberto valido p/ o `:compliance-tce :map` do MesaOut; o FE o
  distingue de um PainelOut real pela chave `:indisponivel` (que o PainelOut nunca tem) e mostra 'painel do
  TCE indisponivel' NAQUELE card, preservando os 3 rollups saudaveis que o paineis possui inteiramente."
  {:indisponivel true})

(defn- mesa-handler
  "GET /paineis/mesa (F7 dashboard da Mesa, §16.11 item 11.4). COMPOE, nao reprojeta: le' os rollups do proprio
  paineis (controller -> repo) e o card de compliance via `painel-compliance` — a fn injetada pelo host que
  fecha sobre o repo de compliance e devolve o PainelOut ja' projetado (inversao de dependencia; paineis nunca
  importa compliance, §22.10). adapters/out embute o card OPACO + valida o MesaOut.

  DEGRADACAO POR CARD (review architect MAJOR): uma FALHA de LEITURA de compliance (hiccup de infra, bug de
  projecao daquele modulo) NAO derruba a tela — vira o sentinel `card-compliance-indisponivel` naquele card,
  e os 3 rollups que o paineis possui inteiramente (tramitacao/pendencias/sessoes) seguem carregando. Espelha
  a disciplina de tolerancia do proprio modulo (repositorio/projetar-evento! catch Throwable) e desacopla a
  disponibilidade do dashboard do comprador da leitura cross-modulo mais fragil. Distincao-chave: o card com
  DADOS de atraso (vencidas>0) e' problema real de compliance e continua VISIVEL nos valores; so' a FALHA de
  leitura degrada. `catch Throwable` (nao Exception): um `:pre`/AssertionError em compliance e' Error, nao
  Exception (mesmo racional do consumer)."
  [repo-paineis painel-compliance]
  (fn [req]
    (let [rollups (controllers/dashboard-mesa repo-paineis (:ator req))
          compliance-card (try
                            (painel-compliance (:ente-id (:ator req)))
                            (catch Throwable e
                              (log/warn e "paineis: leitura de compliance falhou no dashboard da Mesa — card degradado, rollups preservados"
                                        {:ente-id (:ente-id (:ator req))})
                              card-compliance-indisponivel))]
      (http/json-resposta 200 (adapters-out-mesa/mesa->wire rollups compliance-card)))))

(defn rotas
  "Fragmento de rotas do modulo paineis (table syntax Pedestal). Recebe o interceptor `auth` (compartilhado)
  + o `repo-paineis` (Repo-Component) + `painel-compliance` (fn injetada pelo host: ente-id -> PainelOut de
  compliance, p/ o dashboard da Mesa compor sem cruzar modulo) e devolve as rotas-dado. `oplenario.rotas`
  funde este fragmento ao conjunto. Authz GROSSA (papel 'secretario' — mesmo papel interno de compliance/
  sessoes/legislativo/participacao) — os paineis sao tenant-wide read-models, sem recurso unico p/ camada fina."
  [{:keys [auth repo-paineis painel-compliance]}]
  (let [papel (it/exige-papel "secretario")]
    #{["/paineis/pendencias" :get [auth papel (pendencias-handler repo-paineis)]
       :route-name :paineis/pendencias]
      ["/paineis/tramitacao" :get [auth papel (tramitacao-handler repo-paineis)]
       :route-name :paineis/tramitacao]
      ["/paineis/sli/sessoes" :get [auth papel (sli-sessoes-handler repo-paineis)]
       :route-name :paineis/sli-sessoes]
      ["/paineis/mesa" :get [auth papel (mesa-handler repo-paineis painel-compliance)]
       :route-name :paineis/mesa]}))
