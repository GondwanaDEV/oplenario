(ns oplenario.legislativo.logic.votacao-ia
  "PURO — a votacao ENCERRADA como o contexto da IA a le (A.6, ata com o resultado das votacoes). So' o que a ata
  cita: o objeto votado em palavras, a modalidade, o quorum (com a aritmetica do core), o resultado e os totais.

  NUNCA o voto de cada vereador, nem na votacao nominal: nome de vereador ao lado de voto, em contexto de LLM, e'
  dado pessoal (o fornecedor real segue travado no [GAP] juridico) e a lista nominal fica no anexo do sistema. A
  forma devolvida aqui nao tem onde levar isso: o `select-keys` e' uma allowlist, e o wire de saida e' fechado."
  (:require [oplenario.legislativo.logic :as logic]))

(defn objeto-em-palavras
  "O que foi votado, para quem le a ata. Proposicao e redacao final tem numero ('PL 008/2026'); emenda, parecer e
  requerimento apontam OUTRA tabela e o contexto nao os resolve — vao pelo tipo, sem inventar titulo (mesma regra do
  `detalhe-votacao`). `materia` = {:tipo :ano :sequencial} da proposicao, quando a votacao a carrega e ela existe."
  [objeto-tipo materia]
  (let [rotulo (when (and materia (:sequencial materia)) (logic/numero-exibicao materia))]
    (case objeto-tipo
      "proposicao"    (or rotulo "uma proposição")
      "redacao_final" (if rotulo (str "redação final do " rotulo) "a redação final de uma proposição")
      "emenda"        "uma emenda"
      "parecer"       "um parecer"
      "requerimento"  "um requerimento"
      (throw (ex-info "objeto de votacao desconhecido" {:objeto-tipo objeto-tipo})))))

(defn votos-necessarios
  "Quantos votos sim o quorum exige sobre a composicao da Casa (nil na maioria simples, ou sem base gravada) — a
  MESMA aritmetica que decidiu o resultado."
  [quorum-tipo base-membros]
  (when base-membros (logic/votos-necessarios quorum-tipo base-membros)))

(defn para-contexto
  "Linha da votacao encerrada (com `:materia-*`, do JOIN do banco) -> a forma do contexto da IA."
  [{:keys [id objeto-tipo modalidade quorum-tipo base-membros resultado total-sim total-nao total-abstencao
           encerrada-em materia-tipo materia-ano materia-sequencial]}]
  {:id               id
   :objeto           (objeto-em-palavras objeto-tipo {:tipo materia-tipo :ano materia-ano :sequencial materia-sequencial})
   :modalidade       modalidade
   :quorum-tipo      quorum-tipo
   :votos-necessarios (votos-necessarios quorum-tipo base-membros)
   :base-membros     base-membros
   :resultado        resultado
   :total-sim        total-sim
   :total-nao        total-nao
   :total-abstencao  total-abstencao
   :encerrada-em     encerrada-em})
