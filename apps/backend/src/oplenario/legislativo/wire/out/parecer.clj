(ns oplenario.legislativo.wire.out.parecer
  "Representacao EXTERNA de SAIDA do EDITOR de parecer (§22.10 wire/out, ADR-0001, Onda B Slice 5) — o
  contrato de GET/PATCH/POST /legislativo/pareceres/:id(...) (leitura+escrita interna, servidor). Namespace
  PROPRIO (nao incha wire/out/ficha-materia, que so' expoe o RESUMO do parecer dentro da ficha da materia).
  `estado`/`voto-relator` ficam :string (nunca enum): a maquina de parecer e' TEMPLATE-DRIVEN por camara
  (eixo F reusa o eixo C), mesmo racional de wire/out/proposicao; `voto-relator` tem vocabulario regimental
  ABERTO (§22.4.4). `objeto` reusa o SHAPE dos campos de proposicao (nao a struct inteira — o editor so'
  precisa do resumo p/ o rail da matéria, nao o detalhe completo)."
  (:require [oplenario.kernel.malli :as km]))

(def ObjetoResumoOut
  "O objeto opinado (SEMPRE 'proposicao' nesta fatia, YAGNI — mesma decisao de relatores-pendentes/
  buscar-parecer-para-editor); nil no envelope se objeto-tipo != 'proposicao'."
  [:map {:closed true}
   [:id :string]
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]])

(def ParecerEditorOut
  "GET/PATCH /legislativo/pareceres/:id + POST .../emissao (Onda B Slice 5) — o envelope do editor.
  `texto-estado` e' DERIVADO no adapters/out (rascunho > vigente > vazio); `texto-numero-versao` e' o
  numero_versao da versao que originou relatorio/analise (rascunho se houver, senao vigente)."
  [:map {:closed true}
   [:id :string]
   [:objeto-tipo :string]
   [:objeto-id :string]
   [:comissao-id :string]
   ;; Defeito #11 do ledger de prontidao: `comissao-id` e' guard ref `uuid NOT NULL` sem FK cross-schema,
   ;; e a tela mostrava o UUID por falta de nome. O HOST resolve (`resolver-comissoes`, §22.5.3) e o
   ;; controller decora. OPCIONAL/maybe de proposito: guard ref orfao (ou comissao de outra Casa) sai nil
   ;; — o contrato nao pode quebrar porque o resolver nao achou dono.
   [:comissao-nome {:optional true} [:maybe :string]]
   [:relator-id {:optional true} [:maybe :string]]
   [:voto-relator {:optional true} [:maybe :string]]
   [:estado :string]
   [:template-id :string]
   [:lock-version :int]
   [:criado-em :string]
   [:objeto {:optional true} [:maybe ObjetoResumoOut]]
   [:relatorio {:optional true} [:maybe :string]]
   [:analise {:optional true} [:maybe :string]]
   [:texto-estado (km/enum-de #{"rascunho" "vigente" "vazio"})]
   [:texto-numero-versao {:optional true} [:maybe :int]]
   ;; Onda C4 (feature 7.3) — assinatura da versao VIGENTE (nunca do rascunho, que ainda nao foi assinado).
   ;; `assinatura-b64` NAO exposta (sem uso de UI; o valor bruto so' interessa ao backend/prova).
   [:assinatura-algoritmo {:optional true} [:maybe :string]]
   [:assinado-por {:optional true} [:maybe :string]]
   [:assinado-em {:optional true} [:maybe :string]]])

;; ---------- ADR-0019, Eixo 5 (fatia 2): o copiloto do relator ----------

(def CitacaoAnaliseOut
  "De onde veio um trecho do rascunho: a materia (`materia:<id>`) ou um dispositivo da Casa (`norma:<id>#<endereco>`),
  com o rotulo legivel (o dispositivo leva a data ate' quando o texto foi conferido) e o resultado da conferencia."
  [:map {:closed true}
   [:fonte-id :string]
   [:rotulo [:maybe :string]]
   [:trecho [:maybe :string]]
   [:status :string]])

(def AnaliseCopilotoOut
  "O rascunho da analise de constitucionalidade e juridicidade, ja' conferido pelo core: `texto` e' o texto LIMPO (sem
  as marcas de citacao; os `[confirmar: ...]` ficam) que vai para o campo Analise do editor; `pontos-a-confirmar` sao
  recalculados pelo core a partir dele. `execucao-ia` = o id da execucao NA IA (feature 8.4): so' ele permite o 'Reportar
  erro'; ausente, a tela nao oferece. Nunca e' parecer: o relator revisa e salva pelo fluxo de sempre."
  [:map {:closed true}
   [:texto :string]
   [:citacoes [:sequential CitacaoAnaliseOut]]
   [:paragrafos-sem-fonte [:sequential :int]]
   [:pontos-a-confirmar [:sequential :string]]
   [:incerteza (km/enum-de #{"normal" "revisar_com_atencao"})]
   [:modelo :string]
   [:execucao-ia {:optional true} [:maybe :string]]])

(def CopilotoAnaliseOut
  "POST /legislativo/pareceres/:id/copiloto e /meu/pareceres/:id/copiloto. `normas`: a Casa nao publicou normas de
  referencia ('sem-normas'), publicou mas nenhum dispositivo foi citado ('sem-dispositivo'), ou o rascunho cita
  dispositivo conferido ('citadas'). `indisponivel` = a mensagem R-IA-1 quando a IA nao rascunhou."
  [:map {:closed true}
   [:analise [:maybe AnaliseCopilotoOut]]
   [:normas (km/enum-de #{"sem-normas" "sem-dispositivo" "citadas"})]
   [:indisponivel [:maybe :string]]])
