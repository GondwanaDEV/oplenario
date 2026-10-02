(ns comunicados
  "Semente NARRATIVA da demo — os COMUNICADOS INTERNOS da Casa e os SETORES (ADR-0020), na mesma familia de
  `casa`/`acervo`/`sessoes`/`participacao`/`compliance` (mesmo contrato: `semear!` recebe um `sistema` Component JA'
  BOOTADO, a Casa `ente` e as `identidades` que `casa/semear!` devolveu).

  POR QUE EXISTE: sem ela a caixa de comunicados abre vazia em toda demo, e o painel de leitura — o que vende a prova
  de leitura — nunca tem uma linha. Aqui a Casa ganha:
    - 3 setores (Secretaria Legislativa, Juridico, Protocolo) lotados com as pessoas da demo que trabalham na Casa
      (a secretaria Marina e a persona de apresentacao, que tem o papel `juridico`) — nenhuma identidade nova, para
      nao mexer no que as outras sementes e os seus testes contam;
    - 3 comunicados REALISTAS, enviados pelo MESMO caminho da tela (`controllers/enviar!`, com os seams do host), nunca
      por INSERT a mao: a lista e' resolvida e congelada de verdade, o protocolo sai do contador gapless;
       1. da secretaria a Mesa Diretora + a vereadora + o setor Juridico, EXIGINDO CIENCIA com prazo (hoje + 5);
       2. da secretaria ao setor Juridico, com o link para uma proposicao do acervo (quando houver);
       3. da presidente (admin da Casa) ao setor Secretaria Legislativa, so' informativo;
    - marcas variadas no 1º, para o painel de leitura mostrar os tres estados: a vereadora recebeu, leu e deu ciencia;
      a presidente so' recebeu (abriu a caixa); a persona de apresentacao ainda nao abriu nada.

  IDEMPOTENCIA: o setor e' achado pelo NOME (criado so' se faltar; a lotacao e' reafirmada, o que e' no-op); cada
  comunicado tem um GATE pelo assunto + remetente (o comunicado e' imutavel e numerado — reenviar criaria outro
  protocolo a cada corrida). As marcas sao insert-only com a primeira ocorrencia valendo (ON CONFLICT DO NOTHING),
  entao reaplica-las e' no-op. Rodar duas vezes RELE em vez de duplicar."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.comunicacao.components.repositorio :as repo-com]
            [oplenario.comunicacao.controllers :as controllers]
            [oplenario.destinatarios :as destinatarios]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy])
  (:import (java.time LocalDate ZoneId)))

(set! *warn-on-reflection* true)

(def setores
  "Os setores da Casa da demo e quem esta' lotado em cada um (chaves de `casa/semear!` :identidades)."
  [["Secretaria Legislativa" [:secretaria :apresentacao]]
   ["Jurídico" [:apresentacao]]
   ["Protocolo" [:secretaria]]])

(def assunto-sessao-extraordinaria "Sessão extraordinária: pauta da LDO e horário de chegada")
(def assunto-pareceres "Pareceres pendentes da CCJ antes da próxima sessão")
(def assunto-expediente "Expediente reduzido na Semana do Servidor")

;; ---------- setores ----------

(defn- semear-setores!
  "Cria o setor que falta (pelo nome) e reafirma a lotacao. Devolve {nome setor-com-membros}."
  [repo ente identidades]
  (let [existentes (into {} (map (juxt :nome identity)) (repo-cad/listar-setores repo ente))]
    (into {}
          (map (fn [[nome chaves]]
                 (let [s (or (get existentes nome) (repo-cad/criar-setor! repo ente {:id (random-uuid) :nome nome}))]
                   [nome (repo-cad/trocar-membros-do-setor! repo ente (:id s) (mapv identidades chaves))])))
          setores)))

;; ---------- comunicados ----------

(defn- ator
  "A pessoa como o `auth` a resolveria (o controller so' olha estes campos)."
  [ente identidade-id tipo-vinculo papeis]
  {:ente-id ente :identidade-id identidade-id :tipo-vinculo tipo-vinculo :papeis papeis})

(defn- ja-enviado
  "O comunicado ja' enviado por `remetente` com este `assunto` nesta Casa (o gate de idempotencia), ou nil."
  [ds ente remetente assunto]
  (tenancy/com-tenant* ds ente
    (fn [tx]
      (some-> (jdbc/execute-one! tx ["select id from comunicacao.comunicado
                                               where ente_id = ? and remetente_identidade_id = ? and assunto = ?
                                               order by enviado_em limit 1"
                                              ente remetente assunto]) vals first))))

(defn- uma-proposicao
  "Uma proposicao do acervo para o link do comunicado (a mais antiga), ou nil se o acervo nao foi semeado."
  [ds ente]
  (tenancy/com-tenant* ds ente
    (fn [tx]
      (some-> (jdbc/execute-one! tx ["select id from legislativo.proposicoes where ente_id = ?
                                                order by ano, tipo, sequencial limit 1" ente]) vals first))))

(defn- enviar-se-faltar!
  "Envia pelo controller (a mesma regra da tela) se o gate nao achar o comunicado; devolve o id dele."
  [deps ds remetente pedido]
  (or (ja-enviado ds (:ente-id remetente) (:identidade-id remetente) (:assunto pedido))
      (:id (:comunicado (controllers/enviar! deps remetente pedido)))))

(defn semear!
  "Semeia (ou rele) os setores e os comunicados da Casa `ente`. `identidades` = o mapa de `casa/semear!`. O dia de
  hoje (prazo de ciencia, comissao vigente) e o instante do envio saem do MESMO relogio do sistema — um `hoje` cravado
  no passado faria o controller recusar o prazo como vencido.
  Devolve {:setores {nome {:id :membros}} :comunicados {:sessao-extraordinaria id :pareceres id :expediente id}
  :ciencia-ate data}."
  [sistema ente identidades]
  (let [ds (get-in sistema [:datasource :ds])
        repo-cadastros (:repo-cadastros sistema)
        relogio (tempo/relogio-sistema)
        hoje (tempo/hoje relogio (ZoneId/of "America/Fortaleza"))
        deps {:repo-comunicacao (:repo-comunicacao sistema)
              :objeto-store (:objeto-store sistema)
              :relogio relogio
              ;; a Mesa nao entra: quem envia a grupo aqui tem `secretario` ou `admin_ente` (Eixo 3)
              :seams (destinatarios/seams-de-comunicacao {:repo-cadastros repo-cadastros
                                                          :repo-identidade (:repo-identidade sistema)
                                                          :hoje (constantly hoje)
                                                          :cargo-na-mesa (constantly nil)})}
        setores-r (semear-setores! repo-cadastros ente identidades)
        id-do-setor (fn [nome] (:id (get setores-r nome)))
        mesa (first (filter #(= "mesa" (:tipo %)) (repo-cad/comissoes-com-membros repo-cadastros ente hoje)))
        secretaria (ator ente (:secretaria identidades) "servidor" #{"secretario"})
        presidente (ator ente (:presidente identidades) "vereador" #{"vereador" "admin_ente"})
        proposicao (uma-proposicao ds ente)
        prazo (.plusDays ^LocalDate hoje 5)
        c1 (enviar-se-faltar!
            deps ds secretaria
            {:assunto assunto-sessao-extraordinaria
             :corpo (str "Senhoras e senhores,\n\n"
                         "A Presidência convocou sessão extraordinária para a votação, em turno único, do Projeto "
                         "da Lei de Diretrizes Orçamentárias. A pauta segue anexa ao ato de convocação e já está "
                         "publicada no portal.\n\n"
                         "Pedimos a chegada ao plenário com 30 minutos de antecedência para a conferência do painel "
                         "de votação. Confirmem a ciência por aqui até a data indicada.\n\n"
                         "Marina Alencar Freire\nSecretaria Legislativa")
             :exige-ciencia true
             :ciencia-ate prazo
             :destinos (cond-> []
                         mesa (conj {:tipo "comissao" :alvo-id (:id mesa)})
                         true (conj {:tipo "pessoa" :alvo-id (:vereador identidades)})
                         true (conj {:tipo "setor" :alvo-id (id-do-setor "Jurídico")}))})
        c2 (enviar-se-faltar!
            deps ds secretaria
            (cond-> {:assunto assunto-pareceres
                     :corpo (str "Ao Jurídico,\n\n"
                                 "A CCJ precisa dos pareceres técnicos das matérias protocoladas na última semana "
                                 "antes da próxima sessão ordinária. A primeira delas está no link deste comunicado; "
                                 "as demais estão na fila de conferências.\n\n"
                                 "Obrigada,\nMarina")
                     :exige-ciencia false
                     :destinos [{:tipo "setor" :alvo-id (id-do-setor "Jurídico")}]}
              proposicao (assoc :objeto {:tipo "proposicao" :id proposicao})))
        c3 (enviar-se-faltar!
            deps ds presidente
            {:assunto assunto-expediente
             :corpo (str "Comunico que, na Semana do Servidor Público, o expediente interno da Câmara será das 8h "
                         "às 13h. O atendimento ao cidadão no Protocolo segue no horário normal, em regime de "
                         "revezamento a combinar com a Secretaria.\n\n"
                         "Antônio Carlos Ferreira\nPresidente da Câmara")
             :exige-ciencia false
             :destinos [{:tipo "setor" :alvo-id (id-do-setor "Secretaria Legislativa")}]})
        repo-c (:repo-comunicacao sistema)]
    ;; as marcas do 1º (o painel de leitura com os tres estados): insert-only, a primeira vale — reaplicar e' no-op
    (repo-com/registrar-ciencia! repo-c ente c1 (:vereador identidades))
    (repo-com/caixa! repo-c ente (:presidente identidades) true)
    {:setores (into {} (map (fn [[n s]] [n (select-keys s [:id :membros])])) setores-r)
     :comunicados {:sessao-extraordinaria c1 :pareceres c2 :expediente c3}
     :ciencia-ate prazo}))

(defn resumo
  "Uma linha legivel para o log do orquestrador."
  [{:keys [setores comunicados]}]
  (str (count setores) " setor(es) [" (str/join ", " (keys setores)) "], "
       (count (remove nil? (vals comunicados))) " comunicado(s)"))
