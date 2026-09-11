(ns oplenario.legislativo.tramitacao-repo-test
  "INTEGRACAO (PG real): a COMPOSICAO do Repo-Component (ADR-0001 §3-bis) na tramitacao — `transicionar!`
  do RepoLegislativo roda o ENGINE + EMITE `proposicao.transicionou` no shared.outbox na MESMA tx (F3.3b).
  Atomicidade outbox-com-o-ato (§22.9 E2): a linha do evento so existe se a transicao commitou. Guard que
  bloqueia = sem transicao = sem evento. Prova o caminho de producao (via o Component, nao o db/ direto)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)
(def ^:dynamic *registro* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*       (:ds c)
                *repo*     (repo/->RepoLegislativoPg c (outbox/bus))
                *registro* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(def ^:private data (LocalDate/parse "2026-03-01"))

(defn- montar-template! [ente]
  (let [tid (random-uuid)]
    (repo/criar-template! *repo* ente {:id tid :chave "rito_ordinario" :versao 1
                                       :nome "Rito Ordinario [FIXTURE]" :estado-inicial "protocolada"})
    (doseq [[ch nm term] [["protocolada" "Protocolada" false] ["em_comissoes" "Em comissoes" false]
                          ["em_pauta" "Em pauta" false]]]
      (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave ch :nome nm :terminal term}))
    ;; protocolada --despachar--> em_comissoes (sem guard); em_comissoes --concluir--> em_pauta (guard 'falso')
    (repo/criar-transicao! *repo* ente {:id (random-uuid) :template-id tid :de-estado "protocolada"
                                        :para-estado "em_comissoes" :gatilho "despachar" :ordem 1})
    (repo/criar-transicao! *repo* ente {:id (random-uuid) :template-id tid :de-estado "em_comissoes"
                                        :para-estado "em_pauta" :gatilho "concluir" :guarda "falso" :ordem 1})
    tid))

(defn- eventos-transicionou [ente]
  (jdbc/execute! *ds*
    ["SELECT tipo, ente_id, payload::text AS payload FROM shared.outbox
      WHERE ente_id = ? AND tipo = 'proposicao.transicionou' ORDER BY id" ente]))

(defn- protocolar! [ente]
  (:id (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                      :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"})))

(deftest transicao-emite-evento-no-outbox
  (let [ente (random-uuid)
        tid  (montar-template! ente)
        pid  (protocolar! ente)]
    (is (empty? (eventos-transicionou ente)) "nada no outbox antes de transicionar")
    (let [r (repo/transicionar! *repo* ente *registro*
                                {:proposicao-id pid :template-id tid :gatilho "despachar" :agora data})]
      (is (true? (:transicionou? r)) "transicionou (guard nil)")
      (let [evs (eventos-transicionou ente)]
        (is (= 1 (count evs)) "exatamente 1 evento emitido")
        (let [pl (:payload (first evs))]
          (is (re-find #"protocolada" pl) "payload carrega o estado de origem")
          (is (re-find #"em_comissoes" pl) "payload carrega o estado de destino")
          (is (re-find #"despachar" pl) "payload carrega o gatilho")
          (is (re-find (re-pattern (str pid)) pl) "payload carrega a proposicao-id"))))))

(deftest guard-bloqueado-nao-emite-evento
  (let [ente (random-uuid)
        tid  (montar-template! ente)
        pid  (protocolar! ente)]
    ;; despachar (passa) -> 1 evento; concluir tem guard 'falso' -> bloqueia -> nao emite
    (repo/transicionar! *repo* ente *registro* {:proposicao-id pid :template-id tid :gatilho "despachar" :agora data})
    (let [antes (count (eventos-transicionou ente))
          r (repo/transicionar! *repo* ente *registro* {:proposicao-id pid :template-id tid :gatilho "concluir" :agora data})]
      (is (false? (:transicionou? r)) "guard 'falso' bloqueia")
      (is (= antes (count (eventos-transicionou ente))) "transicao bloqueada NAO emite evento"))))

;; ==============================================================================================
;; Fatia 4 — os dois metodos de Repo que NINGUEM executava, e o payload que nao podia reprovar
;; ==============================================================================================

(defn- template-com-estado-inicial!
  "Template GENERICO cujo `estado_inicial` e' um parametro. Existe porque as fixtures de tramitacao todas
  nascem em 'protocolada' — e' justamente isso que impedia duas assercoes desta suite de reprovar."
  [ente estado-inicial]
  (let [tid (random-uuid)]
    (repo/criar-template! *repo* ente {:id tid :chave "rito_estado_inicial" :versao 1
                                       :nome "Rito [FIXTURE]" :estado-inicial estado-inicial})
    tid))

(deftest protocolada-leva-o-estado-DA-LINHA-nao-o-literal-antigo
  ;; A PROVA QUE FALTAVA. `emitir-protocolada!` ja' fora corrigido p/ tirar `:estado` do RETORNO de
  ;; `protocolar!` em vez do literal "protocolada" — mas o conserto NAO TINHA TESTE, e plantar o literal
  ;; de volta deixava a suite IDENTICA. O motivo e' que toda fixture de tramitacao usa um rito cujo
  ;; `estado_inicial` E' 'protocolada': o valor errado e o valor certo coincidiam, e nenhuma assercao
  ;; existente conseguia distingui-los.
  ;;
  ;; Aqui o rito da Casa faz a materia nascer em 'recebida'. Se o payload voltar ao literal, esta assercao
  ;; reprova — que e' a unica coisa que a torna cobertura. E o que esta' em jogo nao e' cosmetico: o
  ;; read-model PUBLICO da transparencia projeta DESTE evento (§22.10, sem JOIN cross-modulo), entao o
  ;; literal faria o portal do cidadao afirmar um estado que a linha nunca teve.
  (let [ente (random-uuid)
        _tid (template-com-estado-inicial! ente "recebida")
        r (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                         :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre Y"})
        evs (jdbc/execute! *ds*
              ["SELECT payload::text AS payload FROM shared.outbox
                WHERE ente_id = ? AND tipo = 'proposicao.protocolada'" ente])]
    (is (= "recebida" (:estado r)) "a materia nasceu no estado_inicial do rito, nao em 'protocolada'")
    (is (= 1 (count evs)))
    (let [pl (:payload (first evs))]
      (is (re-find #"\"estado\"\s*:\s*\"recebida\"" pl)
          "o evento publico afirma o estado QUE A LINHA TEM")
      (is (not (re-find #"\"estado\"\s*:\s*\"protocolada\"" pl))
          "e nao o literal — e' esta assercao que reprova se o literal voltar"))))

(deftest tramitacao-da-proposicao-le-as-quatro-pecas-na-mesma-tx
  ;; O metodo INTEIRO da fatia 3 nunca fora executado por teste nenhum: a borda o exercitava so' por um
  ;; `reify` fake (que devolve o que o teste mandar) e a suite de integracao parava em `db/`. Um fake nao
  ;; prova que a composicao existe, nem que as leituras DEPENDENTES (candidatas e estado-no-rito dependem
  ;; do estado ATUAL, que so' se sabe depois de ler a linha) resolvem na ordem certa — prova so' que o
  ;; controller sabe consumir o mapa que ele mesmo desenhou.
  (let [ente (random-uuid)
        tid  (montar-template! ente)
        pid  (protocolar! ente)]
    (repo/transicionar! *repo* ente *registro*
                        {:proposicao-id pid :template-id tid :gatilho "despachar" :agora data})
    (let [m (repo/tramitacao-da-proposicao *repo* ente pid 100)]
      (is (= pid (:id (:proposicao m))) "a LINHA")
      (is (= "em_comissoes" (:estado (:proposicao m))))
      (is (= tid (:template-id (:proposicao m))) "com o elo do rito (mig 0076)")
      (is (= [["protocolada" "em_comissoes" "despachar"]]
             (mapv (juxt :de-estado :para-estado :gatilho) (:historico m)))
          "o HISTORICO da materia, cronologico")
      (is (= ["concluir"] (mapv :gatilho (:candidatas m)))
          "as CANDIDATAS sao as do estado ATUAL ('em_comissoes'), nao as do inicial — e' o que torna as
           leituras DEPENDENTES e o que obriga a tx unica")
      (is (= "falso" (:guarda (first (:candidatas m)))) "com o guard junto (ato incondicional vs. recusavel)")
      (is (false? (:terminal (:estado-no-template m))) "o ESTADO no rito, que separa fim-de-rito de beco"))))

(deftest tramitacao-da-proposicao-empurra-o-teto-do-historico-ao-SQL
  ;; `limite` e' o teto do HISTORICO — e o controller passa teto+1 como SONDA de truncamento. Se o metodo
  ;; ignorasse o argumento (ou o aplicasse em memoria), a sonda devolveria sempre o mesmo conjunto e o
  ;; `historico-truncado` da borda seria decorativo: a resposta diria que ha' mais quando nao ha', ou pior,
  ;; nao diria quando ha'.
  (let [ente (random-uuid)
        tid  (montar-template! ente)
        pid  (protocolar! ente)]
    ;; duas transicoes de ida e volta pelo mesmo rito nao existem no fixture; basta UMA + a leitura com
    ;; teto 0-util (limite 1) p/ provar que o teto viaja ao SQL.
    (repo/transicionar! *repo* ente *registro*
                        {:proposicao-id pid :template-id tid :gatilho "despachar" :agora data})
    (is (= 1 (count (:historico (repo/tramitacao-da-proposicao *repo* ente pid 1)))))
    (is (= 0 (count (:historico (repo/tramitacao-da-proposicao *repo* ente pid 0))))
        "teto 0 nao devolve linha nenhuma — o limite chegou ao SQL, nao foi descartado no caminho")))

(deftest tramitacao-da-proposicao-de-materia-inexistente-nao-consulta-o-rito
  ;; nil na linha = a borda traduz 404. As outras tres chaves vem vazias/nil por CONTRATO: sem materia nao
  ;; ha' estado, e sem estado nao ha' o que perguntar ao rito. Uma implementacao que consultasse o template
  ;; assim mesmo leria com `estado` nil e devolveria "o rito nao conhece o estado" sobre uma materia que
  ;; nao existe — o diagnostico errado, com a confianca de um diagnostico certo.
  (let [ente (random-uuid)
        m (repo/tramitacao-da-proposicao *repo* ente (random-uuid) 100)]
    (is (nil? (:proposicao m)))
    (is (= [] (:historico m)))
    (is (= [] (:candidatas m)))
    (is (nil? (:estado-no-template m)))))

(deftest materia-SEM-rito-tem-historico-lido-e-rito-nao-consultado
  ;; A assimetria deliberada do impl: sem `template_id` o rito NAO e' consultado (nao ha' o que consultar),
  ;; mas o historico E' lido. "Sem rito logo nunca tramitou" e' INFERENCIA — verdadeira hoje porque
  ;; `transicionar!` exige template-id — e uma leitura de AUDITORIA que devolve inferencia no lugar do dado
  ;; mente no dia em que a inferencia deixar de valer. Este teste e' o que segura a assimetria no lugar.
  (let [ente (random-uuid)                         ; nenhum template: a materia nasce sem rito
        pid  (protocolar! ente)
        m    (repo/tramitacao-da-proposicao *repo* ente pid 100)]
    (is (some? (:proposicao m)) "a materia existe (200, nao 404)")
    (is (nil? (:template-id (:proposicao m))) "e nao tem rito")
    (is (= [] (:historico m)) "historico LIDO (e vazio), nao pulado")
    (is (= [] (:candidatas m)))
    (is (nil? (:estado-no-template m)) "o rito nao foi consultado — nao havia rito a consultar")))
