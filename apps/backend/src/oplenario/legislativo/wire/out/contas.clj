(ns oplenario.legislativo.wire.out.contas
  "Contrato de SAIDA do julgamento das contas (ADR-0021 Parte B). Malli fechado. Tres formas: o RESUMO da lista interna,
  a PRESTACAO inteira (ficha, papeis secretario/vereador/juridico) e a PUBLICA do portal (anonimo: so' os documentos do
  TCE, sem notificacao nem defesa). Datas `AAAA-MM-DD`, instantes ISO-8601.")

(def tipos [:enum "governo_prefeito" "gestao_camara"])
(def pareceres [:enum "favoravel" "favoravel_com_ressalvas" "desfavoravel"])
(def estados [:enum "aguardando_notificacao" "prazo_de_defesa" "pronta_para_pauta" "julgada" "acompanhamento"])
(def resultados [:enum "parecer_mantido" "parecer_rejeitado"])
(def tipos-documento [:enum "parecer_previo" "relatorio_tce" "notificacao" "defesa" "decisao_tce" "outro"])

(def PrestacaoResumo
  [:map {:closed true}
   [:id :string]
   [:tipo tipos]
   [:exercicio :int]
   [:responsavel :string]
   [:parecer-previo {:optional true} pareceres]
   [:estado estados]
   [:resultado {:optional true} resultados]
   [:prazo-julgamento-ate {:optional true} :string]])

(def PrestacoesOut [:map {:closed true} [:prestacoes [:sequential PrestacaoResumo]]])

(def DocumentoOut
  [:map {:closed true} [:id :string] [:tipo tipos-documento] [:nome :string] [:tamanho-bytes :int] [:criado-em :string]])

(def PrestacaoOut
  "O resumo + a ficha: o PDL, a notificacao e a defesa, o quorum que rejeita o parecer (2/3 dos membros), o placar e o
  resultado em palavras, se a pauta aceita o PDL hoje (e por que nao) e os documentos."
  (into PrestacaoResumo
        [[:recebida-em :string]
         [:processo-tce {:optional true} :string]
         [:proposicao {:optional true} [:map {:closed true} [:id :string] [:rotulo :string] [:estado :string]]]
         [:notificado-em {:optional true} :string]
         [:notificacao-meio {:optional true} :string]
         [:prazo-defesa-ate {:optional true} :string]
         [:defesa-juntada-em {:optional true} :string]
         [:julgada-em {:optional true} :string]
         [:situacao-tce {:optional true} :string]
         [:quorum [:map {:closed true} [:base-membros :int] [:necessarios-para-rejeitar :int]]]
         [:votacao {:optional true} [:map {:closed true} [:id :string] [:sim :int] [:nao :int] [:abstencao :int]]]
         [:frase-resultado {:optional true} :string]
         [:pautavel :boolean]
         [:motivo-nao-pautavel {:optional true} :string]
         [:documentos [:sequential DocumentoOut]]]))

(def PrestacaoPublica
  [:map {:closed true}
   [:id :string]
   [:tipo tipos]
   [:exercicio :int]
   [:responsavel :string]
   [:parecer-previo {:optional true} pareceres]
   [:estado estados]
   [:resultado {:optional true} resultados]
   [:julgada-em {:optional true} :string]
   [:proposicao-rotulo {:optional true} :string]
   [:situacao-tce {:optional true} :string]
   [:frase-resultado {:optional true} :string]
   [:documentos [:sequential [:map {:closed true} [:id :string] [:tipo [:enum "parecer_previo" "relatorio_tce" "decisao_tce"]]
                              [:nome :string]]]]])

(def PrestacoesPublicasOut [:map {:closed true} [:prestacoes [:sequential PrestacaoPublica]]])

(def DocumentoAnexadoOut DocumentoOut)

(def ParametrosOut
  [:map {:closed true} [:prazo-defesa-dias :int] [:prazo-julgamento-dias :int] [:padrao :boolean]])
