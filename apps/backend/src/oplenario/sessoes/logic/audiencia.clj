(ns oplenario.sessoes.logic.audiencia
  "PURO: as regras da AUDIENCIA PUBLICA (ADR-0021 Parte A). A audiencia e' um tipo de sessao (`audiencia_publica`,
  `sessoes.logic/tipos-sessao`) com capabilities proprias; aqui mora o que e' so' dela — a finalidade (e a referencia
  do quadrimestre das metas fiscais, LRF art. 9 §4), o tempo unico de fala, e a maquina da INSCRICAO do cidadao:

      inscrita -> falando (a Mesa chama; sessao aberta; uma por vez) -> falou (a Mesa encerra, com o tempo usado)
      inscrita -> ausente (a Mesa registra que nao estava)
      inscrita -> desistiu (so' a propria pessoa, pelo portal)

  Os vocabularios espelham os CHECK da mig 182. Sem I/O."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def finalidades #{"tematica" "metas_fiscais" "ldo" "loa" "ppa"})
(def origens-inscricao #{"portal_govbr" "presencial_secretaria"})
(def falas-como #{"individual" "entidade" "conselho_movimento"})
(def estados-inscricao #{"inscrita" "falando" "falou" "ausente" "desistiu"})
(def estados-terminais-inscricao #{"falou" "ausente" "desistiu"})

(def transicoes-inscricao
  {"inscrita" #{"falando" "ausente" "desistiu"}
   "falando"  #{"falou"}})

(def tempo-fala-padrao-segundos 300)
(def tempo-fala-minimo-segundos 60)
(def tempo-fala-maximo-segundos 1800)
(def teto-texto 200)

(def estados-que-aceitam-inscricao
  "A sessao ainda recebe inscricao (a Mesa inscreve no dia quem chegou; o portal, ate' o fim)."
  #{"agendada" "aberta" "suspensa"})

(def estados-realizada
  "A audiencia ACONTECEU e acabou: o portal lista como realizada e passa a mostrar quem falou."
  #{"encerrada" "arquivada"})

(def teto-realizadas-no-portal 20)

(defn- invalido! [msg campo]
  (throw (ex-info msg {:tipo :validacao/invalido :campo campo})))

(defn referencia-valida?
  "`AAAA-Q1|Q2|Q3` — o quadrimestre da audiencia de metas fiscais."
  [s]
  (boolean (and (string? s) (re-matches #"[0-9]{4}-Q[1-3]" s))))

(def ^:private quadrimestre-do-ultimo-mes {4 "Q1" 8 "Q2" 12 "Q3"})

(defn referencia-do-quadrimestre
  "A `referencia` (`AAAA-Q1|Q2|Q3`) do quadrimestre que TERMINA na competencia `{:ano :mes}` — a forma como a regra de
  metas fiscais do motor (ADR-0021 A4) nomeia o periodo: 04 -> Q1, 08 -> Q2, 12 -> Q3. Outro mes nao fecha quadrimestre:
  nil (o fato responde falso)."
  [{:keys [ano mes]}]
  (when-let [q (and (int? ano) (get quadrimestre-do-ultimo-mes mes))]
    (format "%04d-%s" ano q)))

(defn- texto-ok? [s] (and (string? s) (not (str/blank? s)) (<= (count s) teto-texto)))

(defn tempo-de-fala-valido? [n]
  (and (int? n) (<= tempo-fala-minimo-segundos n tempo-fala-maximo-segundos)))

(defn validar-audiencia!
  "O bloco `audiencia` do agendamento (ja' coagido): tema (1..200), local opcional (1..200), finalidade conhecida, a
  referencia SO' e OBRIGATORIA em `metas_fiscais` (formato do quadrimestre) e o tempo de fala (60..1800 s). Fail-closed
  (`:validacao/invalido` -> 400). Devolve o mapa."
  [{:keys [tema local finalidade referencia tempo-fala-segundos] :as a}]
  (when-not (texto-ok? tema) (invalido! "tema da audiencia obrigatorio (ate' 200 caracteres)" :tema))
  (when (and (some? local) (not (texto-ok? local))) (invalido! "local invalido (ate' 200 caracteres)" :local))
  (when-not (contains? finalidades finalidade) (invalido! "finalidade desconhecida" :finalidade))
  (if (= "metas_fiscais" finalidade)
    (when-not (referencia-valida? referencia)
      (invalido! "audiencia de metas fiscais exige a referencia do quadrimestre (AAAA-Q1, Q2 ou Q3)" :referencia))
    (when (some? referencia)
      (invalido! "so' a audiencia de metas fiscais leva referencia de quadrimestre" :referencia)))
  (when (and (some? tempo-fala-segundos) (not (tempo-de-fala-valido? tempo-fala-segundos)))
    (invalido! "tempo de fala entre 60 e 1800 segundos" :tempo-fala-segundos))
  a)

(defn validar-fala!
  "O que a pessoa diz ao se inscrever: como fala (`individual`, por `entidade` ou por `conselho_movimento`) — fora do
  individual, QUAL entidade e' obrigatorio; no individual, nao ha' entidade — e o tema da fala (1..200). Devolve o mapa
  normalizado (`entidade` nil no individual)."
  [{:keys [fala-como entidade tema] :as f}]
  (when-not (contains? falas-como fala-como) (invalido! "fala-como desconhecido" :fala-como))
  (when-not (texto-ok? tema) (invalido! "tema da fala obrigatorio (ate' 200 caracteres)" :tema))
  (if (= "individual" fala-como)
    (assoc f :entidade nil)
    (do (when-not (texto-ok? entidade)
          (invalido! "quem fala por entidade, conselho ou movimento diz qual (ate' 200 caracteres)" :entidade))
        f)))

(defn transicao-valida? [de para]
  (contains? (get transicoes-inscricao de #{}) para))

(defn escopo-protocolo
  "O escopo do contador gapless da Casa (`shared.sequencial`) — o mesmo desenho dos protocolos da participacao."
  [ano]
  (str "inscricao_audiencia:" ano))

(defn protocolo
  "`AUD-AAAA-NNNNNN`."
  [ano numero]
  (format "AUD-%04d-%06d" (int ano) (int numero)))

(defn audiencia? [sessao] (= "audiencia_publica" (:tipo-sessao sessao)))

(defn inscricoes-abertas-efetivas?
  "O portal aceita inscricao AGORA: a sessao e' audiencia que aceita inscricao de cidadao (capability), a Mesa nao
  fechou as inscricoes e a sessao ainda nao acabou."
  [sessao audiencia]
  (boolean (and (audiencia? sessao)
                (true? (:aceita-inscricao-cidadao sessao))
                (true? (:inscricoes-abertas audiencia))
                (contains? estados-que-aceitam-inscricao (:estado sessao)))))

(defn motivo-recusa-inscricao
  "nil = a inscricao pode entrar; senao a frase do 409. `pelo-portal?` exige tambem as inscricoes abertas (a Mesa
  inscreve quem chegou mesmo com o portal fechado, enquanto a sessao nao acabou)."
  [sessao audiencia pelo-portal?]
  (cond
    (not (and (audiencia? sessao) (true? (:aceita-inscricao-cidadao sessao))))
    "esta sessao nao aceita inscricao de cidadao"
    (not (contains? estados-que-aceitam-inscricao (:estado sessao)))
    "a audiencia ja' terminou: as inscricoes estao encerradas"
    (and pelo-portal? (not (true? (:inscricoes-abertas audiencia))))
    "as inscricoes desta audiencia estao fechadas"))

(defn motivo-recusa-chamada
  "nil = a Mesa pode chamar esta inscricao agora; senao a frase do 409. A sessao tem de estar ABERTA (em curso) e
  ninguem pode estar falando (uma fala de cidadao por vez)."
  [sessao inscricao alguem-falando?]
  (cond
    (not= "aberta" (:estado sessao)) "a audiencia precisa estar aberta para chamar quem vai falar"
    (not= "inscrita" (:estado inscricao)) "esta inscricao nao esta' aguardando a vez"
    alguem-falando? "ja' ha' um cidadao com a palavra: encerre a fala antes de chamar o proximo"))

(defn inscritos-publicos
  "A contagem do portal: quem esta' (ou esteve) na fila — sem os desistentes."
  [inscricoes]
  (count (remove #(= "desistiu" (:estado %)) inscricoes)))

(defn falaram
  "Quem falou, na ordem — SO' depois que a audiencia acabou (antes disso, nada nominal e' publico; quem desistiu ou nao
  falou nunca aparece)."
  [sessao inscricoes]
  (if (contains? estados-realizada (:estado sessao))
    (->> inscricoes (filter #(= "falou" (:estado %))) (sort-by :ordem) vec)
    []))

(defn fala-cidada-para-ia
  "A fala de um cidadao na forma das falas da tribuna que a fronteira com a IA le (ADR-0008): o id da inscricao faz
  as vezes do orador (o nome vai no mapa de nomes, ao lado dos vereadores)."
  [inscricao]
  {:id (:id inscricao) :orador-id (:id inscricao) :tipo-fala "manifestacao_cidada" :fase "audiencia_publica"
   :fala-pai-id nil :iniciou-em (:chamada-em inscricao) :encerrou-em (:encerrada-em inscricao)})
