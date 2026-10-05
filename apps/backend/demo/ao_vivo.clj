(ns ao-vivo
  "Semente OPCIONAL da demo para ver as telas AO VIVO — roda DEPOIS de `semear-tudo` e so' quando alguem quer
  mostrar essas telas (`demo/semear-ao-vivo.sh`); NAO faz parte do `semear-tudo!` de proposito (ver abaixo).

  O que cria, numa sessao NOVA em curso (id fixo `id-ao-vivo`) e em 2 materias NOVAS, tudo pelo caminho de
  producao (Repo-Component dos modulos + o MESMO `acervo/protocolar-e-tramitar!` que o acervo usa: protocolar com
  texto, tramitar pelo rito, receber a carga, inscrever no Livro do Protocolo):

  (a) uma materia APROVADA em plenario e SEM autografo — votacao nominal encerrada com resultado 'aprovada' e o
      texto deliberado congelado na abertura: e' o que `proposicao.aprovada` e o gate do autografo medem, e e' o que
      destrava o formulario 'Prazo de sancao ou veto do Executivo' de `/pos-aprovacao/:id`. A materia chega a
      'aprovada' pelo gatilho 'aprovar' do rito DEPOIS da votacao, na ordem em que a Casa faz (o rotulo nao e'
      autoridade — o ATO e' a votacao —, mas a ficha nao mente sobre o que aconteceu);
  (b) uma votacao NOMINAL ABERTA na sessao em curso, com alguns votos ja' dados (os do plenario) e os das 3
      personas com login (presidente, vereador, apresentacao) ainda por dar — o placar nominal com nome do telao e
      da TV tem o que mostrar e o vereador ainda tem o que votar;
  (c) a votacao nominal ENCERRADA (a da materia de (a)) NA MESMA sessao em curso — o resultado que se ve depois de
      recarregar o telao.

  POR QUE SEPARADA DO `semear-tudo!`: a demo ja' tem uma sessao 'aberta' (`sessoes`, id `…211`, com 1 votacao
  nominal aberta e ZERO voto — e' o que a jornada J4 do vereador exige). Uma SEGUNDA sessao aberta muda o que o
  cockpit `/votar` escolhe (a aberta mais recente, #155), o que o dashboard conta como 'em curso' e o que a
  Trilha 3 espera. Quem roda a semente completa (CI, homologacao, producao) nao ganha nada disso; quem quer ver
  as telas roda este script por cima.

  PRE-CONDICAO: `semear-tudo` ja' rodou (Casa + acervo + sessoes). Falha ALTO se nao — nao semeia a Casa por tras.

  IDEMPOTENCIA: o gate e' a existencia da sessao `id-ao-vivo` (mesmo padrao de `sessoes`). Se existe, RELE (nao
  duplica sessao, materia, voto nem votacao) e devolve os ids; se o estado esta' incompleto (uma corrida que
  parou no meio), falha alto em vez de imprimir URL de tela vazia.

  VOCABULARIO lido da fonte (CHECKs das migrations, ver o cabecalho de `sessoes`): votacao `modalidade` nominal,
  `quorum_tipo` maioria_simples, voto sim|nao|abstencao; estados da sessao agendada->aberta; tipo de item de pauta
  proposicao, fase ordem_do_dia; gatilhos do `rito_ordinario` do acervo (despachar, concluir_comissoes,
  incluir_pauta, aprovar)."
  (:require [acervo]
            [casa]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.cadastros.db.vereador :as vereador]
            [oplenario.config :as config]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.logic :as slogic]
            [oplenario.sistema :as sistema])
  (:import (java.time Instant LocalDate)))

;; ---------- constantes ----------

(def id-ao-vivo
  "Id FIXO da sessao em curso desta semente — o gate de idempotencia. `…213` segue `…210/211/212` de `sessoes`."
  #uuid "10000000-0000-0000-0000-000000000213")

(def ^:private sessao-legislativa-id
  "O ano legislativo que `sessoes/semear!` cria (id fixo `…201`) — esta semente so' PENDURA a sessao nele."
  #uuid "10000000-0000-0000-0000-000000000201")

(def ^:private hoje
  "Mesma data de referencia de `acervo`/`casa` (a posse da legislatura 2025-2028): o roster e a lista de autores."
  (LocalDate/of 2025 1 1))

(def ^:private rito-chave "rito_ordinario")

(def ^:private presentes-total 14)

;; Os votos de cada votacao, na ordem dos eleitores. A aprovada fecha 8 a 2 com 1 abstencao (maioria_simples:
;; sim > nao); a aberta tem 6 dados e deixa os demais presentes — inclusive as 3 personas — por votar.
(def ^:private votos-da-aprovada ["sim" "sim" "nao" "sim" "sim" "abstencao" "sim" "sim" "nao" "sim" "sim"])
(def ^:private votos-da-aberta ["sim" "sim" "nao" "sim" "abstencao" "sim"])

(def ^:private materia-aprovada
  {:tipo "projeto_lei"
   :ementa "Institui o Programa Municipal de Saúde Ocular nas Escolas da Rede Pública."
   :caminho ["despachar" "concluir_comissoes" "incluir_pauta"]
   :texto (str "Lei\n\n"
               "Art. 1º Fica instituído o Programa Municipal de Saúde Ocular nas Escolas da Rede Pública, destinado a "
               "identificar precocemente problemas de visão em estudantes do ensino fundamental.\n\n"
               "Art. 2º O Programa compreenderá a triagem visual anual dos alunos e, quando necessário, o "
               "encaminhamento à rede municipal de saúde para consulta e fornecimento de óculos de grau, na forma do "
               "regulamento.\n\n"
               "Art. 3º As despesas decorrentes da execução desta Lei correrão por conta de dotações orçamentárias "
               "próprias, suplementadas se necessário.\n\n"
               "Art. 4º Esta Lei entra em vigor na data de sua publicação.")})

(def ^:private materia-em-votacao
  {:tipo "projeto_lei"
   :ementa "Dispõe sobre a instalação de pontos de recarga para veículos elétricos em prédios públicos municipais."
   :caminho ["despachar" "concluir_comissoes" "incluir_pauta"]
   :texto (str "Lei\n\n"
               "Art. 1º Os prédios públicos municipais com estacionamento próprio deverão dispor de, no mínimo, 1 (um) "
               "ponto de recarga para veículos elétricos, de uso compartilhado.\n\n"
               "Art. 2º A instalação observará cronograma definido pelo Poder Executivo, priorizando os prédios de "
               "maior fluxo de atendimento ao público, no prazo de 24 (vinte e quatro) meses contados da publicação "
               "desta Lei.\n\n"
               "Art. 3º Esta Lei entra em vigor na data de sua publicação.")})

;; ---------- leituras cruas (sem fn exposta nos modulos — mesma nota de `acervo`/`sessoes`) ----------

(defn- template-do-rito
  "O id do template `rito_ordinario` v1 do ente (o acervo o cria) — ou nil, e entao falta rodar o `semear-tudo`."
  [tx ente]
  (:id (comum/linha->kebab
        (jdbc/execute-one! tx
          (sql/format {:select [:id] :from [:legislativo.template_tramitacao]
                       :where [:and [:= :ente_id ente] [:= :chave rito-chave] [:= :versao 1]]})))))

(defn- sessao-legislativa-existe? [tx ente]
  (some? (jdbc/execute-one! tx
           (sql/format {:select [:id] :from [:cadastros.sessao_legislativa]
                        :where [:and [:= :ente_id ente] [:= :id sessao-legislativa-id]]}))))

(defn- votacoes-da-sessao
  "As votacoes da sessao `sessao-id`, da mais antiga a mais nova, com a contagem de votos nominais de cada."
  [ds ente sessao-id]
  (tenancy/com-tenant* ds ente
    (fn [tx]
      (mapv (fn [v]
              (assoc v :votos (:n (jdbc/execute-one! tx
                                    (sql/format {:select [[[:count :*] :n]] :from [:legislativo.votos]
                                                 :where [:and [:= :ente_id ente] [:= :votacao_id (:id v)]]})))))
            (comum/linhas->kebab
             (jdbc/execute! tx
               (sql/format {:select [:id :objeto_id :estado :resultado] :from [:legislativo.votacoes]
                            :where [:and [:= :ente_id ente] [:= :sessao_id sessao-id]]
                            :order-by [[:criado_em :asc]]})))))))

;; ---------- o roster, o denominador e as presencas ----------

(defn- roster-da-data [repo-cad ente sessao]
  (repo-cadastros/roster-da-casa repo-cad ente (slogic/data-de-referencia-da-sessao sessao)))

(defn- membros-da-casa-agora
  "O denominador do quorum sobre a MESMA uniao que a chamada publica (`logic/membros-da-casa-da-chamada`) — o que o
  encerramento grava em `base-membros`, como o controller faz a partir da composicao real da Casa."
  [repo-s ente sessao-id roster]
  (let [{:keys [presencas justificativas]} (repo-sessoes/chamada-da-sessao repo-s ente sessao-id (Instant/now))]
    (slogic/membros-da-casa-da-chamada roster presencas justificativas)))

(defn- registrar-presenca! [repo-s ente sessao-id presentes]
  (let [agora (Instant/now)]
    (repo-sessoes/registrar-presenca-lote! repo-s ente
      {:sessao-id sessao-id :agora agora
       :registros (mapv (fn [v] {:id (random-uuid) :vereador-id (:vereador-id v) :tipo "entrada"
                                  :modalidade "plenario" :fonte "painel_eletronico" :ocorrido-em agora :created-by nil})
                        presentes)})))

(defn- escolher-presentes
  "Os `presentes-total` do plenario: as 3 personas com login PRIMEIRO (para o vereador, o presidente e a
  apresentacao poderem votar ao vivo — `meu-voto` exige presenca na sessao) e o resto do roster vigente."
  [roster vereadores-das-personas]
  (let [vigentes (filterv #(= "vigente" (:estado-mandato %)) roster)
        persona? #(contains? vereadores-das-personas (:vereador-id %))]
    (into (filterv persona? vigentes)
          (take (- presentes-total (count (filterv persona? vigentes))) (remove persona? vigentes)))))

;; ---------- a votacao pelo caminho do Repo, como `sessoes/votar-e-apurar!` ----------

(defn- abrir-votacao! [repo-l ente sessao-id materia-id]
  (let [vid (random-uuid)]
    (repo-leg/abrir-votacao! repo-l ente
      {:id vid :objeto-tipo "proposicao" :objeto-id materia-id :modalidade "nominal"
       :quorum-tipo "maioria_simples" :sessao-id sessao-id :created-by nil})
    vid))

(defn- votar! [repo-l ente votacao-id eleitores votos]
  (doseq [[v voto] (map vector eleitores votos)]
    (repo-leg/registrar-voto! repo-l ente
      {:id (random-uuid) :votacao-id votacao-id :vereador-id (:vereador-id v) :voto voto :created-by nil})))

(defn- transicionar! [repo-l registro ente template-id materia-id gatilho]
  (let [r (repo-leg/transicionar! repo-l ente registro
            {:proposicao-id materia-id :template-id template-id :gatilho gatilho})]
    (when-not (:transicionou? r)
      (throw (ex-info "ao-vivo/semear!: gatilho nao transicionou (guard bloqueado ou rito mal-formado)"
                      {:proposicao-id materia-id :gatilho gatilho :de (:de r)})))))

(defn verificar-pre-condicao!
  "Falha ALTO se o `semear-tudo` ainda nao rodou nesta Casa (sem o rito do acervo ou sem o ano legislativo de
  `sessoes`); devolve o id do template do rito. Roda ANTES de `casa/semear!` no ponto de entrada: esta semente
  nao semeia a Casa por tras."
  [sistema ente]
  (let [ds (get-in sistema [:datasource :ds])
        [template-id legislativa?] (tenancy/com-tenant* ds ente
                                     (fn [tx] [(template-do-rito tx ente) (sessao-legislativa-existe? tx ente)]))]
    (when-not (and template-id legislativa?)
      (throw (ex-info "ao-vivo/semear!: a Casa demo ainda nao foi semeada — rode demo/semear-tudo.sh primeiro"
                      {:template-id template-id :sessao-legislativa legislativa?})))
    template-id))

;; ---------- a leitura do que ja' foi semeado (a rota de re-execucao) ----------

(defn- ler-estado
  "Reconstroi o resultado a partir do banco (a re-execucao) — e FALHA ALTO se a sessao existe mas nao tem as duas
  votacoes (uma corrida anterior parou no meio): imprimir URL de tela vazia seria pior que parar."
  [ds ente]
  (let [vs (votacoes-da-sessao ds ente id-ao-vivo)
        aberta (first (filter #(= "aberta" (:estado %)) vs))
        encerrada (first (filter #(and (= "encerrada" (:estado %)) (= "aprovada" (:resultado %))) vs))]
    (when-not (and aberta encerrada)
      (throw (ex-info (str "ao-vivo/semear!: a sessao " id-ao-vivo " existe mas esta' incompleta (precisa de 1 votacao "
                           "aberta e 1 encerrada aprovada) — uma corrida anterior parou no meio; refaca a demo "
                           "(down -v + semear-tudo) antes de rodar de novo")
                      {:votacoes (mapv #(select-keys % [:id :estado :resultado]) vs)})))
    {:sessao id-ao-vivo
     :aprovada {:proposicao (:objeto-id encerrada) :votacao (:id encerrada) :votos (:votos encerrada)}
     :em-votacao {:proposicao (:objeto-id aberta) :votacao (:id aberta) :votos (:votos aberta)}
     :ja-semeado true}))

;; ---------- a funcao publica ----------

(defn semear!
  "Semeia (ou rele, se ja' semeada) a sessao ao vivo da Casa `ente`. `sistema` e' um sistema Component BOOTADO e
  `identidades` o mapa de `casa/semear!` (so' leitura: `casa/semear!` e' idempotente e devolve o que ja' existe).

  Devolve `{:sessao :aprovada {:proposicao :votacao :votos} :em-votacao {:proposicao :votacao :votos} :ja-semeado}`."
  [sistema ente identidades]
  (let [repo-s (:repo-sessoes sistema)
        repo-l (:repo-legislativo sistema)
        repo-cad (:repo-cadastros sistema)
        registro (:registro-fatos sistema)
        ds (get-in sistema [:datasource :ds])
        template-id (verificar-pre-condicao! sistema ente)]
    (if (some? (repo-sessoes/buscar-sessao repo-s ente id-ao-vivo))
      (ler-estado ds ente)
      (let [vereadores (tenancy/com-tenant* ds ente (fn [tx] (vereador/listar tx ente hoje)))
            secretaria (:secretaria identidades)
            ;; 2 materias NOVAS, protocoladas e tramitadas pelo rito ate' 'em_pauta' (a carga das comissoes e'
            ;; recebida pela secretaria, como no acervo). `idx` 24 e 25 seguem as 24 do acervo no round-robin de autoria.
            aprovada (acervo/protocolar-e-tramitar! repo-l registro ente template-id vereadores secretaria 24 materia-aprovada)
            em-votacao (acervo/protocolar-e-tramitar! repo-l registro ente template-id vereadores secretaria 25 materia-em-votacao)]
        ;; a sessao em curso: agendada agora e aberta (a maquina so' permite agendada -> aberta)
        (repo-sessoes/agendar-sessao! repo-s ente
          {:id id-ao-vivo :sessao-legislativa-id sessao-legislativa-id :tipo-sessao "ordinaria"
           :modalidade "presencial" :agendada-para (Instant/now) :created-by nil})
        (repo-sessoes/transicionar-sessao! repo-s ente {:id id-ao-vivo :para "aberta" :updated-by nil :lock-version 0})
        (doseq [materia-id [aprovada em-votacao]]
          (repo-sessoes/adicionar-item-na-sessao! repo-s ente
            {:id (random-uuid) :sessao-id id-ao-vivo :fase "ordem_do_dia" :tipo-item "proposicao"
             :proposicao-id materia-id :created-by nil}))
        (let [sessao (repo-sessoes/buscar-sessao repo-s ente id-ao-vivo)
              roster (roster-da-data repo-cad ente sessao)
              personas (into #{} (keep #(some->> (get identidades %)
                                                 (repo-cadastros/vereador-por-identidade repo-cad ente)
                                                 :id))
                             [:presidente :vereador :apresentacao])
              presentes (escolher-presentes roster personas)
              _ (registrar-presenca! repo-s ente id-ao-vivo presentes)
              base (membros-da-casa-agora repo-s ente id-ao-vivo roster)
              ;; (a)+(c): a materia aprovada — votacao nominal aberta, votos do plenario, encerrada (a base de
              ;; membros e' a composicao real da Casa, como o controller sobrescreve), e so' entao o gatilho 'aprovar'
              votacao-aprovada (abrir-votacao! repo-l ente id-ao-vivo aprovada)
              _ (votar! repo-l ente votacao-aprovada presentes votos-da-aprovada)
              _ (repo-leg/encerrar-votacao! repo-l ente
                  {:id votacao-aprovada :base-membros base :updated-by nil :lock-version 0})
              _ (transicionar! repo-l registro ente template-id aprovada "aprovar")
              ;; (b): a outra materia — votacao nominal que FICA aberta, com os votos de quem nao e' persona; as
              ;; personas (e o resto do plenario) ainda nao votaram
              votacao-aberta (abrir-votacao! repo-l ente id-ao-vivo em-votacao)]
          (votar! repo-l ente votacao-aberta (remove #(contains? personas (:vereador-id %)) presentes) votos-da-aberta)
          (log/info "ao-vivo/semear!: sessao em curso semeada" {:sessao id-ao-vivo})
          {:sessao id-ao-vivo
           :aprovada {:proposicao aprovada :votacao votacao-aprovada :votos (count votos-da-aprovada)}
           :em-votacao {:proposicao em-votacao :votacao votacao-aberta :votos (count votos-da-aberta)}
           :ja-semeado false})))))

;; ---------- as URLs das tres telas (o que o operador abre) ----------

(defn urls
  "As URLs prontas das tres telas, com os ids certos. `base` = origem do frontend (sem barra no fim)."
  [base ente {:keys [sessao aprovada em-votacao]}]
  (let [base (string/replace base #"/+$" "")]
    {:pos-aprovacao (str base "/pos-aprovacao/" (:proposicao aprovada))
     :telao (str base "/sessoes/" sessao "/plenario")
     :tv (str base "/sessoes/" sessao "/tv")
     :cockpit-do-vereador (str base "/votar")
     :ficha-da-materia-aprovada (str base "/ficha-materia/" (:proposicao aprovada))
     :ficha-da-materia-em-votacao (str base "/ficha-materia/" (:proposicao em-votacao))
     :entrada (str base "/entrar/" ente)}))

;; ---------- ponto de entrada do `-X` ----------

(defn semear-ao-vivo!
  "Ponto de entrada do `-X` de `demo/semear-ao-vivo.sh`. Boota o sistema, migra (idempotente) e semeia a sessao ao
  vivo por cima da Casa ja' semeada. `OPLENARIO_FRONTEND_URL` (default `http://localhost:3000`) so' compoe as URLs
  impressas. Imprime o resumo e as URLs das tres telas."
  [_]
  (let [sys (component/start (sistema/novo-sistema (config/carregar)))]
    (try
      (migracao/migrar! (:ds (:datasource sys)))
      (verificar-pre-condicao! sys casa/ente-id)
      (let [{:keys [ente identidades]} (casa/semear! sys)
            r (semear! sys ente identidades)
            base (or (System/getenv "OPLENARIO_FRONTEND_URL") "http://localhost:3000")]
        (println "==> ao vivo:" (pr-str r))
        (println "==> telas (entre como secretaria, presidente ou apresentacao):")
        (doseq [[nome url] (urls base ente r)]
          (println (format "    %-28s %s" (name nome) url)))
        (println "==> semear-ao-vivo! OK — sessao" id-ao-vivo)
        r)
      (finally (component/stop sys)))))
