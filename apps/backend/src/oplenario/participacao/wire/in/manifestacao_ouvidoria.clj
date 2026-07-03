(ns oplenario.participacao.wire.in.manifestacao-ouvidoria
  "Representacao EXTERNA de ENTRADA da manifestacao de ouvidoria (§22.10 wire/in, ADR-0001) — o que o
  CIDADAO envia no corpo de POST /portal/ouvidoria/manifestacoes. `tipo` (um dos 5 padrao CGU/Lei 13.460) +
  `assunto`/`descricao` (texto livre) + `anonima` (boolean — decisao: anonima NAO e' sem-auth, so' decide
  se o manifestante persiste). O manifestante/recibo sao INJETADOS do ator/relogio na borda, NUNCA vem do
  corpo (anti-forge). Map CLOSED — chave a mais e' rejeitada (fail-closed no adapter).")

(def ManifestacaoOuvidoriaIn
  "Corpo de protocolo de manifestacao de ouvidoria. Closed: allowlist estrita (tipo+assunto+descricao+
  anonima). Os tetos (:max) espelham os CHECK de tamanho da mig 0042 — defesa-em-profundidade anti-abuso."
  [:map {:closed true}
   [:tipo [:enum "reclamacao" "denuncia" "sugestao" "elogio" "solicitacao"]]
   [:assunto [:string {:min 1 :max 500}]]
   [:descricao [:string {:min 1 :max 20000}]]
   [:anonima {:optional true} :boolean]])
