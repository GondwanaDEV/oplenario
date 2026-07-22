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
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
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
            [oplenario.transparencia.components.repositorio :as transparencia-repo]
            [oplenario.transparencia.controllers :as controllers]
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
  de teste de presenca) ANTES de tocar o banco — semear vocabulario ficticio LANCA aqui."
  [ente sessao vereador tipo]
  (let [payload (sp/validar-vocabulario!
                 {:sessao-id (str sessao) :vereador-id (str vereador) :tipo tipo
                  :modalidade "plenario" :fonte "manual_secretaria"
                  :ocorrido-em "2026-05-18T14:00:00Z"})]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (transparencia-repo/projetar-evento! tx
          {:tipo "presenca.registrada" :ente-id ente :payload payload})))))

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
      (let [p (controllers/perfil-parlamentar *repo-transparencia* ente vereador)]
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
          p    (controllers/perfil-parlamentar *repo-transparencia* ente (random-uuid))]
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
      (let [p (controllers/perfil-parlamentar *repo-transparencia* ente-a vereador)]
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
      (let [p (controllers/perfil-parlamentar *repo-transparencia* ente vereador)]
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
      (let [p (controllers/perfil-parlamentar *repo-transparencia* ente vereador)]
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
      (let [p (controllers/perfil-parlamentar *repo-transparencia* ente vereador)]
        (is (= 1 (:normas-de-autoria p))
            "so' a materia deste vereador, autoria parlamentar, com norma publicada")
        (is (= [pid-nova pid-lei] (mapv :proposicao-id (:materias p)))
            "a lista sai por NUMERACAO decrescente (ano, sequencial) — e so' as parlamentares entram")
        (is (= 2 (:materias-total p)) "o total do universo bate com a lista quando nao ha truncamento"))
      (is (= 0 (:normas-de-autoria (controllers/perfil-parlamentar *repo-transparencia* (random-uuid) vereador)))
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
      (let [p (controllers/perfil-parlamentar *repo-transparencia* ente vereador)]
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
      (let [p (controllers/perfil-parlamentar *repo-transparencia* ente vereador)]
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
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador))]
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
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador))]
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
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador))]
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
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador))]
        (is (= 2 (:sessoes-presente p))
            "numerador conta SESSOES em que ele tem linha (2), nao 'tem alguma linha' (1)")
        (is (= 3 (:sessoes-com-chamada p))
            "denominador conta SESSOES com chamada (3), nao LINHAS de presenca (4)")))))

(deftest denominador-nesta-fatia-ainda-e-do-ente-inteiro
  ;; PIN TEMPORARIO — some na fatia 6 do carry I-5. Documenta, em teste, o recorte que esta fatia NAO muda:
  ;; o denominador e' o ENTE INTEIRO, sem janela de exercicio do mandato. E' precisamente a injustica do I-5
  ;; (o suplente de 3 sessoes recebe o denominador da legislatura), e ela segue ABERTA depois desta fatia.
  ;; O lado bom do mesmo recorte, que a fatia 6 preserva de proposito: o faltoso cronico publica '0 de 3', e
  ;; nao some num '0 de 0'.
  (testing "vereador sem NENHUMA linha ainda recebe o denominador cheio do ente"
    (let [ente     (random-uuid)
          vereador (random-uuid)
          outro    (random-uuid)]
      (dotimes [_ 3] (presenca! ente (random-uuid) outro "entrada"))
      (let [p (:presenca (controllers/perfil-parlamentar *repo-transparencia* ente vereador))]
        (is (= 0 (:sessoes-presente p)) "ele nao compareceu a nenhuma")
        (is (= 3 (:sessoes-com-chamada p))
            "o denominador NAO tem vereador_id no predicado — o faltoso cronico nao vira '0 de 0'")))))
