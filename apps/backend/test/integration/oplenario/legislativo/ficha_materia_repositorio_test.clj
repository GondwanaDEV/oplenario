(ns oplenario.legislativo.ficha-materia-repositorio-test
  "INTEGRACAO (PG real): a COMPOSICAO do Repo-Component (ADR-0001 §3-bis, Onda B Slice 3) — a leitura
  agregada da ficha da materia (proposicao + texto vigente + historico de tramitacao + apensadas ativas +
  emendas + pareceres) NUMA UNICA tx (mesma disciplina de `buscar-proposicao-detalhe`/
  `listar-e-contar-proposicoes`: nunca leituras independentes que poderiam desalinhar sob escrita
  concorrente). Repo real, sem HTTP — mesmo padrao de `proposicao_repositorio_test.clj`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoLegislativoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(defn- protocolar! [ente]
  (:id (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                      :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"
                                      :texto "## Art. 1o"})))

(deftest ficha-completa-compoe-proposicao-e-texto
  (let [ente (random-uuid)
        pid (protocolar! ente)
        f (repo/ficha-completa-da-proposicao *repo* ente pid)]
    (is (= pid (:id (:proposicao f))))
    (is (= "## Art. 1o" (:texto-inline (:texto f))))
    (is (false? (:aprovada (:proposicao f)))
        "Fatia 2: sem votacao encerrada 'aprovada', o cabecalho da ficha traz o mesmo fato false")))

(deftest ficha-completa-de-proposicao-inexistente-devolve-proposicao-nil
  (let [ente (random-uuid)
        f (repo/ficha-completa-da-proposicao *repo* ente (random-uuid))]
    (is (nil? (:proposicao f)))
    (is (= [] (:tramitacao f)))
    (is (= [] (:apensadas f)))
    (is (= [] (:emendas f)))
    (is (= [] (:pareceres f)))))

(deftest ficha-completa-traz-historico-de-tramitacao
  ;; o rito e' montado ANTES do protocolo (fatia 4 da borda de tramitacao): `transicionar!` confronta o
  ;; `template-id` do argumento com o `template_id` da LINHA, e a materia so' recebe o elo se o rito ja'
  ;; existir quando ela nasce. Na ordem antiga a fixture tramitava uma materia SEM rito, que e' um estado
  ;; que o produto nao produz (a borda recusa com `:conflito/sem-rito`).
  (let [ente (random-uuid)
        tid (random-uuid)
        _ (repo/criar-template! *repo* ente {:id tid :chave "rito_ordinario" :versao 1
                                             :nome "Rito Ordinario [FIXTURE]" :estado-inicial "protocolada"})
        _ (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave "protocolada"
                                           :nome "Protocolada" :terminal false})
        _ (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave "em_comissoes"
                                           :nome "Em comissoes" :terminal false})
        _ (repo/criar-transicao! *repo* ente {:id (random-uuid) :template-id tid :de-estado "protocolada"
                                              :para-estado "em_comissoes" :gatilho "despachar" :ordem 1})
        pid (protocolar! ente)]
    (repo/transicionar! *repo* ente {} {:proposicao-id pid :template-id tid :gatilho "despachar"})
    (let [f (repo/ficha-completa-da-proposicao *repo* ente pid)]
      (is (= 1 (count (:tramitacao f))))
      (is (= "protocolada" (:de-estado (first (:tramitacao f)))))
      (is (= "em_comissoes" (:para-estado (first (:tramitacao f))))))))

(deftest ficha-completa-traz-apensadas-emendas-e-pareceres
  (let [ente (random-uuid)
        pid (protocolar! ente)
        apensada-pid (protocolar! ente)
        tid-parecer (random-uuid)]
    (repo/apensar! *repo* ente {:id (random-uuid) :principal-id pid :apensada-id apensada-pid})
    (repo/criar-emenda! *repo* ente {:id (random-uuid) :proposicao-mae-id pid :tipo-emenda "aditiva"
                                     :momento-apresentacao "no_prazo" :formato "markdown" :texto-inline "Emenda 1"})
    (repo/criar-template! *repo* ente {:id tid-parecer :chave "parecer_ccj" :versao 1 :sujeito "parecer"
                                       :nome "Parecer CCJ [FIXTURE]" :estado-inicial "aguardando_designacao"})
    (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid-parecer :chave "aguardando_designacao"
                                     :nome "Aguardando" :terminal false})
    (repo/iniciar-parecer! *repo* ente {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id pid
                                        :comissao-id (random-uuid) :template-id tid-parecer})
    (let [f (repo/ficha-completa-da-proposicao *repo* ente pid)]
      (is (= 1 (count (:apensadas f))))
      (is (= apensada-pid (:apensada-id (first (:apensadas f)))))
      (is (= 1 (count (:emendas f))))
      (is (= "aditiva" (:tipo-emenda (first (:emendas f)))))
      (is (= 1 (count (:pareceres f))))
      (is (= "aguardando_designacao" (:estado (first (:pareceres f))))))))

(deftest ficha-completa-traz-o-rito-da-materia-para-a-faixa
  ;; "Onde esta' a materia": a ordem das etapas vem do RITO da Casa (estados + transicoes do template da materia),
  ;; nunca de uma lista de nomes no front. Vocabulario de uma Casa fora da demo, de proposito.
  (let [ente (random-uuid)
        tid (random-uuid)
        _ (repo/criar-template! *repo* ente {:id tid :chave "rito_da_faixa" :versao 1
                                             :nome "Rito da faixa [FIXTURE]" :estado-inicial "entrada"})
        _ (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave "entrada" :nome "Entrada"
                                           :terminal false :ordem 1})
        _ (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave "instrucao" :nome "Instrução"
                                           :terminal false :ordem 2})
        _ (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave "promulgada" :nome "Promulgada"
                                           :terminal true :ordem 3})
        _ (repo/criar-transicao! *repo* ente {:id (random-uuid) :template-id tid :de-estado "entrada"
                                              :para-estado "instrucao" :gatilho "instruir" :ordem 1})
        _ (repo/criar-transicao! *repo* ente {:id (random-uuid) :template-id tid :de-estado "instrucao"
                                              :para-estado "promulgada" :gatilho "promulgar" :ordem 1})
        pid (protocolar! ente)
        rito (:rito-do-template (repo/ficha-completa-da-proposicao *repo* ente pid))]
    (is (= "entrada" (:estado-inicial rito)))
    (is (= [["entrada" "Entrada" false 1] ["instrucao" "Instrução" false 2] ["promulgada" "Promulgada" true 3]]
           (mapv (juxt :chave :nome :terminal :ordem) (:estados rito)))
        "os estados como o rito os declara, na ordem declarada")
    (is (= [["entrada" "instrucao"] ["instrucao" "promulgada"]]
           (mapv (juxt :de-estado :para-estado) (:transicoes rito))))))

(deftest ficha-completa-do-rito-nao-vaza-rito-de-outra-casa
  ;; a RLS esconde o template do vizinho: a materia da Casa B, com rito proprio, nunca le' estados da Casa A
  (let [ente-a (random-uuid) ente-b (random-uuid)
        tid-a (random-uuid) tid-b (random-uuid)
        mk (fn [ente tid chave nome]
             (repo/criar-template! *repo* ente {:id tid :chave (str "rito_" chave) :versao 1 :nome "R [FIXTURE]"
                                                :estado-inicial chave})
             (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave chave :nome nome
                                              :terminal false :ordem 1}))
        _ (mk ente-a tid-a "so_da_casa_a" "Só da Casa A")
        _ (mk ente-b tid-b "so_da_casa_b" "Só da Casa B")
        pid-b (protocolar! ente-b)
        rito (:rito-do-template (repo/ficha-completa-da-proposicao *repo* ente-b pid-b))]
    (is (= ["so_da_casa_b"] (mapv :chave (:estados rito))))))

(deftest ficha-completa-sem-rito-nao-traz-rito
  ;; materia que nasce sem rito (a Casa nao cadastrou nenhum para a especie) nao tem o que desenhar
  (let [ente (random-uuid)
        pid (protocolar! ente)
        f (repo/ficha-completa-da-proposicao *repo* ente pid)]
    (is (nil? (:rito-do-template f)))))

;; ---------- fatia 'truncamento-familia': as 4 listas param de fingir completude ----------

(deftest ficha-completa-sem-corte-nao-sinaliza-truncamento
  ;; sanidade: abaixo do teto, as 4 listas concordam com o `-truncado` = false (nunca true por acidente).
  (let [ente (random-uuid)
        pid (protocolar! ente)]
    (let [f (repo/ficha-completa-da-proposicao *repo* ente pid)]
      (is (false? (:tramitacao-truncado f)))
      (is (false? (:apensadas-truncado f)))
      (is (false? (:emendas-truncado f)))
      (is (false? (:pareceres-truncado f))))))

(deftest ficha-completa-sinaliza-truncamento-nas-4-listas-sem-derivar-do-corte-ja-aplicado
  ;; O CRITICO desta fatia: as 4 listas da ficha (tramitacao/apensadas/emendas/pareceres) cortavam em
  ;; 100/50/50/50 SEM avisar — "Pareceres (N)"/"Emendas (N)" mostravam um N que o usuario le' como o
  ;; TOTAL. A rota IRMA (GET /proposicoes/:id/tramitacao, `controllers/buscar-tramitacao`) ja' resolvia
  ;; isto com `:historico-truncado`, pedindo `limite+1` ao MESMO db/ — a SONDA. Esta fatia reusa a
  ;; MESMA sonda nas 4 listas (nunca uma 5a forma, nunca `count(*)` novo).
  ;;
  ;; `with-redefs` baixa os 4 tetos de producao (100/50/50/50, privados em components/repositorio) pra 2
  ;; — mesmo racional do teto-de-linhas-lote injetado em repositorio_roster_lote_test (T7): provar o
  ;; corte SEM pagar o custo de inserir mais de 100 linhas reais. O teste NAO quebra se o teto de
  ;; producao mudar de valor (nao le' o numero, so' redefine).
  (let [ente (random-uuid)
        tid (random-uuid)
        tid-parecer (random-uuid)
        ;; rito com 2 estados/2 gatilhos que se alternam — permite gerar N transicoes reais sem N estados.
        _ (repo/criar-template! *repo* ente {:id tid :chave "rito_ficha_trunc" :versao 1
                                             :nome "Rito ficha-trunc [FIXTURE]" :estado-inicial "e0"})
        _ (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave "e0" :nome "E0" :terminal false})
        _ (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid :chave "e1" :nome "E1" :terminal false})
        _ (repo/criar-transicao! *repo* ente {:id (random-uuid) :template-id tid :de-estado "e0"
                                              :para-estado "e1" :gatilho "ir" :ordem 1})
        _ (repo/criar-transicao! *repo* ente {:id (random-uuid) :template-id tid :de-estado "e1"
                                              :para-estado "e0" :gatilho "voltar" :ordem 1})
        ;; o rito tem de existir ANTES do protocolo (mesmo motivo de ficha-completa-traz-historico-de-
        ;; tramitacao acima): `transicionar!` confronta o template-id do argumento com o da LINHA.
        pid (protocolar! ente)
        _ (repo/criar-template! *repo* ente {:id tid-parecer :chave "parecer_ficha_trunc" :versao 1
                                             :sujeito "parecer" :nome "Parecer ficha-trunc [FIXTURE]"
                                             :estado-inicial "aguardando"})
        _ (repo/criar-estado! *repo* ente {:id (random-uuid) :template-id tid-parecer :chave "aguardando"
                                           :nome "Aguardando" :terminal false})]
    (with-redefs [repo/teto-tramitacao-ficha 2 repo/teto-apensadas-ficha 2
                  repo/teto-emendas-ficha 2 repo/teto-pareceres-ficha 2]
      (doseq [gatilho ["ir" "voltar" "ir" "voltar"]]
        (repo/transicionar! *repo* ente {} {:proposicao-id pid :template-id tid :gatilho gatilho}))
      (dotimes [_ 4]
        (repo/apensar! *repo* ente {:id (random-uuid) :principal-id pid :apensada-id (protocolar! ente)}))
      (dotimes [i 4]
        (repo/criar-emenda! *repo* ente {:id (random-uuid) :proposicao-mae-id pid :tipo-emenda "aditiva"
                                         :momento-apresentacao "no_prazo" :formato "markdown"
                                         :texto-inline (str "Emenda " i)}))
      (dotimes [_ 4]
        (repo/iniciar-parecer! *repo* ente {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id pid
                                            :comissao-id (random-uuid) :template-id tid-parecer}))
      (let [f (repo/ficha-completa-da-proposicao *repo* ente pid)]
        (is (= 2 (count (:tramitacao f))) "a LISTA de tramitacao continua cortada no teto injetado")
        (is (true? (:tramitacao-truncado f)) "4 transicoes reais > teto 2 -> sinaliza")
        (is (= 2 (count (:apensadas f))) "a LISTA de apensadas continua cortada no teto injetado")
        (is (true? (:apensadas-truncado f)) "4 apensadas reais > teto 2 -> sinaliza")
        (is (= 2 (count (:emendas f))) "a LISTA de emendas continua cortada no teto injetado")
        (is (true? (:emendas-truncado f)) "4 emendas reais > teto 2 -> sinaliza")
        (is (= 2 (count (:pareceres f))) "a LISTA de pareceres continua cortada no teto injetado")
        (is (true? (:pareceres-truncado f)) "4 pareceres reais > teto 2 -> sinaliza")))))
