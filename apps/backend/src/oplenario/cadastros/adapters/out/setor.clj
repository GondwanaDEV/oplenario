(ns oplenario.cadastros.adapters.out.setor
  "dominio -> wire dos setores (ADR-0020, §22.10 adapters/out). `nomes` = {identidade-id nome} das pessoas ATIVAS da
  Casa (seam do host); quem nao esta' nele sai com `ativo` false.")

(set! *warn-on-reflection* true)

(defn setor->wire [nomes s]
  {:id (str (:id s)) :nome (:nome s) :ativo (boolean (:ativo s)) :criado-em (str (:criado-em s))
   :membros (->> (:membros s)
                 (mapv (fn [i] {:identidade-id (str i) :nome (get nomes i) :ativo (contains? nomes i)}))
                 (sort-by (juxt (comp not :ativo) #(or (:nome %) "") :identidade-id))
                 vec)})

(defn lista->wire [nomes setores]
  {:setores (mapv #(setor->wire nomes %) setores)})
