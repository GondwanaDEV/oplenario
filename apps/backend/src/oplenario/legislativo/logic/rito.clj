(ns oplenario.legislativo.logic.rito
  "PURO: o desenho do rito da Casa para a faixa 'Onde esta' a materia' da ficha (docs/16, retriagem do exploratorio).

  O estado de uma proposicao e' TEXTO LIVRE de template POR CAMARA (Inv.4): nenhum nome de estado pode decidir
  nada aqui — nem a ordem das etapas, nem o que e' passo e o que e' desfecho. Tudo vem do DADO do rito:
  `template_estado` (chave, nome, terminal, ordem) e `template_transicao` (de -> para).

  A ORDEM so' existe quando e' unica E verificavel. Duas fontes, nesta precedencia:
    1. `ordem` declarada em `template_estado` (a coluna tem DEFAULT 0 e nada a valida, entao so' vale com valores
       DISTINTOS entre as etapas);
    2. a ordem topologica das transicoes a partir do estado inicial, quando for unica.
  Qualquer candidata so' vira linha se o RITO a confirmar (`linha-confirmada?`): comeca no estado inicial, cada etapa
  e' ligada a seguinte por uma transicao, e nenhuma transicao SALTA etapa (voltar e' devolucao, vale). Sem isso 'antes
  da atual' nao quer dizer 'ja passou', e a faixa passaria a afirmar o que o rito nao garante.

  Sem linha, a resposta e' so' o entorno: anteriores (do HISTORICO da materia) · atual · proximas possiveis. Nunca uma
  linha reta inventada.

  DESFECHOS (estados terminais) nao sao passos da linha: um rito costuma ter varios (aprovada, arquivada…) e a materia
  vai a UM. Entram na linha so' quando a materia chegou a ele.")

(set! *warn-on-reflection* true)

(defn- etapa [{:keys [chave nome terminal]}]
  {:chave chave :rotulo nome :terminal (boolean terminal)})

(defn- tem-elo? [arestas de para] (contains? arestas [de para]))

(defn- linha-confirmada?
  "A candidata (vetor de chaves, ja' em ordem) so' vale se o rito a confirma. `arestas` = conjunto de [de para] entre
  etapas NAO terminais; `inicial` = o estado inicial do template."
  [candidata arestas inicial]
  (let [posicao (zipmap candidata (range))]
    (boolean
     (and (seq candidata)
          (= inicial (first candidata))
          ;; cada etapa liga na seguinte
          (every? (fn [[a b]] (tem-elo? arestas a b)) (partition 2 1 candidata))
          ;; nenhuma transicao salta para frente mais que uma etapa (voltar e mesmo-lugar sao validos)
          (every? (fn [[de para]] (<= (posicao para) (inc (posicao de)))) arestas)))))

(defn- pela-ordem-declarada
  "As etapas pela `ordem` do template, ou nil quando a ordem nao distingue todas (valores repetidos, tipicamente o 0
  de coluna nao preenchida)."
  [nao-terminais]
  (when (and (seq nao-terminais)
             (= (count nao-terminais) (count (distinct (map :ordem nao-terminais)))))
    (mapv :chave (sort-by :ordem nao-terminais))))

(defn- pela-topologia
  "A ordem topologica UNICA das etapas pelas transicoes (algoritmo de Kahn exigindo exatamente uma etapa livre a cada
  passo), ou nil quando ha' ramificacao, ciclo ou etapa solta. Auto-aresta e volta contam como ciclo: quem declara
  devolucao precisa declarar a `ordem`."
  [chaves arestas]
  (when (seq chaves)
    (loop [restantes (set chaves) resultado []]
      (if (empty? restantes)
        resultado
        (let [livres (filterv (fn [c] (not-any? (fn [[de para]] (and (= para c) (contains? restantes de))) arestas))
                              (filter restantes chaves))]
          (when (= 1 (count livres))
            (recur (disj restantes (first livres)) (conj resultado (first livres)))))))))

(defn- proximas [atual estados-por-chave transicoes]
  (->> transicoes
       (filter #(= (:chave atual) (:de-estado %)))
       (map :para-estado)
       (remove #(= % (:chave atual)))
       distinct
       (keep estados-por-chave)
       (mapv etapa)))

(defn- anteriores [atual estados-por-chave tramitacao]
  (->> tramitacao
       (mapcat (juxt :de-estado :para-estado))
       distinct
       (remove #(= % (:chave atual)))
       (keep estados-por-chave)
       (mapv etapa)))

(defn rito-da-materia
  "`{:estado-inicial :estados :transicoes :atual :tramitacao :tramitacao-truncado}` -> o rito como a ficha o mostra,
  ou nil quando a materia nao tem rito (sem estados).

    :ordem-unica  true = `:etapas` e' a linha do rito, em ordem (e, se a atual e' terminal, fecha nela);
                  false = o rito nao da ordem unica verificavel: `:etapas` vazio.
    :atual        a etapa atual COMO O RITO A DECLARA (rotulo da Casa), ou nil se o rito nao a declara.
    :anteriores   so' sem linha: por onde a materia passou, pelo historico; nil quando o historico foi cortado pelo
                  teto (nao afirma lista parcial) ou quando o rito nao declara a atual.
    :proximas     os destinos que o rito declara a partir da atual (nao avalia guarda: possiveis, nao disponiveis)."
  [{:keys [estado-inicial estados transicoes atual tramitacao tramitacao-truncado]}]
  (when (seq estados)
    (let [por-chave (into {} (map (juxt :chave identity)) estados)
          nao-terminais (remove :terminal estados)
          chaves-nt (mapv :chave nao-terminais)
          conjunto-nt (set chaves-nt)
          arestas (into #{} (comp (filter #(and (conjunto-nt (:de-estado %)) (conjunto-nt (:para-estado %))))
                                  (map (juxt :de-estado :para-estado)))
                        transicoes)
          linha (->> [(pela-ordem-declarada nao-terminais) (pela-topologia chaves-nt arestas)]
                     (filter #(linha-confirmada? % arestas estado-inicial))
                     first)
          estado-atual (get por-chave atual)
          etapas (when linha
                   (cond-> (mapv (comp etapa por-chave) linha)
                     (:terminal estado-atual) (conj (etapa estado-atual))))]
      {:ordem-unica (boolean linha)
       :etapas (or etapas [])
       :atual (some-> estado-atual etapa)
       :anteriores (when (and (not linha) (not tramitacao-truncado) estado-atual)
                     (anteriores estado-atual por-chave tramitacao))
       :proximas (if estado-atual (proximas estado-atual por-chave transicoes) [])})))
