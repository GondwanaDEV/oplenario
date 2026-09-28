(ns oplenario.transparencia.logic.dados-abertos
  "DADOS ABERTOS (Onda E, `dados-abertos`; Decreto 8.777/2016 + LAI art. 8 §3) — PURO. Os datasets que o portal
  publica, com o DICIONARIO de cada coluna (o que ela significa e de onde vem), e a serializacao CSV (RFC 4180:
  virgula, aspas duplas, CRLF; UTF-8 com BOM para a planilha reconhecer o acento). O conteudo e' o MESMO que o
  portal ja' mostra — dados abertos e' formato, nao dado novo.

  Fora deliberadamente: DESPESAS (o documento-mestre veta produzir o dado fiscal: e' do sistema contabil — [GAP]
  de conector) e PRESENCA por sessao (o numero publico de presenca segue a nota metodologica docs/14 — janela de
  exercicio, licencas —, e a linha crua 'presente/ausente' por sessao sem essa leitura publicaria ausencia onde
  ha' licenca; o registro nominal da sessao e' a ata)."
  (:require [clojure.string :as str]))

(defn- ->txt [x] (if (nil? x) "" (str x)))

(def datasets
  "Os datasets na ordem do catalogo. `:colunas` = [nome descricao (fn [linha nomes] valor)]; `nomes` =
  {vereador-id nome} (so' o de votos usa)."
  [{:chave "proposicoes"
    :arquivo "proposicoes.csv"
    :titulo "Proposições e tramitação"
    :descricao "Todas as proposições publicadas no portal, com a situação atual no rito da Casa."
    :colunas [["proposicao_id" "Identificador da proposição no portal (o mesmo do link da ficha)." #(:proposicao-id %1)]
              ["tipo" "Espécie legislativa (vocabulário da Casa)." #(:tipo %1)]
              ["numero" "Número da proposição dentro da espécie e do ano." #(:sequencial %1)]
              ["ano" "Ano da proposição." #(:ano %1)]
              ["urn_lex" "Identificador LexML." #(:urn-lex %1)]
              ["ementa" "Ementa." #(:ementa %1)]
              ["autor_tipo" "Tipo de autor (vereador, comissão, Mesa, Executivo...)." #(:autor-tipo %1)]
              ["autor" "Nome do autor como publicado." #(:autor-texto %1)]
              ["situacao" "Estado atual da tramitação, no vocabulário do rito da Casa." #(:estado %1)]
              ["atualizado_em" "Última atualização da situação no portal (ISO 8601, UTC)." #(:atualizado-em %1)]]}
   {:chave "legislacao"
    :arquivo "legislacao.csv"
    :titulo "Legislação publicada"
    :descricao "As normas publicadas pela Casa (leis, resoluções, decretos legislativos, emendas à Lei Orgânica)."
    :colunas [["norma_id" "Identificador da norma no portal." #(:norma-id %1)]
              ["tipo_norma" "Espécie da norma." #(:tipo-norma %1)]
              ["numero" "Número da norma." #(:numero %1)]
              ["ano" "Ano da norma." #(:ano %1)]
              ["urn" "Identificador LexML da norma." #(:urn %1)]
              ["ementa" "Ementa." #(:ementa %1)]
              ["publicado_em" "Instante da publicação oficial (ISO 8601, UTC)." #(:publicado-em %1)]
              ["veiculo_publicacao" "Onde foi publicada (prova da publicação)." #(:veiculo-publicacao %1)]
              ["proposicao_id" "A proposição de origem." #(:proposicao-id %1)]]}
   {:chave "votos-nominais"
    :arquivo "votos-nominais.csv"
    :titulo "Votações nominais"
    :descricao (str "Cada voto nominal registrado em plenário: quem votou, como e em qual matéria. Votações "
                    "secretas não têm voto individual e não entram.")
    :colunas [["votacao_id" "Identificador da votação." #(:votacao-id %1)]
              ["ocorrido_em" "Instante do voto (ISO 8601, UTC)." #(:ocorrido-em %1)]
              ["proposicao_id" "A proposição votada (vazio se a matéria não foi publicada)." #(:proposicao-id %1)]
              ["materia" "A matéria como tipo número/ano (vazio se não foi publicada)."
               #(when (:materia-tipo %1) (str (:materia-tipo %1) " " (:materia-sequencial %1) "/" (:materia-ano %1)))]
              ["vereador_id" "Identificador do vereador (o mesmo do perfil público)." #(:vereador-id %1)]
              ["vereador" "Nome parlamentar do vereador, ou o civil quando não há." #(get %2 (:vereador-id %1))]
              ["voto" "sim, nao ou abstencao." #(:voto %1)]]}])

(def por-arquivo (into {} (map (juxt :arquivo identity)) datasets))

(defn- campo
  "RFC 4180: entre aspas quando tem virgula, aspas, quebra de linha — ou espaco nas pontas; aspas dobradas."
  [x]
  (let [s (->txt x)]
    (if (re-find #"[\",\r\n]|^\s|\s$" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(def bom "﻿")

(defn ->csv
  "O dataset `d` inteiro como CSV: BOM + cabecalho + uma linha por registro, CRLF."
  [d linhas nomes]
  (let [cols (:colunas d)
        linha (fn [vs] (str (str/join "," (map campo vs)) "\r\n"))]
    (apply str bom (linha (map first cols))
           (map (fn [l] (linha (map (fn [[_ _ f]] (f l nomes)) cols))) linhas))))
