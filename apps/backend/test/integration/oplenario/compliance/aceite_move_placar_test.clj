(ns oplenario.compliance.aceite-move-placar-test
  "INTEGRACAO (PG + MinIO real + a cadeia HTTP de `rotas/montar`): o achado critico do exploratorio de 12/09 —
  'aceitar a remessa pela rota HTTP nao move o placar de compliance'. A jornada da servidora aceitou a remessa de
  2026-09 por `POST /compliance/remessas/:id/resposta` e o card 'saude institucional' seguiu dizendo que a obrigacao
  estava pendente.

  O CAMINHO E' O DE PRODUCAO, nao o da demo: a remessa nasce em `gerar-remessa!` (renderiza -> serializa -> hash ->
  objeto_store -> rascunho) e percorre validar -> submeter -> resposta 'aceita' PELAS ROTAS, como a secretaria faz. Nenhum
  `UPDATE` de estado, e NENHUMA chamada a `avaliar-obrigacao!` entre o aceite e a leitura: a pergunta e' se o sistema,
  sozinho, move a obrigacao quando o TCE aceita a remessa que a cumpre. A semente da demo (`demo/compliance.clj`) so'
  prepara o catalogo e as obrigacoes de uma Casa nova (ente aleatorio: cada corrida parte do mesmo estado); a obrigacao
  de 2026-08 nasce PENDENTE (avaliada em 01/09, data do evento, nao em `hoje`) porque a semente nao lhe da remessa aceita.

  Uma regra do motor nao se avalia sozinha: quem a avalia e' o `gatilho_compliance` do host, e as rotas que o disparam
  estao em `rotas.clj` (secao 'ADR-0021 fatia 3'). O teste final prova a causa (a avaliacao explicita, na data do evento,
  cumpre) para que um vermelho aqui nao seja lido como 'o fato `remessa_enviada` nao enxerga a remessa aceita'."
  (:require [clojure.test :refer [deftest is testing]]
            [compliance :as compliance-demo]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.compliance.components.fontes :as fontes]
            [oplenario.compliance.components.repositorio :as repo-compliance]
            [oplenario.compliance.components.serializador-remessa :as ser]
            [oplenario.compliance.gerador-remessa :as ger]
            [oplenario.config :as config]
            [oplenario.demo.casa-test :refer [with-sistema]]
            [oplenario.gatilho-compliance :as gatilho]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.motor.components.repositorio :as repo-motor]
            [oplenario.motor.nucleo :as nuc]
            [oplenario.rotas :as rotas]
            [next.jdbc :as jdbc])
  (:import (java.nio.charset StandardCharsets)
           (java.time Instant LocalDate)
           (java.util UUID)))

;; 12/09/2026, o mesmo `hoje` do teste da semente: 01..06/2026 cumpridas, 07/2026 vencida pelo sweep, 08/2026
;; (vence em 30/09) pendente.
(def ^:private hoje (LocalDate/of 2026 9 12))
(def ^:private agora (Instant/parse "2026-09-12T15:00:00Z"))
(def ^:private competencia "2026-08")

(defn- fake-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente _id] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis #{"secretario"}})))

(defn- motor-quebrado
  "O catalogo do motor fora do ar: a reavaliacao do gatilho falha, o aceite ja' gravado nao pode sofrer."
  []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-motor/RepoMotor
    (template-vigente [_ _] (throw (ex-info "catalogo do motor fora do ar" {})))))

(defn- compliance-que-nao-lista-as-aceitas
  "O compliance real, menos a leitura das remessas aceitas que o gatilho faz: ela lanca (o gatilho INTEIRO falha, fora
  do isolamento por competencia). Protocolo nao se redefine com `with-redefs` (a chamada vai direto na interface)."
  [real]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-compliance/RepoCompliance
    (registrar-resposta-remessa! [_ ente id estado] (repo-compliance/registrar-resposta-remessa! real ente id estado))
    (remessas-aceitas [_ _ _] (throw (ex-info "banco fora do ar" {})))))

(defn- servico
  "A cadeia HTTP de producao. `agora` = o relogio do host (o `hoje` do gatilho); `motor` e `compliance` trocam os repos."
  ([s] (servico s agora (:repo-motor s) (:repo-compliance s)))
  ([s agora motor] (servico s agora motor (:repo-compliance s)))
  ([s agora motor compliance]
   (-> (http/servico (config/carregar)
                     (rotas/montar {:idp (idp-dev/idp-dev)
                                    :repo-identidade (fake-identidade)
                                    :repo-compliance compliance
                                    :repo-motor motor
                                    :registro-fatos (:registro-fatos s)
                                    :repo-legislativo (:repo-legislativo s)
                                    :relogio (tempo/relogio-fixo agora)})
                     it/globais)
       ph/create-server ::ph/service-fn)))

(defn- chamar [svc ente metodo url corpo]
  (let [tok (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str (random-uuid))})
        ;; Content-Type so' com corpo: JSON declarado e corpo vazio e' 400 antes de o handler rodar
        r (apply pt/response-for svc metodo url
                 :headers (cond-> {"authorization" (str "Bearer " tok)} corpo (assoc "Content-Type" "application/json"))
                 (when corpo [:body (json/write-value-as-string corpo)]))]
    {:status (:status r)
     :corpo (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper))}))

(defn- objeto-id
  "O id do objeto sob prazo, DERIVADO como em `demo/compliance.clj` (a competencia nao e' entidade de dominio)."
  ^UUID [ente chave]
  (UUID/nameUUIDFromBytes (.getBytes (str ente "|SIM|" chave) StandardCharsets/UTF_8)))

(defn- aceitar-fora-do-gatilho!
  "O aceite gravado ANTES do conserto (ou por qualquer caminho que nao seja a rota): so' a transicao da remessa."
  [s ente rid]
  (repo-compliance/validar-remessa! (:repo-compliance s) ente rid)
  (repo-compliance/submeter-remessa! (:repo-compliance s) ente rid)
  (repo-compliance/registrar-resposta-remessa! (:repo-compliance s) ente rid "aceita"))

(defn- envelhecer-avaliacao!
  "As bases existentes: a obrigacao foi avaliada ha' mais de uma hora (so' o carimbo; o estado nao se mexe)."
  [s ente]
  (tenancy/com-tenant* (get-in s [:datasource :ds]) ente
    (fn [tx]
      (jdbc/execute-one! tx ["UPDATE compliance.prazo_dominio_ativo SET atualizado_em = now() - interval '1 hour' WHERE ente_id = ?" ente]))))

(defn- gerar-remessa!
  "A remessa da competencia pelo caminho de producao (`gerar-remessa!`), com os mesmos argumentos da semente."
  [s ente]
  (repo-compliance/gerar-remessa! (:repo-compliance s) ente
    {:descritor ger/descritor-sim-fixture
     :template-chave compliance-demo/template-chave :sistema "SIM" :competencia competencia
     :contexto {"competencia" competencia "sistema" "SIM"}
     :resolver-relacao (fn [_tx nome] (get {"nome_ente" "Camara Municipal de Fortaleza"} nome))
     :fontes (fontes/fontes-fixture {"despesas" [{"data" (str competencia "-05") "valor" "128430,77"}]})
     :serializador (ser/serializador-sim)
     :objeto-store (:objeto-store s)
     :registry-versao-ref compliance-demo/registry-versao-ref}))

(defn- obrigacao [s ente]
  (first (repo-compliance/obrigacoes-do-objeto (:repo-compliance s) ente "competencia" (objeto-id ente competencia))))

(defn- resumo [s ente]
  (into {} (map (juxt :estado :total))
        (:resumo (repo-compliance/painel (:repo-compliance s) ente {:limite-em-aberto 100 :limite-remessas 20}))))

(deftest aceitar-a-remessa-pela-rota-move-o-placar
  (with-sistema [s]
    (let [ente (random-uuid)
          _ (compliance-demo/semear! s ente hoje)
          antes (obrigacao s ente)
          placar-antes (resumo s ente)
          svc (servico s)
          {rid :id} (gerar-remessa! s ente)]
      (testing "ponto de partida: a obrigacao de 2026-08 existe, pendente, e o placar a conta como pendente"
        (is (= "pendente" (:estado antes)))
        (is (= 1 (get placar-antes "pendente" 0)))
        (is (= 6 (get placar-antes "cumprida" 0))))

      (testing "o ciclo da remessa pelas rotas: validar -> submeter -> aceita (o que a secretaria faz)"
        (is (= 200 (:status (chamar svc ente :post (str "/compliance/remessas/" rid "/validar") nil))))
        (is (= 200 (:status (chamar svc ente :post (str "/compliance/remessas/" rid "/submeter") nil))))
        (let [r (chamar svc ente :post (str "/compliance/remessas/" rid "/resposta") {:estado "aceita"})]
          (is (= 200 (:status r)))
          (is (= "aceita" (:estado (:corpo r))))))

      (testing "a remessa aceita cumpre a obrigacao: ela sai de pendente e o placar acompanha"
        (let [depois (obrigacao s ente)
              placar-depois (resumo s ente)]
          (is (= "cumprida" (:estado depois))
              "a remessa que cumpre a obrigacao foi ACEITA pelo TCE; a obrigacao nao pode seguir pendente (e, a 30/09, vencer)")
          (is (= 0 (get placar-depois "pendente" 0)))
          (is (= 7 (get placar-depois "cumprida" 0))))))))

(deftest o-fato-remessa-enviada-enxerga-a-remessa-aceita
  ;; A CAUSA, separada do sintoma: depois do aceite, a avaliacao explicita na data do evento (01/09, o dia em que a
  ;; competencia 2026-08 fecha — nao `hoje`) cumpre a obrigacao. Se este teste passa e o de cima nao, falta o disparo da
  ;; avaliacao no aceite; o fato e a regra estao certos.
  (with-sistema [s]
    (let [ente (random-uuid)
          _ (compliance-demo/semear! s ente hoje)
          svc (servico s)
          {rid :id} (gerar-remessa! s ente)]
      (doseq [passo ["validar" "submeter"]]
        (chamar svc ente :post (str "/compliance/remessas/" rid "/" passo) nil))
      (chamar svc ente :post (str "/compliance/remessas/" rid "/resposta") {:estado "aceita"})
      (let [r (repo-compliance/avaliar-obrigacao! (:repo-compliance s) ente (:registro-fatos s) (:repo-motor s)
                {:regra (nuc/carregar-envelope compliance-demo/fonte-yaml)
                 :reg-ver compliance-demo/registry-versao-ref
                 :objeto-tipo "competencia" :objeto-id (objeto-id ente competencia)
                 :amb {"competencia" {:ano 2026 :mes 8}}
                 :agora (LocalDate/of 2026 9 1) :origem "evento"})]
        (is (= "conforme" (:veredito (:avaliacao r))))
        (is (= "cumprida" (:estado (:obrigacao r))))))))

(deftest remessa-aceita-nao-vira-vencida-no-sweep
  ;; Consequencia: o sweep do gatilho (varre TODA obrigacao pendente da Casa a cada leitura do painel) so' olha estado e
  ;; vencimento. Sem reavaliacao no aceite, a remessa ACEITA a tempo vira 'vencida' depois de 30/09.
  (with-sistema [s]
    (let [ente (random-uuid)
          _ (compliance-demo/semear! s ente hoje)
          svc (servico s)
          {rid :id} (gerar-remessa! s ente)]
      (doseq [passo ["validar" "submeter"]]
        (chamar svc ente :post (str "/compliance/remessas/" rid "/" passo) nil))
      (chamar svc ente :post (str "/compliance/remessas/" rid "/resposta") {:estado "aceita"})
      (repo-compliance/varrer-vencimentos! (:repo-compliance s) ente (LocalDate/of 2026 10 1))
      (is (not= "vencida" (:estado (obrigacao s ente)))
          "remessa aceita a tempo nao pode virar vencida"))))

(deftest o-aceite-reavalia-como-evento-e-registra-na-prova
  ;; o aceite reavalia pelo MESMO caminho do gatilho: a avaliacao nova entra na prova append-only, origem `evento`
  (with-sistema [s]
    (let [ente (random-uuid)
          _ (compliance-demo/semear! s ente hoje)
          svc (servico s)
          {rid :id} (gerar-remessa! s ente)
          antes (count (repo-compliance/avaliacoes-da-obrigacao (:repo-compliance s) ente (:id (obrigacao s ente))))]
      (doseq [passo ["validar" "submeter"]]
        (chamar svc ente :post (str "/compliance/remessas/" rid "/" passo) nil))
      (chamar svc ente :post (str "/compliance/remessas/" rid "/resposta") {:estado "aceita"})
      (let [avs (repo-compliance/avaliacoes-da-obrigacao (:repo-compliance s) ente (:id (obrigacao s ente)))
            ultima (last avs)]                       ; em ordem cronologica
        (is (= (inc antes) (count avs)) "uma avaliacao nova, e so' uma")
        (is (= "conforme" (:veredito ultima)))
        (is (= "evento" (:origem-avaliacao ultima)))))))

(deftest resposta-rejeitada-nao-cumpre-e-nao-mexe-na-obrigacao
  (with-sistema [s]
    (let [ente (random-uuid)
          _ (compliance-demo/semear! s ente hoje)
          svc (servico s)
          {rid :id} (gerar-remessa! s ente)
          antes (obrigacao s ente)
          avals-antes (count (repo-compliance/avaliacoes-da-obrigacao (:repo-compliance s) ente (:id antes)))]
      (doseq [passo ["validar" "submeter"]]
        (chamar svc ente :post (str "/compliance/remessas/" rid "/" passo) nil))
      (let [r (chamar svc ente :post (str "/compliance/remessas/" rid "/resposta") {:estado "rejeitada"})]
        (is (= 200 (:status r)))
        (is (= "rejeitada" (:estado (:corpo r)))))
      (let [depois (obrigacao s ente)]
        (is (= "pendente" (:estado depois)) "a remessa rejeitada nao cumpre: a obrigacao segue como estava")
        (is (= (:atualizado-em antes) (:atualizado-em depois)) "e nem foi reavaliada")
        (is (= avals-antes (count (repo-compliance/avaliacoes-da-obrigacao (:repo-compliance s) ente (:id depois))))
            "nenhuma avaliacao nova na prova"))
      (is (= 1 (get (resumo s ente) "pendente" 0))))))

(deftest falha-na-reavaliacao-nao-desfaz-nem-mascara-o-aceite
  ;; (a) catalogo do motor fora do ar: a reavaliacao falha DENTRO do gatilho (isolada por competencia, logada)
  ;; (b) o gatilho inteiro lanca (o repo de compliance falha ao listar): `disparar-sem-falhar!` loga e segue
  (doseq [quebrar [:motor :gatilho]]
    (with-sistema [s]
      (let [ente (random-uuid)
            _ (compliance-demo/semear! s ente hoje)
            svc (if (= :motor quebrar)
                  (servico s agora (motor-quebrado))
                  (servico s agora (:repo-motor s) (compliance-que-nao-lista-as-aceitas (:repo-compliance s))))
            ;; o ciclo ate' submetida pelo servico sadio (o `quebrado` so' implementa a resposta)
            sadio (servico s)
            {rid :id} (gerar-remessa! s ente)]
        (doseq [passo ["validar" "submeter"]]
          (chamar sadio ente :post (str "/compliance/remessas/" rid "/" passo) nil))
        (let [r (chamar svc ente :post (str "/compliance/remessas/" rid "/resposta") {:estado "aceita"})]
          (is (= 200 (:status r)) (str quebrar ": a resposta do aceite segue 200, nao 500"))
          (is (= "aceita" (:estado (:corpo r))) (str quebrar ": e diz a verdade, a remessa foi aceita")))
        (is (= ["aceita"] (mapv :estado (repo-compliance/listar-remessas (:repo-compliance s) ente
                                                                         compliance-demo/template-chave competencia)))
            (str quebrar ": o aceite gravado esta la'"))
        (is (= "pendente" (:estado (obrigacao s ente)))
            (str quebrar ": a obrigacao fica como estava (a proxima leitura do painel a acerta)"))))))

(deftest o-legado-aceite-gravado-antes-do-conserto-e-acertado-pela-leitura-do-painel
  ;; Bases existentes: a remessa foi aceita SEM disparo e a obrigacao ficou `pendente`. O sweep a venceria depois de
  ;; 30/09; o gatilho reavalia (caminho de producao) ANTES do sweep, na leitura do painel — nao ha' UPDATE de estado.
  (with-sistema [s]
    (let [ente (random-uuid)
          _ (compliance-demo/semear! s ente hoje)
          {rid :id} (gerar-remessa! s ente)
          _ (aceitar-fora-do-gatilho! s ente rid)
          _ (envelhecer-avaliacao! s ente)
          svc (servico s (Instant/parse "2026-10-01T15:00:00Z") (:repo-motor s))]
      (is (= "pendente" (:estado (obrigacao s ente))) "o legado: remessa aceita e obrigacao pendente")
      (is (= 200 (:status (chamar svc ente :get "/compliance/painel" nil))))
      (is (= "cumprida" (:estado (obrigacao s ente)))
          "a leitura do painel reavalia a obrigacao da remessa aceita ANTES do sweep: nao vira vencida")
      (is (= 0 (get (resumo s ente) "pendente" 0))))))

(deftest o-legado-ja-vencido-pelo-sweep-vira-cumprido-tardio
  ;; O sweep ja' rodou sobre a base existente e venceu a obrigacao da remessa aceita. A reavaliacao pelo gatilho a leva a
  ;; `cumprida` (cumprimento tardio de `proxima-fase`): o fato esta' satisfeito, `vencida` seria falso.
  (with-sistema [s]
    (let [ente (random-uuid)
          _ (compliance-demo/semear! s ente hoje)
          {rid :id} (gerar-remessa! s ente)
          _ (aceitar-fora-do-gatilho! s ente rid)
          _ (repo-compliance/varrer-vencimentos! (:repo-compliance s) ente (LocalDate/of 2026 10 1))
          _ (envelhecer-avaliacao! s ente)
          svc (servico s (Instant/parse "2026-10-01T15:00:00Z") (:repo-motor s))]
      (is (= "vencida" (:estado (obrigacao s ente))) "o legado: o sweep ja' a venceu")
      (chamar svc ente :get "/compliance/painel" nil)
      (is (= "cumprida" (:estado (obrigacao s ente)))))))

(deftest o-gatilho-so-reavalia-nao-cria-obrigacao
  ;; a Casa que tem remessa aceita mas nunca teve a regra da remessa (a producao de hoje): nada e' materializado
  (with-sistema [s]
    (let [ente (random-uuid)
          _ (compliance-demo/semear! s ente hoje)       ; so' para o catalogo existir; `outro` nao tem obrigacao
          outro (random-uuid)
          svc (servico s)
          {rid :id} (gerar-remessa! s outro)]
      (doseq [passo ["validar" "submeter"]]
        (chamar svc outro :post (str "/compliance/remessas/" rid "/" passo) nil))
      (is (= 200 (:status (chamar svc outro :post (str "/compliance/remessas/" rid "/resposta") {:estado "aceita"}))))
      (is (nil? (obrigacao s outro)) "sem a regra da remessa na Casa, aceitar nao inventa obrigacao"))))

(deftest o-objeto-da-remessa-e-uma-derivacao-so
  (let [ente (random-uuid)]
    (is (= (objeto-id ente competencia) (gatilho/objeto-da-remessa ente "SIM" competencia))
        "a derivacao do teste (e da semente) e a do gatilho")
    (is (not= (gatilho/objeto-da-remessa ente "SIM" "2026-08") (gatilho/objeto-da-remessa ente "SIM" "2026-09")))))
