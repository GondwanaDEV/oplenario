(ns folha-preview
  "O MOCK NAO E' O ARTEFATO (regra do projeto — ja custou 3 falhas de contraste consertadas so' no
  mock). Este ns gera `produto/design-system/o-plenario/telas/folha-sessao.html` a partir do MESMO
  renderizador puro (`sessoes.gerador-folha/renderizar`) e do MESMO adapter (`serializador-folha-html`)
  que a Etapa 5 fatia 4/5 vai usar em producao — o arquivo do design-system nunca e' autorado a mao.

  Fixture: uma Casa de 21 vereadores com mandato vigente (um deles licenciado, fora do denominador),
  mais UMA presenca sem-assento (fora da composicao, mas dentro do total de presentes) — 22 linhas ao
  todo. Presentes/ausentes/justificativas/series cobrem os casos que o brief pede: presente-plenario,
  presente-remoto, ausente, ausente-justificado, ausente-justificativa-pendente, licenciado,
  sem-assento, uma justificativa deferida, uma pendente, e duas series
  entrada/saida/retorno (movimentacao > 1 evento).

  Rodar (de dentro de apps/backend, container efemero — Mandato Docker):
    clojure -M:dev -e \"(require 'folha-preview) (folha-preview/gerar!)\""
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [oplenario.sessoes.components.serializador-folha :as ser]
            [oplenario.sessoes.gerador-folha :as gerador]))

(defn- uid [n]
  (java.util.UUID/fromString (format "00000000-0000-0000-0000-%012d" n)))

(def ^:private sessao-id (uid 900))

(def ^:private t0 (java.time.Instant/parse "2026-08-14T21:00:00Z")) ;; 18h Fortaleza
(def ^:private t1 (java.time.Instant/parse "2026-08-14T21:03:00Z"))
(def ^:private t2 (java.time.Instant/parse "2026-08-14T22:10:00Z"))
(def ^:private t3 (java.time.Instant/parse "2026-08-14T22:40:00Z"))
(def ^:private t-instante (java.time.Instant/parse "2026-08-14T23:30:00Z"))

(def ^:private partidos ["PDT" "PT" "PSDB" "UNIÃO" "REPUBLICANOS" "PL" "PSD" "MDB"])

(defn- membro
  [{:keys [n nome estado sem-assento desde fonte cargo-mesa]
    :or {sem-assento false}}]
  {:vereador-id (uid n)
   :nome nome
   :nome-parlamentar (first (str/split nome #" "))
   :partido (nth partidos (mod n (count partidos)))
   :cargo-mesa cargo-mesa
   :estado estado
   :inconsistencia-cadastro false
   :sem-assento sem-assento
   :desde desde
   :fonte fonte
   :registrado-em (when desde t2)
   :justificativa nil})

(def ^:private nomes-vereadores
  ["Ana Beatriz Correia" "Bruno Almeida Nogueira" "Carla Duarte Mesquita" "Daniel Ferreira Cavalcante"
   "Elisa Gomes Teixeira" "Fábio Holanda Bezerra" "Giselle Ibiapina Rocha" "Hugo Junqueira Freire"
   "Ivone Kelly Aragão" "João Lira Vasconcelos" "Karla Menezes Pinheiro" "Luiz Otávio Sampaio"
   "Marina Nogueira Castro" "Nélson Oliveira Brito" "Otávia Pereira Lins" "Paulo Quintino Diógenes"
   "Rita Salustiano Neves" "Sérgio Tavares Uchôa" "Tânia Uchôa Ribeiro" "Vicente Wagner Siqueira"
   "Yasmin Xavier Monteiro"])

(def ^:private linhas-membros
  (vec
   (concat
    ;; 1: licenciada — fora do denominador.
    [(membro {:n 1 :nome (nth nomes-vereadores 0) :estado :licenciado})]
    ;; 2–13: 12 presentes no plenario, dois deles com mais de um evento (movimentacao).
    (map (fn [i] (membro {:n (inc i) :nome (nth nomes-vereadores i) :estado :presente-plenario
                          :desde (if (zero? (mod i 6)) t2 t1) :fonte "manual_secretaria"}))
         (range 1 13))
    ;; 14–16: 3 presentes em remoto.
    (map (fn [i] (membro {:n (inc i) :nome (nth nomes-vereadores i) :estado :presente-remoto
                          :desde t1 :fonte "autoatendimento"}))
         (range 13 16))
    ;; 17–19: 3 ausentes sem justificativa.
    (map (fn [i] (membro {:n (inc i) :nome (nth nomes-vereadores i) :estado :ausente}))
         (range 16 19))
    ;; 20: ausente com justificativa DEFERIDA.
    [(membro {:n 20 :nome (nth nomes-vereadores 19) :estado :ausente-justificado})]
    ;; 21: ausente com justificativa PENDENTE.
    [(membro {:n 21 :nome (nth nomes-vereadores 20) :estado :ausente-justificativa-pendente
              :cargo-mesa "1ª Secretária"})])))

(def ^:private linha-sem-assento
  (membro {:n 99 :nome "Wellington Sem Cadeira" :estado :presente-plenario :sem-assento true
           :desde t3 :fonte "manual_secretaria"}))

(def ^:private linhas (conj linhas-membros linha-sem-assento))

(def ^:private quorum
  {:presentes-plenario 13   ;; 12 membros + o sem-assento
   :presentes-remoto 3
   :presentes-total 16
   :membros-da-casa 20      ;; 21 membros - 1 licenciada
   :presencas-fora-do-roster 1})

(def ^:private serie
  {(uid 2) [{:ente-id sessao-id :id (java.util.UUID/randomUUID) :sessao-id sessao-id :vereador-id (uid 2)
             :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria" :ocorrido-em t1 :efetivado-em t1}
            {:ente-id sessao-id :id (java.util.UUID/randomUUID) :sessao-id sessao-id :vereador-id (uid 2)
             :tipo "saida" :modalidade "plenario" :fonte "manual_secretaria" :ocorrido-em t2 :efetivado-em t2}
            {:ente-id sessao-id :id (java.util.UUID/randomUUID) :sessao-id sessao-id :vereador-id (uid 2)
             :tipo "retorno" :modalidade "plenario" :fonte "manual_secretaria" :ocorrido-em t3 :efetivado-em t3}]
   (uid 7) [{:ente-id sessao-id :id (java.util.UUID/randomUUID) :sessao-id sessao-id :vereador-id (uid 7)
             :tipo "entrada" :modalidade "remoto" :fonte "autoatendimento" :ocorrido-em t1 :efetivado-em t1}
            {:ente-id sessao-id :id (java.util.UUID/randomUUID) :sessao-id sessao-id :vereador-id (uid 7)
             :tipo "mudanca_modalidade" :modalidade "plenario" :fonte "manual_secretaria"
             :ocorrido-em t2 :efetivado-em t2}]})

(def ^:private justificativas
  [{:id (java.util.UUID/randomUUID) :vereador-id (uid 20) :estado "aprovada"
    :motivo "Licença médica — atestado anexado ao processo de RH." :lock-version 1
    :decidido-por (java.util.UUID/randomUUID) :decidido-em t2}
   {:id (java.util.UUID/randomUUID) :vereador-id (uid 21) :estado "pendente"
    :motivo "Viagem oficial em representação da Casa — aguardando confirmação da agenda."
    :lock-version 0 :decidido-por nil :decidido-em nil}])

(def ^:private atos
  [{:id (java.util.UUID/randomUUID) :ente-id sessao-id :sessao-id sessao-id
    :conduzida-por (java.util.UUID/randomUUID) :membros-da-casa 20 :ocorrido-em t0 :registrado-em t0}])

(def ^:private cabecalho-da-casa
  {:nome-oficial "Câmara Municipal de Fortaleza" :nome-curto "CMF"
   :legislatura-numero 19 :legislatura-ano-inicio 2025 :legislatura-ano-fim 2028})

(def ^:private dados
  {:sessao {:id sessao-id :estado "encerrada" :motivo-nao-realizada nil}
   :instante t-instante
   :cabecalho-da-casa cabecalho-da-casa
   :linhas linhas
   :quorum quorum
   :serie serie
   :justificativas justificativas
   :atos-de-chamada-conduzida atos})

(def ^:private destino
  "../../produto/design-system/o-plenario/telas/folha-sessao.html")

(defn gerar!
  "Renderiza a fixture pelo MESMO pipeline de producao (gerador-folha/renderizar + SerializadorFolha
  HTML) e escreve o resultado em `produto/design-system/o-plenario/telas/folha-sessao.html`. Devolve o
  caminho absoluto escrito."
  []
  (let [documento (gerador/renderizar dados)
        {conteudo :bytes} (ser/serializar (ser/serializador-folha-html) documento)
        arquivo (io/file destino)]
    (io/make-parents arquivo)
    (with-open [out (io/output-stream arquivo)]
      (.write out ^bytes conteudo))
    (println "folha-sessao.html escrito em" (.getCanonicalPath arquivo) "(" (count conteudo) "bytes )")
    (.getCanonicalPath arquivo)))

(comment
  (gerar!))
