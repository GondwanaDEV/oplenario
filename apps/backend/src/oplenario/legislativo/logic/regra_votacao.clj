(ns oplenario.legislativo.logic.regra-votacao
  "PURO: qual regra de votacao (`legislativo.regra_votacao_materia`) vale para uma materia, e a recusa em palavras.

  A regra e' DADO (Inv.4): a guarda, os turnos e o intersticio moram na tabela. Aqui so' mora a ESCOLHA da linha pela
  materia — a classe de materia e' vocabulario do produto (a especie da proposicao, o tipo da prestacao de contas), nao
  da Casa — e a frase que a Mesa le quando a abertura e' recusada (pela guarda ou pelo turno, `recusa-do-turno`)."
  (:require [oplenario.legislativo.logic.contas :as contas])
  (:import (java.time.format DateTimeFormatter)))

(set! *warn-on-reflection* true)

(def chave-emenda-lom
  "A emenda a Lei Organica (CF art. 29): 2/3 dos membros, dois turnos, intersticio de dez dias."
  "emenda_lom")

(def especie-emenda-lom "proposta_emenda_lom")

(defn chave-da-materia
  "A chave da regra para a materia, ou nil (a materia vota com o quorum que a Mesa escolher). `proposicao` e' a linha
  da proposicao (ou nil); `prestacao` e' a prestacao de contas cujo PDL e' esta proposicao (ou nil)."
  [proposicao prestacao]
  (cond
    (and prestacao (contas/governo? prestacao)) contas/chave-da-regra
    (= especie-emenda-lom (:tipo proposicao)) chave-emenda-lom))

(def ^:private o-que-a-regra-pede
  {contas/chave-da-regra
   "O julgamento das contas do Prefeito se vota em votação nominal e só rejeita o parecer do TCE com 2/3 dos membros da Câmara"
   chave-emenda-lom
   "A emenda à Lei Orgânica só é aprovada com 2/3 dos membros da Câmara"})

(def ^:private nome-da-regra
  {contas/chave-da-regra "das contas do Prefeito"
   chave-emenda-lom "da emenda à Lei Orgânica"})

(defn recusa-da-guarda
  "A frase da recusa quando a abertura nao passa na guarda da regra `chave`."
  [chave referencia]
  (str (get o-que-a-regra-pede chave "A votação desta matéria tem regra própria") " (" referencia ")."))

(defn regra-ausente
  "A frase quando a linha da regra nao existe (fail-closed: a votacao nao abre sem a regra)."
  [chave]
  (str "A regra de votação " (get nome-da-regra chave (str "\"" chave "\"")) " não está configurada."))

(def ^:private a-materia
  {chave-emenda-lom {:esta "Esta emenda à Lei Orgânica" :desta "desta emenda à Lei Orgânica"}})

(def ^:private data-br (DateTimeFormatter/ofPattern "dd/MM/yyyy"))

(defn- quantos-turnos [n] (if (= 2 n) "dois" (str n)))

(defn recusa-do-turno
  "A frase da recusa quando a votacao de turno nao abre (`logic/turnos/para-abrir` devolveu `:recusa`). A rejeicao
  nao cita a regra: a CF fala dos turnos e do intersticio, nao do destino da materia rejeitada."
  [{:keys [chave referencia turnos intersticio-dias]} {:keys [recusa turno anterior a-partir-de]}]
  (let [{:keys [esta desta]} (get a-materia chave {:esta "Esta matéria" :desta "desta matéria"})]
    (case recusa
      :intersticio (str "O " turno "º turno " desta " só pode ser votado a partir de "
                        (.format ^DateTimeFormatter data-br ^java.time.LocalDate a-partir-de)
                        ": são " intersticio-dias " dias depois do " anterior "º turno (" referencia ").")
      :concluida (str esta " já foi aprovada nos " (quantos-turnos turnos) " turnos (" referencia ").")
      :rejeitada (str esta " foi rejeitada no " turno "º turno: não há outro turno a votar."))))
