(ns oplenario.legislativo.rito-logic-test
  "A faixa 'Onde esta' a materia' da ficha vem do RITO da Casa (`template_estado` + `template_transicao`), nunca de
  uma lista de nomes no front. Estes testes fixam a regra de ORDEM: so' ha' linha quando o rito da uma ordem unica e
  verificavel; senao a resposta e' 'anteriores / atual / proximas', sem inventar linha reta.

  O vocabulario das fixtures e' PROPOSITALMENTE o de uma Casa que nao e' a da demo (instrucao, plenario_unico…):
  nenhum nome aqui consta de lista fixa nenhuma."
  (:require [clojure.test :refer [deftest is testing]]
            [oplenario.legislativo.logic.rito :as rito]))

(defn- est
  ([chave nome ordem] (est chave nome ordem false))
  ([chave nome ordem terminal] {:chave chave :nome nome :ordem ordem :terminal terminal}))

(defn- tr [de para] {:de-estado de :para-estado para})

(defn- chaves [etapas] (mapv :chave etapas))

;; rito linear de uma Casa fora da demo: 3 etapas + 2 desfechos (terminais)
(def ^:private estados-linear
  [(est "entrada" "Entrada" 1)
   (est "instrucao" "Instrução" 2)
   (est "plenario_unico" "Plenário único" 3)
   (est "promulgada" "Promulgada" 4 true)
   (est "rejeitada" "Rejeitada" 5 true)])

(def ^:private transicoes-linear
  [(tr "entrada" "instrucao")
   (tr "instrucao" "plenario_unico")
   (tr "plenario_unico" "promulgada")
   (tr "plenario_unico" "rejeitada")
   (tr "instrucao" "rejeitada")])

(defn- rito-de [m]
  (rito/rito-da-materia (merge {:estado-inicial "entrada" :estados estados-linear :transicoes transicoes-linear
                                :atual "instrucao" :tramitacao [] :tramitacao-truncado false}
                               m)))

(deftest linha-pela-ordem-declarada
  (let [r (rito-de {})]
    (testing "a linha e' a das etapas NAO terminais, na ordem do rito, com o nome que a Casa deu"
      (is (true? (:ordem-unica r)))
      (is (= ["entrada" "instrucao" "plenario_unico"] (chaves (:etapas r))))
      (is (= ["Entrada" "Instrução" "Plenário único"] (mapv :rotulo (:etapas r)))))
    (testing "a etapa atual vem com o rotulo da Casa; as proximas sao os destinos declarados a partir dela"
      (is (= {:chave "instrucao" :rotulo "Instrução" :terminal false} (:atual r)))
      (is (= ["plenario_unico" "rejeitada"] (chaves (:proximas r)))))
    (testing "desfechos (terminais) nao sao etapa da linha enquanto a materia nao chegou neles: sao ramos, nao passos"
      (is (not-any? :terminal (:etapas r))))))

(deftest terminal-atual-fecha-a-linha
  (let [r (rito-de {:atual "rejeitada"})]
    (is (true? (:ordem-unica r)))
    (is (= ["entrada" "instrucao" "plenario_unico" "rejeitada"] (chaves (:etapas r))))
    (is (true? (:terminal (last (:etapas r)))))
    (is (= "rejeitada" (:chave (:atual r))))
    (is (= [] (:proximas r)))))

(deftest ordem-nao-informada-cai-na-topologia
  (testing "ordem 0 em todos (o default da coluna): a ordem unica sai das transicoes a partir do estado inicial"
    (let [zerados (mapv #(assoc % :ordem 0) estados-linear)
          r (rito-de {:estados zerados})]
      (is (true? (:ordem-unica r)))
      (is (= ["entrada" "instrucao" "plenario_unico"] (chaves (:etapas r))))))
  (testing "a ordem declarada NAO prevalece sobre o que a engine permite: ordem que contradiz o estado inicial e' ignorada"
    (let [invertidos [(est "entrada" "Entrada" 3) (est "instrucao" "Instrução" 2) (est "plenario_unico" "Plenário único" 1)
                      (est "promulgada" "Promulgada" 4 true)]
          r (rito-de {:estados invertidos})]
      (is (= ["entrada" "instrucao" "plenario_unico"] (chaves (:etapas r)))
          "cai na topologia, que e' o que a engine de fato executa"))))

(deftest sem-ordem-unica-nao-inventa-linha
  (let [bifurca [(est "a" "A" 0) (est "b" "B" 0) (est "c" "C" 0) (est "d" "D" 0) (est "fim" "Fim" 0 true)]
        t-bifurca [(tr "a" "b") (tr "a" "c") (tr "b" "d") (tr "c" "d") (tr "d" "fim")]]
    (testing "ramificacao (a -> b | c -> d) sem ordem declarada: sem linha, so' o entorno da etapa atual"
      (let [r (rito-de {:estado-inicial "a" :estados bifurca :transicoes t-bifurca :atual "a"
                        :tramitacao []})]
        (is (false? (:ordem-unica r)))
        (is (= [] (:etapas r)))
        (is (= "a" (:chave (:atual r))))
        (is (= ["b" "c"] (chaves (:proximas r))))
        (is (= [] (:anteriores r)))))
    (testing "as anteriores vem do HISTORICO da materia, em ordem de passagem, sem a atual"
      (let [r (rito-de {:estado-inicial "a" :estados bifurca :transicoes t-bifurca :atual "d"
                        :tramitacao [(tr "a" "c") (tr "c" "d")]})]
        (is (false? (:ordem-unica r)))
        (is (= ["a" "c"] (chaves (:anteriores r))))
        (is (= ["fim"] (chaves (:proximas r))))))
    (testing "historico cortado pelo teto: nao afirma 'por onde passou' — anteriores ausente, nunca parcial"
      (let [r (rito-de {:estado-inicial "a" :estados bifurca :transicoes t-bifurca :atual "d"
                        :tramitacao [(tr "a" "c") (tr "c" "d")] :tramitacao-truncado true})]
        (is (nil? (:anteriores r)))))
    (testing "etapa do historico que o rito nao declara nao vira rotulo inventado: sai da lista"
      (let [r (rito-de {:estado-inicial "a" :estados bifurca :transicoes t-bifurca :atual "d"
                        :tramitacao [(tr "fantasma" "c") (tr "c" "d")]})]
        (is (= ["c"] (chaves (:anteriores r))))))))

(deftest ordem-declarada-mas-rito-permite-pular-etapa
  (testing "uma transicao que salta etapa (a -> c) tira a garantia de que 'antes da atual' = 'ja passou': sem linha"
    (let [estados [(est "a" "A" 1) (est "b" "B" 2) (est "c" "C" 3) (est "fim" "Fim" 4 true)]
          t [(tr "a" "b") (tr "b" "c") (tr "a" "c") (tr "c" "fim")]
          r (rito-de {:estado-inicial "a" :estados estados :transicoes t :atual "b"})]
      (is (false? (:ordem-unica r)))
      (is (= [] (:etapas r))))))

(deftest ordem-declarada-mas-sem-elo-entre-etapas
  (testing "etapa da linha que a engine nao liga a anterior (nao ha' a -> b): sem linha"
    (let [estados [(est "a" "A" 1) (est "b" "B" 2) (est "fim" "Fim" 3 true)]
          t [(tr "a" "fim") (tr "b" "fim")]
          r (rito-de {:estado-inicial "a" :estados estados :transicoes t :atual "a"})]
      (is (false? (:ordem-unica r)))
      (is (= [] (:etapas r))))))

(deftest devolucao-nao-quebra-a-linha
  (testing "voltar a etapa anterior (devolucao) e' ordem declarada, nao ambiguidade"
    (let [t (conj transicoes-linear (tr "instrucao" "entrada"))
          r (rito-de {:transicoes t})]
      (is (true? (:ordem-unica r)))
      (is (= ["entrada" "instrucao" "plenario_unico"] (chaves (:etapas r)))))))

(deftest ciclo-sem-ordem-declarada-nao-tem-linha
  (let [estados [(est "a" "A" 0) (est "b" "B" 0) (est "fim" "Fim" 0 true)]
        t [(tr "a" "b") (tr "b" "a") (tr "b" "fim")]
        r (rito-de {:estado-inicial "a" :estados estados :transicoes t :atual "b"})]
    (is (false? (:ordem-unica r)))
    (is (= [] (:etapas r)))
    (is (= ["a" "fim"] (chaves (:proximas r))))))

(deftest atual-que-o-rito-nao-declara
  (testing "materia anterior ao rito, ou rito trocado: a linha existe, mas nao ha' etapa atual para marcar"
    (let [r (rito-de {:atual "estado_antigo"})]
      (is (nil? (:atual r)))
      (is (= [] (:proximas r))))))

(deftest sem-rito-nao-ha-resposta
  (is (nil? (rito/rito-da-materia {:estado-inicial nil :estados [] :transicoes [] :atual "x"
                                   :tramitacao [] :tramitacao-truncado false})))
  (is (nil? (rito/rito-da-materia nil))))

(deftest rito-de-uma-etapa-so
  (testing "rito de um estado so': a linha de uma etapa nao e' ambigua"
    (let [r (rito/rito-da-materia {:estado-inicial "unica" :estados [(est "unica" "Única" 0)] :transicoes []
                                   :atual "unica" :tramitacao [] :tramitacao-truncado false})]
      (is (true? (:ordem-unica r)))
      (is (= ["unica"] (chaves (:etapas r)))))))

;; ---- o significado de `ordem` (migration 20261005000261): 0 = nao declarada; > 0 = posicao da etapa na linha do
;; rito, UNICA entre as etapas (nao terminais). Desfecho (terminal) nao entra na linha, entao a `ordem` dele nao conta.

(deftest ordem-declarada-vence-quando-a-topologia-nao-da-linha
  (let [t-devolucao (conj transicoes-linear (tr "plenario_unico" "instrucao"))]
    (testing "com devolucao (ciclo) a topologia nao tem ordem unica: SO' a ordem declarada desenha a linha"
      (let [sem-ordem (rito-de {:estados (mapv #(assoc % :ordem 0) estados-linear) :transicoes t-devolucao})
            com-ordem (rito-de {:transicoes t-devolucao})]
        (is (false? (:ordem-unica sem-ordem)) "sem ordem declarada o ciclo nao tem linha")
        (is (= [] (:etapas sem-ordem)))
        (is (true? (:ordem-unica com-ordem)))
        (is (= ["entrada" "instrucao" "plenario_unico"] (chaves (:etapas com-ordem))))))
    (testing "a ordem declarada decide a ORDEM DE LEITURA: a linha sai pela `ordem`, nao pela ordem em que as etapas chegam"
      (let [embaralhados [(est "plenario_unico" "Plenário único" 3) (est "entrada" "Entrada" 1)
                          (est "promulgada" "Promulgada" 4 true) (est "instrucao" "Instrução" 2)
                          (est "rejeitada" "Rejeitada" 5 true)]
            r (rito-de {:estados embaralhados :transicoes t-devolucao})]
        (is (= ["entrada" "instrucao" "plenario_unico"] (chaves (:etapas r))))))))

(deftest ordem-repetida-entre-etapas-nao-conta-como-declarada
  (testing "duas etapas com a mesma ordem > 0: a coluna nao distingue, vale a topologia (que aqui e' unica)"
    (let [repetidos [(est "entrada" "Entrada" 1) (est "instrucao" "Instrução" 2) (est "plenario_unico" "Plenário único" 2)
                     (est "promulgada" "Promulgada" 3 true) (est "rejeitada" "Rejeitada" 4 true)]
          r (rito-de {:estados repetidos})]
      (is (true? (:ordem-unica r)))
      (is (= ["entrada" "instrucao" "plenario_unico"] (chaves (:etapas r))))))
  (testing "o mesmo vale com devolucao: sem ordem distinta e sem topologia unica, nao ha' linha"
    (let [repetidos [(est "entrada" "Entrada" 1) (est "instrucao" "Instrução" 2) (est "plenario_unico" "Plenário único" 2)
                     (est "promulgada" "Promulgada" 3 true) (est "rejeitada" "Rejeitada" 4 true)]
          r (rito-de {:estados repetidos :transicoes (conj transicoes-linear (tr "plenario_unico" "instrucao"))})]
      (is (false? (:ordem-unica r)))
      (is (= [] (:etapas r))))))

(deftest ordem-dos-desfechos-nao-atrapalha-a-linha
  (testing "terminais com a mesma ordem (ou 0) nao invalidam a ordem declarada das etapas"
    (let [estados [(est "entrada" "Entrada" 1) (est "instrucao" "Instrução" 2) (est "plenario_unico" "Plenário único" 3)
                   (est "promulgada" "Promulgada" 0 true) (est "rejeitada" "Rejeitada" 0 true)]
          r (rito-de {:estados estados :transicoes (conj transicoes-linear (tr "plenario_unico" "instrucao"))})]
      (is (true? (:ordem-unica r)) "a linha vem da ordem declarada: so' ela resolve o ciclo")
      (is (= ["entrada" "instrucao" "plenario_unico"] (chaves (:etapas r)))))))

(deftest ordem-repetida-aponta-quem-repete
  (testing "so' etapa (nao terminal) com ordem > 0 conta; 0 e' 'nao declarada' e se repete a vontade"
    (is (= [] (rito/ordem-repetida estados-linear)))
    (is (= [] (rito/ordem-repetida [(est "a" "A" 0) (est "b" "B" 0) (est "fim" "Fim" 0 true)])))
    (is (= [] (rito/ordem-repetida [(est "a" "A" 1) (est "fim" "Fim" 1 true) (est "arq" "Arq" 1 true)]))
        "terminal nao entra na linha: a ordem dele nao colide com a de etapa nenhuma")
    (is (= [] (rito/ordem-repetida [{:chave "a" :nome "A" :terminal false} {:chave "b" :nome "B" :terminal false}]))
        "ordem ausente = nao declarada")
    (is (= [{:ordem 2 :chaves ["instrucao" "plenario_unico"]}]
           (rito/ordem-repetida [(est "entrada" "Entrada" 1) (est "instrucao" "Instrução" 2)
                                 (est "plenario_unico" "Plenário único" 2) (est "promulgada" "Promulgada" 3 true)])))
    (is (= [{:ordem 1 :chaves ["a" "b"]} {:ordem 2 :chaves ["c" "d"]}]
           (rito/ordem-repetida [(est "a" "A" 1) (est "b" "B" 1) (est "c" "C" 2) (est "d" "D" 2)])))))

;; o rito ordinario REAL da Casa de demonstracao (demo/acervo.clj `estados-rito` + `transicoes-rito`): dois
;; desfechos terminais e uma saida de arquivamento logo no inicio — o grafo NAO e' uma reta, mas as etapas sao.
(def ^:private estados-demo
  [(est "protocolada" "Protocolada" 1) (est "em_comissoes" "Em Comissões" 2) (est "aguardando_pauta" "Aguardando Pauta" 3)
   (est "em_pauta" "Em Pauta" 4) (est "aprovada" "Aprovada" 5 true) (est "arquivada" "Arquivada" 6 true)])

(def ^:private transicoes-demo
  [(tr "protocolada" "em_comissoes") (tr "protocolada" "arquivada") (tr "em_comissoes" "aguardando_pauta")
   (tr "aguardando_pauta" "em_pauta") (tr "em_pauta" "aprovada") (tr "em_pauta" "arquivada")])

(deftest rito-real-da-demo
  (let [de-demo (fn [atual] (rito-de {:estado-inicial "protocolada" :estados estados-demo
                                      :transicoes transicoes-demo :atual atual}))]
    (testing "em andamento: a linha e' protocolada ... em_pauta; aprovada e arquivada sao desfechos, nao passos"
      (let [r (de-demo "aguardando_pauta")]
        (is (true? (:ordem-unica r)))
        (is (= ["protocolada" "em_comissoes" "aguardando_pauta" "em_pauta"] (chaves (:etapas r))))
        (is (= ["em_pauta"] (chaves (:proximas r))))))
    (testing "aprovada: a linha fecha na aprovacao, sem a arquivada"
      (is (= ["protocolada" "em_comissoes" "aguardando_pauta" "em_pauta" "aprovada"] (chaves (:etapas (de-demo "aprovada"))))))
    (testing "arquivada: fecha no arquivamento, sem a aprovada"
      (is (= ["protocolada" "em_comissoes" "aguardando_pauta" "em_pauta" "arquivada"] (chaves (:etapas (de-demo "arquivada"))))))
    (testing "em_pauta tem duas saidas possiveis: as duas aparecem como proximas"
      (is (= ["aprovada" "arquivada"] (chaves (:proximas (de-demo "em_pauta"))))))))
