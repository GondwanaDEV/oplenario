(ns oplenario.comunicacao.controllers
  "As acoes dos comunicados internos da Casa (ADR-0020). Orquestra a logica pura, o Repo e os SEAMS do host — o modulo
  nao importa `cadastros` nem `identidade` (§22.10): quem e' do setor X, da comissao Y, quais sao as pessoas da Casa,
  quem pode enviar a grupo (papeis + Mesa vigente) e o nome de quem envia chegam prontos.

  `deps` = {:repo-comunicacao :objeto-store :relogio
            :seams {:resolver-destino      (fn [ente-id {:tipo :alvo-id}] -> {:alvo-nome :pessoas [{:identidade-id
                                            :nome}] :sem-acesso n} | nil (alvo inexistente/inativo nesta Casa))
                    :pode-enviar-a-grupos? (fn [ator] -> bool)
                    :destinos              (fn [ente-id grupos?] -> as opcoes do formulario)
                    :nome-de               (fn [ente-id identidade-id] -> nome | nil)}}

  O agente (catalogo de acoes) le pelas mesmas funcoes com `:via` no ator: ler pelo agente NAO grava `recebido`/`lido`
  — a marca e' da pessoa abrindo a caixa, nao de um agente lendo por ela."
  (:require [oplenario.comunicacao.components.repositorio :as repo]
            [oplenario.comunicacao.logic :as logic]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.components.objeto-store :as store]
            [oplenario.kernel.tempo :as tempo])
  (:import (java.security MessageDigest)
           (java.time Instant LocalDate ZoneId)))

(set! *warn-on-reflection* true)

(def zona-da-casa
  "O dia civil da Casa (prazo de ciencia, ano do protocolo). Literal proprio, como `auditoria` e `participacao`: a
  constante global do kernel tem a lista de consumidores pinada."
  (ZoneId/of "America/Fortaleza"))

(defn agora ^Instant [{:keys [relogio]}] (tempo/agora (or relogio (tempo/relogio-sistema))))
(defn hoje ^LocalDate [deps] (tempo/hoje-de (agora deps) zona-da-casa))

(defn- conflito! [tipo msg & [info]] (throw (ex-info msg (merge {:tipo tipo} info))))
(defn- invalido! [msg campos] (throw (ex-info msg {:tipo :validacao/invalido :campos campos})))

(defn exige-pessoa-da-casa!
  "Toda rota de comunicado e' de pessoa da Casa — nunca cidadao, nunca agente institucional (Eixo 3)."
  [ator]
  (when-not (logic/pessoa-da-casa? ator)
    (authz/negar! :comunicado-so-pessoa-da-casa {:ator (:identidade-id ator)})))

(defn- marcar? [ator] (nil? (:via ator)))

;; ---------- enviar ----------

(defn- resolver-destinos!
  "Cada destino pedido -> resolvido pelo host, na ordem. Alvo inexistente/inativo nesta Casa -> `:conflito/destino-
  inexistente` (422), nomeando qual."
  [{:keys [resolver-destino]} ente-id destinos]
  (mapv (fn [d]
          (if-let [r (resolver-destino ente-id d)]
            (merge d r)
            (conflito! :conflito/destino-inexistente "destino inexistente nesta Casa"
                       {:destino {:tipo (:tipo d) :alvo-id (some-> (:alvo-id d) str)}})))
        (logic/sem-destinos-repetidos destinos)))

(defn- original-para-substituir!
  "O comunicado a substituir: desta Casa, que o ator pode corrigir (quem enviou, a secretaria, o admin), ainda nao
  substituido. Senao: `:conflito/substituicao-invalida` (422) ou `:conflito/ja-substituido` (409)."
  [repo-c ator id]
  (let [c (repo/comunicado repo-c (:ente-id ator) id)]
    (when-not (and c (logic/pode-substituir? ator c))
      (conflito! :conflito/substituicao-invalida "comunicado a substituir nao encontrado"))
    (when (:substituido-por c)
      (conflito! :conflito/ja-substituido "este comunicado ja' foi substituido"))
    c))

(defn enviar!
  "O ATO de enviar (Eixos 2, 3 e 5): confere quem envia e a quem, resolve e CONGELA a lista, numera e grava. Devolve
  {:comunicado (hidratado) :sem-acesso n}. 403 = grupo sem permissao; 422 = destino inexistente ou lista vazia; 400 =
  prazo invalido."
  [{:keys [repo-comunicacao seams] :as deps} ator {:keys [destinos substitui-id objeto] :as pedido}]
  (exige-pessoa-da-casa! ator)
  (when (and (logic/tem-grupo? destinos) (not ((:pode-enviar-a-grupos? seams) ator)))
    (authz/negar! :comunicado-a-grupo {:ator (:identidade-id ator)}))
  (let [ente (:ente-id ator)
        agora (agora deps)
        hoje (tempo/hoje-de agora zona-da-casa)]
    (when-not (logic/prazo-valido? pedido hoje)
      (invalido! "o prazo de ciencia exige 'exige ciencia' e nao pode ser um dia que ja' passou" [:ciencia-ate]))
    (when substitui-id (original-para-substituir! repo-comunicacao ator substitui-id))
    (let [resolvidos (resolver-destinos! seams ente destinos)
          {:keys [destinos destinatarios sem-acesso]} (logic/congelar resolvidos (:identidade-id ator))]
      (when (empty? destinatarios)
        (conflito! :conflito/lista-vazia "ninguem com acesso ao sistema recebe este comunicado" {:sem-acesso sem-acesso}))
      {:comunicado (repo/enviar! repo-comunicacao ente
                                 {:id (random-uuid) :ano (.getYear hoje) :enviado-em agora
                                  :remetente-identidade-id (:identidade-id ator)
                                  :remetente-nome (or ((:nome-de seams) ente (:identidade-id ator)) "Pessoa da Casa")
                                  :assunto (:assunto pedido) :corpo (:corpo pedido)
                                  :exige-ciencia (:exige-ciencia pedido) :ciencia-ate (:ciencia-ate pedido)
                                  :substitui-id substitui-id
                                  :objeto-tipo (:tipo objeto) :objeto-id (:id objeto)}
                                 destinos destinatarios)
       :sem-acesso sem-acesso})))

(defn previa-do-envio
  "O que a pessoa confirmaria se o AGENTE propusesse este envio (ADR-0012): os destinos com o nome e quantas pessoas
  recebem. Sem gravar nada. Lanca o mesmo que `enviar!` lancaria antes de gravar."
  [{:keys [seams]} ator {:keys [destinos] :as pedido}]
  (exige-pessoa-da-casa! ator)
  (when (and (logic/tem-grupo? destinos) (not ((:pode-enviar-a-grupos? seams) ator)))
    (authz/negar! :comunicado-a-grupo {:ator (:identidade-id ator)}))
  (let [resolvidos (resolver-destinos! seams (:ente-id ator) destinos)
        congelado (logic/congelar resolvidos (:identidade-id ator))]
    (assoc congelado :pedido pedido)))

;; ---------- ler ----------

(defn extras-do-detalhe
  "O que a tela pode oferecer a este ator sobre o comunicado: o painel de leitura e anexar (so' quem enviou, nos 10
  minutos, abaixo do limite)."
  [deps ator c]
  {:pode-ver-leitura (logic/pode-ver-leitura? ator c)
   :pode-anexar (and (logic/remetente? ator c)
                     (logic/na-janela-de-anexos? c (agora deps))
                     (< (count (:anexos c)) logic/max-anexos))})

(defn ler!
  "O detalhe. Para o destinatario (pela tela), grava `lido` (e `recebido` se faltar). nil = inexistente OU sem direito
  de ver (404 uniforme: nao confirma que o comunicado existe)."
  [{:keys [repo-comunicacao]} ator id]
  (exige-pessoa-da-casa! ator)
  (when-let [c (repo/abrir! repo-comunicacao (:ente-id ator) id (:identidade-id ator) (marcar? ator))]
    (when (logic/pode-ver? ator c (some? (:destinatario c)))
      c)))

(defn caixa!
  "A caixa da pessoa; pela tela, grava `recebido` dos que acabou de entregar."
  [{:keys [repo-comunicacao]} ator]
  (exige-pessoa-da-casa! ator)
  (repo/caixa! repo-comunicacao (:ente-id ator) (:identidade-id ator) (marcar? ator)))

(defn contagem
  "O numero do topo: os totais da caixa SEM gravar `recebido` (a marca so' nasce quando a pessoa abre a caixa)."
  [{:keys [repo-comunicacao]} ator]
  (exige-pessoa-da-casa! ator)
  (:resumo (repo/caixa! repo-comunicacao (:ente-id ator) (:identidade-id ator) false)))

(defn registrar-ciencia!
  "\"Estou ciente\": so' o destinatario, so' em comunicado que pede ciencia. Idempotente (a primeira vale). Devolve
  {:comunicado :marcas}; nil = inexistente/sem direito de ver (404). Quem ve mas nao e' destinatario -> 403; comunicado
  que nao pede ciencia -> `:conflito/sem-ciencia` (409)."
  [{:keys [repo-comunicacao]} ator id]
  (exige-pessoa-da-casa! ator)
  (when-let [c (repo/abrir! repo-comunicacao (:ente-id ator) id (:identidade-id ator) false)]
    (when (logic/pode-ver? ator c (some? (:destinatario c)))
      (when-not (:destinatario c)
        (authz/negar! :ciencia-so-do-destinatario {:ator (:identidade-id ator)}))
      (when-not (:exige-ciencia c)
        (conflito! :conflito/sem-ciencia "este comunicado nao pede ciencia"))
      {:comunicado c :marcas (repo/registrar-ciencia! repo-comunicacao (:ente-id ator) id (:identidade-id ator))})))

(defn enviados
  "Os enviados pela pessoa; com `escopo` \"casa\", os da Casa inteira (so' secretaria e admin_ente — 403 para os
  outros)."
  [{:keys [repo-comunicacao]} ator escopo]
  (exige-pessoa-da-casa! ator)
  (when (and (= "casa" escopo) (not (logic/ve-a-casa-inteira? ator)))
    (authz/negar! :enviados-da-casa {:ator (:identidade-id ator)}))
  (repo/enviados repo-comunicacao (:ente-id ator) (when-not (= "casa" escopo) (:identidade-id ator))))

(defn leitura
  "O painel de leitura (quem enviou, a secretaria, o admin). nil = inexistente/sem direito de ver (404); quem ve o
  comunicado mas nao o painel (o destinatario) -> 403. Devolve {:comunicado :linhas}."
  [{:keys [repo-comunicacao]} ator id]
  (exige-pessoa-da-casa! ator)
  (when-let [c (repo/abrir! repo-comunicacao (:ente-id ator) id (:identidade-id ator) false)]
    (when (logic/pode-ver? ator c (some? (:destinatario c)))
      (when-not (logic/pode-ver-leitura? ator c)
        (authz/negar! :leitura-do-comunicado {:ator (:identidade-id ator)}))
      {:comunicado c :linhas (repo/leitura repo-comunicacao (:ente-id ator) id)})))

(defn destinos
  "As opcoes do formulario de envio; os grupos so' para quem pode enviar a grupo."
  [{:keys [seams]} ator]
  (exige-pessoa-da-casa! ator)
  (let [grupos? (boolean ((:pode-enviar-a-grupos? seams) ator))]
    (assoc ((:destinos seams) (:ente-id ator) grupos?) :pode-enviar-a-grupos grupos?)))

;; ---------- anexos (fatia 2) ----------

(defn- hex [^bytes b] (apply str (map #(format "%02x" (bit-and (int %) 0xff)) b)))

(defn anexar!
  "Um arquivo no comunicado: so' quem enviou, nos 10 minutos depois do envio, ate' 5 por comunicado (o tamanho ja' foi
  limitado na borda). O arquivo sobe ao object storage ANTES da linha (a linha so' existe apontando para um blob que
  existe); se a linha for recusada (limite atingido por um envio concorrente), o blob sai. nil = comunicado
  inexistente/sem direito de ver (404). Fora da janela -> `:conflito/fora-da-janela` (409); limite -> 409."
  [{:keys [repo-comunicacao objeto-store] :as deps} ator id {:keys [nome tipo-midia ^bytes conteudo]}]
  (exige-pessoa-da-casa! ator)
  (when-let [c (repo/abrir! repo-comunicacao (:ente-id ator) id (:identidade-id ator) false)]
    (when (logic/pode-ver? ator c (some? (:destinatario c)))
      (when-not (logic/remetente? ator c)
        (authz/negar! :anexo-so-de-quem-enviou {:ator (:identidade-id ator)}))
      (when-not (logic/na-janela-de-anexos? c (agora deps))
        (conflito! :conflito/fora-da-janela "os anexos vao junto com o comunicado: a janela de 10 minutos passou"))
      (when (>= (count (:anexos c)) logic/max-anexos)
        (conflito! :conflito/anexos-demais "o comunicado ja' tem o maximo de anexos" {:limite logic/max-anexos}))
      (let [ente (:ente-id ator)
            anexo-id (random-uuid)
            chave (logic/chave-do-anexo ente id anexo-id)
            sha (hex (.digest (MessageDigest/getInstance "SHA-256") conteudo))]
        (store/guardar! objeto-store chave conteudo tipo-midia)
        (try
          (repo/anexar! repo-comunicacao ente id {:id anexo-id :nome nome :tipo-midia tipo-midia
                                                  :bytes (alength conteudo) :sha256 sha :chave-objeto chave}
                        logic/max-anexos)
          (catch Exception e
            (try (store/remover! objeto-store chave) (catch Exception _ nil))
            (throw e)))))))

(defn baixar-anexo
  "O anexo para quem pode ver o comunicado: {:anexo :stream} (o CHAMADOR fecha o stream). nil = comunicado/anexo
  inexistente ou sem direito de ver (404). Baixar nao grava `lido` (abrir o comunicado grava)."
  [{:keys [repo-comunicacao objeto-store]} ator id anexo-id]
  (exige-pessoa-da-casa! ator)
  (when-let [c (repo/abrir! repo-comunicacao (:ente-id ator) id (:identidade-id ator) false)]
    (when (logic/pode-ver? ator c (some? (:destinatario c)))
      (when-let [a (repo/anexo repo-comunicacao (:ente-id ator) id anexo-id)]
        (when-let [in (store/abrir objeto-store (:chave-objeto a))]
          {:anexo a :stream in})))))
