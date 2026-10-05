(ns oplenario.auditoria.custo-no-voto-real-test
  "MEDICAO (PG real) — ADR-0017, adendo de 04/10/2026, seccao 'Medicao no voto real'.

  O custo da 'tentativa antes, desfecho depois' foi medido primeiro com um handler de teste
  (`janela-de-perda-test/vinte-e-uma-escritas-simultaneas-da-mesma-casa`). Aqui a mesma pergunta contra o VOTO REAL:
  21 vereadores registrando o PROPRIO voto ao mesmo tempo numa votacao nominal aberta, por
  `POST /sessoes/:id/votacoes/:votacao-id/meu-voto`, atravessando a cadeia que o host monta — `rotas/montar` com os
  Repo-Components reais do sistema, os interceptores globais do host (erro, trilha de auditoria), a autenticacao
  sobre o `repo-identidade` real (snapshot do ator, papel `vereador`), a authz grossa da rota, a policy fina de
  mandato vigente + presenca registrada NESTA sessao, a escrita do voto sob lock da votacao, o outbox (na tx do ato)
  e a trilha com o relay de pe' (como em producao).

  Duas condicoes, na MESMA Casa-modelo (21 vereadores presentes), repetidas K rodadas, cada rodada com sessao, materia e
  votacao NOVAS:
   - COM a tentativa: a tabela que `rotas/montar` entrega, como e' hoje;
   - SEM a tentativa: a mesma tabela com o interceptor `tentativa` retirado de cada rota de escrita (= a tabela
     antes de `com-tentativa`, como o teste original a desligou). Nenhuma chave de producao foi acrescentada.

  O teste ASSERE correcao (21 votos gravados, 21 pares tentativa+desfecho, corrente selada confere, nenhuma resposta
  que nao seja 201) e apenas IMPRIME os tempos: a VM e' compartilhada e o numero varia. Linhas `CUSTO-NO-VOTO ...`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.test :as pt]
            [io.pedestal.http :as ph]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.admin-sistema.components.idp-admin :as idp-admin]
            [oplenario.auditoria.components.repositorio :as repo-aud]
            [oplenario.auditoria.diplomat.http.in :as auditoria-http]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-sess]
            [oplenario.sistema :as sistema])
  (:import (java.time Instant LocalDate)
           (java.util.concurrent CountDownLatch)))

(def ^:dynamic *sys* nil)

(def vereadores-por-casa 21)

(def rodadas
  "K rodadas medidas por condicao (alem de 2 de aquecimento, fora da medida). `CUSTO_VOTO_RODADAS` so' para rodar mais."
  (or (some-> (System/getenv "CUSTO_VOTO_RODADAS") parse-long) 10))

(def aquecimento 2)

(use-fixtures :once
  (fn [t]
    (let [s (component/start (sistema/novo-sistema (config/carregar)))
          ds (:ds (:datasource s))]
      (migracao/migrar! ds)
      (referencia/inserir-municipio! ds {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 2703391})
      (referencia/inserir-tribunal! ds {:codigo "TCE-CE" :nome "Tribunal de Contas do Estado do Ceara" :uf "CE" :tipo "estadual"})
      (binding [*sys* s] (try (t) (finally (component/stop s)))))))

(defn- ds [] (:ds (:datasource *sys*)))

;; ---------- o host: as mesmas rotas e os mesmos globais que o ServidorHttp usa ----------

(def ^:private chaves-do-servidor
  ;; as de `component/using` do servidor-http em `sistema-serve`
  [:repo-admin-sistema :repo-identidade :repo-sessoes :repo-legislativo :repo-compliance :repo-participacao
   :repo-transparencia :repo-paineis :repo-cadastros :canal-store :objeto-store :registro-fatos :repo-integracao-ia
   :repo-normas :repo-auditoria :repo-comunicacao :repo-motor])

(defn- componente-do-servidor
  "O que `ServidorHttp` recebe: o `config`, o IdP (o de dev: este ns roda sempre em dev/test) e os Repo reais."
  []
  (assoc (select-keys *sys* chaves-do-servidor)
         :config (config/carregar)
         :idp (idp-dev/idp-dev)
         :idp-operacao (idp-admin/idp-operacao-dev)))

(defn- sem-a-tentativa
  "A tabela de rotas como era antes de `com-tentativa`: o interceptor `tentativa` sai da cadeia de cada escrita."
  [rotas]
  (into #{}
        (map (fn [[caminho metodo cadeia & resto :as r]]
               (if (vector? cadeia)
                 (into [caminho metodo (filterv #(not (identical? auditoria-http/tentativa %)) cadeia)] resto)
                 r)))
        rotas))

(defn- servico [com-tentativa?]
  (let [this (componente-do-servidor)
        tabela (rotas/montar this)
        tabela (if com-tentativa? tabela (sem-a-tentativa tabela))
        globais (#'sistema/globais-do-host this)]
    (-> (http/servico (:config this) tabela (it/globais-com globais))
        ph/create-server ::ph/service-fn)))

;; ---------- a Casa-modelo: 21 vereadores com mandato, vinculo e papel; uma sessao aberta por rodada ----------

(defn- cpf-valido [n]
  ;; 9 digitos a partir de `n` (nunca todos iguais) + os dois verificadores
  (let [base (mapv #(Character/digit ^char % 10) (format "%09d" (+ 100000001 (mod (* 7919 n) 800000000))))
        dv (fn [ds pesos] (let [r (mod (reduce + (map * ds pesos)) 11)] (if (< r 2) 0 (- 11 r))))
        d1 (dv base (range 10 1 -1))
        d2 (dv (conj base d1) (range 11 1 -1))]
    (apply str (concat base [d1 d2]))))

(def ^:private contador-de-cpf (atom (System/nanoTime)))

(defn- semear-casa!
  "Devolve {:ente :vereadores [{:identidade :vereador-id}]} — 21 vereadores, mandato vigente, vinculo de vereador ativo
  com o papel `vereador`, pelos Repo-Components reais (a autenticacao do pedido le' o snapshot do ator deles)."
  []
  (let [{:keys [repo-cadastros repo-identidade]} *sys*
        ente (random-uuid) leg (random-uuid)]
    (repo-cad/criar-ente! repo-cadastros ente {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Camara Municipal de Fortaleza"})
    (repo-cad/criar-legislatura! repo-cadastros ente {:id leg :ente-id ente :numero 19 :ano-inicio 2025 :ano-fim 2028 :vigente true})
    {:ente ente
     :vereadores
     (vec (for [i (range vereadores-por-casa)]
            (let [iid (repo-id/criar-identidade! repo-identidade {:id (random-uuid) :cpf (cpf-valido (swap! contador-de-cpf inc))
                                                                  :nome (str "Vereador " i)})
                  vid (random-uuid)]
              (repo-cad/criar-vereador! repo-cadastros ente {:id vid :ente-id ente :identidade-id iid
                                                             :nome (str "Vereador " i) :nome-parlamentar (str "Vereador " i)})
              (repo-cad/criar-mandato! repo-cadastros ente {:id (random-uuid) :ente-id ente :vereador-id vid :legislatura-id leg
                                                            :partido "PT" :estado "vigente" :natureza "titular"
                                                            :vigencia-inicio (LocalDate/of 2025 1 1)})
              (repo-id/conceder-acesso! repo-identidade ente
                                        {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "vereador" :estado "ativo"}
                                        ["vereador"])
              {:identidade iid :vereador-id vid})))}))

(defn- preparar-votacao!
  "Uma sessao ABERTA com os 21 presentes e uma votacao NOMINAL aberta sobre uma materia nova. {:sid :vid}."
  [{:keys [ente vereadores]}]
  (let [{:keys [repo-sessoes repo-legislativo]} *sys*
        sid (:id (repo-sess/agendar-sessao! repo-sessoes ente
                                            {:id (random-uuid) :sessao-legislativa-id (random-uuid) :tipo-sessao "ordinaria"
                                             :modalidade "presencial" :agendada-para (Instant/now)}))
        _ (repo-sess/transicionar-sessao! repo-sessoes ente {:id sid :para "aberta" :updated-by (random-uuid) :lock-version 0})
        aberta-em (:aberta-em (repo-sess/buscar-sessao repo-sessoes ente sid))
        pid (:id (repo-leg/protocolar! repo-legislativo ente
                                       {:id (random-uuid) :tipo "projeto_lei" :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
                                        :ementa "Materia da medicao do custo da trilha"}))
        vid (:id (repo-leg/abrir-votacao! repo-legislativo ente
                                          {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id pid :modalidade "nominal"
                                           :quorum-tipo "maioria_simples" :sessao-id sid}))]
    (doseq [{:keys [vereador-id]} vereadores]
      (repo-sess/registrar-presenca! repo-sessoes ente
                                     {:id (random-uuid) :sessao-id sid :vereador-id vereador-id :tipo "entrada"
                                      :modalidade "plenario" :fonte "autoatendimento"
                                      :ocorrido-em (.minusSeconds ^Instant aberta-em 1) :agora (Instant/now)}))
    {:sid sid :vid vid}))

;; ---------- o disparo ----------

(defn- como [ente iid]
  {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str iid)}))
   ;; o mock de `pt/response-for` le' o tipo da chave CAPITALIZADA (ver sessoes/http_in_test); minuscula nao parseia o corpo
   "Content-Type" "application/json"})

(def ^:private opcoes ["sim" "nao" "abstencao"])

(defn- disparar!
  "Os 21 `meu-voto`, todos soltos no mesmo instante (cada thread ja' parada na largada). Devolve os status, o tempo de
  parede ate' o ultimo responder e a latencia de cada requisicao, em ms."
  [svc {:keys [ente vereadores]} {:keys [sid vid]}]
  (let [largada (CountDownLatch. 1)
        url (str "/sessoes/" sid "/votacoes/" vid "/meu-voto")
        fs (vec (map-indexed
                 (fn [i {:keys [identidade]}]
                   (future
                     (.await largada)
                     (let [t (System/nanoTime)
                           r (pt/response-for svc :post url
                                              :body (json/write-value-as-string {:voto (opcoes (mod i 3))})
                                              :headers (como ente identidade))]
                       {:status (:status r) :corpo (when (not= 201 (:status r)) (:body r))
                        :ms (/ (- (System/nanoTime) t) 1e6)})))
                 vereadores))]
    (Thread/sleep 300)                      ; todas as threads paradas na largada
    (let [t0 (System/nanoTime)]
      (.countDown largada)
      (let [rs (mapv #(deref % 60000 {:status :timeout :ms 60000.0}) fs)]
        {:status (mapv :status rs)
         :recusados (vec (distinct (keep :corpo rs)))
         :parede-ms (/ (- (System/nanoTime) t0) 1e6)
         :latencias (mapv :ms rs)}))))

(defn- corrente-desde
  "A corrente crua da Casa depois do `seq` dado — o que esta' no banco, sem o filtro da tela."
  [ente seq-antes]
  (tenancy/com-tenant* (ds) ente
    #(mapv (fn [l] (update l :detalhe comum/jsonb->kw))
           (jdbc/execute! % ["SELECT seq, acao, classe, decisao, status_http, detalhe FROM auditoria.registro
                              WHERE ente_id = ? AND seq > ? ORDER BY seq" ente seq-antes]
                          {:builder-fn rs/as-unqualified-lower-maps}))))

(defn- ultimo-seq [ente]
  (tenancy/com-tenant* (ds) ente
    #(or (:s (jdbc/execute-one! % ["SELECT max(seq) AS s FROM auditoria.registro WHERE ente_id = ?" ente])) 0)))

(defn- pendentes-no-outbox
  "Como dono do banco (o role de tenant nao le' o outbox): so' conta, nao muda nada."
  [ente]
  (:n (jdbc/execute-one! (ds) ["SELECT count(*) AS n FROM shared.outbox WHERE ente_id = ? AND processed_at IS NULL" ente])))

(defn- esperar-o-relay!
  "Antes de cada disparo, o relay (de pe', como em producao) termina o que a preparacao e o disparo anterior deixaram no
  outbox — a janela medida nao divide o banco com o trabalho de preparar. Devolve o que ainda sobrou (0 = drenou)."
  [& casas]
  (let [limite (+ (System/currentTimeMillis) 8000)]
    (loop []
      (let [n (reduce + (map (comp pendentes-no-outbox :ente) casas))]
        (if (or (zero? n) (> (System/currentTimeMillis) limite))
          n
          (do (Thread/sleep 100) (recur)))))))

;; ---------- as asserções de correcao ----------

(defn- conferir-correcao!
  "21 votos gravados (um por vereador), todos 201, e a corrente da rodada: COM a tentativa, 21 pares tentativa+desfecho
  (cada desfecho aponta uma tentativa que existe, nenhuma apontada duas vezes); SEM, 21 desfechos sem apontamento."
  [com? casa {:keys [vid]} seq-antes r]
  (let [{:keys [ente vereadores]} casa
        rotulo (if com? "com" "sem")]
    (testing (str rotulo ": os 21 responderam 201 (nenhum 4xx/5xx, deadlock ou timeout sob concorrencia)")
      (is (= (repeat vereadores-por-casa 201) (:status r)) (str "corpos recusados: " (:recusados r))))
    (testing (str rotulo ": 21 votos gravados, um por vereador")
      (let [votos (repo-leg/votos-da-votacao (:repo-legislativo *sys*) ente vid)]
        (is (= vereadores-por-casa (count votos)))
        (is (= (set (map :vereador-id vereadores)) (set (map :vereador-id votos))))))
    (testing (str rotulo ": a corrente da rodada")
      (let [c (corrente-desde ente seq-antes)
            tentativas (set (map :seq (filter #(= "iniciado" (:decisao %)) c)))
            desfechos (remove #(= "iniciado" (:decisao %)) c)
            apontadas (keep (comp :tentativa :detalhe) desfechos)]
        (is (every? #(= "legislativo/meu-voto" (:acao %)) c))
        (is (= vereadores-por-casa (count desfechos)))
        (is (every? #(and (= "permitido" (:decisao %)) (= 201 (:status_http %))) desfechos))
        (if com?
          (do (is (= (* 2 vereadores-por-casa) (count c)))
              (is (= vereadores-por-casa (count tentativas)))
              (is (= tentativas (set apontadas)) "cada desfecho aponta uma tentativa que existe")
              (is (= vereadores-por-casa (count apontadas) (count (distinct apontadas))) "e nenhuma e' apontada duas vezes"))
          (do (is (= vereadores-por-casa (count c)))
              (is (empty? tentativas))
              (is (empty? apontadas))))))))

;; ---------- o resumo ----------

(defn- quantil [v q]
  (let [s (vec (sort v)) n (count s)]
    (nth s (min (dec n) (int (Math/ceil (dec (* q n))))))))

(defn- mediana [v] (let [s (vec (sort v)) n (count s)]
                     (if (odd? n) (nth s (quot n 2)) (/ (+ (nth s (dec (quot n 2))) (nth s (quot n 2))) 2.0))))

(defn- resumo [rotulo medidas]
  (let [lat (mapcat :latencias medidas) paredes (map :parede-ms medidas)]
    (println (format "CUSTO-NO-VOTO resumo %-3s | parede por rodada (ms): mediana %.0f, min %.0f, max %.0f | latencia por requisicao (ms), %d reqs: mediana %.0f, p95 %.0f, max %.0f"
                     rotulo (mediana paredes) (apply min paredes) (apply max paredes)
                     (count lat) (mediana lat) (quantil lat 0.95) (apply max lat)))))

(deftest vinte-e-um-vereadores-votando-ao-mesmo-tempo-com-e-sem-a-tentativa
  (let [svc {:com (servico true) :sem (servico false)}
        casas {:com (semear-casa!) :sem (semear-casa!)}
        pool (get-in (config/carregar) [:db :pool-max-size])
        medidas (atom {:com [] :sem []})]
    (println (format "CUSTO-NO-VOTO config | %d vereadores, %d rodadas medidas + %d de aquecimento, pool de trabalho %s, processadores %d"
                     vereadores-por-casa rodadas aquecimento (or pool 10) (.availableProcessors (Runtime/getRuntime))))
    (doseq [i (range (+ aquecimento rodadas))]
      (let [medir? (>= i aquecimento)
            ordem (if (even? i) [:sem :com] [:com :sem])
            preparos (into {} (for [c ordem] [c (preparar-votacao! (casas c))]))]
        (doseq [c ordem]
          (let [sobrou (esperar-o-relay! (:com casas) (:sem casas))
                seq-antes (ultimo-seq (:ente (casas c)))
                r (disparar! (svc c) (casas c) (preparos c))]
            (conferir-correcao! (= c :com) (casas c) (preparos c) seq-antes r)
            (when medir?
              (swap! medidas update c conj r)
              (println (format "CUSTO-NO-VOTO rodada %2d | %s | parede %6.0f ms | latencia por requisicao: mediana %5.0f, p95 %5.0f, max %5.0f ms | outbox pendente na largada: %d"
                               (- i aquecimento -1) (name c) (:parede-ms r)
                               (mediana (:latencias r)) (quantil (:latencias r) 0.95) (apply max (:latencias r)) sobrou)))))))
    (resumo "sem" (:sem @medidas))
    (resumo "com" (:com @medidas))
    (testing "a corrente selada de cada Casa confere inteira, sem tentativa orfa"
      (doseq [c [:com :sem]
              :let [ente (:ente (casas c))
                    v (repo-aud/verificar (:repo-auditoria *sys*) ente)]]
        (is (:integra v) (str (name c) ": " (pr-str v)))
        (is (= (* (+ aquecimento rodadas) vereadores-por-casa (if (= c :com) 2 1)) (:total v)))
        (is (= {:total 0 :primeiro nil} (repo-aud/sem-desfecho (assoc (:repo-auditoria *sys*) :tolerancia-s 0) ente)))))))
