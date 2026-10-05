(ns oplenario.reconciliar-anexos-test
  "UNITARIO — a logica PURA da reconciliacao dos anexos (banco x object storage): a comparacao de conjuntos, a regra das
  24 horas, o anexo retirado, o teto das listas no relatorio (com o total verdadeiro) e a leitura dos argumentos."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oplenario.admin-sistema.components.repositorio :as repo-admin]
            [oplenario.kernel.components.objeto-store :as store]
            [oplenario.reconciliar-anexos :as r])
  (:import (java.time Instant)))

(def ^:private agora (Instant/parse "2026-10-04T12:00:00Z"))
(defn- ha-horas [h] (.minusSeconds ^Instant agora (long (* h 3600))))

(defn- cmp [linhas blobs] (r/comparar {:linhas linhas :blobs blobs :agora agora}))

;; ---------------------------------------------------------------- comparar

(deftest tudo-casado-nao-diverge
  (let [res (cmp [{:chave "a/1" :retirado? false} {:chave "a/2" :retirado? false}]
                 [{:chave "a/1" :modificado-em (ha-horas 100)} {:chave "a/2" :modificado-em (ha-horas 1)}])]
    (is (= {:linhas 2 :retiradas 0 :blobs 2 :blob-sem-linha [] :linha-sem-blob []} res))
    (is (not (r/diverge? res)))))

(deftest blob-sem-linha-so-e-apagavel-depois-de-24-horas
  (let [res (cmp []
                 [{:chave "a/velho" :modificado-em (ha-horas 25)}
                  {:chave "a/novo" :modificado-em (ha-horas 2)}
                  {:chave "a/na-borda" :modificado-em (ha-horas 24)}
                  {:chave "a/sem-data" :modificado-em nil}])
        por-chave (into {} (map (juxt :chave identity)) (:blob-sem-linha res))]
    (is (= ["a/na-borda" "a/novo" "a/sem-data" "a/velho"] (map :chave (:blob-sem-linha res))) "em ordem de chave")
    (is (true?  (:apagavel? (por-chave "a/velho"))) "25 h: apaga")
    (is (false? (:apagavel? (por-chave "a/novo"))) "2 h: pode ser upload em curso")
    (is (false? (:apagavel? (por-chave "a/na-borda"))) "exatamente 24 h NAO e' 'mais de 24 horas'")
    (is (false? (:apagavel? (por-chave "a/sem-data"))) "sem data de modificacao: nunca apaga")
    (is (every? #(= :sem-linha (:motivo %)) (:blob-sem-linha res)))
    (is (r/diverge? res))))

(deftest linha-sem-blob-lista-so-as-vigentes
  (let [res (cmp [{:chave "a/ok" :retirado? false}
                  {:chave "a/perdido" :retirado? false}
                  {:chave "a/retirado" :retirado? true}]
                 [{:chave "a/ok" :modificado-em (ha-horas 50)}])]
    (is (= ["a/perdido"] (:linha-sem-blob res)))
    (is (= 3 (:linhas res)))
    (is (= 1 (:retiradas res)))
    (is (= [] (:blob-sem-linha res)))))

(deftest anexo-retirado-sem-blob-e-o-estado-correto
  (let [res (cmp [{:chave "a/retirado" :retirado? true}] [])]
    (is (= {:linhas 1 :retiradas 1 :blobs 0 :blob-sem-linha [] :linha-sem-blob []} res))
    (is (not (r/diverge? res)))))

(deftest anexo-retirado-que-ainda-tem-blob-e-orfao-com-o-motivo
  (let [res (cmp [{:chave "a/retirado" :retirado? true}]
                 [{:chave "a/retirado" :modificado-em (ha-horas 30)}])]
    (is (= [{:chave "a/retirado" :modificado-em (ha-horas 30) :motivo :retirado :apagavel? true}]
           (:blob-sem-linha res)))
    (is (= [] (:linha-sem-blob res)))))

(deftest a-idade-minima-e-configuravel
  (let [res (r/comparar {:linhas [] :blobs [{:chave "a/x" :modificado-em (ha-horas 2)}] :agora agora
                         :idade-minima (java.time.Duration/ofHours 1)})]
    (is (true? (:apagavel? (first (:blob-sem-linha res)))))))

;; ---------------------------------------------------------------- argumentos

(deftest argumentos
  (let [ente (random-uuid)]
    (is (= {:ente nil :apagar? false} (r/ler-argumentos [])))
    (is (= {:ente ente :apagar? false} (r/ler-argumentos ["--ente" (str ente)])))
    (is (= {:ente ente :apagar? true} (r/ler-argumentos ["--apagar-orfaos" "--ente" (str ente)])))
    (is (= {:ente nil :apagar? true} (r/ler-argumentos ["--apagar-orfaos"])))
    (testing "o que nao se entende e' recusado, com o uso"
      (doseq [ruim [["--ente"] ["--ente" "nao-e-uuid"] ["--tudo"] ["solto"] ["--ente" (str ente) "--ente" (str ente)]]]
        (let [res (r/ler-argumentos ruim)]
          (is (string? (:erro res)) (pr-str ruim))
          (is (str/includes? (:erro res) "uso: reconciliar-anexos")))))))

;; ---------------------------------------------------------------- relatorio

(defn- res-com [n-orfaos n-sem-blob]
  {:linhas 10 :retiradas 1 :blobs (+ 9 n-orfaos)
   :blob-sem-linha (mapv (fn [i] {:chave (format "a/orfao-%04d" i) :modificado-em (ha-horas 30) :motivo :sem-linha :apagavel? true})
                         (range n-orfaos))
   :linha-sem-blob (mapv #(format "a/falta-%04d" %) (range n-sem-blob))})

(deftest as-listas-do-relatorio-sao-limitadas-a-200-com-o-total-ao-lado
  (let [texto (r/formatar {:agora agora
                           :casas [{:ente-id (random-uuid) :nome "Camara de Teste" :estado "ativo"
                                    :pastas [(assoc (res-com 350 201) :pasta "atendimento")]}]})]
    (is (str/includes? texto "blob sem linha: 350 (mostrando 200 de 350)"))
    (is (str/includes? texto "linha sem blob: 201 (mostrando 200 de 201)"))
    (is (str/includes? texto "a/orfao-0199"))
    (is (not (str/includes? texto "a/orfao-0200")) "o 201o fica de fora")
    (is (str/includes? texto "a/falta-0199"))
    (is (not (str/includes? texto "a/falta-0200")))))

(deftest lista-curta-nao-diz-que-truncou
  (let [texto (r/formatar {:agora agora
                           :casas [{:ente-id (random-uuid) :nome "C" :estado "ativo"
                                    :pastas [(assoc (res-com 2 1) :pasta "comunicados")]}]})]
    (is (str/includes? texto "blob sem linha: 2"))
    (is (not (str/includes? texto "mostrando")))
    (is (str/includes? texto "comunicados/"))))

(deftest o-relatorio-diz-o-que-foi-apagado-e-o-que-falhou
  (let [e (random-uuid)
        texto (r/formatar {:agora agora
                           :casas [{:ente-id e :nome "C" :estado "ativo"
                                    :pastas [(assoc (res-com 0 0) :pasta "atendimento")]
                                    :apagados ["atendimento/x/1" "atendimento/x/2"]
                                    :falhas [{:chave "atendimento/x/3" :erro "boom"}]}]})]
    (is (str/includes? texto "apagados do storage: 2"))
    (is (str/includes? texto "atendimento/x/1"))
    (is (str/includes? texto "falhou ao apagar: 1"))
    (is (str/includes? texto "boom"))))

(deftest codigo-de-saida
  (let [ok  {:linhas 1 :retiradas 0 :blobs 1 :blob-sem-linha [] :linha-sem-blob []}
        bad (res-com 1 0)]
    (is (= 0 (r/codigo-de-saida [{:pastas [(assoc ok :pasta "a")]}])))
    (is (= 1 (r/codigo-de-saida [{:pastas [(assoc ok :pasta "a") (assoc bad :pasta "b")]}])) "qualquer pasta divergente")
    (testing "o que o --apagar-orfaos tirou nao conta mais; o que sobrou conta"
      (is (= 0 (r/codigo-de-saida [{:pastas [(assoc bad :pasta "a")] :apagados ["a/orfao-0000"]}])))
      (is (= 1 (r/codigo-de-saida [{:pastas [(assoc (res-com 2 0) :pasta "a")] :apagados ["a/orfao-0000"]}]))))
    (testing "falha ao apagar e' divergencia que sobra"
      (is (= 1 (r/codigo-de-saida [{:pastas [(assoc bad :pasta "a")] :falhas [{:chave "a/orfao-0000" :erro "x"}]}]))))))

;; ---------------------------------------------------------------- orquestracao, com fakes (sem banco, sem storage)

(def ^:private ente-a #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
(def ^:private ente-enc #uuid "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")

(defn- fake-admin [casas]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-admin/RepoAdminSistema
    (casa-por-id [_ ente-id] (first (filter #(= ente-id (:ente-id %)) casas)))
    (listar-casas [_] casas)))

(defn- fake-store
  "blobs: {chave Instant}. Registra o que foi listado e removido; `falha-em` = chaves cujo remover! explode."
  [blobs falha-em]
  (let [estado (atom {:listados [] :removidos []})]
    {:estado estado
     :store #_{:clj-kondo/ignore [:missing-protocol-method]}
     (reify store/ObjetoStore
       (listar-objetos [_ prefixo]
         (swap! estado update :listados conj prefixo)
         (->> blobs (filter (fn [[k _]] (str/starts-with? k prefixo)))
              (map (fn [[k t]] {:chave k :modificado-em t})) (sort-by :chave) vec))
       (remover! [_ chave]
         (when (contains? (set falha-em) chave) (throw (ex-info "storage fora" {})))
         (swap! estado update :removidos conj chave) chave))}))

(defn- cenario
  ([casas blobs fontes] (cenario casas blobs fontes []))
  ([casas blobs fontes falha-em]
   (let [{:keys [estado store]} (fake-store blobs falha-em)]
     {:estado estado
      :deps {:repo-admin (fake-admin casas) :objeto-store store :agora agora
             :fontes (mapv (fn [[pasta f]] {:pasta pasta :chaves f}) fontes)}})))

(def ^:private casa-a {:ente-id ente-a :nome "Camara A" :estado "ativo"})
(def ^:private casa-enc {:ente-id ente-enc :nome "Camara Velha" :estado "encerrado"})

(defn- chave [pasta ente n] (str pasta "/" ente "/p/" n))

(deftest reconciliar-lista-so-o-prefixo-da-casa-e-apaga-so-o-orfao-velho
  (let [velho (chave "atendimento" ente-a "velho") novo (chave "atendimento" ente-a "novo")
        ok (chave "atendimento" ente-a "ok") de-outra (chave "atendimento" ente-enc "x")
        {:keys [deps estado]} (cenario [casa-a]
                                       {ok (ha-horas 90) velho (ha-horas 30) novo (ha-horas 1) de-outra (ha-horas 99)}
                                       {"atendimento" (fn [_] [{:chave ok :retirado? false}
                                                               {:chave "atendimento/x/perdida" :retirado? false}])
                                        "comunicados" (fn [_] [])})
        res (r/reconciliar! deps ente-a true)
        [past] (:pastas (first (:casas res)))]
    (is (= [(str "atendimento/" ente-a "/") (str "comunicados/" ente-a "/")] (:listados @estado)) "so' o prefixo desta Casa")
    (is (= [velho] (:removidos @estado)) "so' o blob sem linha com mais de 24 h")
    (is (= [velho] (:apagados (first (:casas res)))))
    (is (= [novo velho] (map :chave (:blob-sem-linha past))) "o relatorio ainda mostra os dois: o `antes`")
    (is (= ["atendimento/x/perdida"] (:linha-sem-blob past)))
    (is (= 1 (r/codigo-de-saida (:casas res))) "sobraram o recente e a linha sem blob")))

(deftest sem-apagar-orfaos-nada-e-removido
  (let [{:keys [deps estado]} (cenario [casa-a] {(chave "atendimento" ente-a "velho") (ha-horas 300)}
                                       {"atendimento" (fn [_] []) "comunicados" (fn [_] [])})
        res (r/reconciliar! deps nil false)]
    (is (= [] (:removidos @estado)))
    (is (nil? (:apagados (first (:casas res)))))
    (is (= 1 (r/codigo-de-saida (:casas res))))))

(deftest casa-encerrada-ou-em-apagamento-nao-e-tocada
  (let [{:keys [deps estado]} (cenario [casa-a casa-enc {:ente-id (random-uuid) :nome "X" :estado "suspenso"
                                                         :apagamento-iniciado-em agora}]
                                       {(chave "atendimento" ente-enc "velho") (ha-horas 300)}
                                       {"atendimento" (fn [e] (if (= e ente-a) [] (throw (ex-info "tocou no banco de Casa fechada" {}))))})
        res (r/reconciliar! deps nil true)]
    (is (= [nil "encerrada ou em apagamento: nao tocada" "encerrada ou em apagamento: nao tocada"]
           (map :pulada (:casas res))))
    (is (= [(str "atendimento/" ente-a "/")] (:listados @estado)) "as fechadas nem foram listadas")
    (is (= [] (:removidos @estado)))
    (is (= 0 (r/codigo-de-saida (remove :pulada (:casas res)))))))

(deftest ente-fora-do-registro-e-erro
  (let [{:keys [deps]} (cenario [casa-a] {} {"atendimento" (fn [_] [])})
        res (r/executar deps ["--ente" (str (random-uuid))])]
    (is (= 2 (:codigo res)))
    (is (str/includes? (:saida res) "nao esta no registro"))))

(deftest executar-so-uma-casa-com-ente
  (let [outra {:ente-id (random-uuid) :nome "Outra" :estado "ativo"}
        {:keys [deps estado]} (cenario [casa-a outra] {} {"atendimento" (fn [_] [])})
        res (r/executar deps ["--ente" (str ente-a)])]
    (is (= 0 (:codigo res)))
    (is (= [(str "atendimento/" ente-a "/")] (:listados @estado)))
    (is (str/includes? (:saida res) "Resultado: integro."))))

(deftest falha-ao-apagar-um-blob-nao-para-os-outros
  (let [a (chave "atendimento" ente-a "a") b (chave "atendimento" ente-a "b")
        {:keys [deps estado]} (cenario [casa-a] {a (ha-horas 40) b (ha-horas 40)} {"atendimento" (fn [_] [])} [a])
        res (r/reconciliar! deps ente-a true)
        casa (first (:casas res))]
    (is (= [b] (:removidos @estado)))
    (is (= [{:chave a :erro "storage fora"}] (:falhas casa)))
    (is (= 1 (r/codigo-de-saida (:casas res))))))

(deftest argumento-ruim-sai-com-2-sem-tocar-em-nada
  (let [{:keys [deps estado]} (cenario [casa-a] {} {"atendimento" (fn [_] [])})
        res (r/executar deps ["--apague"])]
    (is (= 2 (:codigo res)))
    (is (= [] (:listados @estado)))))
