(ns oplenario.auditoria.atos-fora-do-http-test
  "ADR-0017, adendo de 05/10/2026 — o INVENTARIO dos atos que a tentativa HTTP (`com-tentativa`) nao alcanca, como teste.

  Tres familias: (1) a ENTRADA (o mint da sessao), (2) os JOBS e comandos de `main.clj`, (3) os CONSUMIDORES do
  outbox. A (1) tem ato auditavel e foi instrumentada (`logic/acoes-de-entrada`). A (2) e a (3) foram medidas e hoje NAO
  tem ato que peca o par: o que cada ponto e', e por que, esta' em `classificacao-*` abaixo. Este teste nao instrumenta
  nada — ele impede que um ponto NOVO entre sem alguem decidir qual das duas coisas ele e':
    - projecao / efeito com registro proprio / corrente da Operacao  -> entra na allowlist com o MOTIVO;
    - ato auditavel da Casa sem registro proprio                      -> precisa do par (e este teste nao o aceita sem).
  Allowlist sem motivo, ou ponto novo fora da allowlist, reprova."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oplenario.auditoria.diplomat.http.in :as auditoria-http]
            [oplenario.auditoria.logic :as logic]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas]
            [oplenario.sistema :as sistema]
            [oplenario.tempo-real.components :as tr-comp])
  (:import (java.io File)))

;; ---------------------------------------------------------------------------------------------------------------------
;; leitura do codigo
;; ---------------------------------------------------------------------------------------------------------------------

(defn- fontes
  "Todo .clj de src/oplenario: [{:caminho \"modulo/arq.clj\" :texto ...}]."
  []
  (->> (file-seq (io/file "src/oplenario"))
       (filter #(.isFile ^File %))
       (filter #(str/ends-with? (.getName ^File %) ".clj"))
       (map (fn [^File f] {:caminho (str/replace (.getPath f) #"^src/oplenario/" "") :texto (slurp f)}))))

(defn- arquivos-com
  "Os caminhos (relativos a src/oplenario) cujo texto casa `re`."
  [re fs]
  (into (sorted-set) (comp (filter #(re-find re (:texto %))) (map :caminho)) fs))

(defn- sem-motivo [allowlist]
  (into (sorted-set) (comp (remove (fn [[_ v]] (not (str/blank? (:motivo v))))) (map key)) allowlist))

;; ---------------------------------------------------------------------------------------------------------------------
;; (1) a ENTRADA: toda criacao de sessao passa por um mint conhecido
;; ---------------------------------------------------------------------------------------------------------------------

(def ^:private chamada-de-criar-sessao
  ;; chamada qualificada `(repo/criar-sessao! ` ou `(repo/criar-sessao-operador! ` — a definicao no protocolo e as
  ;; mencoes em docstring nao casam (nao tem `ns/` colado ao parentese)
  #"\([\w.\-]+/criar-sessao(?:-operador)?!\s")

(def ^:private mints-de-sessao
  {"identidade/diplomat/http/auth_in.clj"
   {:corrente :casa
    :motivo "o mint da Casa: grava a TENTATIVA DA ENTRADA (`logic/acoes-de-entrada`) logo antes de criar a sessao"}
   "admin_sistema/diplomat/http/in.clj"
   {:corrente :operacao
    :motivo "o mint do OPERADOR: a entrada dele fica na atuacao da Operacao (ADR-0016), nunca na trilha da Casa"}})

(deftest toda-sessao-nasce-num-mint-conhecido
  (let [achados (arquivos-com chamada-de-criar-sessao (fontes))]
    (is (= (set (keys mints-de-sessao)) (set achados))
        (str "sessao criada FORA de um mint conhecido (ou um mint sumiu): " (vec achados)
             " — uma entrada nova na Casa precisa da tentativa da entrada (ADR-0017, adendo de 05/10/2026); a do "
             "operador vai para a atuacao. Depois, declare-a em `mints-de-sessao` com o motivo."))
    (is (empty? (sem-motivo mints-de-sessao)))))

(deftest a-entrada-da-casa-tem-a-tentativa-e-a-do-operador-nao-mistura-as-correntes
  (let [tabela @(delay (rotas/montar {:idp (idp-dev/idp-dev) :repo-integracao-ia :lint :repo-auditoria :lint}))
        por-nome (into {} (map (fn [r] [(let [v (vec r) i (.indexOf v :route-name)] (nth v (inc i))) r])) tabela)]
    (testing "a rota de entrada da Casa existe, e' POST e passa por `com-tentativa` (a tentativa dela e' a da entrada)"
      (is (= #{:identidade/mint-sessao} logic/acoes-de-entrada))
      (doseq [acao logic/acoes-de-entrada
              :let [[_ metodo cadeia] (por-nome acao)
                    c (if (vector? cadeia) cadeia [cadeia])]]
        (is (= :post metodo) (str acao))
        (is (identical? auditoria-http/tentativa (peek (pop c))) (str acao " sem o interceptor `tentativa`"))))
    (testing "o mint do operador existe e NAO e' acao de entrada da Casa (outra corrente)"
      (is (contains? por-nome :admin-sistema/mint-sessao))
      (is (not (contains? logic/acoes-de-entrada :admin-sistema/mint-sessao))))))

;; ---------------------------------------------------------------------------------------------------------------------
;; (2) JOBS: quem agenda ou roda trabalho fora de uma requisicao
;; ---------------------------------------------------------------------------------------------------------------------

(def ^:private cria-trabalho-fora-de-requisicao
  #"\(Thread\.\s|ThreadPoolExecutor\.|ScheduledExecutorService|scheduleAtFixedRate|scheduleWithFixedDelay|\(Timer\.|java\.util\.Timer|Executors/new")

(def ^:private classificacao-de-trabalho-em-segundo-plano
  {"main.clj"
   {:classe :infraestrutura
    :motivo "gancho de desligamento da JVM (component/stop); nao age sobre dado de Casa"}
   "kernel/components/outbox_relay.clj"
   {:classe :relay
    :motivo "o relay: so' despacha aos consumidores, que estao classificados em `classificacao-de-consumidores`"}
   "admin_sistema/components/exportacao.clj"
   {:classe :continuacao-de-ato-registrado
    :motivo (str "a exportacao completa roda depois de o pedido responder 202; o pedido e' o ato (rota do admin_ente na "
                 "trilha da Casa, rota do operador na atuacao) e a linha `exportacao` fecha em pronta ou falhou")}
   "sessoes/components/renderizador_pdf.clj"
   {:classe :continuacao-de-ato-registrado
    :motivo "o PDF da folha e' gerado dentro do pedido que congela a folha (a escrita ja' tem o par); so' limita o paralelismo"}})

(deftest nao-ha-agendador-nem-trabalho-novo-fora-de-requisicao-sem-classificar
  (let [achados (arquivos-com cria-trabalho-fora-de-requisicao (fontes))]
    (is (= (set (keys classificacao-de-trabalho-em-segundo-plano)) (set achados))
        (str "trabalho fora de requisicao novo (ou que sumiu): " (vec achados) ". Hoje NAO existe agendador de jobs "
             "em producao (docs de ADR-0017, adendo de 05/10/2026): um job que age sobre dado de uma Casa, em nome do "
             "sistema, precisa de tentativa/desfecho na trilha da Casa — ou de um motivo aqui para nao precisar."))
    (is (empty? (sem-motivo classificacao-de-trabalho-em-segundo-plano)))))

(def ^:private classificacao-dos-comandos-de-main
  {"migrate"
   {:classe :infraestrutura :motivo "aplica as migrations do banco; nao age sobre dado de uma Casa"}
   "ia-republicar-proposicoes"
   {:classe :comando-do-operador
    :motivo (str "o operador (linha de comando) reenvia ao feed da IA o que a Casa ja' tem: so' projecao, nenhuma linha "
                 "da Casa muda")}
   "ia-orcamento"
   {:classe :comando-do-operador
    :motivo (str "o operador define o orcamento de IA da Casa: ato do OPERADOR (corrente da Operacao, ADR-0016, nao a da "
                 "Casa); o efeito fica no `orcamento` append-only com `definido-por`")}
   "reconciliar-anexos"
   {:classe :comando-do-operador
    :motivo "relata; com --apagar-orfaos tira do STORAGE so' o blob sem linha com mais de 24 h (nao e' dado de Casa)"}
   "operador-convidar"
   {:classe :comando-do-operador :motivo "ciclo de vida do operador: ja' grava na atuacao (admin-sistema/controllers)"}
   "operador-desligar"
   {:classe :comando-do-operador :motivo "ciclo de vida do operador: ja' grava na atuacao (admin-sistema/controllers)"}})

(defn- comandos-de-main
  "Os subcomandos que `main.clj` despacha: `(= \"x\" (first args))` e `(#{\"a\" \"b\"} (first args))`."
  [texto]
  (into (sorted-set)
        (concat (map second (re-seq #"\(= \"([a-z][a-z\-]*)\" \(first args\)\)" texto))
                (mapcat #(map second (re-seq #"\"([a-z][a-z\-]*)\"" (second %)))
                        (re-seq #"\(#\{([^}]*)\}\s*\(first args\)\)" texto)))))

(deftest todo-comando-de-main-esta-classificado
  (let [texto (slurp "src/oplenario/main.clj")
        achados (comandos-de-main texto)]
    (is (>= (count achados) 5) (str "o detector leu os comandos de main.clj: " achados))
    (is (= (set (keys classificacao-dos-comandos-de-main)) (set achados))
        (str "comando novo em main.clj (ou removido): " (vec achados) ". Um comando que age em nome do SISTEMA numa "
             "Casa precisa de tentativa/desfecho na trilha dela; o do operador vai para a atuacao (ADR-0016)."))
    (is (empty? (sem-motivo classificacao-dos-comandos-de-main)))))

;; ---------------------------------------------------------------------------------------------------------------------
;; (3) CONSUMIDORES do outbox
;; ---------------------------------------------------------------------------------------------------------------------

(def ^:private classificacao-de-consumidores
  "Cada consumidor que o host monta no relay. `:projecao` = so' materializa read-model, canal ao vivo, feed ou caixa de
  notificacao: nao e' ato (a trilha registra o que a pessoa e o sistema FAZEM, nao o que se projeta do que foi feito).
  `:efeito-com-registro-proprio` = muda dado da Casa, mas o efeito e' uma linha append-only na MESMA transacao do
  efeito, com autor — um par na trilha (outra transacao) nao acrescentaria prova, so' um segundo relogio.
  `:operacao` = efeito no registro de Casas, selado na corrente da Operacao."
  {"tempo-real-sse"
   {:classe :projecao :motivo "publica na CanalStore (SSE do plenario): canal ao vivo, nenhum dado de Casa muda"}
   "transparencia-portal"
   {:classe :projecao :motivo "projeta eventos publicos no read-model do portal (`transparencia.*`)"}
   "transparencia-notificacao"
   {:classe :projecao :motivo "emite `notificacao.requisitada` por seguidor: pedido de aviso, nao muda dado da Casa"}
   "paineis"
   {:classe :projecao :motivo "projeta relogios e tramitacao nos read-models de `paineis`"}
   "paineis-inbox"
   {:classe :projecao :motivo "grava a caixa de notificacao in_app de quem deve receber o aviso"}
   "legislativo-notificacao"
   {:classe :projecao :motivo "avisa o autor de que a norma foi publicada (evento `notificacao.requisitada` in_app)"}
   "integracao-ia-promocao"
   {:classe :projecao :motivo "promove evento de dominio escolhido ao feed que o satelite de IA puxa (ADR-0008)"}
   "integracao-ia-cota-da-casa"
   {:classe :efeito-com-registro-proprio
    :motivo (str "zera/devolve a cota de IA da Casa suspensa/reativada: linha nova em `orcamento` (append-only) na tx do "
                 "relay, com `definido-por`; a CAUSA (suspender/reativar) ja' esta' na atuacao da Operacao")}
   "admin-sistema-registro"
   {:classe :operacao
    :motivo "ativa a Casa no registro quando o 1o administrador entra e sela na atuacao da Operacao (ADR-0016)"}})

(deftest todo-consumidor-do-relay-esta-classificado
  (let [registro (sistema/registro-de-consumidores (tr-comp/canal-store-memoria) (fn [_ _ _] nil))
        nomes (into (sorted-set) (comp (mapcat val) (map :nome)) registro)]
    (is (>= (count nomes) 8) (str "o teste viu o registro inteiro do host: " nomes))
    (is (= (set (keys classificacao-de-consumidores)) (set nomes))
        (str "consumidor novo no relay (ou removido): " (vec nomes) ". Projecao nao ganha par. Um consumidor que "
             "execute EFEITO auditavel numa Casa precisa de registro proprio na mesma tx ou do par na trilha — o relay "
             "e' um so' para todas as Casas: nada no caminho dele pode lancar por causa da auditoria. Classifique-o "
             "em `classificacao-de-consumidores` com o motivo (ADR-0017, adendo de 05/10/2026)."))
    (is (empty? (sem-motivo classificacao-de-consumidores)))
    (is (every? #{:projecao :efeito-com-registro-proprio :operacao} (map :classe (vals classificacao-de-consumidores))))))

;; ---------------------------------------------------------------------------------------------------------------------
;; o detector tem dentes
;; ---------------------------------------------------------------------------------------------------------------------

(deftest os-detectores-tem-dentes
  (testing "criar-sessao: a chamada qualificada casa; a definicao do protocolo e a docstring nao"
    (is (re-find chamada-de-criar-sessao "(let [s (repo/criar-sessao! r {})]"))
    (is (re-find chamada-de-criar-sessao "(identidade-repo/criar-sessao-operador! repo {})"))
    (is (not (re-find chamada-de-criar-sessao "(criar-sessao! [this sessao] \"doc\")")))
    (is (not (re-find chamada-de-criar-sessao "ver `criar-sessao!` no protocolo"))))
  (testing "trabalho fora de requisicao: pool, thread e agendador casam; palavra solta nao"
    (is (re-find cria-trabalho-fora-de-requisicao "(doto (Thread. ^Runnable r \"x\") (.setDaemon true))"))
    (is (re-find cria-trabalho-fora-de-requisicao "(ThreadPoolExecutor. 1 1 0 TimeUnit/MILLISECONDS q f p)"))
    (is (re-find cria-trabalho-fora-de-requisicao "(.scheduleAtFixedRate ex f 0 1 TimeUnit/SECONDS)"))
    (is (not (re-find cria-trabalho-fora-de-requisicao "(defn conferir-preenchimento [] 1)"))))
  (testing "comandos de main: os dois formatos de despacho"
    (is (= #{"a" "b-c" "d" "e"}
           (comandos-de-main "(= \"a\" (first args)) (= \"b-c\" (first args)) (#{\"d\" \"e\"} (first args))")))))
