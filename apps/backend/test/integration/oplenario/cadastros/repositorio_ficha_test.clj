(ns oplenario.cadastros.repositorio-ficha-test
  "INTEGRACAO (PG real) — RepoCadastros/listar-vereadores + ficha-vereador (Task 2) + o metodo composto
  ficha-e-mandatos-do-vereador (I-5 fatia 3): leitura composta NUMA UNICA tx (mesma disciplina de
  ficha-completa-da-proposicao do legislativo)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo]
            [oplenario.cadastros.db.vereador :as db-vereador]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.migracao :as migracao])
  (:import (java.time LocalDate)))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoCadastrosPg c)]
        (try (t) (finally (component/stop c)))))))

(deftest ficha-vereador-nil-quando-nao-existe
  (is (nil? (repo/ficha-vereador *repo* (random-uuid) (random-uuid) (LocalDate/of 2026 7 14)))))

(deftest ficha-vereador-compoe-vereador-mandato-legislatura-e-comissoes
  (let [ente (random-uuid)
        leg-id (random-uuid)
        ver-id (random-uuid)
        mandato-id (random-uuid)
        mesa-id (random-uuid)
        hoje (LocalDate/of 2026 7 14)
        ini  (LocalDate/of 2025 1 1)]
    (repo/criar-legislatura! *repo* ente
      {:id leg-id :ente-id ente :numero 20 :ano-inicio 2025 :ano-fim 2028 :vigente true})
    (repo/criar-vereador! *repo* ente {:id ver-id :ente-id ente :nome "Elisa" :nome-parlamentar "Elisa Vereadora"})
    (repo/criar-mandato! *repo* ente
      {:id mandato-id :ente-id ente :vereador-id ver-id :legislatura-id leg-id :partido "PDT"
       :estado "vigente" :natureza "titular" :vigencia-inicio ini :vigencia-fim nil})
    (repo/criar-comissao! *repo* ente {:id mesa-id :ente-id ente :nome "Mesa Diretora" :tipo "mesa"
                                       :legislatura-id leg-id :vigencia-inicio ini})
    (repo/criar-membro! *repo* ente {:id (random-uuid) :ente-id ente :comissao-id mesa-id
                                     :vereador-id ver-id :vigencia-inicio ini})
    (repo/criar-cargo! *repo* ente {:id (random-uuid) :ente-id ente :comissao-id mesa-id
                                    :vereador-id ver-id :cargo "presidente" :vigencia-inicio ini})
    (let [f (repo/ficha-vereador *repo* ente ver-id hoje)]
      (is (= "Elisa Vereadora" (:nome-parlamentar (:vereador f))))
      (is (= "PDT" (:partido (:mandato f))))
      (is (= 20 (:numero (:legislatura f))))
      (is (= 1 (count (:comissoes f))))
      (is (= "presidente" (:cargo (first (:comissoes f))))))
    ;; ente-scope: outro ente NAO ve a ficha (RLS) -> nil, sem vazar via cross-tenant.
    (is (nil? (repo/ficha-vereador *repo* (random-uuid) ver-id hoje)))))

(deftest listar-vereadores-delega-ao-db
  (let [ente (random-uuid)
        ver-id (random-uuid)
        hoje (LocalDate/of 2026 7 14)]
    (repo/criar-vereador! *repo* ente {:id ver-id :ente-id ente :nome "Fabio"})
    (let [rows (repo/listar-vereadores *repo* ente hoje)]
      (is (= ["Fabio"] (map :nome rows))))))

;; ============================================================================
;; I-5 fatia 3 — ficha-e-mandatos-do-vereador + licencas-de-mandatos
;; ============================================================================

(defn- semear-legislatura! [ente leg-id]
  (repo/criar-legislatura! *repo* ente
    {:id leg-id :ente-id ente :numero 20 :ano-inicio 2021 :ano-fim 2028 :vigente true}))

(defn- semear-mandato!
  "INSERT direto (nao `registrar-mandato!`) — a fatia LE mandatos, nao os cria, e o guard app-level de
   sobreposicao nao e' o que esta sob teste. Stint fechado usa estado 'concluido' (dominio do CHECK da mig
   0010): o EXCLUDE anti-overlap (mig 0059) so' incide sobre estado='vigente'."
  [ente leg-id ver-id m]
  (repo/criar-mandato! *repo* ente
    (merge {:id (random-uuid) :ente-id ente :vereador-id ver-id :legislatura-id leg-id
            :partido "PDT" :estado "vigente" :natureza "titular" :vigencia-fim nil :fim-efetivo nil}
           m)))

(deftest ficha-e-mandatos-devolve-todos-os-stints-do-vereador-nao-so-o-vigente
  (let [ente (random-uuid) leg-id (random-uuid) ver-id (random-uuid)
        antigo (random-uuid) atual (random-uuid)
        hoje (LocalDate/of 2026 7 14)]
    (semear-legislatura! ente leg-id)
    (repo/criar-vereador! *repo* ente {:id ver-id :ente-id ente :nome "Gilda" :nome-parlamentar "Gilda"})
    (semear-mandato! ente leg-id ver-id {:id antigo :estado "concluido"
                                         :vigencia-inicio (LocalDate/of 2021 1 1)
                                         :vigencia-fim (LocalDate/of 2024 12 31)})
    (semear-mandato! ente leg-id ver-id {:id atual :vigencia-inicio (LocalDate/of 2025 1 1)})
    (let [r (repo/ficha-e-mandatos-do-vereador *repo* ente ver-id hoje)]
      (is (= #{antigo atual} (set (map :id (:mandatos r))))
          "os DOIS stints, nao so' o vigente (a janela historica do ex-vereador depende disso)")
      (is (= atual (:id (:mandato r))) ":mandato segue sendo so' o que cobre `data`")
      (is (= [] (:licencas r)) "sem licenca registrada -> vetor vazio, nunca nil"))))

(deftest ficha-e-mandatos-de-ex-vereador-tem-mandatos-mas-mandato-vigente-nil
  (let [ente (random-uuid) leg-id (random-uuid) ver-id (random-uuid)
        stint (random-uuid)
        hoje (LocalDate/of 2026 7 14)]
    (semear-legislatura! ente leg-id)
    (repo/criar-vereador! *repo* ente {:id ver-id :ente-id ente :nome "Hugo"})
    (semear-mandato! ente leg-id ver-id {:id stint :estado "concluido"
                                         :vigencia-inicio (LocalDate/of 2021 1 1)
                                         :vigencia-fim (LocalDate/of 2024 12 31)})
    ;; A licenca vive num stint que `mandato-vigente` NAO devolve (ele e' nil aqui). E' o unico detector
    ;; do ELO `:mandatos` -> `:licencas`: uma versao que passasse so' o mandato corrente a
    ;; `licencas-de-mandatos` devolveria [] e a fatia 6 cobraria do ex-vereador as sessoes das quais ele
    ;; estava legalmente afastado — a propria injustica I-5, republicada em silencio.
    (repo/criar-licenca! *repo* ente {:id (random-uuid) :ente-id ente :mandato-id stint
                                      :inicio (LocalDate/of 2022 6 1) :fim (LocalDate/of 2022 12 31)
                                      :motivo "saude"})
    (let [r (repo/ficha-e-mandatos-do-vereador *repo* ente ver-id hoje)]
      (is (some? (:vereador r)) "ex-vereador ainda existe como pessoa")
      (is (nil? (:mandato r)) "nenhum mandato cobre hoje")
      (is (= [stint] (map :id (:mandatos r)))
          "o stint historico continua vindo — e' a UNICA fonte da janela do ex-vereador")
      (is (= [stint] (map :mandato-id (:licencas r)))
          "a licenca do stint HISTORICO e' a unica fonte da subtracao da janela do ex-vereador")
      (is (= [(LocalDate/of 2022 6 1)] (map :inicio (:licencas r)))
          "e ela volta com o intervalo, nao so' com o id"))))

(deftest ficha-e-mandatos-devolve-licencas-do-vereador-e-nenhuma-de-outro
  (let [ente (random-uuid) leg-id (random-uuid)
        ver-a (random-uuid) ver-b (random-uuid)
        man-a (random-uuid) man-b (random-uuid)
        lic-a (random-uuid) lic-b (random-uuid)
        hoje (LocalDate/of 2026 7 14)]
    (semear-legislatura! ente leg-id)
    (repo/criar-vereador! *repo* ente {:id ver-a :ente-id ente :nome "Ines"})
    (repo/criar-vereador! *repo* ente {:id ver-b :ente-id ente :nome "Joao"})
    (semear-mandato! ente leg-id ver-a {:id man-a :vigencia-inicio (LocalDate/of 2025 1 1)})
    (semear-mandato! ente leg-id ver-b {:id man-b :vigencia-inicio (LocalDate/of 2025 1 1)})
    ;; INSERIDA PRIMEIRO de proposito, com `inicio` POSTERIOR: sem o `:order-by [[:inicio] [:id]]` o
    ;; retorno tende a sair na ordem fisica do heap (= ordem de insercao) e a assercao de ordem abaixo
    ;; falha. E' o unico detector da clausula.
    (repo/criar-licenca! *repo* ente {:id (random-uuid) :ente-id ente :mandato-id man-a
                                      :inicio (LocalDate/of 2026 1 10) :fim nil :motivo "particular"})
    (repo/criar-licenca! *repo* ente {:id lic-a :ente-id ente :mandato-id man-a
                                      :inicio (LocalDate/of 2025 3 1) :fim (LocalDate/of 2025 5 31)
                                      :motivo "saude"})
    (repo/criar-licenca! *repo* ente {:id lic-b :ente-id ente :mandato-id man-b
                                      :inicio (LocalDate/of 2025 4 1) :fim nil :motivo "particular"})
    (let [r (repo/ficha-e-mandatos-do-vereador *repo* ente ver-a hoje)
          ls (:licencas r)]
      (is (= 2 (count ls)) "so' as licencas dos mandatos DESTE vereador (a de `ver-b` fica de fora)")
      (is (= [man-a man-a] (map :mandato-id ls)))
      (is (= [(LocalDate/of 2025 3 1) (LocalDate/of 2026 1 10)] (map :inicio ls))
          "ordem deterministica por (inicio, id) — inseridas fora de ordem de proposito; e coluna `date` chega como LocalDate")
      (is (= (LocalDate/of 2025 5 31) (:fim (first ls))))
      (is (nil? (:fim (second ls)))
          "licenca EM CURSO chega com `fim` nil, nao normalizado p/ data nenhuma: e' o buraco ABERTO a' direita que a fatia 4 subtrai")
      (is (= #{:mandato-id :inicio :fim} (set (keys (first ls))))
          "a linha NUNCA carrega :motivo (dado potencialmente de saude) nem :mandato-suplente-id p/ o caminho da rota publica anonima"))))

(deftest licencas-de-mandatos-tem-ente-id-explicito-no-where
  ;; A RLS (FORCE + policy comparando com o GUC `app.ente_id`) ja' bloqueia o cross-tenant em QUALQUER
  ;; formato de tx, e por isso NENHUM teste comportamental consegue discriminar a presenca do predicado
  ;; explicito — provado por mutacao (`[:= :ente_id ente-id]` -> `[:= 1 1]` deixa o ns inteiro verde). O
  ;; guard de FONTE abaixo e' o unico detector da defesa em profundidade que a docstring declara — molde do
  ;; `estrutura-lint-test` / `zona-civil-padrao-e-a-mesma-usada-pelo-seam-de-ficha`. Importa porque este
  ;; repo ja' chama `db/vereador` de dentro de uma tx do relay que seta so' o GUC e NAO troca de role
  ;; (`identidade-do-vereador-em-tx`), contexto em que o predicado deixa de ser cinto-e-suspensorio.
  (let [fonte (slurp "src/oplenario/cadastros/db/vereador.clj")
        corpo (second (re-find #"(?s)\(defn licencas-de-mandatos(.*?)\n\(defn " fonte))]
    (is (some? corpo) "a fn `licencas-de-mandatos` continua existindo em db/vereador.clj (assert com dentes)")
    (is (re-find #"\[:= :ente_id ente-id\]" corpo)
        "o WHERE casa `ente_id` explicito alem da RLS — remover isso nao pode passar verde")))

(deftest licencas-de-mandatos-com-lista-vazia-nao-toca-o-banco
  ;; `tx` nil: se a fn emitisse SQL, next.jdbc estouraria. Alem de evitar `IN ()` invalido, prova que o
  ;; caminho "vereador sem mandato" nao gasta round-trip.
  (is (= [] (db-vereador/licencas-de-mandatos nil (random-uuid) [])))
  (is (= [] (db-vereador/licencas-de-mandatos nil (random-uuid) nil))))

(deftest licencas-de-mandatos-nao-vaza-licenca-de-outro-tenant
  (let [ente-a (random-uuid) ente-b (random-uuid)
        leg-a (random-uuid) leg-b (random-uuid)
        ver-a (random-uuid) ver-b (random-uuid)
        man-a (random-uuid) man-b (random-uuid)]
    (semear-legislatura! ente-a leg-a)
    (semear-legislatura! ente-b leg-b)
    (repo/criar-vereador! *repo* ente-a {:id ver-a :ente-id ente-a :nome "Kelly"})
    (repo/criar-vereador! *repo* ente-b {:id ver-b :ente-id ente-b :nome "Lucas"})
    (semear-mandato! ente-a leg-a ver-a {:id man-a :vigencia-inicio (LocalDate/of 2025 1 1)})
    (semear-mandato! ente-b leg-b ver-b {:id man-b :vigencia-inicio (LocalDate/of 2025 1 1)})
    (repo/criar-licenca! *repo* ente-a {:id (random-uuid) :ente-id ente-a :mandato-id man-a
                                        :inicio (LocalDate/of 2025 2 1) :fim nil})
    (repo/criar-licenca! *repo* ente-b {:id (random-uuid) :ente-id ente-b :mandato-id man-b
                                        :inicio (LocalDate/of 2025 2 1) :fim nil})
    ;; na tx do tenant A, pedindo os mandatos DOS DOIS entes: so' o de A volta (RLS + ente_id explicito).
    (let [ls (repo/transacao *repo* ente-a
               (fn [tx] (db-vereador/licencas-de-mandatos tx ente-a [man-a man-b])))]
      (is (= [man-a] (map :mandato-id ls))))))

(deftest ficha-e-mandatos-de-vereador-inexistente-e-nil
  (is (nil? (repo/ficha-e-mandatos-do-vereador *repo* (random-uuid) (random-uuid)
                                               (LocalDate/of 2026 7 14))))
  ;; e o guard e' a EXISTENCIA da ficha, nao a janela: vereador real visto de outro tenant tambem e' nil.
  (let [ente (random-uuid) ver-id (random-uuid)]
    (repo/criar-vereador! *repo* ente {:id ver-id :ente-id ente :nome "Marta"})
    (is (nil? (repo/ficha-e-mandatos-do-vereador *repo* (random-uuid) ver-id
                                                 (LocalDate/of 2026 7 14))))))

(deftest ficha-e-mandatos-nao-altera-o-contrato-de-ficha-vereador
  (let [ente (random-uuid) leg-id (random-uuid) ver-id (random-uuid) mesa-id (random-uuid)
        man-id (random-uuid)
        hoje (LocalDate/of 2026 7 14)
        ini (LocalDate/of 2025 1 1)]
    (semear-legislatura! ente leg-id)
    (repo/criar-vereador! *repo* ente {:id ver-id :ente-id ente :nome "Nara" :nome-parlamentar "Nara"})
    (semear-mandato! ente leg-id ver-id {:id man-id :vigencia-inicio ini})
    (repo/criar-comissao! *repo* ente {:id mesa-id :ente-id ente :nome "Mesa Diretora" :tipo "mesa"
                                       :legislatura-id leg-id :vigencia-inicio ini})
    (repo/criar-membro! *repo* ente {:id (random-uuid) :ente-id ente :comissao-id mesa-id
                                     :vereador-id ver-id :vigencia-inicio ini})
    (repo/criar-cargo! *repo* ente {:id (random-uuid) :ente-id ente :comissao-id mesa-id
                                    :vereador-id ver-id :cargo "presidente" :vigencia-inicio ini})
    (let [ficha (repo/ficha-vereador *repo* ente ver-id hoje)
          composta (repo/ficha-e-mandatos-do-vereador *repo* ente ver-id hoje)]
      (is (= #{:vereador :mandato :legislatura :comissoes} (set (keys ficha)))
          "`ficha-vereador` continua com as MESMAS 4 chaves — a fatia e' aditiva")
      (is (= ficha (select-keys composta [:vereador :mandato :legislatura :comissoes]))
          "o metodo composto e' superconjunto exato: as 4 chaves antigas com os MESMOS valores")
      (is (= #{:vereador :mandato :legislatura :comissoes :mandatos :licencas}
             (set (keys composta)))))))
