(ns oplenario.transparencia.perfil-test
  "INTEGRACAO (PG real) — Onda E fatia 2, Task 3: a LEITURA COMPOSTA do perfil PUBLICO do vereador
  (`RepoTransparencia/perfil-parlamentar` + `controllers/perfil-parlamentar`). As quatro leituras (materias
  de autoria, contagem de 'viraram lei', votos publicos, resumo de presenca) rodam numa UNICA tx do tenant —
  mesma disciplina de `legislativo/ficha-completa-da-proposicao`: um so' snapshot MVCC, sem costura de
  numeros lidos em instantes diferentes.

  O perfil NAO tem identidade (nome/mandato/comissoes): §22.10 proibe `transparencia` de importar
  `cadastros`; a identidade chega na BORDA (Task 4), injetada pelo host.

  Semeadura: sempre pelo caminho de PROJECAO (Repo de `legislativo` -> shared.outbox -> relay ->
  `transparencia.diplomat.consumers`), nunca INSERT direto no read-model — mesmo racional de portal_test.
  Os casos que precisam de um estado que o produtor real NAO emite hoje (autoria 'executivo' com `autor_id`
  sobrevivente) entram por `projetar-evento!` com o payload do contrato — ainda o caminho de projecao, so'
  que sem passar pelo outbox."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as legislativo-repo]
            [oplenario.legislativo.db.autografo :as autografo]
            [oplenario.legislativo.db.tramitacao-executiva :as exec]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.rotas :as rotas]
            [oplenario.transparencia.components.repositorio :as transparencia-repo]
            [oplenario.transparencia.controllers :as controllers]
            [oplenario.transparencia.db.parlamentar :as db-parlamentar]
            [oplenario.transparencia.diplomat.consumers :as consumers]
            [oplenario.transparencia.suporte-presenca :as sp])
  (:import (java.time LocalDate)
           (java.util UUID)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo-legislativo* nil)
(def ^:dynamic *repo-transparencia* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))
          bus (outbox/bus)]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)
                *repo-legislativo* (legislativo-repo/->RepoLegislativoPg c bus)
                *repo-transparencia* (transparencia-repo/->RepoTransparenciaPg c)]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(defn- drenar! []
  (outbox/drenar! *ds* (consumers/registrar {})))

(defn- protocolar!
  "Protocola pelo caminho REAL (Repo de legislativo emite `proposicao.protocolada` na tx do ato). Devolve o
  id da proposicao. `extra` sobrescreve autoria (autor-tipo/autor-id/autor-texto)."
  [ente ementa extra]
  (:id (legislativo-repo/protocolar! *repo-legislativo* ente
         (merge {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                 :municipio-nome "Fortaleza" :ementa ementa}
                extra))))

(defn- publicar-norma!
  "Leva a proposicao ja protocolada ate' a norma PUBLICADA (o unico caminho que emite `norma.publicada`).
  Copia da mecanica de portal_test (`ate-promulgavel!` + promulgar + publicar)."
  [ente pid ementa]
  (let [aid (tenancy/com-tenant* *ds* ente
              (fn [tx]
                (let [{aid :id} (autografo/gerar! tx {:id (random-uuid) :ente-id ente :proposicao-id pid
                                                      :ano 2026 :texto-versao-id (random-uuid)
                                                      :destinatario-texto "Prefeito Municipal de Fortaleza"})
                      {tid :id} (exec/iniciar! tx {:id (random-uuid) :ente-id ente :autografo-id aid})]
                  (exec/registrar-resposta! tx {:id tid :ente-id ente :resultado "sancionado"
                                                :updated-by nil :lock-version 0})
                  aid)))
        {nid :id} (legislativo-repo/promulgar-norma! *repo-legislativo* ente
                    {:id (random-uuid) :proposicao-id pid :autografo-id aid :tipo-norma "lei" :ano 2026
                     :uf "CE" :municipio-nome "Fortaleza" :data-promulgacao (LocalDate/of 2026 6 28)
                     :ementa ementa :texto-versao-id (random-uuid)})]
    (legislativo-repo/publicar-norma! *repo-legislativo* ente
      {:id nid :veiculo-publicacao "Diario Oficial do Municipio" :updated-by nil :lock-version 0})
    nid))

(defn- votar!
  "Voto NOMINAL pelo caminho REAL (abrir-votacao! + registrar-voto! -> `voto.registrado`)."
  [ente pid vereador]
  (let [{vid :id} (legislativo-repo/abrir-votacao! *repo-legislativo* ente
                    {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id pid :sessao-id (random-uuid)
                     :modalidade "nominal" :quorum-tipo "maioria_simples"})]
    (legislativo-repo/registrar-voto! *repo-legislativo* ente
      {:id (random-uuid) :votacao-id vid :vereador-id vereador :voto "sim"})
    vid))

(defn- presenca!
  "Projeta `presenca.registrada` (o modulo `sessoes` nao esta' wireado aqui — o portal so' consome o evento
  publico; mesmo padrao de portal_test). `tipo` no vocabulario REAL: entrada|saida|retorno|
  mudanca_modalidade. O payload passa por `suporte-presenca/validar-vocabulario!` (a trava UNICA dos tres ns
  de teste de presenca) ANTES de tocar o banco — semear vocabulario ficticio LANCA aqui.

  `ocorrido-em` e' a 5a aridade (I-5 fatia 6): o consumer deriva dele a DATA CIVIL da sessao na companheira
  `sessao_com_chamada`, e e' essa data que a janela de exercicio recorta. Sem controlar o instante nao ha
  como semear 'sessao antes da posse' vs 'sessao dentro do mandato'. Default = o valor historico deste ns."
  ([ente sessao vereador tipo] (presenca! ente sessao vereador tipo "2026-05-18T14:00:00Z"))
  ([ente sessao vereador tipo ocorrido-em]
   (let [payload (sp/validar-vocabulario!
                  {:sessao-id (str sessao) :vereador-id (str vereador) :tipo tipo
                   :modalidade "plenario" :fonte "manual_secretaria"
                   :ocorrido-em ocorrido-em})]
     (tenancy/com-tenant* *ds* ente
       (fn [tx]
         (transparencia-repo/projetar-evento! tx
           {:tipo "presenca.registrada" :ente-id ente :payload payload}))))))

(def ^:private janela-larga
  "Janela de exercicio que cobre TUDO — o que os casos que nao sao sobre a janela (autoria, votos, teto)
  precisam para seguir medindo o que sempre mediram. NAO e' o default do sistema: a fatia 6 do I-5 exige
  janela EXPLICITA em toda chamada, e `[]` significa 'sem periodo de exercicio registrado', nunca 'tudo'."
  [{:inicio (LocalDate/of 2000 1 1) :fim nil}])

(defn- dia
  "Instante ISO das 14h UTC (= 11h em America/Fortaleza) do dia civil `aaaa-mm-dd` — longe das duas bordas
  de meia-noite, para que a data civil derivada pelo consumer seja exatamente `aaaa-mm-dd`."
  [aaaa-mm-dd]
  (str aaaa-mm-dd "T14:00:00Z"))

(defn- projetar-materia!
  "Semeia uma materia com uma COMBINACAO de autoria que o produtor real nao emite hoje (ex.: autor_tipo
  'executivo' COM autor_id sobrevivente — o cenario do achado N-1). Ainda e' o caminho de projecao: o mesmo
  `projetar-evento!` que o consumer chama, com o payload do contrato `ProtocoladaPayload`."
  [ente pid m]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (transparencia-repo/projetar-evento! tx
        {:tipo "proposicao.protocolada" :ente-id ente
         :payload (merge {:proposicao-id (str pid) :tipo "projeto_lei" :ano 2026 :sequencial 99
                          :urn-lex (str "urn:lex:fixture:" pid) :ementa "Fixture" :estado "protocolada"}
                         m)}))))

(defn- projetar-norma!
  "Semeia `transparencia.norma` para uma materia semeada por `projetar-materia!` (a promulgacao real exige
  a proposicao no legislativo, que estes casos sinteticos nao tem)."
  [ente pid]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (transparencia-repo/projetar-evento! tx
        {:tipo "norma.publicada" :ente-id ente
         :payload {:norma-id (str (random-uuid)) :proposicao-id (str pid) :tipo-norma "lei"
                   :numero 7 :ano 2026 :urn (str "urn:lex:norma:" pid) :ementa "Fixture norma"
                   :publicado-em "2026-06-28T12:00:00Z" :veiculo-publicacao "Diario Oficial do Municipio"}}))))

(defn- projetar-lote!
  "Semeia `n` materias de autoria do vereador numa UNICA tx, pelo mesmo `projetar-evento!` que o consumer
  chama. Existe para exceder o TETO de `listar-por-autor` (200) sem 200 round-trips pelo relay — e' o unico
  jeito de provar que `:materias-total` conta o universo INTEIRO, e nao as linhas devolvidas."
  [ente vereador n]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (doseq [i (range n)]
        (transparencia-repo/projetar-evento! tx
          {:tipo "proposicao.protocolada" :ente-id ente
           :payload {:proposicao-id (str (random-uuid)) :tipo "projeto_lei" :ano 2026 :sequencial (inc i)
                     :urn-lex (str "urn:lex:lote:" ente ":" i) :ementa (str "Materia de lote " i)
                     :estado "protocolada" :autor-tipo "vereador" :autor-id (str vereador)}})))))

(defn- comparar-uuid-pg
  "Ordem que o POSTGRES da' a valores `uuid`: byte a byte SEM SINAL. NAO usar `java.util.UUID/compareTo` —
  ele compara os dois longs COM SINAL, entao inverte o resultado sempre que o bit 63 de uma das metades
  difere (metade dos pares aleatorios). Este comparador e' o que permite prever a ordem de desempate por
  `proposicao_id DESC` sem depender de sorte."
  [^UUID a ^UUID b]
  (let [m (Long/compareUnsigned (.getMostSignificantBits a) (.getMostSignificantBits b))]
    (if (zero? m)
      (Long/compareUnsigned (.getLeastSignificantBits a) (.getLeastSignificantBits b))
      m)))

;; ---------- o caso feliz: as quatro leituras compostas ----------

(deftest perfil-parlamentar-compoe-as-quatro-leituras
  (testing "o perfil publico junta autoria, 'viraram lei', votos e presenca numa unica leitura"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          pid      (protocolar! ente "Hortas comunitarias"
                                {:autor-tipo "vereador" :autor-id vereador :autor-texto "Helena Past"})]
      (votar! ente pid vereador)
      (drenar!)
      (presenca! ente (random-uuid) vereador "entrada")
      (let [p (controllers/perfil-parlamentar *repo-transparencia* ente vereador janela-larga)]
        (is (= 1 (count (:materias p))) "a materia de autoria aparece")
        (is (= 1 (:materias-total p)) "o total do universo (sem teto) acompanha a lista")
        (is (= pid (:proposicao-id (first (:materias p)))))
        (is (= "Hortas comunitarias" (:ementa (first (:materias p)))))
        (is (= 0 (:normas-de-autoria p)) "nada virou lei ainda")
        (is (= 1 (count (:votos p))) "o voto nominal publico aparece")
        (is (= "sim" (:voto (first (:votos p)))))
        (is (= 1 (:sessoes-presente (:presenca p))) "o numerador de presenca")
        (is (= 1 (:sessoes-com-chamada (:presenca p))) "o denominador de presenca")))))

(deftest perfil-de-vereador-sem-atuacao-e-vazio-mas-bem-formado
  (testing "vereador sem nenhuma atuacao devolve as quatro chaves, nunca nil"
    (let [ente (random-uuid)
          p    (controllers/perfil-parlamentar *repo-transparencia* ente (random-uuid) janela-larga)]
      (is (empty? (:materias p)))
      (is (= 0 (:materias-total p)))
      (is (= 0 (:normas-de-autoria p)))
      (is (empty? (:votos p)))
      (is (= 0 (:sessoes-presente (:presenca p))))
      (is (= 0 (:sessoes-com-chamada (:presenca p)))))))

;; ---------- (a) escopo de tenant ----------

(deftest materia-de-outro-ente-nao-aparece-no-perfil
  (testing "o MESMO uuid de vereador em dois entes nao mistura acervo (ente_id explicito + RLS)"
    (let [ente-a   (random-uuid)
          ente-b   (random-uuid)
          vereador (random-uuid)
          pid-a    (protocolar! ente-a "Materia do ente A"
                                {:autor-tipo "vereador" :autor-id vereador :autor-texto "Helena Past"})
          _        (protocolar! ente-b "Materia do ente B"
                                {:autor-tipo "vereador" :autor-id vereador :autor-texto "Helena Past"})]
      (drenar!)
      (let [p (controllers/perfil-parlamentar *repo-transparencia* ente-a vereador janela-larga)]
        (is (= 1 (count (:materias p))) "so' a materia do ente consultado")
        (is (= pid-a (:proposicao-id (first (:materias p)))))
        ;; O total tambem e' escopado. NOTA HONESTA (mesma situacao da mutacao M3 da Task 3): este assert
        ;; NAO consegue MATAR a remocao de `[:= :ente_id ente-id]` de `contar-por-autor` — o isolamento tem
        ;; DUAS camadas e a de baixo basta sozinha. `kernel/tenancy/entrar-app!` faz `SET LOCAL ROLE
        ;; oplenario_app` (NOBYPASSRLS) em TODA tx, entao a policy da mig 0044 aplica mesmo com o pool
        ;; conectado como dono, e a linha do outro ente e' invisivel de qualquer forma. O predicado
        ;; explicito e' defesa em profundidade (exigencia do invariante), estruturalmente inalcancavel por
        ;; teste de caixa-preta. O assert fica porque prende o VALOR do total no cenario multi-tenant.
        (is (= 1 (:materias-total p)) "o total tambem e' escopado ao ente consultado")))))

;; ---------- (b) regressao do achado N-1 ----------

(deftest materia-com-autor-id-mas-autor-tipo-nao-vereador-nao-aparece
  (testing "autoria que virou 'executivo' OU 'comissao' com autor_id sobrevivente NAO e' autoria do vereador"
    (let [ente      (random-uuid)
          vereador  (random-uuid)
          pid-ok    (protocolar! ente "Autoria parlamentar legitima"
                                 {:autor-tipo "vereador" :autor-id vereador :autor-texto "Helena Past"})
          pid-exec  (random-uuid)
          pid-com   (random-uuid)]
      (drenar!)
      (projetar-materia! ente pid-exec {:autor-tipo "executivo" :autor-id (str vereador)
                                        :autor-texto "Prefeitura" :ementa "Autoria virou executivo"})
      ;; O predicado tem que ser IGUALDADE a 'vereador', nao "qualquer coisa menos executivo": o vocabulario
      ;; da coluna e' ('vereador','mesa','comissao','executivo','cidadao') (mig 0013). Com so' o distrator
      ;; 'executivo', degradar o filtro para [:<> :autor_tipo "executivo"] passaria despercebido — e creditar
      ;; ato de COMISSAO a um vereador e' a mesma classe de erro do achado N-1.
      (projetar-materia! ente pid-com {:autor-tipo "comissao" :autor-id (str vereador)
                                       :autor-texto "Comissao de Financas" :ementa "Autoria virou comissao"})
      (let [p (controllers/perfil-parlamentar *repo-transparencia* ente vereador janela-larga)]
        (is (= 1 (count (:materias p)))
            "as de autoria 'executivo'/'comissao' sao filtradas — so' a parlamentar legitima permanece")
        (is (= 1 (:materias-total p)) "o total tambem so' conta a autoria parlamentar")
        (is (= pid-ok (:proposicao-id (first (:materias p)))))))))

;; ---------- (c) acervo legado sem elo ----------

(deftest materia-legada-sem-autor-id-nao-aparece
  (testing "materia protocolada antes da mig 0063 (sem autor_id) nao aparece em perfil nenhum"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          _legada  (protocolar! ente "Acervo legado sem elo" {:autor-tipo "vereador"
                                                              :autor-texto "Helena Past"})
          pid-nova (protocolar! ente "Materia com elo"
                                {:autor-tipo "vereador" :autor-id vereador :autor-texto "Helena Past"})]
      (drenar!)
      (let [p (controllers/perfil-parlamentar *repo-transparencia* ente vereador janela-larga)]
        (is (= 1 (count (:materias p))) "so' a materia COM o elo autor_id")
        (is (= pid-nova (:proposicao-id (first (:materias p)))))))))

;; ---------- (d) o numero-card 'viraram lei' ----------

(deftest contar-normas-conta-so-as-publicadas-do-autor-certo
  (testing "'viraram lei' conta so' materia DESTE vereador (autoria parlamentar) COM norma publicada"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          outro    (random-uuid)
          ;; Ordem de PROTOCOLO deliberadamente OPOSTA a ordem esperada de exibicao: o mais ANTIGO
          ;; (2025) entra primeiro, entao a ordem fisica/insercao das linhas nao pode ser confundida
          ;; com a ordem que o ORDER BY produz.
          pid-lei  (protocolar! ente "Virou lei"
                                {:ano 2025 :autor-tipo "vereador" :autor-id vereador
                                 :autor-texto "Helena Past"})
          pid-nova (protocolar! ente "Ainda tramitando"
                                {:ano 2026 :autor-tipo "vereador" :autor-id vereador
                                 :autor-texto "Helena Past"})
          pid-outro (random-uuid)
          pid-exec  (random-uuid)
          pid-com   (random-uuid)]
      (publicar-norma! ente pid-lei "Virou lei")
      (drenar!)
      ;; distrator 1: norma publicada de OUTRO vereador
      (projetar-materia! ente pid-outro {:autor-tipo "vereador" :autor-id (str outro)
                                         :autor-texto "Ciclano" :ementa "Lei de outro vereador"})
      (projetar-norma! ente pid-outro)
      ;; distrator 2: norma publicada de materia cuja autoria virou 'executivo' (achado N-1 no card)
      (projetar-materia! ente pid-exec {:autor-tipo "executivo" :autor-id (str vereador)
                                        :autor-texto "Prefeitura" :ementa "Lei do executivo"})
      (projetar-norma! ente pid-exec)
      ;; distrator 3: autoria de COMISSAO — o filtro precisa ser IGUALDADE a 'vereador' e nao
      ;; "diferente de executivo" (vocabulario da coluna tem 5 valores, mig 0013).
      (projetar-materia! ente pid-com {:autor-tipo "comissao" :autor-id (str vereador)
                                       :autor-texto "Comissao de Financas" :ementa "Lei da comissao"})
      (projetar-norma! ente pid-com)
      (let [p (controllers/perfil-parlamentar *repo-transparencia* ente vereador janela-larga)]
        (is (= 1 (:normas-de-autoria p))
            "so' a materia deste vereador, autoria parlamentar, com norma publicada")
        (is (= [pid-nova pid-lei] (mapv :proposicao-id (:materias p)))
            "a lista sai por NUMERACAO decrescente (ano, sequencial) — e so' as parlamentares entram")
        (is (= 2 (:materias-total p)) "o total do universo bate com a lista quando nao ha truncamento"))
      (is (= 0 (:normas-de-autoria (controllers/perfil-parlamentar *repo-transparencia* (random-uuid) vereador janela-larga)))
          "outro ente nao ve a contagem"))))

;; ---------- (e) ordem estavel no EMPATE de numeracao ----------

(deftest lista-de-autoria-desempata-por-proposicao-id
  (testing "duas especies com o MESMO (ano, sequencial) saem em ordem deterministica, nao a do planner"
    ;; `sequencial` e' gapless por escopo 'tipo:ano' (legislativo/db/proposicao) — logo NAO e' unico por
    ;; (ente, ano): requerimento 1/2026 e projeto_lei 1/2026 empatam nas DUAS chaves do ORDER BY. Sem uma
    ;; terceira chave, a ordem da lista publica passa a depender do plano de execucao (Index Scan vs
    ;; Seq Scan+Sort) e pode trocar entre dois carregamentos sem nada ter mudado.
    (let [ente          (random-uuid)
          vereador      (random-uuid)
          [menor maior] (sort comparar-uuid-pg [(random-uuid) (random-uuid)])
          autoria       {:autor-tipo "vereador" :autor-id vereador :autor-texto "Helena Past"}]
      ;; protocola o MENOR primeiro: a ordem de insercao fica oposta a esperada (proposicao_id DESC)
      (protocolar! ente "Requerimento de informacao"
                   (merge autoria {:id menor :tipo "requerimento" :tipo-requerimento "informacao"}))
      (protocolar! ente "Projeto de lei" (merge autoria {:id maior :tipo "projeto_lei"}))
      (drenar!)
      (let [p (controllers/perfil-parlamentar *repo-transparencia* ente vereador janela-larga)]
        (is (= #{1} (set (map :sequencial (:materias p))))
            "premissa do caso: as duas materias empatam mesmo em (ano, sequencial)")
        (is (= [maior menor] (mapv :proposicao-id (:materias p)))
            "desempate por proposicao_id DESC (ordem UNSIGNED do Postgres, nao a de UUID/compareTo)")))))

;; ---------- (f) truncamento honesto: o teto de 200 vs o total ----------

(deftest materias-total-revela-o-truncamento-do-teto
  (testing "acima do teto, a lista trunca em 200 e :materias-total ainda diz quantas existem"
    ;; Sem este sinal, um vereador com 260 materias (15 delas ja' lei, fora das 200 primeiras) recebe um
    ;; card '15 viraram lei' e uma lista onde nenhuma das 15 aparece — e a borda nao tem como dizer
    ;; "mostrando 200 de 260". `contar-normas-por-autor` nao tem teto; `listar-por-autor` tem.
    (let [ente     (random-uuid)
          vereador (random-uuid)]
      (projetar-lote! ente vereador 205)
      (let [p (controllers/perfil-parlamentar *repo-transparencia* ente vereador janela-larga)]
        (is (= 200 (count (:materias p))) "a lista para no teto server-side")
        (is (= 205 (:materias-total p)) "o total conta o universo INTEIRO, nao as linhas devolvidas")))))

;; ---------- (g) o numerador de presenca: vocabulario real de `sessoes` (Onda E, carry I-5 fatia 1) ----------

(deftest fixture-de-presenca-recusa-vocabulario-fora-de-sessoes-logic
  (testing "o validador trava contra `sessoes.logic` — semear o vocabulario ficticio antigo LANCA"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sp/validar-vocabulario!
                  {:tipo "presente" :modalidade "plenario" :fonte "manual_secretaria"}))
        "'presente' NUNCA foi emitido por produtor nenhum — e' exatamente o valor do numerador morto")
    (is (thrown? clojure.lang.ExceptionInfo
                 (sp/validar-vocabulario!
                  {:tipo "entrada" :modalidade "presencial" :fonte "manual_secretaria"}))
        "'presencial' nao esta' em modalidades-presenca (plenario|remoto)")
    (is (thrown? clojure.lang.ExceptionInfo
                 (sp/validar-vocabulario!
                  {:tipo "entrada" :modalidade "plenario" :fonte "mesa"}))
        "'mesa' nao esta' em fontes-presenca (manual_secretaria|painel_eletronico|...)")
    (is (= "entrada" (:tipo (sp/validar-vocabulario!
                             {:tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria"})))
        "o vocabulario REAL passa e devolve o proprio payload — a trava nao e' um `throw` incondicional"))
  (testing "a FIXTURE (nao so' o validador) recusa — a chamada dentro de `presenca!` esta' cabeada"
    ;; Sem esta assercao o deftest provava so' que o VALIDADOR funciona: apagar a chamada de dentro de
    ;; `presenca!` deixava a suite inteira verde e a fixture voltava a aceitar qualquer string. O guard lanca
    ;; ANTES de `com-tenant*`, entao este caso nao toca o banco.
    (is (thrown? clojure.lang.ExceptionInfo
                 (presenca! (random-uuid) (random-uuid) (random-uuid) "presente"))
        "`presenca!` com o vocabulario morto LANCA — a trava nao e' decoracao")))

(deftest numerador-com-vocabulario-real-de-sessoes-deixa-de-ser-zero
  ;; A REGRESSAO DO BUG MORTO. O numerador comparava `tipo = 'presente'`, valor que produtor nenhum emite
  ;; (`sessoes/logic/tipos-evento-presenca` = entrada|saida|retorno|mudanca_modalidade, espelhando o CHECK da
  ;; mig 0029) — logo `sessoes_presente` valia ZERO para TODO parlamentar em producao, com o denominador
  ;; cheio. Este teste so' pode passar se o predicado de `tipo` tiver morrido.
  (testing "sessao com 'entrada' projetada conta no numerador"
    (let [ente     (random-uuid)
          vereador (random-uuid)]
      (presenca! ente (random-uuid) vereador "entrada")
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador janela-larga))]
        (is (= 1 (:sessoes-presente p)) "com o vocabulario REAL o numerador deixa de ser zero")
        (is (= 1 (:sessoes-com-chamada p)))))))

(deftest numerador-conta-sessao-cujo-unico-evento-projetado-e-saida
  ;; PINA A AUSENCIA DELIBERADA DO FILTRO POR `tipo`. `presenca_parlamentar` guarda o ESTADO ATUAL por
  ;; (sessao, vereador) e o UPSERT mantem o evento de maior `ocorrido_em`: quem entrou e saiu termina a sessao
  ;; com `tipo = 'saida'`. Ausencia NUNCA e' gravada (`sessoes/relacoes/presenca`: "sem evento ate' la =
  ;; ausente") e nao existe chamada em lote — entao TER LINHA == COMPARECEU, e 'saida' e' comparecimento.
  ;; Se um dev futuro "consertar" o codigo reintroduzindo `WHERE tipo IN (...positivos)`, este teste quebra
  ;; ANTES de o numero publico mudar em silencio.
  (testing "o vereador que entrou e saiu compareceu — 'saida' nao e' falta"
    (let [ente     (random-uuid)
          vereador (random-uuid)]
      (presenca! ente (random-uuid) vereador "saida")
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador janela-larga))]
        (is (= 1 (:sessoes-presente p)) "quem assinou e saiu compareceu")
        (is (= 1 (:sessoes-com-chamada p)))))))

(deftest numerador-nao-conta-sessao-em-que-so-outro-vereador-tem-linha
  ;; O distrator do predicado `vereador_id` no NUMERADOR: a RLS isola por TENANT, nao por vereador. Sem o
  ;; predicado, a presenca do colega vira presenca deste — numa pagina publica e NOMINAL.
  (testing "sessao em que so' o OUTRO vereador tem linha entra no denominador, nunca no numerador"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          outro    (random-uuid)]
      (presenca! ente (random-uuid) vereador "entrada")
      (presenca! ente (random-uuid) outro    "entrada")
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador janela-larga))]
        (is (= 1 (:sessoes-presente p)) "so' a sessao em que ESTE vereador tem linha")
        (is (= 2 (:sessoes-com-chamada p)) "as duas sessoes tiveram chamada")))))

(deftest presenca-conta-SESSOES-e-nao-LINHAS-nos-dois-lados-da-fracao
  ;; ACHADO DE MUTACAO (revisao da fatia 1), o unico MAJOR: nenhum teste do repo semeava DUAS linhas na MESMA
  ;; sessao nem o MESMO vereador em DUAS sessoes. Com uma linha por sessao, `COUNT(DISTINCT sessao_id)` e
  ;; `COUNT(*)` dao sempre o mesmo numero (o DISTINCT do denominador nunca era exercitado — mutacao aplicada e
  ;; VERIFICADA sobrevivendo), e qualquer numerador que colapse num booleano (ex.: `COUNT(DISTINCT
  ;; vereador_id) FILTER`) tambem passava. Aqui sao 4 LINHAS em 3 SESSOES, com o vereador-alvo em 2 delas:
  ;;   sessao-a: o vereador E o outro  (2 linhas, 1 sessao — mata `count(*)` no denominador: daria 4)
  ;;   sessao-b: so' o vereador        (numerador > 1 — mata o numerador colapsado em 0/1: daria 1)
  ;;   sessao-c: so' o outro           (o distrator do predicado `vereador_id`)
  ;; Sem isto, uma Casa de 21 vereadores publicaria '38 de 840' em vez de '38 de 40' numa pagina nominal.
  (testing "4 linhas em 3 sessoes: numerador 2, denominador 3"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          outro    (random-uuid)
          sessao-a (random-uuid)
          sessao-b (random-uuid)
          sessao-c (random-uuid)]
      (presenca! ente sessao-a vereador "entrada")
      (presenca! ente sessao-a outro    "entrada")
      (presenca! ente sessao-b vereador "entrada")
      (presenca! ente sessao-c outro    "entrada")
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador janela-larga))]
        (is (= 2 (:sessoes-presente p))
            "numerador conta SESSOES em que ele tem linha (2), nao 'tem alguma linha' (1)")
        (is (= 3 (:sessoes-com-chamada p))
            "denominador conta SESSOES com chamada (3), nao LINHAS de presenca (4)")))))

;; ---------- (h) o denominador RECORTADO PELA JANELA DE EXERCICIO (carry I-5 fatia 6 — fecha o I-5) --------
;;
;; A janela chega PRONTA da borda. Os casos abaixo que falam de mandato/licenca a produzem pela MESMA defn
;; pura do host que a rota usa (`rotas/janelas-de-exercicio`), e nao por um literal escrito aqui: escrever a
;; janela a mao provaria so' que o SQL sabe filtrar `data`, e deixaria a traducao mandato->janela (que e'
;; onde moram o `fim-efetivo`, a licenca por stint e o vao entre stints) sem detector NESTE lado.
;; O ns de teste PODE requerer `oplenario.rotas` e `cadastros` — o import-lint da §22.10 roda so' sobre `src`.

(defn- mandato
  "Linha de `cadastros.mandato` na forma que `mandatos-do-vereador` devolve (kebab, `date` -> LocalDate).
  So' as 4 chaves que `janelas-de-exercicio` le — as demais existem no banco e nao interessam aqui."
  ([id inicio fim] (mandato id inicio fim nil))
  ([id inicio fim fim-efetivo]
   {:id id
    :vigencia-inicio (LocalDate/parse inicio)
    :vigencia-fim (some-> fim LocalDate/parse)
    :fim-efetivo (some-> fim-efetivo LocalDate/parse)}))

(defn- licenca
  "Linha de `cadastros.mandato_licenca` como `licencas-de-mandatos` devolve. `fim` nil = licenca EM CURSO."
  [mandato-id inicio fim]
  {:mandato-id mandato-id :inicio (LocalDate/parse inicio) :fim (some-> fim LocalDate/parse)})

(deftest faltoso-cronico-sem-nenhuma-linha-publica-zero-de-denominador-cheio
  ;; O TESTE CENTRAL da fatia. O denominador NAO pode ter `vereador_id` no predicado: se tiver, o vereador
  ;; que faltou a tudo some num "0 de 0" (indistinguivel de "nao ha sessoes") em vez de publicar "0 de 12".
  ;; Falsificavel de proposito: quem meter `vereador_id` no WHERE do denominador derruba este deftest.
  (testing "vereador em exercicio que nao compareceu a NENHUMA sessao recebe o denominador cheio da janela"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          outro    (random-uuid)
          janelas  (rotas/janelas-de-exercicio [(mandato (random-uuid) "2025-01-01" "2028-12-31")] [])]
      (doseq [d ["2026-03-10" "2026-03-17" "2026-03-24"]]
        (presenca! ente (random-uuid) outro "entrada" (dia d)))
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador janelas))]
        (is (= 0 (:sessoes-presente p)) "ele nao compareceu a nenhuma")
        (is (= 3 (:sessoes-com-chamada p))
            "o denominador conta as sessoes da JANELA DELE, e nao so' aquelas em que ele tem linha")
        (is (true? (:janela-de-exercicio-conhecida p))
            "ha' periodo de exercicio registrado — '0 de 3' e' uma afirmacao, nao um vazio")))))

(deftest suplente-de-tres-sessoes-nao-recebe-o-denominador-da-legislatura
  ;; A INJUSTICA QUE ABRIU O CARRY I-5. Antes da fatia 6 este suplente publicava "3 de 12" (o denominador da
  ;; Casa inteira) numa pagina publica e NOMINAL. Agora o denominador comeca na convocacao e acaba no fim dela.
  (testing "convocado para 3 sessoes: 3 de 3, nao 3 do total da Casa"
    (let [ente     (random-uuid)
          suplente (random-uuid)
          titular  (random-uuid)
          janelas  (rotas/janelas-de-exercicio [(mandato (random-uuid) "2026-04-01" "2026-04-30")] [])]
      ;; 6 sessoes na Casa; so' 3 caem dentro da convocacao, e o suplente compareceu as 3.
      (doseq [d ["2026-02-03" "2026-03-03" "2026-05-05"]]
        (presenca! ente (random-uuid) titular "entrada" (dia d)))
      (doseq [d ["2026-04-07" "2026-04-14" "2026-04-21"]]
        (let [s (random-uuid)]
          (presenca! ente s titular "entrada" (dia d))
          (presenca! ente s suplente "entrada" (dia d))))
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente suplente janelas))]
        (is (= 3 (:sessoes-presente p)))
        (is (= 3 (:sessoes-com-chamada p))
            "as 3 sessoes fora da convocacao NAO entram — '3 de 6' seria a injustica do I-5 de volta")
        (is (true? (:janela-de-exercicio-conhecida p)))))))

(deftest empossado-no-meio-nao-conta-sessoes-anteriores-a-posse
  (testing "o denominador comeca na data da posse, nao no inicio da legislatura"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          outro    (random-uuid)
          janelas  (rotas/janelas-de-exercicio [(mandato (random-uuid) "2026-06-01" "2028-12-31")] [])]
      (presenca! ente (random-uuid) outro    "entrada" (dia "2026-05-31"))  ; vespera da posse
      (presenca! ente (random-uuid) vereador "entrada" (dia "2026-06-01"))  ; o proprio dia da posse
      (presenca! ente (random-uuid) outro    "entrada" (dia "2026-06-08"))
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador janelas))]
        (is (= 1 (:sessoes-presente p)))
        (is (= 2 (:sessoes-com-chamada p))
            "a sessao da vespera fica fora; a do PROPRIO dia da posse entra (janela INCLUSIVA nos dois lados)")))))

(deftest ex-vereador-usa-janela-historica-mesmo-sem-mandato-vigente
  ;; A razao de a janela vir de `mandatos-do-vereador` (TODOS os stints) e nunca de `mandato-vigente`
  ;; (LIMIT 1 em `hoje`, nil para quem saiu): o perfil historico e' justamente onde a janela mais importa.
  (testing "mandato encerrado em 2024 ainda produz janela — e o denominador e' o daquele periodo"
    (let [ente     (random-uuid)
          ex       (random-uuid)
          atual    (random-uuid)
          janelas  (rotas/janelas-de-exercicio [(mandato (random-uuid) "2021-01-01" "2024-12-31")] [])]
      (presenca! ente (random-uuid) ex    "entrada" (dia "2024-11-12"))
      (presenca! ente (random-uuid) atual "entrada" (dia "2026-03-10"))
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente ex janelas))]
        (is (= 1 (:sessoes-presente p)))
        (is (= 1 (:sessoes-com-chamada p)) "a sessao de 2026 nao e' dele — ele nao era mais vereador")
        (is (true? (:janela-de-exercicio-conhecida p))
            "ex-vereador TEM periodo de exercicio conhecido — o que nao tem e' mandato vigente")))))

(deftest licenciado-nao-paga-pelas-sessoes-do-periodo-de-licenca
  (testing "o periodo de licenca e' subtraido do denominador"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          outro    (random-uuid)
          mid      (random-uuid)
          janelas  (rotas/janelas-de-exercicio [(mandato mid "2025-01-01" "2028-12-31")]
                                               [(licenca mid "2026-03-01" "2026-03-31")])]
      (presenca! ente (random-uuid) vereador "entrada" (dia "2026-02-24"))  ; antes da licenca
      (presenca! ente (random-uuid) outro    "entrada" (dia "2026-03-10"))  ; DURANTE a licenca
      (presenca! ente (random-uuid) outro    "entrada" (dia "2026-03-31"))  ; ultimo dia da licenca
      (presenca! ente (random-uuid) vereador "entrada" (dia "2026-04-07"))  ; depois da licenca
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador janelas))]
        (is (= 2 (:sessoes-presente p)))
        (is (= 2 (:sessoes-com-chamada p))
            "as duas sessoes do periodo de licenca somem dos DOIS lados — '2 de 4' difamaria o licenciado")))))

(deftest cassado-nao-conta-sessoes-posteriores-ao-fim-efetivo
  ;; `mudar-estado!` carimba `fim_efetivo` e NAO fecha `vigencia_fim`. Sem olhar `fim-efetivo` o cassado
  ;; seguiria acumulando denominador ate' 2028 — e publicando queda de presenca por sessoes que ocorreram
  ;; depois de ele deixar a Casa.
  (testing "a janela fecha em fim-efetivo, e nao no fim nominal da vigencia"
    (let [ente     (random-uuid)
          cassado  (random-uuid)
          outro    (random-uuid)
          janelas  (rotas/janelas-de-exercicio
                    [(mandato (random-uuid) "2025-01-01" "2028-12-31" "2026-03-15")] [])]
      (presenca! ente (random-uuid) cassado "entrada" (dia "2026-03-10"))
      (presenca! ente (random-uuid) outro   "entrada" (dia "2026-03-20"))
      (presenca! ente (random-uuid) outro   "entrada" (dia "2026-06-02"))
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente cassado janelas))]
        (is (= 1 (:sessoes-presente p)))
        (is (= 1 (:sessoes-com-chamada p))
            "so' a sessao anterior ao fim efetivo — '1 de 3' contaria faltas de quem ja' nao era vereador")))))

(deftest multiplos-stints-nao-contam-a-mesma-sessao-duas-vezes
  ;; O denominador e' `count(*)` sobre `sessao_com_chamada` com um OR de intervalos no WHERE. Se dois
  ;; intervalos se sobrepusessem (ou se o predicado virasse um JOIN com a lista de janelas), a MESMA sessao
  ;; entraria duas vezes e o denominador ficaria maior que o numero de sessoes que existem.
  (testing "dois stints que se tocam produzem UM intervalo e a sessao da fronteira conta uma vez so'"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          janelas  (rotas/janelas-de-exercicio [(mandato (random-uuid) "2026-01-01" "2026-03-31")
                                                (mandato (random-uuid) "2026-03-01" "2026-06-30")]
                                               [])]
      (is (= 1 (count janelas)) "premissa do caso: os stints sobrepostos ja' foram fundidos em UM intervalo")
      (presenca! ente (random-uuid) vereador "entrada" (dia "2026-03-10"))
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador janelas))]
        (is (= 1 (:sessoes-presente p)) "o numerador tambem nao duplica")
        (is (= 1 (:sessoes-com-chamada p)) "uma sessao, uma contagem"))
      (testing "e mesmo com intervalos SOBREPOSTOS crus (sem passar pela normalizacao) a sessao conta uma vez"
        ;; Detector do shape do predicado: um OR de intervalos num unico WHERE nao pode duplicar linha; um
        ;; JOIN/UNION ALL contra a lista de janelas duplicaria. O contrato diz que a janela chega normalizada
        ;; — este caso e' o que garante que a garantia nao depende disso.
        (let [cruas [{:inicio (LocalDate/parse "2026-01-01") :fim (LocalDate/parse "2026-03-31")}
                     {:inicio (LocalDate/parse "2026-03-01") :fim (LocalDate/parse "2026-06-30")}]
              p     (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador cruas))]
          (is (= 1 (:sessoes-com-chamada p)))
          (is (= 1 (:sessoes-presente p))))))))

(deftest vao-entre-stints-nao-entra-no-denominador
  (testing "suplente reconvocado: os meses em que ele NAO era vereador ficam fora dos dois lados"
    (let [ente     (random-uuid)
          suplente (random-uuid)
          titular  (random-uuid)
          janelas  (rotas/janelas-de-exercicio [(mandato (random-uuid) "2026-02-01" "2026-02-28")
                                                (mandato (random-uuid) "2026-06-01" "2026-06-30")]
                                               [])]
      (is (= 2 (count janelas)) "premissa: dois intervalos DISJUNTOS, o vao entre eles nao e' exercicio")
      (presenca! ente (random-uuid) suplente "entrada" (dia "2026-02-10"))
      (presenca! ente (random-uuid) titular  "entrada" (dia "2026-04-14"))  ; no VAO
      (presenca! ente (random-uuid) suplente "entrada" (dia "2026-06-09"))
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente suplente janelas))]
        (is (= 2 (:sessoes-presente p)))
        (is (= 2 (:sessoes-com-chamada p))
            "a sessao de abril nao entra: fundir os dois stints num intervalo unico a traria de volta")))))

(deftest sem-mandato-devolve-zero-de-zero-com-janela-desconhecida-e-sem-tocar-o-banco
  ;; Chamado no nivel do `db/` de proposito: o `tx` e' um KEYWORD, nao uma conexao. Se o ramo de janela vazia
  ;; emitir SQL, o next.jdbc estoura aqui. E' o unico jeito de provar "SEM tocar o banco" — no nivel do
  ;; controller a tx e' aberta antes, e a economia (uma rota PUBLICA e anonima, sem cache) sumiria do teste.
  (testing "janelas vazias curto-circuitam a leitura inteira"
    (let [r (db-parlamentar/resumo-presenca ::tx-envenenada (random-uuid) (random-uuid) [])]
      (is (= {:sessoes-com-chamada 0 :sessoes-presente 0 :janela-de-exercicio-conhecida false} r)
          "0/0 declarado como DESCONHECIDO — a tela DEVE dizer 'sem periodo de exercicio registrado'"))))

(deftest janela-vazia-nunca-cai-no-denominador-global-como-fallback
  ;; A REGRA NEGATIVA. Um `(if (seq janelas) ... <denominador do ente inteiro>)` "defensivo" republicaria o
  ;; I-5 exatamente na janela em que ninguem esta olhando: o eleito nao empossado receberia o denominador da
  ;; Casa e um 0% publico e nominal.
  (testing "com sessoes no ente e janela vazia, o denominador e' 0 — nunca o do ente"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          outro    (random-uuid)]
      (doseq [d ["2026-03-10" "2026-03-17" "2026-03-24"]]
        (presenca! ente (random-uuid) outro "entrada" (dia d)))
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador []))]
        (is (= 0 (:sessoes-com-chamada p)) "o ente TEM 3 sessoes com chamada e nenhuma delas entra")
        (is (= 0 (:sessoes-presente p)))
        (is (false? (:janela-de-exercicio-conhecida p))
            "e o wire diz POR QUE e' zero — sem isso a tela nao distingue 'faltou a tudo' de 'sem mandato'")))))

(deftest numerador-nunca-excede-o-denominador
  ;; O numerador e' um JOIN com o MESMO conjunto elegivel, entao a desigualdade e' por construcao. O caso que
  ;; a quebraria e' o numerador ignorando a janela: aqui o vereador tem linha numa sessao FORA da janela.
  (testing "sessao do vereador fora da janela nao infla o numerador"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          janelas  (rotas/janelas-de-exercicio [(mandato (random-uuid) "2026-06-01" "2026-06-30")] [])]
      (presenca! ente (random-uuid) vereador "entrada" (dia "2026-01-20"))  ; ANTES da janela
      (presenca! ente (random-uuid) vereador "entrada" (dia "2026-06-09"))  ; dentro
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador janelas))]
        (is (= 1 (:sessoes-presente p)) "so' a sessao de dentro da janela")
        (is (= 1 (:sessoes-com-chamada p)))
        (is (<= (:sessoes-presente p) (:sessoes-com-chamada p))
            "numerador <= denominador: '2 de 1' seria uma fracao impossivel numa pagina publica")))))

(deftest vereador-que-entrou-e-saiu-conta-como-comparecimento
  ;; A ausencia deliberada do filtro por `tipo` sobrevive a fatia 6 (a query foi REESCRITA — se o `tipo`
  ;; voltasse no caminho, seria aqui). `presenca_parlamentar` guarda o ESTADO ATUAL e quem entrou e saiu
  ;; termina com `tipo = 'saida'`.
  (testing "'saida' dentro da janela e' comparecimento"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          janelas  (rotas/janelas-de-exercicio [(mandato (random-uuid) "2026-01-01" "2026-12-31")] [])]
      (presenca! ente (random-uuid) vereador "saida" (dia "2026-05-18"))
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador janelas))]
        (is (= 1 (:sessoes-presente p)) "quem assinou e saiu compareceu")
        (is (= 1 (:sessoes-com-chamada p)))))))

;; ---------- (i) buracos da revisao da fatia 6: as DUAS bordas do predicado, o `nil` de fiacao, o quarto
;;                estado da janela vazia, e os dois pins de catalogo/migration que nenhum assert
;;                comportamental enxerga ----------

(deftest sessao-no-ultimo-dia-da-janela-conta
  ;; ACHADO DE MUTACAO (revisao da fatia 6, MAJOR): trocar `[:<= :data fim]` por `[:< :data fim]` em
  ;; `predicado-de-janela` sobrevivia ao ns INTEIRO — nenhum dos 10 casos com janela FECHADA semeava uma
  ;; sessao cuja data fosse exatamente o `:fim` de alguma janela. A decisao publica intervalos INCLUSIVOS
  ;; nos DOIS lados e a borda ESQUERDA ja' tinha detector (`empossado-no-meio-...`, sessao no PROPRIO dia
  ;; da posse); esta e' a direita. Em producao a mutacao apaga a sessao do ULTIMO dia de exercicio dos dois
  ;; lados — e, se a pessoa faltou a ela, so' o denominador cai e a fracao publicada MELHORA (lavagem do
  ;; faltoso, a mesma classe de defeito que reprovou a Forma C1). No caso de massa e' a ultima sessao do ano
  ;; em `vigencia_fim = 2028-12-31`, que sairia do denominador dos 21 vereadores de uma vez.
  (testing "a sessao do PROPRIO dia em que a janela fecha entra nos dois lados da fracao"
    (let [ente     (random-uuid)
          cassado  (random-uuid)
          outro    (random-uuid)
          janelas  (rotas/janelas-de-exercicio
                    [(mandato (random-uuid) "2025-01-01" "2028-12-31" "2026-03-15")] [])]
      (presenca! ente (random-uuid) cassado "entrada" (dia "2026-03-15"))  ; o PROPRIO dia do fim
      (presenca! ente (random-uuid) outro   "entrada" (dia "2026-03-16"))  ; o dia seguinte
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente cassado janelas))]
        (is (= 1 (:sessoes-presente p))
            "o ultimo dia de exercicio e' exercicio — `<` no lugar de `<=` zeraria o numerador aqui")
        (is (= 1 (:sessoes-com-chamada p))
            "e o denominador tambem: a sessao do dia seguinte fica fora, a do proprio dia entra")))))

(deftest mandato-em-aberto-nao-conta-sessao-anterior-a-posse
  ;; ACHADO DE MUTACAO (revisao da fatia 6, MAJOR): o ramo `else` de `predicado-de-janela` (janela EM
  ;; ABERTO, `:fim` nil) podia perder o `[:>= :data inicio]` inteiro — substitui-lo por `[:= 1 1]` deixava o
  ;; ns verde. Todos os casos que exercitavam esse ramo usavam `janela-larga` (inicio 2000-01-01), anterior
  ;; a TODA sessao semeada, entao o `:inicio` era irrelevante. E' o ramo MAIS provavel em producao:
  ;; `RegistrarMandato` tem `vigencia_fim {:optional true}`, nao ha PATCH de mandato, e a propria docstring
  ;; de `rotas/janelas-de-exercicio` registra que mandato aberto = janela ABERTA. A mutacao republica o I-5
  ;; na sua forma original: o suplente empossado em junho recebe o denominador da serie INTEIRA do ente,
  ;; agora com `:janela-de-exercicio-conhecida true` — o pior estado, porque a tela o apresenta como firme.
  (testing "janela sem `:fim` continua recortando pela POSSE, nao vira 'tudo'"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          outro    (random-uuid)
          janelas  (rotas/janelas-de-exercicio [(mandato (random-uuid) "2026-06-01" nil)] [])]
      (is (= [nil] (mapv :fim janelas)) "premissa do caso: a janela e' a EM ABERTO, nao uma fechada")
      (presenca! ente (random-uuid) outro    "entrada" (dia "2026-05-31"))  ; vespera da posse
      (presenca! ente (random-uuid) vereador "entrada" (dia "2026-06-02"))
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador janelas))]
        (is (= 1 (:sessoes-presente p)))
        (is (= 1 (:sessoes-com-chamada p))
            "a sessao da vespera fica fora — sem o `>= inicio` o denominador viraria 2 (o do ente)")))))

(deftest janelas-nil-e-bug-de-servidor-nao-afirmacao-publica
  ;; ACHADO (revisao da fatia 6, dois revisores): `(empty? janelas)` e' verdadeiro para `[]` E para `nil`,
  ;; e o `:pre` guardava so' `ente-id`/`vereador-id`. `[]` tem significado de DOMINIO ('sem periodo de
  ;; exercicio registrado', publicado como tal numa pagina NOMINAL); `nil` so' pode ser erro de FIACAO — o
  ;; handler destrutura `{:keys [ficha janelas]}` e uma chave ausente vira nil sem ruido. Colapsados, um
  ;; seam mal montado fazia TODOS os vereadores de TODAS as Casas responderem 200 com 'a Camara nao tem
  ;; periodo de exercicio registrado para este parlamentar', sem log, sem 500 e sem teste vermelho.
  (testing "`nil` estoura (500 opaco na borda); `[]` segue sendo o 0/0 declarado"
    (is (thrown? AssertionError
                 (db-parlamentar/resumo-presenca ::tx-envenenada (random-uuid) (random-uuid) nil))
        "ausencia de fiacao NAO pode virar afirmacao publica bem-formada")
    (is (= {:sessoes-com-chamada 0 :sessoes-presente 0 :janela-de-exercicio-conhecida false}
           (db-parlamentar/resumo-presenca ::tx-envenenada (random-uuid) (random-uuid) []))
        "`[]` continua sendo o caminho legitimo — a guarda nova nao o fecha")))

(deftest licenca-que-cobre-o-stint-inteiro-tambem-devolve-janela-vazia
  ;; ACHADO (revisao da fatia 6, MENOR): `janelas-de-exercicio` devolve `[]` em DOIS casos semanticamente
  ;; distintos — (a) nao ha mandato registrado e (b) ha mandato, mas a licenca consome o stint inteiro
  ;; (licenca com `fim` nil comecando no primeiro dia, caminho de PRIMEIRA CLASSE no wire `RegistrarLicenca`).
  ;; O wire publica os dois como `:janela-de-exercicio-conhecida false` = 'a Casa NAO tem periodo de
  ;; exercicio registrado', o que e' FALSO no caso (b). Nao ha conserto de codigo aqui — o caso (b) esta'
  ;; atado ao CARRY ABERTO da licenca irreversivel (decisao do Daouda) — mas ele passa a estar ESCRITO nas
  ;; duas docstrings e PINADO aqui, em vez de ser um estado que ninguem sabe que existe.
  (testing "mandato coberto por licenca sem fim: 0/0 declarado desconhecido, e nunca o denominador global"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          outro    (random-uuid)
          mid      (random-uuid)
          janelas  (rotas/janelas-de-exercicio [(mandato mid "2026-01-01" "2026-12-31")]
                                               [(licenca mid "2026-01-01" nil)])]
      (is (= [] janelas) "premissa: a licenca consome o stint inteiro — mandato EXISTE, exercicio nao")
      (presenca! ente (random-uuid) outro "entrada" (dia "2026-03-10"))
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador janelas))]
        (is (= 0 (:sessoes-com-chamada p)) "a sessao do ente nao entra — nada de fallback global")
        (is (false? (:janela-de-exercicio-conhecida p))
            "o wire nao distingue 'sem mandato' de 'mandato inteiro sob licenca' — limite declarado")))))

(deftest indice-do-numerador-por-vereador-esta-no-catalogo
  ;; ACHADO DE MUTACAO (revisao da fatia 6, MEDIO): o `CREATE INDEX` da mig 0069 e' a justificativa
  ;; economica INTEIRA da fatia (652 -> 50 buffers no perfil titular, medido no proprio commit) e nenhum
  ;; deftest o observava — contagem nao ve plano, entao apagar o indice deixava os 16 casos verdes. Molde
  ;; literal do que a fatia 5 ja' criou para a companheira
  ;; (`companheira-tem-force-rls-e-o-indice-de-data-no-catalogo`). O repo ja' errou de indice DUAS vezes na
  ;; MESMA tabela (a 0065 criou um que leitor nenhum usava; a 0069 o dropou por redundancia) e migration
  ;; aplicada e' IMUTAVEL: sem detector, um `DROP INDEX` "de faxina" numa migration futura devolve o
  ;; numerador ao Seq Scan com a suite inteira verde.
  (let [indices (map :indexdef
                     (jdbc/execute! *ds*
                       ["SELECT indexdef FROM pg_indexes
                          WHERE schemaname = 'transparencia' AND tablename = 'presenca_parlamentar'"]
                       {:builder-fn rs/as-unqualified-kebab-maps}))]
    (is (some #(re-find #"\(ente_id, vereador_id, sessao_id\)" %) indices)
        "o indice (ente_id, vereador_id, sessao_id) e' a diferenca entre Index Only Scan e Seq Scan no
         NUMERADOR; trocar por outra forma exige trocar este assert A MAO, com medicao anexada")
    (is (not-any? #(re-find #"idx_presenca_parlamentar_ente\b" %) indices)
        "e o (ente_id) puro — prefixo estrito da PK — segue dropado pela 0069, para nao voltar por descuido")))

(deftest migration-analisa-as-duas-tabelas-de-presenca-depois-do-backfill
  ;; ACHADO (revisao da fatia 6, MEDIO): o cliff de estatisticas pos-deploy estava so' no RUNBOOK. Logo
  ;; apos o backfill da 0067 (bulk load numa tabela nova, `reltuples` = -1) o planner escolhe Nested Loop
  ;; com CTE Scan no lado INTERNO — o CTE `elegivel` e' referenciado DUAS vezes, entao o PG12+ o MATERIALIZA
  ;; e o lado interno re-varre o tuplestore por linha externa. Medido: 253 ms (implementador) e ~940 ms
  ;; (revisor, bancada maior) contra ~1,1 ms com estatisticas, numa rota PUBLICA, anonima e sem cache, que
  ;; sobe assim que `migrate` termina (`app depends_on: service_completed_successfully`). O conserto barato
  ;; e' um `ANALYZE` no fim da corrente de migrations — legal dentro de bloco de transacao, ao contrario de
  ;; VACUUM. Este teste pina o statement: ele nao tem efeito observavel na suite (o fixture roda `migrar!`
  ;; com as tabelas vazias) e some sem ruido se alguem o remover.
  (let [recurso "migrations/20260722000070-transparencia-analyze-presenca.up.sql"
        arquivo (io/resource recurso)]
    (is (some? arquivo) (str "migration nao encontrada no classpath: " recurso))
    (let [corpo (str/upper-case (slurp arquivo))]
      (is (str/includes? corpo "ANALYZE TRANSPARENCIA.SESSAO_COM_CHAMADA")
          "sem estatisticas na companheira o CTE materializado vira O(sessoes^2)")
      (is (str/includes? corpo "ANALYZE TRANSPARENCIA.PRESENCA_PARLAMENTAR")
          "e sem elas na tabela do numerador o planner nao escolhe o indice da 0069"))))
