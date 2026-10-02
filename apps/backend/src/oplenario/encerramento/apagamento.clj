(ns oplenario.encerramento.apagamento
  "HOST (§22.10) — o APAGAMENTO irreversivel dos dados de uma Casa encerrada (ADR-0018, Eixo 4.5). Somos operador
  (LGPD), a Casa e' controladora: depois da guarda, sai tudo do banco, do object storage, do IdP e do satelite. Fica so'
  o supratenant (Eixo 4.6): o registro, a atuacao da Operacao, os selos ancorados e o hash da exportacao entregue.

  A ordem e' a da retomada (cada passo idempotente; rodar de novo termina o que faltou sem erro):
    1. CONFERIR no banco (`admin_sistema.conferir_apagamento`) — nada externo e' tocado sem as salvaguardas;
    2. os BLOBS da Casa (convencao + referencias, lidas enquanto as linhas existem) — falha aqui LANCA, e nada do banco
       foi apagado ainda;
    3. o BANCO (`admin_sistema.apagar_dados_da_casa`, SECURITY DEFINER do dono: confere de novo e apaga numa transacao
       so') — falha LANCA;
    4. as EXPORTACOES `exportacoes/<ente>/` (depois do banco: ate' aqui a Casa ainda podia baixar a dela);
    5. o REALM da Casa no IdP e 6. os dados no SATELITE de IA — fora do ar = `pendente`, nunca sucesso fingido;
    7. a VARREDURA: o banco e os blobs da convencao de novo. A Casa fechou quando o apagamento comecou (mig 0177), mas
       uma escrita que ja' estava em voo naquele instante (e o evento que ela emitiu) pode ter gravado depois do passo
       3; a varredura, segundos depois, apaga o que sobrou. O resumo soma as duas passadas e diz quanto a varredura
       achou (`:varredura`)."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.encerramento.arquivos :as arquivos]
            [oplenario.encerramento.inventario :as inventario]
            [oplenario.integracao-ia.diplomat.http.out :as plataforma-ia]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.objeto-store :as store]
            [oplenario.kernel.tenancy :as tenancy])
  (:import (java.util UUID)
           (org.postgresql.util PGobject PSQLException)))

(set! *warn-on-reflection* true)

(defn- recusado-pelo-banco
  "A excecao do banco -> ex-info legivel. `apagamento recusado: ...` (as salvaguardas) vira `:encerramento/recusado`."
  [^Exception e]
  (let [msg (or (some-> (when (instance? PSQLException e) (.getServerErrorMessage ^PSQLException e)) .getMessage)
                (ex-message e))]
    (if (and msg (str/starts-with? msg "apagamento recusado"))
      (ex-info (str/replace-first msg "apagamento recusado: " "Apagamento recusado: ")
               {:tipo :encerramento/recusado} e)
      (ex-info (str "O apagamento da Casa falhou no banco: " msg) {:tipo :encerramento/falhou :passo :banco} e))))

(defn- no-banco [f]
  (try (f) (catch PSQLException e (throw (recusado-pelo-banco e)))))

(defn conferir!
  "As salvaguardas do Eixo 4.5, conferidas no banco. Recusa = ex-info `:encerramento/recusado` com o porque."
  [ds ente-id pedido-id]
  (no-banco #(jdbc/execute-one! ds ["SELECT admin_sistema.conferir_apagamento(?::uuid, ?::uuid)"
                                    (str ente-id) (str pedido-id)])))

(defn- blobs-da-casa
  "As chaves a remover: a convencao `<pasta>/<ente>/` (fora `exportacoes/`, que sai no passo 4) + as referencias das
  linhas que estao DENTRO da convencao (as de fora sao relatadas, nao apagadas — ver `arquivos`)."
  [ds objeto-store ente-id]
  (let [tabelas (filter :exporta? (inventario/inventario ds))
        refs (tenancy/com-tenant* ds ente-id #(arquivos/referencias % tabelas ente-id))]
    {:chaves (into (arquivos/por-convencao objeto-store ente-id #{arquivos/pasta-das-exportacoes})
                   (filter #(arquivos/da-convencao? ente-id %) refs))
     :fora-da-convencao (vec (remove #(arquivos/da-convencao? ente-id %) refs))}))

(defn- remover-todos! [objeto-store chaves passo]
  (doseq [k chaves]
    (try (store/remover! objeto-store k)
         (catch Exception e
           (throw (ex-info (str "O apagamento parou ao remover arquivos (" passo "): " (ex-message e) " — rode de novo")
                           {:tipo :encerramento/falhou :passo passo :chave k} e)))))
  (count chaves))

(defn- apagar-no-banco!
  "{\"schema.tabela\" linhas} — a funcao do dono, numa transacao propria e curta."
  [ds ente-id pedido-id]
  (let [^PGobject r (-> (no-banco #(jdbc/execute-one! ds ["SELECT admin_sistema.apagar_dados_da_casa(?::uuid, ?::uuid) AS r"
                                                         (str ente-id) (str pedido-id)]
                                                     {:builder-fn rs/as-unqualified-maps}))
                        :r)]
    (into (sorted-map) (json/read-value (.getValue r)))))

(defn- exportacao-entregue
  "A exportacao confirmada que liberou o apagamento (o hash dela fica no registro para sempre)."
  [ds ente-id]
  (some-> (jdbc/execute-one! ds ["SELECT id, sha256, confirmada_em FROM admin_sistema.exportacao_casa
                                   WHERE ente_id = ?::uuid AND estado = 'pronta' AND confirmada_em IS NOT NULL
                                   ORDER BY confirmada_em DESC LIMIT 1" (str ente-id)]
                                 {:builder-fn rs/as-unqualified-maps})
          (update :confirmada_em #(some-> % (as-> x (if (instance? java.sql.Timestamp x) (.toInstant ^java.sql.Timestamp x) x)) str))
          (update-keys #(keyword (str/replace (name %) "_" "-")))))

(defn- passo-externo
  "Roda um passo externo (realm/IA). Falha ou dependencia ausente = {:pendente true :motivo ...}: o apagamento fica
  retomavel e nunca relata sucesso que nao houve."
  [nome f]
  (if-not f
    {:pendente true :motivo (str nome " nao configurado")}
    (try (f)
         (catch Exception e {:pendente true :motivo (or (ex-message e) (.getName (class e)))}))))

(defn apagar!
  "Ver `oplenario.encerramento/apagar-casa!` (o contrato, os `deps` e o resumo)."
  [{:keys [ds objeto-store] :as deps} ente-id pedido-id]
  (let [ente-id (UUID/fromString (str ente-id))]
    (when-not (and ds objeto-store)
      (throw (ex-info "apagamento: faltam dependencias (:ds, :objeto-store)" {:tipo :encerramento/falhou})))
    (conferir! ds ente-id pedido-id)
    (let [{:keys [chaves fora-da-convencao]} (blobs-da-casa ds objeto-store ente-id)
          objetos (remover-todos! objeto-store chaves "arquivos da Casa")
          tabelas (apagar-no-banco! ds ente-id pedido-id)
          exportacoes (remover-todos! objeto-store
                                      (store/listar objeto-store (arquivos/prefixo-das-exportacoes ente-id) true)
                                      "exportacoes")
          realm (passo-externo "IdP" (when-let [i (:idp deps)] #(idp/apagar-realm! i ente-id)))
          ia (passo-externo "satelite de IA"
                            (when-let [p (:plataforma-ia deps)]
                              #(let [r (plataforma-ia/apagar-ente p ente-id)]
                                 {:pendente false
                                  :apagados (into (sorted-map) (map (fn [[k v]] [(name k) v])) (:apagados r))
                                  :total (:total r)})))
          pendencias (cond-> []
                       (:pendente realm) (conj :realm)
                       (:pendente ia) (conj :ia))
          sobra-blobs (remover-todos! objeto-store
                                      (arquivos/por-convencao objeto-store ente-id #{arquivos/pasta-das-exportacoes})
                                      "varredura dos arquivos")
          sobra (apagar-no-banco! ds ente-id pedido-id)
          tabelas (merge-with + tabelas sobra)]
      {:ente-id ente-id
       :tabelas tabelas
       :linhas-total (reduce + 0 (vals tabelas))
       :varredura {:linhas (reduce + 0 (vals sobra)) :objetos sobra-blobs}
       :objetos (+ objetos sobra-blobs)
       :objetos-fora-da-convencao fora-da-convencao
       :exportacoes-apagadas exportacoes
       :realm-apagado? (not (:pendente realm))
       :realm realm
       :ia ia
       :exportacao (exportacao-entregue ds ente-id)
       :pendencias pendencias
       :completo? (empty? pendencias)})))
