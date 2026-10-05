(ns oplenario.auditoria.resumo-de-toda-escrita-test
  "ADR-0017 (Eixo 1-C) — a garantia e' ESTRUTURAL: toda rota de ESCRITA da tabela que o host monta (`rotas/montar`, a
  mesma do `toda-escrita-tem-tentativa-test`) tem um resumo em palavras em `auditoria.resumos/por-acao`, ou um MOTIVO
  nomeado em `auditoria.resumos/sem-resumo`. Escrita nova sem uma das duas reprova aqui, com o nome da rota. Sem o
  resumo, `/auditoria` e o CSV mostram a rota crua.

  E a logica pura: o rotulo da acao so' entra no ato que aconteceu, e o do handler (o do objeto) sempre vence."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oplenario.auditoria.logic :as logic]
            [oplenario.auditoria.resumos :as resumos]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas]))

(def ^:private escrita #{:post :put :patch :delete})

(defn- nome [r] (let [v (vec r) i (.indexOf v :route-name)] (if (>= i 0) (nth v (inc i)) (first r))))

(def ^:private rotas-do-host
  ;; as mesmas chaves do `toda-escrita-tem-tentativa-test`: ligam os fragmentos que so' existem com o Repo presente
  (delay (rotas/montar {:idp (idp-dev/idp-dev) :repo-integracao-ia :lint :repo-auditoria :lint})))

(defn- acoes-de-escrita [rotas] (into (sorted-set-by #(compare (str %1) (str %2)))
                                      (comp (filter #(escrita (second %))) (map nome))
                                      rotas))

(defn- sem-resumo-nem-motivo
  "As acoes de escrita que nao estao em nenhuma das duas tabelas."
  [acoes por-acao sem-resumo]
  (remove #(or (contains? por-acao %) (contains? sem-resumo %)) acoes))

(deftest toda-rota-de-escrita-do-host-tem-resumo-ou-motivo
  (let [acoes (acoes-de-escrita @rotas-do-host)
        faltam (sem-resumo-nem-motivo acoes resumos/por-acao resumos/sem-resumo)]
    (is (> (count acoes) 100) (str "o teste viu a tabela inteira do host: " (count acoes) " rotas de escrita"))
    (is (empty? faltam)
        (str "rota de escrita SEM resumo na trilha (ADR-0017 1-C): " (vec faltam)
             " — de a ela um rotulo em `oplenario.auditoria.resumos/por-acao` (verbo no passado, vocabulario do servidor"
             " da Casa, sem rota/enum/UUID/conteudo) ou, se o ato nao entra na corrente da Casa, um motivo em `sem-resumo`"))))

(deftest nenhuma-entrada-sobrando
  (testing "entrada que nao e' rota de escrita do host: a rota foi renomeada ou removida e o rotulo ficou para tras"
    (let [acoes (acoes-de-escrita @rotas-do-host)]
      (is (empty? (set/difference (set (keys resumos/por-acao)) acoes))
          (str "rotulos de rota que nao existe mais: " (vec (set/difference (set (keys resumos/por-acao)) acoes))))
      (is (empty? (set/difference (set (keys resumos/sem-resumo)) acoes))
          (str "excecoes de rota que nao existe mais: " (vec (set/difference (set (keys resumos/sem-resumo)) acoes)))))))

(deftest a-acao-esta-numa-tabela-so
  (is (empty? (set/intersection (set (keys resumos/por-acao)) (set (keys resumos/sem-resumo))))
      "rota com rotulo e com motivo de nao ter rotulo ao mesmo tempo"))

(deftest o-motivo-de-excecao-e-uma-frase
  (doseq [[acao motivo] resumos/sem-resumo]
    (is (and (string? motivo) (>= (count (str/trim motivo)) 30)) (str acao ": a excecao precisa dizer por que"))))

(deftest o-rotulo-e-palavra-de-servidor-de-camara
  (doseq [[acao rotulo] resumos/por-acao]
    (is (nil? (resumos/rotulo-fraco rotulo)) (str acao " -> " (pr-str rotulo) ": " (resumos/rotulo-fraco rotulo)))))

(deftest cada-rotulo-diz-uma-coisa-so
  (let [repetidos (->> (vals resumos/por-acao) frequencies (filter #(> (val %) 1)) (map key))]
    (is (empty? repetidos) (str "rotulos iguais para acoes diferentes: " (vec repetidos)))))

;; ---- o detector tem dentes ----

(deftest o-detector-tem-dentes
  (let [h (fn [_] {:status 200})
        rotas #{["/coisa-nova/:id" :post [h] :route-name :modulo/coisa-nova]
                ["/outra" :delete h :route-name :modulo/outra]
                ["/ler" :get [h] :route-name :modulo/ler]}
        acoes (acoes-de-escrita rotas)]
    (testing "so' escrita entra na conta; a leitura nao precisa de resumo"
      (is (= [:modulo/coisa-nova :modulo/outra] (vec acoes))))
    (testing "rota de escrita sem rotulo e sem motivo e' acusada pelo nome"
      (is (= [:modulo/coisa-nova :modulo/outra] (vec (sem-resumo-nem-motivo acoes {} {}))))
      (is (= [:modulo/outra] (vec (sem-resumo-nem-motivo acoes {:modulo/coisa-nova "Criou uma coisa"} {}))))
      (is (= [] (vec (sem-resumo-nem-motivo acoes {:modulo/coisa-nova "Criou uma coisa"} {:modulo/outra "motivo"})))))
    (testing "tirar uma entrada real da tabela faz a acao voltar a ser acusada"
      (let [reais (acoes-de-escrita @rotas-do-host)]
        (is (= [:sessoes/publicar-pauta]
               (vec (sem-resumo-nem-motivo reais (dissoc resumos/por-acao :sessoes/publicar-pauta) resumos/sem-resumo))))))))

(deftest o-rotulo-fraco-tem-dentes
  (doseq [ruim ["legislativo/publicar-pauta" "publicar_pauta" "publicou a pauta" "" "  "
                "Publicou 30000000-0000-0000-0000-000000000003" "Publicou pauta(s)" "Publicou a pauta."
                "Publicar-pauta-da-sessao" "em_pauta" "Publicou: a pauta" nil]]
    (is (some? (resumos/rotulo-fraco ruim)) (pr-str ruim)))
  (doseq [bom ["Publicou a pauta da sessão" "Revogou um acesso à Casa" "Definiu o encarregado de dados (LGPD) da Casa"]]
    (is (nil? (resumos/rotulo-fraco bom)) bom)))

;; ---- a logica pura: o rotulo da acao so' no ato que aconteceu ----

(def ^:private ente (random-uuid))
(def ^:private maria (random-uuid))
(def ^:private secretaria {:ente-id ente :identidade-id maria :papeis #{"secretario"}})
(def ^:private sessao "20000000-0000-0000-0000-000000000002")

(defn- req [metodo] {:request-method metodo :ator secretaria :path-params {:sessao-id sessao} :headers {}})

(deftest o-rotulo-da-acao-entra-no-ato-feito
  (let [r (logic/registro-da-requisicao (req :post) {:status 201} :sessoes/publicar-pauta)]
    (is (= ["Publicou a pauta da sessão" "escrita" "permitido" "sessao" sessao]
           ((juxt :rotulo :classe :decisao :recurso-tipo :recurso-id) r))
        "o recurso continua vindo do caminho; o rotulo diz o que foi feito")
    (is (= [] (:campos r)) "nenhum campo inventado: so' o handler sabe quais campos mudaram")))

(deftest o-rotulo-do-handler-vence
  (let [r (logic/registro-da-requisicao (req :post)
                                        {:status 201 :auditoria {:rotulo "PAUTA-2026-7" :campos [:pauta]}}
                                        :sessoes/publicar-pauta)]
    (is (= ["PAUTA-2026-7" ["pauta"]] ((juxt :rotulo :campos) r)))))

(deftest negacao-falha-e-tentativa-nao-dizem-que-o-ato-foi-feito
  (testing "negacao (403), falha (4xx/5xx): sem rotulo da acao"
    (doseq [status [403 409 422 500]]
      (is (nil? (:rotulo (logic/registro-da-requisicao (req :post) {:status status} :sessoes/publicar-pauta)))
          (str status))))
  (testing "a tentativa (antes do handler, sem desfecho) tambem nao"
    (is (nil? (:rotulo (logic/registro-da-tentativa (req :post) :sessoes/publicar-pauta)))))
  (testing "mas o rotulo que o PROPRIO handler deu numa recusa (423, 'recusado: ...') e' mantido"
    (is (= "recusado: Casa com acesso restrito"
           (:rotulo (logic/registro-da-requisicao (req :post)
                                                  {:status 423 :auditoria {:rotulo "recusado: Casa com acesso restrito"}}
                                                  :sessoes/publicar-pauta))))))

(deftest so-escrita-e-entrada-ganham-o-rotulo-da-acao
  (testing "acao fora da tabela: sem rotulo, como antes"
    (is (nil? (:rotulo (logic/registro-da-requisicao (req :post) {:status 201} :modulo/nao-existe)))))
  (testing "leitura sensivel marcada pelo handler: o rotulo e' do handler, nunca o da tabela"
    (is (nil? (:rotulo (logic/registro-da-requisicao (req :get) {:status 200 :auditoria {:classe "leitura_sensivel"}}
                                                     :sessoes/publicar-pauta)))))
  (testing "a entrada (classe marcada pelo handler do mint) leva o rotulo da acao quando o handler nao deu um"
    (is (= "Entrou no sistema"
           (:rotulo (logic/registro-da-requisicao {:request-method :post :path-params {} :headers {}}
                                                  {:status 200 :auditoria {:classe "entrada" :ator secretaria}}
                                                  :identidade/mint-sessao))))))

(deftest o-rotulo-entra-no-selo-e-a-corrente-continua-conferindo
  (let [r (assoc (logic/registro-da-requisicao (req :post) {:status 201} :sessoes/publicar-pauta)
                 :seq 1 :id (random-uuid) :ocorrido-em (java.time.Instant/parse "2026-10-05T12:00:00Z"))
        selo (logic/selo-de "" r)
        c [(assoc r :selo-anterior "" :selo selo)]]
    (is (:integra (logic/verificar c)))
    (is (not (:integra (logic/verificar [(assoc (first c) :rotulo "Outro texto")])))
        "o rotulo e' selado: trocar o texto depois quebra a corrente")))
