(ns oplenario.comunicacao.logic
  "O nucleo PURO dos comunicados internos da Casa (ADR-0020). Sem IO, sem relogio (o `hoje` e o `agora` chegam como
  parametro, lidos na borda).

  - quem e' PESSOA DA CASA (pode usar a caixa e enviar): vinculo ativo que nao e' o de cidadao;
  - quem pode enviar a GRUPO (setor, todos os setores, comissao): decidido pelo host (papeis + Mesa), aqui so' o que e'
    grupo;
  - a LISTA CONGELADA (Eixo 2): os destinos resolvidos viram uma lista de pessoas, sem repetir quem chega por dois
    caminhos (fica o primeiro) e sem quem enviou;
  - quem ve o comunicado e o painel de leitura (Eixo 4: a Casa responde por eles, nao so' quem clicou em enviar);
  - o PRAZO de ciencia (fatia 3): vencido e' calculado na leitura, nunca por agendador;
  - o protocolo `COM-AAAA-NNNNNN` (Eixo 5) e a janela de anexos (fatia 2)."
  (:require [clojure.string :as str]
            [oplenario.kernel.arquivo :as arquivo])
  (:import (java.time Duration Instant LocalDate)))

(set! *warn-on-reflection* true)

;; ---------- quem ----------

(def papeis-da-casa-inteira
  "Quem ve os comunicados de toda a Casa (o painel de leitura e os enviados com `escopo=casa`): a secretaria e o
  administrador da Casa — a Casa responde pelo que comunicou (Eixo 4)."
  #{"secretario" "admin_ente"})

(defn pessoa-da-casa?
  "O ator e' uma pessoa que trabalha na Casa (servidor, vereador, administrador)? Cidadao nunca (Eixo 3); agente
  institucional (sem pessoa) tambem nao."
  [ator]
  (boolean (and (:identidade-id ator) (:ente-id ator) (:tipo-vinculo ator) (not= "cidadao" (:tipo-vinculo ator)))))

(defn ve-a-casa-inteira? [ator]
  (boolean (some papeis-da-casa-inteira (map name (:papeis ator)))))

(def tipos-de-destino #{"pessoa" "vereador" "setor" "comissao" "todos"})

(def tipos-de-grupo
  "Os destinos que so' quem tem `secretario`/`admin_ente` ou e' da Mesa vigente pode usar (Eixo 3)."
  #{"setor" "comissao" "todos"})

(defn tem-grupo? [destinos] (boolean (some (comp tipos-de-grupo :tipo) destinos)))

(defn remetente? [ator comunicado]
  (= (:identidade-id ator) (:remetente-identidade-id comunicado)))

(defn pode-ver?
  "Quem ve o comunicado: o destinatario, quem enviou, e a secretaria e o administrador da Casa."
  [ator comunicado destinatario?]
  (boolean (and (pessoa-da-casa? ator)
                (= (:ente-id ator) (:ente-id comunicado))
                (or destinatario? (remetente? ator comunicado) (ve-a-casa-inteira? ator)))))

(defn pode-ver-leitura?
  "O painel de leitura: quem enviou, a secretaria e o administrador da Casa (o destinatario nao ve a leitura dos outros)."
  [ator comunicado]
  (boolean (and (pessoa-da-casa? ator)
                (= (:ente-id ator) (:ente-id comunicado))
                (or (remetente? ator comunicado) (ve-a-casa-inteira? ator)))))

(defn pode-substituir?
  "Quem pode corrigir um comunicado enviando outro que o substitui: quem o enviou, ou a secretaria/administrador (a Casa
  responde por ele)."
  [ator comunicado]
  (pode-ver-leitura? ator comunicado))

;; ---------- a lista congelada (Eixo 2) ----------

(defn via
  "O caminho pelo qual a pessoa chegou a lista, em palavras (fica gravado como era no envio)."
  [{:keys [tipo alvo-nome]}]
  (case tipo
    ("pessoa" "vereador") "direto"
    "setor" (str "setor " alvo-nome)
    "comissao" alvo-nome
    "todos" "todos os setores"))

(defn chave-do-destino [{:keys [tipo alvo-id]}] [tipo alvo-id])

(defn sem-destinos-repetidos
  "Os destinos pedidos, sem repetir o mesmo enderecamento (fica o primeiro)."
  [destinos]
  (into [] (comp (map #(select-keys % [:tipo :alvo-id])) (distinct)) destinos))

(defn congelar
  "Os destinos RESOLVIDOS (em ordem: [{:tipo :alvo-id :alvo-nome :pessoas [{:identidade-id :nome}] :sem-acesso n}])
  -> o que o comunicado grava: os `:destinos` (como foi enderecado, com a ordem) e os `:destinatarios` (a lista
  congelada: uma vez por pessoa, com o PRIMEIRO caminho, sem quem enviou). `:sem-acesso` = quantos membros dos grupos
  pedidos nao tem acesso ao sistema (vereador sem identidade) — a tela avisa; eles nao entram."
  [resolvidos remetente-id]
  (let [destinos (vec (map-indexed (fn [i d] (assoc (select-keys d [:tipo :alvo-id :alvo-nome]) :ordem i)) resolvidos))
        destinatarios (reduce (fn [{:keys [vistos lista] :as acc} d]
                                (reduce (fn [acc {:keys [identidade-id nome]}]
                                          (if (or (= identidade-id remetente-id) (contains? (:vistos acc) identidade-id))
                                            acc
                                            (-> acc
                                                (update :vistos conj identidade-id)
                                                (update :lista conj {:identidade-id identidade-id :nome nome
                                                                     :via (via d)}))))
                                        {:vistos vistos :lista lista}
                                        (:pessoas d)))
                              {:vistos #{} :lista []}
                              resolvidos)]
    {:destinos destinos
     :destinatarios (:lista destinatarios)
     :sem-acesso (reduce + 0 (keep :sem-acesso resolvidos))}))

;; ---------- o protocolo (Eixo 5) ----------

(defn protocolo
  "`COM-<ano>-<numero com 6 digitos>` (ex.: COM-2026-000123) — o mesmo desenho dos protocolos da participacao."
  [ano numero]
  (format "COM-%d-%06d" (long ano) (long numero)))

(defn escopo-da-numeracao [ano] (str "comunicado:" ano))

;; ---------- o prazo de ciencia (fatia 3) ----------

(defn prazo-vencido?
  "O prazo de ciencia ja' passou? O prazo e' um DIA civil: vence quando `hoje` e' depois dele (o dia do prazo inteiro
  ainda vale)."
  [{:keys [exige-ciencia ciencia-ate]} ^LocalDate hoje]
  (boolean (and exige-ciencia ciencia-ate (.isAfter hoje ^LocalDate ciencia-ate))))

(defn vencido?
  "A ciencia DESTA pessoa esta' vencida: o comunicado pede ciencia, o prazo passou e ela ainda nao deu ciencia."
  [comunicado ciente-em hoje]
  (and (nil? ciente-em) (prazo-vencido? comunicado hoje)))

(defn pendentes-vencidos
  "Nos enviados: quantos destinatarios devem a ciencia de um prazo que ja' passou."
  [{:keys [destinatarios cientes] :as comunicado} hoje]
  (if (prazo-vencido? comunicado hoje) (max 0 (- (long destinatarios) (long cientes))) 0))

(defn proxima-ciencia-ate
  "O topo da caixa ('ciencia pendente ate DD/MM'): o prazo mais proximo entre os comunicados que ainda pedem a ciencia
  desta pessoa e ainda nao venceram. nil = nenhum."
  [pendentes hoje]
  (->> pendentes
       (keep :ciencia-ate)
       (remove #(.isAfter ^LocalDate hoje ^LocalDate %))
       sort
       first))

(defn prazo-valido?
  "O prazo pedido no envio: so' com `exige-ciencia`, e nao pode ser um dia que ja' passou (hoje vale — o dia inteiro
  ainda esta' por vir)."
  [{:keys [exige-ciencia ciencia-ate]} ^LocalDate hoje]
  (or (nil? ciencia-ate)
      (and exige-ciencia (not (.isBefore ^LocalDate ciencia-ate hoje)))))

;; ---------- anexos (fatia 2) ----------

(def max-anexos 5)
(def max-bytes-anexo (* 10 1024 1024))
(def ^Duration janela-de-anexos (Duration/ofMinutes 10))

(defn na-janela-de-anexos?
  "Os anexos vao junto com o comunicado: so' nos primeiros 10 minutos depois do envio (o comunicado e' imutavel; o
  anexo chega logo depois porque o arquivo sobe por outra requisicao)."
  [{:keys [enviado-em]} ^Instant agora]
  (boolean (and enviado-em (not (.isAfter agora (.plus ^Instant enviado-em ^java.time.temporal.TemporalAmount janela-de-anexos))))))

(defn chave-do-anexo
  "A chave no object storage: a convencao `<pasta>/<ente>/...` que a exportacao e o apagamento da Casa descobrem."
  [ente-id comunicado-id anexo-id]
  (str "comunicados/" ente-id "/" comunicado-id "/" anexo-id))

(defn nome-de-arquivo
  "O nome que o navegador mandou, como nome de exibicao seguro (a limpeza vive em `kernel/arquivo`, compartilhada com os
  anexos do atendimento). Vazio -> \"anexo\"."
  [s]
  (arquivo/nome-de-arquivo s))

(defn tipo-de-midia
  "O content-type declarado da parte, se tem a forma `tipo/subtipo`; senao octet-stream (o navegador baixa)."
  [s]
  (arquivo/tipo-de-midia s))

;; ---------- leitura (Eixo 4) ----------

(defn trecho
  "O comeco do texto, para a lista da caixa."
  [corpo]
  (let [c (str/replace (str corpo) #"\s+" " ")]
    (if (> (count c) 160) (str (subs c 0 157) "...") c)))

(defn totais-da-leitura
  "\"12 de 15 leram, 3 faltam\": os totais do painel de leitura a partir das linhas."
  [linhas]
  {:destinatarios (count linhas)
   :recebidos (count (filter :recebido-em linhas))
   :lidos (count (filter :lido-em linhas))
   :cientes (count (filter :ciente-em linhas))
   :faltam-ler (count (remove :lido-em linhas))
   :vencidos (count (filter :vencido linhas))})
