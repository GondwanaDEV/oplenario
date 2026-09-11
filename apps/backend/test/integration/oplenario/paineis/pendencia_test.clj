(ns oplenario.paineis.pendencia-test
  "INTEGRACAO (PG real) — F7 Slice 1: a PROJECAO do painel 'o que vence' (§16.11). `participacao` EMITE os
  eventos de protocolo/fechamento/vencimento/prorrogacao dos 4 relogios (e-SIC/recurso/LGPD/ouvidoria); o
  relay DRENA e despacha ao consumer de paineis (`paineis.diplomat.consumers`), que PROJETA em
  `paineis.pendencia` (mig 0048) — sem import/JOIN cross-modulo (§22.10). Este teste emite os eventos DIRETO
  no outbox (o payload casa o contrato dos eventos reais de participacao), drena, e le' pela leitura interna
  (Repo). Prova tambem RLS (isolamento cross-tenant), tolerancia (fechamento/vencimento/prorrogacao sem
  pendencia projetada, e payload malformado, nunca lancam) e idempotencia (ON CONFLICT DO NOTHING no redrive
  do protocolo)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.paineis.components.repositorio :as repo]
            [oplenario.paineis.db.pendencia :as db-pendencia]
            [oplenario.paineis.diplomat.consumers :as consumers]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*   (:ds c)
                *repo* (repo/->RepoPaineisPg c)]
        (try (t) (finally (component/stop c)))))))

(defn- drenar! []
  (outbox/drenar! *ds* (consumers/registrar {})))

(defn- emitir! [ente tipo payload]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (eventos/emitir! (outbox/bus) tx (eventos/evento tipo ente payload)))))

;; ---------- protocolo (4 especies) -> INSERT pendente ----------

(deftest pedido-esic-protocolado-projeta-pendencia
  (let [ente (random-uuid) pid (random-uuid)]
    (emitir! ente "participacao.pedido_esic.protocolado"
             {:pedido-id (str pid) :protocolo "ESIC-2026-000001"
              :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-07-24"})
    (drenar!)
    (let [[p] (:pendencias (repo/o-que-vence *repo* ente {}))]
      (is (some? p) "a pendencia foi projetada")
      (is (= "pedido_esic" (:objeto-tipo p)))
      (is (= pid (:objeto-id p)))
      (is (= "ESIC-2026-000001" (:protocolo p)))
      (is (= (java.time.LocalDate/of 2026 7 24) (:vence-em p)))
      (is (= "pendente" (:estado p))))
    (is (empty? (:pendencias (repo/o-que-vence *repo* (random-uuid) {}))) "RLS: outro ente nao ve a pendencia projetada")))

(deftest recurso-esic-protocolado-projeta-pendencia
  (let [ente (random-uuid) rid (random-uuid)]
    (emitir! ente "participacao.recurso_esic.protocolado"
             {:recurso-id (str rid) :pedido-id (str (random-uuid)) :protocolo "ESIC-REC-2026-000001"
              :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-07-09"})
    (drenar!)
    (let [[p] (:pendencias (repo/o-que-vence *repo* ente {}))]
      (is (= "recurso_esic" (:objeto-tipo p)))
      (is (= rid (:objeto-id p))))))

(deftest solicitacao-titular-protocolada-projeta-pendencia
  (let [ente (random-uuid) sid (random-uuid)]
    (emitir! ente "participacao.solicitacao_titular.protocolada"
             {:solicitacao-id (str sid) :protocolo "LGPD-2026-000001" :tipo "acesso"
              :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-08-03"})
    (drenar!)
    (let [[p] (:pendencias (repo/o-que-vence *repo* ente {}))]
      (is (= "solicitacao_titular" (:objeto-tipo p)))
      (is (= sid (:objeto-id p))))))

(deftest manifestacao-ouvidoria-protocolada-projeta-pendencia
  (let [ente (random-uuid) mid (random-uuid)]
    (emitir! ente "participacao.manifestacao_ouvidoria.protocolada"
             {:manifestacao-id (str mid) :protocolo "OUV-2026-000001"
              :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-08-03"})
    (drenar!)
    (let [[p] (:pendencias (repo/o-que-vence *repo* ente {}))]
      (is (= "manifestacao_ouvidoria" (:objeto-tipo p)))
      (is (= mid (:objeto-id p))))))

;; ---------- fechamento (5 eventos) -> concluido, some de "o que vence" ----------

(deftest pedido-esic-respondido-fecha-a-pendencia
  (let [ente (random-uuid) pid (random-uuid)]
    (emitir! ente "participacao.pedido_esic.protocolado"
             {:pedido-id (str pid) :protocolo "ESIC-2026-000002"
              :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-07-24"})
    (drenar!)
    (emitir! ente "participacao.pedido_esic.respondido"
             {:pedido-id (str pid) :protocolo "ESIC-2026-000002" :respondida-em "2026-07-10T09:00:00Z"})
    (drenar!)
    (is (empty? (:pendencias (repo/o-que-vence *repo* ente {}))) "concluido nao aparece mais em 'o que vence'")))

(deftest recurso-esic-decidido-fecha-a-pendencia
  (let [ente (random-uuid) rid (random-uuid)]
    (emitir! ente "participacao.recurso_esic.protocolado"
             {:recurso-id (str rid) :pedido-id (str (random-uuid)) :protocolo "ESIC-REC-2026-000002"
              :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-07-09"})
    (drenar!)
    (emitir! ente "participacao.recurso_esic.decidido" {:recurso-id (str rid) :decidido-em "2026-07-06T09:00:00Z"})
    (drenar!)
    (is (empty? (:pendencias (repo/o-que-vence *repo* ente {}))))))

(deftest solicitacao-titular-respondida-fecha-a-pendencia
  (let [ente (random-uuid) sid (random-uuid)]
    (emitir! ente "participacao.solicitacao_titular.protocolada"
             {:solicitacao-id (str sid) :protocolo "LGPD-2026-000002" :tipo "acesso"
              :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-08-03"})
    (drenar!)
    (emitir! ente "participacao.solicitacao_titular.respondida"
             {:solicitacao-id (str sid) :respondida-em "2026-07-06T09:00:00Z"})
    (drenar!)
    (is (empty? (:pendencias (repo/o-que-vence *repo* ente {}))))))

(deftest manifestacao-ouvidoria-respondida-fecha-a-pendencia
  (let [ente (random-uuid) mid (random-uuid)]
    (emitir! ente "participacao.manifestacao_ouvidoria.protocolada"
             {:manifestacao-id (str mid) :protocolo "OUV-2026-000002"
              :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-08-03"})
    (drenar!)
    (emitir! ente "participacao.manifestacao_ouvidoria.respondida"
             {:manifestacao-id (str mid) :protocolo "OUV-2026-000002" :respondida-em "2026-07-06T09:00:00Z"})
    (drenar!)
    (is (empty? (:pendencias (repo/o-que-vence *repo* ente {}))))))

(deftest manifestacao-ouvidoria-arquivada-fecha-a-pendencia
  (let [ente (random-uuid) mid (random-uuid)]
    (emitir! ente "participacao.manifestacao_ouvidoria.protocolada"
             {:manifestacao-id (str mid) :protocolo "OUV-2026-000003"
              :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-08-03"})
    (drenar!)
    (emitir! ente "participacao.manifestacao_ouvidoria.arquivada"
             {:manifestacao-id (str mid) :protocolo "OUV-2026-000003" :arquivada-em "2026-07-06T09:00:00Z"})
    (drenar!)
    (is (empty? (:pendencias (repo/o-que-vence *repo* ente {}))) "arquivamento tambem fecha (sem merito, mas fora do radar)")))

(deftest fechamento-sem-pendencia-projetada-nao-lanca
  ;; TOLERANCIA (mesmo racional de transparencia/atualizar-estado!): um fechamento cujo protocolo ainda nao
  ;; foi drenado (backlog/reordenacao) NUNCA pode lancar dentro da tx do relay COMPARTILHADO. NAO afirma o
  ;; Nº literal devolvido por drenar! (GOTCHA de suite: shared.outbox e' PERSISTENTE entre TODOS os
  ;; namespaces de teste da mesma rodada — outros modulos podem deixar backlog, entao drenar! aqui pode
  ;; varrer mais de 1 evento); o que prova a nao-excecao e' o proprio drenar! RETORNAR sem lancar.
  (let [ente (random-uuid)]
    (emitir! ente "participacao.pedido_esic.respondido"
             {:pedido-id (str (random-uuid)) :protocolo "ESIC-FANTASMA" :respondida-em "2026-07-06T09:00:00Z"})
    (drenar!)
    (is (empty? (:pendencias (repo/o-que-vence *repo* ente {}))) "nenhuma pendencia fantasma foi criada")))

;; ---------- vencimento e prorrogacao (polimorficos) ----------

(deftest prazo-vencido-flipa-pendente-para-vencido
  (let [ente (random-uuid) pid (random-uuid)]
    (emitir! ente "participacao.pedido_esic.protocolado"
             {:pedido-id (str pid) :protocolo "ESIC-2026-000003"
              :recibo-em "2026-06-01T12:00:00Z" :vence-em "2026-06-21"})
    (drenar!)
    (emitir! ente "participacao.prazo.vencido"
             {:objeto-tipo "pedido_esic" :objeto-id (str pid) :vence-em "2026-06-21"})
    (drenar!)
    (let [[p] (:pendencias (repo/o-que-vence *repo* ente {}))]
      (is (= "vencido" (:estado p)) "flipou pendente->vencido; segue visivel em 'o que vence' (mais urgente)"))))

(deftest prazo-vencido-sem-pendencia-projetada-nao-lanca
  (let [ente (random-uuid)]
    (emitir! ente "participacao.prazo.vencido"
             {:objeto-tipo "manifestacao_ouvidoria" :objeto-id (str (random-uuid)) :vence-em "2026-06-21"})
    (drenar!)
    (is (empty? (:pendencias (repo/o-que-vence *repo* ente {}))))))

(deftest prazo-prorrogado-adota-o-novo-vencimento
  (let [ente (random-uuid) mid (random-uuid)]
    (emitir! ente "participacao.manifestacao_ouvidoria.protocolada"
             {:manifestacao-id (str mid) :protocolo "OUV-2026-000004"
              :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-08-03"})
    (drenar!)
    (emitir! ente "participacao.prazo.prorrogado"
             {:objeto-tipo "manifestacao_ouvidoria" :objeto-id (str mid)
              :de-data "2026-08-03" :para-data "2026-09-02"})
    (drenar!)
    (let [[p] (:pendencias (repo/o-que-vence *repo* ente {}))]
      (is (= (java.time.LocalDate/of 2026 9 2) (:vence-em p)) "adotou para-data")
      (is (= "pendente" (:estado p)) "prorrogacao nao muda o estado"))))

(deftest prazo-prorrogado-reabre-pendencia-ja-vencida
  ;; review database LOW-MEDIUM: um `prorrogado` aplicado FORA DE ORDEM apos um `vencido` ja projetado
  ;; (cenario de redrive/backfill) nao pode deixar `vence_em` no futuro com `estado` travado em 'vencido' —
  ;; atualizar-vence-em! agora tambem reabre p/ 'pendente' (self-heal).
  (let [ente (random-uuid) pid (random-uuid)]
    (emitir! ente "participacao.pedido_esic.protocolado"
             {:pedido-id (str pid) :protocolo "ESIC-2026-000004"
              :recibo-em "2026-06-01T12:00:00Z" :vence-em "2026-06-21"})
    (drenar!)
    (emitir! ente "participacao.prazo.vencido"
             {:objeto-tipo "pedido_esic" :objeto-id (str pid) :vence-em "2026-06-21"})
    (drenar!)
    (emitir! ente "participacao.prazo.prorrogado"
             {:objeto-tipo "pedido_esic" :objeto-id (str pid) :de-data "2026-06-21" :para-data "2026-07-21"})
    (drenar!)
    (let [[p] (:pendencias (repo/o-que-vence *repo* ente {}))]
      (is (= "pendente" (:estado p)) "reabriu de vencido->pendente (o novo prazo esta no futuro)")
      (is (= (java.time.LocalDate/of 2026 7 21) (:vence-em p))))))

(deftest prazo-prorrogado-sem-pendencia-projetada-nao-lanca
  (let [ente (random-uuid)]
    (emitir! ente "participacao.prazo.prorrogado"
             {:objeto-tipo "manifestacao_ouvidoria" :objeto-id (str (random-uuid))
              :de-data "2026-08-03" :para-data "2026-09-02"})
    (drenar!)
    (is (empty? (:pendencias (repo/o-que-vence *repo* ente {}))))))

;; ---------- idempotencia + drift-guard ----------

(deftest protocolo-redrive-e-no-op
  (let [ente (random-uuid) pid (random-uuid)
        payload {:pedido-id (str pid) :protocolo "ESIC-2026-000005"
                 :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-07-24"}]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (some? (repo/projetar-evento! tx {:tipo "participacao.pedido_esic.protocolado"
                                              :ente-id ente :payload payload}))
            "1a projecao insere")
        (is (nil? (repo/projetar-evento! tx {:tipo "participacao.pedido_esic.protocolado"
                                             :ente-id ente :payload payload}))
            "2a projecao (mesmo pedido-id) = no-op (ON CONFLICT DO NOTHING), NUNCA lanca")))
    (is (= 1 (count (:pendencias (repo/o-que-vence *repo* ente {})))) "uma unica linha permanece")))

;; drift-guard (`todo-tipo-consumido-tem-branch-de-projecao`) consolidado em consumers_test.clj (review
;; clojure MEDIUM, F7 Slice 2: vivia duplicado byte-a-byte aqui e em tramitacao_test.clj — o guard e'
;; MODULO-WIDE (itera `consumers/tipos-consumidos` inteiro), nao por feature; pertence a UM lugar so'.

(deftest projetar-evento-tolera-payload-malformado
  ;; O FIX do security HIGH: um payload com data ILEGIVEL (nao ISO — ex.: um bug de producao futuro em
  ;; participacao) faria LocalDate/parse lancar ANTES de qualquer SQL. Sem o try/catch de projetar-evento!,
  ;; isso propagaria p/ dentro da tx do relay COMPARTILHADO (poison de TODOS os modulos). Prova que o
  ;; caminho tolerante (`projetar-evento!`) engole a excecao — nunca deixa escapar — mesmo quando `despachar!`
  ;; (chamado diretamente logo abaixo) de fato lanca p/ o MESMO payload."
  (let [ente (random-uuid)
        payload {:pedido-id (str (random-uuid)) :protocolo "ESIC-MALFORMADO"
                 :recibo-em "2026-07-04T12:00:00Z" :vence-em "nao-e-uma-data"}]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (thrown? Exception (repo/despachar! tx ente "participacao.pedido_esic.protocolado" payload))
            "despachar! (sem tolerancia) de fato lanca em data malformada — prova que o cenario e' real")))
    (is (nil? (try (tenancy/com-tenant* *ds* ente
                     (fn [tx] (repo/projetar-evento! tx {:tipo "participacao.pedido_esic.protocolado"
                                                         :ente-id ente :payload payload})))
                   (catch Throwable e e)))
        "projetar-evento! (o caminho real do consumer) NUNCA deixa a excecao escapar")
    (is (empty? (:pendencias (repo/o-que-vence *repo* ente {}))) "nenhuma pendencia foi criada a partir do payload malformado")))

;; ---------- leitura: "o que vence" ordena por vencimento, exclui concluido ----------

(deftest o-que-vence-ordena-por-vencimento-e-exclui-concluido
  (let [ente (random-uuid) p1 (random-uuid) p2 (random-uuid) p3 (random-uuid)]
    (emitir! ente "participacao.pedido_esic.protocolado"
             {:pedido-id (str p1) :protocolo "ESIC-A" :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-08-31"})
    (emitir! ente "participacao.pedido_esic.protocolado"
             {:pedido-id (str p2) :protocolo "ESIC-B" :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-07-10"})
    (emitir! ente "participacao.pedido_esic.protocolado"
             {:pedido-id (str p3) :protocolo "ESIC-C" :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-07-31"})
    (drenar!)
    (emitir! ente "participacao.pedido_esic.respondido"
             {:pedido-id (str p3) :protocolo "ESIC-C" :respondida-em "2026-07-05T09:00:00Z"})
    (drenar!)
    (let [abertas (:pendencias (repo/o-que-vence *repo* ente {}))]
      (is (= 2 (count abertas)) "p3 (concluido) fora")
      (is (= [p2 p1] (map :objeto-id abertas)) "ordenadas por vence-em asc (mais urgente primeiro)"))))

;; ---------- pendencias-total: o TOTAL real, sem o teto — o CRITICO da frente "truncamento-familia" ----------
;; GET /paineis/pendencias:131 cortava em 100 (teto-o-que-vence) sem sinalizar; a Casa atrasada (vencidas
;; primeiro na ordenacao) e' exatamente a que perde os prazos FUTUROS de vista. Insercao DIRETA via db/
;; (nao via emitir!/drenar!) pelos mesmos motivos do precedente compliance/painel_test: mais barato para
;; criar N linhas e para forcar um estado especifico (vencido/concluido) sem montar a cadeia de eventos.

(deftest o-que-vence-total-bate-com-a-lista-quando-nao-ha-corte
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (db-pendencia/inserir! tx {:ente-id ente :objeto-tipo "pedido_esic" :objeto-id (random-uuid)
                                   :protocolo "A" :vence-em (java.time.LocalDate/of 2099 1 1)})
        (db-pendencia/inserir! tx {:ente-id ente :objeto-tipo "recurso_esic" :objeto-id (random-uuid)
                                   :protocolo "B" :vence-em (java.time.LocalDate/of 2099 1 2)})))
    (let [r (repo/o-que-vence *repo* ente {})]
      (is (= 2 (count (:pendencias r))))
      (is (= 2 (:pendencias-total r)) "o total bate com a lista quando nao ha corte"))))

(deftest o-que-vence-com-limite-injetado-trunca-lista-mas-total-continua-real
  ;; o teto de producao e' 100 (caro demais criar no teste); a fatia expoe `opts` ({:limite}) exatamente
  ;; para permitir injetar um teto pequeno e provar o corte SEM pagar o custo de 101 linhas — mesmo
  ;; racional de compliance/painel-com-teto-injetado-trunca-lista-mas-total-continua-real. O caminho de
  ;; PRODUCAO continua chamando com `{}` (controllers/o-que-vence) e cai no default teto-o-que-vence=100.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (dotimes [i 5]
          (db-pendencia/inserir! tx {:ente-id ente :objeto-tipo "pedido_esic" :objeto-id (random-uuid)
                                     :protocolo (str "P-" i) :vence-em (java.time.LocalDate/of 2099 1 (inc i))}))))
    (let [r (repo/o-que-vence *repo* ente {:limite 2})]
      (is (= 2 (count (:pendencias r))) "a lista respeita o teto INJETADO")
      (is (= 5 (:pendencias-total r)) "o total ignora o teto injetado — continua o numero real"))))

(deftest o-que-vence-total-usa-o-mesmo-predicado-da-lista
  ;; a asserção que mata a DERIVA: lista e total tem de nascer do MESMO filtro de estado. Insere um estado
  ;; de CADA fase do dominio (pendente/vencido/concluido, CHECK de paineis.pendencia) — se o total um dia
  ;; passar a usar um predicado copiado (em vez de reusar `resumo`, que `listar-abertas` tambem usa), esta
  ;; asserção reprova no dia em que os dois divergirem, nao anos depois.
  (let [ente (random-uuid) a (random-uuid) b (random-uuid) c (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (db-pendencia/inserir! tx {:ente-id ente :objeto-tipo "pedido_esic" :objeto-id a
                                   :protocolo "A" :vence-em (java.time.LocalDate/of 2099 1 1)})
        (db-pendencia/inserir! tx {:ente-id ente :objeto-tipo "recurso_esic" :objeto-id b
                                   :protocolo "B" :vence-em (java.time.LocalDate/of 2099 1 2)})
        (db-pendencia/marcar-vencida! tx {:ente-id ente :objeto-tipo "recurso_esic" :objeto-id b})
        (db-pendencia/inserir! tx {:ente-id ente :objeto-tipo "solicitacao_titular" :objeto-id c
                                   :protocolo "C" :vence-em (java.time.LocalDate/of 2099 1 3)})
        (db-pendencia/marcar-concluida! tx {:ente-id ente :objeto-tipo "solicitacao_titular" :objeto-id c})))
    (let [r (repo/o-que-vence *repo* ente {})]
      (is (= 2 (count (:pendencias r))) "a lista: so pendente+vencido (concluido fora)")
      (is (= 2 (:pendencias-total r))
          "o total: o MESMO conjunto que a lista enxerga — a prova de que e' o mesmo predicado"))))
