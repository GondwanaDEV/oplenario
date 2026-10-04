(ns oplenario.integracao-ia.plataforma-http-out-test
  "O cliente core -> IA (ADR-0008) contra um servidor HTTP local de verdade: segredo e tenant no pedido, 404 = nil,
  qualquer outra coisa = `:ia/indisponivel` (R-IA-1), inclusive IA fora do ar."
  (:require [clojure.test :refer [deftest is]]
            [jsonista.core :as json]
            [oplenario.integracao-ia.diplomat.http.out :as out])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)))

(defn- com-servidor [status corpo f]
  (let [visto (atom nil)
        s (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext s "/" (reify HttpHandler
                            (handle [_ ex]
                              (let [^HttpExchange ex ex
                                    b (.getBytes ^String corpo "UTF-8")]
                                (reset! visto {:path (str (.getRequestURI ex))
                                               :auth (.getFirst (.getRequestHeaders ex) "Authorization")
                                               :metodo (.getRequestMethod ex)
                                               :corpo (slurp (.getRequestBody ex) :encoding "UTF-8")})
                                (.sendResponseHeaders ex status (alength b))
                                (with-open [o (.getResponseBody ex)] (.write o b))))))
    (.start s)
    (try (f (str "http://127.0.0.1:" (.getPort (.getAddress s))) visto) (finally (.stop s 0)))))

(defn- indisponivel? [f]
  (try (f) false (catch clojure.lang.ExceptionInfo e (= :ia/indisponivel (:tipo (ex-data e))))))

(deftest le-com-segredo-e-tenant
  (com-servidor 200 "{\"trechos\":[{\"texto\":\"ola\",\"orador-nome\":\"Ana\"}]}"
    (fn [url visto]
      (let [r (out/ler-transcricao (out/plataforma-ia {:url (str url "/") :segredo "s"}) "e1" "t1")]
        (is (= "Ana" (get-in r [:trechos 0 :orador-nome])))
        (is (= {:path "/v1/entes/e1/transcricoes/t1" :auth "Bearer s"} (select-keys @visto [:path :auth])))))))

(deftest nao-existe-e-nil
  (com-servidor 404 "{}" (fn [url _] (is (nil? (out/ler-transcricao (out/plataforma-ia {:url url :segredo "s"}) "e" "t"))))))

(deftest falhas-viram-indisponivel
  (com-servidor 500 "{}" (fn [url _] (is (indisponivel? #(out/ler-transcricao (out/plataforma-ia {:url url :segredo "s"}) "e" "t")))))
  (com-servidor 401 "{}" (fn [url _] (is (indisponivel? #(out/ler-transcricao (out/plataforma-ia {:url url :segredo "s"}) "e" "t")))))
  (is (indisponivel? #(out/ler-transcricao (out/plataforma-ia {:url "http://127.0.0.1:9" :segredo "s"}) "e" "t"))
      "IA fora do ar")
  (is (indisponivel? #(out/ler-transcricao (out/plataforma-ia {:url nil :segredo nil}) "e" "t")) "nao configurada"))

(deftest le-o-rascunho-da-ata
  (com-servidor 200 "{\"texto\":\"Ata.\",\"pontos-a-confirmar\":[\"hora\"]}"
    (fn [url visto]
      (let [r (out/ler-rascunho-ata (out/plataforma-ia {:url url :segredo "s"}) "e1" "r1")]
        (is (= ["Ata." ["hora"]] [(:texto r) (:pontos-a-confirmar r)]))
        (is (= "/v1/entes/e1/atas/rascunhos/r1" (:path @visto))))))
  (is (indisponivel? #(out/ler-rascunho-ata (out/plataforma-ia {:url nil :segredo nil}) "e" "r"))))

(deftest busca-posta-a-consulta-e-devolve-os-ids
  (com-servidor 200 "{\"modelo\":\"fake-hash-384\",\"resultados\":[{\"tipo\":\"proposicao\",\"ref-id\":\"p1\",\"parte\":0,\"texto\":\"Merenda.\",\"meta\":{},\"score\":0.03}]}"
    (fn [url visto]
      (let [r (out/buscar (out/plataforma-ia {:url url :segredo "s"}) "e1"
                          {:consulta "merenda escolar" :tipos ["proposicao"] :limite 10})]
        (is (= [["proposicao" "p1" "Merenda."]] (map (juxt :tipo :ref-id :texto) (:resultados r))))
        (is (= {:path "/v1/entes/e1/busca" :auth "Bearer s" :metodo "POST"} (select-keys @visto [:path :auth :metodo])))
        (is (= {"consulta" "merenda escolar" "tipos" ["proposicao"] "limite" 10}
               (json/read-value (:corpo @visto)))))))
  (com-servidor 503 "{}" (fn [url _] (is (indisponivel? #(out/buscar (out/plataforma-ia {:url url :segredo "s"}) "e" {:consulta "xx"})))))
  (is (indisponivel? #(out/buscar (out/plataforma-ia {:url nil :segredo nil}) "e" {:consulta "xx"}))))

(deftest le-o-rascunho-do-resumo
  (com-servidor 200 "{\"texto\":\"Resumo.\",\"texto-limpo\":\"Resumo.\"}"
    (fn [url visto]
      (is (= "Resumo." (:texto (out/ler-rascunho-resumo (out/plataforma-ia {:url url :segredo "s"}) "e1" "r1"))))
      (is (= "/v1/entes/e1/resumos/rascunhos/r1" (:path @visto))))))

(deftest apagar-a-casa-no-satelite-e-delete-com-segredo
  ;; ADR-0018 (Eixo 4.5): o core pede ao satelite que apague o que e' da Casa; o schema `ia` e' so' dele
  (com-servidor 200 "{\"ente_id\":\"e1\",\"apagados\":{\"ia.trabalho\":3},\"total\":3}"
    (fn [url visto]
      (let [r (out/apagar-ente (out/plataforma-ia {:url url :segredo "s"}) "e1")]
        (is (= 3 (:total r)))
        (is (= {:path "/v1/entes/e1" :auth "Bearer s" :metodo "DELETE"} (select-keys @visto [:path :auth :metodo]))))))
  (com-servidor 404 "{}" (fn [url _] (is (indisponivel? #(out/apagar-ente (out/plataforma-ia {:url url :segredo "s"}) "e")))
                           "404 nao e' 'nada a apagar': a rota existe sempre; sem ela, o passo fica pendente"))
  (com-servidor 503 "{}" (fn [url _] (is (indisponivel? #(out/apagar-ente (out/plataforma-ia {:url url :segredo "s"}) "e")))))
  (is (indisponivel? #(out/apagar-ente (out/plataforma-ia {:url "http://127.0.0.1:9" :segredo "s"}) "e")) "IA fora do ar")
  (is (indisponivel? #(out/apagar-ente (out/plataforma-ia {:url nil :segredo nil}) "e")) "nao configurada"))

(deftest reportar-erro-posta-quem-e-categoria
  ;; Feature 8.4: o reporte vai ao registro da Camada de Confianca do satelite; 404 = execucao que nao e' desta Casa
  (com-servidor 200 "{\"execucao-id\":\"x1\",\"reportado\":true}"
    (fn [url visto]
      (let [r (out/reportar-erro (out/plataforma-ia {:url url :segredo "s"}) "e1" "x1"
                                 {:quem "p1" :categoria "citacao_errada"})]
        (is (true? (:reportado r)))
        (is (= {:path "/v1/entes/e1/execucoes/x1/reportes" :auth "Bearer s" :metodo "POST"}
               (select-keys @visto [:path :auth :metodo])))
        (is (= {"quem" "p1" "categoria" "citacao_errada"} (json/read-value (:corpo @visto)))))))
  (com-servidor 404 "{}" (fn [url _] (is (nil? (out/reportar-erro (out/plataforma-ia {:url url :segredo "s"}) "e" "x"
                                                                  {:quem "p" :categoria "outro"})))))
  (com-servidor 503 "{}" (fn [url _] (is (indisponivel? #(out/reportar-erro (out/plataforma-ia {:url url :segredo "s"})
                                                                             "e" "x" {:quem "p" :categoria "outro"})))))
  (is (indisponivel? #(out/reportar-erro (out/plataforma-ia {:url nil :segredo nil}) "e" "x"
                                         {:quem "p" :categoria "outro"}))))
