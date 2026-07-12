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
            [oplenario.paineis.adapters.out.minha-sessao-atual :as adapters-out-minha-sessao-atual]
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

(defn- minha-sessao-atual-handler
  "GET /meu/sessao-atual (Onda C3, papel 'vereador'). Reusa a MESMA leitura tenant-wide de `sli-sessoes`
  (sem recurso unico p/ camada fina — mesmo escopo authz das demais rotas deste modulo) e projeta so' a
  PRIMEIRA entrada (ja' ordenada 'em curso primeiro'). Sem sessao viva -> 200 {:sessao-id nil :situacao nil}
  (nunca 404 — ausencia de sessao e' um ESTADO do cockpit do celular, nao um erro)."
  [repo-paineis]
  (fn [req]
    (http/json-resposta 200 (adapters-out-minha-sessao-atual/minha-sessao-atual->wire
                             (controllers/sli-sessoes repo-paineis (:ator req))))))

(def ^:private card-generico-indisponivel
  "Sentinela GENERICO de degradacao por card (review architect MAJOR: degradacao por CARD, nunca 500 da
  pagina inteira) — reusado pelos 4 cards cross-modulo do dashboard (compliance/presenca/esic/relatores),
  todos seguindo a MESMA disciplina de tolerancia. `:compliance-tce` tolera QUALQUER mapa (tipado `:map`
  aberto no wire/out); os outros 3 cards sao tipados fechados no wire/out, mas cada um e' uma UNIAO
  `[:or <forma-fechada> CardIndisponivelOut]` (wire/out/mesa.clj) — o sentinel valida contra o segundo ramo
  da uniao, entao a validacao do MesaOut passa tanto com o card real quanto com o sentinel, nos 4 cards. O
  FE distingue um card real do sentinel pela chave `:indisponivel` (que nenhum card real tem)."
  {:indisponivel true})

(defn- card-seguro
  "Chama `f` (a fn cross-modulo injetada pelo host: ente-id -> card ja' projetado) e devolve o resultado; em
  FALHA de leitura (hiccup de infra, bug de projecao daquele modulo), loga e degrada p/ o sentinel generico —
  NUNCA derruba a pagina inteira por causa de UM card cross-modulo fragil. Generaliza o try/catch que antes
  vivia inline so' p/ compliance (review architect MAJOR) — mesma disciplina p/ os 4 cards do dashboard.
  Distincao-chave: o card com DADOS de atraso/pendencia e' problema real do dominio e continua VISIVEL nos
  valores; so' a FALHA de leitura degrada. `catch Throwable` (nao Exception): um `:pre`/AssertionError na fonte
  e' Error, nao Exception (mesmo racional do consumer)."
  [rotulo f ente-id]
  (try
    (f ente-id)
    (catch Throwable e
      (log/warn e (str "paineis: leitura de " rotulo " falhou no dashboard da Mesa — card degradado, demais cards preservados")
                {:ente-id ente-id})
      card-generico-indisponivel)))

(defn- mesa-handler
  "GET /paineis/mesa (F7 dashboard da Mesa, §16.11 item 11.4). COMPOE, nao reprojeta: le' os rollups do proprio
  paineis (controller -> repo) e os 4 cards cross-modulo (compliance/presenca/esic/relatores) via fns injetadas
  pelo host que fecham sobre o repo de cada modulo e devolvem o card ja' projetado (inversao de dependencia;
  paineis nunca importa compliance/sessoes/participacao/legislativo, §22.10). adapters/out embute os 4 cards
  OPACOS + valida o MesaOut.

  DEGRADACAO POR CARD (review architect MAJOR, generalizada aos 4 cards nesta task): uma FALHA de LEITURA de
  qualquer card cross-modulo NAO derruba a tela — vira o sentinel `card-generico-indisponivel` NAQUELE card
  (via `card-seguro`), e os demais (incl. os 3 rollups que o paineis possui inteiramente: tramitacao/
  pendencias/sessoes) seguem carregando. Espelha a disciplina de tolerancia do proprio modulo (repositorio/
  projetar-evento! catch Throwable) e desacopla a disponibilidade do dashboard da leitura cross-modulo mais
  fragil."
  [repo-paineis painel-compliance presenca-resumo esic-cumprimento relatores-pendentes]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          rollups (controllers/dashboard-mesa repo-paineis (:ator req))
          compliance-card (card-seguro "compliance" painel-compliance ente-id)
          presenca-card (card-seguro "presenca-resumo" presenca-resumo ente-id)
          esic-card (card-seguro "esic-cumprimento" esic-cumprimento ente-id)
          relatores-card (card-seguro "relatores-pendentes" relatores-pendentes ente-id)]
      (http/json-resposta 200 (adapters-out-mesa/mesa->wire rollups compliance-card
                                                            presenca-card esic-card relatores-card)))))

(defn rotas
  "Fragmento de rotas do modulo paineis (table syntax Pedestal). Recebe o interceptor `auth` (compartilhado)
  + o `repo-paineis` (Repo-Component) + as 4 fns cross-modulo injetadas pelo host (`painel-compliance`,
  `presenca-resumo`, `esic-cumprimento`, `relatores-pendentes` — cada uma ente-id -> card ja' projetado, p/ o
  dashboard da Mesa compor sem cruzar modulo) e devolve as rotas-dado. `oplenario.rotas` funde este fragmento
  ao conjunto. Authz GROSSA (papel 'secretario' — mesmo papel interno de compliance/sessoes/legislativo/
  participacao) — os paineis sao tenant-wide read-models, sem recurso unico p/ camada fina. `GET
  /meu/sessao-atual` (Onda C3) e' a UNICA excecao — gate 'vereador' (o cockpit do celular descobre a
  sessao viva sem o papel secretario), reusando a MESMA leitura de `sli-sessoes`."
  [{:keys [auth repo-paineis painel-compliance presenca-resumo esic-cumprimento relatores-pendentes]}]
  (let [papel (it/exige-papel "secretario")
        papel-vereador (it/exige-papel "vereador")]
    #{["/paineis/pendencias" :get [auth papel (pendencias-handler repo-paineis)]
       :route-name :paineis/pendencias]
      ["/paineis/tramitacao" :get [auth papel (tramitacao-handler repo-paineis)]
       :route-name :paineis/tramitacao]
      ["/paineis/sli/sessoes" :get [auth papel (sli-sessoes-handler repo-paineis)]
       :route-name :paineis/sli-sessoes]
      ["/paineis/mesa" :get [auth papel (mesa-handler repo-paineis painel-compliance
                                                       presenca-resumo esic-cumprimento relatores-pendentes)]
       :route-name :paineis/mesa]
      ["/meu/sessao-atual" :get [auth papel-vereador (minha-sessao-atual-handler repo-paineis)]
       :route-name :paineis/minha-sessao-atual]}))
