(ns oplenario.kernel.tempo-test
  "Relogio injetado (§22.6 'tempo como coordenada de primeira classe'): producao usa o
  relogio do sistema; teste crava o instante. O motor ja consome 'agora' como valor (runtime),
  o kernel so o PRODUZ de forma injetavel.

  A segunda metade do ns cobre a ARITMETICA PURA de intervalos de data civil (I-5 fatia 2):
  `normalizar-intervalos` e `subtrair-intervalos` sobre `{:inicio LocalDate :fim (maybe LocalDate)}`
  INCLUSIVO nos dois lados, `fim` nil = em aberto. Sem banco, sem relogio: aqui nao ha 'mandato'
  nem 'licenca' — o kernel nao conhece modulo (kernel-sem-modulo), so' intervalos anonimos."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.kernel.tempo :as tempo])
  (:import (java.time Instant LocalDate ZoneId)))

(defn- d
  "Atalho de legibilidade: \"2026-01-01\" -> LocalDate."
  ^LocalDate [s]
  (LocalDate/parse s))

(defn- iv
  "Intervalo inclusivo; `fim` omitido = em aberto (nil)."
  ([inicio] {:inicio (d inicio) :fim nil})
  ([inicio fim] {:inicio (d inicio) :fim (d fim)}))

(deftest relogio-fixo-devolve-o-instante-cravado
  (let [t (Instant/parse "2026-06-26T12:00:00Z")
        r (tempo/relogio-fixo t)]
    (is (= t (tempo/agora r)) "relogio fixo devolve exatamente o instante cravado")))

(deftest relogio-sistema-devolve-um-instant
  (let [r (tempo/relogio-sistema)]
    (is (instance? Instant (tempo/agora r)) "relogio do sistema devolve um java.time.Instant")))

(deftest hoje-projeta-o-instante-em-localdate-na-zona
  ;; 2026-06-26T02:00:00Z e' 2026-06-25 em America/Fortaleza (UTC-3) — prova que a zona conta.
  (let [r (tempo/relogio-fixo (Instant/parse "2026-06-26T02:00:00Z"))]
    (is (= (LocalDate/parse "2026-06-25")
           (tempo/hoje r (ZoneId/of "America/Fortaleza")))
        "hoje converte o instante na zona dada (nao em UTC)")))

;; ---------------------------------------------------------------------------
;; Aritmetica de intervalos de data civil (I-5 fatia 2)
;; ---------------------------------------------------------------------------

(deftest subtrair-buraco-no-meio-parte-a-janela-em-duas
  (is (= [(iv "2026-01-01" "2026-05-31")
          (iv "2026-07-01" "2026-12-31")]
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2026-06-01" "2026-06-30")]))
      "buraco interno gera DUAS partes, cada uma recuada/avancada um dia (bordas inclusivas)"))

(deftest subtrair-buraco-que-cobre-a-janela-inteira-devolve-vazio
  (is (= []
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2025-12-01" "2027-01-31")]))
      "buraco que engloba a janela nao deixa resto")
  (is (= []
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2026-01-01" "2026-12-31")]))
      "buraco com as MESMAS bordas inclusivas tambem zera — nao sobra dia de borda"))

(deftest subtrair-buraco-aberto-a-direita-fecha-a-janela-no-dia-anterior
  (is (= [(iv "2026-01-01" "2026-05-31")]
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2026-06-01")]))
      "buraco com fim nil (em aberto) corta tudo dali para frente; a janela fecha em 05-31"))

(deftest subtrair-buraco-fora-da-janela-nao-altera-nada
  (is (= [(iv "2026-01-01" "2026-12-31")]
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2027-03-01" "2027-04-01")]))
      "buraco depois da janela nao a toca")
  (is (= [(iv "2026-01-01" "2026-12-31")]
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2027-01-01" "2027-01-05")]))
      "buraco ADJACENTE (comeca no dia seguinte ao fim) nao sobrepoe — nada muda")
  (is (= [(iv "2026-01-01" "2026-12-31")]
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2025-11-01" "2025-12-31")]))
      "buraco adjacente ANTES do inicio tambem nao sobrepoe"))

(deftest subtrair-de-janela-aberta-a-direita-preserva-o-fim-nil
  (is (= [(iv "2026-01-01" "2026-02-28")
          (iv "2026-04-01")]
         (tempo/subtrair-intervalos [(iv "2026-01-01")]
                                    [(iv "2026-03-01" "2026-03-31")]))
      "a parte da direita de uma janela em aberto continua em aberto (fim nil), nunca vira data"))

(deftest buraco-que-toca-a-borda-inclusiva-corta-um-dia-so
  (is (= [(iv "2026-01-01" "2026-12-30")]
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2026-12-31" "2026-12-31")]))
      "buraco de UM dia sobre o ultimo dia inclusivo tira exatamente esse dia")
  (is (= [(iv "2026-01-02" "2026-12-31")]
         (tempo/subtrair-intervalos [(iv "2026-01-01" "2026-12-31")]
                                    [(iv "2026-01-01" "2026-01-01")]))
      "idem na borda esquerda"))

(deftest normalizar-funde-intervalos-adjacentes-e-sobrepostos
  (is (= [(iv "2026-01-01" "2026-02-28")
          (iv "2026-05-01" "2026-05-31")
          (iv "2026-09-01")]
         (tempo/normalizar-intervalos [(iv "2026-05-10" "2026-05-31")
                                       (iv "2026-02-01" "2026-02-28")
                                       (iv "2026-09-01")
                                       (iv "2026-01-01" "2026-01-31")
                                       (iv "2026-05-01" "2026-05-20")]))
      "ordena por inicio, funde ADJACENTES (01-31 + 02-01) e SOBREPOSTOS (05-01..05-20 + 05-10..05-31),
       e preserva o vao real entre fevereiro e maio")
  (is (= [(iv "2026-01-01")]
         (tempo/normalizar-intervalos [(iv "2026-01-01")
                                       (iv "2026-03-01" "2026-03-31")]))
      "intervalo em aberto absorve qualquer intervalo posterior"))

(deftest normalizar-descarta-intervalo-de-inicio-posterior-ao-fim
  (is (= [(iv "2026-01-01" "2026-01-31")]
         (tempo/normalizar-intervalos [(iv "2026-05-01" "2026-04-30")
                                       (iv "2026-01-01" "2026-01-31")]))
      "intervalo vazio (inicio > fim) e' descartado, nao invertido")
  (is (= [(iv "2026-01-01" "2026-01-01")]
         (tempo/normalizar-intervalos [(iv "2026-01-01" "2026-01-01")]))
      "intervalo de UM dia (inicio = fim) NAO e' vazio — bordas sao inclusivas")
  (is (= [] (tempo/normalizar-intervalos []))
      "lista vazia continua vazia"))

(deftest zona-civil-padrao-e-a-mesma-usada-pelo-seam-de-ficha
  ;; O seam `ficha-vereador-publica` (e `membros-da-casa`) em `rotas.clj` carregava o literal
  ;; (ZoneId/of "America/Fortaleza"); a constante do kernel tem de ser IDENTICA em valor e efeito,
  ;; senao a troca da fatia 2 mudaria a data civil que decide mandato/comissao vigente.
  (is (= (ZoneId/of "America/Fortaleza") tempo/zona-civil-padrao)
      "a constante do kernel e' exatamente a zona que o host usava literal")
  (let [r (tempo/relogio-fixo (Instant/parse "2026-06-26T02:00:00Z"))]
    (is (= (tempo/hoje r (ZoneId/of "America/Fortaleza"))
           (tempo/hoje r tempo/zona-civil-padrao))
        "mesma data civil derivada do mesmo instante — troca sem mudanca de comportamento")))
