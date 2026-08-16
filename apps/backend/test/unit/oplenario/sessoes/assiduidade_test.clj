(ns oplenario.sessoes.assiduidade-test
  "UNIT (sem Postgres) — Etapa 6 fatia 2: a subquery em LOTE (`ultimos-eventos-por-sessao-e-vereador-q`), o
  teto do periodo (`validar-periodo-assiduidade!`) e o coracao PURO da apuracao (`apurar-assiduidade`).

  A FIXTURE E' O TESTE (mesma disciplina da revisao da Fatia 1): cada `deftest` abaixo exercita um ramo que
  uma fixture pobre deixaria verde por acidente — vereador empossado no meio do periodo, licenciado, presenca
  sem assento, sessao nao_realizada, sessao secreta, e as 4 classificacoes de falta separadas."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.sessoes.logic :as logic])
  (:import (java.time Instant LocalDate)))

;; ---------- a subquery em LOTE — estrutura (o dado real e' pinado na integracao) ----------

(deftest ultimos-eventos-por-sessao-e-vereador-q-INVOCA-a-query-singular
  (let [s1 (random-uuid) s2 (random-uuid)
        i1 (Instant/parse "2026-06-20T10:00:00Z") i2 (Instant/parse "2026-06-21T10:00:00Z")
        ente (random-uuid)
        sem (logic/ultimos-eventos-por-sessao-e-vereador-q {:sessoes-e-instantes [[s1 i1] [s2 i2]]})
        com (logic/ultimos-eventos-por-sessao-e-vereador-q {:sessoes-e-instantes [[s1 i1] [s2 i2]] :ente-id ente})
        lateral-de (fn [q] (second (first (first (:join q)))))]
    (testing "o LATERAL e' LITERALMENTE `ultimos-eventos-por-vereador-q` — nao uma transcricao dela (I2)"
      (is (= [:lateral (logic/ultimos-eventos-por-vereador-q
                        {:sessao-id :si.sessao_id :instante :si.instante})]
             (first (first (:join sem)))))
      (is (= [:lateral (logic/ultimos-eventos-por-vereador-q
                        {:sessao-id :si.sessao_id :instante :si.instante :ente-id ente})]
             (first (first (:join com))))
          "ente-id atravessa INTACTO para a subquery singular"))
    (testing "NENHUM order-by externo — era ele o Sort que derramava dezenas de MB em disco"
      (is (nil? (:order-by sem)))
      (is (nil? (:select-distinct-on sem)) "o DISTINCT ON mora na subquery singular, nao aqui"))
    (testing "a tabela VALUES declara os OIDs — sem cast o Postgres resolve as colunas como text"
      (is (= [[[:cast s1 :uuid] [:cast i1 :timestamptz]]
              [[:cast s2 :uuid] [:cast i2 :timestamptz]]]
             (:values (first (first (:from sem)))))))
    (testing "o SELECT externo traz sessao_id de `si` + a projecao da subquery, qualificada por `u`"
      (is (= [:si.sessao_id :u.vereador_id :u.tipo :u.modalidade] (:select sem)))
      (is (= [:si.sessao_id :u.vereador_id :u.fonte]
             (:select (logic/ultimos-eventos-por-sessao-e-vereador-q
                       {:sessoes-e-instantes [[s1 i1]] :projecao [:vereador_id :fonte]})))))
    (testing "a projecao pedida atravessa para a subquery singular"
      (is (= (logic/ultimos-eventos-por-vereador-q
              {:sessao-id :si.sessao_id :instante :si.instante :projecao [:vereador_id :fonte]})
             (lateral-de (logic/ultimos-eventos-por-sessao-e-vereador-q
                          {:sessoes-e-instantes [[s1 i1]] :projecao [:vereador_id :fonte]})))))))

;; ---------- o NUMERADOR do quorum tem uma fonte so' (o achado CRITICO desta revisao) ----------

(deftest numerador-do-quorum-e-comparecimentos-mudam-JUNTOS
  ;; Antes desta correcao, `contar-quorum` consultava `estados-presentes` e `somar-linha-na-assiduidade`
  ;; consultava `estados-chamada-presentes` — dois vars distintos, MESMO conteudo, ambas as docstrings
  ;; reivindicando ser a fonte unica do numerador. Uma terceira categoria positiva (presenca por
  ;; videoconferencia, ja' prevista no dominio) acrescentada a UM dos dois faria a chamada mostrar o vereador
  ;; PRESENTE e o CSV publicar `:comparecimentos 0`, sem nenhum teste vermelho. Este teste acrescenta um
  ;; estado positivo SINTETICO a UNICA fonte que restou e prova que os DOIS numeros se movem juntos.
  (let [v (random-uuid)
        linha {:vereador-id v :estado :presente-videoconferencia :sem-assento false}
        somar #(#'logic/somar-linha-na-assiduidade (#'logic/agregado-vazio-de-assiduidade) %)]
    (is (= 0 (:presentes-total (logic/contar-quorum [linha])))
        "sanidade: sem o estado sintetico na fonte, o quorum nao o conta")
    (is (= 0 (:comparecimentos (somar linha)))
        "sanidade: e a apuracao tambem nao")
    (with-redefs [logic/estados-presentes (conj logic/estados-presentes :presente-videoconferencia)]
      (is (= 1 (:presentes-total (logic/contar-quorum [linha])))
          "o numerador do QUORUM passa a contar o estado novo")
      (is (= 1 (:comparecimentos (somar linha)))
          "e `:comparecimentos` muda JUNTO — uma fonte so', nunca duas"))))

;; ---------- o teto do periodo ----------

(deftest validar-periodo-assiduidade-rejeita-de-posterior-a-ate
  (let [hoje (LocalDate/of 2026 6 20)
        erro (try (logic/validar-periodo-assiduidade! hoje (.minusDays hoje 1)) nil
                  (catch clojure.lang.ExceptionInfo e e))]
    (is (some? erro))
    (is (= :validacao/invalido (:tipo (ex-data erro))))))

(deftest validar-periodo-assiduidade-366-dias-passa-367-estoura
  (let [ate (LocalDate/of 2026 6 20)]
    (is (nil? (logic/validar-periodo-assiduidade! (.minusDays ate 365) ate))
        "366 dias (365+1, inclusivo) = o periodo maximo, PASSA")
    (let [de (.minusDays ate 366)
          erro (try (logic/validar-periodo-assiduidade! de ate) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? erro) "367 dias estoura")
      (is (= :limite/periodo-excedido (:tipo (ex-data erro))))
      (is (= 367 (:medido (ex-data erro))))
      (is (= 366 (:teto (ex-data erro)))))))

;; ---------- fixtures puros de `apurar-assiduidade` ----------

(defn- sessao-fechada
  [{:keys [id numero tipo estado data-de-referencia transmite-publica]
    :or {numero 1 tipo "ordinaria" estado "encerrada" transmite-publica true}}]
  {:id id :numero numero :tipo tipo :estado estado :data-de-referencia data-de-referencia
   :transmite-publica transmite-publica})

(defn- roster-linha
  [vid nome & [{:keys [nome-parlamentar partido estado-mandato cargo-mesa]
                :or {partido "PX" estado-mandato "vigente"}}]]
  {:vereador-id vid :nome nome :nome-parlamentar nome-parlamentar :partido partido
   :estado-mandato estado-mandato :cargo-mesa cargo-mesa})

(defn- presenca [vid tipo modalidade] {:vereador-id vid :tipo tipo :modalidade modalidade})
(defn- justificativa [vid estado] {:vereador-id vid :estado estado})

(defn- por-vereador-map [resultado] (into {} (map (juxt :vereador-id identity)) (:por-vereador resultado)))
(defn- detalhe-por-vereador [resultado sid]
  (into {} (comp (filter #(= sid (:sessao-id %))) (map (juxt :vereador-id :estado))) (:detalhe resultado)))

(defn- apurar
  "Chama `apurar-assiduidade` com o `contexto` obrigatorio; `:sessoes-sem-data-de-referencia` default 0 (as
  fixtures montam a lista de sessoes a mao, entao nao ha' sessao excluida por falta de data). O teste que
  exercita o contador nao-zero passa o valor explicitamente."
  ([sessoes rosters presencas justs] (apurar sessoes rosters presencas justs 0))
  ([sessoes rosters presencas justs sem-data]
   (logic/apurar-assiduidade sessoes rosters presencas justs
                             {:sessoes-sem-data-de-referencia sem-data})))

;; ---------- PUROS obrigatorios do brief ----------
;; NOTA sobre as fixtures: todo mapa de entrada e' passado COMPLETO (chave para cada data e para cada sessao,
;; com vetor vazio quando nao ha' conteudo). Nao e' zelo — `apurar-assiduidade` agora LANCA em chave ausente,
;; e as fixtures anteriores passavam `{}` justamente onde o fail-open morava: um `{}` como `rosters-por-data`
;; derivava a sessao com roster vazio, quem faltou SUMIA e o percentual inflava ate' 100%, com HTTP 200.

(deftest as-quatro-classificacoes-saem-separadas-e-pendente-nunca-vira-ausente
  (let [s1 (random-uuid) ana (random-uuid) bruno (random-uuid) carla (random-uuid) dede (random-uuid)
        d (LocalDate/of 2026 6 1)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d})]
        roster {d [(roster-linha ana "Ana") (roster-linha bruno "Bruno") (roster-linha carla "Carla")
                   (roster-linha dede "Dede")]}
        presencas {s1 [(presenca ana "entrada" "plenario")]}
        justs {s1 [(justificativa bruno "aprovada") (justificativa carla "pendente")]}
        r (apurar sessoes roster presencas justs)
        por-ver (por-vereador-map r)
        detalhe (detalhe-por-vereador r s1)]
    (is (= :presente-plenario (detalhe ana)))
    (is (= :ausente-justificado (detalhe bruno)))
    (is (= :ausente-justificativa-pendente (detalhe carla)))
    (is (= :ausente (detalhe dede)))
    (is (= 1 (:comparecimentos (por-ver ana))))
    (is (= 1 (:ausencias-justificadas (por-ver bruno))))
    (is (= 1 (:ausencias-com-justificativa-pendente (por-ver carla))))
    (is (= 0 (:ausencias-injustificadas (por-ver carla)))
        "I4: pendente NUNCA conta como injustificada — a Mesa ainda nao decidiu")
    (is (= 1 (:ausencias-injustificadas (por-ver dede))))))

(deftest licenciado-fica-fora-do-denominador-e-aparece-em-sessoes-licenciado
  (let [s1 (random-uuid) v (random-uuid) d (LocalDate/of 2026 6 1)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d})]
        roster {d [(roster-linha v "Vera" {:estado-mandato "licenciado"})]}
        r (apurar sessoes roster {s1 []} {s1 []})
        agg (first (:por-vereador r))]
    (is (= 0 (:sessoes-computadas agg)))
    (is (= 1 (:sessoes-licenciado agg)))
    (is (nil? (:percentual agg)))))

(deftest denominador-zero-nunca-produz-zero-por-cento-e-sem-assento-conta-so-no-comparecimento
  (let [s1 (random-uuid) orfao (random-uuid) d (LocalDate/of 2026 6 1)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d})]
        presencas {s1 [(presenca orfao "entrada" "plenario")]}
        r (apurar sessoes {d []} presencas {s1 []})
        agg (first (:por-vereador r))
        detalhe (first (:detalhe r))]
    (is (= 0 (:sessoes-computadas agg)) "sem roster nenhum, a linha e' SEM ASSENTO — nao cria cadeira")
    (is (= 1 (:comparecimentos agg)) "mas CONTA no comparecimento — o fato observado")
    (is (nil? (:percentual agg)) "denominador zero -> percentual NIL, nunca 0")
    (is (= :presente-plenario (:estado detalhe)))))

(deftest vereador-empossado-no-meio-do-periodo-tem-denominador-menor-que-a-casa
  (let [s1 (random-uuid) s2 (random-uuid) ana (random-uuid) fabio (random-uuid)
        d1 (LocalDate/of 2026 5 1) d2 (LocalDate/of 2026 6 1)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d1})
                 (sessao-fechada {:id s2 :numero 2 :data-de-referencia d2})]
        roster {d1 [(roster-linha ana "Ana")]
                d2 [(roster-linha ana "Ana") (roster-linha fabio "Fabio")]}
        r (apurar sessoes roster {s1 [] s2 []} {s1 [] s2 []})
        por-ver (por-vereador-map r)]
    (is (= 2 (:sessoes-computadas (por-ver ana))) "Ana compunha a Casa nas duas sessoes")
    (is (= 1 (:sessoes-computadas (por-ver fabio)))
        "Fabio (o suplente convocado no meio do periodo) so' compunha a Casa na segunda")
    (is (< (:sessoes-computadas (por-ver fabio)) (:sessoes-computadas (por-ver ana))))))

(deftest sessao-nao-realizada-conta-como-convocada
  (let [s1 (random-uuid) presente (random-uuid) ausente (random-uuid) d (LocalDate/of 2026 6 1)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d :estado "nao_realizada"})]
        roster {d [(roster-linha presente "Presente") (roster-linha ausente "Ausente")]}
        presencas {s1 [(presenca presente "entrada" "plenario")]}
        r (apurar sessoes roster presencas {s1 []})
        por-ver (por-vereador-map r)]
    (is (= 1 (:sessoes-computadas (por-ver presente))))
    (is (= 1 (:comparecimentos (por-ver presente))))
    (is (= 1 (:sessoes-computadas (por-ver ausente))))
    (is (= 1 (:ausencias-injustificadas (por-ver ausente))))
    (is (= 1 (:sessoes-consideradas (:totais r))))))

;; ---------- sessao SECRETA: nos totais E marcada LINHA A LINHA ----------

(deftest sessao-secreta-entra-nos-totais-e-CADA-LINHA-do-detalhe-sai-marcada-sigilosa
  ;; A versao anterior deste teste rodava com roster e presencas VAZIOS: `:detalhe` tinha ZERO linhas, entao
  ;; o teste cujo NOME anuncia a marcacao nao podia observar a marcacao que faltava. Agora ha' vereador de
  ;; verdade nas duas sessoes, e a asserção EXIGE a chave.
  (let [secreta (random-uuid) publica (random-uuid) v (random-uuid)
        d1 (LocalDate/of 2026 6 1) d2 (LocalDate/of 2026 6 2)
        sessoes [(sessao-fechada {:id secreta :data-de-referencia d1 :tipo "secreta" :transmite-publica false})
                 (sessao-fechada {:id publica :numero 2 :data-de-referencia d2})]
        roster {d1 [(roster-linha v "Vera")] d2 [(roster-linha v "Vera")]}
        r (apurar sessoes roster {secreta [] publica []} {secreta [] publica []})
        linha-de (fn [sid] (first (filter #(= sid (:sessao-id %)) (:detalhe r))))]
    (is (true? (:sigilosa (first (filter #(= secreta (:id %)) (:sessoes r))))))
    (is (= 1 (:sessoes-sigilosas (:totais r))))
    (is (= 2 (:sessoes-consideradas (:totais r))) "a sessao secreta ENTRA nos totais, nao some em silencio")
    (is (true? (:sigilosa (linha-de secreta)))
        "a LINHA de detalhe da sessao secreta sai marcada — `:sessoes-sigilosas N` diz QUANTAS, nao QUAIS, e a fatia 3 serializa o detalhe para um CSV que circula por e-mail")
    (is (false? (:sigilosa (linha-de publica))) "e a linha da sessao publica NAO")
    (is (= #{:sessao-id :vereador-id :estado :sigilosa} (set (keys (linha-de secreta))))
        "detalhe = ids + estado + sigilo; nome/partido NAO se repetem por linha (LGPD)")))

;; ---------- identidade: uma vez, e nunca sobrescrita por linha sem-assento ----------

(deftest identidade-aparece-uma-vez-e-detalhe-nao-repete-nome-nem-partido
  (let [s1 (random-uuid) s2 (random-uuid) ana (random-uuid)
        d1 (LocalDate/of 2026 5 1) d2 (LocalDate/of 2026 6 1)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d1})
                 (sessao-fechada {:id s2 :numero 2 :data-de-referencia d2})]
        roster {d1 [(roster-linha ana "Ana" {:nome-parlamentar "Aninha" :partido "PDT"})]
                d2 [(roster-linha ana "Ana" {:nome-parlamentar "Aninha" :partido "PDT"})]}
        r (apurar sessoes roster {s1 [] s2 []} {s1 [] s2 []})]
    (is (= 1 (count (:vereadores r))) "identidade UMA VEZ, apesar de aparecer em DUAS sessoes")
    (is (= {:id ana :nome "Ana" :nome-parlamentar "Aninha" :partido "PDT" :partido-variou false}
           (first (:vereadores r))))
    (is (= 2 (count (:detalhe r))))))

(deftest identidade-conhecida-NAO-e-sobrescrita-por-linha-sem-assento-posterior
  ;; O vereador cassado em 10/06 que registra presenca em 15/06 (a escrita de presenca nao valida mandato, e
  ;; `vereador_id` nao tem FK): esta no roster de 01/06 (linha COMPLETA) e sem-assento em 15/06 (identidade
  ;; nil). Com um `assoc` cru, a linha POSTERIOR vencia e o CSV saia com NOME EM BRANCO para alguem que a
  ;; Casa conhece. O unico teste anterior punha o mesmo vereador com a MESMA identidade nas duas sessoes —
  ;; trocar o `cond` por `assoc` cru ficava verde.
  (let [s1 (random-uuid) s2 (random-uuid) v (random-uuid)
        d1 (LocalDate/of 2026 6 1) d2 (LocalDate/of 2026 6 15)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d1})
                 (sessao-fechada {:id s2 :numero 2 :data-de-referencia d2})]
        roster {d1 [(roster-linha v "Joana Prado" {:nome-parlamentar "Joana" :partido "PSB"})]
                d2 []}
        presencas {s1 [] s2 [(presenca v "entrada" "plenario")]}
        r (apurar sessoes roster presencas {s1 [] s2 []})
        ident (first (:vereadores r))]
    (is (= 1 (count (:vereadores r))))
    (is (= "Joana Prado" (:nome ident)) "a identidade CONHECIDA sobrevive a linha sem-assento posterior")
    (is (= "Joana" (:nome-parlamentar ident)))
    (is (= "PSB" (:partido ident)))))

(deftest partido-que-VARIOU-no-periodo-nao-e-publicado-como-se-fosse-constante
  ;; Mandatos sequenciais com partidos diferentes sao permitidos, e `mandato-vigente-lateral` devolve o
  ;; partido DAQUELA data. Fixar o primeiro nao-nil publicaria um rotulo escolhido pela ordem das sessoes.
  (let [s1 (random-uuid) s2 (random-uuid) trocou (random-uuid) fiel (random-uuid)
        d1 (LocalDate/of 2026 6 1) d2 (LocalDate/of 2026 6 15)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d1})
                 (sessao-fechada {:id s2 :numero 2 :data-de-referencia d2})]
        roster {d1 [(roster-linha trocou "Trocou" {:partido "PDT"}) (roster-linha fiel "Fiel" {:partido "PV"})]
                d2 [(roster-linha trocou "Trocou" {:partido "REDE"}) (roster-linha fiel "Fiel" {:partido "PV"})]}
        r (apurar sessoes roster {s1 [] s2 []} {s1 [] s2 []})
        por-id (into {} (map (juxt :id identity)) (:vereadores r))]
    (is (nil? (:partido (por-id trocou))) "quem trocou de partido na janela NAO ganha um rotulo unico")
    (is (true? (:partido-variou (por-id trocou))))
    (is (= "PV" (:partido (por-id fiel))) "quem nao trocou continua publicado")
    (is (false? (:partido-variou (por-id fiel))))))

;; ---------- o PERCENTUAL: o unico numero que a Mesa le' ----------

(deftest licenciado-no-MEIO-do-periodo-e-o-arquetipo-que-a-etapa-existe-para-medir
  ;; 3 sessoes: licenciado em 1, presente em 1, ausente em 1. E' o caso real que motiva a apuracao e nao
  ;; existia em NENHUMA das 26 fixtures — por isso tres mutacoes sobreviviam a suite inteira, e a pior punha
  ;; o licenciado de VOLTA no denominador (50% viraria 33%).
  (let [s1 (random-uuid) s2 (random-uuid) s3 (random-uuid) v (random-uuid)
        d1 (LocalDate/of 2026 6 1) d2 (LocalDate/of 2026 6 8) d3 (LocalDate/of 2026 6 15)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d1})
                 (sessao-fechada {:id s2 :numero 2 :data-de-referencia d2})
                 (sessao-fechada {:id s3 :numero 3 :data-de-referencia d3})]
        roster {d1 [(roster-linha v "Vera" {:estado-mandato "licenciado"})]
                d2 [(roster-linha v "Vera")]
                d3 [(roster-linha v "Vera")]}
        presencas {s1 [] s2 [(presenca v "entrada" "plenario")] s3 []}
        r (apurar sessoes roster presencas {s1 [] s2 [] s3 []})
        agg (first (:por-vereador r))]
    (is (= 2 (:sessoes-computadas agg)) "a sessao em que estava LICENCIADO fica fora do denominador")
    (is (= 1 (:sessoes-licenciado agg)) "e aparece a' parte, nunca some")
    (is (= 1 (:comparecimentos agg)))
    (is (= 1 (:ausencias-injustificadas agg)))
    (is (= 50 (:percentual agg)) "1 de 2 = 50% — o denominador NAO inclui a sessao de licenca")))

(deftest percentual-e-truncado-para-baixo-100-porcento-so-quando-nao-faltou-a-nenhuma
  ;; `Math/round` fazia 199/200 = 99,5 imprimir **100%**. Afirmar 'compareceu a 100% das sessoes' de quem
  ;; faltou a uma e' falso num documento que responde oficio e requerimento.
  (let [v (random-uuid)
        monta (fn [n-sessoes n-presencas]
                (let [ids (vec (repeatedly n-sessoes random-uuid))
                      datas (mapv #(.plusDays (LocalDate/of 2026 1 1) %) (range n-sessoes))
                      sessoes (mapv (fn [i] (sessao-fechada {:id (ids i) :numero (inc i)
                                                             :data-de-referencia (datas i)})) (range n-sessoes))
                      roster (into {} (map (fn [d] [d [(roster-linha v "Vera")]])) datas)
                      presencas (into {} (map-indexed
                                          (fn [i sid] [sid (if (< i n-presencas)
                                                             [(presenca v "entrada" "plenario")] [])])) ids)
                      justs (into {} (map (fn [sid] [sid []])) ids)]
                  (:percentual (first (:por-vereador (apurar sessoes roster presencas justs))))))]
    (is (= 99 (monta 200 199)) "199/200 = 99,5 -> 99, NUNCA 100: ele faltou a uma")
    (is (= 100 (monta 200 200)) "100% so' quando nao faltou a nenhuma")
    (is (= 66 (monta 3 2)) "2/3 = 66,66 -> 66")
    (is (= 0 (monta 3 0)) "faltou a todas = 0% (e o denominador nao e' zero)")))

(deftest percentual-acima-de-100-e-DELIBERADO-e-fica-visivel
  ;; Presenca SEM ASSENTO conta no numerador e nao no denominador (mesmo tratamento de `contar-quorum`).
  ;; A desigualdade e' sintoma de cadastro furado e NAO e' clampada — decisao documentada, agora pinada.
  (let [s1 (random-uuid) s2 (random-uuid) v (random-uuid)
        d1 (LocalDate/of 2026 6 1) d2 (LocalDate/of 2026 6 2)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d1})
                 (sessao-fechada {:id s2 :numero 2 :data-de-referencia d2})]
        roster {d1 [(roster-linha v "Vera")] d2 []}
        presencas {s1 [(presenca v "entrada" "plenario")] s2 [(presenca v "entrada" "plenario")]}
        r (apurar sessoes roster presencas {s1 [] s2 []})
        agg (first (:por-vereador r))]
    (is (= 1 (:sessoes-computadas agg)) "so' a sessao com assento conta no denominador")
    (is (= 2 (:comparecimentos agg)) "as duas presencas contam no numerador")
    (is (= 200 (:percentual agg)) "NAO e' clampado a 100 — a desigualdade e' o sintoma, e fica visivel")))

;; ---------- ordem estavel do payload ----------

(deftest vereadores-e-por-vereador-saem-ordenados-por-nome
  ;; Sem asserção de sequencia, remover os dois `sort-by` fica verde e a ordem do CSV vira ordem de hash-map.
  (let [s1 (random-uuid) d (LocalDate/of 2026 6 1)
        zeca (random-uuid) ana (random-uuid) bruno (random-uuid)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d})]
        roster {d [(roster-linha zeca "Zeca") (roster-linha ana "Ana") (roster-linha bruno "Bruno")]}
        r (apurar sessoes roster {s1 []} {s1 []})]
    (is (= ["Ana" "Bruno" "Zeca"] (mapv :nome (:vereadores r))))
    (is (= [ana bruno zeca] (mapv :id (:vereadores r))))
    (is (= [ana bruno zeca] (mapv :vereador-id (:por-vereador r)))
        "`:por-vereador` na MESMA ordem de `:vereadores` — o CSV le' as duas lado a lado")))

;; ---------- chave ausente NUNCA e' 'perguntei e veio vazio' ----------

(deftest chave-ausente-em-qualquer-dos-tres-mapas-LANCA-em-vez-de-derivar-em-branco
  (let [s1 (random-uuid) v (random-uuid) d (LocalDate/of 2026 6 1)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d})]
        roster {d [(roster-linha v "Vera")]}
        completo #(apurar sessoes roster {s1 []} {s1 []})
        erro-de (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (is (some? (completo)) "sanidade: com os tres mapas completos, apura normalmente")
    (is (= :roster-da-data (:chave-ausente (erro-de #(apurar sessoes {} {s1 []} {s1 []}))))
        "roster ausente NAO vira roster vazio — quem faltou sumiria e o percentual inflaria")
    (is (= :presencas-da-sessao (:chave-ausente (erro-de #(apurar sessoes roster {} {s1 []})))))
    (is (= :justificativas-da-sessao (:chave-ausente (erro-de #(apurar sessoes roster {s1 []} {})))))
    (is (= s1 (:sessao-id (erro-de #(apurar sessoes {} {s1 []} {s1 []}))))
        "a ex-data nomeia a sessao — o erro e' acionavel, nao um NPE")))

;; ---------- o que foi EXCLUIDO tambem e' publicado ----------

(deftest sessoes-sem-data-de-referencia-sao-DECLARADAS-nos-totais
  (let [s1 (random-uuid) v (random-uuid) d (LocalDate/of 2026 6 1)
        sessoes [(sessao-fechada {:id s1 :data-de-referencia d})]
        roster {d [(roster-linha v "Vera")]}
        r (apurar sessoes roster {s1 []} {s1 []} 3)]
    (is (= 3 (:sessoes-sem-data-de-referencia (:totais r)))
        "sessao fechada SEM nenhum marco de data fica fora do periodo — mas o denominador nao encolhe em silencio")
    (is (= 1 (:sessoes-consideradas (:totais r))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (logic/apurar-assiduidade sessoes roster {s1 []} {s1 []} {}))
        "contexto sem o contador e' erro do chamador — publicar 0 por default seria uma mentira silenciosa")))
