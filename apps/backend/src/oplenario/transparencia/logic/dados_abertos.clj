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
  "Os datasets na ordem do catalogo. `:colunas` = [nome descricao valor], onde `valor` e' a chave da linha ou, para
  a coluna calculada, (fn [linha nomes] valor); `nomes` = {vereador-id nome} (so' o de votos usa)."
  [{:chave "proposicoes"
    :arquivo "proposicoes.csv"
    :titulo "Proposições e tramitação"
    :descricao "Todas as proposições publicadas no portal, com a situação atual no rito da Casa."
    :colunas [["proposicao_id" "Identificador da proposição no portal (o mesmo do link da ficha)." :proposicao-id]
              ["tipo" "Espécie legislativa (vocabulário da Casa)." :tipo]
              ["numero" "Número da proposição dentro da espécie e do ano." :sequencial]
              ["ano" "Ano da proposição." :ano]
              ["urn_lex" "Identificador LexML." :urn-lex]
              ["ementa" "Ementa: o resumo oficial do conteúdo." :ementa]
              ["autor_tipo" "Tipo de autor (vereador, comissão, Mesa, Executivo...)." :autor-tipo]
              ["autor" "Nome do autor como publicado." :autor-texto]
              ["situacao" "Estado atual da tramitação, no vocabulário do rito da Casa." :estado]
              ["atualizado_em" "Última atualização da situação no portal (ISO 8601, UTC)." :atualizado-em]]}
   {:chave "legislacao"
    :arquivo "legislacao.csv"
    :titulo "Legislação publicada"
    :descricao "As normas publicadas pela Casa (leis, resoluções, decretos legislativos, emendas à Lei Orgânica)."
    :colunas [["norma_id" "Identificador da norma no portal." :norma-id]
              ["tipo_norma" "Espécie da norma." :tipo-norma]
              ["numero" "Número da norma." :numero]
              ["ano" "Ano da norma." :ano]
              ["urn" "Identificador LexML da norma." :urn]
              ["ementa" "Ementa: o resumo oficial do conteúdo." :ementa]
              ["publicado_em" "Instante da publicação oficial (ISO 8601, UTC)." :publicado-em]
              ["veiculo_publicacao" "Onde foi publicada (prova da publicação)." :veiculo-publicacao]
              ["proposicao_id" "A proposição de origem." :proposicao-id]]}
   {:chave "votos-nominais"
    :arquivo "votos-nominais.csv"
    :titulo "Votações nominais"
    :descricao (str "Cada voto nominal registrado em plenário em sessão pública: quem votou, como e em qual matéria. "
                    "Votações secretas não têm voto individual e não entram; votos de sessão secreta ou fechada ao "
                    "público também não.")
    :colunas [["votacao_id" "Identificador da votação." :votacao-id]
              ["ocorrido_em" "Instante do voto (ISO 8601, UTC)." :ocorrido-em]
              ["proposicao_id" "A proposição votada (vazio se a matéria não foi publicada)." :proposicao-id]
              ["materia" "A matéria como tipo número/ano (vazio se não foi publicada)."
               (fn [l _] (when (:materia-tipo l) (str (:materia-tipo l) " " (:materia-sequencial l) "/" (:materia-ano l))))]
              ["vereador_id" "Identificador do vereador (o mesmo do perfil público)." :vereador-id]
              ["vereador" "Nome parlamentar do vereador, ou o civil quando não há." (fn [l nomes] (get nomes (:vereador-id l)))]
              ["voto" "sim, nao ou abstencao." :voto]]}])

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
        linha (fn [vs] (str (str/join "," (map campo vs)) "\r\n"))
        valor (fn [l [_ _ g]] (if (keyword? g) (get l g) (g l nomes)))]
    (apply str bom (linha (map first cols))
           (map (fn [l] (linha (map #(valor l %) cols))) linhas))))
