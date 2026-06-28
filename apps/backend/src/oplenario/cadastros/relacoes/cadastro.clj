(ns oplenario.cadastros.relacoes.cadastro
  "Funcoes de RELACAO que o cadastros e' dono (§22.5 eixo B / §22.7.5). O motor de DSL (autorizacao,
  compliance, plenario) as alcanca POR NOME via o registry/injecao da F2 — nunca por import (§22.10).

  Disciplina §22.5.3 disc.5: TODA funcao de relacao aceita `data` (consulta historica) — 'quem era
  presidente da Mesa em DD/MM/AAAA?' tem resposta consultando a vigencia naquela data, nao a de hoje.
  O default now()/hoje e' aplicado pela camada que chama (motor/controller); aqui `data` e' explicito
  (puro/testavel). Recebem a `tx` do tenant (RLS isola; ente=1:1 -> a Casa corrente). Tudo intra-schema
  cadastros (sem JOIN cross-schema, §22.10); a resolucao 'usuario' e' por identidade_id (CPF, disc.1).
  HoneySQL; os JOINs casam tambem ente_id (defesa em profundidade, redundante com a FORCE RLS)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]))

(set! *warn-on-reflection* true)

(defn- existe?
  "Roda `query` (mapa HoneySQL com :from/:join/:where) como SELECT 1 ... LIMIT 1 e devolve boolean."
  [tx query]
  (boolean (jdbc/execute-one! tx (sql/format (assoc query :select [1] :limit 1)))))

(defn tem-mandato-vigente?
  "A identidade tem mandato em estado 'vigente' cuja vigencia cobre `data`?"
  [tx identidade-id data]
  (existe? tx
    {:from [[:cadastros.mandato :m]]
     :join [[:cadastros.vereador :v] [:and [:= :v.id :m.vereador_id] [:= :v.ente_id :m.ente_id]]]
     :where [:and [:= :v.identidade_id identidade-id] [:= :m.estado "vigente"]
             [:<= :m.vigencia_inicio data] [:or [:is :m.vigencia_fim nil] [:>= :m.vigencia_fim data]]]}))

(defn membro-de-comissao?
  [tx identidade-id comissao-id data]
  (existe? tx
    {:from [[:cadastros.comissao_membro :cm]]
     :join [[:cadastros.vereador :v] [:and [:= :v.id :cm.vereador_id] [:= :v.ente_id :cm.ente_id]]]
     :where [:and [:= :v.identidade_id identidade-id] [:= :cm.comissao_id comissao-id]
             [:<= :cm.vigencia_inicio data] [:or [:is :cm.vigencia_fim nil] [:>= :cm.vigencia_fim data]]]}))

(defn- tem-cargo-na-comissao? [tx identidade-id comissao-id cargos data]
  (existe? tx
    {:from [[:cadastros.comissao_cargo :cc]]
     :join [[:cadastros.vereador :v] [:and [:= :v.id :cc.vereador_id] [:= :v.ente_id :cc.ente_id]]]
     :where [:and [:= :v.identidade_id identidade-id] [:= :cc.comissao_id comissao-id]
             [:= :cc.cargo [:any [:lift (into-array String cargos)]]]
             [:<= :cc.vigencia_inicio data] [:or [:is :cc.vigencia_fim nil] [:>= :cc.vigencia_fim data]]]}))

(defn presidente-de-comissao?
  [tx identidade-id comissao-id data]
  (tem-cargo-na-comissao? tx identidade-id comissao-id ["presidente"] data))

(defn- mesa-vigente-id [tx data]
  (:comissao/id
   (jdbc/execute-one! tx
     (sql/format {:select [:id] :from [:cadastros.comissao]
                  :where [:and [:= :tipo "mesa"] [:<= :vigencia_inicio data]
                          [:or [:is :vigencia_fim nil] [:>= :vigencia_fim data]]]
                  :order-by [[:vigencia_inicio :desc]] :limit 1}))))

(defn presidente-da-mesa?
  [tx identidade-id data]
  (if-let [mesa (mesa-vigente-id tx data)]
    (tem-cargo-na-comissao? tx identidade-id mesa ["presidente"] data)
    false))

(defn secretario-da-mesa?
  [tx identidade-id data]
  (if-let [mesa (mesa-vigente-id tx data)]
    (tem-cargo-na-comissao? tx identidade-id mesa ["secretario" "1_secretario" "2_secretario"] data)
    false))

(defn quem-exerce-presidencia
  "Identidade (CPF) do presidente da Mesa vigente em `data` (camada fora-de-sessao; o override por
  sessao via PresidenciaPassada e' F4). nil se nao ha Mesa/presidente vigente."
  [tx data]
  (when-let [mesa (mesa-vigente-id tx data)]
    (:vereador/identidade_id
     (jdbc/execute-one! tx
       (sql/format {:select [:v.identidade_id] :from [[:cadastros.comissao_cargo :cc]]
                    :join [[:cadastros.vereador :v] [:and [:= :v.id :cc.vereador_id] [:= :v.ente_id :cc.ente_id]]]
                    :where [:and [:= :cc.comissao_id mesa] [:= :cc.cargo "presidente"]
                            [:<= :cc.vigencia_inicio data] [:or [:is :cc.vigencia_fim nil] [:>= :cc.vigencia_fim data]]]
                    :order-by [[:cc.vigencia_inicio :desc] [:cc.id]] :limit 1})))))

(defn populacao
  "Populacao do municipio do ente corrente (alimenta regras de porte do TCE — §22.7.5). Fail-LOUD: se o
  IBGE ainda nao foi semeado (populacao NULL), LANCA — senao 'populacao() > X' viraria false em silencio
  e a regra de porte seria marcada 'inaplicavel', pulando a auditoria de compliance sem ninguem ver."
  [tx]
  (or (:municipios/populacao
       (jdbc/execute-one! tx
         (sql/format {:select [:m.populacao] :from [[:cadastros.ente :e]]
                      :join [[:cadastros.municipios :m] [:= :m.codigo_ibge :e.municipio_ibge]]})))
      (throw (ex-info "populacao: dado ausente (cadastros.municipios.populacao NULL — seed IBGE pendente)"
                      {:erro :dado-ausente :fato "populacao"}))))

(defn membros-da-casa
  "Nº de vereadores com mandato vigente em `data` (base de quorum/maioria — §22.7.5)."
  [tx data]
  (:c (jdbc/execute-one! tx
        (sql/format {:select [[[:count [:distinct :m.vereador_id]] :c]] :from [[:cadastros.mandato :m]]
                     :where [:and [:= :m.estado "vigente"] [:<= :m.vigencia_inicio data]
                             [:or [:is :m.vigencia_fim nil] [:>= :m.vigencia_fim data]]]}))))

(defn tribunal-competente
  "Codigo do Tribunal de Contas competente do ente (E1, §22.7.9): override por municipio > default da UF.
  Reconcilia o 'UF JOIN' do Eixo B com a §22.10 — e' funcao de relacao intra-schema, nao JOIN cross-schema.
  Retorna nil se nao ha jurisdicao cadastrada p/ a UF/municipio -> o CALLER (motor) trata como ERRO
  (fail-closed: nao roteia remessa sem tribunal), nunca como 'pula a regra'.
  Precedencia municipio>UF: ORDER BY (j.municipio_ibge IS NULL) ASC = nao-nulo (override) primeiro."
  [tx]
  (:jurisdicao_camara/tribunal_codigo
   (jdbc/execute-one! tx
     (sql/format {:select [:j.tribunal_codigo] :from [[:cadastros.ente :e]]
                  :join [[:cadastros.municipios :m] [:= :m.codigo_ibge :e.municipio_ibge]
                         [:cadastros.jurisdicao_camara :j]
                         [:and [:= :j.uf :m.uf]
                          [:or [:= :j.municipio_ibge :e.municipio_ibge] [:is :j.municipio_ibge nil]]]]
                  :order-by [[[:is :j.municipio_ibge nil] :asc]] :limit 1}))))

;; Registro das relacoes deste contexto (nome canonico -> fn). A F2 consome isto p/ injetar no motor
;; (cada modulo registra suas relacoes no catalogo; o avaliador chama por nome). As assinaturas tipadas
;; (params/retorno p/ o type-check do save time) sao cravadas junto da reconciliacao do registry na F2.
(def relacoes
  {"tem_mandato_vigente"    tem-mandato-vigente?
   "é_membro_de_comissao"   membro-de-comissao?
   "é_presidente_de_comissao" presidente-de-comissao?
   "é_presidente_da_mesa"   presidente-da-mesa?
   "é_secretario_da_mesa"   secretario-da-mesa?
   "quem_exerce_presidencia" quem-exerce-presidencia
   "populacao"              populacao
   "membros_da_casa"        membros-da-casa
   "tribunal_competente"    tribunal-competente})
