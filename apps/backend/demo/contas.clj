(ns contas
  "Semente NARRATIVA da demo — o JULGAMENTO DAS CONTAS (ADR-0021 Parte B), na mesma familia de `casa`/`acervo`/
  `comunicados` (mesmo contrato: `semear!` recebe um `sistema` Component JA' BOOTADO, a Casa `ente` e as `identidades`
  de `casa/semear!`).

  POR QUE EXISTE: sem ela `/contas` e o portal abrem vazios. Aqui a Casa ganha as tres situacoes que a tela precisa mostrar:
    1. as contas do Prefeito de 2024 PRONTAS PARA A PAUTA: parecer previo 'favoravel com ressalvas', o PDL protocolado
       pela Comissao de Financas (o MESMO caminho da tela, `controllers/registrar-prestacao!`), a notificacao ha' 20 dias
       (prazo de defesa congelado e ja' vencido) e a defesa escrita juntada como PDF;
    2. as contas do Prefeito de 2023 JULGADAS, parecer mantido: uma votacao NOMINAL de verdade sobre o PDL, 2/3 dos
       membros, que nao alcancou os 2/3 — o encerramento grava o resultado na prestacao (a mesma tx da producao). A
       votacao nao tem sessao (o julgamento e' anterior a esta demo): nao entra na pauta de nenhuma sessao semeada nem
       gera evento de plenario;
    3. as contas da Mesa de 2024 em acompanhamento (processo e situacao no TCE).

  IDEMPOTENCIA: a prestacao e' achada por (tipo, exercicio) — o UNIQUE da tabela; cada passo (notificacao, documentos,
  votacao) so' roda se ainda nao aconteceu. Rodar duas vezes RELE em vez de duplicar."
  (:require [clojure.string :as str]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.components.repositorio-contas :as repo-contas]
            [oplenario.legislativo.controllers :as controllers]
            [oplenario.legislativo.logic.contas :as logic-contas])
  (:import (java.nio.charset StandardCharsets)
           (java.time LocalDate ZoneId)))

(set! *warn-on-reflection* true)

(def ^:private zona (ZoneId/of "America/Fortaleza"))

;; ---------- um PDF pequeno, gerado aqui (uma pagina, texto Helvetica) ----------

(defn- escapar-pdf [s] (str/replace s #"([\\()])" "\\\\$1"))

(defn pdf-minimo
  "Um PDF 1.4 valido de uma pagina com as `linhas` (ASCII). As posicoes do xref sao calculadas sobre os bytes."
  ^bytes [linhas]
  (let [conteudo (str "BT /F1 12 Tf 72 760 Td 16 TL\n"
                      (apply str (map #(str "(" (escapar-pdf %) ") Tj T*\n") linhas))
                      "ET")
        objetos ["<< /Type /Catalog /Pages 2 0 R >>"
                 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
                 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>"
                 (str "<< /Length " (count conteudo) " >>\nstream\n" conteudo "\nendstream")
                 "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
        cabecalho "%PDF-1.4\n"
        [corpo offsets] (reduce (fn [[acc offs] [i o]]
                                  [(str acc (inc i) " 0 obj\n" o "\nendobj\n") (conj offs (count acc))])
                                [cabecalho []] (map-indexed vector objetos))
        xref (str "xref\n0 " (inc (count objetos)) "\n0000000000 65535 f \n"
                  (apply str (map #(format "%010d 00000 n \n" %) offsets)))]
    (.getBytes (str corpo xref "trailer\n<< /Size " (inc (count objetos)) " /Root 1 0 R >>\nstartxref\n" (count corpo)
                    "\n%%EOF\n")
               StandardCharsets/ISO_8859_1)))

;; ---------- passos ----------

(defn- ator [ente identidade-id] {:ente-id ente :identidade-id identidade-id :tipo-vinculo "servidor" :papeis #{"secretario"}})

(defn- a-prestacao [repo ente tipo exercicio]
  (first (filter #(and (= tipo (:tipo %)) (= exercicio (:exercicio %))) (repo-contas/prestacoes repo ente))))

(defn- registrar-se-faltar!
  [{:keys [repo repo-cad hoje]} sec m]
  (let [ente (:ente-id sec)]
    (or (a-prestacao repo ente (:tipo m) (:exercicio m))
        (controllers/registrar-prestacao! repo #(repo-cad/uf-e-municipio repo-cad %)
                                          #(repo-cad/comissoes-vigentes repo-cad % hoje) sec hoje m))))

(defn- notificar-se-faltar! [{:keys [repo hoje]} sec p dias-atras meio]
  (if (:notificado-em p)
    p
    (:prestacao (controllers/notificar-prestacao! repo sec hoje (:id p)
                                                  {:notificado-em (.minusDays ^LocalDate hoje (long dias-atras))
                                                   :meio meio}))))

(defn- anexar-se-faltar! [{:keys [repo objeto-store]} sec p tipo nome linhas]
  (when-not (some #(= tipo (:tipo %)) (:documentos p))
    (controllers/anexar-documento-de-contas! repo objeto-store sec (:id p) tipo
                                             {:nome nome :tipo-midia "application/pdf" :conteudo (pdf-minimo linhas)})))

(defn- julgar-se-faltar!
  "A votacao NOMINAL do PDL pelo caminho do Repo (abrir com a regra da classe, votar, encerrar com a composicao real):
  `sim` votos pela rejeicao, os demais contra — sem chegar aos 2/3, o parecer prevalece."
  [{:keys [repo repo-cad hoje]} ente p sim]
  (when-not (:resultado p)
    (let [roster (filterv #(= "vigente" (:estado-mandato %)) (repo-cad/roster-da-casa repo-cad ente hoje))
          base (repo-cad/membros-da-casa repo-cad ente hoje)
          vid (random-uuid)]
      (repo-leg/abrir-votacao! repo ente {:id vid :objeto-tipo "proposicao" :objeto-id (get-in p [:proposicao :id])
                                          :modalidade "nominal" :quorum-tipo "maioria_qualificada_2_3"
                                          :sessao-id nil :created-by nil})
      (doseq [[v voto] (map vector roster (concat (repeat sim "sim") (repeat "nao")))]
        (repo-leg/registrar-voto! repo ente {:id (random-uuid) :votacao-id vid :vereador-id (:vereador-id v)
                                             :voto voto :created-by nil}))
      (repo-leg/encerrar-votacao! repo ente {:id vid :base-membros base :updated-by nil :lock-version 0}))))

(defn- comissao-de-financas
  "A Comissao de Financas e Orcamento da Casa (ou, sem ela, a primeira comissao permanente vigente)."
  [repo-cad ente hoje]
  (let [cs (repo-cad/comissoes-vigentes repo-cad ente hoje)]
    (or (first (filter #(re-find #"(?i)finan" (:nome %)) cs)) (first cs))))

(defn semear!
  "Semeia (ou rele) as prestacoes de contas da Casa `ente`. Devolve {:governo-2024 id :governo-2023 id :mesa-2024 id
  :estados {exercicio-tipo estado}}."
  [sistema ente identidades]
  (let [repo (:repo-legislativo sistema)
        repo-cad (:repo-cadastros sistema)
        hoje (tempo/hoje (tempo/relogio-sistema) zona)
        deps {:repo repo :repo-cad repo-cad :hoje hoje :objeto-store (:objeto-store sistema)}
        sec (ator ente (:secretaria identidades))
        financas (comissao-de-financas repo-cad ente hoje)
        _ (when-not financas
            (throw (ex-info "contas/semear!: a Casa nao tem comissao vigente — rode casa/semear! primeiro" {:ente ente})))
        ;; 1. contas do Prefeito de 2024: prontas para a pauta
        g24 (registrar-se-faltar! deps sec {:tipo "governo_prefeito" :exercicio 2024
                                            :responsavel "Francisco Almeida Rocha"
                                            :recebida-em (.minusDays hoje 45) :processo-tce "08214/2025-3"
                                            :parecer-previo "favoravel_com_ressalvas"
                                            :comissao-autora-id (:id financas)})
        _ (anexar-se-faltar! deps sec g24 "parecer_previo" "parecer-previo-tce-ce-2024.pdf"
                             ["Tribunal de Contas do Estado do Ceara" "Parecer previo - Contas de Governo 2024"
                              "Processo 08214/2025-3" "Conclusao: favoravel a aprovacao, com ressalvas."])
        g24 (notificar-se-faltar! deps sec (repo-contas/prestacao repo ente (:id g24)) 20
                                  "Ofício entregue em mãos ao ex-Prefeito")
        _ (anexar-se-faltar! deps sec g24 "defesa" "defesa-escrita-contas-2024.pdf"
                             ["Defesa escrita - Contas de Governo 2024"
                              "O responsavel apresenta esclarecimentos sobre as ressalvas apontadas pelo TCE."])
        ;; 2. contas do Prefeito de 2023: julgadas, o parecer prevaleceu (sem os 2/3)
        g23 (registrar-se-faltar! deps sec {:tipo "governo_prefeito" :exercicio 2023
                                            :responsavel "Francisco Almeida Rocha"
                                            :recebida-em (.minusDays hoje 300) :processo-tce "06102/2024-8"
                                            :parecer-previo "favoravel"
                                            :comissao-autora-id (:id financas)})
        g23 (notificar-se-faltar! deps sec (repo-contas/prestacao repo ente (:id g23)) 270 "AR dos Correios")
        _ (julgar-se-faltar! deps ente g23 9)
        ;; 3. contas da Mesa de 2024: so' acompanhamento
        m24 (registrar-se-faltar! deps sec {:tipo "gestao_camara" :exercicio 2024
                                            :responsavel "Mesa Diretora (biênio 2023-2024)"
                                            :recebida-em (.minusDays hoje 60) :processo-tce "08990/2025-1"
                                            :situacao-tce "Em instrução na 2ª Diretoria de Controle Externo"})
        ids {:governo-2024 (:id g24) :governo-2023 (:id g23) :mesa-2024 (:id m24)}]
    (assoc ids :estados (into {} (map (fn [[k id]] [k (logic-contas/estado (repo-contas/prestacao repo ente id) hoje)]))
                              ids))))
