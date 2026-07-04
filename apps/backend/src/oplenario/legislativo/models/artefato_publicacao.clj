(ns oplenario.legislativo.models.artefato-publicacao
  "Representacao INTERNA (dominio) do ARTEFATO DE PUBLICACAO OFICIAL ('DO-lite', doc-mestre L287, feature 16.5)
  — Malli (§22.10 models/, F6c Slice 4a). Artefato LEGAL imutavel: gerado uma vez por versao, nunca muta. O
  binario mora no objeto_store; aqui so' metadados + ponteiro + a assinatura DESTACADA. `assinado-por` e'
  forward-ref ao ator (sem FK cross-schema; NULL ate' a borda autenticada, Slice 4b). Carimbos via
  kernel.malli/Instante (timestamptz). Espelha o model Remessa (mesmo padrao de artefato, §22.7.8)."
  (:require [oplenario.kernel.malli :as km]))

(def ArtefatoPublicacao
  [:map {:closed true}
   [:id :uuid]
   [:ente-id :uuid]
   [:norma-id :uuid]
   [:versao :int]
   [:spec-versao :string]
   [:content-type :string]
   [:hash :string]
   [:objeto-store-ref :string]
   [:assinatura-algoritmo :string]
   [:assinatura-b64 :string]
   ;; o ator que assinou: NULL ate' a borda HTTP autenticada + step-up (Slice 4b / F1.4-carry).
   [:assinado-por {:optional true} [:maybe :uuid]]
   ;; assinado-em/criado-em sao DEFAULTs do banco; nao-opcionais aqui porque o UNICO caminho de insert
   ;; (db/inserir-versionada! RETURNING *) sempre os devolve preenchidos. Se um insert futuro projetar um
   ;; subconjunto de colunas, revisar esta obrigatoriedade (review architect MINOR).
   [:assinado-em km/Instante]
   [:criado-em km/Instante]])
