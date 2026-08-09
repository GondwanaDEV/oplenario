(ns oplenario.sessoes.models.presenca
  "Representacao INTERNA (dominio) da PRESENCA (§22.6 eixo C, F4.3a) — Malli (§22.10 models/). PresencaEvento
  (fato append-only com instante de dominio `ocorrido-em`) e JustificativaAusencia (ato apartado com state
  machine). Enums de sessoes.logic (fonte unica; os CHECK da mig 0029 espelham). `vereador-id` e' forward-ref
  a cadastros (uuid, sem FK, §22.10)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.sessoes.logic :as logic]))

(defn- enum-de [s] (into [:enum] (sort s)))

(def PresencaEvento
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:sessao-id :uuid]
   [:vereador-id :uuid]
   [:tipo (enum-de logic/tipos-evento-presenca)]
   [:modalidade (enum-de logic/modalidades-presenca)]
   [:fonte (enum-de logic/fontes-presenca)]
   [:ocorrido-em km/Instante]])

(def LinhaChamada
  "A linha da CHAMADA de um vereador (§22.6 eixo C) — representacao de DOMINIO, kebab-case, produzida por
  `sessoes.logic/derivar-linha-chamada`. NAO e' entidade persistida: a presenca corrente nunca e'
  materializada (e' derivada do ultimo evento por vereador ate' um instante).

  `estado` usa o vocabulario PROPRIO da chamada (`logic/estados-chamada`, keywords), nao o `tipo` cru do
  evento — sao coisas distintas: `entrada`/`saida` sao FATOS append-only, `:presente-plenario` e' a
  CONCLUSAO depois de cruzar cadastro + evento + justificativa.

  `inconsistencia-cadastro` = o cadastro diz licenciado mas o vereador esta fisicamente presente. Fica no
  contrato de dominio (nao e' detalhe de tela) porque e' o unico canal pelo qual esse conflito chega ao
  servidor que pode corrigi-lo.

  `nome-parlamentar` e `partido` sao nullable na origem (`cadastros.vereador.nome_parlamentar` e o LEFT JOIN
  LATERAL do mandato podem nao ter linha na data)."
  [:map {:closed true}
   [:vereador-id :uuid]
   [:nome [:string {:min 1}]]
   [:nome-parlamentar [:maybe :string]]
   [:partido [:maybe :string]]
   [:estado (enum-de logic/estados-chamada)]
   [:inconsistencia-cadastro :boolean]])

(def JustificativaAusencia
  [:map {:closed true}
   [:ente-id :uuid]
   [:id :uuid]
   [:sessao-id :uuid]
   [:vereador-id :uuid]
   [:estado (enum-de logic/estados-justificativa)]
   [:motivo [:string {:min 1}]]
   [:lock-version :int]
   [:decidido-por {:optional true} [:maybe :uuid]]
   [:decidido-em {:optional true} [:maybe km/Instante]]])
