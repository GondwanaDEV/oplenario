(ns oplenario.transparencia.sessao-com-chamada-test
  "INTEGRACAO (PG real) — carry I-5, fatia 5: a companheira INTRA-SCHEMA
  `transparencia.sessao_com_chamada` (mig 0067) e a manutencao dela pelo consumer de `presenca.registrada`.

  O QUE ESTA FATIA PROVA. O consumer passa de UM para DOIS statements na MESMA tx do relay: alem do UPSERT
  de `presenca_parlamentar` (estado atual por sessao+vereador), grava UMA linha por SESSAO com a DATA CIVIL
  do PRIMEIRO evento de presenca dela (`LEAST` no `ON CONFLICT`). Essa tabela e' o DENOMINADOR que a fatia 6
  vai recortar pela janela de exercicio do mandato — aqui ela sobe SEM LEITOR, de proposito, para a fatia
  ficar verde e bisectavel.

  O QUE ESTA FATIA **NAO** PROVA (limite honesto, escrito para nao ser re-descoberto):
  (a) o BACKFILL da migration 0067 nao e' exercitado por nenhum destes deftest — o fixture roda `migrar!`
      ANTES de qualquer linha existir, entao o `INSERT..SELECT..GROUP BY` dela sempre encontra a tabela de
      presenca vazia. A verificacao daquele backfill e' MANUAL (documentada no commit da fatia). REVISAO DA
      FATIA 5: o STATEMENT em si passou a ser testavel — o reconciliador da 0068 e' o mesmo `INSERT..SELECT`,
      e os dois ultimos deftest daqui o extraem do proprio `.up.sql` e o rodam contra dado semeado;
  (b) `resumo-presenca` continua lendo `presenca_parlamentar` e continua com o denominador do ENTE INTEIRO —
      o I-5 SEGUE ABERTO ate' a fatia 6.

  A semeadura e' pelo caminho do consumer (`projetar-evento!`), nunca INSERT direto no read-model, e dentro
  de `com-tenant*` — que faz `SET LOCAL ROLE oplenario_app` (NOBYPASSRLS) + `app.ente_id`, o mesmo regime do
  relay. Ou seja: os GRANTs e a policy da tabela nova estao no caminho critico de TODO deftest daqui."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.transparencia.components.repositorio :as repo]
            [oplenario.transparencia.suporte-presenca :as sp])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

;; ---------- semeadura ----------

(defn- projetar!
  "Chama o consumer com o payload CRU, sem trava de vocabulario — so' os casos deliberadamente malformados
  usam esta porta (os demais entram por `presenca!`)."
  [ente payload]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (repo/projetar-evento! tx {:tipo "presenca.registrada" :ente-id ente :payload payload}))))

(defn- presenca!
  "Projeta `presenca.registrada` pelo caminho do consumer. `ocorrido-em` e' string ISO (como chega do jsonb
  do outbox). O payload passa por `suporte-presenca/validar-vocabulario!` — a trava UNICA dos ns de teste de
  presenca — ANTES de tocar o banco."
  ([ente sessao vereador ocorrido-em] (presenca! ente sessao vereador "entrada" ocorrido-em))
  ([ente sessao vereador tipo ocorrido-em]
   (projetar! ente (sp/validar-vocabulario!
                    {:sessao-id (str sessao) :vereador-id (str vereador) :tipo tipo
                     :modalidade "plenario" :fonte "manual_secretaria"
                     :ocorrido-em ocorrido-em}))))

;; ---------- leitura (sempre no regime de tenant: role oplenario_app + RLS) ----------

(defn- companheira
  "Linhas de `sessao_com_chamada` VISIVEIS ao tenant `ente` (role oplenario_app, RLS aplicada)."
  [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (jdbc/execute! tx ["SELECT sessao_id, data FROM transparencia.sessao_com_chamada ORDER BY data, sessao_id"]
                     {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn- data-da-sessao [ente sessao]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (:data (jdbc/execute-one! tx ["SELECT data FROM transparencia.sessao_com_chamada WHERE sessao_id = ?" sessao]
                                {:builder-fn rs/as-unqualified-kebab-maps})))))

(defn- versao-da-linha
  "O `ctid` da linha da companheira — o ENDERECO FISICO da versao de tupla. Muda a cada UPDATE de verdade
  (o Postgres nao atualiza uma tupla no lugar; grava uma versao nova e deixa a velha morta), e NAO muda
  quando o `ON CONFLICT` no-opa. E' o unico observavel que distingue 'o valor continuou o mesmo' de
  'nada foi escrito' — que e' exatamente o achado da revisao da fatia 5."
  [ente sessao]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (:ctid (jdbc/execute-one! tx ["SELECT ctid::text AS ctid FROM transparencia.sessao_com_chamada WHERE sessao_id = ?" sessao]
                                {:builder-fn rs/as-unqualified-kebab-maps})))))

(defn- contagens
  "{:sessoes-na-presenca N :linhas-na-companheira M} do tenant — o par que o invariante compara."
  [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      {:sessoes-na-presenca
       (:c (jdbc/execute-one! tx ["SELECT count(DISTINCT sessao_id) c FROM transparencia.presenca_parlamentar"]
                              {:builder-fn rs/as-unqualified-kebab-maps}))
       :linhas-na-companheira
       (:c (jdbc/execute-one! tx ["SELECT count(*) c FROM transparencia.sessao_com_chamada"]
                              {:builder-fn rs/as-unqualified-kebab-maps}))})))

;; ---------- 1. a escrita nova existe ----------

(deftest projetar-presenca-cria-a-linha-da-sessao-na-companheira
  (testing "o consumer grava DOIS statements na mesma tx: a presenca e a linha da sessao"
    (let [ente (random-uuid) sessao (random-uuid)]
      (presenca! ente sessao (random-uuid) "2026-05-18T17:00:00Z")
      (let [linhas (companheira ente)]
        (is (= 1 (count linhas)) "uma linha por SESSAO (nao por vereador)")
        (is (= sessao (:sessao-id (first linhas))))
        (is (= (LocalDate/parse "2026-05-18") (:data (first linhas)))
            "a data civil do evento, nao o timestamptz")))))

;; ---------- 2. uma linha por sessao, nao por vereador ----------

(deftest segunda-presenca-na-mesma-sessao-nao-duplica-a-companheira
  (testing "21 vereadores na mesma sessao = UMA linha (e' a reducao de cardinalidade que a fatia 6 gasta)"
    (let [ente (random-uuid) sessao (random-uuid)]
      (doseq [_ (range 5)]
        (presenca! ente sessao (random-uuid) "2026-05-18T17:00:00Z"))
      (is (= 1 (count (companheira ente)))))))

;; ---------- 3. LEAST: a data e' a do PRIMEIRO evento, em qualquer ordem de projecao ----------

(deftest data-da-sessao-e-a-do-primeiro-evento-mesmo-projetado-fora-de-ordem
  ;; REVISAO DA FATIA 5 (buraco de teste, revisor de mutacao): as duas chamadas usavam `(random-uuid)` como
  ;; vereador, entao cada evento INSERIA uma linha nova em `presenca_parlamentar` e o gate de monotonicidade
  ;; de `registrar-presenca!` NUNCA era executado. O caso de producao que o nome do deftest promete —
  ;; redrive fora de ordem do MESMO vereador — ficava sem cobertura, e e' justamente onde as DUAS semanticas
  ;; opostas do mesmo branch do `case` se cruzam: presenca e' ULTIMO-VENCE (`WHERE ocorrido_em <`) e a
  ;; companheira e' PRIMEIRO-VENCE (`LEAST`). Mutante que sobrevivia com vereadores distintos e morre agora:
  ;; condicionar a chamada da companheira ao update-count de `registrar-presenca!` ("so' projeta se a
  ;; presenca de fato mudou") — plausivel como otimizacao, e faria a data da sessao ficar a do evento
  ;; MAIS NOVO no redrive.
  (testing "redrive fora de ordem do MESMO vereador nao empurra a data para frente (ON CONFLICT ... LEAST)"
    (let [ente (random-uuid) sessao (random-uuid) vereador (random-uuid)]
      ;; o evento MAIS NOVO chega primeiro (ordem invertida — o caso do redrive)
      (presenca! ente sessao vereador "2026-05-20T17:00:00Z")
      (presenca! ente sessao vereador "2026-05-18T17:00:00Z")
      (is (= (LocalDate/parse "2026-05-18") (data-da-sessao ente sessao))
          "o UPSERT de presenca NO-OPOU pelo gate de monotonicidade, e ainda assim a companheira recuou a data")))
  (testing "e um evento POSTERIOR do mesmo vereador nunca sobrescreve a data ja' gravada"
    (let [ente (random-uuid) sessao (random-uuid) vereador (random-uuid)]
      (presenca! ente sessao vereador "2026-05-18T17:00:00Z")
      (presenca! ente sessao vereador "2026-05-20T17:00:00Z")
      (is (= (LocalDate/parse "2026-05-18") (data-da-sessao ente sessao))))))

;; ---------- 3-bis. o conflito NAO reescreve a linha quando nao ha o que mudar ----------

(deftest upsert-da-companheira-nao-reescreve-a-linha-quando-a-data-nao-muda
  ;; REVISAO DA FATIA 5 (achado MEDIO, 3 revisores). `DO UPDATE SET data = LEAST(...)` SEM `WHERE` executa um
  ;; UPDATE REAL em todo evento que nao seja o primeiro da sessao, mesmo quando `LEAST` devolve o valor que
  ;; ja' estava la': o Postgres nunca pula um UPDATE por valor identico — grava versao nova de tupla e
  ;; registro de WAL. Como TODOS os eventos de presenca da mesma sessao colidem na MESMA linha, sao ~20 de 21
  ;; escritas inuteis por sessao, pagas na tx do relay SINGLE-FLIGHT e COMPARTILHADO, numa tabela cujo unico
  ;; proposito e' ser barata de varrer na fatia 6 (paginas sujas = sem index-only scan).
  (testing "eventos seguintes da mesma sessao sao NO-OP de conflito: a versao FISICA da linha nao muda"
    (let [ente (random-uuid) sessao (random-uuid)]
      (presenca! ente sessao (random-uuid) "2026-05-18T17:00:00Z")
      (let [v0 (versao-da-linha ente sessao)]
        (is (some? v0))
        (doseq [_ (range 5)]
          (presenca! ente sessao (random-uuid) "2026-05-18T19:00:00Z"))
        (is (= v0 (versao-da-linha ente sessao))
            "sem o gate no `ON CONFLICT` cada um destes 5 eventos deixaria uma versao morta de tupla"))))
  (testing "e o gate veta SO' o no-op — um evento mais antigo continua recuando a data"
    (let [ente (random-uuid) sessao (random-uuid)]
      (presenca! ente sessao (random-uuid) "2026-05-18T17:00:00Z")
      (let [v0 (versao-da-linha ente sessao)]
        (presenca! ente sessao (random-uuid) "2026-05-11T17:00:00Z")
        (is (= (LocalDate/parse "2026-05-11") (data-da-sessao ente sessao))
            "o gate certo e' `excluded.data < data`, nao o gate por `ocorrido_em` (esse vetaria o mais antigo)")
        (is (not= v0 (versao-da-linha ente sessao))
            "e quando ha' o que mudar, a linha E' reescrita — o gate nao pode ser um veto cego")))))

;; ---------- 4. o fuso e' aplicado UMA vez, em Clojure, na zona civil ----------

(deftest sessao-das-21h30-em-fortaleza-nao-cai-no-dia-seguinte
  (testing "sessao noturna: 00:30Z do dia 19 e' 21:30 do dia 18 em America/Fortaleza (UTC-3)"
    (let [ente (random-uuid) sessao (random-uuid)]
      (presenca! ente sessao (random-uuid) "2026-05-19T00:30:00Z")
      (is (= (LocalDate/parse "2026-05-18") (data-da-sessao ente sessao))
          "usar UTC aqui jogaria a sessao um dia adiante e moveria o denominador na borda do mandato"))))

;; ---------- 5. isolamento por tenant (a policy da tabela nova, no role oplenario_app) ----------

(deftest companheira-e-isolada-por-tenant-pela-rls
  (testing "o MESMO sessao_id em dois entes: cada tenant so' enxerga a propria linha"
    (let [a (random-uuid) b (random-uuid) sessao (random-uuid)]
      (presenca! a sessao (random-uuid) "2026-05-18T17:00:00Z")
      (presenca! b sessao (random-uuid) "2026-06-01T17:00:00Z")
      (let [la (companheira a) lb (companheira b)]
        (is (= 1 (count la)) "ente A ve so' a linha de A")
        (is (= 1 (count lb)) "ente B ve so' a linha de B")
        (is (= (LocalDate/parse "2026-05-18") (:data (first la))))
        (is (= (LocalDate/parse "2026-06-01") (:data (first lb))))))))

;; ---------- 6. tolerancia: o relay e' COMPARTILHADO ----------

(deftest consumer-continua-tolerante-e-nao-derruba-o-relay-compartilhado
  (testing "payload de presenca sem :ocorrido-em nao lanca — o relay e' unico e um throw trava o bus inteiro"
    (let [ente (random-uuid) sessao (random-uuid)]
      (is (nil? (projetar! ente {:sessao-id (str sessao) :vereador-id (str (random-uuid))
                                 :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria"}))
          "sem instante nao ha data civil nem estado honesto a gravar: loga e tolera")
      (is (= {:sessoes-na-presenca 0 :linhas-na-companheira 0} (contagens ente))
          "e NENHUM dos dois statements foi executado (nao ha meia-projecao)")))
  (testing "o mesmo vale para um :ocorrido-em malformado"
    (let [ente (random-uuid)]
      (is (nil? (projetar! ente {:sessao-id (str (random-uuid)) :vereador-id (str (random-uuid))
                                 :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria"
                                 :ocorrido-em "ontem a' noite"})))
      (is (= {:sessoes-na-presenca 0 :linhas-na-companheira 0} (contagens ente))))))

;; ---------- 7. o invariante que a fatia 6 vai depender ----------

(deftest toda-sessao-em-presenca-parlamentar-tem-linha-na-companheira
  (testing "COUNT(DISTINCT sessao_id) da presenca == COUNT(*) da companheira, no mesmo tenant"
    (let [ente     (random-uuid)
          sessoes  (repeatedly 3 random-uuid)
          vereadores (repeatedly 4 random-uuid)]
      (doseq [[i s] (map-indexed vector sessoes)
              v vereadores]
        (presenca! ente s v (format "2026-05-%02dT17:00:00Z" (+ 10 i))))
      (let [{:keys [sessoes-na-presenca linhas-na-companheira]} (contagens ente)]
        (is (= 3 sessoes-na-presenca))
        (is (= 3 linhas-na-companheira))
        (is (= sessoes-na-presenca linhas-na-companheira)
            "sem esta igualdade o denominador da fatia 6 perderia sessoes em silencio"))))
  (testing "LIMITE HONESTO deste invariante (revisao da fatia 5): ele vale porque AQUI todo evento passou
            pelo consumer NOVO. Em producao ha' uma janela em que nao vale — `docker compose up -d --build`
            roda o servico `migrate` ate' a conclusao ANTES de recriar o `app`, e o container ANTIGO segue
            drenando o outbox com o consumer de UM statement. Toda `presenca.registrada` drenada nessa janela
            grava presenca e NENHUMA linha na companheira; o evento fica em `shared.evento_consumido`, entao
            nao ha redrive, e nao existe ferramenta de re-projecao no repo. Quem fecha esse buraco e' o
            RECONCILIADOR da mig 0068 (os dois deftest abaixo), que roda DEPOIS do codigo novo estar vivo."
    (is true "declaracao de limite — o detector do reconciliador esta' nos dois deftest seguintes")))

;; ---------- 8. catalogo: o que a migration promete e nenhum teste comportamental enxerga ----------

(deftest companheira-tem-force-rls-e-o-indice-de-data-no-catalogo
  ;; REVISAO DA FATIA 5 (achado MEDIO do revisor de mutacao): duas mutacoes da 0067 sobreviviam ao ns inteiro.
  ;; (1) apagar o `CREATE INDEX idx_sessao_com_chamada_data` — nenhum deftest consulta por faixa de data, e o
  ;;     indice E' a justificativa economica inteira da tabela (o denominador da fatia 6 sai de um Seq Scan de
  ;;     42.000 linhas para um range scan). O defeito so' apareceria no EXPLAIN da fatia 6, com a migration
  ;;     ja' aplicada e IMUTAVEL.
  ;; (2) apagar o `FORCE ROW LEVEL SECURITY` — `com-tenant*` entra como `oplenario_app`, que NAO e' o dono da
  ;;     tabela (dono = `oplenario`, verificado em `pg_class`), entao `ENABLE` sozinho ja' isola e
  ;;     `companheira-e-isolada-por-tenant-pela-rls` fica verde sem o FORCE. A tabela viraria a unica de
  ;;     `transparencia` sem FORCE, divergindo da 0044/0064 sem nada acusar.
  ;; Molde: `tenancy_retrofit_test/motor-regra-tenant-tem-rls-forcada` + `migracao_test`.
  (let [r (jdbc/execute-one! *ds*
            ["SELECT c.relrowsecurity, c.relforcerowsecurity
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'transparencia' AND c.relname = 'sessao_com_chamada'"]
            {:builder-fn rs/as-unqualified-kebab-maps})
        indices (map :indexdef
                     (jdbc/execute! *ds*
                       ["SELECT indexdef FROM pg_indexes
                          WHERE schemaname = 'transparencia' AND tablename = 'sessao_com_chamada'"]
                       {:builder-fn rs/as-unqualified-kebab-maps}))]
    (is (true? (:relrowsecurity r)) "RLS habilitada")
    (is (true? (:relforcerowsecurity r))
        "FORCE RLS: nem o DONO bypassa — o teste de isolamento sozinho nao prova isto (ele entra como oplenario_app)")
    (is (some #(re-find #"\(ente_id, data" %) indices)
        "o indice (ente_id, data) e' o PREDICADO da janela de exercicio do mandato na fatia 6 — sem ele a
         tabela nao compra nada; se a fatia 6 o trocar por um INCLUDE, este assert tem de ser trocado A MAO")))

;; ---------- 9. o reconciliador da 0068: o buraco da janela de deploy ----------

(defn- reconciliador-sql
  "O `INSERT..SELECT..GROUP BY` da mig 0068, extraido do PROPRIO `.up.sql` (statements separados por `--;;`).
  Ler do arquivo, e nao re-escrever a query no teste, e' o que impede o teste de virar tautologia: se o
  statement da migration mudar de forma, e' ESTE texto que roda aqui."
  []
  (let [recurso "migrations/20260722000068-transparencia-sessao-com-chamada-comentarios-e-reconciliacao.up.sql"
        arquivo (io/resource recurso)]
    (is (some? arquivo) (str "migration nao encontrada no classpath: " recurso))
    (->> (str/split (slurp arquivo) #"--;;")
         (filter #(str/includes? % "INSERT INTO transparencia.sessao_com_chamada"))
         first)))

(defn- rodar-reconciliador!
  "Roda o statement da 0068 no regime de TENANT (role oplenario_app + app.ente_id), que e' o unico regime em
  que ele e' testavel de dentro da suite — na migration ele roda como dono, com o par NO FORCE/FORCE em volta."
  [ente]
  (tenancy/com-tenant* *ds* ente (fn [tx] (jdbc/execute-one! tx [(reconciliador-sql)]))))

(defn- apagar-companheira!
  "Simula a JANELA DE DEPLOY: os eventos foram drenados pelo app ANTIGO, que so' escrevia `presenca_parlamentar`.
  Roda fora de `com-tenant*` de proposito — `oplenario_app` nao tem DELETE nesta tabela (e nao deve ter)."
  [ente]
  (jdbc/execute-one! *ds* ["DELETE FROM transparencia.sessao_com_chamada WHERE ente_id = ?" ente]))

(deftest reconciliador-da-0068-recria-a-linha-que-a-janela-de-deploy-perdeu
  ;; REVISAO DA FATIA 5 (achado MEDIO, 2 revisores): o backfill da 0067 e' um SNAPSHOT unico, e o comando
  ;; canonico de subida (`docker compose up -d --build`) roda `migrate` ate' a conclusao com o `app` ANTIGO
  ;; ainda de pe' e drenando o outbox. Sem reconciliador, a sessao drenada nessa janela some do denominador da
  ;; fatia 6 PARA SEMPRE, sem log e sem auto-cura — o faltoso da sessao perdida publica 100%, que e' a mesma
  ;; "lavagem do faltoso" pela qual a Forma C1 foi descartada.
  (testing "sessao com presenca mas SEM linha na companheira volta a ter linha, com a data certa"
    (let [ente (random-uuid) s1 (random-uuid) s2 (random-uuid)]
      (presenca! ente s1 (random-uuid) "2026-05-18T17:00:00Z")
      (presenca! ente s2 (random-uuid) "2026-06-02T17:00:00Z")
      (apagar-companheira! ente)
      (is (= 0 (:linhas-na-companheira (contagens ente))) "buraco simulado")
      (rodar-reconciliador! ente)
      (is (= {:sessoes-na-presenca 2 :linhas-na-companheira 2} (contagens ente))
          "o reconciliador re-deriva UMA linha por sessao a partir da propria presenca (intra-schema)")
      (is (= (LocalDate/parse "2026-05-18") (data-da-sessao ente s1)))
      (is (= (LocalDate/parse "2026-06-02") (data-da-sessao ente s2)))))
  (testing "e e' IDEMPOTENTE: rodar de novo com as linhas ja' la' nao muda nada (ON CONFLICT DO NOTHING)"
    (let [ente (random-uuid) sessao (random-uuid)]
      (presenca! ente sessao (random-uuid) "2026-05-18T17:00:00Z")
      (let [v0 (versao-da-linha ente sessao)]
        (rodar-reconciliador! ente)
        (rodar-reconciliador! ente)
        (is (= 1 (:linhas-na-companheira (contagens ente))))
        (is (= (LocalDate/parse "2026-05-18") (data-da-sessao ente sessao)))
        (is (= v0 (versao-da-linha ente sessao))
            "DO NOTHING: nem sequer reescreve a linha existente")))))

(deftest linha-reconstruida-usa-o-primeiro-dos-ULTIMOS-eventos-e-pode-ser-POSTERIOR-a-do-caminho-vivo
  ;; REVISAO DA FATIA 5 (achado MEDIO): a 0067 afirma, no `.up.sql` e na docstring, que `data` e' "a data civil
  ;; do PRIMEIRO evento de presenca da sessao" — e isso e' verdade SO' para a linha projetada ao vivo.
  ;; `presenca_parlamentar` NAO e' um log: e' o ESTADO ATUAL por (sessao, vereador), e `registrar-presenca!`
  ;; guarda o MAXIMO `ocorrido_em` por vereador. Logo `min(...) GROUP BY ente_id, sessao_id` e' `min(max por
  ;; vereador)` — maior ou igual ao instante do primeiro evento, nunca menor. A "verificacao" citada no commit
  ;; da fatia ("zero linha divergente de min(ocorrido_em ...)") re-executa a MESMA expressao do backfill e e'
  ;; incapaz de detectar esta classe por construcao. Nao ha conserto: o log so' existe em
  ;; `sessoes.presenca_evento` e le-lo daqui seria JOIN cross-schema (proibido, §22.10). Entao a semantica
  ;; divergente fica PINADA aqui e escrita no COMMENT do catalogo — nao escondida.
  (testing "sessao suspensa e reaberta: o caminho VIVO da a data do primeiro evento..."
    (let [ente (random-uuid) sessao (random-uuid) v1 (random-uuid) v2 (random-uuid)]
      (presenca! ente sessao v1 "2026-05-18T23:00:00Z")
      (presenca! ente sessao v2 "2026-05-18T23:10:00Z")
      (presenca! ente sessao v1 "retorno" "2026-05-25T18:00:00Z")
      (presenca! ente sessao v2 "retorno" "2026-05-25T18:05:00Z")
      (is (= (LocalDate/parse "2026-05-18") (data-da-sessao ente sessao))
          "ao vivo o LEAST viu todos os eventos, um a um")
      (testing "...e a linha RECONSTRUIDA da uma data POSTERIOR, porque os eventos de 18/05 foram sobrescritos"
        (apagar-companheira! ente)
        (rodar-reconciliador! ente)
        (is (= (LocalDate/parse "2026-05-25") (data-da-sessao ente sessao))
            "SETE dias de divergencia — na fatia 6 isso move a sessao para dentro/fora da janela de mandato")))))
