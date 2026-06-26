(ns oplenario.cadastros.relacoes.cadastro
  "Funcoes de RELACAO que o cadastros e' dono (§22.5 eixo B / §22.7.5). O motor de DSL (autorizacao,
  compliance, plenario) as alcanca POR NOME via o registry/injecao da F2 — nunca por import (§22.10).

  Disciplina §22.5.3 disc.5: TODA funcao de relacao aceita `data` (consulta historica) — 'quem era
  presidente da Mesa em DD/MM/AAAA?' tem resposta consultando a vigencia naquela data, nao a de hoje.
  O default now()/hoje e' aplicado pela camada que chama (motor/controller); aqui `data` e' explicito
  (puro/testavel). Recebem a `tx` do tenant (RLS isola; ente=1:1 -> a Casa corrente). Tudo intra-schema
  cadastros (sem JOIN cross-schema, §22.10); a resolucao 'usuario' e' por identidade_id (CPF, disc.1)."
  (:require [next.jdbc :as jdbc]))

(set! *warn-on-reflection* true)

(defn- existe?
  ;; `sql` DEVE ser literal estatico (concatenado em SQL); nunca passar valor de usuario aqui — os
  ;; valores vao SEMPRE por `params` (placeholders ?). Os JOINs casam tambem ente_id (defesa em
  ;; profundidade: a RLS ja isola cada lado, mas o predicado redundante nao depende de FORCE RLS).
  [tx sql params]
  (boolean (jdbc/execute-one! tx (into [(str "SELECT 1 AS x WHERE EXISTS (" sql ")")] params))))

(defn tem-mandato-vigente?
  "A identidade tem mandato em estado 'vigente' cuja vigencia cobre `data`?"
  [tx identidade-id data]
  (existe? tx
    "SELECT 1 FROM cadastros.mandato m JOIN cadastros.vereador v ON v.id = m.vereador_id AND v.ente_id = m.ente_id
     WHERE v.identidade_id = ? AND m.estado = 'vigente'
       AND m.vigencia_inicio <= ? AND (m.vigencia_fim IS NULL OR m.vigencia_fim >= ?)"
    [identidade-id data data]))

(defn membro-de-comissao?
  [tx identidade-id comissao-id data]
  (existe? tx
    "SELECT 1 FROM cadastros.comissao_membro cm JOIN cadastros.vereador v ON v.id = cm.vereador_id AND v.ente_id = cm.ente_id
     WHERE v.identidade_id = ? AND cm.comissao_id = ?
       AND cm.vigencia_inicio <= ? AND (cm.vigencia_fim IS NULL OR cm.vigencia_fim >= ?)"
    [identidade-id comissao-id data data]))

(defn- tem-cargo-na-comissao? [tx identidade-id comissao-id cargos data]
  (existe? tx
    (str "SELECT 1 FROM cadastros.comissao_cargo cc JOIN cadastros.vereador v ON v.id = cc.vereador_id AND v.ente_id = cc.ente_id
          WHERE v.identidade_id = ? AND cc.comissao_id = ? AND cc.cargo = ANY(?)
            AND cc.vigencia_inicio <= ? AND (cc.vigencia_fim IS NULL OR cc.vigencia_fim >= ?)")
    [identidade-id comissao-id (into-array String cargos) data data]))

(defn presidente-de-comissao?
  [tx identidade-id comissao-id data]
  (tem-cargo-na-comissao? tx identidade-id comissao-id ["presidente"] data))

(defn- mesa-vigente-id [tx data]
  (:comissao/id
   (jdbc/execute-one! tx
     ["SELECT id FROM cadastros.comissao WHERE tipo = 'mesa' AND vigencia_inicio <= ?
       AND (vigencia_fim IS NULL OR vigencia_fim >= ?) ORDER BY vigencia_inicio DESC LIMIT 1" data data])))

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
       ["SELECT v.identidade_id FROM cadastros.comissao_cargo cc JOIN cadastros.vereador v ON v.id = cc.vereador_id AND v.ente_id = cc.ente_id
         WHERE cc.comissao_id = ? AND cc.cargo = 'presidente'
           AND cc.vigencia_inicio <= ? AND (cc.vigencia_fim IS NULL OR cc.vigencia_fim >= ?)
         ORDER BY cc.vigencia_inicio DESC, cc.id LIMIT 1" mesa data data]))))

(defn populacao
  "Populacao do municipio do ente corrente (alimenta regras de porte do TCE — §22.7.5)."
  [tx]
  (:municipios/populacao
   (jdbc/execute-one! tx
     ["SELECT m.populacao FROM cadastros.ente e JOIN cadastros.municipios m ON m.codigo_ibge = e.municipio_ibge"])))

(defn membros-da-casa
  "Nº de vereadores com mandato vigente em `data` (base de quorum/maioria — §22.7.5)."
  [tx data]
  (:c (jdbc/execute-one! tx
        ["SELECT count(DISTINCT m.vereador_id) AS c FROM cadastros.mandato m
          WHERE m.estado = 'vigente' AND m.vigencia_inicio <= ?
            AND (m.vigencia_fim IS NULL OR m.vigencia_fim >= ?)" data data])))

(defn tribunal-competente
  "Codigo do Tribunal de Contas competente do ente (E1, §22.7.9): override por municipio > default da UF.
  Reconcilia o 'UF JOIN' do Eixo B com a §22.10 — e' funcao de relacao intra-schema, nao JOIN cross-schema.
  Retorna nil se nao ha jurisdicao cadastrada p/ a UF/municipio -> o CALLER (motor) trata como ERRO
  (fail-closed: nao roteia remessa sem tribunal), nunca como 'pula a regra'."
  [tx]
  (:jurisdicao_camara/tribunal_codigo
   (jdbc/execute-one! tx
     ["SELECT j.tribunal_codigo
       FROM cadastros.ente e
       JOIN cadastros.municipios m ON m.codigo_ibge = e.municipio_ibge
       JOIN cadastros.jurisdicao_camara j
         ON j.uf = m.uf AND (j.municipio_ibge = e.municipio_ibge OR j.municipio_ibge IS NULL)
       ORDER BY (j.municipio_ibge IS NOT NULL) DESC
       LIMIT 1"])))

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
