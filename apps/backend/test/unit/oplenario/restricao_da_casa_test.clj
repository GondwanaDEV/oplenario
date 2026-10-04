(ns oplenario.restricao-da-casa-test
  "ADR-0018 (Eixos 2 e 3), puro: leitura passa, escrita fora da allowlist numa Casa suspensa recebe 423 com o motivo
  publico; o cache do seam expira e invalida; a faixa so' mostra o motivo ao interno."
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [oplenario.restricao-da-casa :as r])
  (:import (java.time Instant)))

(def desde (Instant/parse "2026-09-30T12:00:00Z"))
(def suspensa {:estado "suspenso" :motivo "inadimplencia" :desde desde})

(defn- ctx [metodo rota ente] {:request {:request-method metodo :ator {:ente-id ente}} :route {:route-name rota}})

(deftest leitura-e-allowlist
  (is (r/escrita-permitida? :get :legislativo/criar-proposicao) "o metodo de leitura passa sempre")
  (is (r/escrita-permitida? :head :qualquer/rota))
  (is (r/escrita-permitida? :post :participacao/protocolar-esic) "o protocolo do cidadao segue")
  (is (r/escrita-permitida? :post :participacao/responder-pedido) "o servidor responde o e-SIC")
  (is (r/escrita-permitida? :post :participacao/indeferir-pedido) "e indefere, com fundamentacao: negar tambem e' responder")
  (is (r/escrita-permitida? :post :participacao/indeferir-solicitacao) "o encarregado indefere a solicitacao LGPD")
  (is (every? #(r/escrita-permitida? :post %) [:participacao/anexar-esic :participacao/anexar-ouvidoria :participacao/anexar-lgpd])
      "o documento que acompanha a resposta segue: anexar e' parte de responder")
  (is (every? #(r/escrita-permitida? :post %) [:participacao/anexar-meu-esic :participacao/anexar-meu-ouvidoria :participacao/anexar-meu-lgpd])
      "o cidadao anexa ao proprio protocolo: parte de protocolar (como `protocolar-esic`)")
  (is (every? #(r/escrita-permitida? :post %) [:participacao/retirar-anexo-esic :participacao/retirar-anexo-ouvidoria :participacao/retirar-anexo-lgpd])
      "retirar um anexo e' contencao de incidente de conteudo: a Casa suspensa pode")
  (is (r/escrita-permitida? :post :exportacao-da-casa/gerar) "a Casa suspensa exporta (portabilidade, 9.6)")
  (is (r/escrita-permitida? :post :exportacao-da-casa/confirmar-recebimento))
  (is (not (r/escrita-permitida? :post :legislativo/criar-proposicao)) "o legislativo nao opera")
  (is (not (r/escrita-permitida? :post :rota/que-ainda-nao-existe)) "escrita nova nasce bloqueada (fail-closed)"))

(deftest a-restricao-no-interceptor
  (let [estados {:susp suspensa :ativa {:estado "ativo"}}
        guarda (r/restricao (fn [ente] (get estados ente)))]
    (testing "Casa suspensa, escrita fora da allowlist: 423 com o motivo publico"
      (let [res (:response (guarda (ctx :post :legislativo/criar-proposicao :susp)))
            corpo (json/read-value (:body res) json/keyword-keys-object-mapper)]
        (is (= 423 (:status res)))
        (is (= {:erro "acesso restrito" :desde "2026-09-30T12:00:00Z"} corpo))
        (is (not (re-find #"inadimplencia" (:body res))) "o motivo comercial nao vai ao fio")
        (is (= {:rotulo "recusado: Casa com acesso restrito"} (:auditoria res)) "a trilha rotula a recusa")))
    (is (nil? (:response (guarda (ctx :get :legislativo/listar :susp)))) "leitura passa")
    (is (nil? (:response (guarda (ctx :post :participacao/protocolar-manifestacao :susp)))))
    (is (nil? (:response (guarda (ctx :post :legislativo/criar-proposicao :ativa)))) "Casa ativa escreve")
    (is (nil? (:response (guarda (ctx :post :legislativo/criar-proposicao :fora-do-registro)))) "sem registro, sem restricao")))

(def encerrada {:estado "encerrado" :encerrada-em (Instant/parse "2027-01-10T15:00:00Z") :nome "Câmara Municipal de Baturité"
                :destino-acervo-url "https://camara.exemplo.gov.br/acervo"})

(deftest a-casa-encerrada-nao-responde-nada
  ;; ADR-0018 (fatia 2): ao contrario da suspensa, a encerrada recusa tambem a LEITURA — por isso o estado e' lido em
  ;; toda requisicao da Casa (do cache de 30 s, nao do banco)
  (let [guarda (r/restricao (fn [_] encerrada))]
    (doseq [[metodo rota] [[:get :legislativo/listar] [:post :participacao/protocolar-esic]
                           [:post :legislativo/criar-proposicao] [:post :exportacao-da-casa/gerar]]]
      (let [c (guarda (ctx metodo rota :enc))
            res (:response c)]
        (is (= 410 (:status res)) (str rota))
        (is (= {:erro "esta Camara nao usa mais O Plenario" :encerrada-em "2027-01-10T15:00:00Z"
                :nome "Câmara Municipal de Baturité"
                :destino-acervo-url "https://camara.exemplo.gov.br/acervo"}
               (json/read-value (:body res) json/keyword-keys-object-mapper)))
        (is (nil? (get-in c [:request :ator])) "sem ator: a trilha da Casa apagada nao ganha registro")))))

(deftest as-rotas-publicas-da-casa
  (is (r/rota-publica-da-casa? "/portal/casa/:ente"))
  (is (r/rota-publica-da-casa? "/portal/casa/:ente/materias/:proposicao_id"))
  (is (r/rota-publica-da-casa? "/auth/descoberta/:ente"))
  (is (not (r/rota-publica-da-casa? "/portal/casas")))
  (is (not (r/rota-publica-da-casa? "/operacao/casas/:ente")) "o console e' supratenant")
  (let [ente (random-uuid)
        i (r/casa-encerrada-publica (fn [e] (when (= e ente) encerrada)))
        ctx-publico (fn [v] {:request {:request-method :get :path-params {:ente v}}})]
    (is (= 410 (get-in ((:enter i) (ctx-publico (str ente))) [:response :status])))
    (is (nil? (:response ((:enter i) (ctx-publico (str (random-uuid)))))) "outra Casa segue")
    (is (nil? (:response ((:enter i) (ctx-publico "nao-e-uuid")))) "o handler faz o 400 dele"))
  (testing "o host poe o interceptor na frente so' das rotas publicas da Casa"
    (let [h (fn [_] {:status 200})
          rotas (r/com-casa-encerrada #{["/portal/casa/:ente" :get h :route-name :a]
                                        ["/auth/descoberta/:ente" :get [h] :route-name :b]
                                        ["/operacao/casas/:ente" :get [h] :route-name :c]}
                                      (constantly nil))
          por-nome (into {} (map (fn [r] [(nth r 4) (nth r 2)])) rotas)]
      (is (= 2 (count (:a por-nome))))
      (is (= 2 (count (:b por-nome))))
      (is (= [h] (:c por-nome))))))

(deftest o-cache-do-estado
  (let [leituras (atom 0)
        {:keys [estado-da-casa invalidar!]} (r/com-cache (fn [_] (swap! leituras inc) suspensa) 60000)]
    (estado-da-casa :a) (estado-da-casa :a)
    (is (= 1 @leituras) "dentro do prazo, do cache")
    (invalidar! :a)
    (estado-da-casa :a)
    (is (= 2 @leituras) "invalidado, le de novo"))
  (let [leituras (atom 0)
        {:keys [estado-da-casa]} (r/com-cache (fn [_] (swap! leituras inc) nil) 0)]
    (estado-da-casa :a) (estado-da-casa :a)
    (is (= 2 @leituras) "ttl 0 = sem cache")))

(deftest a-faixa
  (is (= {:desde "2026-09-30T12:00:00Z" :motivo "inadimplencia"} (r/visao suspensa true)) "o interno ve o motivo")
  (is (= {:desde "2026-09-30T12:00:00Z"} (r/visao suspensa false)) "a cidada so' ve desde quando")
  (is (nil? (r/visao {:estado "ativo"} true)))
  (is (nil? (r/visao nil false))))

(deftest o-apagamento-comecado-fecha-a-casa
  ;; mig 0177: o apagamento comecou (`:apagando?`) -> a Casa ainda esta' `suspenso`, mas ja' responde 410 em tudo —
  ;; inclusive a allowlist do cidadao — e o estado de uma Casa com o encerramento em curso nao fica no cache
  (let [apagando {:estado "suspenso" :motivo "encerramento_em_curso" :apagando? true :nome "Câmara Municipal de Baturité"}
        guarda (r/restricao (fn [_] apagando))]
    (doseq [[metodo rota] [[:get :legislativo/listar] [:post :participacao/protocolar-esic]
                           [:post :exportacao-da-casa/gerar]]]
      (let [c (guarda (ctx metodo rota :ap))]
        (is (= 410 (get-in c [:response :status])) (str rota))
        (is (nil? (get-in c [:request :ator])) "sem ator: nada novo na trilha"))))
  (testing "a Casa com o encerramento em curso e' lida a cada requisicao; as outras, do cache"
    (let [leituras (atom 0)
          {:keys [estado-da-casa]} (r/com-cache (fn [_] (swap! leituras inc)
                                                  {:estado "suspenso" :motivo "encerramento_em_curso"})
                                                60000)]
      (estado-da-casa :a) (estado-da-casa :a)
      (is (= 2 @leituras)))
    (let [leituras (atom 0)
          {:keys [estado-da-casa]} (r/com-cache (fn [_] (swap! leituras inc) {:estado "suspenso" :motivo "inadimplencia"})
                                                60000)]
      (estado-da-casa :a) (estado-da-casa :a)
      (is (= 1 @leituras)))))
