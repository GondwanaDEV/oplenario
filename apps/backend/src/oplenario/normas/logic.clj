(ns oplenario.normas.logic
  "O PARSER de texto legal (docs/25 Eixo 7.2, ADR-0011): o texto de uma norma vira DISPOSITIVOS — artigo, paragrafo,
  inciso, alinea, item —, cada um com ENDERECO estavel (fragmento no estilo LexML, `art12_par1_inc2_ali1`) e o
  ROTULO com que uma pessoa o cita ('art. 12, § 1º, II, a'). Puro e deterministico: sem IA. O que ele nao entende
  vira ALERTA para quem confere (a norma so' vale depois de uma pessoa conferir, Eixo 7.3).

  Convencoes de endereco: `artN` (artigo; `artN-a` para 'Art. N-A'); `_cpt_incN` (inciso do caput); `_parN` e
  `_par1u` (paragrafo unico); `_incN` (inciso, em arabico); `_aliN` (alinea, a=1); `_iteN` (item). O texto antes do
  primeiro artigo (ementa, preambulo) e' um dispositivo so', `preambulo`."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def ^:private valores-romanos {\I 1 \V 5 \X 10 \L 50 \C 100 \D 500 \M 1000})

(defn romano->int
  "'XLIX' -> 49; nil se nao for numeral romano."
  [s]
  (when (and (string? s) (re-matches #"[IVXLCDM]+" s))
    (let [vs (map valores-romanos s)]
      (reduce + (map (fn [v prox] (if (and prox (< v prox)) (- v) v)) vs (concat (rest vs) [nil]))))))

(defn- ordinal [n] (if (<= 1 n 9) (str n "º") (str n)))

(def ^:private travessao "[-–—]")
(def ^:private re-agrupador #"(?i)^(LIVRO|PARTE|T[ÍI]TULO|CAP[ÍI]TULO|SE[ÇC][ÃA]O|SUBSE[ÇC][ÃA]O)\s+([IVXLCDM]+|[ÚU]NIC[OA])\b\.?\s*(.*)$")
(def ^:private re-artigo #"^Art(?:igo)?\.?\s*(\d+)\s*[ºo°]?\s*(?:-\s*([A-Z]))?\s*[.\-–—]?\s*(.*)$")
(def ^:private re-paragrafo #"^§\s*(\d+)\s*[ºo°]?\s*[.\-–—]?\s*(.*)$")
(def ^:private re-par-unico #"(?i)^Par[áa]grafo\s+[úu]nico\s*[.:\-–—]?\s*(.*)$")
(def ^:private re-inciso (re-pattern (str "^([IVXLCDM]+)\\s*" travessao "\\s*(.*)$")))
(def ^:private re-alinea #"^([a-z])\)\s*(.*)$")
(def ^:private re-item #"^(\d+)[.)]\s+(.*)$")

(def ^:private nivel {:agrupador 0 :artigo 1 :paragrafo 2 :inciso 3 :alinea 4 :item 5})

(defn- classificar
  "Uma linha -> {:tipo ... } ou nil (continuacao do dispositivo corrente)."
  [linha]
  (or (when-let [[_ tipo num resto] (re-matches re-agrupador linha)]
        {:tipo :agrupador :titulo (str (str/upper-case tipo) " " num) :resto (str/trim resto)})
      (when-let [[_ n letra texto] (re-matches re-artigo linha)]
        {:tipo :artigo :n (parse-long n) :letra letra :texto texto})
      (when-let [[_ n texto] (re-matches re-paragrafo linha)]
        {:tipo :paragrafo :n (parse-long n) :texto texto})
      (when-let [[_ texto] (re-matches re-par-unico linha)]
        {:tipo :paragrafo :unico true :texto texto})
      (when-let [[_ rom texto] (re-matches re-inciso linha)]
        (when-let [n (romano->int rom)] {:tipo :inciso :n n :romano rom :texto texto}))
      (when-let [[_ l texto] (re-matches re-alinea linha)]
        {:tipo :alinea :n (inc (- (int (first l)) (int \a))) :letra l :texto texto})
      (when-let [[_ n texto] (re-matches re-item linha)]
        {:tipo :item :n (parse-long n) :texto texto})))

(defn- sufixo-e-rotulo
  "Endereco e rotulo do dispositivo `c` sob a pilha `pilha` (os ancestrais abertos, do artigo para baixo)."
  [c pilha]
  (let [pai (peek pilha)]
    (case (:tipo c)
      :artigo [(str "art" (:n c) (when (:letra c) (str "-" (str/lower-case (:letra c)))))
               (str "art. " (ordinal (:n c)) (when (:letra c) (str "-" (:letra c))))]
      :paragrafo [(str (:endereco pai) (if (:unico c) "_par1u" (str "_par" (:n c))))
                  (str (:rotulo pai) ", " (if (:unico c) "parágrafo único" (str "§ " (ordinal (:n c)))))]
      :inciso [(str (:endereco pai) (when (= :artigo (:tipo pai)) "_cpt") "_inc" (:n c))
               (str (:rotulo pai) ", " (:romano c))]
      :alinea [(str (:endereco pai) "_ali" (:n c)) (str (:rotulo pai) ", " (:letra c))]
      :item [(str (:endereco pai) "_ite" (:n c)) (str (:rotulo pai) ", item " (:n c))])))

(defn- aceita-pai?
  "O dispositivo de tipo `t` pode ficar sob `pai`? Inciso sob artigo ou paragrafo; alinea sob inciso; item sob
  alinea; paragrafo sob artigo."
  [t pai]
  (case t
    :paragrafo (= :artigo (:tipo pai))
    :inciso (#{:artigo :paragrafo} (:tipo pai))
    :alinea (= :inciso (:tipo pai))
    :item (= :alinea (:tipo pai))
    false))

(defn- linhas [texto]
  (->> (str/split-lines (or texto ""))
       (map #(str/trim (str/replace % #"\s+" " ")))))

(defn- juntar [a b] (str/trim (str a (when (seq a) " ") b)))

(defn dispositivos
  "Texto da norma -> {:dispositivos [{:endereco :rotulo :tipo :pai :ordem :texto :agrupador}] :alertas [string]}."
  [texto]
  (loop [[l & resto] (linhas texto)
         st {:saida [] :pilha [] :agrupadores {} :titulo-aberto nil :alertas [] :vistos #{} :ultimo-art nil
             :preambulo ""}]
    (if (nil? l)
      (let [{:keys [saida alertas preambulo]} st
            pre (when (seq (str/trim preambulo))
                  [{:endereco "preambulo" :rotulo "preâmbulo" :tipo :preambulo :pai nil :texto (str/trim preambulo)
                    :agrupador nil}])
            todos (vec (map-indexed (fn [i d] (assoc d :ordem i)) (concat pre saida)))]
        {:dispositivos todos
         :alertas (cond-> alertas
                    (not-any? #(= :artigo (:tipo %)) todos)
                    (conj "Nenhum artigo reconhecido: confira se o texto está completo e se os artigos começam com \"Art.\"."))})
      (if (str/blank? l)
        (recur resto (assoc st :titulo-aberto nil))
        (let [c (classificar l)]
          (cond
            ;; agrupador: TITULO/CAPITULO/SECAO; o nome costuma vir na linha seguinte
            (= :agrupador (:tipo c))
            (let [chave (first (str/split (:titulo c) #" "))
                  nivel-ag (.indexOf ["LIVRO" "PARTE" "TÍTULO" "TITULO" "CAPÍTULO" "CAPITULO" "SEÇÃO" "SECAO"
                                      "SUBSEÇÃO" "SUBSECAO"] chave)
                  ags (into {} (remove (fn [[k _]] (> (first k) nivel-ag))) (:agrupadores st))]
              (recur resto (assoc st :agrupadores (assoc ags [nivel-ag chave] (juntar (:titulo c) (when (seq (:resto c)) (str "— " (:resto c)))))
                                  :titulo-aberto [nivel-ag chave] :pilha [])))

            ;; linha logo abaixo do agrupador, sem marca de dispositivo: e' o nome dele
            (and (nil? c) (:titulo-aberto st))
            (let [k (:titulo-aberto st)
                  atual (get-in st [:agrupadores k])]
              (recur resto (assoc-in st [:agrupadores k] (if (str/includes? atual "—") (juntar atual l) (str atual " — " l)))))

            ;; continuacao: texto do dispositivo corrente, ou do preambulo
            (nil? c)
            (if-let [idx (some-> st :saida seq count dec)]
              (recur resto (update-in st [:saida idx :texto] juntar l))
              (recur resto (update st :preambulo juntar l)))

            :else
            (let [pilha (if (= :artigo (:tipo c))
                          []
                          (vec (take-while #(< (nivel (:tipo %)) (nivel (:tipo c))) (:pilha st))))
                  pai (peek pilha)]
              (if (and (not= :artigo (:tipo c)) (not (aceita-pai? (:tipo c) pai)))
                (if (nil? (:ultimo-art st))
                  (recur resto (-> st
                                   (update :preambulo juntar l)
                                   (update :alertas conj (str "\"" (subs l 0 (min 40 (count l))) "\" aparece antes do primeiro artigo: ficou no preâmbulo."))))
                  (recur resto (-> (if-let [idx (some-> st :saida seq count dec)]
                                     (update-in st [:saida idx :texto] juntar l)
                                     st)
                                   (update :alertas conj (str "\"" (subs l 0 (min 40 (count l))) "\" não se encaixa no dispositivo anterior: ficou como continuação do texto.")))))
                (let [[endereco rotulo] (sufixo-e-rotulo c pilha)
                      d {:endereco endereco :rotulo rotulo :tipo (:tipo c) :pai (:endereco pai) :texto (str/trim (:texto c))
                         :agrupador (when-let [ags (seq (:agrupadores st))] (str/join " / " (map val (sort-by key ags))))}
                      salto (when (and (= :artigo (:tipo c)) (nil? (:letra c)) (:ultimo-art st)
                                       (> (:n c) (inc (:ultimo-art st))))
                              (str "Depois do art. " (ordinal (:ultimo-art st)) " vem o art. " (ordinal (:n c))
                                   ": confira se faltou algum artigo (ou se foi revogado)."))]
                  (if (contains? (:vistos st) endereco)
                    (recur resto (update st :alertas conj (str rotulo " aparece repetido: ficou só o primeiro.")))
                    (recur resto (cond-> (-> st
                                             (update :saida conj d)
                                             (assoc :pilha (conj pilha d) :titulo-aberto nil)
                                             (update :vistos conj endereco))
                                   (and (= :artigo (:tipo c)) (nil? (:letra c))) (assoc :ultimo-art (:n c))
                                   salto (update :alertas conj salto)))))))))))))

;; ---------- a norma que a Casa importa ----------

(def especies-da-casa
  "As especies que uma Casa importa e confere (federal e estadual sao curadoria do produto, Eixo 7.1/7.3)."
  #{"lei_organica" "regimento_interno" "lei_complementar" "lei" "resolucao" "decreto" "outra"})

(defn camada-da-especie
  "A LOM e as leis sao do MUNICIPIO; o Regimento Interno, as resolucoes e o resto sao da CASA (Eixo 7.1)."
  [especie]
  (if (#{"lei_organica" "lei_complementar" "lei" "decreto"} especie) "municipal" "casa"))

(defn unica-na-casa?
  "LOM e Regimento Interno sao unicos na Casa: nao levam numero."
  [especie]
  (contains? #{"lei_organica" "regimento_interno"} especie))

(defn sha256-hex [^String s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))
