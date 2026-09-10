(ns oplenario.legislativo.wire.out.proposicao
  "Representacao EXTERNA de SAIDA da leitura de proposicoes (§22.10 wire/out, ADR-0001, Onda B Slice 1) — o
  contrato de GET /legislativo/proposicoes, do qual o Eixo 8 gera os tipos TS. `estado` fica :string (nao
  enum fechado): a maquina fina de tramitacao e' TEMPLATE-DRIVEN por camara (F3.3) — mesmo racional de
  paineis/wire/out/tramitacao e de tramitacao-vista.ts no FE."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(def ProposicaoResumoOut
  "Uma linha da lista (nao a proposicao inteira — sem atributos_especificos/texto_vigente_versao_id/
  lock_version/created_by/updated_by)."
  [:map {:closed true}
   [:id :string]
   [:tipo (km/enum-de logic/tipos)]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:estado :string]
   [:atualizado-em :string]])

(def ListaProposicoesOut
  "O envelope da resposta de GET /legislativo/proposicoes — a pagina de itens (ProposicaoResumoOut) mais os
  metadados de paginacao (total do MESMO filtro, pagina e tamanho-pagina efetivos)."
  [:map {:closed true}
   [:itens [:sequential ProposicaoResumoOut]]
   [:total :int]
   [:pagina :int]
   [:tamanho-pagina :int]])

(def ProposicaoDetalheOut
  "GET /legislativo/proposicoes/:id (Onda B Slice 2) — a proposicao inteira (nao o resumo estreito da
  lista) + o texto vigente inline, p/ o form de edicao pre-encher (e a resposta do POST de criacao, com
  o numero oficial + lock-version iniciais)."
  [:map {:closed true}
   [:id :string]
   [:tipo (km/enum-de logic/tipos)]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-id {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:objeto-indicacao {:optional true} [:maybe :string]]
   [:destinatario-id {:optional true} [:maybe :string]]
   [:destinatario-texto {:optional true} [:maybe :string]]
   [:tipo-requerimento {:optional true} [:maybe :string]]
   [:categoria-mocao {:optional true} [:maybe :string]]
   [:estado :string]
   ;; T3-A/Fatia 2 (guarda-autografo-votacao): o fato "a Casa APROVOU" que o FE gateia botao (autografo,
   ;; pos-aprovacao) — vem do ATO (votacao encerrada 'aprovada'), NUNCA do :estado acima (texto livre,
   ;; template por camara — ver db/votacao.clj/aprovada-em-votacao?). :boolean, nao {:optional true}: toda
   ;; leitura de detalhe computa o fato, entao a ausencia do campo e' sempre bug de servidor, nunca "nao sei".
   [:aprovada :boolean]
   [:lock-version :int]
   [:atualizado-em :string]
   [:texto {:optional true} [:maybe :string]]])
