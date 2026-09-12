(ns oplenario.transparencia.materia-db-test
  "INTEGRACAO (PG real) — a familia dos truncamentos silenciosos, sitio (b): GET /portal/casa/:ente/materias
  (`db/materia.clj:listar-em-tramitacao`) corta em `teto-listagem` (200) SEM sinalizar. Prova o par
  `listar-em-tramitacao` + `contar-em-tramitacao` — MESMO racional de `paineis/db/pendencia`
  (o-que-vence-total-usa-o-mesmo-predicado-da-lista, o molde desta frente): o total tem de nascer do MESMO
  predicado da lista, senao um dia diverge em silencio."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.transparencia.db.materia :as db-materia]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)]
        (try (t) (finally (component/stop c)))))))

(defn- inserir-materia!
  [tx ente pid estado]
  (db-materia/inserir! tx {:ente-id ente :proposicao-id pid :tipo "projeto_lei" :ano 2026 :sequencial
                            (mod (.getMostSignificantBits ^java.util.UUID pid) 100000)
                            :urn-lex (str "urn:lex:x;" pid) :ementa "Dispoe sobre X" :estado estado}))

(deftest em-tramitacao-total-bate-com-a-lista-quando-nao-ha-corte
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (inserir-materia! tx ente (random-uuid) "protocolada")
        (inserir-materia! tx ente (random-uuid) "em_comissoes")
        (is (= 2 (count (db-materia/listar-em-tramitacao tx ente #{}))))
        (is (= 2 (db-materia/contar-em-tramitacao tx ente #{}))
            "o total bate com a lista quando nao ha corte")))))

(deftest em-tramitacao-total-usa-o-mesmo-predicado-da-lista
  ;; a asserção que mata a DERIVA: insere um estado de CADA valor usado neste teste e exclui um deles —
  ;; se o total um dia passar a usar um WHERE copiado (em vez do MESMO predicado de `listar-em-tramitacao`),
  ;; esta asserção reprova no dia em que os dois divergirem, nao anos depois.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (inserir-materia! tx ente (random-uuid) "protocolada")
        (inserir-materia! tx ente (random-uuid) "em_comissoes")
        (inserir-materia! tx ente (random-uuid) "arquivada")
        (let [excl #{"arquivada"}]
          (is (= 2 (count (db-materia/listar-em-tramitacao tx ente excl)))
              "a lista: exclui 'arquivada'")
          (is (= 2 (db-materia/contar-em-tramitacao tx ente excl))
              "o total: o MESMO conjunto que a lista enxerga — a prova de que e' o mesmo predicado"))))))

(deftest em-tramitacao-com-limite-injetado-trunca-lista-mas-total-continua-real
  ;; CORRECAO (achado da revisao adversarial, IMPORTANTE): a versao anterior deste teste criava so' 3
  ;; linhas e afirmava 3=3 nos dois lados — provava identidade de PREDICADO, nao AUSENCIA DE TETO (o
  ;; nome mentia cobertura que nao existia; mutacao medida: `contar-em-tramitacao` -> `(count
  ;; (listar-em-tramitacao ...))` deixava esta suite INTEIRA verde). `listar-em-tramitacao` agora aceita
  ;; `limite` INJETAVEL (4a aridade, mesmo racional de paineis/db/pendencia/listar-abertas e
  ;; o-que-vence-com-limite-injetado-trunca-lista-mas-total-continua-real): cria 5 linhas, lista com
  ;; limite=2 e afirma lista=2 E total=5 — a lista TRUNCA de verdade e o total continua MAIOR que ela.
  ;; O caminho de PRODUCAO continua chamando a aridade de 3 args (repositorio.clj) e cai no default
  ;; teto-listagem=200.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (dotimes [_ 5] (inserir-materia! tx ente (random-uuid) "protocolada"))
        (is (= 2 (count (db-materia/listar-em-tramitacao tx ente #{} 2))) "a lista respeita o limite INJETADO")
        (is (= 5 (db-materia/contar-em-tramitacao tx ente #{}))
            "o total ignora o limite injetado da lista — continua o numero real, MAIOR que a lista truncada")))))

(deftest em-tramitacao-total-rls-isola-cross-tenant
  (let [ente-a (random-uuid) ente-b (random-uuid)]
    (tenancy/com-tenant* *ds* ente-a (fn [tx] (inserir-materia! tx ente-a (random-uuid) "protocolada")))
    (tenancy/com-tenant* *ds* ente-b
      (fn [tx]
        (is (zero? (db-materia/contar-em-tramitacao tx ente-b #{}))
            "RLS: outro ente nao conta a materia do ente-a")))))
