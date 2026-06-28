(ns oplenario.motor.models.catalogo
  "Representacao INTERNA (dominio) do CATALOGO do motor — Malli (§22.10 models/). As 5 tabelas estaticas
  do schema `motor` (§22.7.6 Eixo B): Template (B1), RegraTenant (B2), VersaoCatalogo (B3), Prazo +
  Feriado (B4). Os enums vivem AQUI: o DDL 0006 declara dominio/severidade/estado_versao como texto
  ('enum em codigo', sem CHECK) — o model e' a FONTE do vocabulario. Datas LocalDate, carimbos Instant
  (convencao kernel/tempo + db-tipos). NAO confundir com `motor/catalogo.clj` (assinaturas de tipo da DSL).
  Um arquivo enquanto cabe; vira pasta-por-agregado quando crescer (§22.10 'arquivo vs pasta')."
  (:import (java.time LocalDate)))

;; vocabularios controlados (o DDL 0006 os deixa como texto livre — aqui e' onde o enum existe de fato)
(def dominios          #{"federal" "tribunal_de_contas" "regimento_tenant"})
(def severidades       #{"bloqueante" "aviso"})
(def estados-versao    #{"vigente" "superada" "arquivada"})
(def jurisdicoes-feriado #{"nacional" "municipal"})

;; enum ordenado: a validacao independe da ordem, mas `sort` torna explain/= deterministico entre JVMs.
(defn- enum-de [s] (into [:enum] (sort s)))

(def Data
  "Data civil (java.time.LocalDate) — coluna `date` volta assim via kernel/db-tipos (sem cast)."
  [:fn {:error/message "deve ser java.time.LocalDate"} #(instance? LocalDate %)])
(def ^:private LocalDate? Data)

;; --- B1: template_compliance — a DEFINICAO da regra (o que o runtime avalia) ---
;; Shape do read canonico `vigentes-por-dominio` (a resolucao por escopo); `por-chave-versao` acresce
;; :estado-versao (por isso opcional). forma-compilada/assinatura sao jsonb com chaves KEYWORD (jsonb->kw).
(def Template
  [:map {:closed true}
   [:id :uuid]
   [:chave-template :string]
   [:versao :int]
   [:dominio (enum-de dominios)]
   [:chave-dominio {:optional true} [:maybe :string]]      ; NULL p/ federal; cod. TCE/UF p/ tribunal_de_contas
   [:severidade (enum-de severidades)]
   [:forma-compilada [:map-of :keyword :any]]              ; AST normalizado/tipado pos type-check
   [:assinatura-parametros [:map-of :keyword :any]]        ; assinatura dos parametros do template
   [:registry-versao-ref :string]
   [:estado-versao {:optional true} (enum-de estados-versao)]])

;; --- B2: compliance_regra_tenant — o BINDING por tenant (UNICA tabela TENANT do schema motor) ---
;; parametros-tenant com chaves STRING (casam com a chave literal da DSL — NAO keyword).
(def RegraTenant
  [:map {:closed true}
   [:id :uuid]
   [:ente-id :uuid]
   [:template-chave :string]
   [:versao-fixada-id {:optional true} [:maybe :uuid]]
   [:ativa :boolean]
   [:motivo-desativacao {:optional true} [:maybe :string]] ; obrigatorio no DDL quando ativa=false (CHECK)
   [:parametros-tenant [:map-of :string :any]]])

;; --- B3: registry_catalogo_versao — log de versao do catalogo (proveniencia/re-validacao no deploy) ---
(def VersaoCatalogo
  [:map {:closed true}
   [:id :uuid]
   [:versao :string]
   [:hash {:optional true} [:maybe :string]]
   [:descricao {:optional true} [:maybe :string]]])

;; --- B4: prazo_dominio_vigente — REFERENCIA regulatoria de prazo (entidade completa, shape de insert) ---
(def Prazo
  [:map {:closed true}
   [:id :uuid]
   [:dominio (enum-de #{"federal" "tribunal_de_contas"})]  ; prazo nao tem regimento_tenant
   [:chave-dominio {:optional true} [:maybe :string]]
   [:tipo-prazo :string]                                   ; ex.: 'SIM_mensal', 'PCS_anual'
   [:chave-periodo :string]
   [:data-limite LocalDate?]
   [:fonte :string]
   [:vigente :boolean]])

;; projecao lida pelo builtin prazo_vigente(dominio, tipo, competencia) — o que o resolvedor consome
(def PrazoVigente
  [:map {:closed true}
   [:data-limite LocalDate?]
   [:fonte :string]])

;; --- B4: calendario_feriado — feriado nacional|municipal (lido por proximo_dia_util/soma_dias_uteis) ---
(def Feriado
  [:map {:closed true}
   [:id :uuid]
   [:jurisdicao (enum-de jurisdicoes-feriado)]
   [:municipio-id {:optional true} [:maybe :uuid]]         ; nil p/ nacional; uuid p/ municipal
   [:data LocalDate?]
   [:descricao :string]])
