(ns oplenario.transparencia.suporte-presenca
  "SUPORTE DE TESTE (nao e' um ns de teste — o kaocha so' carrega `*-test`): a UNICA trava de vocabulario de
  presenca dos testes de `transparencia`, compartilhada por perfil_test, http_perfil_test e portal_test.

  POR QUE EXISTE. `transparencia` semeia presenca por `projetar-evento!`/`ev-presenca/registrada` com o
  payload escrito a mao — nenhum dos dois passa pelo produtor real (`sessoes/db/presenca/registrar-evento!`,
  que e' fail-closed contra `sessoes.logic`). Ate' a Onda E/fatia 1 as fixtures semeavam `tipo \"presente\"`,
  `modalidade \"presencial\"` e `fonte \"mesa\"` — TRES valores que produtor nenhum emite — e a suite inteira
  de presenca ficava verde sobre um pipeline FICTICIO. Foi esse o mecanismo que manteve vivo o numerador
  morto (`tipo = 'presente'`) por duas fatias. A fatia 1 travou DOIS dos tres arquivos com copias inline do
  guard e deixou portal_test — justamente o ns que exercita a cadeia ponta-a-ponta — sem detector nenhum.
  Aqui a trava e' UMA so', e os tres arquivos a chamam.

  NAO substitui o aperto do produtor: `sessoes/events/presenca/RegistradaPayload` tambem passou a tipar
  tipo/modalidade/fonte como `[:enum ...]` alimentado por `sessoes.logic`. Os dois sao necessarios — o enum
  cobre quem emite o EVENTO, este guard cobre quem chama `projetar-evento!` DIRETO (que nao ve o envelope)."
  (:require [oplenario.sessoes.logic :as sessoes-logic]))

(defn validar-vocabulario!
  "Valida `tipo`/`modalidade`/`fonte` do payload de `presenca.registrada` contra os conjuntos de
  `sessoes.logic` (a fonte do produtor real). LANCA `ex-info` no primeiro valor fora do vocabulario; devolve
  o proprio payload quando tudo passa, para poder envolver o literal no ponto da construcao."
  [{:keys [tipo modalidade fonte] :as payload}]
  (doseq [[campo valor validos] [["tipo" tipo sessoes-logic/tipos-evento-presenca]
                                 ["modalidade" modalidade sessoes-logic/modalidades-presenca]
                                 ["fonte" fonte sessoes-logic/fontes-presenca]]]
    (when-not (contains? validos valor)
      (throw (ex-info (str "fixture de presenca fora do vocabulario de sessoes: " campo)
                      {:campo campo :valor valor :validos validos}))))
  payload)
