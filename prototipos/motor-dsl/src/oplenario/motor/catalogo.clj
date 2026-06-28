(ns oplenario.motor.catalogo
  "Catálogo do registry (§22.7 Eixo B / B3): tipos, enums, registros, builtins e
  funções de relação que o type-checker do save time lê (Eixo A dec. 2/3).

  Modelo B3: código declara -> catálogo versionado -> type-checker lê -> regra
  carimba a versão (CATALOGO-VERSAO em registry_versao_ref). Conteúdo regulatório
  real (valores de prazo, feriados) é [GAP] de §22.7.5 — aqui só ASSINATURAS/TIPOS."
  (:require [oplenario.motor.tipos :as t]))

(def CATALOGO-VERSAO "registry-v1@2026-06-20")

;; Enums de domínio. Símbolos globalmente únicos no protótipo p/ o literal resolver
;; o domínio sem ambiguidade (o catálogo real qualificaria: TipoAtoLegislativo.resolucao).
(def ENUMS
  {"TipoAtoLegislativo" #{"resolucao" "decreto_legislativo" "ato_mesa"}
   "Materia" #{"emenda_lom" "rejeicao_veto" "cassacao"}})

(defn dominio-do-literal
  "Resolve o domínio enum de um literal (resolucao -> TipoAtoLegislativo)."
  [simbolo]
  (let [achados (for [[dom membros] ENUMS :when (contains? membros simbolo)] dom)]
    (when (= 1 (count achados)) (first achados))))

;; Registros (records) de domínio e campos tipados (acesso ato.tipo, T3/T4).
(def REGISTROS
  {"Ente" {}
   "AtoDespesa" {}
   "AtoLegislativo" {"tipo" (t/enum-t "TipoAtoLegislativo")}
   "Votacao" {"materia" (t/enum-t "Materia")}})

(def ^:private tipos-nomeados
  {"Booleano" t/BOOLEANO "Inteiro" t/INTEIRO "Texto" t/TEXTO "Data" t/DATA
   "Instante" t/INSTANTE "Duracao" t/DURACAO "Racional" t/RACIONAL
   "Competencia" t/COMPETENCIA "Maioria" t/MAIORIA})

(defn resolver-tipo-nome [nome]
  (or (get tipos-nomeados nome)
      (when (contains? REGISTROS nome) (t/Registro nome))))

(defn assinatura [nome params retorno dono categoria]
  {:nome nome :params (vec params) :retorno retorno :dono dono :categoria categoria})

(defn- b [nome params retorno] (assinatura nome params retorno "builtin" "builtin"))
(defn- r [nome params retorno dono] (assinatura nome params retorno dono "relacao"))

;; Builtins (biblioteca da DSL — §22.7.5 §8.1[a]).
(def BUILTINS
  (into {} (map (juxt :nome identity))
        [(b "hoje" [] t/DATA)
         (b "agora" [] t/INSTANTE)
         (b "fim_de" [t/COMPETENCIA] t/DATA)
         (b "proximo_dia_util" [t/DATA] t/DATA)
         (b "soma_dias_uteis" [t/DATA t/INTEIRO] t/DATA)
         (b "arredonda_cima" [t/RACIONAL] t/INTEIRO)   ; aceita Inteiro via coerção numérica
         (b "fracao" [t/INTEIRO t/INTEIRO] t/RACIONAL)
         (b "prazo_vigente" [t/TEXTO t/TEXTO t/COMPETENCIA] t/DATA)
         (b "dias" [t/INTEIRO] t/DURACAO)]))            ; construtor de Duracao (n dias) — base da aritmética temporal
;; parametro_tenant é tratado à parte (retorno depende da CHAVE — binding B2 tipado).

(def PARAMETROS-TENANT
  {"prazo_publicacao_ato_dias" t/INTEIRO})

;; Funções de relação (expostas pelo contexto dono — §22.7.5 §8.1[b]).
(def FUNCOES-RELACAO
  (into {} (map (juxt :nome identity))
        [(r "populacao" [(t/Registro "Ente")] t/INTEIRO "Cadastros/Ente")
         (r "membros_da_casa" [(t/Registro "Ente")] t/INTEIRO "Cadastros/Ente")
         (r "remessa_enviada" [(t/Registro "Ente") t/TEXTO t/COMPETENCIA] t/BOOLEANO "Remessa-tracking")
         (r "publicada_no_portal" [(t/Registro "AtoDespesa")] t/BOOLEANO "Transparencia")
         (r "data_registro_contabil" [(t/Registro "AtoDespesa")] t/DATA "Execucao/Transparencia")
         (r "publicado" [(t/Registro "AtoLegislativo")] t/BOOLEANO "Atos Legislativos")
         (r "data_promulgacao" [(t/Registro "AtoLegislativo")] t/DATA "Atos Legislativos")
         (r "votos_favoraveis" [(t/Registro "Votacao")] t/INTEIRO "Plenario")]))

(defn buscar-assinatura [nome]
  (or (get BUILTINS nome) (get FUNCOES-RELACAO nome)))
