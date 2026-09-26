(ns oplenario.legislativo.tramitacao-http-in-test
  "Fatia 2 da BORDA DE TRAMITACAO — `POST /legislativo/proposicoes/:id/tramitacao`. DB-free (Repo FAKE,
  mesmo racional de meu-parecer-http-in-test/proposicao-escrita-http-in-test): a ENGINE do eixo C ja' tem
  cobertura de integracao real em tramitacao-db-test/tramitacao-repo-test. O foco AQUI e' o que so' a
  BORDA decide, e que a engine nao tem como decidir sozinha:

    (a) o corpo aceita GATILHO e NUNCA estado-destino (a classe de defeito do T3-A: o chamador escolhendo
        a regra em vez do template);
    (b) os TRES resultados da engine sao DISTINGUIDOS — transicionou / guard bloqueou (dominio normal) /
        excecao (guard que lanca, CAS) — e nenhum deles vira 500 opaco;
    (c) materia SEM rito e' recusada ANTES de a engine rodar, dizendo POR QUE;
    (d) o `ator-id`/`template-id` saem do token e da LINHA, nunca do corpo."
  (:require [clojure.test :refer [deftest is testing]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.rotas :as rotas])
  (:import (java.time Instant)))

(def ^:private ocorrido (Instant/parse "2026-09-10T14:30:00Z"))

(defn- linha-proposicao
  "A LINHA de `legislativo.proposicoes` como `db/proposicao/buscar` a devolve (kebab). `template-id` e'
  o elo da fatia 1 — `nil` = a Casa nao declarou rito para esta materia."
  [id template-id estado]
  {:id id :tipo "projeto_lei" :ano 2026 :sequencial 1 :urn-lex "urn:lex:x" :ementa "X"
   :estado estado :template-id template-id :lock-version 0 :atualizado-em ocorrido})

(defn- fake-repo-legislativo [{:keys [buscar transicionar]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (buscar-proposicao [_ _ente-id id] (buscar id))
    (transicionar! [_ _ente-id registro args] (transicionar registro args))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn [papeis repo-l]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-legislativo repo-l
                                   :registro-fatos :registro-fake
                                   :relogio (constantly ocorrido)})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-bearer [tok] {"authorization" (str "Bearer " tok) "Content-Type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- post-tramitacao
  "Dispara a rota. `repo-l` FAKE; `corpo` e' o mapa cru do corpo JSON."
  ([repo-l pid corpo] (post-tramitacao repo-l pid corpo #{"secretario"} (random-uuid) (random-uuid)))
  ([repo-l pid corpo papeis ente identidade]
   (pt/response-for (service-fn papeis repo-l)
                    :post (str "/legislativo/proposicoes/" pid "/tramitacao")
                    :headers (com-bearer (token ente identidade))
                    :body (json/write-value-as-string corpo))))

(defn- explode [& _] (throw (AssertionError. "a engine NAO devia ter sido chamada")))

;; ============================ (1) o caminho feliz: o recibo ============================

(deftest tramitou-200-com-recibo-de-de-para-gatilho
  (let [pid (random-uuid) tid (random-uuid)
        repo (fake-repo-legislativo
               {:buscar (fn [id] (linha-proposicao id tid "protocolada"))
                :transicionar (fn [_registro _args]
                                {:transicionou? true :de "protocolada" :para "em_comissoes"
                                 :transicao-id (random-uuid) :ocorrido-em ocorrido})})
        r (post-tramitacao repo pid {:gatilho "despachar"})
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= (str pid) (:proposicao-id body)))
    (is (= "protocolada" (:de body)))
    (is (= "em_comissoes" (:para body)))
    (is (= "despachar" (:gatilho body)))
    (is (= (str ocorrido) (:ocorrido-em body))
        "o recibo carrega o instante REAL da transicao (RETURNING do historico), nao o de projecao")))

;; ============ (2) o gatilho e' o UNICO verbo: estado-destino nunca entra (T3-A) ============

(deftest corpo-com-estado-destino-e-recusado-400
  (testing "`para`/`estado`/`para-estado` no corpo = o chamador escolhendo a regra (T3-A). O wire e'
           :closed — campo extra e' 400, e a engine nunca roda."
    (doseq [campo [:para :estado :para-estado :template-id]]
      (let [pid (random-uuid)
            repo (fake-repo-legislativo {:buscar explode :transicionar explode})
            r (post-tramitacao repo pid {:gatilho "despachar" campo "aprovada"})]
        (is (= 400 (:status r)) (str "campo proibido no corpo: " campo))))))

(deftest corpo-sem-gatilho-400
  (let [pid (random-uuid)
        repo (fake-repo-legislativo {:buscar explode :transicionar explode})]
    (is (= 400 (:status (post-tramitacao repo pid {}))))
    (is (= 400 (:status (post-tramitacao repo pid {:gatilho "   "})))
        "gatilho em branco e' 400 (o `:min 1` do Malli so' barra a string vazia)")))

;; ================= (3) guard bloqueou TODAS as candidatas = dominio normal =================

(defn- recusa
  "Dispara a rota contra uma engine FAKE que devolve `{:transicionou? false :motivo ...}` — o contrato de
  `db/tramitacao/transicionar!`. Devolve o corpo JSON ja' lido + o status."
  [motivo]
  (let [pid (random-uuid) tid (random-uuid)
        repo (fake-repo-legislativo
               {:buscar (fn [id] (linha-proposicao id tid "protocolada"))
                :transicionar (fn [_r _a] (cond-> {:transicionou? false :de "protocolada" :gatilho "promulgar"}
                                            (some? motivo) (assoc :motivo motivo)))})
        r (post-tramitacao repo pid {:gatilho "promulgar"})]
    (assoc (ler-json r) :status (:status r))))

(deftest guard-bloqueia-todas-409-com-estado-e-gatilho
  (testing "nao e' erro: a Casa nao permite este ato AGORA. 409 (e nao 400) porque o corpo era valido — o
           MESMO corpo funcionaria noutro estado; quem recusa e' o recurso, nao o pedido."
    (let [body (recusa :guarda-recusou)]
      (is (= 409 (:status body)))
      (is (= "protocolada" (:estado-atual body)) "o operador ve' de ONDE a materia nao saiu")
      (is (= "promulgar" (:gatilho body)) "e QUAL ato o rito recusou")
      (is (not (re-find #"(?i)erro interno" (str (:erro body)))) "recusa de dominio nunca e' erro opaco"))))

(deftest as-quatro-recusas-de-tramitacao-nao-se-confundem-na-borda
  ;; Fatia 2: a engine deixou de achatar os desfechos de `{:transicionou? false}` num `nil` so', e a borda
  ;; deixou de responder a MESMA frase para situacoes que pedem acoes OPOSTAS do operador. `motivo` e'
  ;; campo PROPRIO no corpo justamente para que a interface nao precise casar substring de prosa.
  (testing "fim de rito: a Casa ENCERROU o processo — nao e' 'agora nao', e' 'nunca mais'"
    (let [b (recusa :estado-terminal)]
      (is (= 409 (:status b)))
      (is (= "estado-terminal" (:motivo b)))
      (is (re-find #"(?i)fim de processo" (str (:erro b))))
      (is (not (re-find #"(?i)nao permite o ato" (str (:erro b))))
          "nao cai na frase generica — se caisse, o `motivo` estaria mentindo sobre a prosa ao lado")))
  (testing "guard: o ato existe, a condicao dele nao esta' cumprida — e' o unico que o tempo muda"
    (let [b (recusa :guarda-recusou)]
      (is (= "guarda-recusou" (:motivo b)))
      (is (re-find #"(?i)condicao" (str (:erro b))))))
  (testing "ato nao declarado a partir deste estado: repetir nunca funciona"
    (let [b (recusa :gatilho-nao-declarado)]
      (is (= "gatilho-nao-declarado" (:motivo b)))
      (is (re-find #"(?i)nao declara o ato" (str (:erro b))))))
  (testing "o rito nem conhece o estado atual: conserto e' de CONFIG, nao de tentativa"
    (let [b (recusa :estado-fora-do-rito)]
      (is (= "estado-fora-do-rito" (:motivo b)))
      (is (re-find #"(?i)reconciliar" (str (:erro b))))))
  (testing "motivo AUSENTE (contrato antigo) degrada p/ a frase generica, nunca inventa um motivo"
    (let [b (recusa nil)]
      (is (= 409 (:status b)))
      (is (nil? (:motivo b)) "a borda nao adivinha")
      (is (re-find #"(?i)nao permite o ato" (str (:erro b))))))
  (testing "toda recusa, seja qual for o motivo, carrega de-onde e qual-ato"
    (doseq [m [:estado-terminal :guarda-recusou :gatilho-nao-declarado :estado-fora-do-rito nil]]
      (let [b (recusa m)]
        (is (= "protocolada" (:estado-atual b)) (str "estado-atual ausente no motivo " m))
        (is (= "promulgar" (:gatilho b)) (str "gatilho ausente no motivo " m))))))

;; ===================== (4) materia sem rito: recusa que diz POR QUE =====================

(deftest materia-sem-template-409-antes-da-engine
  (let [pid (random-uuid)
        repo (fake-repo-legislativo
               {:buscar (fn [id] (linha-proposicao id nil "protocolada"))
                :transicionar explode})
        r (post-tramitacao repo pid {:gatilho "despachar"})
        body (ler-json r)]
    (is (= 409 (:status r)) "nao e' 400: o corpo estava certo; a Casa e' que nao declarou rito")
    (is (re-find #"(?i)rito" (str (:erro body))) "a mensagem nomeia o que falta")))

(deftest proposicao-inexistente-404
  (let [pid (random-uuid)
        repo (fake-repo-legislativo {:buscar (constantly nil) :transicionar explode})]
    (is (= 404 (:status (post-tramitacao repo pid {:gatilho "despachar"}))))))

;; ============ (5) excecao: guard que LANCA e CAS sao distintos entre si e do resto ============

(deftest guard-que-lanca-nao-vira-500-opaco
  (testing "fato ausente no registry / tipo nao-booleano: o rito da Casa nao PODE ser avaliado. E' 5xx
           (nenhuma acao do cliente conserta, e e' incidente — §5 do CLAUDE.md), mas com corpo NOMEADO."
    (doseq [ex-data-guard [{:erro :runtime :nome "materia_tem_parecer"} {:erro :sintaxe}]]
      (let [pid (random-uuid) tid (random-uuid)
            repo (fake-repo-legislativo
                   {:buscar (fn [id] (linha-proposicao id tid "protocolada"))
                    :transicionar (fn [_r _a] (throw (ex-info "fato sem fn registrada" ex-data-guard)))})
            r (post-tramitacao repo pid {:gatilho "despachar"})
            body (ler-json r)]
        (is (= 500 (:status r)))
        (is (re-find #"(?i)rito" (str (:erro body)))
            (str "o corpo nomeia a causa (rito inavaliavel), nao 'erro interno' — " ex-data-guard))
        (is (= "despachar" (:gatilho body)) "e diz qual ato ficou preso")))))

(deftest conflito-de-cas-409-distinto-do-guard-bloqueado
  (let [pid (random-uuid) tid (random-uuid)
        repo (fake-repo-legislativo
               {:buscar (fn [id] (linha-proposicao id tid "protocolada"))
                :transicionar (fn [_r _a]
                                (throw (ex-info "conflito de escrita (lock_version desatualizado)"
                                                {:tipo :conflito/transicao :id pid})))})
        r (post-tramitacao repo pid {:gatilho "despachar"})
        body (ler-json r)]
    (is (= 409 (:status r)))
    (is (nil? (:estado-atual body))
        "o corpo do CAS NAO e' o do guard-bloqueado: ninguem sabe o estado, a escrita e' que colidiu")))

;; ==================== (6) ator e template saem do servidor, nao do corpo ====================

(deftest ator-id-vem-do-token-e-template-id-vem-da-linha
  (let [pid (random-uuid) tid (random-uuid) ente (random-uuid) identidade (random-uuid)
        visto (atom nil)
        repo (fake-repo-legislativo
               {:buscar (fn [id] (linha-proposicao id tid "protocolada"))
                :transicionar (fn [registro args]
                                (reset! visto (assoc args ::registro registro))
                                {:transicionou? true :de "protocolada" :para "em_comissoes"
                                 :transicao-id (random-uuid) :ocorrido-em ocorrido})})
        r (post-tramitacao repo pid {:gatilho "despachar"} #{"secretario"} ente identidade)]
    (is (= 200 (:status r)))
    (is (= identidade (:ator-id @visto)) "o ator sai do token")
    (is (= identidade (:updated-by @visto)))
    (is (= tid (:template-id @visto)) "o rito sai da LINHA, nunca do cliente")
    (is (= pid (:proposicao-id @visto)))
    (is (= :registro-fake (::registro @visto)) "o RegistroFatos do motor chega pela fiacao do host")))

(deftest contexto-do-corpo-chega-ao-dominio-como-ALEGADO-keywordizado-e-limitado
  (let [pid (random-uuid) tid (random-uuid) visto (atom nil)
        repo (fake-repo-legislativo
               {:buscar (fn [id] (linha-proposicao id tid "protocolada"))
                :transicionar (fn [_r args] (reset! visto args)
                                {:transicionou? true :de "a" :para "b"
                                 :transicao-id (random-uuid) :ocorrido-em ocorrido})})]
    (testing "contexto ausente vira {} — a engine nunca recebe nil"
      (post-tramitacao repo pid {:gatilho "despachar"})
      (is (= {} (:alegado @visto))))
    (testing "contexto presente chega com chaves KEYWORD (o avaliador le' `alegado.x` por keyword)"
      (post-tramitacao repo pid {:gatilho "despachar" :contexto {:comissao "ccj" :urgente true}})
      (is (= {:comissao "ccj" :urgente true} (:alegado @visto))))
    (testing "o campo do corpo chama-se `contexto`; o que chega ao dominio chama-se `:alegado` (fatia 4).
             A troca e' a marca de PROCEDENCIA: sob este nome o guard do rito le' o que o CLIENTE AFIRMA,
             distinto de `proposicao` (a linha) e dos fatos por nome (apurados pelo servidor). A chave
             `:contexto` NAO pode sobreviver no mapa de dominio — se sobrevivesse, a engine teria duas
             portas para a mesma carga e a renomeacao seria decorativa."
      (post-tramitacao repo pid {:gatilho "despachar" :contexto {:comissao "ccj"}})
      (is (nil? (:contexto @visto)) "nada de dominio continua chamando-se :contexto"))
    (testing "contexto aninhado/gigante e' 400 — e' dado de guard, nao um saco de blobs no historico"
      (is (= 400 (:status (post-tramitacao repo pid {:gatilho "d" :contexto {:x {:y 1}}}))))
      (is (= 400 (:status (post-tramitacao repo pid
                            {:gatilho "d" :contexto (into {} (map #(vector (str "k" %) %)) (range 40))})))))))

;; ==================================== (7) o gate de papel ====================================

(deftest sem-papel-secretario-403
  (let [pid (random-uuid)
        repo (fake-repo-legislativo {:buscar explode :transicionar explode})]
    (is (= 403 (:status (post-tramitacao repo pid {:gatilho "despachar"} #{"vereador"}
                                         (random-uuid) (random-uuid)))))))

;; ==============================================================================================
;; Fatia 3 — a LEITURA: `GET /legislativo/proposicoes/:id/tramitacao`
;;
;; Devolve (1) o HISTORICO de transicoes e (2) os GATILHOS que a Casa declara a partir do estado ATUAL.
;;
;; A DECISAO DESTA FATIA, e ela e' o eixo dos testes abaixo: a leitura lista os gatilhos DECLARADOS
;; (opcao (a)), e NAO avalia os guards p/ dizer quais passariam (opcao (b)). [REVERTIDO por ADR-0004] Ate'
;; 11/09/2026 o motivo de MAIOR peso era que (b) nao tinha como estar certa: o guard lia `contexto`/
;; `alegado`, argumento do POST que nao existe na hora do GET — avaliar contra `{}` responderia "nao
;; passa" sobre um ato que passaria com o corpo certo, resposta precisa e falsa. Esse motivo especifico
;; SUMIU: hoje o guard nao le' o corpo sob nome nenhum (nem `contexto`, nem `alegado`), so' verdade
;; APURADA (`proposicao`/`parecer`, a linha, e fatos por nome). Os outros dois motivos, que ja' vinham
;; junto, seguem de pe' sozinhos e bastam para a mesma decisao:
;;
;;   1. guard LANCA. Um rito inavaliavel derrubaria a LEITURA — o operador perderia tambem o historico,
;;      exatamente no momento em que mais precisa dele p/ entender o que houve.
;;   2. guard consulta FATO: avaliar N guards por GET poe o resolvedor de fatos no caminho de uma tela.
;;
;; O PRECO de (a) e' o botao que o guard vai recusar — a familia de "dado falso" que o briefing proibe.
;; Pago em DUAS moedas, ambas testadas aqui: o campo chama-se `gatilhos-possiveis` (nunca "disponiveis"),
;; e cada item carrega `pode-ser-recusado` — que e' MAIS informacao que um aviso generico, porque separa
;; mecanicamente o ato que o rito declara incondicional do ato que tem condicao a verificar.
;; ==============================================================================================

(defn- fake-repo-leitura
  "Fake SEPARADO do de escrita (o de cima) de proposito: nenhum teste de leitura pode passar a reboque de
  uma fiacao de escrita, e `transicionar!` fica FORA deste reify — chamar a engine numa leitura e' AssertionError."
  [tramitacao]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (tramitacao-da-proposicao [_ _ente-id id limite] (tramitacao id limite))))

(defn- get-tramitacao
  ([repo-l pid] (get-tramitacao repo-l pid "" #{"secretario"}))
  ([repo-l pid query papeis]
   (pt/response-for (service-fn papeis repo-l)
                    :get (str "/legislativo/proposicoes/" pid "/tramitacao" query)
                    :headers (com-bearer (token (random-uuid) (random-uuid))))))

(defn- hist
  "Uma linha do historico como `db/tramitacao/historico-da-proposicao` a devolve (ASC, cronologica)."
  [de para gatilho n]
  {:id (random-uuid) :de-estado de :para-estado para :gatilho gatilho
   :ocorrido-em (.plusSeconds ocorrido n) :contexto {} :ator-id (random-uuid) :template-id (random-uuid)})

(defn- cand [gatilho para guarda ordem]
  {:gatilho gatilho :para-estado para :guarda guarda :ordem ordem})

(defn- leitura
  "Monta o retorno do Repo (uma tx so': a linha, o historico, as candidatas do estado atual, o estado no rito)."
  [& {:keys [proposicao historico candidatas estado-no-template]}]
  (fn [_id _limite]
    {:proposicao proposicao :historico (or historico []) :candidatas (or candidatas [])
     :estado-no-template estado-no-template}))

;; ------------------------ (8) o caminho feliz: historico + gatilhos ------------------------

(deftest leitura-200-com-historico-cronologico-e-gatilhos-do-estado-atual
  (let [pid (random-uuid) tid (random-uuid)
        repo (fake-repo-leitura
               (leitura :proposicao (linha-proposicao pid tid "em_comissoes")
                        :historico [(hist "protocolada" "em_comissoes" "despachar" 0)]
                        :candidatas [(cand "concluir" "em_pauta" "materia_tem_parecer" 1)
                                     (cand "arquivar" "arquivada" nil 2)]
                        :estado-no-template {:terminal false}))
        r (get-tramitacao repo pid)
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= (str pid) (:proposicao-id body)))
    (is (= "em_comissoes" (:estado-atual body)) "o rotulo atual")
    (is (= (str tid) (:template-id body)) "o rito sob o qual a materia corre")
    (is (= [{:de-estado "protocolada" :para-estado "em_comissoes" :gatilho "despachar"
             :ocorrido-em (str ocorrido) :recebimento nil}]
           (:historico body))
        "historico em ordem CRONOLOGICA, com o instante REAL da transicao (fatia 2b: + o recibo de carga, nil aqui)")
    (is (false? (:historico-truncado body)))
    (is (= ["concluir" "arquivar"] (mapv :gatilho (:gatilhos-possiveis body)))
        "os gatilhos vem na ORDEM do rito (`ordem`), nao em alfabetica — e' a ordem ritual que o operador le'")
    (is (nil? (:nota body)) "havendo ato a praticar, nao ha' o que explicar")))

(deftest pode-ser-recusado-separa-ato-incondicional-de-ato-com-guard
  (testing "e' ISTO que paga a opcao (a): o campo nao promete que o ato passa, promete saber se ha' CONDICAO.
           Guard nil = o rito declara o ato incondicional. Guard presente = ha' condicao, avaliada so' no disparo."
    (let [pid (random-uuid) tid (random-uuid)
          repo (fake-repo-leitura
                 (leitura :proposicao (linha-proposicao pid tid "protocolada")
                          :candidatas [(cand "despachar" "em_comissoes" nil 1)
                                       (cand "arquivar" "arquivada" "falso" 2)]
                          :estado-no-template {:terminal false}))
          por-gatilho (into {} (map (juxt :gatilho identity)) (:gatilhos-possiveis (ler-json (get-tramitacao repo pid))))]
      (is (false? (:pode-ser-recusado (get por-gatilho "despachar"))))
      (is (true? (:pode-ser-recusado (get por-gatilho "arquivar")))))))

(deftest gatilho-com-DUAS-candidatas-vira-UM-item-com-os-dois-destinos
  (testing "o mesmo ato pode levar a destinos diferentes conforme o guard — o rito escolhe a 1a que passa.
           Listar duas linhas 'concluir' faria a interface desenhar dois botoes iguais. E `pode-ser-recusado`
           e' FALSO aqui: havendo UMA candidata sem guard, o ato nao pode ser recusado por guard nenhum —
           so' o DESTINO e' que varia. (`nil?` e' o mesmo predicado que a engine usa em `passa?`; usar outro
           aqui seria a leitura mentindo sobre a escrita por divergencia de definicao.)"
    (let [pid (random-uuid) tid (random-uuid)
          repo (fake-repo-leitura
                 (leitura :proposicao (linha-proposicao pid tid "em_comissoes")
                          :candidatas [(cand "concluir" "em_pauta" "materia_tem_parecer" 1)
                                       (cand "concluir" "arquivada" nil 2)]
                          :estado-no-template {:terminal false}))
          gs (:gatilhos-possiveis (ler-json (get-tramitacao repo pid)))]
      (is (= 1 (count gs)) "UM item por gatilho")
      (is (= ["em_pauta" "arquivada"] (:destinos-possiveis (first gs))) "os dois destinos, na ordem do rito")
      (is (false? (:pode-ser-recusado (first gs)))))))

;; --------------- (9) lista vazia: QUATRO causas distintas, quatro diagnosticos ---------------

(deftest materia-sem-rito-devolve-historico-vazio-nenhum-gatilho-e-DIZ-por-que
  (let [pid (random-uuid)
        repo (fake-repo-leitura (leitura :proposicao (linha-proposicao pid nil "protocolada")))
        body (ler-json (get-tramitacao repo pid))]
    (is (= [] (:historico body)))
    (is (= [] (:gatilhos-possiveis body)))
    (is (nil? (:template-id body)))
    (is (= "protocolada" (:estado-atual body))
        "o rotulo vem da LINHA: aqui o historico e' VAZIO, entao derivar dele daria nil (fatia 4 — a
         assercao equivalente no caminho feliz nao podia reprovar, porque la' a fixture poe o mesmo valor
         nos dois lugares)")
    (is (re-find #"(?i)rito" (str (:nota body))) "a nota nomeia o que falta: a Casa nao vinculou rito")))

(deftest o-rotulo-ATUAL-vem-da-LINHA-e-nao-do-ultimo-para-estado-do-historico
  ;; A assercao original desta propriedade ("o rotulo ATUAL, lido da linha, nao derivado do historico")
  ;; NAO PODIA REPROVAR: a fixture do caminho feliz punha `em_comissoes` na linha E como `para-estado` da
  ;; ultima transicao, entao ler da linha e derivar do historico davam a MESMA resposta. Uma assercao que
  ;; nao consegue distinguir as duas implementacoes nao esta' cobrindo nenhuma das duas.
  ;;
  ;; Aqui elas DIVERGEM, e a divergencia e' um estado real do sistema, nao um formato inventado: o
  ;; versionamento de template e' por COPIA INTEGRAL (mig 0016), entao uma materia pode estar num rotulo
  ;; que o rito ATUAL nem declara — e' o caso que a propria engine diagnostica como `:estado-fora-do-rito`,
  ;; e o historico que sobrou e' o do rito ANTIGO, terminando noutro estado. Derivar o rotulo do historico
  ;; ali responderia `em_comissoes` sobre uma materia que esta' em `estado_legado`, apagando exatamente o
  ;; caso que pede intervencao humana.
  (let [pid (random-uuid) tid (random-uuid)
        repo (fake-repo-leitura
               (leitura :proposicao (linha-proposicao pid tid "estado_legado")
                        :historico [(hist "protocolada" "em_comissoes" "despachar" 0)]
                        :estado-no-template nil))
        body (ler-json (get-tramitacao repo pid))]
    (is (= "estado_legado" (:estado-atual body))
        "a LINHA manda; derivar do historico devolveria 'em_comissoes' e esta assercao reprovaria")
    (is (= "em_comissoes" (:para-estado (last (:historico body))))
        "e o historico continua sendo devolvido como esta' — as duas coisas convivem, so' nao se confundem")
    (is (nil? (:estado-terminal body)) "tri-valorado: o rito nem declara este estado")))

(deftest estado-terminal-e-beco-sem-saida-tem-notas-DIFERENTES
  (testing "'o rito acabou' e 'o rito nao declara saida daqui' pedem acoes opostas do operador — nada vs.
           mexer na config. Uma nota so' p/ os dois casos seria verdadeira e inutil."
    (let [pid (random-uuid) tid (random-uuid)
          terminal (ler-json (get-tramitacao (fake-repo-leitura
                                               (leitura :proposicao (linha-proposicao pid tid "arquivada")
                                                        :estado-no-template {:terminal true})) pid))
          beco (ler-json (get-tramitacao (fake-repo-leitura
                                           (leitura :proposicao (linha-proposicao pid tid "em_pauta")
                                                    :estado-no-template {:terminal false})) pid))
          fora (ler-json (get-tramitacao (fake-repo-leitura
                                           (leitura :proposicao (linha-proposicao pid tid "estado_legado")
                                                    :estado-no-template nil)) pid))]
      (is (true? (:estado-terminal terminal)))
      (is (re-find #"(?i)terminal" (str (:nota terminal))))
      (is (false? (:estado-terminal beco)))
      (is (not (re-find #"(?i)terminal" (str (:nota beco)))) "beco NAO e' terminal")
      (is (nil? (:estado-terminal fora)))
      (is (not= (:nota terminal) (:nota beco)))
      (is (not= (:nota beco) (:nota fora)))
      (is (re-find #"(?i)estado_legado" (str (:nota fora))) "a nota nomeia o estado que o rito desconhece"))))

;; ----------------- (10) o teto: truncar em silencio seria mentir sobre o inicio -----------------

(deftest historico-truncado-e-DECLARADO-e-preserva-os-MAIS-RECENTES
  (testing "com teto, `n` itens devolvidos sao indistinguiveis de 'a materia so' teve n atos' — e o
           operador leria a linha mais antiga MOSTRADA como o comeco do processo. O Repo e' consultado com
           teto+1 justamente p/ a borda SABER que sobrou."
    (let [pid (random-uuid) tid (random-uuid) visto (atom nil)
          todos [(hist "a" "b" "g1" 0) (hist "b" "c" "g2" 10) (hist "c" "d" "g3" 20)]
          repo (fake-repo-leitura
                 (fn [_id limite]
                   (reset! visto limite)
                   {:proposicao (linha-proposicao pid tid "d")
                    ;; o db devolve os `limite` MAIS RECENTES, revertidos a ASC
                    :historico (vec (take-last limite todos))
                    :candidatas [] :estado-no-template {:terminal false}}))
          body (ler-json (get-tramitacao repo pid "?limite=2" #{"secretario"}))]
      (is (= 3 @visto) "o Repo e' consultado com teto+1 — a sonda que revela que ha' mais")
      (is (true? (:historico-truncado body)))
      (is (= ["g2" "g3"] (mapv :gatilho (:historico body))) "ficam os MAIS RECENTES, nao os mais antigos")
      (is (= 2 (count (:historico body))) "e sao exatamente `limite`, nunca o teto+1 da sonda"))))

(deftest limite-ausente-tem-default-e-limite-invalido-e-400
  (testing "mesma disciplina de pagina/tamanho do modulo: default quando AUSENTE, 400 quando PRESENTE e
           invalido — nunca absorvido em silencio, porque muda o contrato que o FE le'."
    (let [pid (random-uuid) visto (atom nil)
          repo (fake-repo-leitura
                 (fn [_id limite] (reset! visto limite)
                   {:proposicao (linha-proposicao pid nil "protocolada") :historico [] :candidatas []}))]
      (is (= 200 (:status (get-tramitacao repo pid))))
      (is (= 101 @visto) "default 100, +1 da sonda de truncamento")
      (doseq [q ["?limite=0" "?limite=-3" "?limite=501" "?limite=abc" "?limite=1&limite=2"]]
        (is (= 400 (:status (get-tramitacao repo pid q #{"secretario"}))) q)))))

;; ------------------------------- (11) as bordas de sempre -------------------------------

(deftest leitura-de-proposicao-inexistente-404
  (let [pid (random-uuid)
        repo (fake-repo-leitura (leitura :proposicao nil))]
    (is (= 404 (:status (get-tramitacao repo pid))))))

(deftest leitura-papel-sem-leitura-403
  ;; docs/20: leitura de tramitacao aberta a secretario OU vereador; um papel sem nenhum dos dois
  ;; (cidadao) segue NEGADO ANTES de tocar o Repo.
  (let [pid (random-uuid)
        repo (fake-repo-leitura (fn [& _] (throw (AssertionError. "o Repo NAO devia ter sido tocado"))))]
    (is (= 403 (:status (get-tramitacao repo pid "" #{"cidadao"}))))))

(deftest leitura-vereador-le-200
  ;; o vereador legisla sobre a materia -> LE a tramitacao (era 403 pelo gate grosso so'-secretario).
  (let [pid (random-uuid) tid (random-uuid)
        repo (fake-repo-leitura (leitura :proposicao (linha-proposicao pid tid "em_comissoes")))]
    (is (= 200 (:status (get-tramitacao repo pid "" #{"vereador"}))))))

(deftest a-leitura-NUNCA-avalia-guard
  (testing "o `reify` de `fake-repo-leitura` nao implementa `transicionar!`: se a borda de leitura tentasse
           avaliar os guards pela engine, isto seria AbstractMethodError, nao um teste verde. E um guard que
           so' o motor saberia avaliar ('materia_tem_parecer') atravessa a leitura como TEXTO, sem 500."
    (let [pid (random-uuid) tid (random-uuid)
          repo (fake-repo-leitura
                 (leitura :proposicao (linha-proposicao pid tid "em_comissoes")
                          :candidatas [(cand "concluir" "em_pauta" "materia_tem_parecer e nao materia_arquivada" 1)]
                          :estado-no-template {:terminal false}))
          r (get-tramitacao repo pid)]
      (is (= 200 (:status r)) "rito com guard rico nao derruba a LEITURA")
      (is (= ["concluir"] (mapv :gatilho (:gatilhos-possiveis (ler-json r))))))))
