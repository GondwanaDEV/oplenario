(ns oplenario.motor.components.registro-fatos
  "RegistroFatos — a 2ª face do registry (§22.7.5 / docs/12 §1): o mapa {nome → fn} das funções de
  relação que o HOST injeta no boot. O motor é BIBLIOTECA (§22.10: kernel/motor nunca importam módulo);
  é o host (sistema.clj — raiz de composição) que importa `cadastros/relacoes`, `identidade/relacoes`…
  e os entrega aqui. O avaliador (`runtime/a-chamada`) chama por NOME via `resolver-para` — nunca
  importa o módulo. A resolução é in-process SÍNCRONA (caminho quente de avaliação/autorização, §3).

  ASSERT DE COSTURA (a rede contra o risco CRÍTICO, §1): no `start`, toda fn registrada DEVE ter
  assinatura `:categoria \"relacao\"` no catálogo com aridade de domínio compatível. Uni-direcional
  (fns ⊆ assinaturas — assinatura-sem-fn é legal: fato de módulo futuro). Divergência = NÃO sobe
  (fail-closed: melhor não-bootar que avaliar errado)."
  (:require [com.stuartsierra.component :as component]
            [oplenario.motor.catalogo :as cat])
  (:import (java.lang.reflect Method)))

(set! *warn-on-reflection* true)

(defn- aridades-invoke
  "Aridades FIXAS de `invoke` da fn (reflexão). Ex.: (fn [tx data]) → #{2}; multi-aridade → o conjunto.
  Relações são de aridade fixa por contrato (docs/12 §4 — a notação `(fn tx & args)` é 'tx seguido dos
  args de domínio', não variádico). Uma fn VARIÁDICA (RestFn) declara só `doInvoke`/herda os `invoke` →
  devolveria #{}; quem chama trata como erro explícito (não 'aridade vazia' opaca)."
  [f]
  (into #{}
        (keep (fn [^Method m] (when (= "invoke" (.getName m)) (.getParameterCount m))))
        (.getDeclaredMethods (class f))))

(defn verificar-costura
  "Uni-direcional (§1): cada fn registrada tem assinatura `:relacao` no catálogo, com aridade de domínio
  compatível. A forma é `(fn tx arg…)`: a aridade-fn relevante = (count params) + 1 (a `tx` injetada).
  Aceita-se a fn cuja aridade-invoke contém (inc aridade-de-domínio). Devolve {:ok bool :erros [str]}."
  [fns]
  (let [erros (reduce-kv
                (fn [acc nome f]
                  (let [sig (cat/buscar-assinatura nome)
                        aridades (aridades-invoke f)
                        esperada (inc (count (:params sig)))]
                    (cond
                      (nil? sig)
                      (conj acc (str "fato '" nome "' registrado sem assinatura no catálogo"))
                      (not= "relacao" (:categoria sig))
                      (conj acc (str "fato '" nome "' não é :relacao no catálogo (é " (pr-str (:categoria sig)) ")"))
                      (instance? clojure.lang.RestFn f)
                      (conj acc (str "fato '" nome "' é variádico; relações devem ter aridade fixa (fn [tx arg…]) — docs/12 §4"))
                      (not (contains? aridades esperada))
                      (conj acc (str "fato '" nome "': assinatura espera " (count (:params sig))
                                     " arg(s) de domínio (aridade-fn " esperada
                                     " com tx), mas a fn tem aridades " aridades))
                      :else acc)))
                [] fns)]
    {:ok (empty? erros) :erros (vec erros)}))

(defrecord RegistroFatos [fns]
  component/Lifecycle
  (start [this]
    (let [r (verificar-costura fns)]
      (when-not (:ok r)
        (throw (ex-info "costura catálogo⋈registry quebrada (fail-closed, docs/12 §1)" {:erros (:erros r)})))
      this))
  (stop [this] this))

(defn registro-fatos
  "Cria o Component a partir do mapa mesclado {nome → fn} das relações dos módulos (injetado pelo host)."
  [fns]
  (->RegistroFatos fns))

(defn resolver-para
  "Resolvedor de PRODUÇÃO (§4): fecha sobre o RegistroFatos + a `tx` do tenant. `runtime/a-chamada`
  chama `(resolver nome args)`; aqui `(apply f tx args)` — a `tx` (RLS/GUC do tenant) é o 1º arg.
  Fato sem fn neste deploy = fail-closed (C1: lança; nunca avalia errado)."
  [registro tx]
  (let [fns (:fns registro)]                              ; capturado uma vez (registro imutável pós-start)
    (fn [nome args]
      (if-let [f (get fns nome)]
        (apply f tx args)
        (throw (ex-info (str "fato sem fn registrada: " (pr-str nome)) {:erro :runtime :nome nome}))))))
