(ns oplenario.compliance.gerador-remessa
  "O \"renderizador proprio\" (§22.7.8 dec.2b/D1) do descritor declarativo de layout de remessa. PURO: le o
  descritor (dado, models/descritor-remessa) + os valores JA RESOLVIDOS pelas fontes (contexto + relacoes
  escalares do registry + read-ports em lote) e projeta um DOCUMENTO INTERMEDIARIO formato-agnostico. A
  serializacao no formato fisico (XML/posicional/CSV/proprio) e' do port SerializadorRemessa (D4); a
  proveniencia (chamar relacoes/read-ports) + a persistencia (`remessa_gerada` + binario no objeto_store)
  sao do Repo (`gerar-remessa!`). Aqui NAO ha banco, motor nem I/O — so a projecao.

  Fail-closed: um campo declarado no descritor que nao resolve LANCA — artefato regulatorio nao sai com
  campo em branco silencioso (perder janela por arquivo errado e' o 'incidente inaceitavel', §5).

  O `descritor-sim-fixture` e' ILUSTRATIVO: o layout FISICO do SIM/TCE-CE = [GAP] de conteudo regulatorio
  (campos/ordem/formato/encoding reais), nao inventado aqui — a forma fecha com fixture (disciplina B/C:
  'a forma nao depende do valor').")

(set! *warn-on-reflection* true)

;; ---------- descritor-fixture (ILUSTRATIVO — layout fisico do SIM = [GAP], §22.7.8) ----------

(def descritor-sim-fixture
  "Descritor declarativo de layout de EXEMPLO p/ a remessa mensal do SIM (TCE-CE). DADO ilustrativo: os
  campos/colunas/formato reais sao [GAP] regulatorio. Reusa o registry de relacoes como fonte (`:relacao`)."
  {:spec-layout-versao "fixture-sim-v0"
   :sistema "SIM"
   :content-type "application/xml"
   :cabecalho [{:campo "competencia" :fonte [:contexto "competencia"]}
               {:campo "nome_ente"   :fonte [:relacao "nome_ente"]}]
   :registros {:fonte [:lote "despesas"]
               :colunas [{:campo "data_lancamento" :de "data"}
                         {:campo "valor_total"     :de "valor"}]}})

;; ---------- renderizador puro ----------

(defn- resolver-escalar
  "Resolve UM campo escalar do cabecalho contra os valores ja' resolvidos. nil OU ausente = nao resolvido
  -> LANCA (fail-closed estrito; num artefato regulatorio nil tambem e' campo em branco)."
  [resolvidos {:keys [campo fonte]}]
  (let [[tipo chave] fonte
        v (case tipo
            :contexto (get (:contexto resolvidos) chave)
            :relacao  (get (:relacoes resolvidos) chave))]
    (when (nil? v)
      (throw (ex-info "campo de remessa nao resolvido" {:campo campo :fonte fonte})))
    [campo v]))

(defn- resolver-registros
  "Projeta a secao de registros do read-port em lote: o lote AUSENTE (read-port nao consultado) LANCA; um
  lote VAZIO ([]) e' legitimo (competencia sem registros) -> [] registros. Cada coluna mapeia a chave do
  registro de origem (`:de`) p/ o campo de saida (`:campo`); valor de coluna nil = nao resolvido -> LANCA."
  [resolvidos {:keys [fonte colunas]}]
  (let [[_ chave] fonte
        lote (get (:lotes resolvidos) chave ::ausente)]
    (when (= lote ::ausente)
      (throw (ex-info "lote de remessa nao resolvido" {:fonte fonte})))
    (mapv (fn [reg]
            (reduce (fn [acc {:keys [campo de]}]
                      (let [v (get reg de)]
                        (when (nil? v)
                          (throw (ex-info "campo de remessa nao resolvido" {:campo campo :de de})))
                        (assoc acc campo v)))
                    {} colunas))
          lote)))

(defn renderizar
  "Renderizador proprio (PURO): projeta o `descritor` sobre os valores `resolvidos`
  ({:contexto {chave->v} :relacoes {nome->v} :lotes {chave->[registro]}}) num DOCUMENTO INTERMEDIARIO
  ({:spec-layout-versao :sistema :cabecalho {campo->v} :registros [{campo->v}...]}). Formato-agnostico —
  o port SerializadorRemessa o leva ao formato fisico. Fail-closed em campo/lote nao resolvido."
  [descritor resolvidos]
  {:spec-layout-versao (:spec-layout-versao descritor)
   :sistema            (:sistema descritor)
   :cabecalho          (into {} (map #(resolver-escalar resolvidos %)) (:cabecalho descritor))
   :registros          (if-let [sec (:registros descritor)]
                         (resolver-registros resolvidos sec)
                         [])})
