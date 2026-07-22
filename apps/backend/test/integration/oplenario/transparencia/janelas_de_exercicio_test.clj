(ns oplenario.transparencia.janelas-de-exercicio-test
  "INTEGRACAO (PG real) — o SEAM do host `rotas/ficha-e-janelas-publicas` (I-5 fatia 4), que a rota PUBLICA
  do perfil do vereador usa para obter, DE UMA VEZ, a identidade (a ficha que ja' era o guard de 404) e a
  JANELA DE EXERCICIO do mandato (o recorte do denominador de presenca, fatia 6).

  Vive sob `transparencia/` porque e' o consumidor da fatia — mas o codigo sob teste e' do HOST
  (`oplenario.rotas`), que e' a UNICA raiz de composicao autorizada a cruzar modulos (§22.10). `transparencia`
  nunca importa `cadastros`; recebe a fn ja' resolvida. Precedente de forma: `legislativo/meu_painel_test`,
  que testa `rotas/resolver-vereador` do mesmo jeito.

  O que este ns cobre: o seam contra Postgres real (com RLS), incluindo o ex-vereador (cujo `:mandato`
  vigente e' nil e cuja janela so' existe porque a origem sao TODOS os stints) e o cross-tenant. A
  aritmetica da janela em si e' unit e vive em `oplenario.rotas-janelas-test`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas]
            [oplenario.transparencia.components.repositorio :as repo-transparencia])
  (:import (java.time LocalDate)))

(def ^:dynamic *repo* nil)
(def ^:dynamic *repo-transparencia* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo-cadastros/->RepoCadastrosPg c)
                *repo-transparencia* (repo-transparencia/->RepoTransparenciaPg c)]
        (try (t) (finally (component/stop c)))))))

(def ^:private hoje (LocalDate/of 2026 7 14))

(defn- semear-vereador!
  "Legislatura + vereador. `mandatos` sao INSERT direto (`criar-mandato!`): a fatia LE mandatos, e o guard
  app-level de sobreposicao de `registrar-mandato!` nao esta sob teste (stint fechado usa estado
  'concluido' — o EXCLUDE anti-overlap da mig 0059 so' incide sobre estado='vigente')."
  [ente ver-id mandatos]
  (let [leg-id (random-uuid)]
    (repo-cadastros/criar-legislatura! *repo* ente
      {:id leg-id :ente-id ente :numero 20 :ano-inicio 2021 :ano-fim 2028 :vigente true})
    (repo-cadastros/criar-vereador! *repo* ente
      {:id ver-id :ente-id ente :nome "Otavio Bandeira" :nome-parlamentar "Otavio B"})
    (doseq [m mandatos]
      (repo-cadastros/criar-mandato! *repo* ente
        (merge {:id (random-uuid) :ente-id ente :vereador-id ver-id :legislatura-id leg-id
                :partido "PDT" :estado "vigente" :natureza "titular" :vigencia-fim nil :fim-efetivo nil}
               m)))))

(deftest seam-le-ficha-e-janelas-numa-unica-transacao-de-cadastros
  ;; O espiao implementa SO' `ficha-e-mandatos-do-vereador` (delegando ao Repo real). Duas coisas ficam
  ;; provadas: (1) o seam chama a leitura COMPOSTA exatamente UMA vez — e ela e' uma tx so' (fatia 3);
  ;; (2) o seam NAO abre nenhuma outra leitura de cadastros — qualquer outro metodo do protocolo estoura
  ;; AbstractMethodError no espiao. Um segundo seam com BEGIN/SET LOCAL/COMMIT proprio (o que a decisao
  ;; recusou por custo, numa rota anonima sem cache) ficaria vermelho aqui.
  (let [ente (random-uuid)
        ver-id (random-uuid)
        man-id (random-uuid)
        chamadas (atom [])
        espiao #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify repo-cadastros/RepoCadastros
          (ficha-e-mandatos-do-vereador [_ e i d]
            (swap! chamadas conj [e i d])
            (repo-cadastros/ficha-e-mandatos-do-vereador *repo* e i d)))]
    (semear-vereador! ente ver-id [{:id man-id :vigencia-inicio (LocalDate/of 2025 1 1)}])
    (repo-cadastros/criar-licenca! *repo* ente
      {:id (random-uuid) :ente-id ente :mandato-id man-id
       :inicio (LocalDate/of 2025 6 1) :fim (LocalDate/of 2025 8 31) :motivo "saude"})
    (let [r (rotas/ficha-e-janelas-publicas espiao ente ver-id hoje)]
      (is (= [[ente ver-id hoje]] @chamadas)
          "UMA unica leitura composta, com o ente/vereador/data que a borda passou")
      (is (= #{:ficha :janelas} (set (keys r)))
          "o seam devolve as DUAS coisas — a janela nunca vem de uma segunda chamada")
      (is (= #{:vereador :mandato :legislatura :comissoes} (set (keys (:ficha r))))
          "`:ficha` continua com as MESMAS 4 chaves de `ficha-vereador` — e' o que `adapters/out/parlamentar`
           consome, e o guard de 404 da borda")
      (is (= [{:inicio (LocalDate/of 2025 1 1) :fim (LocalDate/of 2025 5 31)}
              {:inicio (LocalDate/of 2025 9 1) :fim nil}]
             (:janelas r))
          "e a licenca lida NA MESMA tx ja' sai subtraida da janela"))))

(deftest seam-de-vereador-de-outro-tenant-devolve-nil
  (let [ente (random-uuid)
        ver-id (random-uuid)]
    (semear-vereador! ente ver-id [{:vigencia-inicio (LocalDate/of 2025 1 1)}])
    (is (some? (rotas/ficha-e-janelas-publicas *repo* ente ver-id hoje))
        "sanidade: no proprio ente o seam responde")
    (is (nil? (rotas/ficha-e-janelas-publicas *repo* (random-uuid) ver-id hoje))
        "vereador real visto de OUTRA Casa e' nil — a borda publica devolve 404 e nunca 200 com janela
         vazia (200 insinuaria um parlamentar sem atuacao)")
    (is (nil? (rotas/ficha-e-janelas-publicas *repo* ente (random-uuid) hoje))
        "vereador inexistente tambem")))

(deftest seam-de-ex-vereador-devolve-ficha-sem-mandato-vigente-e-janela-historica
  (let [ente (random-uuid)
        ver-id (random-uuid)
        stint (random-uuid)]
    (semear-vereador! ente ver-id [{:id stint :estado "concluido"
                                    :vigencia-inicio (LocalDate/of 2021 1 1)
                                    :vigencia-fim (LocalDate/of 2024 12 31)}])
    (repo-cadastros/criar-licenca! *repo* ente
      {:id (random-uuid) :ente-id ente :mandato-id stint
       :inicio (LocalDate/of 2022 6 1) :fim (LocalDate/of 2022 12 31) :motivo "saude"})
    (let [r (rotas/ficha-e-janelas-publicas *repo* ente ver-id hoje)]
      (is (some? (:vereador (:ficha r))) "ex-vereador ainda existe como pessoa -> 200, nao 404")
      (is (nil? (:mandato (:ficha r))) "nenhum mandato cobre `hoje` — `mandato-vigente` e' nil")
      (is (= [{:inicio (LocalDate/of 2021 1 1) :fim (LocalDate/of 2022 5 31)}
              {:inicio (LocalDate/of 2023 1 1) :fim (LocalDate/of 2024 12 31)}]
             (:janelas r))
          "a janela HISTORICA existe mesmo sem mandato vigente — e' o detector de origem: quem 'simplificar'
           derivando a janela de `:mandato` (LIMIT 1 em hoje) devolve [] aqui e o ex-vereador vira 0/0"))))

;; ---------------------------------------------------------------------------
;; a closure de PRODUCAO do seam (revisao da fatia 4 — achado do revisor de mutacao)
;; ---------------------------------------------------------------------------

(defn- service-fn
  "Borda REAL montada SEM a chave `:ficha-e-janelas-publicas` — ou seja, caindo no ramo `or` de `montar`,
  a closure de PRODUCAO. Todos os demais ns que sobem esta rota (`http_perfil_test`) INJETAM o seam e
  portanto nunca a executam."
  []
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade nil
                                   :repo-cadastros *repo*
                                   :repo-transparencia *repo-transparencia*})
                    it/globais)
      ph/create-server ::ph/service-fn))

(deftest borda-publica-usa-a-closure-de-producao-do-seam-com-os-argumentos-na-ordem-certa
  ;; O par (a) ordem `repo ente-id vereador-id`, (b) aridade 2 exposta ao diplomat e (c) o desembrulho de
  ;; `:ficha` na linha que passa o seam a `transparencia-http/rotas` nao tinha NENHUM detector: inverter
  ;; `ente-id`/`vereador-id` ali deixava a suite inteira verde e devolvia 404 para 100% dos vereadores de
  ;; todas as Casas em producao (a tx abriria `com-tenant*` com o UUID do vereador e a RLS nao devolveria
  ;; linha). Nao ha e2e nem chamada de FE cobrindo esta rota — a tela e' Onda E e ainda nao existe.
  (let [ente (random-uuid)
        ver-id (random-uuid)
        ;; mandato EM ABERTO desde 2020: cobre `hoje` seja qual for a data em que a suite rodar (a closure
        ;; de producao le o relogio do sistema no fuso civil, nao a `hoje` cravada deste ns).
        _ (semear-vereador! ente ver-id [{:vigencia-inicio (LocalDate/of 2020 1 1)}])
        svc (service-fn)
        r (pt/response-for svc :get (str "/portal/casa/" ente "/vereadores/" ver-id))
        body (json/read-value (:body r) json/keyword-keys-object-mapper)]
    (is (= 200 (:status r))
        "vereador semeado NESTA Casa responde 200 pela closure de producao — se os argumentos estiverem
         trocados, a RLS nao acha linha e isto vira 404")
    (is (= "Otavio B" (:nome-parlamentar body))
        "e a identidade veio de `cadastros` pelo seam, nao de um fake")
    (is (= 404 (:status (pt/response-for svc :get (str "/portal/casa/" (random-uuid) "/vereadores/" ver-id))))
        "o MESMO vereador visto de outra Casa e' 404 — o escopo por ente atravessa a borda inteira")))
