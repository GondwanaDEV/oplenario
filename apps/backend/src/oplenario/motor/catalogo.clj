(ns oplenario.motor.catalogo
  "Catálogo do registry (§22.7 Eixo B / B3): tipos, enums, registros, builtins e
  funções de relação que o type-checker do save time lê (Eixo A dec. 2/3).

  Modelo B3: código declara -> catálogo versionado -> type-checker lê -> regra
  carimba a versão (CATALOGO-VERSAO em registry_versao_ref). Conteúdo regulatório
  real (valores de prazo, feriados) é [GAP] de §22.7.5 — aqui só ASSINATURAS/TIPOS."
  (:require [oplenario.motor.tipos :as t]))

(def CATALOGO-VERSAO "registry-v1@2026-07-11")  ; C3: +esta_presente_em(SessaoId,VereadorId,Instante)->Booleano

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
;; "Ente" SAIU (§4-bis/C2): a Casa é 1:1 com o tenant — implícita na `tx` (RLS isola); as relações
;; reais não tomam `ente` (populacao [tx], membros_da_casa [tx data]). Não é parâmetro/arg do DSL.
(def REGISTROS
  {"AtoDespesa" {}
   "AtoLegislativo" {"tipo" (t/enum-t "TipoAtoLegislativo")}
   "Votacao" {"materia" (t/enum-t "Materia")}})

(def ^:private tipos-nomeados
  {"Booleano" t/BOOLEANO "Inteiro" t/INTEIRO "Texto" t/TEXTO "Data" t/DATA
   "Instante" t/INSTANTE "Duracao" t/DURACAO "Racional" t/RACIONAL
   "Competencia" t/COMPETENCIA "Maioria" t/MAIORIA
   "IdentidadeId" t/IDENTIDADE-ID "ComissaoId" t/COMISSAO-ID "SessaoId" t/SESSAO-ID
   "VereadorId" t/VEREADOR-ID})

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

;; Funções de relação (expostas pelo contexto dono — §22.7.5 §8.1[b]). As assinaturas ESPELHAM os args
;; de domínio reais das fns de F1 (`cadastros/relacoes`, `identidade/relacoes`): a aridade aqui = aridade
;; da fn menos a `tx` injetada (§4-bis/M7). O assert de costura do boot (RegistroFatos) casa
;; fn-registrada ⋈ assinatura :relacao — divergência de aridade/nome NÃO sobe (fail-closed, §1).
;;
;; Dois grupos: (a) já têm fn registrada em F1 (cadastros+identidade); (b) "assinatura sem fn" — fato de
;; módulo futuro (Plenário/F4, compliance-remessa/F5): tipa no save-time, só AVALIA quando o módulo dono
;; registrar a fn; até lá, fato-sem-fn em runtime = fail-closed (C1).
(def FUNCOES-RELACAO
  (into {} (map (juxt :nome identity))
        [;; (a) relações reais de F1 — cadastros/relacoes (a Casa = a tx; sem arg `ente`)
         (r "populacao" [] t/INTEIRO "Cadastros")
         (r "membros_da_casa" [t/DATA] t/INTEIRO "Cadastros")
         (r "tribunal_competente" [] t/TEXTO "Cadastros")
         (r "tem_mandato_vigente" [t/IDENTIDADE-ID t/DATA] t/BOOLEANO "Cadastros")
         (r "é_membro_de_comissao" [t/IDENTIDADE-ID t/COMISSAO-ID t/DATA] t/BOOLEANO "Cadastros")
         (r "é_presidente_de_comissao" [t/IDENTIDADE-ID t/COMISSAO-ID t/DATA] t/BOOLEANO "Cadastros")
         (r "é_presidente_da_mesa" [t/IDENTIDADE-ID t/DATA] t/BOOLEANO "Cadastros")
         (r "é_secretario_da_mesa" [t/IDENTIDADE-ID t/DATA] t/BOOLEANO "Cadastros")
         (r "quem_exerce_presidencia" [t/DATA] t/IDENTIDADE-ID "Cadastros")
         ;; identidade/relacoes — pura (transversal); ignora a tx mas casa a forma (fn tx & args)
         (r "é_o_próprio" [t/IDENTIDADE-ID t/IDENTIDADE-ID] t/BOOLEANO "Identidade")
         ;; sessoes/relacoes (F4.3b §22.6 eixo C) — agregadores de quorum expostos a DSL do motor de votacao.
         ;; A presenca corrente e' DERIVADA do ultimo evento por vereador ate o instante (sem snapshot).
         (r "presentes_plenario" [t/SESSAO-ID t/INSTANTE] t/INTEIRO "Sessoes")
         (r "presentes_remoto"   [t/SESSAO-ID t/INSTANTE] t/INTEIRO "Sessoes")
         (r "esta_presente_em" [t/SESSAO-ID t/VEREADOR-ID t/INSTANTE] t/BOOLEANO "Sessoes")
         ;; (b) assinaturas sem fn (módulo futuro) — remessa_enviada perde `ente` (§4-bis)
         (r "remessa_enviada" [t/TEXTO t/COMPETENCIA] t/BOOLEANO "Compliance-remessa")
         (r "publicada_no_portal" [(t/Registro "AtoDespesa")] t/BOOLEANO "Transparencia")
         (r "data_registro_contabil" [(t/Registro "AtoDespesa")] t/DATA "Execucao/Transparencia")
         (r "publicado" [(t/Registro "AtoLegislativo")] t/BOOLEANO "Atos Legislativos")
         (r "data_promulgacao" [(t/Registro "AtoLegislativo")] t/DATA "Atos Legislativos")
         (r "votos_favoraveis" [(t/Registro "Votacao")] t/INTEIRO "Plenario")]))

(defn buscar-assinatura [nome]
  (or (get BUILTINS nome) (get FUNCOES-RELACAO nome)))
