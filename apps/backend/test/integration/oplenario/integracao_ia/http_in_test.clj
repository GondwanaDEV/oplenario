(ns oplenario.integracao-ia.http-in-test
  "Borda HTTP de SERVICO da fronteira core <-> IA (ADR-0008). DB-free: Repo e seams FAKE. Prova o segredo
  (503 desligado, 401 errado), o feed, o sigilo fail-closed do contexto e do conteudo, e a caixa de entrada."
  (:require [clojure.test :refer [deftest is testing]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.integracao-ia.components.repositorio :as repo]
            [oplenario.integracao-ia.diplomat.http.in :as ia-http]
            [oplenario.interceptors :as it])
  (:import (java.io ByteArrayInputStream)
           (java.time Instant)))

(def segredo "segredo-de-teste-com-tamanho-suficiente")
(def ente #uuid "10000000-0000-0000-0000-000000000001")
(def sid #uuid "30000000-0000-0000-0000-000000000003")
(def seg #uuid "20000000-0000-0000-0000-000000000002")

(defn- fake-repo [recebidos]
  (reify repo/RepoIntegracaoIA
    (listar-eventos [_ depois limite]
      (->> (range 1 6)
           (filter #(> % depois))
           (take limite)
           (mapv (fn [n] {:seq n :ente-id ente :tipo "GravacaoVinculada" :versao 1 :chave (str "k" n)
                          :payload {:segmento-id "s"} :criado-em (Instant/parse "2026-09-26T20:00:00Z")}))))
    (receber-evento! [_ evento efeito]
      (if (some #(= (:chave evento) (:chave %)) @recebidos)
        {:aplicado false}
        (do (efeito :tx (:ente-id evento) evento) (swap! recebidos conj evento) {:aplicado true})))))

(def contexto-ok
  {:sessao {:id sid :tipo-sessao "ordinaria" :numero-sequencial 12 :estado "encerrada"
            :aberta-em (Instant/parse "2026-09-22T21:00:00Z") :encerrada-em (Instant/parse "2026-09-23T00:00:00Z")}
   :segmentos [{:id seg :iniciou-em (Instant/parse "2026-09-22T20:50:00Z") :encerrou-em nil :acesso-restrito false}
               {:id (random-uuid) :iniciou-em (Instant/parse "2026-09-22T22:00:00Z") :acesso-restrito true}]
   :falas [{:id (random-uuid) :orador-id #uuid "40000000-0000-0000-0000-000000000004" :tipo-fala "principal"
            :fase "expediente" :iniciou-em (Instant/parse "2026-09-22T21:10:00Z")
            :encerrou-em (Instant/parse "2026-09-22T21:15:00Z")}]
   :nomes {#uuid "40000000-0000-0000-0000-000000000004" "Ana Ribeiro"}})

(defn- servico [& {:keys [seg-redo contexto abrir efeitos recebidos ata]
                   :or {seg-redo segredo recebidos (atom [])}}]
  (-> (http/servico (config/carregar)
                    (ia-http/rotas {:repo-integracao-ia (fake-repo recebidos)
                                    :segredo seg-redo
                                    :contexto-da-sessao (or contexto (fn [_ _] contexto-ok))
                                    :abrir-gravacao (or abrir (fn [_ _] nil))
                                    :registrar-transcricao (fn [_tx e m] (some-> efeitos (swap! conj [e m])))
                                    :registrar-rascunho-ata (fn [_tx e m] (some-> efeitos (swap! conj [:ata e m])))
                                    :ata-para-ia (or ata (fn [_ _ _] nil))})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- com-segredo [& [s]] {"authorization" (str "Bearer " (or s segredo))})
(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest segredo-de-servico
  (testing "sem segredo configurado: tudo desligado"
    (is (= 503 (:status (pt/response-for (servico :seg-redo nil) :get "/integracao/ia/v1/eventos"
                                         :headers (com-segredo))))))
  (testing "segredo errado ou ausente: 401"
    (is (= 401 (:status (pt/response-for (servico) :get "/integracao/ia/v1/eventos" :headers (com-segredo "outro")))))
    (is (= 401 (:status (pt/response-for (servico) :get "/integracao/ia/v1/eventos")))))
  (testing "o login de pessoa nao abre a porta de servico"
    (is (= 401 (:status (pt/response-for (servico) :get "/integracao/ia/v1/eventos"
                                         :headers {"authorization" "Bearer {\"sub\":\"u\"}"}))))))

(deftest feed-com-cursor
  (let [r (pt/response-for (servico) :get "/integracao/ia/v1/eventos?depois=2&limite=2" :headers (com-segredo))
        b (ler r)]
    (is (= 200 (:status r)))
    (is (= [3 4] (map :seq (:eventos b))))
    (is (= 4 (:proximo b)) "o proximo cursor e' o ultimo seq entregue")
    (is (= (str ente) (:ente-id (first (:eventos b))))))
  (let [b (ler (pt/response-for (servico) :get "/integracao/ia/v1/eventos?depois=9" :headers (com-segredo)))]
    (is (= {:eventos [] :proximo 9} b) "nada novo: o cursor fica onde estava"))
  (is (= 400 (:status (pt/response-for (servico) :get "/integracao/ia/v1/eventos?depois=abc" :headers (com-segredo))))))

(defn- url-contexto [e s] (str "/integracao/ia/v1/entes/" e "/sessoes/" s "/contexto"))

(deftest contexto-da-sessao
  (let [r (pt/response-for (servico) :get (url-contexto ente sid) :headers (com-segredo))
        b (ler r)]
    (is (= 200 (:status r)))
    (is (= 1 (count (:segmentos b))) "o segmento restrito nao vai")
    (is (= (str "/integracao/ia/v1/entes/" ente "/gravacoes/" seg "/conteudo") (:conteudo-uri (first (:segmentos b)))))
    (is (= "Ana Ribeiro" (:orador-nome (first (:falas b)))) "o nome do orador na data da sessao")
    (is (= "2026-09-22T21:10:00Z" (:iniciou-em (first (:falas b))))))
  (is (= 403 (:status (pt/response-for (servico :contexto (fn [_ _] (assoc-in contexto-ok [:sessao :tipo-sessao] "secreta")))
                                       :get (url-contexto ente sid) :headers (com-segredo))))
      "sessao secreta nunca vai para a IA")
  (is (= 404 (:status (pt/response-for (servico :contexto (fn [_ _] nil)) :get (url-contexto ente sid)
                                       :headers (com-segredo)))))
  (is (= 400 (:status (pt/response-for (servico) :get (url-contexto "nao-uuid" sid) :headers (com-segredo))))))

(defn- url-conteudo [e s] (str "/integracao/ia/v1/entes/" e "/gravacoes/" s "/conteudo"))

(deftest conteudo-da-gravacao
  (let [pedido (atom nil)
        r (pt/response-for (servico :abrir (fn [e s] (reset! pedido [e s])
                                              {:stream (ByteArrayInputStream. (.getBytes "audio-bruto")) :audio-hash "ab12"}))
                           :get (url-conteudo ente seg) :headers (com-segredo))]
    (is (= 200 (:status r)))
    (is (= "audio-bruto" (:body r)))
    (is (= "ab12" (get-in r [:headers "X-Conteudo-Sha256"])) "a IA confere a integridade")
    (is (= [ente seg] @pedido) "o tenant vem do caminho, explicito"))
  (is (= 403 (:status (pt/response-for (servico :abrir (fn [_ _] :restrita)) :get (url-conteudo ente seg)
                                       :headers (com-segredo)))))
  (is (= 404 (:status (pt/response-for (servico) :get (url-conteudo ente seg) :headers (com-segredo))))))

(defn- evento [chave & {:keys [tipo versao payload] :or {tipo "TranscricaoConcluida" versao 1}}]
  (json/write-value-as-string
   {"tipo" tipo "versao" versao "chave" chave "ente-id" (str ente) "correlation-id" "c-9"
    "ocorrido-em" "2026-09-26T22:00:00Z"
    "payload" (or payload {"sessao-id" (str sid) "segmento-id" (str seg) "transcricao-id" (str (random-uuid))
                           "versao-transcricao" 1 "idioma" "pt-BR" "duracao-s" 3600.5 "n-trechos" 412
                           "cobertura-atribuida" 0.83 "modelo-asr" "whisper-large-v3-turbo"})}))

(defn- post [svc corpo]
  (pt/response-for svc :post "/integracao/ia/v1/eventos"
                   :headers (assoc (com-segredo) "Content-Type" "application/json") :body corpo))

(deftest caixa-de-entrada
  (let [efeitos (atom []) svc (servico :efeitos efeitos)]
    (let [r (post svc (evento "k-1"))]
      (is (= 201 (:status r)))
      (is (= {:chave "k-1" :aplicado true} (ler r))))
    (is (= 200 (:status (post svc (evento "k-1")))) "reenvio: 200, sem efeito novo")
    (is (= 1 (count @efeitos)))
    (let [[e m] (first @efeitos)]
      (is (= ente e))
      (is (= ["concluida" sid seg 412] [(:situacao m) (:sessao-id m) (:segmento-id m) (:n-trechos m)]))))
  (testing "falha categorizada"
    (let [efeitos (atom [])]
      (is (= 201 (:status (post (servico :efeitos efeitos)
                                (evento "f-1" :tipo "TranscricaoFalhou"
                                        :payload {"sessao-id" (str sid) "segmento-id" (str seg) "categoria" "entrada"
                                                  "detalhe" "audio corrompido" "retentavel" false})))))
      (is (= "entrada" (:categoria-erro (second (first @efeitos)))))))
  (testing "contrato desconhecido ou invalido nao e' aplicado"
    (is (= 422 (:status (post (servico) (evento "k-2" :versao 2)))) "versao que o core ainda nao conhece")
    (is (= 422 (:status (post (servico) (evento "k-3" :tipo "ResumoCidadaoPronto")))))
    (is (= 400 (:status (post (servico) (evento "k-4" :payload {"sessao-id" "x"})))))
    (is (= 400 (:status (post (servico) (evento "k-5" :tipo "TranscricaoFalhou"
                                              :payload {"sessao-id" (str sid) "segmento-id" (str seg)
                                                        "categoria" "saida_plausivel_errada" "detalhe" "x"
                                                        "retentavel" false}))))
        "categoria 5 nao e' falha tecnica (§22.3.5)")))

(deftest caixa-de-entrada-da-ata
  (let [efeitos (atom []) svc (servico :efeitos efeitos) solic (str (random-uuid)) rid (str (random-uuid))]
    (is (= 201 (:status (post svc (evento "a-1" :tipo "AtaRascunhoPronta"
                                          :payload {"sessao-id" (str sid) "solicitacao-id" solic "rascunho-id" rid
                                                    "modelo-llm-id" "fake:fake-1" "prompt-versao" "ata-v1"
                                                    "incerteza" "revisar_com_atencao" "n-citacoes" 3
                                                    "n-citacoes-conferidas" 3 "n-paragrafos-sem-fonte" 1
                                                    "n-pontos-a-confirmar" 1})))))
    (let [[marca e m] (first @efeitos)]
      (is (= [:ata ente] [marca e]) "o rascunho vai para o seam da ata, nao para o da transcricao")
      (is (= ["pronto" "ata-v1" "revisar_com_atencao" 1]
             [(:situacao m) (:prompt-versao m) (:incerteza m) (:n-pontos-a-confirmar m)])))
    (is (= 201 (:status (post svc (evento "a-2" :tipo "AtaFalhou"
                                          :payload {"sessao-id" (str sid) "solicitacao-id" solic
                                                    "categoria" "entrada" "detalhe" "sem transcricao"
                                                    "retentavel" false})))))
    (is (= ["falhou" "entrada"] ((juxt :situacao :categoria-erro) (nth (second @efeitos) 2))))
    (is (= 400 (:status (post svc (evento "a-3" :tipo "AtaRascunhoPronta"
                                          :payload {"sessao-id" (str sid) "solicitacao-id" solic "rascunho-id" rid
                                                    "modelo-llm-id" "m" "prompt-versao" "p" "incerteza" "talvez"
                                                    "n-citacoes" 0 "n-citacoes-conferidas" 0
                                                    "n-paragrafos-sem-fonte" 0 "n-pontos-a-confirmar" 0}))))
        "incerteza fora do vocabulario")))

(deftest texto-da-ata-publicada
  (let [pedidos (atom [])
        svc (servico :ata (fn [e s v] (swap! pedidos conj [e s v])
                            (case v
                              1 {:versao 1 :texto "Ata final." :conteudo-sha256 "sha256:aa"
                                 :origem-redacao "gerada_automaticamente" :publicada-por (random-uuid)}
                              2 :restrita
                              nil)))
        url (fn [v] (str "/integracao/ia/v1/entes/" ente "/sessoes/" sid "/atas/" v))
        r (pt/response-for svc :get (url 1) :headers (com-segredo))]
    (is (= 200 (:status r)))
    (is (= {:versao 1 :texto "Ata final." :conteudo-sha256 "sha256:aa" :origem-redacao "gerada_automaticamente"}
           (ler r)) "so' o contrato (quem publicou nao sai)")
    (is (= [ente sid 1] (first @pedidos)) "o tenant do caminho")
    (is (= 403 (:status (pt/response-for svc :get (url 2) :headers (com-segredo)))))
    (is (= 404 (:status (pt/response-for svc :get (url 3) :headers (com-segredo)))))
    (is (= 400 (:status (pt/response-for svc :get (url "x") :headers (com-segredo)))))
    (is (= 401 (:status (pt/response-for svc :get (url 1) :headers (com-segredo "errado")))))))
