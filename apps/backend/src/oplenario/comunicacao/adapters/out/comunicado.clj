(ns oplenario.comunicacao.adapters.out.comunicado
  "dominio -> wire dos comunicados (ADR-0020, §22.10 adapters/out). `hoje` (o dia civil da Casa, lido na borda) decide
  os vencidos — o vencimento e' calculado na leitura (fatia 3). A `chave_objeto` do anexo nunca sai no fio."
  (:require [oplenario.comunicacao.logic :as logic]))

(set! *warn-on-reflection* true)

(defn- s [x] (some-> x str))

(defn- ref->wire [r] (when (and r (:id r)) {:id (str (:id r)) :protocolo (str (:protocolo r))}))

(defn- objeto->wire [c] (when (:objeto-tipo c) {:tipo (:objeto-tipo c) :id (str (:objeto-id c))}))

(defn- remetente->wire [c] {:identidade-id (str (:remetente-identidade-id c)) :nome (:remetente-nome c)})

(defn destino->wire [d] {:tipo (:tipo d) :alvo-id (s (:alvo-id d)) :alvo-nome (:alvo-nome d)})

(defn anexo->wire [a]
  {:id (str (:id a)) :nome (:nome a) :tipo-midia (:tipo-midia a) :bytes (long (:bytes a)) :sha256 (:sha256 a)
   :enviado-em (str (:enviado-em a))})

(defn marcas->wire [c marcas hoje]
  {:recebido-em (s (:recebido-em marcas)) :lido-em (s (:lido-em marcas)) :ciente-em (s (:ciente-em marcas))
   :vencido (boolean (logic/vencido? c (:ciente-em marcas) hoje))})

(defn comunicado->wire
  "O detalhe. `extras` = {:pode-ver-leitura :pode-anexar}."
  [c hoje {:keys [pode-ver-leitura pode-anexar]}]
  {:id (str (:id c)) :protocolo (:protocolo c) :assunto (:assunto c) :corpo (:corpo c)
   :remetente (remetente->wire c) :enviado-em (str (:enviado-em c))
   :exige-ciencia (boolean (:exige-ciencia c)) :ciencia-ate (s (:ciencia-ate c))
   :prazo-vencido (logic/prazo-vencido? c hoje)
   :objeto (objeto->wire c)
   :destinos (mapv destino->wire (:destinos c))
   :destinatarios (long (or (:n-destinatarios c) 0))
   :anexos (mapv anexo->wire (:anexos c))
   :substitui (ref->wire (:substitui c))
   :substituido-por (ref->wire (:substituido-por c))
   :minhas-marcas (when (:destinatario c) (marcas->wire c (:minhas-marcas c) hoje))
   :pode-ver-leitura (boolean pode-ver-leitura)
   :pode-anexar (boolean pode-anexar)})

(defn enviado->wire [c hoje extras sem-acesso]
  (assoc (comunicado->wire c hoje extras) :sem-acesso (long sem-acesso)))

(defn caixa->wire [{:keys [itens resumo]} hoje]
  {:itens (mapv (fn [c]
                  (let [mm (:minhas-marcas c)]
                    {:id (str (:id c)) :protocolo (:protocolo c) :assunto (:assunto c)
                     :trecho (logic/trecho (:corpo c))
                     :remetente (remetente->wire c) :enviado-em (str (:enviado-em c)) :via (:via c)
                     :exige-ciencia (boolean (:exige-ciencia c)) :ciencia-ate (s (:ciencia-ate c))
                     :vencido (boolean (logic/vencido? c (:ciente-em mm) hoje))
                     :recebido-em (s (:recebido-em mm)) :lido-em (s (:lido-em mm)) :ciente-em (s (:ciente-em mm))
                     :objeto (objeto->wire c) :n-anexos (long (:n-anexos c 0))
                     :substituido-por (ref->wire (:substituido-por c))}))
                itens)
   :nao-lidos (long (:nao-lidos resumo 0))
   :pendentes-ciencia (count (:pendentes resumo))
   :proxima-ciencia-ate (s (logic/proxima-ciencia-ate (:pendentes resumo) hoje))})

(defn enviados->wire [escopo cs hoje]
  {:escopo escopo
   :itens (mapv (fn [c]
                  {:id (str (:id c)) :protocolo (:protocolo c) :assunto (:assunto c)
                   :remetente (remetente->wire c) :enviado-em (str (:enviado-em c))
                   :destinos (mapv destino->wire (:destinos c))
                   :exige-ciencia (boolean (:exige-ciencia c)) :ciencia-ate (s (:ciencia-ate c))
                   :prazo-vencido (logic/prazo-vencido? c hoje)
                   :destinatarios (long (:destinatarios c)) :recebidos (long (:recebidos c))
                   :lidos (long (:lidos c)) :cientes (long (:cientes c))
                   :pendentes-vencidos (long (logic/pendentes-vencidos c hoje))
                   :substituido-por (ref->wire (:substituido-por c))})
                cs)})

(defn leitura->wire [c linhas hoje]
  (let [ls (mapv (fn [l] {:identidade-id (str (:identidade-id l)) :nome (:nome l) :via (:via l)
                          :recebido-em (s (:recebido-em l)) :lido-em (s (:lido-em l)) :ciente-em (s (:ciente-em l))
                          :vencido (boolean (logic/vencido? c (:ciente-em l) hoje))})
                 linhas)
        totais (logic/totais-da-leitura ls)]
    {:comunicado {:id (str (:id c)) :protocolo (:protocolo c) :assunto (:assunto c)
                  :exige-ciencia (boolean (:exige-ciencia c)) :ciencia-ate (s (:ciencia-ate c))
                  :prazo-vencido (logic/prazo-vencido? c hoje)}
     :totais (assoc totais :faltam-ciencia (if (:exige-ciencia c) (- (:destinatarios totais) (:cientes totais)) 0))
     :linhas ls}))

(defn ciencia->wire [c marcas hoje]
  {:id (str (:id c)) :protocolo (:protocolo c) :minhas-marcas (marcas->wire c marcas hoje)})

(defn destinos->wire [{:keys [pode-enviar-a-grupos pessoas vereadores setores comissoes todos-os-setores]}]
  {:pode-enviar-a-grupos (boolean pode-enviar-a-grupos)
   :pessoas (mapv (fn [p] {:identidade-id (str (:identidade-id p)) :nome (:nome p)}) pessoas)
   :vereadores (mapv (fn [v] {:id (str (:id v)) :nome (:nome v) :tem-acesso (boolean (:tem-acesso v))}) vereadores)
   :setores (mapv (fn [x] {:id (str (:id x)) :nome (:nome x) :membros (long (:membros x))}) setores)
   :comissoes (mapv (fn [x] {:id (str (:id x)) :nome (:nome x) :membros (long (:membros x))
                             :sem-acesso (long (:sem-acesso x 0))})
                    comissoes)
   :todos-os-setores (long (or todos-os-setores 0))})
