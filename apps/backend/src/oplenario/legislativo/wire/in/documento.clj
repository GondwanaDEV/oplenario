(ns oplenario.legislativo.wire.in.documento
  "Representacao EXTERNA de ENTRADA do EXPEDIENTE — geracao de documento por modelo + protocolo (§22.10
  wire/in, ADR-0001, Onda B Slice 6). `:closed true` recusa campo extra; tenant/autor NUNCA vem do corpo
  (vem do ator resolvido na auth, §22.5); `modelo-id`/`id` do documento vem do corpo/path, nunca inventados
  pelo cliente. `dados` (o MERGE, feature 3.22) e' mapa string->string — os FATOS ja' resolvidos UPSTREAM
  (cadastro etc.) que preenchem os placeholders {{chave}} do template do modelo; ausente vira {} no
  adapters/in (o formulario da tela pode nao ter nenhum placeholder de fato-externo, ex. um modelo cujo
  corpo e' texto fixo). Sem enum nesta borda (vocabulario de tipo-documento vem do MODELO resolvido pelo
  controller, nao do corpo do cliente) — por isso, ao contrario dos wire/in irmaos, nao requer
  kernel.malli/enum-de.")

;; assunto = a linha curta do documento (ex.: 'Convite a audiencia publica'), nao o corpo — mesmo bound de
;; autor-texto/ementa em wire/in/proposicao (texto livre de borda, nao ilimitado).
(def ^:private assunto-max 1000)

(def GerarDocumento
  "Corpo de POST /legislativo/documentos. `modelo-id` obrigatorio (o controller resolve o modelo — tipo-
  documento/corpo-template vem DAI, nao do cliente). `dados` OPCIONAL (default {} no adapters/in)."
  [:map {:closed true}
   [:modelo-id [:string {:min 1 :max 36}]]
   [:assunto [:string {:min 1 :max assunto-max}]]
   [:dados {:optional true} [:maybe [:map-of :string :string]]]])

(def EditarDocumento
  "Corpo de PATCH /legislativo/documentos/:id. PATCH parcial (so' os campos presentes mudam) ENQUANTO
  'rascunho' (o db/documento.clj barra fora disso). `lock-version` OBRIGATORIO (CAS real — mesmo contrato
  de wire/in/proposicao.EditarProposicao e wire/in/parecer.EmitirParecer: sem ele o servidor nunca detecta
  que o documento mudou entre o GET e o PATCH)."
  [:map {:closed true}
   [:lock-version :int]
   [:corpo {:optional true} [:maybe :string]]
   [:assunto {:optional true} [:maybe [:string {:min 1 :max assunto-max}]]]])

(def ProtocolarDocumento
  "Corpo de POST /legislativo/documentos/:id/protocolo — o CTA 'Protocolar e numerar' do mockup. `lock-
  version` OBRIGATORIO (mesmo contrato de EmitirParecer: a CAS otimista real acontece no Repo, contra o
  snapshot que o cliente viu no editor, antes de qualquer escrita)."
  [:map {:closed true}
   [:lock-version :int]])
