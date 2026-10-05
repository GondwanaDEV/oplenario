(ns oplenario.reconciliar-anexos
  "HOST (§22.10) — a RECONCILIACAO dos anexos entre o banco e o object storage (ADR-0022, Eixo 3: o blob mora em
  `atendimento/<ente>/<protocolo>/<id>`; ADR-0020: `comunicados/<ente>/<comunicado>/<id>`). E' raiz de composicao porque
  cruza `participacao` e `comunicacao` (que nao se importam, ADR-0001) e o registro de Casas (`admin_sistema`).

  O que mede, por Casa e por pasta:
  - BLOB SEM LINHA: o upload gravou o arquivo e falhou antes do INSERT (ou o anexo foi retirado e o blob ficou);
  - LINHA SEM BLOB: o banco aponta para um arquivo que o storage nao tem.
  O anexo RETIRADO (`participacao.anexo_retirada`) nao tem blob de proposito: linha retirada sem blob e' o estado correto.

  Padrao: so' RELATA. `--apagar-orfaos` remove do STORAGE apenas o blob sem linha com mais de 24 h (um upload em curso tem
  blob antes da linha); nunca toca em linha do banco. A leitura do banco e' por Casa, na tx do tenant (RLS); Casa
  `encerrada` (ou com o apagamento em curso, ADR-0018) nao e' tocada. Sem agendador: e' comando de operacao
  (`reconciliar-anexos`, ver `oplenario.main`).

  A logica pura (`comparar`, `ler-argumentos`, `formatar`, `codigo-de-saida`) nao toca em I/O; a orquestracao
  (`reconciliar!`) recebe as FONTES (de onde vem a lista de chaves esperadas de cada pasta) e o object storage."
  (:require [clojure.string :as str]
            [oplenario.admin-sistema.components.repositorio :as repo-admin]
            [oplenario.comunicacao.components.repositorio :as repo-comunicacao]
            [oplenario.kernel.components.objeto-store :as store]
            [oplenario.participacao.components.repositorio :as repo-participacao])
  (:import (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(def idade-minima-do-orfao
  "Blob sem linha so' e' apagavel depois disto: um upload em curso grava o blob ANTES de inserir a linha."
  (Duration/ofHours 24))

(def limite-da-lista
  "O relatorio mostra no maximo isto de cada lista, SEMPRE com o total verdadeiro ao lado."
  200)

(def uso "uso: reconciliar-anexos [--ente <uuid>] [--apagar-orfaos]")

;; ---------------------------------------------------------------- logica pura

(defn comparar
  "Compara as LINHAS do banco com os BLOBS do storage de UMA pasta de UMA Casa.
  `linhas` [{:chave :retirado?}]; `blobs` [{:chave :modificado-em (Instant | nil)}]; `agora` Instant;
  `idade-minima` (Duration, default 24 h). Devolve
    {:linhas n :retiradas n :blobs n
     :blob-sem-linha [{:chave :modificado-em :motivo (:sem-linha | :retirado) :apagavel? bool}]   ; em ordem de chave
     :linha-sem-blob [chave ...]}                                                                  ; so' as VIGENTES
  `:apagavel?` = mais de `idade-minima` desde a ultima modificacao; sem data (nil) nunca e' apagavel."
  [{:keys [linhas blobs agora idade-minima] :or {idade-minima idade-minima-do-orfao}}]
  (let [vigentes  (into #{} (comp (remove :retirado?) (map :chave)) linhas)
        retiradas (into #{} (comp (filter :retirado?) (map :chave)) linhas)
        presentes (into #{} (map :chave) blobs)
        corte     (.minus ^Instant agora ^Duration idade-minima)]
    {:linhas (count linhas)
     :retiradas (count retiradas)
     :blobs (count blobs)
     :blob-sem-linha (->> blobs
                          (remove #(contains? vigentes (:chave %)))
                          (sort-by :chave)
                          (mapv (fn [{:keys [chave modificado-em]}]
                                  {:chave chave :modificado-em modificado-em
                                   :motivo (if (contains? retiradas chave) :retirado :sem-linha)
                                   :apagavel? (boolean (and modificado-em (.isBefore ^Instant modificado-em corte)))})))
     :linha-sem-blob (->> vigentes (remove presentes) sort vec)}))

(defn diverge? [{:keys [blob-sem-linha linha-sem-blob]}]
  (boolean (or (seq blob-sem-linha) (seq linha-sem-blob))))

(defn ler-argumentos
  "`[\"--ente\" <uuid>] [\"--apagar-orfaos\"]`, em qualquer ordem -> {:ente uuid|nil :apagar? bool}, ou {:erro \"...\"}."
  [args]
  (loop [args (seq args) acc {:ente nil :apagar? false}]
    (if-not args
      acc
      (let [[a & resto] args]
        (cond
          (= "--apagar-orfaos" a) (recur resto (assoc acc :apagar? true))
          (= "--ente" a)
          (let [v (first resto) id (when v (parse-uuid v))]
            (cond (nil? id)   {:erro (str "--ente pede um uuid valido. " uso)}
                  (:ente acc) {:erro (str "--ente so' pode vir uma vez. " uso)}
                  :else       (recur (next resto) (assoc acc :ente id))))
          :else {:erro (str "argumento desconhecido: " a ". " uso)})))))

(defn- restante
  "Quantas divergencias SOBRAM numa Casa depois do que o `--apagar-orfaos` tirou."
  [{:keys [pastas apagados]}]
  (- (reduce + (map #(+ (count (:blob-sem-linha %)) (count (:linha-sem-blob %))) pastas))
     (count apagados)))

(defn codigo-de-saida
  "0 = nada divergente (ou so' o que o `--apagar-orfaos` limpou); 1 = sobrou divergencia. (2 = uso/erro, no `executar`.)"
  [casas]
  (if (some #(pos? (restante %)) casas) 1 0))

(defn- lista
  "Uma lista do relatorio: o titulo com o TOTAL, o corte avisado (`mostrando n de total`) e as linhas."
  [titulo itens ->linha]
  (let [total (count itens)
        cabeca (if (> total limite-da-lista)
                 (format "    %s: %d (mostrando %d de %d)" titulo total limite-da-lista total)
                 (format "    %s: %d" titulo total))]
    (into [cabeca] (map #(str "      " (->linha %))) (take limite-da-lista itens))))

(defn- linha-de-orfao [{:keys [chave modificado-em motivo apagavel?]}]
  (str chave " (" (if modificado-em (str "modificado " modificado-em) "sem data")
       (when (= :retirado motivo) ", anexo retirado")
       "; " (if apagavel? "apagavel" "recente, nao apaga") ")"))

(defn- bloco-da-pasta [{:keys [pasta linhas retiradas blobs blob-sem-linha linha-sem-blob]}]
  (into [(format "  %s/: %d linhas (%d retiradas), %d blobs" pasta linhas retiradas blobs)]
        (concat (lista "blob sem linha" blob-sem-linha linha-de-orfao)
                (lista "linha sem blob" linha-sem-blob identity))))

(defn- bloco-da-casa [{:keys [ente-id nome estado pastas apagados falhas]}]
  (let [se-houver (fn [titulo itens ->linha] (when (seq itens) (lista titulo itens ->linha)))]
    (-> [(format "Casa %s%s [%s]" ente-id (if (str/blank? nome) "" (str " (" nome ")")) estado)]
        (into (mapcat bloco-da-pasta) pastas)
        (into (se-houver "apagados do storage" apagados identity))
        (into (se-houver "falhou ao apagar" falhas #(str (:chave %) " — " (:erro %)))))))

(defn formatar
  "O relatorio em texto. `casas` [{:ente-id :nome :estado :pastas [(comparar + :pasta)] :apagados [chave]
  :falhas [{:chave :erro}] :pulada \"motivo\"}], `agora` Instant."
  [{:keys [casas agora]}]
  (let [tratadas (remove :pulada casas)
        soma (fn [f] (reduce + (for [c tratadas p (:pastas c)] (f p))))
        orfaos (soma #(count (:blob-sem-linha %)))
        apagaveis (soma #(count (filter :apagavel? (:blob-sem-linha %))))
        sem-blob (soma #(count (:linha-sem-blob %)))
        apagados (reduce + (map #(count (:apagados %)) tratadas))]
    (str/join
     "\n"
     (concat
      [(format "Reconciliacao de anexos (banco x object storage) em %s" agora)
       (format "Orfao apagavel = blob sem linha ha mais de %d h. Nada do banco e' alterado." (.toHours ^Duration idade-minima-do-orfao))
       ""]
      (mapcat (fn [c] (if (:pulada c)
                        [(format "Casa %s [%s]: %s" (:ente-id c) (:estado c) (:pulada c)) ""]
                        (conj (bloco-da-casa c) "")))
              casas)
      [(format "Resumo: %d Casa(s); blob sem linha: %d (apagaveis: %d); linha sem blob: %d; apagados agora: %d."
               (count tratadas) orfaos apagaveis sem-blob apagados)
       (if (zero? (codigo-de-saida tratadas)) "Resultado: integro." "Resultado: DIVERGENTE (codigo de saida 1).")]))))

;; ---------------------------------------------------------------- composicao (I/O)

(defn fontes
  "As fontes de chaves esperadas: UMA por pasta do storage, cada uma `(fn [ente-id] -> [{:chave :retirado?}])` pela porta
  do proprio modulo (o host compoe; `participacao` e `comunicacao` nao se importam)."
  [{:keys [repo-participacao repo-comunicacao]}]
  [{:pasta "atendimento" :chaves (fn [ente-id] (repo-participacao/chaves-de-anexos repo-participacao ente-id))}
   {:pasta "comunicados" :chaves (fn [ente-id] (repo-comunicacao/chaves-de-anexos repo-comunicacao ente-id))}])

(defn- pasta-da-casa [objeto-store agora ente-id {:keys [pasta chaves]}]
  (assoc (comparar {:linhas (chaves ente-id)
                    :blobs (store/listar-objetos objeto-store (str pasta "/" ente-id "/"))
                    :agora agora})
         :pasta pasta))

(defn- apagar-orfaos!
  "Remove do STORAGE so' os blobs `:apagavel?` (mais de 24 h, sem linha vigente). Uma falha nao para as demais.
  Devolve {:apagados [chave] :falhas [{:chave :erro}]}."
  [objeto-store pastas]
  (reduce (fn [acc {:keys [chave]}]
            (try (store/remover! objeto-store chave)
                 (update acc :apagados conj chave)
                 (catch Exception e (update acc :falhas conj {:chave chave :erro (or (ex-message e) (str (class e)))}))))
          {:apagados [] :falhas []}
          (for [p pastas o (:blob-sem-linha p) :when (:apagavel? o)] o)))

(defn- fechada?
  "Casa que nao se toca: encerrada, ou com o apagamento comecado (ADR-0018)."
  [{:keys [estado apagamento-iniciado-em]}]
  (or (= "encerrado" estado) (some? apagamento-iniciado-em)))

(defn reconciliar!
  "Reconcilia as Casas (`ente-id` nil = todas as do registro; senao so' ela, que PRECISA estar no registro).
  `deps` {:repo-admin :objeto-store :fontes :agora (Instant)} — `:agora` e' a regra das 24 h. `apagar?` remove os orfaos
  apagaveis do storage. Devolve {:casas [...] :agora} para `formatar`/`codigo-de-saida`, ou {:erro \"...\"}."
  [{:keys [repo-admin objeto-store agora] pastas-fonte :fontes} ente-id apagar?]
  (let [casas (if ente-id
                (some-> (repo-admin/casa-por-id repo-admin ente-id) vector)
                (repo-admin/listar-casas repo-admin))]
    (if (and ente-id (empty? casas))
      {:erro (str "Casa " ente-id " nao esta no registro de Casas: nada a reconciliar (confira o DATABASE_URL).")}
      {:agora agora
       :casas (mapv (fn [c]
                      (let [base {:ente-id (:ente-id c) :nome (:nome c) :estado (:estado c)}]
                        (if (fechada? c)
                          (assoc base :pulada "encerrada ou em apagamento: nao tocada")
                          (let [pastas (mapv #(pasta-da-casa objeto-store agora (:ente-id c) %) pastas-fonte)]
                            (cond-> (assoc base :pastas pastas)
                              apagar? (merge (apagar-orfaos! objeto-store pastas)))))))
                    casas)})))

(defn executar
  "O subcomando `reconciliar-anexos`: `args` (sem o nome do subcomando) -> {:saida \"texto\" :codigo 0|1|2}."
  [deps args]
  (let [{:keys [ente apagar? erro]} (ler-argumentos args)]
    (if erro
      {:saida erro :codigo 2}
      (let [res (reconciliar! deps ente apagar?)]
        (if (:erro res)
          {:saida (:erro res) :codigo 2}
          {:saida (formatar res) :codigo (codigo-de-saida (remove :pulada (:casas res)))})))))
