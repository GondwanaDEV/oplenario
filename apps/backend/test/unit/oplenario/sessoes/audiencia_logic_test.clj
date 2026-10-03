(ns oplenario.sessoes.audiencia-logic-test
  "UNIDADE (puro): ADR-0021 Parte A — o tipo `audiencia_publica` e as capabilities dele, a validacao da audiencia
  (finalidade e a referencia do quadrimestre), a da fala (entidade fora do individual), a maquina da inscricao, quando
  a inscricao cabe e quando a Mesa pode chamar, e o que o portal mostra (contagem sem desistentes, quem falou so'
  depois de encerrada)."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.sessoes.adapters.in.sessao :as adapters-in]
            [oplenario.sessoes.logic :as logic]
            [oplenario.sessoes.logic.audiencia :as aud])
  (:import (java.time Instant)))

(defn- invalido? [f]
  (try (f) false (catch clojure.lang.ExceptionInfo e (= :validacao/invalido (:tipo (ex-data e))))))

(deftest audiencia-e-tipo-de-sessao-com-capabilities-proprias
  (is (contains? logic/tipos-sessao "audiencia_publica"))
  (is (= {:delibera false :transmite-publica true :gera-ata-regimental true :permite-voto-secreto false
          :permite-modalidade-remota false :exige-quorum false :aceita-inscricao-cidadao true}
         (logic/capabilities-default "audiencia_publica")))
  (testing "nos tipos que ja' existiam, exige-quorum = delibera e ninguem aceita inscricao de cidadao"
    (doseq [t ["ordinaria" "extraordinaria" "solene" "secreta" "especial"]]
      (let [c (logic/capabilities-default t)]
        (is (= (:delibera c) (:exige-quorum c)) t)
        (is (false? (:aceita-inscricao-cidadao c)) t))))
  (is (= "audiência pública" (get logic/rotulo-do-tipo "audiencia_publica"))))

(deftest a-audiencia-nao-conta-na-assiduidade
  (is (not (contains? logic/tipos-de-assiduidade "audiencia_publica")))
  (is (invalido? #(logic/validar-tipos-de-assiduidade! ["audiencia_publica"])))
  (is (nil? (logic/validar-tipos-de-assiduidade! ["ordinaria" "especial"]))))

(deftest o-aviso-de-pauta-diz-audiencia-publica
  (let [[a] (logic/avisos-de-pauta-publicada {:id (random-uuid) :tipo-sessao "audiencia_publica" :numero-sequencial 2}
                                             {:id (random-uuid) :numero-versao 1 :tipo-versao "publicacao_inicial"}
                                             [(random-uuid)])]
    (is (= "Pauta publicada: audiência pública nº 2" (:assunto a)))))

(deftest validar-audiencia
  (let [base {:tema "Saúde básica" :finalidade "tematica"}]
    (is (= base (aud/validar-audiencia! base)))
    (is (invalido? #(aud/validar-audiencia! (assoc base :tema " "))))
    (is (invalido? #(aud/validar-audiencia! (assoc base :tema (apply str (repeat 201 "x"))))))
    (is (invalido? #(aud/validar-audiencia! (assoc base :finalidade "outra"))))
    (testing "a referencia do quadrimestre e' SO' e OBRIGATORIA nas metas fiscais"
      (is (invalido? #(aud/validar-audiencia! (assoc base :finalidade "metas_fiscais"))))
      (is (invalido? #(aud/validar-audiencia! (assoc base :finalidade "metas_fiscais" :referencia "2026-Q4"))))
      (is (invalido? #(aud/validar-audiencia! (assoc base :finalidade "metas_fiscais" :referencia "26-Q1"))))
      (is (aud/validar-audiencia! (assoc base :finalidade "metas_fiscais" :referencia "2026-Q3")))
      (is (invalido? #(aud/validar-audiencia! (assoc base :finalidade "loa" :referencia "2026-Q1")))))
    (testing "o tempo de fala entre 60 e 1800 segundos"
      (is (aud/validar-audiencia! (assoc base :tempo-fala-segundos 60)))
      (is (invalido? #(aud/validar-audiencia! (assoc base :tempo-fala-segundos 59))))
      (is (invalido? #(aud/validar-audiencia! (assoc base :tempo-fala-segundos 1801)))))))

(deftest validar-fala
  (is (= {:fala-como "individual" :entidade nil :tema "Postos"}
         (aud/validar-fala! {:fala-como "individual" :entidade "ignorada" :tema "Postos"}))
      "no individual nao ha' entidade")
  (is (invalido? #(aud/validar-fala! {:fala-como "entidade" :tema "Postos"})) "fora do individual, diz qual")
  (is (aud/validar-fala! {:fala-como "conselho_movimento" :entidade "Conselho de Saúde" :tema "Postos"}))
  (is (invalido? #(aud/validar-fala! {:fala-como "individual" :tema ""})))
  (is (invalido? #(aud/validar-fala! {:fala-como "outro" :tema "x"}))))

(deftest maquina-da-inscricao
  (is (aud/transicao-valida? "inscrita" "falando"))
  (is (aud/transicao-valida? "inscrita" "ausente"))
  (is (aud/transicao-valida? "inscrita" "desistiu"))
  (is (aud/transicao-valida? "falando" "falou"))
  (is (not (aud/transicao-valida? "falando" "desistiu")))
  (doseq [t aud/estados-terminais-inscricao p aud/estados-inscricao]
    (is (not (aud/transicao-valida? t p)) (str t " e' terminal"))))

(deftest protocolo
  (is (= "AUD-2026-000007" (aud/protocolo 2026 7)))
  (is (= "inscricao_audiencia:2026" (aud/escopo-protocolo 2026)))
  (is (= 2027 (logic/ano-civil (Instant/parse "2027-01-01T03:30:00Z"))) "o ano civil e' o de Fortaleza")
  (is (= 2026 (logic/ano-civil (Instant/parse "2027-01-01T02:30:00Z")))))

(def ^:private audiencia-aberta {:tipo-sessao "audiencia_publica" :estado "agendada" :aceita-inscricao-cidadao true})

(deftest quando-a-inscricao-cabe
  (is (nil? (aud/motivo-recusa-inscricao audiencia-aberta {:inscricoes-abertas true} true)))
  (is (some? (aud/motivo-recusa-inscricao audiencia-aberta {:inscricoes-abertas false} true))
      "o portal respeita as inscricoes fechadas")
  (is (nil? (aud/motivo-recusa-inscricao audiencia-aberta {:inscricoes-abertas false} false))
      "a Mesa inscreve quem chegou mesmo com o portal fechado")
  (is (some? (aud/motivo-recusa-inscricao (assoc audiencia-aberta :estado "encerrada") {:inscricoes-abertas true} false)))
  (is (some? (aud/motivo-recusa-inscricao {:tipo-sessao "ordinaria" :estado "agendada"} {:inscricoes-abertas true} true)))
  (is (aud/inscricoes-abertas-efetivas? audiencia-aberta {:inscricoes-abertas true}))
  (is (not (aud/inscricoes-abertas-efetivas? (assoc audiencia-aberta :estado "encerrada") {:inscricoes-abertas true}))))

(deftest quando-a-mesa-chama
  (let [aberta (assoc audiencia-aberta :estado "aberta") inscrita {:estado "inscrita"}]
    (is (nil? (aud/motivo-recusa-chamada aberta inscrita false)))
    (is (some? (aud/motivo-recusa-chamada aberta inscrita true)) "uma fala por vez")
    (is (some? (aud/motivo-recusa-chamada audiencia-aberta inscrita false)) "so' com a audiencia aberta")
    (is (some? (aud/motivo-recusa-chamada aberta {:estado "ausente"} false)))))

(deftest o-que-o-portal-mostra
  (let [ins [{:ordem 2 :estado "falou" :nome "B"} {:ordem 1 :estado "falou" :nome "A"}
             {:ordem 3 :estado "desistiu" :nome "C"} {:ordem 4 :estado "ausente" :nome "D"}]]
    (is (= 3 (aud/inscritos-publicos ins)) "sem os desistentes")
    (is (= [] (aud/falaram {:estado "aberta"} ins)) "antes de encerrada, ninguem nominal")
    (is (= ["A" "B"] (map :nome (aud/falaram {:estado "encerrada"} ins))) "so' quem falou, na ordem")))

(deftest agendar-exige-o-bloco-sse-audiencia
  (let [ator {:ente-id (random-uuid) :identidade-id (random-uuid)}
        base {"sessao-legislativa-id" (str (random-uuid)) "agendada-para" "2026-10-10T12:00:00Z"}
        bloco {"comissao-id" (str (random-uuid)) "tema" " Saúde " "finalidade" "metas_fiscais" "referencia" "2026-Q2"}]
    (is (invalido? #(adapters-in/agendar-sessao->dominio ator (assoc base "tipo-sessao" "audiencia_publica"))))
    (is (invalido? #(adapters-in/agendar-sessao->dominio ator (assoc base "tipo-sessao" "ordinaria" "audiencia" bloco))))
    (let [m (adapters-in/agendar-sessao->dominio ator (assoc base "tipo-sessao" "audiencia_publica" "audiencia" bloco))]
      (is (= "Saúde" (get-in m [:audiencia :tema])) "o tema chega aparado")
      (is (uuid? (get-in m [:audiencia :comissao-id])))
      (is (= "2026-Q2" (get-in m [:audiencia :referencia]))))
    (is (nil? (:audiencia (adapters-in/agendar-sessao->dominio ator (assoc base "tipo-sessao" "ordinaria")))))))
