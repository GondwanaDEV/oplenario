(ns oplenario.motor.tipos
  "Sistema de tipos do núcleo de expressão A2 (§22.7 Eixo A dec. 4/5).

  Port Clojure: um Tipo é um MAPA de dados (Clojure é homoicônico — 'regra é dado',
  Invariante 4). Igualdade de tipo = igualdade de valor de mapa, de graça.

  Primitivos + compostos derivados de carga real no Eixo C (§22.7.5):
    Competencia, Maioria, Conjunto<T>, Enum<dominio>, Registro, Racional (exato).")

(declare ->str)

(defn ->str
  "Render de tipo para mensagens de erro (Conjunto<T>, Enum<dom>, ou o nome)."
  [t]
  (case (:kind t)
    :conjunto (str "Conjunto<" (->str (:elem t)) ">")
    :enum (str "Enum<" (:nome t) ">")
    (let [n (:nome t)] (if (seq n) n (name (:kind t))))))

;; ---- primitivos ----
(def BOOLEANO {:kind :primitivo :nome "Booleano"})
(def INTEIRO  {:kind :primitivo :nome "Inteiro"})
(def TEXTO    {:kind :primitivo :nome "Texto"})
(def DATA     {:kind :primitivo :nome "Data"})
(def INSTANTE {:kind :primitivo :nome "Instante"})
(def DURACAO  {:kind :primitivo :nome "Duracao"})
(def RACIONAL {:kind :primitivo :nome "Racional"})   ; exato (ratio Clojure), nunca float — armadilha do quórum (T4)

;; ---- compostos ----
(def COMPETENCIA {:kind :competencia :nome "Competencia"})
(def MAIORIA     {:kind :maioria :nome "Maioria"})

(defn Conjunto [elem] {:kind :conjunto :nome "Conjunto" :elem elem})
(defn enum-t [nome] {:kind :enum :nome nome})   ; 'Enum' colidiria com java.lang.Enum (auto-importado)
(defn Registro [nome] {:kind :registro :nome nome})

;; ---- escalares OPACOS (id de domínio: uuid) ----
;; Comparáveis SÓ por ==/!= (igualdade de referência de entidade), nunca aritmética/ordem — o
;; type-checker já garante isso: opaco não é numérico nem temporal, então > + * 'in' erram; só o
;; ramo ==/!= (que exige tipos iguais) os aceita. F2: args de identidade/comissão nas relações reais
;; (tem_mandato_vigente(IdentidadeId,…), é_o_próprio(IdentidadeId,IdentidadeId), §22.7.5/§4-bis).
(def IDENTIDADE-ID {:kind :opaco :nome "IdentidadeId"})
(def COMISSAO-ID   {:kind :opaco :nome "ComissaoId"})
(def SESSAO-ID     {:kind :opaco :nome "SessaoId"})    ; F4.3b: agregadores de quorum (presentes_*(SessaoId,Instante))

;; ---- predicados de compatibilidade usados pelo type-checker ----
(def ^:private numericos #{INTEIRO RACIONAL})
(def ^:private temporais #{DATA INSTANTE})

(defn numerico? [t] (contains? numericos t))
(defn temporal? [t] (contains? temporais t))

(defn compativel-argumento?
  "Igualdade estrutural OU coerção numérica (Inteiro<->Racional). Coerção restrita a
  numérico<->numérico: passar Inteiro onde se espera Texto continua erro."
  [formal real]
  (or (= formal real)
      (and (numerico? formal) (numerico? real))))
