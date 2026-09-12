(ns compliance
  "Semente NARRATIVA da demo — a 5a, na mesma familia de `casa`/`acervo`/`sessoes`/`participacao`
  (mesmo contrato: `semear!` recebe um `sistema` Component JA' BOOTADO e a Casa `ente`).

  POR QUE EXISTE: o primeiro card do dashboard da Mesa (`/paineis/mesa`, 'saude institucional') e' o
  placar `EM DIA · PENDENTES · VENCIDAS` de obrigacoes junto ao TCE-CE — e ate' aqui ele abria ZERADO em
  toda demo, porque NENHUMA das 4 sementes materializa obrigacao de compliance. Card zerado nao prova
  motor nenhum; prova que a tela existe. Este ns fecha isso.

  A DISCIPLINA: nao se escreve estado de obrigacao na mao. A semente popula o CATALOGO do motor (a
  definicao do template + os prazos regulatorios + o binding do tenant), gera as remessas ACEITAS pelo
  caminho de PRODUCAO (`gerar-remessa!` -> renderiza -> serializa -> hash -> objeto_store -> ciclo
  rascunho/validada/submetida/aceita) e deixa o RUNTIME decidir o placar: `avaliar-obrigacao!`
  materializa/reconcilia cada obrigacao contra o fato real `remessa_enviada`, e `varrer-vencimentos!`
  faz a unica transicao que evento nao dispara (pendente -> vencida). Um `UPDATE ... SET estado =
  'cumprida'` daria o mesmo card com zero prova de que o motor funciona.

  O TEMPLATE e' o T1 de `docs/05-eixo-C-stress-test-rascunho.md` §7 (remessa mensal ao SIM, o caso
  'prazo deslizante'), com UMA correcao de assinatura contra o codigo: a relacao perdeu o arg `ente`
  (§4-bis — implicito na tx do tenant), entao aqui e' `remessa_enviada(\"SIM\", competencia)`. E' o
  UNICO dos 4 templates do stress-test que fecha com fato REAL hoje — T2 (transparencia em tempo real)
  exigiria `publicada_no_portal` e `data_registro_contabil`, que nao existem em nenhum `relacoes` de
  modulo; semea-lo seria semear um `fato sem fn registrada` (fail-closed no runtime).

  `[GAP]` que a semente NAO inventa, so' carrega com a marca da fonte:
    - o valor do prazo SIM (dia 30 do mes seguinte) e' `[INF media]` de `docs/05` §6.1 — o texto exato da
      IN 04/2019 esta num PDF escaneado e segue `[GAP]`;
    - o layout FISICO do arquivo SIM segue `[GAP]` (descritor e serializador sao os fixtures ilustrativos
      de `gerador-remessa`/`serializador-remessa`, os mesmos que a suite usa).

  IDEMPOTENCIA: as linhas de catalogo que esta semente e' DONA (o template, os prazos `SIM_mensal`, a
  versao do registry, o binding) sao limpas antes de reinserir — `prazo_dominio_vigente` tem UNIQUE
  parcial por (dominio, chave, tipo, periodo) vigente e `template_compliance` UNIQUE (chave, versao),
  entao 'idempotente por pular' aqui esconderia catalogo velho. Ja' a obrigacao e' idempotente por
  natureza (UNIQUE ente⋈template⋈objeto_tipo⋈objeto_id + `objeto-id` DERIVADO da competencia, nao
  `random-uuid`), e a remessa so' e' gerada se ainda nao houver nenhuma versao daquela competencia —
  re-emitir criaria versao nova a cada corrida."
  (:require [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [oplenario.compliance.components.fontes :as fontes]
            [oplenario.compliance.components.repositorio :as repo-compliance]
            [oplenario.compliance.components.serializador-remessa :as ser]
            [oplenario.compliance.gerador-remessa :as ger]
            [oplenario.motor.components.repositorio :as repo-motor]
            [oplenario.motor.nucleo :as nuc])
  (:import (java.nio.charset StandardCharsets)
           (java.time LocalDate)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(def registry-versao-ref
  "Versao do catalogo carimbada em cada avaliacao e em cada remessa (B3). A mesma string que a suite de
  integracao usa — a semente nao inventa um eixo de versionamento proprio."
  "registry-v1@2026-06-20")

(def template-chave "remessa_mensal_sim")

(def fonte-yaml
  "T1 de `docs/05` §7 — a DEFINICAO da regra, como DADO (Invariante 4: TCE-CE na V1 e' configuracao, nao
  branch de codigo)."
  (str "template: " template-chave "\n"
       "contexto: compliance\n"
       "dominio: tribunal_de_contas\n"
       "parametros: { competencia: Competencia }\n"
       "aplica_quando: verdadeiro\n"
       "exige: remessa_enviada(\"SIM\", competencia)\n"
       "prazo:\n"
       "  janela: prazo_vigente(\"TCE-CE\", \"SIM_mensal\", competencia)\n"
       "  a_partir_de: fim_de(competencia)\n"
       "severidade: bloqueante\n"
       "referencia_normativa: \"IN TCE-CE 04/2019; Lei 12.160/1993 art.40 §3º\"\n"))

(def ^:private competencias
  "As 8 competencias da narrativa, e se a remessa daquele mes foi de fato ACEITA pelo TCE. E' o UNICO
  eixo que a semente controla — o estado de cada obrigacao sai do runtime, nao daqui. A forma desenhada:
  a Casa em dia nos 6 primeiros meses, UMA remessa atrasada (07/2026, venceu 30/08) e UMA no prazo em
  aberto (08/2026, vence 30/09). Um placar todo verde nao mostraria o produto pegando o problema."
  [{:ano 2026 :mes 1 :aceita? true}
   {:ano 2026 :mes 2 :aceita? true}
   {:ano 2026 :mes 3 :aceita? true}
   {:ano 2026 :mes 4 :aceita? true}
   {:ano 2026 :mes 5 :aceita? true}
   {:ano 2026 :mes 6 :aceita? true}
   {:ano 2026 :mes 7 :aceita? false}
   {:ano 2026 :mes 8 :aceita? false}])

(defn- chave-competencia [{:keys [ano mes]}] (format "%04d-%02d" ano mes))

(defn- vence-em
  "Prazo da remessa mensal do SIM: dia 30 do mes SEGUINTE a competencia (`[INF media]`, `docs/05` §6.1 —
  o texto exato da IN 04/2019 segue `[GAP]`). Fevereiro nao tem dia 30: clampa no ultimo dia do mes, em
  vez de construir uma data invalida."
  ^LocalDate [{:keys [ano mes]}]
  (let [seguinte (.plusMonths (LocalDate/of ^long ano ^long mes 1) 1)]
    (.withDayOfMonth seguinte (min 30 (.lengthOfMonth seguinte)))))

(defn- avaliado-em
  "O instante (DATA) em que a avaliacao daquela competencia teria ocorrido no fluxo real: o 1o dia do mes
  seguinte, i.e. `fim_de(competencia) + 1` — quando a competencia fecha e o evento dispara a avaliacao.

  NAO e' detalhe de cosmetica. Avaliar tudo com `hoje` faria `logic/proxima-fase` materializar a
  competencia de prazo ja' vencido DIRETO como 'vencida' (`vencido?` verdadeiro na 1a materializacao), e
  o sweep — a UNICA transicao que evento nao dispara (§22.7.7 S1) — nunca teria o que mover. Avaliando na
  data do evento, a obrigacao nasce PENDENTE e e' o sweep de `hoje` que a vence: a demo passa a exercitar
  os DOIS drivers do runtime, que e' o que o painel afirma ao cliente."
  ^LocalDate [{:keys [ano mes]}]
  (.plusMonths (LocalDate/of ^long ano ^long mes 1) 1))

(defn- objeto-id
  "O objeto sob prazo e' a COMPETENCIA (nao ha' entidade de dominio p/ ela). O id e' DERIVADO de
  (ente, sistema, competencia) — nunca `random-uuid`: a chave de idempotencia da materializacao e'
  ente⋈template⋈objeto_tipo⋈objeto_id, entao um id aleatorio faria cada corrida da semente materializar
  uma obrigacao NOVA e o placar crescer sem limite."
  ^UUID [ente chave]
  (UUID/nameUUIDFromBytes (.getBytes (str ente "|SIM|" chave) StandardCharsets/UTF_8)))

(defn- semear-catalogo!
  "O catalogo do motor: versao do registry + a definicao do template + os prazos por competencia (esfera
  `motor`, dominio SEM ente) e o binding do tenant (com ente, sob RLS). Limpa o que e' seu antes de
  inserir — ver a nota de IDEMPOTENCIA no cabecalho do ns."
  [sistema ente]
  (let [ds   (:ds (:datasource sistema))
        motor (:repo-motor sistema)]
    (jdbc/execute-one! ds ["DELETE FROM motor.prazo_dominio_vigente WHERE tipo_prazo = 'SIM_mensal'"])
    (jdbc/execute-one! ds ["DELETE FROM motor.template_compliance WHERE chave_template = ?" template-chave])
    (jdbc/execute-one! ds ["DELETE FROM motor.registry_catalogo_versao WHERE versao = ?" registry-versao-ref])
    (repo-motor/registrar-versao-catalogo! motor
      {:id (random-uuid) :versao registry-versao-ref :hash nil
       :descricao "catalogo da semente narrativa de demo (compliance)"})
    (repo-motor/criar-template! motor
      {:id (random-uuid) :chave-template template-chave :versao 1 :template-pai-id nil
       :dominio "tribunal_de_contas" :chave-dominio "TCE-CE"
       :descricao "Remessa mensal ao SIM (balancete + folha de pagamento) — TCE-CE"
       :severidade "bloqueante"
       :referencia-normativa "IN TCE-CE 04/2019; Lei 12.160/1993 art.40 §3º"
       :fonte-yaml fonte-yaml
       :forma-compilada (nuc/carregar-envelope fonte-yaml)
       :assinatura-parametros {"competencia" "Competencia"}
       :registry-versao-ref registry-versao-ref :estado-versao "vigente"})
    (doseq [c competencias]
      (repo-motor/criar-prazo! motor
        {:id (random-uuid) :dominio "tribunal_de_contas" :chave-dominio "TCE-CE"
         :tipo-prazo "SIM_mensal" :chave-periodo (chave-competencia c)
         :data-limite (vence-em c) :fonte "IN 04/2019" :vigente true}))
    ;; O binding do tenant NAO segue a regra de limpar-e-reinserir dos demais: `com-tenant*` faz
    ;; `SET LOCAL ROLE oplenario_app`, e esse papel tem exatamente INSERT/SELECT/UPDATE em
    ;; `motor.compliance_regra_tenant` — DELETE nao foi concedido (config de tenant nao se apaga; ela
    ;; se desativa, com `motivo_desativacao`, que o proprio CHECK `motivo_quando_inativa` exige). Um
    ;; DELETE aqui nao "quase funciona": estoura `permission denied`. Entao: le, e so' cria se ausente
    ;; — o binding e' constante (`parametros-tenant {}`; T1 nao le nenhum `parametro_tenant`).
    (when-not (repo-motor/binding-do-ente motor ente template-chave)
      (repo-motor/criar-binding! motor ente
        {:id (random-uuid) :ente-id ente :template-chave template-chave
         :ativa true :parametros-tenant {}}))))

(defn- gerar-remessa-aceita!
  "Gera o ARTEFATO da competencia pelo caminho de producao e o leva pelo ciclo ate' 'aceita'. So' 'aceita'
  torna o fato `remessa_enviada` verdadeiro (§22.7.8) — rejeitada/submetida NAO cumprem. Devolve o id."
  [sistema ente chave]
  (let [repo (:repo-compliance sistema)
        {rid :id} (repo-compliance/gerar-remessa! repo ente
                    {:descritor ger/descritor-sim-fixture
                     :template-chave template-chave :sistema "SIM" :competencia chave
                     :contexto {"competencia" chave "sistema" "SIM"}
                     ;; a unica relacao escalar que o descritor-fixture pede; o registry de relacoes
                     ;; nao tem `nome_ente` (a semente resolve o que o fixture declara, nada mais).
                     :resolver-relacao (fn [_tx nome]
                                         (get {"nome_ente" "Camara Municipal de Fortaleza"} nome))
                     :fontes (fontes/fontes-fixture
                              {"despesas" [{"data" (str chave "-05") "valor" "128430,77"}
                                           {"data" (str chave "-19") "valor" "91002,10"}]})
                     :serializador (ser/serializador-sim)
                     :objeto-store (:objeto-store sistema)
                     :registry-versao-ref registry-versao-ref})]
    (repo-compliance/validar-remessa! repo ente rid)
    (repo-compliance/submeter-remessa! repo ente rid)
    (repo-compliance/registrar-resposta-remessa! repo ente rid "aceita")
    rid))

(defn semear!
  "Semeia (ou rele) o compliance da Casa `ente`. `sistema` e' um sistema Component BOOTADO — usa
  `:repo-motor`, `:repo-compliance`, `:registro-fatos`, `:objeto-store` e o `:datasource` cru (so' p/ a
  limpeza do catalogo que a semente e' dona).

  `hoje` e' injetavel (o motor de compliance opera em DATAS, nao instantes) — o default e' `LocalDate/now`,
  e o teste crava uma data p/ nao depender do calendario da maquina.

  Devolve `{:template :obrigacoes [{:competencia :vence-em :veredito :estado}...] :vencidas-pelo-sweep N
  :resumo {estado -> total}}`."
  ([sistema ente] (semear! sistema ente (LocalDate/now)))
  ([sistema ente ^LocalDate hoje]
   (let [repo (:repo-compliance sistema)
         registro (:registro-fatos sistema)
         motor (:repo-motor sistema)]
     (semear-catalogo! sistema ente)
     (let [regra (nuc/carregar-envelope fonte-yaml)
           linhas
           (vec
            (for [{:keys [ano mes aceita?] :as c} competencias
                  :let [chave (chave-competencia c)]]
              (do
                (when (and aceita?
                           (empty? (repo-compliance/listar-remessas repo ente template-chave chave)))
                  (gerar-remessa-aceita! sistema ente chave))
                (let [r (repo-compliance/avaliar-obrigacao! repo ente registro motor
                          {:regra regra :reg-ver registry-versao-ref
                           :objeto-tipo "competencia" :objeto-id (objeto-id ente chave)
                           :amb {"competencia" {:ano ano :mes mes}}
                           :agora (avaliado-em c) :origem "evento"})]
                  {:competencia chave
                   :vence-em (vence-em c)
                   :veredito (:veredito (:avaliacao r))
                   :estado (:estado (:obrigacao r))}))))
           ;; o vencimento e' a UNICA transicao que evento nao dispara (§22.7.7 S1)
           movidas (repo-compliance/varrer-vencimentos! repo ente hoje)
           {:keys [resumo]} (repo-compliance/painel repo ente {:limite-em-aberto 100 :limite-remessas 20})]
       (log/info "compliance/semear!: obrigacoes materializadas" {:ente ente :n (count linhas)
                                                                  :vencidas-pelo-sweep (count movidas)})
       {:template template-chave
        :obrigacoes linhas
        :vencidas-pelo-sweep (count movidas)
        :resumo (into {} (map (juxt :estado :total)) resumo)}))))
