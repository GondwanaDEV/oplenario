(ns oplenario.participacao.wire.out.atendimento
  "Representacao EXTERNA de SAIDA do BALCAO interno de atendimento (§22.10 wire/out, ADR-0001): as tres filas da
  secretaria (GET /atendimento/esic|ouvidoria|lgpd) e o detalhe de cada item (GET /atendimento/<especie>/:id).
  Rota de SERVIDOR (papel `secretario`), mas a identidade segue a lei, por CONTRATO (map closed — campo a mais e' 500):

  - e-SIC: o nome do requerente (LAI art. 10) e o CPF MASCARADO — nunca o CPF inteiro;
  - LGPD: o titular, idem;
  - OUVIDORIA: so' `identificacao` (identificada|anonima) — nem nome nem CPF (Lei 13.460 art. 10 §7º).

  Datas: `recebido-em`/`em`/`decidido-em` = instante ISO; `prazo-vigente`/`de-data`/`para-data` = AAAA-MM-DD (o dia
  civil do prazo). `dias-restantes` negativo = vencido, 0 = ultimo dia, nil = encerrado (o prazo nao corre mais)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def ^:private Data [:re #"^\d{4}-\d{2}-\d{2}$"])

(def ^:private prazo
  [[:aberto :boolean]
   [:recebido-em :string]
   [:prazo-vigente [:maybe Data]]
   [:dias-restantes [:maybe :int]]
   [:prorrogado :boolean]])

(def PessoaOut
  "Quem pediu (e-SIC/LGPD). O CPF ja' sai mascarado do banco: '***.456.789-**'."
  [:map {:closed true}
   [:nome :string]
   [:cpf-mascarado [:re #"^\*\*\*\.\d{3}\.\d{3}-\*\*$"]]])

(def RecursoPendenteOut
  [:map {:closed true} [:id :string] [:protocolo :string] [:recebido-em :string]])

(def ItemEsicOut
  (into [:map {:closed true}
         [:id :string] [:protocolo :string]
         ;; CONTEUDO DO USUARIO: o consumidor escapa antes de renderizar (React ja' escapa).
         [:assunto :string]
         [:estado (km/enum-de logic/estados-pedido)]
         [:recurso-pendente [:maybe RecursoPendenteOut]]]
        prazo))

(def ItemOuvidoriaOut
  (into [:map {:closed true}
         [:id :string] [:protocolo :string]
         [:tipo (km/enum-de logic/tipos-manifestacao)]
         [:assunto :string]
         [:identificacao [:enum "anonima" "identificada"]]
         [:estado (km/enum-de logic/estados-manifestacao)]]
        prazo))

(def ItemLgpdOut
  (into [:map {:closed true}
         [:id :string] [:protocolo :string]
         [:tipo (km/enum-de logic/tipos-solicitacao-titular)]
         [:estado (km/enum-de logic/estados-solicitacao-titular)]]
        prazo))

(def ^:private Situacao (km/enum-de logic/situacoes-do-balcao))

(def FilaEsicOut [:map {:closed true} [:situacao Situacao] [:itens [:vector ItemEsicOut]]])
(def FilaOuvidoriaOut [:map {:closed true} [:situacao Situacao] [:itens [:vector ItemOuvidoriaOut]]])
(def FilaLgpdOut [:map {:closed true} [:situacao Situacao] [:itens [:vector ItemLgpdOut]]])

(def EventoOut
  "Uma entrada do historico, em ordem cronologica. `por` = o nome de quem agiu pela Casa (nil no recurso, que e' do
  cidadao, ou se a pessoa nao foi encontrada). `protocolo` so' no recurso; `de-data`/`para-data` so' na prorrogacao.
  `indeferimento` = a recusa fundamentada (o `texto` e' a fundamentacao), distinta da `resposta` de merito."
  [:map {:closed true}
   [:tipo [:enum "resposta" "indeferimento" "recurso" "decisao-recurso" "prorrogacao" "arquivamento"]]
   [:em :string]
   [:texto :string]
   [:por [:maybe :string]]
   [:protocolo {:optional true} :string]
   [:de-data {:optional true} Data]
   [:para-data {:optional true} Data]])

(def RecursoOut
  "O recurso do pedido (V1: um por pedido), com o prazo PROPRIO dele."
  [:map {:closed true}
   [:id :string] [:protocolo :string] [:motivo [:maybe :string]]
   [:estado (km/enum-de logic/estados-recurso)]
   [:recebido-em :string] [:decidido-em [:maybe :string]]
   [:prazo-vigente [:maybe Data]] [:dias-restantes [:maybe :int]] [:prorrogado :boolean]])

(def AcoesEsicOut
  "O que cabe no estado atual (a tela nao deduz regra): responder o pedido, indeferi-lo com fundamentacao (LAI art. 11
  §1º II — mesma condicao do responder), prorrogar o prazo DO PEDIDO (LAI art. 11 §2º, uma vez) e decidir o recurso
  pendente (o id dele)."
  [:map {:closed true}
   [:pode-responder :boolean]
   [:pode-indeferir :boolean]
   [:pode-prorrogar :boolean]
   [:recurso-pendente-id [:maybe :string]]])

(def AcoesOuvidoriaOut
  [:map {:closed true} [:pode-responder :boolean] [:pode-arquivar :boolean] [:pode-prorrogar :boolean]])

(def AcoesLgpdOut
  "Responder e indeferir (LGPD art. 18 §4º) a solicitacao aberta: a mesma condicao."
  [:map {:closed true} [:pode-responder :boolean] [:pode-indeferir :boolean]])

(def DetalheEsicOut
  (into [:map {:closed true}
         [:id :string] [:protocolo :string]
         [:assunto :string] [:descricao :string]
         [:estado (km/enum-de logic/estados-pedido)]
         [:requerente [:maybe PessoaOut]]
         [:recurso [:maybe RecursoOut]]
         [:historico [:vector EventoOut]]
         [:acoes AcoesEsicOut]]
        prazo))

(def DetalheOuvidoriaOut
  (into [:map {:closed true}
         [:id :string] [:protocolo :string]
         [:tipo (km/enum-de logic/tipos-manifestacao)]
         [:assunto :string] [:descricao :string]
         [:identificacao [:enum "anonima" "identificada"]]
         [:estado (km/enum-de logic/estados-manifestacao)]
         [:historico [:vector EventoOut]]
         [:acoes AcoesOuvidoriaOut]]
        prazo))

(def DetalheLgpdOut
  (into [:map {:closed true}
         [:id :string] [:protocolo :string]
         [:tipo (km/enum-de logic/tipos-solicitacao-titular)]
         [:detalhe [:maybe :string]]
         [:estado (km/enum-de logic/estados-solicitacao-titular)]
         [:titular [:maybe PessoaOut]]
         [:historico [:vector EventoOut]]
         [:acoes AcoesLgpdOut]]
        prazo))
