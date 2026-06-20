(ns oplenario.motor.verificador
  "Type-checker do save time (§22.7 Eixo A dec. 2): valida envelope + expressões
  contra o catálogo (B3). Regra mal-tipada NÃO vira 'vigente' — tira a falha de
  compliance do caminho crítico (princípio comercial / Invariante 4).

  Separa de propósito: :status VALIDA/INVALIDA (decide persistência, dec. 2) e
  :expressao-ok (se o NÚCLEO tipou, independe do envelope). T4 é o caso-chave:
  expressao-ok=true mas status=INVALIDA (envelope de compliance não cabe — S4)."
  (:require [clojure.string :as str]
            [oplenario.motor.catalogo :as cat]
            [oplenario.motor.nucleo :as nuc]
            [oplenario.motor.tipos :as t]))

(defn- erro-tipo [msg] (ex-info msg {:erro :tipo}))

(def ^:private lit-tipo {"Inteiro" t/INTEIRO "Texto" t/TEXTO "Booleano" t/BOOLEANO})

(declare inferir)

(defn- inf-ident [nome env]
  (cond
    (contains? env nome) (get env nome)
    (cat/dominio-do-literal nome) (t/enum-t (cat/dominio-do-literal nome))
    :else (throw (erro-tipo (str "identificador desconhecido: " (pr-str nome))))))

(defn- inf-campo [no env]
  (let [tp (inferir (:obj no) env)]
    (when (not= (:kind tp) :registro)
      (throw (erro-tipo (str "acesso a campo ." (:campo no) " em tipo não-registro " (t/->str tp)))))
    (let [campos (get cat/REGISTROS (:nome tp) {})]
      (when-not (contains? campos (:campo no))
        (throw (erro-tipo (str "registro " (:nome tp) " não tem campo " (pr-str (:campo no))))))
      (get campos (:campo no)))))

(defn- inf-parametro-tenant [no]
  (when (not= 1 (count (:args no)))
    (throw (erro-tipo "parametro_tenant: esperava 1 arg (a chave)")))
  (let [a (first (:args no))]
    (when-not (and (= (:t a) :lit) (= (:tipo-lit a) "Texto"))
      (throw (erro-tipo "parametro_tenant: a chave deve ser literal Texto")))
    (when-not (contains? cat/PARAMETROS-TENANT (:valor a))
      (throw (erro-tipo (str "parametro_tenant: chave desconhecida " (pr-str (:valor a))))))
    (get cat/PARAMETROS-TENANT (:valor a))))

(defn- inf-chamada [no env]
  (if (= (:nome no) "parametro_tenant")
    (inf-parametro-tenant no)
    (let [sig (cat/buscar-assinatura (:nome no))]
      (when (nil? sig) (throw (erro-tipo (str "função desconhecida: " (pr-str (:nome no))))))
      (when (not= (count (:args no)) (count (:params sig)))
        (throw (erro-tipo (str (:nome no) ": esperava " (count (:params sig)) " arg(s), veio " (count (:args no))))))
      (doseq [[k formal arg] (map vector (range 1 (inc (count (:params sig)))) (:params sig) (:args no))]
        (let [real (inferir arg env)]
          (when-not (t/compativel-argumento? formal real)
            (throw (erro-tipo (str (:nome no) ": arg " k " esperava " (t/->str formal) ", veio " (t/->str real)))))))
      (:retorno sig))))

(defn- inf-conjunto [no env]
  (let [els (:elementos no)]
    (when (empty? els) (throw (erro-tipo "conjunto vazio sem tipo declarado")))
    (let [tipos-el (mapv #(inferir % env) els)
          t0 (first tipos-el)]
      (doseq [tt (rest tipos-el)]
        (when (not= tt t0)
          (throw (erro-tipo (str "conjunto heterogêneo: " (t/->str t0) " vs " (t/->str tt))))))
      (t/Conjunto t0))))

(defn- inf-aditivo
  "Tipagem de + e - : numérico (com coerção Inteiro<->Racional) OU aritmética temporal real —
  Data/Instante ± Duracao → mesmo temporal; (mesmo temporal) - (mesmo temporal) → Duracao;
  Duracao ± Duracao → Duracao. Multiplicação NÃO entra aqui (só numérica)."
  [op le ri]
  (cond
    (and (t/numerico? le) (t/numerico? ri))
    (if (or (= le t/RACIONAL) (= ri t/RACIONAL)) t/RACIONAL t/INTEIRO)

    (and (t/temporal? le) (= ri t/DURACAO)) le                       ; Data + dias → Data; Data - dias → Data
    (and (= op "+") (= le t/DURACAO) (t/temporal? ri)) ri            ; dias + Data → Data (soma comuta)
    (and (= op "-") (t/temporal? le) (= le ri)) t/DURACAO            ; Data - Data → Duracao (mesmo tipo)
    (and (= le t/DURACAO) (= ri t/DURACAO)) t/DURACAO                ; Duracao ± Duracao → Duracao

    :else
    (throw (erro-tipo (str "'" op "' inválido para " (t/->str le) " e " (t/->str ri)
                           " (numérico, ou Data/Instante ± Duracao)")))))

(defn- inf-binop [no env]
  (let [op (:op no) le (inferir (:esq no) env) ri (inferir (:dir no) env)]
    (cond
      (#{"e" "ou"} op)
      (if (and (= le t/BOOLEANO) (= ri t/BOOLEANO)) t/BOOLEANO
          (throw (erro-tipo (str "operador '" op "' exige Booleano, veio " (t/->str le) " e " (t/->str ri)))))

      (= op "in")
      (cond
        (not= (:kind ri) :conjunto) (throw (erro-tipo (str "'in' exige Conjunto à direita, veio " (t/->str ri))))
        (not= le (:elem ri)) (throw (erro-tipo (str "'in': " (t/->str le) " não é elemento de " (t/->str ri))))
        :else t/BOOLEANO)

      (#{">" ">=" "<" "<="} op)
      (if (or (and (t/numerico? le) (t/numerico? ri))
              (and (t/temporal? le) (= le ri)))                 ; ordem temporal só entre o MESMO tipo (Instante<Data é erro)
        t/BOOLEANO
        (throw (erro-tipo (str "comparação '" op "' exige numérico homogêneo ou mesmo tipo temporal, veio " (t/->str le) " e " (t/->str ri)))))

      (#{"==" "!="} op)
      (if (or (= le ri) (and (t/numerico? le) (t/numerico? ri))) t/BOOLEANO
          (throw (erro-tipo (str "'" op "' exige tipos compatíveis, veio " (t/->str le) " e " (t/->str ri)))))

      (= op "*")
      (if (and (t/numerico? le) (t/numerico? ri))
        (if (or (= le t/RACIONAL) (= ri t/RACIONAL)) t/RACIONAL t/INTEIRO)
        (throw (erro-tipo (str "'*' exige numérico, veio " (t/->str le) " e " (t/->str ri)))))

      (#{"+" "-"} op)
      (inf-aditivo op le ri)

      :else (throw (erro-tipo (str "operador binário desconhecido: " op))))))

(defn- inf-unop [no env]
  (let [tp (inferir (:operando no) env)]
    (if (= (:op no) "nao")
      (if (= tp t/BOOLEANO) t/BOOLEANO (throw (erro-tipo (str "'nao' exige Booleano, veio " (t/->str tp)))))
      (throw (erro-tipo (str "operador unário desconhecido: " (:op no)))))))

(defn inferir [no env]
  (case (:t no)
    :lit (or (get lit-tipo (:tipo-lit no))
             (throw (erro-tipo (str "literal com tipo-lit desconhecido: " (pr-str (:tipo-lit no))))))
    :ident (inf-ident (:nome no) env)
    :campo (inf-campo no env)
    :chamada (inf-chamada no env)
    :conjunto-lit (inf-conjunto no env)
    :binop (inf-binop no env)
    :unop (inf-unop no env)
    (throw (erro-tipo (str "nó AST desconhecido: " (pr-str no))))))

;; ===========================================================================
;; Verificação de um template inteiro
;; ===========================================================================
(def ^:private sentinela ::erro)

(defn- checar-expr
  "Tenta parsear+tipar `fonte`; acumula em `erros` (atom). Devolve true se OK."
  [rotulo fonte env esperado erros]
  (if (nil? fonte)
    (do (swap! erros conj (str rotulo ": ausente")) false)
    (let [no (try (nuc/parse-expr fonte)
                  (catch clojure.lang.ExceptionInfo e
                    (if (= :sintaxe (:erro (ex-data e)))
                      (do (swap! erros conj (str rotulo ": erro de sintaxe — " (.getMessage e))) sentinela)
                      (throw e))))]
      (if (= no sentinela)
        false
        (let [tp (try (inferir no env)
                      (catch clojure.lang.ExceptionInfo e
                        (if (= :tipo (:erro (ex-data e)))
                          (do (swap! erros conj (str rotulo ": erro de tipo — " (.getMessage e))) sentinela)
                          (throw e))))]
          (cond
            (= tp sentinela) false
            (and esperado (not (contains? esperado tp)))
            (do (swap! erros conj (str rotulo ": esperava " (str/join " ou " (map t/->str esperado)) ", veio " (t/->str tp))) false)
            :else true))))))

(defn verificar-template
  "Recebe um mapa-Envelope (de nucleo/carregar-envelope). Devolve o Resultado."
  [env]
  (let [erros (atom []) avisos (atom [])]
    ;; 1) envelope estrutural
    (when (not= (:contexto env) "compliance")
      (swap! erros conj (str "contexto deve ser 'compliance', veio " (pr-str (:contexto env)))))
    (when-not (#{"federal" "tce_estadual" "regimento_tenant"} (:dominio env))
      (swap! erros conj (str "dominio inválido: " (pr-str (:dominio env)) " (S2: federal|tce_estadual|regimento_tenant)")))
    (when-not (#{"bloqueante" "aviso"} (:severidade env))
      (swap! erros conj (str "severidade inválida: " (pr-str (:severidade env)) " (bloqueante|aviso)")))

    ;; 2) ambiente de tipos: parâmetros + `ente` implícito
    (let [tipos-env (atom {"ente" (t/Registro "Ente")})]
      (doseq [[nome tnome] (:parametros env)]
        (let [tp (cat/resolver-tipo-nome tnome)]
          (if (nil? tp)
            (swap! erros conj (str "parametro " nome ": tipo desconhecido " (pr-str tnome)))
            (swap! tipos-env assoc nome tp))))

      ;; 3) expressões obrigatórias
      (let [ok-aq (checar-expr "aplica_quando" (:aplica-quando env) @tipos-env #{t/BOOLEANO} erros)
            ok-ex (checar-expr "exige" (:exige env) @tipos-env #{t/BOOLEANO} erros)
            expressao-ok0 (and ok-aq ok-ex)
            ;; 4) prazo (opcional): ausente=contínua; bloco=deadline-bound; malformado=erro
            prazo (:prazo env)
            expressao-ok
            (cond
              (nil? prazo)
              (do (swap! avisos conj "sem prazo: obrigação CONTÍNUA — não materializa instância (§22.7.7)") expressao-ok0)
              (contains? prazo :__malformado__)
              (do (swap! erros conj (str "prazo malformado: " (pr-str (:__malformado__ prazo)) " (esperava bloco com janela/a_partir_de)")) expressao-ok0)
              :else
              (let [ok-j (checar-expr "prazo.janela" (get prazo "janela") @tipos-env #{t/DATA t/INSTANTE} erros)
                    ok-a (checar-expr "prazo.a_partir_de" (get prazo "a_partir_de") @tipos-env #{t/DATA t/INSTANTE} erros)]
                (and expressao-ok0 ok-j ok-a)))
            status (if (empty? @erros) "VALIDA" "INVALIDA")]
        {:chave (:template env)
         :status status
         :erros @erros
         :avisos @avisos
         :expressao-ok expressao-ok
         :registry-versao-ref (when (= status "VALIDA") cat/CATALOGO-VERSAO)}))))
