(ns oplenario.transparencia.db.artefato-publicacao
  "Persistencia de 'transparencia.artefato_publicacao' (F6c Slice 4b, feature 16.5 — PROJECAO do artefato de
  publicacao oficial que `legislativo` gera). Funcoes sobre a `tx` corrente (FORCE RLS isola, mig 0047).
  HoneySQL schema-qualified; ente_id em TODA query. Read-model PROJETADO, INSERT-only IDEMPOTENTE (o artefato e'
  imutavel) do evento `artefato.publicacao.gerado`. `inserir!` e' chamado pelo CONSUMER (dentro da tx do relay);
  `mais-recente-por-norma` pela LEITURA publica (rota de download) — ambos via o Repo-Component (o db/ so' e'
  importado pelo seu Component, import-lint ADR-0001)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:ente_id :norma_id :artefato_id :versao :objeto_store_ref :content_type :hash
   :assinatura_algoritmo :assinado :criado_em :projetado_em])

(defn inserir!
  "Projeta o artefato de publicacao (`artefato.publicacao.gerado`). `criado-em` chega como java.time.Instant
  (parseado do ISO-8601 do payload pelo consumer). `assinado` vem do payload `:assinado?` (boolean).
  `ON CONFLICT DO NOTHING` SEM alvo (nao so' a PK): a tabela tem DUAS chaves unicas — a PK (ente_id,
  artefato_id) e o idx UNIQUE (ente_id, norma_id, versao) de defesa-em-profundidade (mig 0047). Um redrive do
  relay COMPARTILHADO (idempotency-key nova, mesmo artefato_id) colide na PK; uma corrupcao da fonte (dois
  artefatos DISTINTOS com a mesma (norma, versao) — que legislativo.artefato_publicacao ja' impede via UNIQUE +
  MAX+1 atomico) colidiria no idx UNIQUE. AMBOS DEVEM no-op, NUNCA lancar 23505 dentro da tx do relay
  (envenenaria o bus de TODOS os modulos, head-of-line). ON CONFLICT com alvo so' pegaria UMA das duas chaves;
  o alvo-menos absorve as duas."
  [tx {:keys [ente-id norma-id artefato-id versao objeto-store-ref content-type assinatura-algoritmo
              criado-em] hash-conteudo :hash assinado :assinado?}]
  {:pre [(some? ente-id) (some? norma-id) (some? artefato-id) (some? versao) (some? objeto-store-ref)
         (some? content-type) (some? hash-conteudo) (some? assinatura-algoritmo) (boolean? assinado)
         (some? criado-em)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :transparencia.artefato_publicacao
                  :values [{:ente_id ente-id :norma_id norma-id :artefato_id artefato-id :versao versao
                            :objeto_store_ref objeto-store-ref :content_type content-type :hash hash-conteudo
                            :assinatura_algoritmo assinatura-algoritmo :assinado assinado :criado_em criado-em}]
                  :on-conflict []
                  :do-nothing []
                  :returning [:*]}))))

(defn mais-recente-por-norma
  "O PONTEIRO do artefato de publicacao MAIS RECENTE (maior versao) de uma norma, ou nil (RLS via ente-id).
  Serve a rota publica de download — resolve `objeto_store_ref` + `content_type` p/ ler o binario. Servida
  por idx_artefato_pub_norma_versao (ente_id, norma_id, versao DESC). ORDER BY termina em `artefato_id DESC`
  = DESEMPATE ESTAVEL (review db MINOR; mesma disciplina de db/norma/listar): a fonte (legislativo, UNIQUE
  (ente_id,norma_id,versao) + MAX+1 atomico) ja' torna versao unica por norma, mas sem o tie-break um futuro
  backfill/redrive que violasse esse contrato faria a 'mais recente' oscilar entre cargas — inaceitavel num
  documento OFICIAL servido publicamente."
  [tx ente-id norma-id]
  {:pre [(some? ente-id) (some? norma-id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:transparencia.artefato_publicacao]
                  :where [:and [:= :ente_id ente-id] [:= :norma_id norma-id]]
                  :order-by [[:versao :desc] [:artefato_id :desc]]
                  :limit 1}))))
