(ns oplenario.sessoes.assiduidade-db-test
  "INTEGRACAO (PG real) — Etapa 6 fatia 2: as leituras em LOTE do periodo
  (`db/sessao/listar-fechadas-no-periodo`, `db/presenca/presencas-correntes-das-sessoes`,
  `db/presenca/justificativas-das-sessoes`) e os DOIS tetos (dias do periodo, sessoes do recorte).

  Companheiro deste ns: `assiduidade-cruzado-test` fecha o CRUZADO controller-a-controller (o teste que
  importa de verdade — a leitura em lote e' identica ao que a chamada nominal, sessao a sessao, devolveria).
  Aqui o foco e' a MECANICA da leitura em lote: agrupamento correto, pre-semeadura de chave vazia, o instante
  POR SESSAO (I5) e os dois tetos fail-closed (I7)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.db.presenca :as presenca]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sessoes.logic :as logic])
  (:import (java.time Duration Instant LocalDate)))

(def ^:dynamic *repo-s* nil)
(def ^:dynamic *repo-c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo-s* (repo-sessoes/->RepoSessoesPg c nil)
                *repo-c* (repo-cadastros/->RepoCadastrosPg c)]
        (try (t) (finally (component/stop c)))))))

;; ---------- seeds (produtores REAIS) ----------

(defn- casa! [ente]
  (let [leg (random-uuid)]
    (repo-cadastros/criar-legislatura! *repo-c* ente
      {:id leg :ente-id ente :numero 1 :ano-inicio 2025 :ano-fim 2028 :vigente true})
    leg))

(defn- vereador! [ente nome]
  (let [id (random-uuid)]
    (repo-cadastros/criar-vereador! *repo-c* ente {:id id :ente-id ente :nome nome
                                                   :nome-parlamentar nil :identidade-id nil})
    id))

(defn- em ^Instant [^LocalDate d] (-> d (.atStartOfDay tempo/zona-civil-padrao) .toInstant))

(defn- sessao-nao-realizada!
  "Sessao FECHADA com data TOTALMENTE controlavel: agendada -> nao_realizada DIRETO (nunca passa por
  'aberta', entao `aberta_em` fica NULL e a data de referencia sai de `agendada_para`, sob controle do
  teste — nao do `now()` do banco)."
  [ente leg tipo ^LocalDate data & [motivo]]
  (repo-sessoes/transacao *repo-s* ente
    (fn [tx]
      (let [{:keys [id]} (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id leg
                                              :tipo-sessao tipo :agendada-para (em data)})]
        (sessao/transicionar! tx {:id id :ente-id ente :para "nao_realizada" :lock-version 0
                                   :motivo (or motivo "sem quorum")})
        id))))

(defn- sessao-encerrada-hoje!
  "Sessao FECHADA de verdade (agendada -> aberta -> encerrada): `aberta_em`/`encerrada_em` saem do `now()`
  do banco — fora do controle do teste, mas necessarios quando o proprio ramo 'encerrada' e' o que se testa."
  [ente leg tipo]
  (repo-sessoes/transacao *repo-s* ente
    (fn [tx]
      (let [{:keys [id]} (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id leg
                                              :tipo-sessao tipo :agendada-para (Instant/now)})]
        (sessao/transicionar! tx {:id id :ente-id ente :para "aberta" :lock-version 0})
        (sessao/transicionar! tx {:id id :ente-id ente :para "encerrada" :lock-version 1})
        id))))

(defn- ev! [tx ente sid vid tipo modalidade quando]
  (presenca/registrar-evento! tx {:id (random-uuid) :ente-id ente :sessao-id sid :vereador-id vid
                                  :tipo tipo :modalidade modalidade :fonte "manual_secretaria"
                                  :ocorrido-em quando}))

;; ---------- listar-fechadas-no-periodo ----------

(deftest so-fechadas-entram-agendada-fica-fora
  (let [ente (random-uuid) leg (casa! ente)
        hoje (tempo/hoje-de (Instant/now) tempo/zona-civil-padrao)
        fechada (sessao-nao-realizada! ente leg "ordinaria" hoje)
        _agendada (repo-sessoes/transacao *repo-s* ente
                    (fn [tx] (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id leg
                                                       :tipo-sessao "ordinaria" :agendada-para (em hoje)}))))
        linhas (repo-sessoes/transacao *repo-s* ente
                 (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de hoje :ate hoje})))]
    (is (= #{fechada} (set (map :id linhas)))
        "so' a sessao FECHADA aparece — a agendada, com a mesma data, fica de fora")))

(deftest filtro-de-tipo-e-aplicado
  (let [ente (random-uuid) leg (casa! ente)
        hoje (tempo/hoje-de (Instant/now) tempo/zona-civil-padrao)
        ord (sessao-nao-realizada! ente leg "ordinaria" hoje)
        _extra (sessao-nao-realizada! ente leg "extraordinaria" hoje)
        so-ord (repo-sessoes/transacao *repo-s* ente
                 (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de hoje :ate hoje :tipos ["ordinaria"]})))
        todas (repo-sessoes/transacao *repo-s* ente
                (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de hoje :ate hoje})))]
    (is (= [ord] (mapv :id so-ord)))
    (is (= 2 (count todas)) "sem filtro de tipo, os dois tipos aparecem")))

(deftest bordas-do-periodo-sao-inclusivas-e-exatas
  (let [ente (random-uuid) leg (casa! ente)
        hoje (LocalDate/of 2026 6 20)
        antes (sessao-nao-realizada! ente leg "ordinaria" (.minusDays hoje 6))
        dentro-inicio (sessao-nao-realizada! ente leg "ordinaria" (.minusDays hoje 5))
        dentro-fim (sessao-nao-realizada! ente leg "ordinaria" hoje)
        depois (sessao-nao-realizada! ente leg "ordinaria" (.plusDays hoje 1))
        linhas (repo-sessoes/transacao *repo-s* ente
                 (fn [tx] (sessao/listar-fechadas-no-periodo tx ente
                            {:de (.minusDays hoje 5) :ate hoje})))]
    (is (= #{dentro-inicio dentro-fim} (set (map :id linhas))))
    (is (not (contains? (set (map :id linhas)) antes)))
    (is (not (contains? (set (map :id linhas)) depois)))))

(deftest cada-linha-carrega-a-data-de-referencia-ja-calculada
  (let [ente (random-uuid) leg (casa! ente)
        d (LocalDate/of 2026 6 20)
        sid (sessao-nao-realizada! ente leg "ordinaria" d)
        linhas (repo-sessoes/transacao *repo-s* ente
                 (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de d :ate d})))]
    (is (= d (:data-de-referencia (first linhas))))))

(deftest periodo-acima-do-teto-de-dias-lanca-fail-closed
  (let [ente (random-uuid)
        hoje (LocalDate/of 2026 6 20)
        de (.minusDays hoje 367)
        erro (try (repo-sessoes/transacao *repo-s* ente
                    (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de de :ate hoje})))
                  nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? erro) "367 dias estoura o teto de 366 — a rejeicao roda ANTES de qualquer query")
    (is (= :limite/periodo-excedido (:tipo (ex-data erro))))
    (is (= 368 (:medido (ex-data erro))))
    (is (= 366 (:teto (ex-data erro))))))

(deftest sessoes-acima-do-teto-do-recorte-lanca-fail-closed-e-o-driver-para-antes
  (let [ente (random-uuid) leg (casa! ente)
        hoje (tempo/hoje-de (Instant/now) tempo/zona-civil-padrao)]
    (dotimes [n 3] (sessao-nao-realizada! ente leg "ordinaria" hoje (str "motivo " n)))
    (is (= 3 (count (repo-sessoes/transacao *repo-s* ente
                      (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de hoje :ate hoje})))))
        "sanidade: as 3 sessoes existem e aparecem sem teto rebaixado")
    (with-redefs [logic/teto-de-sessoes-do-periodo-de-assiduidade 2]
      (let [erro (try (repo-sessoes/transacao *repo-s* ente
                        (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de hoje :ate hoje})))
                      nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? erro) "resultado acima do teto lanca, nunca devolve pagina truncada")
        (is (= :limite/sessoes-excedido (:tipo (ex-data erro))))
        (is (= 3 (:medido-ao-menos (ex-data erro)))
            "3 = o driver PAROU de materializar ali (:max-rows = teto+1 = 3)")
        (is (= 2 (:teto (ex-data erro))))))))

;; ---------- presencas-correntes-das-sessoes ----------

(deftest lote-de-presenca-e-identico-ao-singular-para-uma-sessao
  (let [ente (random-uuid) leg (casa! ente)
        v (vereador! ente "Ana")
        sid (sessao-encerrada-hoje! ente leg "ordinaria")]
    (repo-sessoes/transacao *repo-s* ente
      (fn [tx]
        (let [agora (Instant/now)]
          (ev! tx ente sid v "entrada" "plenario" (.minusSeconds agora 60))
          (ev! tx ente sid v "saida" "plenario" (.minusSeconds agora 30)))))
    (let [encerrada-em (:encerrada-em (repo-sessoes/buscar-sessao *repo-s* ente sid))
          singular (repo-sessoes/transacao *repo-s* ente
                     (fn [tx] (presenca/presenca-corrente tx ente sid encerrada-em)))
          lote (repo-sessoes/transacao *repo-s* ente
                 (fn [tx] (presenca/presencas-correntes-das-sessoes tx ente [[sid encerrada-em]])))]
      ;; o lote projeta `:sessao-id` a mais (precisa dele p/ agrupar; o singular nao, so' ha uma sessao) — a
      ;; comparacao e' de SEQUENCIA sobre os campos que os DOIS tem, nao do mapa inteiro.
      (is (= singular (mapv #(dissoc % :sessao-id) (get lote sid)))
          "a leitura em LOTE de UMA sessao devolve a MESMA sequencia que o singular"))))

(deftest lote-de-presenca-preseeda-chave-vazia-para-sessao-sem-evento
  (let [ente (random-uuid) leg (casa! ente)
        sid (sessao-encerrada-hoje! ente leg "ordinaria")
        encerrada-em (:encerrada-em (repo-sessoes/buscar-sessao *repo-s* ente sid))
        lote (repo-sessoes/transacao *repo-s* ente
               (fn [tx] (presenca/presencas-correntes-das-sessoes tx ente [[sid encerrada-em]])))]
    (is (= {sid []} lote) "chave PRESENTE com vetor vazio — nunca chave ausente")))

(deftest instante-e-por-sessao-nao-um-global-do-lote
  (let [ente (random-uuid) leg (casa! ente)
        v (vereador! ente "Bruno")
        s1 (sessao-encerrada-hoje! ente leg "ordinaria")
        t1 (:encerrada-em (repo-sessoes/buscar-sessao *repo-s* ente s1))
        s2 (sessao-encerrada-hoje! ente leg "extraordinaria")
        t2 (:encerrada-em (repo-sessoes/buscar-sessao *repo-s* ente s2))]
    (is (.isBefore ^Instant t1 ^Instant t2) "sanidade: s1 fechou antes de s2")
    ;; evento NA SESSAO s1, no PONTO MEDIO exato entre t1 e t2 — DEPOIS do fechamento de s1, ANTES do de s2.
    ;; NAO um offset fixo: a versao anterior usava `+200ms`, e a folga real entre duas transacoes sequenciais
    ;; no Postgres aquecido e' MENOR que isso — o evento caia depois dos DOIS instantes, e o comentario logo
    ;; acima afirmava detectar uma mutacao (instante global = t2) que este teste, com o offset fixo, NAO
    ;; detectava. O ponto medio esta' matematicamente garantido entre t1 e t2 quando t1 < t2 (ja' checado
    ;; acima), que e' a MESMA correcao que o teste do cruzado recebeu e que nao tinha sido propagada aqui.
    (let [meio (.plus ^Instant t1 (.dividedBy (Duration/between t1 t2) 2))]
      (repo-sessoes/transacao *repo-s* ente (fn [tx] (ev! tx ente s1 v "entrada" "plenario" meio))))
    (let [lote (repo-sessoes/transacao *repo-s* ente
                 (fn [tx] (presenca/presencas-correntes-das-sessoes tx ente [[s1 t1] [s2 t2]])))]
      (is (empty? (get lote s1)) "evento pos-fechamento de s1 NAO aparece na leitura de s1")
      (is (empty? (get lote s2)) "s2 nunca teve evento nenhum"))))

;; ---------- os TETOS das duas leituras em lote (I7) ----------

(deftest lote-de-presenca-acima-do-teto-de-linhas-lanca-fail-closed
  ;; `presenca_evento.vereador_id` NAO tem FK e a escrita nao valida mandato: o `DISTINCT ON` e' limitado
  ;; pelos ids que aparecem nos EVENTOS, nao pelos do roster. Um acervo migrado sujo materializa 400 x N.
  (let [ente (random-uuid) leg (casa! ente)
        sid (sessao-encerrada-hoje! ente leg "ordinaria")
        encerrada-em (:encerrada-em (repo-sessoes/buscar-sessao *repo-s* ente sid))]
    (repo-sessoes/transacao *repo-s* ente
      (fn [tx]
        (dotimes [n 3]
          (ev! tx ente sid (random-uuid) "entrada" "plenario" (.minusSeconds encerrada-em (+ 10 n))))))
    (is (= 3 (count (get (repo-sessoes/transacao *repo-s* ente
                           (fn [tx] (presenca/presencas-correntes-das-sessoes tx ente [[sid encerrada-em]])))
                         sid)))
        "sanidade: os 3 ids sem mandato aparecem, sem teto rebaixado")
    (with-redefs [logic/teto-de-linhas-de-lote-de-presenca 2]
      (let [erro (try (repo-sessoes/transacao *repo-s* ente
                        (fn [tx] (presenca/presencas-correntes-das-sessoes tx ente [[sid encerrada-em]])))
                      nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? erro) "acima do teto LANCA — nunca devolve pagina truncada parecendo total")
        (is (= :limite/linhas-excedido (:tipo (ex-data erro))))
        (is (= 3 (:medido-ao-menos (ex-data erro))) "3 = o driver PAROU ali (:max-rows = teto+1)")
        (is (= 2 (:teto (ex-data erro))))))))

(deftest lote-de-justificativas-acima-do-teto-de-linhas-lanca-fail-closed
  (let [ente (random-uuid) leg (casa! ente)
        s1 (sessao-encerrada-hoje! ente leg "ordinaria")
        s2 (sessao-encerrada-hoje! ente leg "ordinaria")
        s3 (sessao-encerrada-hoje! ente leg "ordinaria")]
    (repo-sessoes/transacao *repo-s* ente
      (fn [tx]
        (doseq [sid [s1 s2 s3]]
          (presenca/criar-justificativa! tx {:id (random-uuid) :ente-id ente :sessao-id sid
                                             :vereador-id (random-uuid) :motivo "doenca"}))))
    (with-redefs [logic/teto-de-linhas-de-lote-de-presenca 2]
      (let [erro (try (repo-sessoes/transacao *repo-s* ente
                        (fn [tx] (presenca/justificativas-das-sessoes tx ente [s1 s2 s3])))
                      nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? erro))
        (is (= :limite/linhas-excedido (:tipo (ex-data erro))))
        (is (= 3 (:medido-ao-menos (ex-data erro))))))))

(deftest lote-de-justificativas-nao-atravessa-o-MOTIVO-para-fora-do-modulo
  ;; `motivo` e' onde o vereador escreve POR QUE faltou — na pratica dado de saude (LGPD art. 11). O unico
  ;; consumidor da leitura em lote e' `logic/estado-de-presenca`, que le' `:estado`. Copiar a projecao da
  ;; leitura irma fazia `motivo`/`decidido-por`/`decidido-em`/`lock-version` atravessarem a fronteira sem
  ;; sair em lugar nenhum do payload.
  (let [ente (random-uuid) leg (casa! ente)
        v (vereador! ente "Carla")
        sid (sessao-encerrada-hoje! ente leg "ordinaria")]
    (repo-sessoes/transacao *repo-s* ente
      (fn [tx] (presenca/criar-justificativa! tx {:id (random-uuid) :ente-id ente :sessao-id sid
                                                  :vereador-id v :motivo "cirurgia cardiaca"})))
    (let [linha (first (get (repo-sessoes/transacao *repo-s* ente
                              (fn [tx] (presenca/justificativas-das-sessoes tx ente [sid])))
                            sid))]
      (is (= #{:sessao-id :vereador-id :estado} (set (keys linha)))
          "projecao MINIMA — `motivo` nao sai do banco nesta leitura")
      (is (not (contains? linha :motivo))))))

;; ---------- justificativas-das-sessoes ----------

(deftest lote-de-justificativas-agrupa-por-sessao-e-preseeda-vazio
  (let [ente (random-uuid) leg (casa! ente)
        v1 (vereador! ente "Carla")
        s1 (sessao-encerrada-hoje! ente leg "ordinaria")
        s2 (sessao-encerrada-hoje! ente leg "ordinaria")]
    (repo-sessoes/transacao *repo-s* ente
      (fn [tx] (presenca/criar-justificativa! tx {:id (random-uuid) :ente-id ente :sessao-id s1
                                                   :vereador-id v1 :motivo "doenca"})))
    (let [lote (repo-sessoes/transacao *repo-s* ente
                 (fn [tx] (presenca/justificativas-das-sessoes tx ente [s1 s2])))]
      (is (= 1 (count (get lote s1))))
      (is (= [] (get lote s2)) "sessao sem justificativa tem chave PRESENTE com vetor vazio"))))

(deftest lista-de-sessoes-vazia-devolve-mapa-vazio-sem-tocar-o-banco
  (is (= {} (repo-sessoes/transacao *repo-s* (random-uuid)
              (fn [tx] (presenca/presencas-correntes-das-sessoes tx (random-uuid) [])))))
  (is (= {} (repo-sessoes/transacao *repo-s* (random-uuid)
              (fn [tx] (presenca/justificativas-das-sessoes tx (random-uuid) []))))))

;; ---------- a DATA DE REFERENCIA: filtro e rotulo saem da MESMA regra ----------

(deftest filtro-e-rotulo-seguem-aberta-em-quando-os-dois-marcos-caem-em-DIAS-CIVIS-DIFERENTES
  ;; O cenario real: sessao agendada para o dia 10, adiada, aberta no dia 17. Ate' a revisao desta fatia a
  ;; regra estava REDIGITADA em tres lugares (controller, COALESCE do filtro, rotulo em Clojure) — inverter
  ;; UMA das copias fazia a sessao ser FILTRADA por uma data e ROTULADA com a outra, e a suite inteira ficava
  ;; VERDE porque nenhuma fixture tinha `aberta_em` e `agendada_para` em dias civis diferentes.
  (let [ente (random-uuid) leg (casa! ente)
        hoje (tempo/hoje-de (Instant/now) tempo/zona-civil-padrao)
        agendada-para (.minusDays hoje 7)
        sid (repo-sessoes/transacao *repo-s* ente
              (fn [tx]
                (let [{:keys [id]} (sessao/agendar! tx {:id (random-uuid) :ente-id ente
                                                        :sessao-legislativa-id leg :tipo-sessao "ordinaria"
                                                        :agendada-para (em agendada-para)})]
                  ;; abre HOJE (aberta_em = now() do banco) e encerra
                  (sessao/transicionar! tx {:id id :ente-id ente :para "aberta" :lock-version 0})
                  (sessao/transicionar! tx {:id id :ente-id ente :para "encerrada" :lock-version 1})
                  id)))
        no-dia-de-abertura (repo-sessoes/transacao *repo-s* ente
                             (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de hoje :ate hoje})))
        no-dia-agendado (repo-sessoes/transacao *repo-s* ente
                          (fn [tx] (sessao/listar-fechadas-no-periodo
                                    tx ente {:de agendada-para :ate agendada-para})))]
    (is (contains? (set (map :id no-dia-de-abertura)) sid)
        "o RECORTE segue `aberta_em` — a Casa que efetivamente se reuniu")
    (is (not (contains? (set (map :id no-dia-agendado)) sid))
        "e NAO a data em que a sessao fora agendada e adiada")
    (is (= hoje (:data-de-referencia (first (filter #(= sid (:id %)) no-dia-de-abertura))))
        "o ROTULO e' a MESMA data do filtro — uma regra so'")))

;; ---------- o que o filtro EXCLUI e' contado, nao esquecido ----------

(deftest sessao-fechada-sem-nenhum-marco-de-data-e-CONTADA-em-vez-de-sumir
  (let [ente (random-uuid) leg (casa! ente)
        hoje (tempo/hoje-de (Instant/now) tempo/zona-civil-padrao)
        com-data (sessao-nao-realizada! ente leg "ordinaria" hoje)
        ;; `agendada_para` e' OPCIONAL no wire e nullable na coluna, e `agendada -> nao_realizada` nao exige
        ;; data: esta linha e' alcancavel pela API NORMAL, nao e' dado corrompido.
        _sem-data (repo-sessoes/transacao *repo-s* ente
                    (fn [tx]
                      (let [{:keys [id]} (sessao/agendar! tx {:id (random-uuid) :ente-id ente
                                                              :sessao-legislativa-id leg
                                                              :tipo-sessao "ordinaria" :agendada-para nil})]
                        (sessao/transicionar! tx {:id id :ente-id ente :para "nao_realizada" :lock-version 0
                                                  :motivo "sem quorum"})
                        id)))
        linhas (repo-sessoes/transacao *repo-s* ente
                 (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de hoje :ate hoje})))
        n (repo-sessoes/transacao *repo-s* ente
            (fn [tx] (sessao/contar-fechadas-sem-data-de-referencia tx ente {:de hoje :ate hoje})))]
    (is (= [com-data] (mapv :id linhas)) "a sessao sem data fica FORA do periodo (nao ha' onde posiciona-la)")
    (is (= 1 n) "mas e' CONTADA — o denominador de todos os vereadores nao encolhe em silencio")
    (is (= 0 (repo-sessoes/transacao *repo-s* ente
               (fn [tx] (sessao/contar-fechadas-sem-data-de-referencia
                         tx ente {:de hoje :ate hoje :tipos ["extraordinaria"]}))))
        "o contador respeita o MESMO filtro de tipo da listagem — senao contaria o que a listagem nem consideraria")))

;; ---------- `tipos` fail-closed nas DUAS camadas ----------

(deftest tipo-desconhecido-e-rejeitado-fail-closed-em-vez-de-devolver-apuracao-em-branco
  (let [ente (random-uuid)
        hoje (tempo/hoje-de (Instant/now) tempo/zona-civil-padrao)
        erro-de (fn [tipos]
                  (try (repo-sessoes/transacao *repo-s* ente
                         (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de hoje :ate hoje :tipos tipos})))
                       nil (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (is (nil? (erro-de nil)) "nil = todos os tipos, continua legitimo")
    (is (nil? (erro-de [])) "vazio idem")
    (is (nil? (erro-de ["ordinaria" "secreta"])) "tipos validos passam")
    (doseq [mau [["ordinária"] ["ordinaira"] ["Ordinaria"] [:ordinaria] [["1) OR (1=1"]] [nil]]]
      (is (= :validacao/invalido (:tipo (erro-de mau)))
          (str "tipo invalido rejeitado FAIL-CLOSED, nunca casando zero sessoes em silencio: " (pr-str mau))))))

(deftest data-que-nao-e-localdate-e-rejeitada-com-400-e-nao-com-500-opaco
  (let [ente (random-uuid)
        hoje (LocalDate/of 2026 6 20)
        erro-de (fn [de ate]
                  (try (repo-sessoes/transacao *repo-s* ente
                         (fn [tx] (sessao/listar-fechadas-no-periodo tx ente {:de de :ate ate})))
                       nil (catch Exception e (if (instance? clojure.lang.ExceptionInfo e) (ex-data e) {:tipo :cru}))))]
    (is (= :validacao/invalido (:tipo (erro-de nil hoje))))
    (is (= :validacao/invalido (:tipo (erro-de hoje nil))))
    (is (= :validacao/invalido (:tipo (erro-de (java.sql.Date/valueOf hoje) hoje))))
    (is (= :de (:campo (erro-de "2026-06-20" hoje))))))
