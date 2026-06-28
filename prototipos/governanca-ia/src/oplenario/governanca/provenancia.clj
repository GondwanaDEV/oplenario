(ns oplenario.governanca.provenancia
  "Proveniência + GATE determinístico (B1). O sigilo é DADO do domínio que viaja com o conteúdo
  (B2: o core é a verdade, a porta lê a tag). Fail-closed: sem classificação explícita = bloqueado
  (§22.5 eixo E). Bloqueia: sessão fechada/secreta, voto secreto, doc restrito/e-SIC, classe não-pública.")

;; Peça destinada ao LLM externo = {:texto ... :proveniencia {:origem ref :sigilo :publico|:restrito|:secreto :voto-secreto? bool}}.

(defn liberado?
  "True só se a peça é COMPROVADAMENTE pública e sem flag de sigilo. Ausência de tag => false (fail-closed)."
  [{:keys [proveniencia]}]
  (boolean
   (and (map? proveniencia)
        (= :publico (:sigilo proveniencia))          ; precisa ser EXPLICITAMENTE pública
        (not (:voto-secreto? proveniencia)))))

(defn motivo-bloqueio
  "Por que a peça foi bloqueada (p/ auditoria/aviso). nil se liberada. (Toda peça que falha em
  `liberado?` casa um dos ramos abaixo — não há ramo 'bloqueado' genérico, seria código morto.)"
  [{:keys [proveniencia]}]
  (cond
    (not (map? proveniencia))               "proveniencia ausente (fail-closed)"
    (:voto-secreto? proveniencia)           "voto secreto"
    (= :secreto (:sigilo proveniencia))     "sessao/conteudo secreto"
    (= :restrito (:sigilo proveniencia))    "conteudo restrito (sigiloso/e-SIC)"
    (not= :publico (:sigilo proveniencia))  (str "sigilo nao-publico: " (pr-str (:sigilo proveniencia)))
    :else nil))

(defn gate
  "Particiona um payload (coleção de peças) em :liberadas / :bloqueadas. A política (B3) é do filtro:
  qualquer bloqueada => a chamada degrada (não envia parcial em silêncio)."
  [pecas]
  {:liberadas  (vec (filter liberado? pecas))
   :bloqueadas (vec (remove liberado? pecas))})
