(ns oplenario.admin-sistema.logic
  "Nucleo PURO do ciclo de vida da Casa (ADR-0018, fatia 1) — zero I/O. Quem suspende e por que (Eixo 1), o que a
  suspensao faz com a sessao ao vivo (Eixo 2) e quando o incidente sem 2a aprovacao volta atras (Eixo 1b)."
  (:import (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(def motivos-de-suspensao
  "Eixo 1a: a lista FECHADA. \"Outro\" nao entra — forca a nomear o motivo."
  #{"inadimplencia" "pedido_da_casa" "ordem_judicial" "incidente_de_seguranca"})

(def origens-do-encerramento
  "Eixo 4.1: o pedido de encerramento vem da Casa (oficio) ou e' nosso (fim de contrato)."
  #{"pedido_da_casa" "fim_de_contrato"})

(def motivo-do-encerramento
  "A Casa com o encerramento em curso fica `suspenso` com este motivo (Eixo 4.1)."
  "encerramento_em_curso")

(def prazo-da-segunda-aprovacao
  "Eixo 1b: o incidente de seguranca suspende com um operador so' e exige a 2a aprovacao em ate' 24 h."
  (Duration/ofHours 24))

(defn corta-na-hora?
  "Eixo 2 (sessao ao vivo): `ordem_judicial` e `incidente_de_seguranca` cortam na hora; o resto espera o
  encerramento da sessao em curso."
  [motivo]
  (contains? #{"ordem_judicial" "incidente_de_seguranca"} motivo))

(defn um-operador-basta?
  "So' o incidente suspende com um operador (e ainda assim pede a 2a aprovacao em 24 h)."
  [acao motivo]
  (and (= "suspender" acao) (= "incidente_de_seguranca" motivo)))

(defn confirmar-ate
  "O prazo da 2a aprovacao de um pedido, ou nil (so' o incidente tem)."
  ^Instant [acao motivo ^Instant agora]
  (when (um-operador-basta? acao motivo) (.plus agora ^Duration prazo-da-segunda-aprovacao)))

(defn motivo-da-casa
  "O motivo que a Casa passa a ter quando o pedido se efetiva."
  [{:keys [acao motivo]}]
  (if (= "encerrar" acao) motivo-do-encerramento motivo))

(defn efeito-da-aprovacao
  "O que acontece quando o pedido fica aprovado: `:imediato` (a Casa fica suspensa agora), `:agendado` (espera a
  sessao em curso encerrar) ou `:ja-efetivado` (o incidente ja' tinha suspendido; a aprovacao so' confirma)."
  [{:keys [acao motivo efetivado-em]} sessao-em-curso?]
  (cond
    efetivado-em :ja-efetivado
    (and (= "suspender" acao) (corta-na-hora? motivo)) :imediato
    sessao-em-curso? :agendado
    :else :imediato))

(defn incidente-vencido?
  "O pedido de incidente ainda aberto cujo prazo da 2a aprovacao passou (a Casa volta a ativa)."
  [{:keys [estado confirmar-ate]} ^Instant agora]
  (boolean (and (= "aguardando" estado) confirmar-ate (not (.isBefore agora ^Instant confirmar-ate)))))

;; ---------------------------------------------------------------------------------------------
;; ADR-0018 (fatia 2): ENCERRAR. Exportacao entregue e confirmada -> janela de guarda de 90 dias -> apagamento aprovado
;; por dois operadores -> `encerrado`, para sempre (Eixos 4 e 5).
;; ---------------------------------------------------------------------------------------------

(def janela-de-guarda
  "Eixo 4.3: 90 dias de guarda desde a confirmacao de recebimento antes de apagar (recomendado e aceito)."
  (Duration/ofDays 90))

(def prazo-da-geracao
  "Uma geracao que nao terminou em 6 h morreu com o processo (deploy, queda): a proxima pedida a fecha como falha, em
  vez de a Casa ficar presa a uma linha `gerando` para sempre (o indice parcial so' deixa uma por Casa)."
  (Duration/ofHours 6))

(def transicoes
  "O ciclo de vida da Casa (12.1). `encerrado` nao tem saida (Eixo 5): a Casa que volta e' uma Casa nova."
  {"provisionar" #{"ativo"}
   "ativo" #{"suspenso"}
   "suspenso" #{"ativo" "encerrado"}
   "encerrado" #{}})

(defn transicao-permitida?
  [de para]
  (contains? (get transicoes de #{}) para))

(defn em-encerramento?
  "A Casa com o encerramento em curso (suspensa, motivo `encerramento_em_curso`)."
  [{:keys [estado motivo-restricao]}]
  (and (= "suspenso" estado) (= motivo-do-encerramento motivo-restricao)))

(defn apagamento-possivel-em
  "O instante a partir do qual o apagamento pode ser pedido: a confirmacao + 90 dias."
  ^Instant [^Instant confirmada-em]
  (when confirmada-em (.plus confirmada-em ^Duration janela-de-guarda)))

(defn guarda-cumprida?
  [^Instant confirmada-em ^Instant agora]
  (boolean (when confirmada-em (not (.isBefore agora (apagamento-possivel-em confirmada-em))))))

(defn confirmacao-do-encerramento
  "Das exportacoes da Casa, a que vale para o encerramento: a confirmada MAIS RECENTE desde que o encerramento entrou
  (`desde`). Uma confirmacao de portabilidade de antes do encerramento nao abre a guarda — senao o apagamento poderia
  vir no dia seguinte ao pedido de encerramento. nil = ainda nao ha'."
  [exportacoes ^Instant desde]
  (->> exportacoes
       (filter #(and (:confirmada-em %) desde (not (.isBefore ^Instant (:confirmada-em %) desde))))
       ;; empate na confirmacao (o mesmo instante): vale a exportacao gerada por ultimo
       (sort-by (juxt :confirmada-em :solicitada-em))
       last))

(defn geracao-abandonada?
  "A linha `gerando` mais velha que o prazo (o processo que a gerava morreu)."
  [{:keys [estado solicitada-em]} ^Instant agora]
  (boolean (and (= "gerando" estado) solicitada-em
                (.isBefore ^Instant (.plus ^Instant solicitada-em ^Duration prazo-da-geracao) agora))))

(defn resumo-do-manifesto
  "O que o OPERADOR ve do manifesto: so' o primeiro nivel, com numeros e textos curtos como estao e cada colecao
  reduzida a sua contagem. A Operacao e' operadora (LGPD): ve que entregou e quanto, nunca o que ha' dentro."
  [manifesto]
  (when (map? manifesto)
    (into (sorted-map)
          (keep (fn [[k v]]
                  (cond
                    (number? v) [k v]
                    (boolean? v) [k v]
                    (and (string? v) (<= (count v) 80)) [k v]
                    (map? v) [k {:itens (count v)}]
                    (coll? v) [k {:itens (count v)}]
                    :else nil)))
          manifesto)))

(defn pode-pedir-apagamento?
  "Eixo 4.5 (salvaguarda): so' com o encerramento em curso, a exportacao confirmada e a guarda cumprida."
  [casa confirmacao ^Instant agora]
  (boolean (and (em-encerramento? casa) confirmacao (guarda-cumprida? (:confirmada-em confirmacao) agora))))

(defn- campo-de
  "O campo `k` do mapa, com chave keyword OU string (o resumo volta do jsonb com chaves string). `false` e' valor."
  [m k]
  (when (map? m) (if (contains? m k) (get m k) (get m (name k)))))

(defn somar-resumos
  "PURA: o resumo de uma retomada somado ao das execucoes anteriores do mesmo pedido. Contagens somam (tabelas por nome,
  linhas, objetos, exportacoes apagadas); o estado dos passos externos (realm, IA, pendencias, completo?) e' o da ULTIMA
  execucao; a exportacao entregue e' a da primeira que a trouxe."
  [anterior atual]
  (if-not anterior
    atual
    (let [tabelas (fn [r] (into {} (map (fn [[k v]] [(name k) v])) (campo-de r :tabelas)))
          n (fn [r k] (or (campo-de r k) 0))]
      (assoc atual
             :tabelas (merge-with + (tabelas anterior) (tabelas atual))
             :linhas-total (+ (n anterior :linhas-total) (n atual :linhas-total))
             :objetos (+ (n anterior :objetos) (n atual :objetos))
             :exportacoes-apagadas (+ (n anterior :exportacoes-apagadas) (n atual :exportacoes-apagadas))
             :objetos-fora-da-convencao (vec (distinct (concat (campo-de anterior :objetos-fora-da-convencao)
                                                               (campo-de atual :objetos-fora-da-convencao))))
             :exportacao (or (campo-de anterior :exportacao) (campo-de atual :exportacao))))))
