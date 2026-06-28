(ns oplenario.identidade.relacoes.identidade
  "Funcao de relacao TRANSVERSAL que o identidade e' dono (§22.5.2 eixo B): é_o_próprio. Utilitario p/
  self-action ('editar proprio comentario', 'ver proprio audit log', 'justificar propria ausencia').
  E' PURA (comparacao de identidades) — nao toca banco; o motor a alcanca por nome via o registry (F2).")

(defn e-o-proprio?
  "A identidade do ator e' a mesma do sujeito da acao? Aceita `_tx` por uniformidade da assinatura de
  relacao (o motor injeta a tx; aqui e' ignorada — a relacao e' pura)."
  ([ator-identidade-id sujeito-identidade-id]
   (boolean (and ator-identidade-id (= ator-identidade-id sujeito-identidade-id))))
  ([_tx ator-identidade-id sujeito-identidade-id]
   (e-o-proprio? ator-identidade-id sujeito-identidade-id)))

(def relacoes
  {"é_o_próprio" e-o-proprio?})
