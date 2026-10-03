(ns audiencias
  "Semente NARRATIVA da demo — as AUDIENCIAS PUBLICAS (ADR-0021 Parte A), na mesma familia de `casa`/`acervo`/`sessoes`/
  `participacao`/`comunicados` (mesmo contrato: `semear!` recebe um `sistema` Component JA' BOOTADO, a Casa `ente` e as
  `identidades` que `casa/semear!` devolveu).

  POR QUE EXISTE: sem ela o portal abre 'Audiencias publicas' vazio e a Mesa nunca ve a tela de conduzir as falas. Aqui
  a Casa ganha, pela Comissao de Financas e Orcamento (uma das 3 permanentes de `casa`):
    1. uma audiencia TEMATICA AGENDADA para daqui a 7 dias, com 2 inscricoes na fila: uma feita pela Mesa
       (presencial, falando por uma associacao de moradores) e uma da CIDADA da demo pelo portal (o nome vem da
       identidade dela — a Roberta, que entra pelo gov.br simulado);
    2. a audiencia de METAS FISCAIS do 1º quadrimestre (`2026-Q1`, LRF art. 9 §4) ja' ENCERRADA, com 2 cidadaos que
       falaram e a ata publicada — a prova que a regra do motor (fatia 3) procura.
  Tudo pelo MESMO caminho da tela (`controllers.audiencia` + os Repos, com os seams montados aqui sobre os Repos do
  sistema), nunca por INSERT a mao: protocolo do contador gapless, ordem, maquina da inscricao.

  IDEMPOTENCIA: as duas sessoes tem UUID FIXO; cada passo confere o estado antes de agir (a inscricao pelo nome / pela
  identidade, a sessao pelo estado, a ata pela existencia) — rodar duas vezes RELE em vez de duplicar, e uma corrida
  interrompida no meio continua de onde parou."
  (:require [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.sessoes.components.repositorio :as repo-s]
            [oplenario.sessoes.components.repositorio-audiencia :as repo-aud]
            [oplenario.sessoes.controllers :as controllers]
            [oplenario.sessoes.controllers.audiencia :as controllers-aud])
  (:import (java.time LocalDate ZoneId)))

(set! *warn-on-reflection* true)

(def id-tematica #uuid "10000000-0000-0000-0000-000000000220")
(def id-metas-fiscais #uuid "10000000-0000-0000-0000-000000000221")

(def ^:private sessao-legislativa-id
  "A mesma sessao legislativa de `sessoes` (forward-ref, sem FK — §22.10)."
  #uuid "10000000-0000-0000-0000-000000000201")

(def ^:private fuso (ZoneId/of "America/Fortaleza"))

(def comissao-promotora "Comissão de Finanças e Orçamento")

(def tema-tematica "Saúde nos bairros: os postos de saúde e os agentes comunitários")
(def tema-metas "Avaliação do cumprimento das metas fiscais — 1º quadrimestre de 2026")

(def inscricao-presencial
  {:nome "José Raimundo Lima" :fala-como "entidade" :entidade "Associação de Moradores do Bairro Centro"
   :tema "Horário de atendimento do posto do Centro"})

(def inscricao-da-cidada
  {:fala-como "individual" :entidade nil :tema "Falta de agentes comunitários na minha rua"})

(def falaram-nas-metas
  [[{:nome "Francisca Helena Sousa" :fala-como "conselho_movimento" :entidade "Conselho Municipal de Saúde"
     :tema "O gasto com saúde no quadrimestre"} 280]
   [{:nome "Raimundo Nonato Alves" :fala-como "individual" :entidade nil
     :tema "A arrecadação do IPTU e a dívida ativa"} 205]])

(def ata-metas
  (str "ATA DA AUDIÊNCIA PÚBLICA DE AVALIAÇÃO DAS METAS FISCAIS — 1º QUADRIMESTRE DE 2026\n\n"
       "Aos vinte e sete dias do mês de maio de dois mil e vinte e seis, no Plenário da Câmara Municipal, reuniu-se a "
       "Comissão de Finanças e Orçamento em audiência pública, nos termos do art. 9º, § 4º, da Lei Complementar nº "
       "101/2000, para a demonstração e a avaliação do cumprimento das metas fiscais do primeiro quadrimestre de 2026.\n\n"
       "O representante da Secretaria Municipal de Finanças apresentou o relatório resumido da execução orçamentária e "
       "o relatório de gestão fiscal do período: a receita corrente líquida, as despesas com pessoal dentro do limite "
       "prudencial e o resultado primário acima da meta da LDO.\n\n"
       "Usaram a palavra os cidadãos inscritos: a Sra. Francisca Helena Sousa, pelo Conselho Municipal de Saúde, sobre "
       "a aplicação mínima em saúde no quadrimestre; e o Sr. Raimundo Nonato Alves, sobre a arrecadação do IPTU e a "
       "cobrança da dívida ativa. As perguntas foram respondidas pelo representante do Executivo.\n\n"
       "Nada mais havendo a tratar, o Presidente da Comissão encerrou a audiência, da qual se lavrou a presente ata."))

;; ---------- os seams (os mesmos que o host monta em `rotas.clj`) ----------

(defn- deps [sistema]
  (let [repo-cadastros (:repo-cadastros sistema)
        repo-identidade (:repo-identidade sistema)
        hoje #(tempo/hoje (tempo/relogio-sistema) fuso)]
    {:repo-sessoes (:repo-sessoes sistema)
     :relogio (tempo/relogio-sistema)
     :comissoes-vigentes (fn [ente] (repo-cad/comissoes-vigentes repo-cadastros ente (hoje)))
     :nomes-de-comissoes (fn [ente ids] (repo-cad/nomes-de-comissoes repo-cadastros ente ids))
     ;; a demo nao liga materia as audiencias: sem proposicao, o seam nunca e' consultado
     :rotular-proposicoes (fn [_ _] {})
     :nome-da-identidade (fn [iid] (:nome (repo-id/nome-por-id repo-identidade iid)))}))

(defn- ator [ente identidade-id tipo papeis]
  {:ente-id ente :identidade-id identidade-id :tipo-vinculo tipo :papeis papeis})

(defn- comissao-id [d ente]
  (let [vigentes ((:comissoes-vigentes d) ente)]
    (:id (or (first (filter #(= comissao-promotora (:nome %)) vigentes))
             (first vigentes)
             (throw (ex-info "audiencias/semear!: a Casa nao tem comissao vigente — rode casa/semear! primeiro"
                             {:ente ente}))))))

(defn- inscricoes [d ente sid]
  (:inscricoes (repo-aud/audiencia-da-sessao (:repo-sessoes d) ente sid)))

(defn- agendar-se-faltar! [d secretaria sid agendada-para audiencia]
  (when-not (repo-s/buscar-sessao (:repo-sessoes d) (:ente-id secretaria) sid)
    (controllers-aud/agendar-sessao d secretaria
                                    {:id sid :ente-id (:ente-id secretaria) :sessao-legislativa-id sessao-legislativa-id
                                     :tipo-sessao "audiencia_publica" :modalidade "presencial"
                                     :agendada-para agendada-para :created-by (:identidade-id secretaria)
                                     :audiencia audiencia})))

(defn- inscrever-na-mesa-se-faltar! [d secretaria sid inscricao]
  (or (first (filter #(= (:nome inscricao) (:nome %)) (inscricoes d (:ente-id secretaria) sid)))
      (controllers-aud/inscrever-presencial! d secretaria sid inscricao)))

(defn- transicionar! [d ente sid para]
  (let [s (repo-s/buscar-sessao (:repo-sessoes d) ente sid)]
    (repo-s/transicionar-sessao! (:repo-sessoes d) ente {:id sid :para para :lock-version (:lock-version s)})))

(defn- as-14h [^LocalDate dia] (.toInstant (.atZone (.atTime dia 14 0) ^ZoneId fuso)))

;; ---------- 1. a tematica, agendada ----------

(defn- semear-tematica! [d ente secretaria cidada]
  (agendar-se-faltar! d secretaria id-tematica (as-14h (.plusDays ^LocalDate (tempo/hoje (tempo/relogio-sistema) fuso) 7))
                      {:comissao-id (comissao-id d ente) :tema tema-tematica :local "Plenário da Câmara"
                       :finalidade "tematica" :tempo-fala-segundos 300})
  (inscrever-na-mesa-se-faltar! d secretaria id-tematica inscricao-presencial)
  (when-not (some #(and (= (:identidade-id cidada) (:identidade-id %)) (not= "desistiu" (:estado %)))
                  (inscricoes d ente id-tematica))
    (controllers-aud/inscrever-pelo-portal! d cidada id-tematica inscricao-da-cidada))
  id-tematica)

;; ---------- 2. as metas fiscais do 1º quadrimestre, encerrada com ata ----------

(defn- semear-metas-fiscais! [d ente secretaria]
  (agendar-se-faltar! d secretaria id-metas-fiscais (as-14h (LocalDate/of 2026 5 27))
                      {:comissao-id (comissao-id d ente) :tema tema-metas :local "Plenário da Câmara"
                       :finalidade "metas_fiscais" :referencia "2026-Q1" :tempo-fala-segundos 300})
  (let [estado #(:estado (repo-s/buscar-sessao (:repo-sessoes d) ente id-metas-fiscais))]
    (when (= "agendada" (estado))
      (doseq [[i _] falaram-nas-metas] (inscrever-na-mesa-se-faltar! d secretaria id-metas-fiscais i))
      (transicionar! d ente id-metas-fiscais "aberta"))
    (when (= "aberta" (estado))
      (doseq [[{:keys [nome]} segundos] falaram-nas-metas]
        (let [i (first (filter #(= nome (:nome %)) (inscricoes d ente id-metas-fiscais)))]
          (when (= "inscrita" (:estado i))
            (controllers-aud/conduzir-inscricao! d secretaria id-metas-fiscais (:id i) "falando" {}))
          (when (#{"inscrita" "falando"} (:estado i))
            (controllers-aud/conduzir-inscricao! d secretaria id-metas-fiscais (:id i) "falou"
                                                 {:tempo-usado-segundos segundos}))))
      (transicionar! d ente id-metas-fiscais "encerrada")))
  (when-not (repo-s/ata-versao (:repo-sessoes d) ente id-metas-fiscais 1)
    (controllers/publicar-ata! (:repo-sessoes d) secretaria id-metas-fiscais
                               {:texto ata-metas :origem-redacao "redigida_externamente" :motivo-retificacao nil}))
  id-metas-fiscais)

(defn semear!
  "Semeia (ou rele) as duas audiencias da Casa `ente`. `identidades` = o mapa de `casa/semear!` (usa `:secretaria` e
  `:cidadao`). Devolve {:tematica id :metas-fiscais id}."
  [sistema ente identidades]
  (let [d (deps sistema)
        secretaria (ator ente (:secretaria identidades) "servidor" #{"secretario"})
        cidada (ator ente (:cidadao identidades) "cidadao" #{})]
    {:tematica (semear-tematica! d ente secretaria cidada)
     :metas-fiscais (semear-metas-fiscais! d ente secretaria)}))

(defn resumo
  "Uma linha legivel para o log do orquestrador."
  [sistema ente {:keys [tematica metas-fiscais]}]
  (let [d (deps sistema)]
    (str "temática " tematica " (" (count (inscricoes d ente tematica)) " inscrição(ões)), metas fiscais 2026-Q1 "
         metas-fiscais " (" (:estado (repo-s/buscar-sessao (:repo-sessoes d) ente metas-fiscais)) ")")))
