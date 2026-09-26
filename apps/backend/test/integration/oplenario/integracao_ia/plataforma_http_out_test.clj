(ns oplenario.integracao-ia.plataforma-http-out-test
  "O cliente core -> IA (ADR-0008) contra um servidor HTTP local de verdade: segredo e tenant no pedido, 404 = nil,
  qualquer outra coisa = `:ia/indisponivel` (R-IA-1), inclusive IA fora do ar."
  (:require [clojure.test :refer [deftest is]]
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
                                               :auth (.getFirst (.getRequestHeaders ex) "Authorization")})
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
        (is (= {:path "/v1/entes/e1/transcricoes/t1" :auth "Bearer s"} @visto))))))

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
