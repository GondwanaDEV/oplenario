(ns oplenario.tempo-real.logic
  "PURO: as regras do endpoint SSE (§22.6 eixo G / §22.5 eixo E). A POLITICA da camada FINA do canal plenario
  mora aqui (o canal e' o recurso DONO deste modulo, ADR-0001) — consumida por kernel.autorizacao/check! no
  controller. Sem I/O. Os predicados materializam os itens 1 e 2 do CHECKLIST de authz cravado em canais.clj.")

(set! *warn-on-reflection* true)

(defn mesma-casa?
  "Item 1 do checklist (posse de tenant, defesa-em-profundidade): o ator e a sessao sao da MESMA Casa. A RLS ja
  escopa a consulta; isto barra um recurso de outra Casa que escape por bug de query/repo (fail-closed)."
  [ator sessao]
  (= (:ente-id ator) (:ente-id sessao)))

(defn plenario-publico?
  "Item 2 do checklist: a sessao transmite publicamente. Sessao secreta (transmite_publica=false) NAO alimenta
  o painel ao vivo — recusa-se a subscricao inteira (nao se filtra por evento)."
  [sessao]
  (true? (:transmite-publica sessao)))

(defn pode-assistir-plenario?
  "Politica da camada FINA (policy.check) p/ ABRIR o stream do painel ao vivo, com a sessao ja carregada:
  mesma Casa E sessao com transmissao publica. Politicas mais ricas (ex.: so vereadores/Mesa) plugam aqui sem
  mudar a borda. Pura — o seam estavel; em F2 vira expressao da DSL avaliada pelo mesmo motor (disciplina 5)."
  [ator sessao]
  (and (mesma-casa? ator sessao)
       (plenario-publico? sessao)))
