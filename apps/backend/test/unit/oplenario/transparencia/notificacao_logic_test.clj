(ns oplenario.transparencia.notificacao-logic-test
  "UNIT (puro, sem PG) — F7 E2: renderizacao do conteudo + chave de idempotencia deterministica do fan-out."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oplenario.transparencia.logic.notificacao :as logic]))

(def ^:private materia
  {:tipo "projeto_lei" :ano 2026 :sequencial 12 :ementa "Dispoe sobre a arborizacao urbana." :estado "em_pauta"})

(deftest chave-idempotencia-e-deterministica
  (let [tid "11111111-1111-1111-1111-111111111111"
        dst "22222222-2222-2222-2222-222222222222"]
    (is (= (logic/chave-idempotencia tid dst) (logic/chave-idempotencia tid dst))
        "mesma (transicao, destinatario) -> mesma chave (dedup no-op em replay)")
    (is (not= (logic/chave-idempotencia tid dst) (logic/chave-idempotencia tid "outro"))
        "destinatarios diferentes -> chaves diferentes (uma entrega por seguidor)")
    (is (not= (logic/chave-idempotencia "outra" dst) (logic/chave-idempotencia tid dst))
        "transicoes diferentes -> chaves diferentes (uma entrega por transicao)")))

(deftest renderizar-monta-assunto-e-corpo-de-info-publica
  (let [{:keys [assunto corpo]} (logic/renderizar materia "sancionada")]
    (is (str/includes? assunto "PL 12/2026") "assunto tem o identificador humano da materia (sigla da especie, como o portal)")
    (is (str/includes? corpo "arborizacao urbana") "corpo tem a ementa (info publica)")
    (is (str/includes? corpo "Sancionada") "corpo tem a NOVA fase (do evento, autoritativa), humanizada")
    (is (not (str/includes? corpo "2222")) "corpo NAO tem o destinatario (entrega 1:1, sem PII no texto)")))

(deftest renderizar-escreve-para-gente-nunca-a-chave-do-banco
  (let [pl16 {:tipo "projeto_lei" :ano 2026 :sequencial 16 :ementa "Dispõe sobre hortas." :estado "em_pauta"}
        {:keys [assunto corpo]} (logic/renderizar pl16 "em_pauta")]
    (is (= "Movimentação: PL 16/2026" assunto) "sigla como o portal mostra, nunca PROJETO_LEI, e com acento")
    (is (str/includes? corpo "A matéria PL 16/2026 que você acompanha teve movimentação."))
    (is (str/includes? corpo "Nova fase: Em pauta") "a fase pelo rótulo do portal, nunca a chave em_pauta")
    (is (not (re-find #"[a-z]+_[a-z]+|PROJETO_LEI" (str assunto "\n" (str/replace corpo #"Ementa:[^\n]*" "")))) "nenhuma chave com sublinhado no texto")))

(deftest renderizar-cobre-as-especies-e-as-fases-do-portal
  (doseq [[tipo sigla] {"projeto_lei" "PL" "projeto_lei_complementar" "PLC" "projeto_resolucao" "PR"
                        "projeto_decreto_legislativo" "PDL" "proposta_emenda_lom" "PELOM"
                        "indicacao" "IND" "requerimento" "REQ" "mocao" "MOÇ"}]
    (is (= (str "Movimentação: " sigla " 3/2026")
           (:assunto (logic/renderizar {:tipo tipo :ano 2026 :sequencial 3 :ementa "x"} "protocolada")))
        (str "espécie " tipo)))
  (doseq [[chave rotulo] {"protocolada" "Protocolado" "em_comissoes" "Em comissões" "primeiro_turno" "Em 1º turno"
                          "segundo_turno" "Em 2º turno" "em_sancao" "Em sanção" "aprovada" "Aprovado"
                          "arquivada" "Arquivada"}]
    (is (str/includes? (:corpo (logic/renderizar materia chave)) (str "Nova fase: " rotulo "\n")) (str "fase " chave))))

(deftest renderizar-fase-fora-do-vocabulario-e-humanizada-nunca-a-chave
  (is (str/includes? (:corpo (logic/renderizar materia "aguardando_pauta")) "Nova fase: Aguardando pauta\n")
      "fase de template por Câmara que o mapa não conhece: troca _ por espaço e sobe a inicial")
  (is (= "Movimentação: Projeto especial 7/2026"
         (:assunto (logic/renderizar {:tipo "projeto_especial" :ano 2026 :sequencial 7 :ementa "x"} "protocolada")))
      "espécie desconhecida também é humanizada, não impressa crua"))

(deftest renderizar-nao-promete-o-que-o-portal-nao-mostra
  (let [{:keys [corpo]} (logic/renderizar materia "sancionada")]
    (is (not (re-find #"(?i)tramita[cç][aã]o completa|toda a tramita" corpo))
        "o portal publico mostra a faixa do estado da materia, nao a tramitacao inteira: a notificacao nao promete mais que isso")
    (is (str/includes? corpo "no portal da Câmara") "o corpo ainda leva o cidadao ao portal")))
