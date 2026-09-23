(ns oplenario.legislativo.wire.in.documento-modelo
  "Representacao EXTERNA de ENTRADA do MODELO de documento — CRUD de template (§22.10 wire/in, ADR-0001,
  Onda B Slice 6, fatia de escrita). `:closed true` recusa campo extra; tenant/autor NUNCA vem do corpo
  (vem do ator resolvido na auth, §22.5); `id`/`chave` do modelo vem do corpo/path, nunca gerados aqui pelo
  cliente exceto `chave` (o cliente escolhe a chave estavel, unica por ente — UNIQUE(ente_id,chave) no db).
  `tipo-documento` usa o MESMO vocabulario de logic/tipos-documento do wire/out irmao (fonte unica, espelha
  o CHECK da mig 0025)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

(def ^:private chave-max 100)
(def ^:private nome-max 200)

(def CriarModelo
  "Corpo de POST /legislativo/documento-modelos. `corpo-template` sem teto de tamanho (mesmo racional de
  wire/in/documento.EditarDocumento.corpo — texto livre de template, nao ha' razao de negocio p/ limitar)."
  [:map {:closed true}
   [:chave [:string {:min 1 :max chave-max}]]
   [:nome [:string {:min 1 :max nome-max}]]
   [:tipo-documento (km/enum-de logic/tipos-documento)]
   [:corpo-template [:string {:min 1}]]])

(def AtualizarModelo
  "Corpo de PATCH /legislativo/documento-modelos/:id. PATCH parcial (so' os campos presentes mudam).
  `lock-version` OBRIGATORIO (CAS real, mesmo contrato de wire/in/documento.EditarDocumento). `chave`/
  `tipo-documento` NAO editaveis nesta fatia (identidade do modelo; trocar tipo-documento de um template
  ja usado mudaria retroativamente o rotulo de documentos ja gerados por ele — fora de escopo, sem pedido
  de cliente validado)."
  [:map {:closed true}
   [:lock-version :int]
   [:nome {:optional true} [:maybe [:string {:min 1 :max nome-max}]]]
   [:corpo-template {:optional true} [:maybe [:string {:min 1}]]]
   [:ativo {:optional true} [:maybe :boolean]]])
