(ns oplenario.legislativo.logic.meus-votos
  "Logica PURA dos votos do proprio vereador ('Minha atuacao'): o que o PORTAL publica do voto e o que e' so' do vereador.

  QUAIS sessoes sao publicas nao e' decidido aqui — `sessoes` e' o dono dessa regra e o host entrega o conjunto de ids
  (§22.10). A marca e' conservadora: so' e 'publico' o voto de sessao que consta no conjunto; sem conjunto (seam fora,
  falha), nada e' 'publico' e a tela avisa que o voto nao esta no portal — o erro cai no lado de avisar, nunca no de
  prometer publicidade.")

(def portal-do-voto
  "Vocabulario do que o portal faz com o voto: `publico` (sessao publica — o cidadao ve), `sessao-fechada` (sessao secreta
  ou fechada ao publico — so' o vereador ve) e `sem-sessao` (votacao fora de plenario — o portal nao publica)."
  #{"publico" "sessao-fechada" "sem-sessao"})

(defn portal-do-voto-de
  "`publico` | `sessao-fechada` | `sem-sessao` para o voto da sessao `sessao-id`, dado o conjunto de sessoes publicas."
  [sessao-id sessoes-publicas]
  (cond
    (nil? sessao-id) "sem-sessao"
    (contains? (set sessoes-publicas) sessao-id) "publico"
    :else "sessao-fechada"))

(defn marcar-voto
  "Acrescenta ao voto cru do Repo `:portal` e `:anulada` (a votacao foi desfeita: o voto aparece, mas nao conta)."
  [sessoes-publicas {:keys [sessao-id votacao-estado] :as voto}]
  (assoc voto
         :portal (portal-do-voto-de sessao-id sessoes-publicas)
         :anulada (= "anulada" votacao-estado)))
