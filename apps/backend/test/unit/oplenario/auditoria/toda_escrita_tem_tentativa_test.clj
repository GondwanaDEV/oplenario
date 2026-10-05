(ns oplenario.auditoria.toda-escrita-tem-tentativa-test
  "ADR-0017 (adendo de 04/10/2026) — a garantia e' ESTRUTURAL, sem lista de rotas: toda rota de escrita da tabela que o
  host monta (`rotas/montar`) tem o interceptor da TENTATIVA logo antes do handler. Rota de escrita nova herda isso por
  estar na tabela; se alguem montar uma por fora de `com-tentativa`, este teste a nomeia. E a logica pura da tentativa:
  o que vira tentativa, o desfecho que a aponta e a corrente conferindo registros antigos e novos juntos."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.auditoria.adapters.in.filtro :as filtro]
            [oplenario.auditoria.adapters.out.trilha :as out]
            [oplenario.auditoria.diplomat.http.in :as auditoria-http]
            [oplenario.auditoria.logic :as logic]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas])
  (:import (java.time Instant)))

(def ^:private escrita #{:post :put :patch :delete})

(defn- cadeia [[_ _ c]] (if (vector? c) c [c]))

(defn- nome [r] (let [v (vec r) i (.indexOf v :route-name)] (if (>= i 0) (nth v (inc i)) (first r))))

(defn- tem-tentativa-antes-do-handler? [r]
  (let [c (cadeia r)]
    (and (>= (count c) 2) (identical? auditoria-http/tentativa (peek (pop c))))))

(def ^:private rotas-do-host
  ;; as mesmas chaves do catalogo-lint: ligam os fragmentos que so' existem com o Repo presente
  (delay (rotas/montar {:idp (idp-dev/idp-dev) :repo-integracao-ia :lint :repo-auditoria :lint})))

(deftest toda-rota-de-escrita-do-host-tem-a-tentativa-antes-do-handler
  (let [escritas (filter #(escrita (second %)) @rotas-do-host)
        sem (remove tem-tentativa-antes-do-handler? escritas)]
    (is (> (count escritas) 100) (str "o teste viu a tabela inteira do host: " (count escritas) " rotas de escrita"))
    (is (empty? sem)
        (str "rota de escrita SEM a tentativa da trilha (ADR-0017): " (vec (sort (map (comp str nome) sem)))
             " — a rota tem de entrar na tabela que `rotas/montar` passa por `auditoria-http/com-tentativa`"))
    (testing "uma vez so' por rota (a tentativa duplicada gravaria dois registros)"
      (is (every? #(= 1 (count (filter (fn [i] (identical? auditoria-http/tentativa i)) (cadeia %)))) escritas)))))

(deftest a-leitura-nao-ganha-tentativa
  (let [leituras (filter #(= :get (second %)) @rotas-do-host)]
    (is (> (count leituras) 100))
    (is (not-any? (fn [r] (some #(identical? auditoria-http/tentativa %) (cadeia r))) leituras))))

(deftest o-detector-tem-dentes
  (let [auth {:name ::auth :enter identity} h (fn [_] {:status 200})
        nova ["/coisa-nova/:id" :post [auth h] :route-name :modulo/coisa-nova]
        solta ["/handler-solto" :delete h :route-name :modulo/handler-solto]
        ler ["/ler" :get [auth h] :route-name :modulo/ler]]
    (testing "a rota de escrita montada por fora e' acusada"
      (is (not (tem-tentativa-antes-do-handler? nova)))
      (is (not (tem-tentativa-antes-do-handler? solta))))
    (testing "passando por `com-tentativa`, a rota nova herda — o handler continua o ultimo e a authz vem antes"
      (let [depois (auditoria-http/com-tentativa #{nova solta ler})
            por-nome (into {} (map (juxt nome identity)) depois)]
        (is (= [auth auditoria-http/tentativa h] (cadeia (por-nome :modulo/coisa-nova))))
        (is (= [auditoria-http/tentativa h] (cadeia (por-nome :modulo/handler-solto))))
        (is (= ler (por-nome :modulo/ler)) "a leitura passa intacta")
        (is (= [:route-name :modulo/coisa-nova] (subvec (por-nome :modulo/coisa-nova) 3)) "o resto da rota nao muda")))))

;; ---- a logica pura ----

(def ^:private ente (random-uuid))
(def ^:private maria (random-uuid))
(def ^:private prop "30000000-0000-0000-0000-000000000003")

(defn- req [metodo ator]
  {:request-method metodo :ator ator :path-params {:proposicao-id prop}
   :headers {"x-forwarded-for" "189.45.12.7"}})

(def ^:private secretaria {:ente-id ente :identidade-id maria :papeis #{"secretario"}})

(deftest o-que-vira-tentativa
  (testing "a escrita de um ator da Casa: iniciado, sem status, com quem/o que/sobre o que/de onde"
    (let [t (logic/registro-da-tentativa (req :post secretaria) :legislativo/despachar)]
      (is (= ["escrita" "iniciado" nil "legislativo/despachar" "proposicao" prop "189.45.12.7" {:metodo "POST"}]
             ((juxt :classe :decisao :status-http :acao :recurso-tipo :recurso-id :ip :detalhe) t)))
      (is (= [ente maria ["secretario"]] ((juxt :ente-id :identidade-id :papeis) t)))))
  (testing "os quatro verbos de escrita"
    (doseq [m [:post :put :patch :delete]]
      (is (some? (logic/registro-da-tentativa (req m secretaria) :x/y)) (str m))))
  (testing "leitura, anonimo e operador (ator sem Casa) nao tem tentativa"
    (is (nil? (logic/registro-da-tentativa (req :get secretaria) :x/y)))
    (is (nil? (logic/registro-da-tentativa (req :post nil) :x/y)))
    (is (nil? (logic/registro-da-tentativa (req :post {:operador-id (random-uuid)}) :x/y)))))

(deftest o-desfecho-aponta-a-tentativa
  (let [com (logic/registro-da-requisicao (req :post secretaria) {:status 201} :legislativo/despachar 41)
        sem (logic/registro-da-requisicao (req :post secretaria) {:status 201} :legislativo/despachar)]
    (is (= {:metodo "POST" :tentativa 41} (:detalhe com)))
    (is (= {:metodo "POST"} (:detalhe sem)) "sem tentativa, o registro e' o de antes — byte a byte")
    (is (= "permitido" (:decisao com)))
    (testing "a negacao (403 antes do handler) segue com um registro so', sem apontamento"
      (is (= ["negacao" "negado" {:metodo "POST"}]
             ((juxt :classe :decisao :detalhe)
              (logic/registro-da-requisicao (req :post secretaria) {:status 403} :legislativo/despachar)))))))

(defn- selar [corrente r]
  (let [anterior (peek corrente)
        r (assoc r :ente-id ente :seq (inc (count corrente)) :id (random-uuid)
                 :ocorrido-em (Instant/parse "2026-10-04T12:00:00Z"))]
    (conj corrente (assoc r :selo-anterior (or (:selo anterior) "")
                          :selo (logic/selo-de (or (:selo anterior) "") r)))))

(deftest a-corrente-confere-registros-antigos-e-novos-juntos
  (let [antigo (logic/registro-da-requisicao (req :post secretaria) {:status 201} :legislativo/despachar)
        tent   (logic/registro-da-tentativa (req :post secretaria) :legislativo/despachar)
        desf   (logic/registro-da-requisicao (req :post secretaria) {:status 201} :legislativo/despachar 2)
        c      (reduce selar [] [antigo tent desf])]
    (is (= {:integra true :total 3 :cabeca (:selo (peek c)) :quebra-em nil} (logic/verificar c)))
    (testing "o apontamento esta' no selo: trocar a tentativa apontada quebra a corrente ali"
      (is (= 3 (:quebra-em (logic/verificar (assoc-in c [2 :detalhe :tentativa] 1))))))))

(deftest a-tentativa-sem-desfecho-sai-em-palavras
  (let [orfa {:seq 7 :ocorrido-em (Instant/parse "2026-10-04T12:00:00Z") :ator-tipo "pessoa" :ator-nome "Maria"
              :papeis ["secretario"] :acao "legislativo/despachar" :classe "escrita" :decisao "iniciado"
              :campos [] :canal "web" :selo "b" :selo-anterior "a"}]
    (is (= "sem_desfecho" (:decisao (out/registro->wire orfa))) "no fio, o vocabulario da tela — nunca o enum do banco")
    (is (.contains ^String (out/->csv [orfa]) ",sem desfecho registrado,"))
    (is (not (.contains ^String (out/->csv [orfa]) "iniciado")))
    (is (.contains ^String (out/->csv [(assoc orfa :decisao "permitido")]) ",permitido,") "o resto do CSV nao muda")))

(deftest o-filtro-sem-desfecho
  (is (= {:sem-desfecho true} (filtro/query->filtro {:classe "sem_desfecho"})))
  (is (= {:classe "escrita"} (filtro/query->filtro {:classe "escrita"})))
  (is (= :validacao/invalido (try (filtro/query->filtro {:classe "iniciado"}) nil
                                  (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))
      "o enum do banco nao e' vocabulario do filtro"))
