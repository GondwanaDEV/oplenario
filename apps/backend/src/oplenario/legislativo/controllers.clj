(ns oplenario.legislativo.controllers
  "Orquestracao (impura) do legislativo (§22.10 controllers, ADR-0001): coordena Repo-Component + policy.check
  (camada FINA, §22.5 eixo E). Trabalha SO em models (kebab) — a traducao de borda fica no diplomat. A vertical
  da votacao ao vivo (F4 Slice 3) DIRIGE a votacao: a authz e' HERDADA do recurso SESSAO (lido via
  `consultar-sessao` INJETADA pelo host — legislativo NAO importa sessoes, §22.10), e as escritas usam o Repo do
  PROPRIO modulo (que casa ato + emissao do evento de tempo real na MESMA tx, Slice 1)."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.components.objeto-store :as store]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.components.repositorio-contas :as repo-contas]
            [oplenario.legislativo.components.repositorio-juridico :as repo-juridico]
            [oplenario.legislativo.components.repositorio-nota-juridica :as repo-nota-juridica]
            [oplenario.legislativo.logic :as logic]
            [oplenario.legislativo.logic.contas :as logic-contas]
            [oplenario.motor.api :as motor])
  (:import (java.security MessageDigest)))

(set! *warn-on-reflection* true)

(defn- sessao-autorizada
  "Carrega a sessao `sessao-id` no tenant do `ator` via `consultar-sessao` (delega ao Repo de sessoes; a RLS
  escopa por tenant) e roda a camada FINA (policy.check/pode-dirigir-votacao? = mesma Casa). Devolve a sessao se
  autorizada; nil se inexistente (a borda traduz -> 404); LANCA negacao (-> 403) se de outra Casa. Fail-closed:
  ator nil NEGA aqui, nunca delega a consultar-sessao com ente-id nil.

  T2 grupo A achado #4/#5 (ledger de prontidao Fase 8): sessao ENCERRADA continuava aceitando abrir-votacao/
  registrar-voto/encerrar-votacao/meu-voto — so' `pode-dirigir-votacao?` (mesma Casa) rodava. `sessao-fechada?`
  e' INJETADA pelo host (§22.10: legislativo NAO importa `sessoes.logic/estados-sessao-fechada` — o vocabulario
  do estado fechado mora la', o predicado atravessa a fronteira do jeito que `consultar-sessao` ja' atravessa,
  nao um segundo conjunto duplicado aqui). UM SO' ponto de checagem para as 4 escritas da familia votacao
  (abrir/registrar-voto/encerrar/meu-voto) — todas passam por esta fn. Lanca `:conflito/sessao-fechada` (o
  diplomat mapeia 409, mesmo tag do modulo `sessoes`)."
  [consultar-sessao sessao-fechada? ator sessao-id]
  (when (nil? ator) (authz/negar! :ator-ausente {:acao :votacao/dirigir}))
  (when-let [s (consultar-sessao (:ente-id ator) sessao-id)]
    (authz/check! ator :votacao/dirigir s logic/pode-dirigir-votacao?)
    (when (sessao-fechada? s)
      (throw (ex-info "sessao ja fechada; escrita de votacao bloqueada"
                      {:tipo :conflito/sessao-fechada :sessao-id sessao-id :estado (:estado s)})))
    s))

(defn- votacao-na-sessao
  "Carrega a votacao `votacao-id` no tenant do ator e CONFIRMA que pertence a `sessao-id` (a amarra
  votacao<->sessao da URL). Devolve a votacao ou nil (inexistente OU de outra sessao) — a borda traduz -> 404,
  sem vazar a existencia de uma votacao de outra sessao."
  [repo-leg ente-id sessao-id votacao-id]
  (when-let [v (repo/buscar-votacao repo-leg ente-id votacao-id)]
    (when (= sessao-id (:sessao-id v)) v)))

(defn abrir-votacao
  "Abre uma votacao na sessao `sessao-id` (authz herdada da sessao). `m` ja vem decodificado/coagido pelo
  adapters/in (sem sessao-id). Devolve o recibo {:id} ou nil se a sessao nao existe no tenant (-> 404). Sessao
  ja fechada -> `sessao-autorizada` lanca `:conflito/sessao-fechada` (-> 409, ledger Fase 8 achado #5).
  ADR-0021: `registro` (RegistroFatos do motor) vai ao Repo, que avalia NA TX do INSERT a regra de votacao da materia de
  contas (`:conflito/regra-de-votacao` -> 422); sem ele, a guarda so' le os campos da votacao (fato = fail-closed).

  ADR-0021 (A1): sessao que NAO DELIBERA (a capability `delibera` da sessao — audiencia publica, solene, especial) nao
  abre votacao: `:conflito/sessao-nao-delibera` (-> 409 \"esta sessão não delibera\"). Ate' aqui abria. `false?` e nao
  `not`: a sessao sem a capability no mapa (fixture antiga) segue o comportamento de antes."
  ([repo-leg consultar-sessao sessao-fechada? ator sessao-id m]
   (abrir-votacao repo-leg consultar-sessao sessao-fechada? nil ator sessao-id m))
  ([repo-leg consultar-sessao sessao-fechada? registro ator sessao-id m]
   (when-let [s (sessao-autorizada consultar-sessao sessao-fechada? ator sessao-id)]
     (when (false? (:delibera s))
       (throw (ex-info "esta sessão não delibera" {:tipo :conflito/sessao-nao-delibera :sessao-id sessao-id})))
     (repo/abrir-votacao! repo-leg (:ente-id ator) (assoc m :sessao-id sessao-id :registro registro)))))

(defn registrar-voto
  "Registra um voto na votacao `votacao-id` da sessao `sessao-id`. Authz na sessao + amarra votacao<->sessao.
  DISPATCH EXPLICITO (case 3-vias) pela modalidade da votacao CARREGADA: 'secreta' -> registrar-voto-secreto!
  (DESCARTA a identidade, sigilo §22.6); 'nominal' -> registrar-voto! (exige vereador-id); 'simbolica'
  (aclamacao) -> NAO registra votos individuais (o resultado e' cravado no encerramento via :resultado) ->
  :validacao/invalido (-> 400). Qualquer modalidade futura desconhecida cai no ramo fail-closed (nao no nominal).
  Devolve {:id} ou nil (sessao/votacao inexistente ou de outra sessao -> 404). Nominal sem vereador-id -> 400.
  Sessao ja fechada -> `sessao-autorizada` lanca `:conflito/sessao-fechada` (-> 409).

  sec MEDIUM-2 (gate #2): no NOMINAL, `vereador-id` vem do corpo — `vereador-no-roster?` (relacao de cadastros
  injetada pelo host, §22.10) exige que ele componha a Casa com mandato VIGENTE hoje (o MESMO conjunto do
  denominador de quorum); fora do roster -> `:validacao/invalido` (-> 400). O `meu-voto` do celular ja' fazia
  o equivalente por policy-fina; a rota da Mesa nao tinha o gate."
  [repo-leg consultar-sessao sessao-fechada? vereador-no-roster? ator sessao-id votacao-id m]
  (when (sessao-autorizada consultar-sessao sessao-fechada? ator sessao-id)
    (let [ente-id (:ente-id ator)]
      (when-let [v (votacao-na-sessao repo-leg ente-id sessao-id votacao-id)]
        (case (:modalidade v)
          ;; sigilo §22.6 (defesa-em-profundidade): o caminho secreto nao carrega identidade nem autor.
          "secreta" (repo/registrar-voto-secreto! repo-leg ente-id (dissoc m :vereador-id :created-by))
          "nominal" (do
                      (when (nil? (:vereador-id m))
                        (throw (ex-info "voto nominal exige vereador-id"
                                        {:tipo :validacao/invalido :campos [:vereador-id]})))
                      ;; sec MEDIUM-2 (gate #2): `vereador-id` vem do CORPO — so' pode votar quem compoe a
                      ;; Casa com mandato VIGENTE hoje (mesmo conjunto do denominador de quorum). Sem isto, a
                      ;; Mesa registraria voto para um id fora do roster (inexistente/outra Casa/mandato
                      ;; encerrado) e inflaria o placar. O `meu-voto` do celular ja' faz o equivalente por policy.
                      (when-not (vereador-no-roster? ente-id (:vereador-id m))
                        (throw (ex-info "vereador nao e' membro com mandato vigente desta Casa"
                                        {:tipo :validacao/invalido :campos [:vereador-id]})))
                      (repo/registrar-voto! repo-leg ente-id m))
          ;; 'simbolica' (sem apuracao individual) E qualquer modalidade futura -> nao se registra voto aqui.
          (throw (ex-info "modalidade nao registra votos individuais"
                          {:tipo :validacao/invalido :campos [:modalidade] :modalidade (:modalidade v)})))))))

;; ============================ Onda C3: meu-voto (o vereador vota do proprio celular) ============================

(def ^:private expr-mandato-vigente
  "tem_mandato_vigente(ator.identidade, hoje())")

(def ^:private expr-presente-nesta-sessao
  "esta_presente_em(recurso.sessao_id, recurso.vereador_id, agora())")

(defn meu-voto
  "Onda C3 — o vereador vota do PROPRIO celular. `vereador-id` NUNCA vem do corpo (resolvido do ator via
  `resolver-vereador`, mesmo contrato anti-forja de `acusar-ciencia`). Authz herdada da sessao (mesma Casa,
  `sessao-autorizada`) + a AMARRA votacao<->sessao (`votacao-na-sessao`), como a rota da Mesa. A modalidade
  (imutavel pos-abertura) e' checada CEDO contra o `v` pre-tx — 'secreta' NUNCA passa por aqui (voto secreto
  e' regra de borda, nao so' authz) e so' 'nominal' segue (qualquer outra, ex.: 'simbolica' -> :validacao/
  invalido antes de tocar o Repo). A escrita de fato passa por `repo/registrar-meu-voto!` (review CRÍTICO:
  autorizar+escrever precisam da MESMA tx — a versao anterior abria uma tx so' p/ o check e OUTRA, separada,
  p/ a escrita, deixando uma janela onde a Mesa podia encerrar a votacao no meio do caminho): o Repo re-busca
  a votacao SOB LOCK (`FOR UPDATE`) dentro da tx, roda o `autorizar!` (este predicado, com a POLICY FINA — 1a
  producao real de `motor/politica-dsl`, disciplina 5: mandato vigente + presenca registrada NESTA sessao,
  cada fato em avaliacao DSL SEPARADA — `hoje()`/`agora()` compartilham UM so' slot `:agora` no motor,
  motor/runtime.clj, nao coexistem numa MESMA expressao; ver docs/superpowers/specs/2026-07-11-onda-c-
  slice3-cockpit-votacao-design.md §3.2 — + o check trivial de estado 'aberta', tudo contra o snapshot
  LOCKED, nunca o `v` pre-tx) e so' entao reconfere+insere. Qualquer falha -> authz/check! lanca -> 403
  generico (nunca detalha qual precondicao falhou). nil (sessao/votacao inexistente ou de outra sessao, OU
  ator sem cadastro de vereador) -> borda traduz 404. Sessao ja fechada -> `sessao-autorizada` lanca
  `:conflito/sessao-fechada` (-> 409): o proprio celular do vereador nao pode votar numa sessao que a Mesa ja
  encerrou, MESMO gate da rota da Mesa (ledger Fase 8 achado #4/#5)."
  [repo-leg consultar-sessao sessao-fechada? resolver-vereador registro ator sessao-id votacao-id hoje instante m]
  (when-let [vereador-id (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (when (sessao-autorizada consultar-sessao sessao-fechada? ator sessao-id)
      (let [ente-id (:ente-id ator)]
        (when-let [v (votacao-na-sessao repo-leg ente-id sessao-id votacao-id)]
          (when (= "secreta" (:modalidade v))
            (throw (ex-info "voto secreto nao e' registravel pelo proprio celular"
                            {:tipo :validacao/invalido :campos [:modalidade] :modalidade "secreta"})))
          (when (not= "nominal" (:modalidade v))
            (throw (ex-info "modalidade nao registra votos individuais"
                            {:tipo :validacao/invalido :campos [:modalidade] :modalidade (:modalidade v)})))
          (repo/registrar-meu-voto! repo-leg ente-id (assoc m :vereador-id vereador-id)
            (fn [tx v-fresco]
              ;; review MAJOR (revisao final de branch): authz/check! recebe o ATOR e o RECURSO REAIS
              ;; (nao os stand-ins da DSL) — os diagnosticos de NEGACAO do proprio check! leem
              ;; (:identidade-id ator)/(:tipo recurso)/(:id recurso); passar so' os mapas `_`-keyed da DSL
              ;; deixava esses campos SEMPRE nil no audit trail da 1a producao real de politica-dsl. Os
              ;; mapas DSL (chaves `_`, exigencia do tokenizer — motor/nucleo.clj) moram DENTRO do
              ;; predicado, derivados de `a`/`r`.
              (authz/check! ator :votacao/meu-voto v-fresco
                (fn [a r]
                  ;; review LOW (revisao final de branch): `:ente-id` no ator-dsl — `motor/politica-dsl`
                  ;; injeta `(:ente-id ator)` no ctx (usado por builtins own-schema, ex.: parametro_tenant);
                  ;; os 2 fatos daqui (tem_mandato_vigente/esta_presente_em) nao o leem hoje (resolvem so'
                  ;; via a tx), mas omiti-lo deixaria QUALQUER expressao futura neste call site resolver
                  ;; ente-id como nil silenciosamente em vez de falhar alto.
                  (let [ator-dsl {:identidade (:identidade-id a) :ente-id (:ente-id a)}
                        recurso-dsl {:sessao_id (:sessao-id r) :vereador_id vereador-id}]
                    (and (= "aberta" (:estado r))
                         ((motor/politica-dsl {:registro registro :tx tx :expr expr-mandato-vigente :agora hoje}) ator-dsl recurso-dsl)
                         ((motor/politica-dsl {:registro registro :tx tx :expr expr-presente-nesta-sessao :agora instante}) ator-dsl recurso-dsl))))))))))))

(defn- resolver-objeto-votacao
  "{:objeto-tipo :proposicao} pra uma votacao carregada — a resolucao compartilhada entre `detalhe-votacao`
  (Fatia 2) e `votacao-aberta` (Fatia 2b), uma unica formula pros dois. `proposicao`/`redacao_final`
  resolvem TOTALMENTE (`objetos-que-carregam-a-materia`, db/votacao.clj); as demais ficam `nil` — ver a
  docstring de `detalhe-votacao` pro racional completo."
  [repo-leg ente-id v]
  (let [objeto-tipo (:objeto-tipo v)
        proposicao (when (contains? #{"proposicao" "redacao_final"} objeto-tipo)
                     (repo/buscar-proposicao repo-leg ente-id (:objeto-id v)))]
    {:objeto-tipo objeto-tipo :proposicao proposicao}))

(defn detalhe-votacao
  "GET .../votacoes/:votacao-id (fatia 'demo-tres-consertos' #2) — O QUE esta em votacao: resolve o objeto
  POLIMORFICO (`objeto-tipo`,`objeto-id`) da votacao pra um titulo de exibicao. Achado ao vivo (Daouda,
  12/09/2026): o cockpit do vereador (`/votar`) e o telao da Mesa mostravam SO o placar — nenhuma ementa,
  nenhum numero de materia; o vereador votava num objeto nao identificado. LEITURA: reusa a MESMA amarra
  sessao<->votacao e a MESMA authz 'mesma Casa' das 4 escritas da familia (`sessao-autorizada`/
  `votacao-na-sessao`) — nao dirige nada, mas o dado e' tenant-scoped como o resto. nil (sessao/votacao
  inexistente ou de outra sessao) -> borda traduz 404; sessao ja fechada -> `:conflito/sessao-fechada`
  (mesmo racional das escritas — o cockpit so' chama isto com a sessao aberta).

  `proposicao`/`redacao_final` resolvem TOTALMENTE: `objetos-que-carregam-a-materia` (db/votacao.clj) fixa
  que o `objeto-id` deles E' a propria proposicao — uma unica leitura (`repo/buscar-proposicao`, ja'
  existente, sem query nova) devolve tipo+ano+sequencial+ementa reais. `emenda`/`parecer`/`requerimento`
  sao entidades PROPRIAS cujo `objeto-id` aponta OUTRA tabela; a UNICA leitura hoje alcancavel por um
  vereador para elas (`/legislativo/proposicoes/:id`) e' `secretario`-only e nao serve. Resolve-las por
  completo e' escopo MAIOR que esta fatia (registrado, nao feito aqui) — `:proposicao` fica `nil` para
  as 3, e quem serializa (adapters/out) devolve so' o `:objeto-tipo`, nunca um titulo vazio nem inventado."
  [repo-leg consultar-sessao sessao-fechada? ator sessao-id votacao-id]
  (when (sessao-autorizada consultar-sessao sessao-fechada? ator sessao-id)
    (let [ente-id (:ente-id ator)]
      (when-let [v (votacao-na-sessao repo-leg ente-id sessao-id votacao-id)]
        (resolver-objeto-votacao repo-leg ente-id v)))))

(defn votacao-aberta
  "GET .../votacao-aberta (fatia 'demo-tres-consertos' #2b) — QUAL votacao esta aberta nesta sessao, pra
  RECUPERACAO de estado (achado ao vivo, Daouda 12/09/2026): o canal Valkey do plenario tem retencao MINID
  de ~5min (`tempo_real/components`); um cliente que conecta DEPOIS dessa janela (ou perde e reconecta) nao
  ve `votacao.aberta` nenhum e a tela mostra 'nenhuma votacao aberta' com uma votacao de verdade aberta no
  banco — pior que a ementa faltando (Fatia 2): um voto perdido numa deliberacao real. NAO substitui o SSE
  — e' so' a fotografia pra reconstruir o que o stream ao vivo teria mandado.

  Mesma authz 'mesma Casa' + amarra de sessao das outras leituras/escritas da familia (`sessao-autorizada`).
  nil (sessao inexistente/de outra Casa/fechada -> erro/404 mais acima) OU sessao sem votacao aberta -> nil
  — ESTADO LEGITIMO, a borda traduz pra 404 (nao erro): 'nenhuma votacao aberta' segue certo quando e'
  verdade, so' nao pode mais ser dito quando o servidor tem uma aberta.

  Reusa `resolver-objeto-votacao` (a MESMA resolucao da Fatia 2 — uma ida, nao duas) + os votos: NOMINAL
  devolve a lista {vereador-id, voto} (o mesmo que os eventos `voto.registrado` teriam acumulado);
  SECRETA/SIMBOLICA devolvem so' a CONTAGEM (tick anonimo, nunca apuracao por valor — sigilo §22.6, mesma
  fronteira de VotoRegistradoPayload: uma votacao secreta em curso NUNCA vaza sim/nao/abstencao por
  aqui, e simbolica nunca registra voto individual pra comecar, `registrar-voto`).

  AUTHZ (carry telao, Daouda 12/09/2026): `sessao-autorizada` continua garantindo mesma Casa + sessao
  aberta (`pode-dirigir-votacao?`, herdada das 4 escritas da familia — 403/409 de sempre). POR CIMA dela,
  `pode-ver-votacao-aberta?` (injetada pelo host, rotas.clj) estreita para quem esta rota alcanca: mesma
  Casa E (transmissao publica OU papel 'secretario' OU papel 'vereador'). NECESSARIO desde que a borda
  parou de exigir papel 'vereador' — sem esta segunda checada, QUALQUER vinculo ativo da Casa (inclusive
  cidadao, sem papel nenhum) leria a votacao em curso de uma sessao SECRETA, o mesmo defeito que a
  docstring de `sessoes.logic/pode-ver-quorum-da-sessao?` registra ter acontecido uma vez.

  A clausula 'vereador' (revisao do Daouda, 12/09/2026) NAO e' concessao — e' o MESMO argumento daquela
  docstring levado ate' o fim: o gate e' de PUBLICO (quem so' assiste ao telao), nao de sessao; 'secretario'
  entra porque ja' lia esse estado, nominalmente, por `/chamada`. O vereador entra pela razao mais forte
  ainda: numa sessao secreta ele VOTA (`/meu-voto` e' gated 'vereador') — quem tem direito de REGISTRAR o
  voto tem, por construcao, direito de saber que ela esta' aberta. Nega-lo aqui nao protege sigilo nenhum;
  so' devolveria o defeito que esta fatia existe para consertar, no cenario de maior consequencia (sessao
  fechada, vereador que recarregou a pagina e nao vota). Ver a docstring de `pode-ver-votacao-aberta?` em
  rotas.clj para a FORMA exata da composicao (a disjuncao de papel entra DENTRO da conjuncao de mesma-Casa,
  nunca por fora — senao um vereador de OUTRA Casa passaria)."
  [repo-leg consultar-sessao sessao-fechada? pode-ver-votacao-aberta? ator sessao-id]
  (when-let [s (sessao-autorizada consultar-sessao sessao-fechada? ator sessao-id)]
    (authz/check! ator :votacao/ver-aberta s pode-ver-votacao-aberta?)
    (let [ente-id (:ente-id ator)]
      (when-let [v (repo/votacao-aberta-da-sessao repo-leg ente-id sessao-id)]
        (let [{:keys [objeto-tipo proposicao]} (resolver-objeto-votacao repo-leg ente-id v)
              nominal? (= "nominal" (:modalidade v))]
          (merge
           {:votacao-id (:id v) :modalidade (:modalidade v) :objeto-tipo objeto-tipo :objeto-id (:objeto-id v)
            :proposicao proposicao}
           (if nominal?
             {:votos (repo/votos-da-votacao repo-leg ente-id (:id v))}
             {:votos-registrados (repo/contar-votos-secretos-da-votacao repo-leg ente-id (:id v))})))))))

;; ========================= FE Onda A1: fila de relatores pendentes (§16.11) =========================

(defn relatores-pendentes
  "Fila de pareceres 'aguardando_designacao' do tenant `ente-id` (leitura tenant-wide, sem ator/policy fina —
  mesmo contrato de `resumo-presenca`/`esic-cumprimento`). Devolve {:itens [...] :truncado bool} cru
  (kebab, do Repo — frente 'truncamento-familia'); o adapters/out projeta+valida o contrato
  RelatoresPendentesOut."
  [repo-legislativo ente-id]
  (repo/relatores-pendentes repo-legislativo ente-id))

(defn listar-proposicoes
  "Onda B Slice 1 — leitura tenant-wide (mesmo contrato de authz de `relatores-pendentes`: sem policy fina
  adicional, so' o gate grosso da rota — papel 'secretario'). `filtro` ja vem coagido pelo adapters/in.
  `itens`/`total` vem de `listar-e-contar-proposicoes` (UMA tx do Repo, review ecc) — nunca duas leituras
  independentes que poderiam desalinhar sob escrita concorrente."
  [repo-legislativo ente-id filtro]
  (let [{:keys [itens total]} (repo/listar-e-contar-proposicoes repo-legislativo ente-id filtro)]
    {:itens itens
     :total total
     :pagina (:pagina filtro)
     :tamanho-pagina (:tamanho filtro)}))

(defn- validar-autor!
  "Onda E fatia 2 (fix da revisao — achados I-1 + M-1) + Task 1-N1 fix2 (achado 1, Importante): `autor-id`
  cru do cliente vira o elo de autoria PUBLICA (transparencia.materia, via protocolar!/emitir-protocolada!/
  editar-proposicao!->proposicao.editada) — um UUID so' validado por SHAPE (adapters/in) nao pode virar uma
  afirmacao publica de autoria sobre um vereador identificado. Roda ANTES do Repo, em
  `criar-proposicao`/`editar-proposicao`.

  Regra (M-1, decidida aqui): `autor-id` so' e' coerente com `autor-tipo` = \"vereador\" — as outras 4
  especies do vocabulario (mesa/comissao/executivo/cidadao, `logic/autor-tipos`) se identificam por
  `autor-texto`, nunca por id, e um `autor-id` associado a uma delas nao pode materializar como se fosse
  autoria parlamentar. Como `m` e' PARCIAL num PATCH (Onda B Slice 2 — 'so' os campos presentes mudam'), a
  checagem e' sobre o que VEIO NESTA escrita, nunca sobre o estado anterior da linha: um PATCH que muda
  `autor-id` sem reafirmar `autor-tipo` \"vereador\" na MESMA chamada e' rejeitado — decisao deliberada de
  nao ler a linha anterior pra inferir o autor-tipo efetivo (evitaria round-trip extra e abre janela de
  corrida entre o pre-check e o UPDATE); o cliente reenvia os dois campos juntos ao trocar o autor.

  Regra nova (achado 1, Task 1-N1 fix2, MESMA disciplina — nunca ler a linha anterior): quando `autor-tipo`
  vem PRESENTE nesta escrita, `autor-texto` tambem tem que vir presente e nao-vazio na MESMA escrita. Sem
  isto, `db/proposicao.clj editar!` zera o `autor_id` (Peca A da task anterior) mas NAO toca `autor_texto`
  (`some?`-gated) — um PATCH `{:autor-tipo \"executivo\"}` sem `autor-texto` deixava a linha
  `(\"executivo\", NULL, \"<nome antigo do vereador>\")`, e `autor_texto` e' campo PUBLICO exibido no
  portal: o texto obsoleto vira uma afirmacao publica FALSA sobre quem propos. Quem muda a especie de
  autoria reafirma o nome de exibicao junto — mesma disciplina de `autor-id`.

  `vereador-vinculado?` (injetada pelo host, cross-modulo p/ cadastros — mesma inversao de dependencia de
  `resolver-vereador`/`resolver-municipio`, §22.10) confirma que o UUID e' um cadastro de vereador NESTE
  ente (`ente-id` do ATOR, nunca do corpo). Lanca `:validacao/invalido` (-> 400, interceptor global `erro`)
  em todos os casos; NO-OP (nem chama `vereador-vinculado?`) quando `autor-id` esta ausente — a maioria das
  proposicoes nao tem autor-id."
  [vereador-vinculado? ente-id {:keys [autor-tipo autor-id autor-texto]}]
  (when (some? autor-tipo)
    (when (or (nil? autor-texto) (= "" autor-texto))
      (throw (ex-info "autor-texto e obrigatorio quando autor-tipo vem presente (na mesma escrita)"
                      {:tipo :validacao/invalido :campos [:autor-tipo :autor-texto]}))))
  (when (some? autor-id)
    (when (not= "vereador" autor-tipo)
      (throw (ex-info "autor-id so e valido quando autor-tipo e vereador (na mesma escrita)"
                      {:tipo :validacao/invalido :campos [:autor-tipo :autor-id]})))
    (when-not (vereador-vinculado? ente-id autor-id)
      (throw (ex-info "autor-id nao corresponde a um vereador vinculado a esta Casa"
                      {:tipo :validacao/invalido :campos [:autor-id]})))))

(defn criar-proposicao
  "Onda B Slice 2 — protocola uma proposicao nova (authz: so' o gate grosso da rota, papel 'secretario',
  mesmo contrato de listar-proposicoes). `resolver-municipio` (injetado pelo host, cross-modulo p/
  cadastros) resolve {:uf :municipio-nome} do ente — precondicao de protocolar! (eixo H). Ente sem perfil
  cadastrado (resolver devolve nil) e' erro de PROVISIONAMENTO, nao de cliente: propaga sem catch (-> 500),
  nunca mascarado como 400. `vereador-vinculado?` (injetada pelo host) valida `autor-id` ANTES do Repo —
  ver `validar-autor!` (achados I-1/M-1 da review)."
  [repo-legislativo resolver-municipio vereador-vinculado? ente-id m]
  (validar-autor! vereador-vinculado? ente-id m)
  (let [{:keys [uf municipio-nome]} (resolver-municipio ente-id)]
    (repo/protocolar! repo-legislativo ente-id (merge m {:uf uf :municipio-nome municipio-nome}))))

(defn buscar-proposicao-ficha
  "Onda B Slice 2 — detalhe (proposicao + texto vigente inline) p/ a tela de edicao pre-encher. nil se a
  proposicao nao existe no tenant (-> 404 na borda)."
  [repo-legislativo ente-id id]
  (let [{:keys [proposicao texto]} (repo/buscar-proposicao-detalhe repo-legislativo ente-id id)]
    (when proposicao {:proposicao proposicao :texto (:texto-inline texto)})))

(defn editar-proposicao
  "Onda B Slice 2 — edita metadados e/ou promove nova versao de texto ('edicao'). Mesmo gate grosso; `m`
  ja' vem coagido pelo adapters/in. `vereador-vinculado?` (injetada pelo host) valida `autor-id` ANTES do
  Repo, mesmo contrato de `criar-proposicao` — ver `validar-autor!` (achados I-1/M-1 da review)."
  [repo-legislativo vereador-vinculado? ente-id m]
  (validar-autor! vereador-vinculado? ente-id m)
  (repo/editar-proposicao! repo-legislativo ente-id m))

(defn tramitar-proposicao
  "Fatia 2 — dispara UM GATILHO na maquina de tramitacao (eixo C) sobre a materia `(:proposicao-id m)`.
  `m` ja' vem coagido pelo adapters/in (gatilho trimado, `contexto` do corpo virado `:alegado` e
  keywordizado — a marca de procedencia da fatia 4 —, ator/updated-by do token,
  `agora` resolvido na borda). `registro` (RegistroFatos do motor) e' o mesmo que o host ja' injeta p/ o
  editor de parecer — a engine precisa dele p/ resolver os fatos do guard.

  O RITO E' INJETADO AQUI, da coluna `template_id` da propria linha (fatia 1) — nunca do corpo. E' este o
  ponto que impede o T3-A nesta borda: o cliente nomeia o ATO que quer praticar; quem decide sob qual rito
  ele corre, e para onde ele leva, e' o servidor lendo o dado da Casa.

  Tres saidas, DISTINTAS entre si (o diplomat traduz cada uma):
  - `nil` = materia inexistente NESTE tenant -> 404 na borda. Um pre-check, nao paranoia: sem ele, a engine
    leria estado `nil`, nao acharia candidata nenhuma e devolveria `{:transicionou? false}` — a materia
    fantasma se disfarcaria de 'a Casa nao permite este ato', que e' resposta ERRADA sobre uma materia que
    nao existe (mesmo fail-closed que `transicionar-parecer!` ganhou na review F3.6a).
  - lanca `:conflito/sem-rito` = a materia existe mas a Casa nao declarou rito para ela (`template_id`
    NULL). NAO e' erro de corpo: o pedido estava certo, falta CONFIGURACAO da Casa. Fail-closed ANTES do
    Repo — sem template a engine nao tem transicao candidata nenhuma e devolveria o mesmo
    `{:transicionou? false}` enganoso de cima, dizendo 'nao pode agora' quando a verdade e' 'esta materia
    nunca vai poder, ninguem escreveu o rito'.
  - o mapa da engine (`{:transicionou? true|false ...}`) = resultado de DOMINIO; guard que LANCA e conflito
    de CAS propagam como excecao, nao sao capturados aqui (a distincao entre eles e' de borda)."
  [repo-legislativo registro ator m]
  (when-let [linha (repo/buscar-proposicao repo-legislativo (:ente-id ator) (:proposicao-id m))]
    (when (nil? (:template-id linha))
      (throw (ex-info "esta materia nao tem rito declarado (a Casa nao vinculou um template de tramitacao a ela) — nao ha o que tramitar"
                      {:tipo :conflito/sem-rito :proposicao-id (:proposicao-id m)})))
    ;; 3-A: o ATOR INTEIRO desce ate' a engine, nao so' o `ator-id`. `motor/politica-dsl` avalia
    ;; `(fn [ator recurso] -> bool)` com acesso a campo (`ator.identidade`) e aos fatos de relacao
    ;; (`é_presidente_da_mesa`, `quem_exerce_presidencia`, …) — um uuid solto nao responde nenhuma dessas
    ;; perguntas. O `:ator-id` continua indo separado porque ele e' AUTORIA no historico (quem praticou o
    ;; ato), nao insumo de decisao: sao dois usos distintos do mesmo sujeito.
    (repo/transicionar! repo-legislativo (:ente-id ator) registro
                        (assoc m :template-id (:template-id linha) :ator ator))))

(defn receber-movimentacao
  "Fatia 2b — quem RECEBE a carga assina o recebimento da movimentacao pendente (pedido do stakeholder: 'toda
  movimentacao do documento assinada por quem recebe'). `m` = {:proposicao-id :transicao-id :agora} ja'
  coagido pelo adapters/in; o ator vem do token e e' ELE quem assina (`recebido-por`), nunca o corpo.

  nil = materia inexistente no tenant (-> 404), mesmo pre-check de `tramitar-proposicao`. O resto propaga:
  `:conflito/sem-recebimento-pendente` / `:conflito/movimentacao-divergente` (-> 409) e a negacao da regra
  de quem recebe (-> 403) — a traducao e' da borda. `assinador` = o AssinadorICP (hoje o STUB-ICP-v0, a mesma
  divida conhecida do parecer e do requerimento)."
  [repo-legislativo registro assinador ator m]
  (when (repo/buscar-proposicao repo-legislativo (:ente-id ator) (:proposicao-id m))
    (repo/receber-movimentacao! repo-legislativo (:ente-id ator) registro
                                (assoc m :ator ator :assinador assinador))))

(defn recebimentos-pendentes
  "Fatia 2b — a fila de cargas da Casa ainda nao recebidas (mais antigas primeiro, teto 200 no db/)."
  [repo-legislativo ente-id]
  (repo/recebimentos-pendentes repo-legislativo ente-id))

(defn- nota-de-lista-vazia
  "Lista de gatilhos vazia tem QUATRO causas, e elas pedem acoes DIFERENTES do operador. Devolver so'
  'nenhum ato disponivel' seria verdadeiro e inutil — a nota diz de qual das quatro se trata.
  Nunca e' preenchida quando ha' ato a praticar: nao se explica o que nao aconteceu."
  [estado {:keys [template-id estado-no-template]}]
  (cond
    (nil? template-id)
    (str "esta materia nao tem rito de tramitacao declarado — a Casa nunca vinculou um template a ela, "
         "e por isso ela nao tramita. Quem vincula e' a configuracao do rito, nao esta tela.")

    (nil? estado-no-template)
    (str "o rito desta materia nao declara o estado atual '" estado "' — a materia pode ser anterior a "
         "este rito, ou o rito ter sido trocado sob os pes dela (o versionamento de template e' por copia "
         "integral). Nenhum ato e' possivel ate' alguem reconciliar rito e estado.")

    (:terminal estado-no-template)
    (str "'" estado "' e' estado TERMINAL neste rito: o processo legislativo acabou aqui, e nao ha' ato "
         "a praticar — nao e' falta de permissao, e' fim de rito.")

    :else
    (str "o rito nao declara nenhum ato a partir de '" estado "', e tampouco marca '" estado "' como fim "
         "de processo: a materia esta' presa num beco. A saida depende de corrigir a configuracao do "
         "rito, nao de tentar de novo.")))

(defn- anotar-recebimentos
  "Fatia 2b — cada linha do historico ganha `:recebimento` ({:recebido-por-nome :recebido-em
  :assinatura-algoritmo}, ou nil quando a movimentacao nao foi recebida — ou nao exigia recebimento).
  `recebimentos` = transicao-id -> recibo (db/recebimento/recebimentos-da-proposicao). O NOME vem do seam
  `nome-na-casa` do host (§22.5.3 — o legislativo guarda o id de identidade e nunca soube o nome; mesma porta
  da folha de sessao): so' sai nome de quem tem vinculo NESTA Casa, senao nil e a tela diz 'recebida'. Um
  nome por PESSOA, nao por linha: a mesma servidora recebe dezenas de cargas."
  [nome-na-casa ente-id historico recebimentos]
  (let [nomes (into {} (map (fn [id] [id (nome-na-casa ente-id id)]))
                    (distinct (keep :recebido-por (vals recebimentos))))]
    (mapv (fn [linha]
            (assoc linha :recebimento
                   (when-let [r (get recebimentos (:id linha))]
                     {:recebido-por-nome (get nomes (:recebido-por r))
                      :recebido-em (:recebido-em r)
                      :assinatura-algoritmo (:assinatura-algoritmo r)})))
          historico)))

(defn buscar-tramitacao
  "Fatia 3 — a LEITURA da tramitacao: o HISTORICO da materia + os GATILHOS que a Casa declara a partir do
  estado ATUAL. nil = materia inexistente no tenant (a borda traduz -> 404), mesmo contrato de
  `buscar-ficha-materia`/`buscar-proposicao-detalhe`.

  NAO AVALIA GUARD, e a decisao e' de desenho (ver `logic/gatilhos-possiveis`, onde os motivos completos —
  e a nota [REVERTIDO por ADR-0004] sobre a razao que caiu — vivem por extenso): guard consulta FATO (N
  avaliacoes por GET) e guard LANCA (derrubaria tambem o historico). A honestidade e' paga em
  `pode-ser-recusado`, por gatilho, que e' mais informacao que um aviso generico.

  A SONDA DE TRUNCAMENTO: o Repo e' consultado com `limite+1`. Com um teto simples, `n` itens devolvidos
  sao indistinguiveis de 'a materia so' teve n atos', e o operador leria a linha mais antiga MOSTRADA como
  o comeco do processo — uma mentira por omissao num artefato de auditoria. O item excedente nunca vai p/ a
  resposta: ele so' existe p/ a borda poder DIZER que ha' mais. `take-last` porque `historico-da-proposicao`
  ja' devolve os N mais recentes em ordem cronologica — o que sobra p/ descartar e' o mais ANTIGO."
  ([repo-legislativo ente-id proposicao-id limite]
   (buscar-tramitacao repo-legislativo (constantly nil) ente-id proposicao-id limite))
  ([repo-legislativo nome-na-casa ente-id proposicao-id limite]
  (let [{:keys [proposicao historico candidatas estado-no-template recebimentos recebimento-pendente]}
        (repo/tramitacao-da-proposicao repo-legislativo ente-id proposicao-id (inc limite))]
    (when proposicao
      (let [truncado? (> (count historico) limite)
            gatilhos  (logic/gatilhos-possiveis candidatas)
            estado    (:estado proposicao)
            historico (if truncado? (vec (take-last limite historico)) (vec historico))]
        {:proposicao-id proposicao-id
         :estado-atual estado
         :template-id (:template-id proposicao)
         ;; tri-valorado de proposito: true/false = o rito declara o estado e diz se e' fim; nil = o rito
         ;; NAO declara o estado (ou nao ha' rito). "Desconhecido" e "nao-terminal" sao diagnosticos
         ;; diferentes, e achatar os dois em `false` apagaria justamente o caso que pede intervencao.
         :estado-terminal (:terminal estado-no-template)
         :historico (anotar-recebimentos nome-na-casa ente-id historico recebimentos)
         :historico-truncado truncado?
         ;; fatia 2b: a carga que a materia espera AGORA. Enquanto houver, a engine recusa qualquer ato
         ;; (`:recebimento-pendente`) — a tela oferece RECEBER antes de oferecer tramitar.
         :recebimento-pendente recebimento-pendente
         :gatilhos-possiveis gatilhos
         :nota (when (empty? gatilhos)
                 (nota-de-lista-vazia estado {:template-id (:template-id proposicao)
                                              :estado-no-template estado-no-template}))})))))

(defn- nomear-comissoes
  "Decora cada mapa de `ms` (que tem `:comissao-id`) com `:comissao-nome`, resolvendo os N ids numa
  chamada so'. A CHAVE existe sempre, mesmo quando o resolver nao acha nada: o wire/out nao pode depender
  de o guard ref ter dono — id orfao (ou de outra Casa) vira nil, e o FE mostra rotulo honesto.

  `resolver-comissoes` e' a fn injetada pelo HOST (§22.5.3, exceção nomeada — o legislativo NUNCA importa
  `cadastros`), mesma forma de `resolver-vereador`. Lista vazia nao chama o resolver: materia sem parecer
  nao paga uma transacao a mais."
  [resolver-comissoes ente-id ms]
  (if (empty? ms)
    ms
    (let [nomes (resolver-comissoes ente-id (mapv :comissao-id ms))]
      (mapv #(assoc % :comissao-nome (get nomes (:comissao-id %))) ms))))

(defn- nomear-relatores
  "ADR-0019: decora cada parecer (`:relator-id`) com `:relator-nome`, num lote so'. A chave existe sempre (nil sem relator
  ou sem cadastro nesta Casa: o FE mostra 'sem relator', nunca inventa)."
  [nomes-de-vereadores ente-id ms]
  (if (empty? ms)
    ms
    (let [nomes (nomes-de-vereadores ente-id (keep :relator-id ms))]
      (mapv #(assoc % :relator-nome (get nomes (:relator-id %))) ms))))

(defn buscar-ficha-materia
  "Onda B Slice 3 — ficha completa da materia (proposicao + texto + tramitacao + apensadas + emendas +
  pareceres), mesmo gate grosso das rotas irmas (papel 'secretario', sem policy fina adicional). nil se a
  proposicao nao existe no tenant (-> 404 na borda), mesmo contrato de `buscar-proposicao-ficha`. `:texto`
  sai daqui JA extraido (:texto-inline da linha de dominio, ou nil) — mesma disciplina de
  `buscar-proposicao-ficha` (review MENOR fe-9-ficha-materia): o CONTROLLER e' quem decide o nome de campo
  do model, nunca o diplomat/http/in (que so' compoe adapters/out ja' prontos).

  Cada parecer sai com `:comissao-nome` (defeito #11 do ledger de prontidao — a aba mostrava o UUID)."
  ([repo-legislativo resolver-comissoes ente-id id]
   (buscar-ficha-materia repo-legislativo resolver-comissoes (constantly nil) ente-id id))
  ([repo-legislativo resolver-comissoes nome-na-casa ente-id id]
   (buscar-ficha-materia repo-legislativo resolver-comissoes nome-na-casa (constantly {}) ente-id id))
  ([repo-legislativo resolver-comissoes nome-na-casa nomes-de-vereadores ente-id id]
   (let [{:keys [proposicao texto recebimentos] :as ficha} (repo/ficha-completa-da-proposicao repo-legislativo ente-id id)]
     (when proposicao
       (-> (dissoc ficha :recebimentos)
           (assoc :texto (:texto-inline texto))
           ;; fatia 2b: o historico da ficha mostra quem recebeu cada movimentacao (mesma anotacao da rota irma)
           (update :tramitacao #(anotar-recebimentos nome-na-casa ente-id % (or recebimentos {})))
           (update :pareceres #(nomear-relatores nomes-de-vereadores ente-id (nomear-comissoes resolver-comissoes ente-id %))))))))

;; ========================= Onda B Slice 5: editor/emissao do parecer =========================

(defn- nomear-comissao-do-parecer
  "Mesma decoracao de `nomear-comissoes`, para o agregado de UM parecer (`{:parecer :objeto ...}`).
  `nil` passa reto — o gate 404 da borda vem antes e nao paga transacao de cadastros."
  [resolver-comissoes ente-id dados]
  (when dados
    (update dados :parecer #(first (nomear-comissoes resolver-comissoes ente-id [%])))))

(defn buscar-parecer-editor
  "Onda B Slice 5 — leitura agregada p/ o editor de parecer (parecer + objeto + texto rascunho/vigente),
  mesmo gate grosso das rotas irmas (papel 'secretario', sem policy fina adicional). nil se o parecer nao
  existe no tenant (-> 404 na borda), mesmo contrato de buscar-ficha-materia/buscar-proposicao-ficha.

  O parecer sai com `:comissao-nome` resolvido pelo host (defeito #11 do ledger de prontidao — o
  subtitulo e o rail de /parecer/:id mostravam o UUID da comissao)."
  [repo-legislativo resolver-comissoes ente-id id]
  (nomear-comissao-do-parecer resolver-comissoes ente-id
                              (repo/buscar-parecer-para-editor repo-legislativo ente-id id)))

(defn salvar-rascunho-parecer
  "Onda B Slice 5 — cria uma nova versao 'rascunho' do texto do parecer. `m` ja' vem coagido pelo
  adapters/in."
  [repo-legislativo ente-id m]
  (repo/nova-versao-parecer! repo-legislativo ente-id m))

(defn emitir-parecer
  "Onda B Slice 5 (+Onda C4: assinatura) — promove o rascunho a vigente (se houver, assinando-o —
  Repo/emitir-parecer!) + registra o voto do relator + tenta transicionar (gatilho recebido, best-effort),
  1 tx. `registro` (RegistroFatos do motor, injetado pelo host) e' o mesmo que `transicionar-parecer!` ja
  recebe. `assinador` (porta AssinadorICP, construida pelo diplomat — mesmo padrao de
  gerar-artefato-publicacao!) e' repassado pro Repo dentro do mapa de args, nunca guardado aqui."
  [repo-legislativo registro assinador ente-id m]
  (repo/emitir-parecer! repo-legislativo ente-id registro (assoc m :assinador assinador)))

(defn meu-parecer-editor
  "Onda C4 (feature 7.3) — leitura do parecer p/ o vereador-relator (mesmo agregado do editor desktop),
  GATE DE POSSE: so' devolve se o vereador ATOR e' de fato o relator deste parecer. Anti-forja: vereador-id
  SEMPRE resolvido do proprio ator (mesmo contrato de meu-painel/acusar-ciencia), nunca de path/corpo. nil
  (ator sem cadastro vinculado OU nao e' o relator OU parecer inexistente) -> nil — a borda traduz -> 404,
  sem distinguir motivo (mesmo contrato de parecer-elegivel-para-ciencia?)."
  [repo-legislativo resolver-vereador resolver-comissoes ator id]
  (when-let [vereador-id (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (when (repo/relator-do-parecer? repo-legislativo (:ente-id ator) vereador-id id)
      (nomear-comissao-do-parecer resolver-comissoes (:ente-id ator)
                                  (repo/buscar-parecer-para-editor repo-legislativo (:ente-id ator) id)))))

(defn meu-emitir-parecer
  "Onda C4 — 'assinar em 2 toques': o vereador-relator emite (=assina) o PROPRIO parecer. MESMO gate de
  posse de meu-parecer-editor, ANTES de delegar pro Repo (que faz promover+voto+transicao+assinatura) —
  posse negada NUNCA chega a chamar emitir-parecer! (nil -> a borda traduz -> 404)."
  [repo-legislativo registro assinador resolver-vereador ator id m]
  (when-let [vereador-id (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (when (repo/relator-do-parecer? repo-legislativo (:ente-id ator) vereador-id id)
      (repo/emitir-parecer! repo-legislativo (:ente-id ator) registro (assoc m :assinador assinador)))))

(defn encerrar-votacao
  "Encerra a votacao `votacao-id` da sessao `sessao-id` (authz na sessao + amarra). `m` carrega o id
  (=votacao-id), lock-version, base-membros e resultado. Devolve o snapshot apurado ou nil se a votacao nao
  existe nesta sessao (-> 404). Pre-condicoes de borda usam a votacao JA carregada — em vez de propagarem como
  500 do db: (a) votacao terminal nao reencerra -> `:conflito/votacao-terminal` (-> 409, T2 grupo A achado #3
  do ledger Fase 8 — ERA `:validacao/invalido` (400), mas 400 diz 'conserte seu pedido' e o MESMO corpo teria
  funcionado segundos antes; 409 diz 'seu pedido era valido, o recurso mudou', consistente com os outros 5
  conflitos de 'ja terminal' deste grupo de 17 rotas — transicao/pauta/inscricao/fala/vinculo, todos 409); (b)
  modalidade 'simbolica' (aclamacao) exige `resultado` explicito -> `:validacao/invalido` (-> 400, este SIM e'
  erro de pedido, nao de estado).

  Sessao ja fechada -> `sessao-autorizada` lanca `:conflito/sessao-fechada` (-> 409, ledger Fase 8 achado #4/#5).

  SEGURANCA (sec MEDIUM-1 FECHADO): `base-membros` (denominador do quorum p/ maioria absoluta/qualificada)
  e' resolvido SERVER-SIDE via `membros-da-casa` (relacao `cadastros/membros_da_casa`, injetada pelo host como
  `consultar-sessao` — legislativo NAO importa cadastros, §22.10) e SOBRESCREVE qualquer valor do corpo. Antes
  vinha do corpo do request: um secretario comprometido faria `base-membros=1` e aprovaria tudo. Agora o campo
  saiu de `campos-encerrar` no adapters/in (valor forjado no corpo e' DESCARTADO na borda) E este controller
  o SOBRESCREVE com a composicao REAL da Casa (mandato vigente hoje, fuso civil) — dupla defesa; o snapshot
  append-only segue gravando o base_membros USADO (auditavel)."
  [repo-leg consultar-sessao sessao-fechada? membros-da-casa ator sessao-id votacao-id m]
  (when (sessao-autorizada consultar-sessao sessao-fechada? ator sessao-id)
    (let [ente-id (:ente-id ator)]
      (when-let [v (votacao-na-sessao repo-leg ente-id sessao-id votacao-id)]
        (when (contains? logic/estados-votacao-terminais (:estado v))
          (throw (ex-info "votacao ja em estado terminal (encerrada/anulada)"
                          {:tipo :conflito/votacao-terminal :estado (:estado v)})))
        (when (and (= "simbolica" (:modalidade v)) (nil? (:resultado m)))
          (throw (ex-info "votacao simbolica exige resultado explicito"
                          {:tipo :validacao/invalido :campos [:resultado]})))
        ;; O denominador NUNCA vem do cliente: computa-se aqui, da composicao real da Casa, e sobrescreve `m`.
        (repo/encerrar-votacao! repo-leg ente-id (assoc m :base-membros (membros-da-casa ente-id)))))))

;; ========================= Onda B Slice 6: expediente (documentos + protocolo geral) =========================

(defn listar-modelos-documento
  "Onda B Slice 6 — modelos ATIVOS do tenant p/ o seletor da aba 'Gerar documento' (mesmo gate grosso das
  rotas irmas, papel 'secretario', sem policy fina adicional)."
  [repo-legislativo ente-id]
  (repo/listar-modelos-ativos repo-legislativo ente-id))

(defn buscar-modelo-documento
  "Onda B Slice 6 (fatia de escrita) — GET /legislativo/documento-modelos/:id, a tela de gestao (aba
  'Modelos'). nil (inexistente no tenant) -> 404 na borda, mesmo contrato das leituras irmas."
  [repo-legislativo ente-id id]
  (repo/buscar-modelo repo-legislativo ente-id id))

(defn criar-modelo-documento!
  "Onda B Slice 6 (fatia de escrita) — cria um modelo novo (config do tenant). GUARD DE CHAVE DUPLICADA
  (pre-condicao de borda, mesmo racional de gerar-autografo/GUARD DE DUPLICIDADE acima): pre-checa
  `modelo-por-chave` ANTES de inserir, em vez de deixar a excecao opaca do UNIQUE(ente_id,chave) do
  db/documento-modelo.clj subir crua ate' o interceptor global -> 500. `:tipo :validacao/invalido` (mesmo
  ramo -> 400 do guard irmao). `m` ja' vem coagido pelo adapters/in (id/chave/nome/tipo-documento/
  corpo-template/created-by)."
  [repo-legislativo ente-id m]
  (when (repo/modelo-por-chave repo-legislativo ente-id (:chave m))
    (throw (ex-info "criar-modelo-documento!: chave ja utilizada por outro modelo neste ente"
                    {:tipo :validacao/invalido :chave (:chave m)})))
  (repo/criar-modelo! repo-legislativo ente-id m))

(defn atualizar-modelo-documento!
  "Onda B Slice 6 (fatia de escrita) — edita nome/corpo/ativo (CAS por lock-version). O diplomat PRE-CHECA
  404 (buscar-modelo-documento) antes de chamar este controller (mesmo contrato de editar-documento) — o
  que sobra pra `db/documento-modelo.clj atualizar!` lancar e' so' o conflito de versao real (concorrencia
  entre o GET que abriu o editor e este PATCH), `:tipo :validacao/invalido` -> 400 (mesmo contrato de
  db/documento.clj editar-rascunho!/emitir!, disciplina 5 — nao inventar um segundo formato de CAS)."
  [repo-legislativo ente-id m]
  (repo/atualizar-modelo! repo-legislativo ente-id m))

(defn gerar-documento
  "Onda B Slice 6 — gera um documento a partir de um modelo (feature 3.22, merge do dominio). Busca o modelo
  PRIMEIRO — `tipo-documento`/`corpo-template` vem DAI, nunca do cliente (o wire/adapters-in ja' fecha essa
  porta; este controller e' quem RESOLVE o modelo de fato). nil se o modelo nao existe no tenant (-> 404 na
  borda, mesmo contrato das leituras irmas). `m` ja' vem coagido pelo adapters/in (id/modelo-id/assunto/
  dados/created-by, com `:id` JA' gerado — o diplomat re-le o documento por esse mesmo id apos o sucesso).
  `logic/renderizar-documento` (via Repo/gerar-documento!) lanca `:validacao/invalido` (-> 400) se `dados` nao
  cobrir algum placeholder do template — nunca 500 por um formulario incompleto."
  [repo-legislativo ente-id m]
  (when-let [modelo (repo/buscar-modelo repo-legislativo ente-id (:modelo-id m))]
    ;; fatia 2a: o modelo de requerimento do VEREADOR vira proposicao pela borda /meu, nunca documento
    ;; administrativo (a tabela `documento` nem aceita o tipo — sem este guard, o CHECK viraria 500).
    (when (= logic/tipo-modelo-requerimento (:tipo-documento modelo))
      (throw (ex-info "gerar-documento: modelo de requerimento de vereador nao gera documento do Expediente"
                      {:tipo :validacao/invalido :campos [:modelo-id]})))
    (repo/gerar-documento! repo-legislativo ente-id
                           (merge m {:ente-id ente-id
                                     :tipo-documento (:tipo-documento modelo)
                                     :corpo-template (:corpo-template modelo)}))))

(defn buscar-documento-editor
  "Onda B Slice 6 — leitura agregada p/ a aba 'Gerar documento': {:documento :protocolo}. `:protocolo` vem
  ENRIQUECIDO (numero/ano) SE o documento ja' tiver `protocolo-geral-id` (pos 'Protocolar e numerar'); nil
  enquanto 'rascunho' — o editor nao faz um segundo GET so' pra mostrar o numero apos protocolar. nil
  (documento inexistente no tenant) -> 404 na borda, mesmo contrato de buscar-parecer-editor. Delega a
  `Repo/buscar-documento-para-editor` (review clojure+database MAJOR — NUMA UNICA tx, mesma disciplina de
  buscar-parecer-editor/buscar-parecer-para-editor; antes eram 2 chamadas publicas do Repo = 2 tx)."
  [repo-legislativo ente-id id]
  (repo/buscar-documento-para-editor repo-legislativo ente-id id))

(defn editar-documento
  "Onda B Slice 6 — reescreve corpo/assunto de um documento 'rascunho' (CAS). Mesmo gate grosso; `m` ja' vem
  coagido pelo adapters/in."
  [repo-legislativo ente-id m]
  (repo/editar-documento! repo-legislativo ente-id m))

(defn protocolar-documento
  "Onda B Slice 6 — o CTA 'Protocolar e numerar' do mockup: numera no Protocolo Geral (objeto-tipo
  'documento', sentido 'expedido') E emite o documento (rascunho -> emitido), 1 tx (Repo/protocolar-
  documento!). `ano` e' a data civil RESOLVIDA PELO DIPLOMAT (kernel/tempo — mesmo padrao de
  emitir-parecer/`agora`); este controller so' junta ao `m` ja' coagido pelo adapters/in (documento-id/
  lock-version/ator-id)."
  [repo-legislativo ente-id ano m]
  (repo/protocolar-documento! repo-legislativo ente-id (assoc m :ano ano)))

(defn listar-protocolo-do-ano
  "Onda B Slice 6 — o 'Livro do Protocolo Geral' (feature 3.23) do ano corrente, mesmo gate grosso. `ano`
  resolvido pelo diplomat (kernel/tempo), mesmo padrao de protocolar-documento."
  [repo-legislativo ente-id ano]
  (repo/protocolos-do-ano repo-legislativo ente-id ano))

;; ========================= Onda B Slice 7: pos-aprovacao (autografo + sancao/veto) =========================

(defn buscar-pos-aprovacao
  "Onda B Slice 7 — leitura composta {:autografo :tramitacao-executiva} da proposicao `proposicao-id`
  (mesmo gate grosso das rotas irmas, papel 'secretario', sem policy fina adicional). PRE-CHECK a
  existencia da proposicao no tenant (mesma disciplina de pre-check de editar-proposicao-handler/
  protocolar-documento-handler): sem isso, uma proposicao inexistente devolveria {:autografo nil
  :tramitacao-executiva nil} (200 vazio) em vez de 404 — Repo/buscar-pos-aprovacao NAO checa a proposicao
  por design (spec: sem short-circuit no nil do autografo; a decisao 404-vs-corpo-parcial e' deste
  controller). nil (proposicao inexistente no tenant) -> 404 na borda."
  [repo-legislativo ente-id proposicao-id]
  (when (:proposicao (repo/buscar-proposicao-detalhe repo-legislativo ente-id proposicao-id))
    (repo/buscar-pos-aprovacao repo-legislativo ente-id proposicao-id)))

(defn gerar-autografo
  "Onda B Slice 7 — gera o autografo (numera gapless) + abre a tramitacao executiva 'aguardando' NUMA
  UNICA tx (Repo/gerar-autografo-e-abrir-tramitacao!). Resolve `destinatario-texto` a partir do municipio
  do ente ('Prefeito Municipal de <municipio>', reusa `resolver-municipio` — mesmo padrao injetado de
  criar-proposicao). `texto-versao-id` NAO sai daqui: o Repo o le da VOTACAO que aprovou (T3-A2). `ano` vem do diplomat (kernel/
  tempo, mesmo padrao de protocolar-documento — o ano do AUTOGRAFO e' o ano civil da geracao, escopo do
  numerador gapless 'autografo:ano', nao necessariamente o ano de protocolo da proposicao).

  GUARD DE APROVACAO (T3-A, o achado mais grave da T3 — ledger Fase 11). RODA PRIMEIRO, e de proposito:
  'esta materia nunca foi aprovada' e' a verdade que o operador precisa ouvir, mesmo quando ha' tambem
  duplicidade ou falta de texto. A pre-condicao NAO e' `proposicao.estado = 'aprovada'`: aquele rotulo e'
  texto livre, chave de estado de TEMPLATE (config do tenant, Inv.4 — cravar a string no codigo seria
  vocabulario de camara dentro do motor), e nenhuma rota HTTP o move hoje. E' o ATO: votacao encerrada com
  resultado 'aprovada' sobre esta proposicao (Repo/proposicao-aprovada-em-votacao?). 409, nao 400 — o
  pedido estava correto, o recurso e' que nao chegou la' (mesma regua dos outros 6 conflitos de estado
  desta borda, ledger Fase 8 achado #3). O Repo REVERIFICA dentro da tx da escrita: aqui mora a mensagem,
  la' mora a atomicidade.

  GUARD DE DUPLICIDADE (pre-condicao de borda, mesmo racional de encerrar-votacao — vira
  :validacao/invalido usando um recurso JA carregado, em vez de propagar a excecao opaca do UNIQUE
  (ente_id, proposicao_id) do db/autografo.clj como 500): uma proposicao que ja' tem autografo lanca ANTES
  de qualquer escrita nova.

  GUARD DE TEXTO DELIBERADO (era o 'guard de texto vigente'; virou isto em T3-A2). Protocolar sem texto e'
  permitido (Onda B Slice 2), entao uma materia pode ser aprovada sem NUNCA ter tido versao vigente — e o
  autografo e' o ARTEFATO LEGAL, nao pode nascer vazio (CHECK autografo_efetivado_tem_texto). Mudou a
  PERGUNTA: nao e' mais 'ha texto vigente AGORA?', e sim 'a votacao que aprovou registrou QUAL texto foi
  deliberado?'. A antiga passava quando o texto tinha sido trocado depois da aprovacao — que e' exatamente
  o buraco do A-2 — e reprovava quando a versao aprovada existia mas fora superada por outra.
  -> `:conflito/aprovacao-sem-texto` -> 409 (estado do recurso, nao erro de corpo).

  nil se a proposicao nao existe no tenant (-> 404 na borda). `m` ja' vem coagido pelo adapters/in (id do
  autografo/prazo-resposta-em/created-by; SEM ano/destinatario-texto/texto-versao-id, injetados aqui)."
  [repo-legislativo resolver-municipio ente-id ano m]
  (let [proposicao-id (:proposicao-id m)
        {:keys [proposicao]} (repo/buscar-proposicao-detalhe repo-legislativo ente-id proposicao-id)]
    (when proposicao
      (when-not (repo/proposicao-aprovada-em-votacao? repo-legislativo ente-id proposicao-id)
        (throw (ex-info "gerar-autografo: a materia nao foi aprovada em votacao pela Camara"
                        {:tipo :conflito/proposicao-nao-aprovada :proposicao-id proposicao-id})))
      (when (repo/autografo-da-proposicao repo-legislativo ente-id proposicao-id)
        (throw (ex-info "gerar-autografo: a proposicao ja tem autografo (UNIQUE por proposicao)"
                        {:tipo :validacao/invalido :proposicao-id proposicao-id})))
      (when (nil? (:texto-versao-id (repo/aprovacao-vigente repo-legislativo ente-id proposicao-id)))
        (throw (ex-info "gerar-autografo: a votacao que aprovou esta materia nao registrou qual texto foi deliberado"
                        {:tipo :conflito/aprovacao-sem-texto :proposicao-id proposicao-id})))
      (let [{:keys [municipio-nome]} (resolver-municipio ente-id)]
        ;; T3-A2: `texto-versao-id` NAO se resolve aqui. O Repo o le da VOTACAO que aprovou (a versao
        ;; deliberada, congelada na abertura) — o vigente de agora pode ser outro.
        (repo/gerar-autografo-e-abrir-tramitacao! repo-legislativo ente-id
          (merge m {:ano ano :destinatario-texto (str "Prefeito Municipal de " municipio-nome)}))))))

(defn buscar-tramitacao-executiva
  "Onda B Slice 7 — busca a tramitacao executiva pelo SEU PROPRIO id (mesmo gate grosso). nil se inexistente
  no tenant (-> 404 na borda)."
  [repo-legislativo ente-id id]
  (repo/buscar-tramitacao-executiva repo-legislativo ente-id id))

(defn buscar-tramitacao-por-autografo
  "Onda B Slice 7 — resolve a tramitacao executiva DO autografo `autografo-id` (mesmo gate grosso). nil se
  nao ha tramitacao executiva para este autografo no tenant (-> 404 na borda)."
  [repo-legislativo ente-id autografo-id]
  (repo/tramitacao-executiva-do-autografo repo-legislativo ente-id autografo-id))

(defn registrar-resposta-executivo
  "Onda B Slice 7 — 'Registrar retorno': POST /legislativo/autografos/:id/resposta (path :id =
  autografo-id). Resolve a tramitacao executiva DESTE autografo e registra a resposta nela (aguardando ->
  sancionado|sancao_tacita|vetado; CAS por lock-version). `m` ja' vem coagido pelo adapters/in (lock-
  version/resultado/veto-tipo/veto-razoes/updated-by); este controller injeta o `:id` (o da tramitacao
  executiva, NUNCA o path cru — o path e' o autografo). nil se nao ha tramitacao executiva para este
  autografo no tenant (-> 404 na borda, mesmo contrato de buscar-tramitacao-por-autografo)."
  [repo-legislativo ente-id autografo-id m]
  (when-let [{:keys [id]} (buscar-tramitacao-por-autografo repo-legislativo ente-id autografo-id)]
    (repo/registrar-resposta-executivo! repo-legislativo ente-id (assoc m :id id))))

(defn apreciar-veto
  "Onda B Slice 7 — carimba a apreciacao do veto pela camara: POST /legislativo/tramitacoes-executivas/:id/
  apreciacao (path :id = tramitacao-executiva-id, DIRETO — ao contrario de registrar-resposta-executivo,
  aqui nao ha' indireccao por autografo). vetado -> veto_mantido|veto_derrubado (CAS por lock-version). `m`
  ja' vem coagido pelo adapters/in (lock-version/resultado/veto-votacao-id/updated-by); este controller
  injeta o `:id` do path. nil se a tramitacao executiva nao existe no tenant (-> 404 na borda)."
  [repo-legislativo ente-id id m]
  (when (repo/buscar-tramitacao-executiva repo-legislativo ente-id id)
    (repo/apreciar-veto! repo-legislativo ente-id (assoc m :id id))))

;; ========================= Onda C1: borda /meu do vereador (home fora-de-sessao) =========================

(defn meu-painel
  "Onda C1 — leitura composta 'minhas proposicoes + meus pareceres + ciencias pendentes' do vereador ATOR
  (anti-forja: SEMPRE o vereador resolvido do proprio ator, nunca um vereador-id arbitrario do request).
  `resolver-vereador` e' a fn injetada pelo HOST (§22.5.3, exceção nomeada — o legislativo NUNCA importa
  cadastros; mesma inversao de dependencia de `consultar-sessao`/`resolver-municipio`) que resolve
  identidade-id->vereador-id NESTE ente. Um ator com papel 'vereador' mas SEM cadastro vinculado
  (`resolver-vereador` nil) devolve painel VAZIO — nao lanca: o gate grosso (exige-papel) ja' garantiu o
  papel, a ausencia de vinculo e' estado de dados, nao falha de autorizacao. Onda C3: tambem devolve o
  `vereador-id` resolvido (bootstrap de identidade p/ o cockpit — ver docstring do wire/out)."
  [repo-legislativo resolver-vereador ator]
  (if-let [vereador-id (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (assoc (repo/meu-painel repo-legislativo (:ente-id ator) vereador-id) :vereador-id vereador-id)
    {:vereador-id nil :proposicoes [] :proposicoes-truncado false :pareceres [] :pareceres-truncado false
     :ciencias [] :ciencias-truncado false}))

(defn acusar-ciencia
  "Onda C1 — 'Dar ciencia' (Task 3): registra a ciencia do vereador ATOR sobre `evento-ref` (anti-forja:
  o `vereador-id` e' SEMPRE o resolvido do proprio ator via `resolver-vereador`, nunca um valor do corpo
  do request — mesmo contrato de `meu-painel`). `m` ja' vem coagido pelo adapters/in (`evento-ref`/`tipo`/
  `id` novo). Ator sem cadastro vinculado (`resolver-vereador` nil) -> nil (a borda traduz -> 404, mesmo
  contrato de um recurso ausente do proprio ator — nunca 500).

  GUARD DE ELEGIBILIDADE (review CRITICO clojure+database+security, 3 revisores convergentes): `evento-
  ref` PRECISA ser um parecer publicado real sobre proposicao de autoria do proprio vereador — sem este
  guard, `acusar-ciencia!` gravava qualquer UUID sintaticamente valido na prova append-only (Inv.10), sem
  jamais poder ser corrigido (a tabela e' append-only puro). `evento-ref` nao-elegivel -> nil (-> 404,
  mesmo contrato de recurso ausente — o guard NAO distingue 'nao existe' de 'nao e' seu', por design:
  vazar essa distincao revelaria a existencia de pareceres de OUTROS vereadores)."
  [repo-legislativo resolver-vereador ator m]
  (when-let [vereador-id (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (when (repo/parecer-elegivel-para-ciencia? repo-legislativo (:ente-id ator) vereador-id (:evento-ref m))
      (repo/acusar-ciencia! repo-legislativo (:ente-id ator) (assoc m :vereador-id vereador-id)))))

;; ========================= Fatia 2a: o requerimento do VEREADOR (borda /meu) =========================

(defn- modelo-de-requerimento
  "O modelo `id` SE for um modelo de requerimento de vereador ATIVO desta Casa; senao nil (-> 404 na borda:
  modelo inexistente, inativo ou de outro tipo nao se distinguem — o vereador so' ve os que a lista oferece)."
  [repo-legislativo ente-id id]
  (let [m (repo/buscar-modelo repo-legislativo ente-id id)]
    (when (and m (:ativo m) (= logic/tipo-modelo-requerimento (:tipo-documento m))) m)))

(defn modelos-de-requerimento
  "GET /meu/modelos-requerimento — os modelos ATIVOS de requerimento da Casa, cada um com os CAMPOS que o
  formulario pede (os placeholders MENOS os automaticos: `logic/campos-do-requerimento`). Sem gate de posse:
  e' config da Casa, igual para todo vereador; o gate e' o papel 'vereador' da rota."
  [repo-legislativo ator]
  (->> (repo/listar-modelos-ativos repo-legislativo (:ente-id ator))
       (filter #(= logic/tipo-modelo-requerimento (:tipo-documento %)))
       (sort-by :nome)
       (mapv (fn [m] {:id (:id m) :nome (:nome m) :campos (logic/campos-do-requerimento (:corpo-template m))}))))

(defn- texto-do-requerimento
  [modelo campos autor hoje]
  (logic/renderizar-documento (:corpo-template modelo)
                              (logic/dados-do-requerimento campos {:nome-vereador (:nome autor) :hoje hoje})))

(defn previa-requerimento
  "POST /meu/requerimentos/previa — o texto FORMATADO que sera' assinado, sem gravar nada: o mesmo merge do
  protocolo (autor e data do servidor), para o vereador revisar antes de assinar. nil (ator sem cadastro de
  vereador, ou modelo fora da lista) -> 404. Campo faltando -> 400 (fail-closed do renderizador)."
  [repo-legislativo resolver-autor ator {:keys [modelo-id campos hoje]}]
  (when-let [autor (resolver-autor (:ente-id ator) (:identidade-id ator))]
    (when-let [modelo (modelo-de-requerimento repo-legislativo (:ente-id ator) modelo-id)]
      {:texto (texto-do-requerimento modelo campos autor hoje)})))

(defn meu-protocolar-requerimento
  "POST /meu/requerimentos — o vereador ASSINA e protocola o proprio requerimento (fatia 2a). Anti-forja: o
  AUTOR e' o vereador do LOGIN (`resolver-autor`, host wiring), nunca o corpo; `autor-tipo` 'vereador',
  `autor-id`/`autor-texto` = o cadastro dele. O texto e' o merge do modelo da Casa (o MESMO da previa); o
  `tipo-requerimento` (exigido pelo CHECK da mig 0013) e' o NOME do modelo. `assinador` (porta AssinadorICP,
  construida pelo diplomat — mesmo padrao de emitir-parecer) assina os bytes do texto no Repo, na MESMA tx
  do protocolo. nil (sem cadastro de vereador / modelo fora da lista) -> 404."
  [repo-legislativo resolver-municipio resolver-autor assinador ator {:keys [id modelo-id campos ementa hoje]}]
  (when-let [autor (resolver-autor (:ente-id ator) (:identidade-id ator))]
    (when-let [modelo (modelo-de-requerimento repo-legislativo (:ente-id ator) modelo-id)]
      (let [{:keys [uf municipio-nome]} (resolver-municipio (:ente-id ator))
            r (repo/protocolar! repo-legislativo (:ente-id ator)
                                {:id id :tipo "requerimento" :ano (.getYear ^java.time.LocalDate hoje)
                                 :ementa ementa :tipo-requerimento (:nome modelo)
                                 :autor-tipo "vereador" :autor-id (:id autor) :autor-texto (:nome autor)
                                 :texto (texto-do-requerimento modelo campos autor hoje)
                                 :created-by (:identidade-id ator)
                                 :assinador assinador :assinado-por (:identidade-id ator)
                                 :uf uf :municipio-nome municipio-nome})]
        (assoc r :ano (.getYear ^java.time.LocalDate hoje))))))

;; ========================= Fatia 2c: o requerimento COLETIVO (subscricao) =========================
;; Desenho: autoria-apoiamento.html. O autor redige e convida coautores; cada um confirma com a propria
;; assinatura (sobre o texto congelado) ou recusa; o autor protocola quando quiser, e quem nao confirmou NAO
;; CONSTA. Quem e' quem vem SEMPRE do login (resolver-autor/resolver-vereador do host), nunca do corpo.

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn colegas-para-subscricao
  "GET /meu/colegas — quem o vereador pode convidar: os vereadores com mandato VIGENTE nesta Casa (seam
  `colegas-da-casa` do host), menos ele mesmo. nil = o login nao e' vereador cadastrado aqui (-> 404)."
  [resolver-autor colegas-da-casa ator]
  (when-let [autor (resolver-autor (:ente-id ator) (:identidade-id ator))]
    (vec (remove #(= (:id autor) (:id %)) (colegas-da-casa (:ente-id ator))))))

(defn criar-proposta-requerimento
  "POST /meu/requerimentos/propostas — o requerimento COLETIVO: mesmo formulario do individual (modelo, campos,
  ementa) + os COAUTORES (vereador-ids). Grava a proposta com o texto CONGELADO (o mesmo merge do protocolo: autor
  e data do servidor) e um convite por coautor. Nada e' assinado nem numerado aqui.

  Coautor que nao compoe a Casa (mandato vigente), repetido, ou o proprio autor -> 400: o convite e' para um
  colega de plenario, e um id solto no corpo nao vira convite. nil (sem cadastro de vereador / modelo fora da
  lista) -> 404."
  [repo-legislativo resolver-autor colegas-da-casa ator {:keys [id modelo-id campos ementa coautores hoje]}]
  (when-let [autor (resolver-autor (:ente-id ator) (:identidade-id ator))]
    (when-let [modelo (modelo-de-requerimento repo-legislativo (:ente-id ator) modelo-id)]
      (let [pedidos (distinct coautores)
            _ (when (not= (count pedidos) (count coautores))
                (invalido! "coautor repetido" {:campos [:coautores]}))
            _ (when (some #(= (:id autor) %) pedidos)
                (invalido! "o autor nao se convida como coautor" {:campos [:coautores]}))
            por-id (into {} (map (juxt :id identity)) (colegas-da-casa (:ente-id ator)))
            fora (remove por-id pedidos)
            _ (when (seq fora)
                (invalido! "coautor que nao compoe a Casa (mandato vigente)" {:campos [:coautores]}))
            texto (texto-do-requerimento modelo campos autor hoje)]
        (repo/criar-proposta-requerimento! repo-legislativo (:ente-id ator)
          {:id id :autor-vereador-id (:id autor) :autor-identidade-id (:identidade-id ator)
           :autor-nome (:nome autor) :modelo-id modelo-id :tipo-requerimento (:nome modelo)
           :ementa ementa :texto texto
           :coautores (mapv (fn [vid] {:id vid :nome (:nome (por-id vid))}) pedidos)})
        (assoc (repo/buscar-proposta-requerimento repo-legislativo (:ente-id ator) id (:id autor))
               :sou-autor true)))))

(defn buscar-proposta-requerimento
  "GET /meu/requerimentos/propostas/:id — a proposta para quem PARTICIPA (autor ou convidado); nil p/ os demais
  (-> 404, nao vaza). Marca `:sou-autor` e a `:minha-subscricao` (o estado do convite do leitor, se for coautor)."
  [repo-legislativo resolver-vereador ator id]
  (when-let [vid (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (when-let [p (repo/buscar-proposta-requerimento repo-legislativo (:ente-id ator) id vid)]
      (assoc p :sou-autor (= vid (:autor-vereador-id p))
               :minha-subscricao (:estado (first (filter #(= vid (:vereador-id %)) (:subscricoes p))))))))

(defn responder-subscricao
  "POST /meu/requerimentos/propostas/:id/resposta — o coautor CONFIRMA (assina) ou RECUSA. nil = sem convite
  para este login (-> 404). Conflitos (ja' respondeu / ja' protocolada) propagam -> 409 na borda."
  [repo-legislativo resolver-vereador assinador ator id acao]
  (when-let [vid (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (repo/responder-subscricao! repo-legislativo (:ente-id ator)
                                {:proposta-id id :vereador-id vid :identidade-id (:identidade-id ator)
                                 :acao acao :assinador assinador})))

(defn protocolar-proposta-requerimento
  "POST /meu/requerimentos/propostas/:id/protocolo — o AUTOR assina e protocola o texto congelado (o mesmo que
  os coautores assinaram). Entram os coautores que CONFIRMARAM; os pendentes ficam registrados como 'nao
  consta'. nil = proposta inexistente ou de outro autor (-> 404). Ja' protocolada -> 409."
  [repo-legislativo resolver-municipio resolver-autor assinador ator id hoje]
  (when-let [autor (resolver-autor (:ente-id ator) (:identidade-id ator))]
    (when-let [prop (repo/buscar-proposta-requerimento repo-legislativo (:ente-id ator) id (:id autor))]
      (when (= (:id autor) (:autor-vereador-id prop))
        (let [{:keys [uf municipio-nome]} (resolver-municipio (:ente-id ator))
              ano (.getYear ^java.time.LocalDate hoje)]
          (when-let [r (repo/protocolar-proposta-requerimento! repo-legislativo (:ente-id ator) id (:id autor)
                         {:id (random-uuid) :tipo "requerimento" :ano ano
                          :ementa (:ementa prop) :tipo-requerimento (:tipo-requerimento prop)
                          :autor-tipo "vereador" :autor-id (:id autor) :autor-texto (:autor-nome prop)
                          :texto (:texto prop) :created-by (:identidade-id ator)
                          :assinador assinador :assinado-por (:identidade-id ator)
                          :uf uf :municipio-nome municipio-nome})]
            (assoc r :ano ano)))))))

(defn convites-de-subscricao
  "GET /meu/subscricoes — os pedidos de subscricao esperando a resposta deste vereador. Sem cadastro -> []."
  [repo-legislativo resolver-vereador ator]
  (if-let [vid (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (repo/convites-de-subscricao repo-legislativo (:ente-id ator) vid)
    []))

(defn propostas-abertas
  "GET /meu/requerimentos/propostas — as propostas do autor ainda esperando subscricoes. Sem cadastro -> []."
  [repo-legislativo resolver-vereador ator]
  (if-let [vid (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (repo/propostas-abertas-do-autor repo-legislativo (:ente-id ator) vid)
    []))

;; ========================= Faixa A / A.8: o resumo cidadao =========================

(defn resumo-da-proposicao
  "O resumo cidadao da materia: o ultimo rascunho da IA, a versao publicada e o historico, com a versao ATUAL do texto
  (para dizer o que ficou para tras). nil = proposicao inexistente no tenant."
  [repo-legislativo ente-id proposicao-id]
  (repo/resumo-da-proposicao repo-legislativo ente-id proposicao-id))

(defn rascunho-resumo
  "O CONTEUDO do rascunho, lido da IA pelo seam `ler-rascunho-resumo` (fn [ente-id rascunho-id] -> mapa | nil). So'
  pede a IA um id que o core registrou como rascunho pronto DESTA proposicao — nunca repassa id arbitrario. nil =
  proposicao, ponteiro ou rascunho inexistente. IA fora -> `:ia/indisponivel` (a borda traduz em 503, R-IA-1)."
  [repo-legislativo ler-rascunho-resumo ente-id proposicao-id rascunho-id]
  (when-let [{:keys [texto-base-sha256]} (repo/resumo-da-proposicao repo-legislativo ente-id proposicao-id)]
    (when-let [p (repo/buscar-rascunho-resumo-pronto repo-legislativo ente-id proposicao-id rascunho-id)]
      (when-let [r (ler-rascunho-resumo ente-id rascunho-id)]
        (assoc r :ponteiro p :texto-base-sha256 texto-base-sha256)))))

(defn publicar-resumo!
  "Publica a proxima versao do resumo cidadao (ele vai para o portal). Quem publica vem do `ator`. Com `rascunho-id`,
  tem de ser um rascunho PRONTO desta proposicao (`:conflito/rascunho-desconhecido`); modelo, versao do prompt e a
  versao do texto resumida vem do ponteiro, nunca do cliente. nil = proposicao inexistente."
  [repo-legislativo ator proposicao-id {:keys [texto rascunho-id]}]
  (repo/publicar-resumo! repo-legislativo (:ente-id ator)
    (cond-> {:proposicao-id proposicao-id :texto texto :publicado-por (:identidade-id ator)}
      rascunho-id (assoc :rascunho-id rascunho-id))))

;; ========================= Faixa B / B.8: a nota tecnica de conferencia =========================
;; O agente institucional da Casa (ADR-0013) le a proposicao protocolada e os dispositivos da LOM/RI e grava um
;; RASCUNHO de nota tecnica pela ferramenta `rascunho` do catalogo. A secretaria decide: aproveita (com ou sem edicao)
;; ou descarta. Nunca uma decisao da IA — nada anda na tramitacao por causa da nota.

(def teto-fila 200)

(defn registrar-nota-tecnica!
  "O rascunho do agente (o `ator` de agente institucional: agente e execucao da CREDENCIAL vem do `:via`, nunca da
  entrada). `execucao-ia` (opcional) e' o id da execucao NA IA: so' guardado, como chave de correlacao do 'Reportar
  erro' (8.4) — nao e' identidade nem autoridade e nenhuma decisao le esse valor. nil = proposicao inexistente nesta Casa."
  [repo-legislativo ator {:keys [proposicao-id texto citacoes paragrafos-sem-fonte incerteza motivos-incerteza modelo
                                 execucao-ia]}]
  (let [via (:via ator)]
    (repo/registrar-nota-tecnica! repo-legislativo (:ente-id ator)
                                  {:proposicao-id proposicao-id :agente (:agente via) :execucao-id (:execucao-id via)
                                   :execucao-ia execucao-ia
                                   :texto texto :citacoes (or citacoes []) :paragrafos-sem-fonte (or paragrafos-sem-fonte [])
                                   :incerteza incerteza :motivos-incerteza (or motivos-incerteza []) :modelo-llm-id modelo})))

(defn notas-tecnicas [repo-legislativo ente-id estado]
  (repo/notas-tecnicas repo-legislativo ente-id estado teto-fila))

(defn nota-tecnica [repo-legislativo ente-id id]
  (repo/nota-tecnica repo-legislativo ente-id id))

(defn decidir-nota-tecnica!
  "A secretaria decide uma nota pendente. 'aproveitada' guarda o texto que ela aproveitou (o editado, ou o do agente
  sem as marcas de citacao); 'descartada' nao guarda texto. Ja' decidida -> `:conflito/nota-decidida`. nil =
  inexistente nesta Casa."
  [repo-legislativo ator id {:keys [desfecho texto]}]
  (let [ente-id (:ente-id ator)]
    (when-let [n (repo/nota-tecnica repo-legislativo ente-id id)]
      (when-not (= "pendente" (:estado n))
        (throw (ex-info "nota ja' decidida" {:tipo :conflito/nota-decidida})))
      (or (repo/decidir-nota-tecnica! repo-legislativo ente-id id
                                      {:estado desfecho :decidida-por (:identidade-id ator)
                                       :texto-final (when (= "aproveitada" desfecho)
                                                      (if (str/blank? texto) (logic/texto-limpo (:texto n)) texto))})
          (throw (ex-info "nota ja' decidida" {:tipo :conflito/nota-decidida}))))))

;; ========================= Faixa B / B.7: o copiloto do requerimento =========================
;; O vereador descreve em palavras; a IA (seam `copiloto` do host, sobre o satelite) escolhe o modelo, preenche e
;; redige a justificativa citando a norma. E' RASCUNHO que volta ao formulario: nada e' gravado aqui, e o vereador
;; revisa e assina pelo fluxo de sempre (previa -> protocolo). O core CONFERE o que a IA devolveu contra os modelos
;; da Casa: modelo fora da lista ou campo que o modelo nao pede nao chegam a tela.

(def ^:private teto-campo 2000)

(defn- conferir-preenchimento
  "O preenchimento da IA, se couber num modelo da Casa: o modelo da lista, so' os campos dele, textos com teto."
  [modelos {:keys [modelo-id ementa campos]}]
  (when-let [m (some #(when (= (str (:id %)) (str modelo-id)) %) modelos)]
    (let [ementa (some-> ementa str str/trim)
          pedidos (set (:campos m))
          campos (into {} (keep (fn [[k v]]
                                  (let [k (name k)]
                                    (when (and (pedidos k) (string? v) (not (str/blank? v)))
                                      [k (subs v 0 (min teto-campo (count v)))]))))
                       campos)]
      (when-not (str/blank? ementa)
        {:modelo-id (:id m) :ementa (subs ementa 0 (min teto-campo (count ementa))) :campos campos}))))

(defn copiloto-requerimento
  "POST /meu/requerimentos/copiloto — o rascunho da IA para o formulario do vereador. nil = o login nao e' vereador
  cadastrado nesta Casa (-> 404). Sem modelo de requerimento na Casa, nem chama a IA. IA fora lanca
  `:ia/indisponivel` (a borda responde 503, R-IA-1: 'preencha o formulario')."
  [repo-legislativo resolver-autor copiloto ator {:keys [descricao]}]
  (when (resolver-autor (:ente-id ator) (:identidade-id ator))
    (let [modelos (modelos-de-requerimento repo-legislativo ator)]
      (if (empty? modelos)
        {:preenchimento nil :justificativa nil :indisponivel nil}
        (let [r (copiloto (:ente-id ator) {:descricao descricao :correlation_id (str (random-uuid))
                                           :modelos (mapv #(-> (select-keys % [:id :nome :campos])
                                                               (update :id str)) modelos)})
              p (some->> (:preenchimento r) (conferir-preenchimento modelos))
              j (:justificativa r)
              j (when (and p j (contains? (:campos p) (:campo j)))
                  (cond-> {:campo (:campo j)
                           :citacoes (mapv #(select-keys % [:fonte-id :rotulo :trecho :status]) (:citacoes j))
                           :paragrafos-sem-fonte (vec (:paragrafos-sem-fonte j))
                           :incerteza (str (:incerteza j)) :modelo (str (:modelo j))}
                    ;; 8.4: o id da execucao NA IA (nunca inventado): sem ele a tela nao oferece o reporte
                    (not (str/blank? (some-> (:execucao-id j) str))) (assoc :execucao-ia (str (:execucao-id j)))))]
          {:preenchimento p :justificativa j :indisponivel (get-in r [:indisponivel :mensagem])})))))

;; ========================= ADR-0019: caminho da comissao + parecer juridico =========================

(def teto-fila-juridico 100)

(defn- invalido! [msg campo] (throw (ex-info msg {:tipo :validacao/invalido :campo campo})))

(defn encaminhar-as-comissoes!
  "A secretaria encaminha a materia `proposicao-id` a `itens` [{:comissao-id :relator-id?}]: abre um parecer por
  comissao no rito de parecer da Casa. Comissao inexistente na Casa ou relator que nao e' vereador desta Casa -> 400.
  nil = materia inexistente (-> 404). Rito de parecer nao configurado -> `:config/sem-rito-de-parecer` (409 nomeado)."
  [repo-legislativo resolver-comissoes vereador-vinculado? nomes-de-vereadores ator proposicao-id itens]
  (let [ente-id (:ente-id ator)
        nomes-comissao (resolver-comissoes ente-id (mapv :comissao-id itens))]
    (doseq [{:keys [comissao-id relator-id]} itens]
      (when-not (contains? nomes-comissao comissao-id) (invalido! "comissao inexistente nesta Casa" :comissoes))
      (when (and relator-id (not (vereador-vinculado? ente-id relator-id)))
        (invalido! "relator nao e' vereador desta Casa" :relator-id)))
    (when-let [abertos (try (repo-juridico/abrir-pareceres-de-comissao! repo-legislativo ente-id
                                                               {:proposicao-id proposicao-id :comissoes itens
                                                                :created-by (:identidade-id ator)})
                            (catch clojure.lang.ExceptionInfo e
                              (if (= :sem-rito-de-parecer (:erro (ex-data e)))
                                (throw (ex-info "a Casa nao configurou o rito de parecer"
                                                {:tipo :config/sem-rito-de-parecer}))
                                (throw e))))]
      (let [nomes-relator (nomes-de-vereadores ente-id (keep :relator-id abertos))]
        (mapv #(assoc % :comissao-nome (get nomes-comissao (:comissao-id %))
                        :relator-nome (get nomes-relator (:relator-id %)))
              abertos)))))

(defn designar-relator-do-parecer!
  "Define (ou troca) o relator de um parecer de comissao ainda em curso. nil = parecer inexistente, ja' terminal, ou
  relator que nao e' vereador desta Casa (-> 404, sem distinguir)."
  [repo-legislativo vereador-vinculado? nomes-de-vereadores ator parecer-id relator-id]
  (let [ente-id (:ente-id ator)]
    (when (vereador-vinculado? ente-id relator-id)
      (when-let [r (repo-juridico/designar-relator-do-parecer! repo-legislativo ente-id parecer-id relator-id (:identidade-id ator))]
        (assoc r :relator-nome (get (nomes-de-vereadores ente-id [relator-id]) relator-id))))))

(defn- nomear-quem-pediu
  "Decora cada pedido com `:pedido-por-nome` (so' de quem tem vinculo nesta Casa; senao nil)."
  [nome-na-casa ente-id pedidos]
  (let [nomes (into {} (map (fn [id] [id (nome-na-casa ente-id id)])) (distinct (keep :pedido-por pedidos)))]
    (mapv #(assoc % :pedido-por-nome (get nomes (:pedido-por %))) pedidos)))

(defn- juridicos
  "ADR-0020 fatia 2: quem recebe o aviso automatico do pedido (as pessoas com `juridico`, pelo seam do host — legislativo
  nunca importa identidade, §22.10), menos quem pediu. Enriquecimento: o seam falhar nunca impede o pedido (fica no log)."
  [juridicos-a-avisar ente-id ator]
  (if juridicos-a-avisar
    (try (vec (remove #{(:identidade-id ator)} (juridicos-a-avisar ente-id)))
         ;; Throwable (menos falha da JVM): um seam sem a implementacao (AbstractMethodError) tambem nao derruba o ato
         (catch Throwable e
           (when (instance? VirtualMachineError e) (throw e))
           (log/warn e "aviso do pedido de parecer juridico: juridicos indisponiveis; o pedido segue sem aviso")
           []))
    []))

(defn pedir-parecer-juridico!
  "A secretaria pede o parecer (sobre uma materia, ou consulta avulsa). nil = materia inexistente (-> 404).
  `juridicos-a-avisar` (fn [ente-id] -> [identidade-id]) | nil: o aviso automatico na caixa do sistema (ADR-0020)."
  [repo-legislativo nome-na-casa juridicos-a-avisar ator m]
  (let [ente-id (:ente-id ator)]
    (when-let [p (repo-juridico/criar-pedido-juridico! repo-legislativo ente-id
                                              (assoc m :origem "secretaria" :pedido-por (:identidade-id ator)
                                                     :avisar (juridicos juridicos-a-avisar ente-id ator)))]
      (first (nomear-quem-pediu nome-na-casa ente-id [p])))))

(defn pedir-parecer-juridico-do-relator!
  "O relator do parecer de comissao pede o parecer juridico sobre a materia que relata. GATE DE POSSE (mesmo contrato de
  `meu-parecer-editor`): so' o relator do parecer; qualquer outro caso (ator sem cadastro, nao e' o relator, parecer
  inexistente ou sobre emenda) -> nil (-> 404)."
  [repo-legislativo resolver-vereador nome-na-casa juridicos-a-avisar ator parecer-id {:keys [assunto]}]
  (let [ente-id (:ente-id ator)]
    (when-let [vereador-id (resolver-vereador ente-id (:identidade-id ator))]
      (when (repo/relator-do-parecer? repo-legislativo ente-id vereador-id parecer-id)
        (let [pc (repo/buscar-parecer repo-legislativo ente-id parecer-id)]
          (when (= "proposicao" (:objeto-tipo pc))
            (when-let [p (repo-juridico/criar-pedido-juridico! repo-legislativo ente-id
                                                      {:proposicao-id (:objeto-id pc) :assunto assunto
                                                       :origem "relator" :pedido-por (:identidade-id ator)
                                                       :avisar (juridicos juridicos-a-avisar ente-id ator)})]
              (first (nomear-quem-pediu nome-na-casa ente-id [p])))))))))

(defn- ocultar-rascunho
  "O rascunho e' trabalho em curso do advogado: so' quem tem o papel `juridico` le o texto dele. A secretaria ve' o pedido
  e que o parecer esta' sendo redigido (estado 'rascunho'), sem o texto."
  [ator p]
  (if (and (= "rascunho" (get-in p [:parecer :estado])) (not (contains? (:papeis ator) "juridico")))
    (update p :parecer dissoc :relatorio :fundamentacao :conclusao)
    p))

(defn pedido-juridico [repo-legislativo nome-na-casa ator id]
  (let [ente-id (:ente-id ator)]
    (when-let [p (repo-juridico/pedido-juridico repo-legislativo ente-id id)]
      (ocultar-rascunho ator (first (nomear-quem-pediu nome-na-casa ente-id [p]))))))

(defn fila-juridica
  "A fila (secretaria e juridico). O rascunho e' do advogado: a secretaria ve' que esta' em redacao, sem a conclusao."
  [repo-legislativo nome-na-casa ator estado]
  (let [ente-id (:ente-id ator)]
    (mapv #(ocultar-rascunho ator %)
          (nomear-quem-pediu nome-na-casa ente-id
                             (repo-juridico/pedidos-juridicos repo-legislativo ente-id estado teto-fila-juridico)))))

(defn- decorado [nome-na-casa ente-id {:keys [pedido] :as r}]
  (if pedido (assoc r :pedido (first (nomear-quem-pediu nome-na-casa ente-id [pedido]))) r))

(defn cancelar-pedido-juridico!
  "Cancela um pedido pendente. {:pedido} | {:erro :nao-encontrado} | {:erro :nao-pendente}."
  [repo-legislativo nome-na-casa ator id]
  (let [ente-id (:ente-id ator)]
    (if-let [p (repo-juridico/pedido-juridico repo-legislativo ente-id id)]
      (if-let [c (when (= "pendente" (:estado p))
                   (repo-juridico/cancelar-pedido-juridico! repo-legislativo ente-id id (:identidade-id ator)))]
        {:pedido (ocultar-rascunho ator (first (nomear-quem-pediu nome-na-casa ente-id [(assoc c :parecer (:parecer p))])))}
        {:erro :nao-pendente})
      {:erro :nao-encontrado})))

(defn salvar-parecer-juridico!
  "O advogado grava o RASCUNHO. {:pedido} | {:erro kw} (ver db/parecer-juridico)."
  [repo-legislativo nome-na-casa ator pedido-id texto]
  (decorado nome-na-casa (:ente-id ator)
            (repo-juridico/salvar-parecer-juridico! repo-legislativo (:ente-id ator) pedido-id (:identidade-id ator) texto)))

(defn assinar-parecer-juridico!
  "O advogado assina. O snapshot (nome, OAB, qualificacao) sai do PERFIL JURIDICO do signatario (`perfil-juridico`, seam
  do host sobre `identidade`), nunca do corpo. `assinador` (AssinadorICP, hoje o STUB-ICP-v0) carimba os bytes canonicos
  do texto. Sem perfil -> {:erro :sem-perfil-juridico}."
  [repo-legislativo assinador perfil-juridico nome-na-casa ator pedido-id]
  (let [ente-id (:ente-id ator)]
    (if-let [{:keys [nome oab qualificacao]} (perfil-juridico ente-id (:identidade-id ator))]
      (decorado nome-na-casa ente-id
                (repo-juridico/assinar-parecer-juridico! repo-legislativo ente-id pedido-id
                                                {:por (:identidade-id ator) :nome nome :oab oab
                                                 :qualificacao qualificacao :assinador assinador}))
      {:erro :sem-perfil-juridico})))

(defn substituir-parecer-juridico!
  "O advogado abre o parecer que corrige o assinado. {:pedido} | {:erro kw}."
  [repo-legislativo nome-na-casa ator pedido-id]
  (decorado nome-na-casa (:ente-id ator)
            (repo-juridico/substituir-parecer-juridico! repo-legislativo (:ente-id ator) pedido-id (:identidade-id ator))))

(defn pareceres-juridicos-da-materia [repo-legislativo ente-id proposicao-id]
  (repo-juridico/pareceres-juridicos-da-materia repo-legislativo ente-id proposicao-id))

(defn pareceres-juridicos-publicos [repo-legislativo ente-id proposicao-id]
  (repo-juridico/pareceres-juridicos-publicos repo-legislativo ente-id proposicao-id))

(defn comissoes-da-casa
  "As comissoes (menos a Mesa) que a secretaria escolhe ao encaminhar uma materia. `comissoes-vigentes` e' o seam do host
  sobre `cadastros` (§22.5.3)."
  [comissoes-vigentes ente-id]
  (comissoes-vigentes ente-id))

;; ========================= ADR-0019 fatia 2a: a nota tecnica da IA como rascunho + antecipar o portal ==================

(defn usar-nota-como-rascunho!
  "O advogado usa a nota tecnica `nota-id` como rascunho do parecer: numa tx, abre/reaproveita o pedido da materia, cria o
  rascunho (texto da nota sem as marcas de citacao, SEM conclusao) e marca a nota como aproveitada. O texto da IA nunca e'
  parecer: o advogado o revisa, assume e assina. `casa-tem-juridico?` e' o seam do host sobre `identidade`; Casa sem
  juridico ativo -> `{:erro :sem-juridico-ativo}` (defensivo: o gate de papel ja' exige `juridico`).
  {:pedido} | {:erro :nao-encontrado | :nota-decidida | :ja-ha-rascunho | :sem-juridico-ativo}."
  [repo-legislativo casa-tem-juridico? nome-na-casa ator nota-id]
  (let [ente-id (:ente-id ator)]
    (if-not (casa-tem-juridico? ente-id)
      {:erro :sem-juridico-ativo}
      (decorado nome-na-casa ente-id
                (repo-nota-juridica/usar-nota-como-rascunho! repo-legislativo ente-id nota-id (:identidade-id ator))))))

(defn parametros-parecer-juridico
  "O que o `admin_ente` le: {:publicar-ao-assinar bool} (padrao false: o portal so' mostra depois da deliberacao)."
  [repo-legislativo ente-id]
  (repo-nota-juridica/parametros-parecer-juridico repo-legislativo ente-id))

(defn salvar-parametros-parecer-juridico!
  "O `admin_ente` liga/desliga a publicacao do parecer no portal ja' ao assinar. Devolve os parametros gravados."
  [repo-legislativo ator {:keys [publicar-ao-assinar]}]
  (repo-nota-juridica/salvar-parametros-parecer-juridico! repo-legislativo (:ente-id ator)
                                                          {:publicar-ao-assinar publicar-ao-assinar
                                                           :por (:identidade-id ator)}))
;; ========================= ADR-0019, Eixo 5 (fatia 2): o copiloto do relator =========================
;; No editor do parecer de comissao, o relator (ou a secretaria, que redige por ele) pede a IA (seam `copiloto` do host,
;; sobre o satelite) o RASCUNHO da analise de constitucionalidade e juridicidade: a IA le a materia (o texto PUBLICO,
;; `texto-para-ia`) e os dispositivos da LOM/Regimento da Casa e cita cada um. Nada e' gravado aqui: o texto volta ao
;; editor e o relator revisa e salva pelo fluxo de sempre (nova versao de texto). O core CONFERE o que a IA devolveu:
;; so' citacoes da propria materia ou de dispositivo da Casa, status conhecido, textos com teto; o texto limpo e os
;; pontos a confirmar sao recalculados aqui; incerteza 'normal' so' com tudo conferido.

(def ^:private teto-analise 20000)
(def ^:private teto-citacoes 60)
(def ^:private status-de-citacao #{"conferida" "sem_trecho" "trecho_nao_encontrado" "fonte_nao_lida"})
(def ^:private fonte-de-norma #"norma:[^\s|\]\"#]+#[^\s|\]\"]+")

(defn- com-teto [s n] (when (string? s) (subs s 0 (min n (count s)))))

(defn- conferir-citacao
  "A citacao, se for da propria materia (`materia:<id>`) ou de um dispositivo da Casa (`norma:<id>#<endereco>`) e com
  status conhecido; senao, cai."
  [proposicao-id {:keys [fonte-id rotulo trecho status]}]
  (let [fid (str fonte-id)]
    (when (and (or (= fid (str "materia:" proposicao-id)) (re-matches fonte-de-norma fid))
               (contains? status-de-citacao (str status)))
      {:fonte-id fid :rotulo (com-teto rotulo 500) :trecho (com-teto trecho 1000) :status (str status)})))

(defn conferir-analise
  "O rascunho que a IA devolveu -> o que a tela recebe (ver a secao). `normas-publicadas?` e' o que o CORE sabe da Casa
  (nunca o eco do satelite): sem normas -> 'sem-normas'; com dispositivo conferido citado -> 'citadas'; senao
  'sem-dispositivo'. Texto vazio -> sem analise (a tela manda redigir pelo editor)."
  [proposicao-id normas-publicadas? resposta]
  (let [a (:analise resposta)
        texto (com-teto (logic/texto-limpo (some-> (:texto a) str)) teto-analise)
        citacoes (into [] (comp (keep #(conferir-citacao proposicao-id %)) (take teto-citacoes)) (:citacoes a))
        sem-fonte (filterv int? (:paragrafos-sem-fonte a))
        tudo-conferido? (and (seq citacoes) (every? #(= "conferida" (:status %)) citacoes) (empty? sem-fonte))
        analise (when-not (str/blank? texto)
                  (cond-> {:texto texto :citacoes citacoes :paragrafos-sem-fonte sem-fonte
                           :pontos-a-confirmar (logic/pontos-a-confirmar texto)
                           :incerteza (if (and tudo-conferido? normas-publicadas? (= "normal" (:incerteza a)))
                                        "normal" "revisar_com_atencao")
                           :modelo (str (or (:modelo a) ""))}
                    ;; 8.4: o id da execucao NA IA (nunca inventado): sem ele a tela nao oferece o reporte
                    (not (str/blank? (some-> (:execucao-id a) str))) (assoc :execucao-ia (str (:execucao-id a)))))]
    {:analise analise
     :normas (cond (not normas-publicadas?) "sem-normas"
                   (some #(and (str/starts-with? (:fonte-id %) "norma:") (= "conferida" (:status %))) (:citacoes analise))
                   "citadas"
                   :else "sem-dispositivo")
     :indisponivel (when-not analise (get-in resposta [:indisponivel :mensagem]))}))

(defn- pedido-de-analise [t comissao-nome normas-publicadas?]
  {:proposicao_id (str (:proposicao-id t)) :tipo (:tipo t) :ano (:ano t) :sequencial (:sequencial t)
   ;; o satelite le no maximo 30 mil caracteres da materia; o teto aqui so' evita mandar um documento inteiro a toa
   :ementa (:ementa t) :texto (com-teto (:texto t) 100000) :autor_texto (:autor-texto t) :comissao comissao-nome
   :normas_publicadas (boolean normas-publicadas?) :correlation_id (str (random-uuid))})

(defn- analisar-parecer
  "`dados` = o agregado do editor JA' autorizado. So' parecer sobre proposicao (nil -> 404). IA fora lanca
  `:ia/indisponivel` (a borda responde 503, R-IA-1)."
  [repo-legislativo copiloto normas-publicadas? ente-id {:keys [parecer]}]
  (when (= "proposicao" (:objeto-tipo parecer))
    (when-let [t (repo/texto-para-ia repo-legislativo ente-id (:objeto-id parecer))]
      (let [publicadas? (boolean (normas-publicadas? ente-id))]
        (conferir-analise (:objeto-id parecer) publicadas?
                          (copiloto ente-id (pedido-de-analise t (:comissao-nome parecer) publicadas?)))))))

(defn copiloto-analise-parecer
  "POST /legislativo/pareceres/:id/copiloto — a secretaria, no editor do parecer. nil = parecer inexistente nesta Casa
  ou nao e' sobre proposicao (-> 404)."
  [repo-legislativo resolver-comissoes copiloto normas-publicadas? ator id]
  (let [ente-id (:ente-id ator)]
    (some->> (buscar-parecer-editor repo-legislativo resolver-comissoes ente-id id)
             (analisar-parecer repo-legislativo copiloto normas-publicadas? ente-id))))

(defn meu-copiloto-analise-parecer
  "POST /meu/pareceres/:id/copiloto — o vereador-relator. MESMA posse de `meu-parecer-editor` (so' o relator deste
  parecer; qualquer outro caso -> nil -> 404 sem distinguir), verificada ANTES de chamar a IA."
  [repo-legislativo resolver-vereador resolver-comissoes copiloto normas-publicadas? ator id]
  (some->> (meu-parecer-editor repo-legislativo resolver-vereador resolver-comissoes ator id)
           (analisar-parecer repo-legislativo copiloto normas-publicadas? (:ente-id ator))))

(defn meu-salvar-rascunho-parecer
  "PATCH /meu/pareceres/:id — o relator grava uma nova versao 'rascunho' do texto (Relatorio + Analise), o mesmo fluxo
  da secretaria (`salvar-rascunho-parecer`). MESMA posse de `meu-parecer-editor`: nil -> 404 sem distinguir motivo."
  [repo-legislativo resolver-vereador ator id m]
  (let [ente-id (:ente-id ator)]
    (when-let [vereador-id (resolver-vereador ente-id (:identidade-id ator))]
      (when (repo/relator-do-parecer? repo-legislativo ente-id vereador-id id)
        (repo/nova-versao-parecer! repo-legislativo ente-id m)))))

;; ========================= ADR-0021 Parte B: o julgamento das contas =========================
;; A prestacao do Prefeito (governo) protocola o PDL de autoria da comissao escolhida, na MESMA tx; a notificacao congela
;; o prazo de defesa; a defesa entra como documento; o estado e' derivado (logic/contas). A Mesa (gestao_camara) e' so'
;; acompanhamento. Quem pode cada rota e' o gate grosso da borda; a Casa e' a do ator (RLS).

(defn prestacoes-de-contas [repo-legislativo ente-id] (repo-contas/prestacoes repo-legislativo ente-id))

(defn prestacao-de-contas [repo-legislativo ente-id id] (repo-contas/prestacao repo-legislativo ente-id id))

(defn prestacao-da-proposicao
  "A prestacao cujo PDL e' `proposicao-id` (o painel de votacao trava quorum e pergunta), ou nil."
  [repo-legislativo ente-id proposicao-id]
  (repo-contas/prestacao-da-proposicao repo-legislativo ente-id proposicao-id))

(defn base-do-quorum
  "A composicao sobre a qual se contam os 2/3: a da votacao que julgou (gravada no encerramento) ou, antes dela, a de
  hoje (`membros-da-casa`, seam do host sobre cadastros — o mesmo denominador do encerramento)."
  [membros-da-casa ente-id p]
  (or (get-in p [:votacao :base-membros]) (membros-da-casa ente-id) 0))

(defn registrar-prestacao!
  "POST /contas. Governo: a comissao autora tem de ser comissao VIGENTE da Casa (`comissoes-vigentes`, seam do host) — o
  PDL nasce de autoria dela, com a ementa do exercicio, no ano de hoje; `resolver-municipio` da' a URN (eixo H). Mesa:
  sem PDL. A comissao inexistente -> :validacao/invalido (400); exercicio ja' registrado -> :conflito/prestacao-duplicada."
  [repo-legislativo resolver-municipio comissoes-vigentes ator hoje m]
  (let [ente-id (:ente-id ator)
        por (:identidade-id ator)
        pdl (when (logic-contas/governo? m)
              (let [comissao (some #(when (= (:comissao-autora-id m) (:id %)) %) (comissoes-vigentes ente-id))
                    {:keys [uf municipio-nome]} (resolver-municipio ente-id)]
                (when-not comissao
                  (throw (ex-info "a comissao autora nao e' comissao vigente desta Casa"
                                  {:tipo :validacao/invalido :campos [:comissao-autora-id]})))
                {:id (random-uuid) :tipo "projeto_decreto_legislativo" :ano (.getYear ^java.time.LocalDate hoje)
                 :uf uf :municipio-nome municipio-nome :ementa (logic-contas/ementa-do-pdl (:exercicio m))
                 :autor-tipo "comissao" :autor-texto (:nome comissao) :created-by por}))]
    (repo-contas/registrar-prestacao! repo-legislativo ente-id
                                      (cond-> (-> m (dissoc :comissao-autora-id) (assoc :por por))
                                        pdl (assoc :pdl pdl)))))

(defn atualizar-prestacao! [repo-legislativo ator id m]
  (repo-contas/atualizar-prestacao! repo-legislativo (:ente-id ator) id m (:identidade-id ator)))

(defn notificar-prestacao!
  "Registra a notificacao do responsavel. Data futura -> 400. {:prestacao} | {:erro kw}."
  [repo-legislativo ator hoje id {:keys [notificado-em] :as m}]
  (when (.isAfter ^java.time.LocalDate notificado-em ^java.time.LocalDate hoje)
    (throw (ex-info "a notificacao nao pode ter data futura" {:tipo :validacao/invalido :campos [:notificado-em]})))
  (repo-contas/notificar-prestacao! repo-legislativo (:ente-id ator) id (assoc m :por (:identidade-id ator))))

(defn- sha256-hex [^bytes b]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) (.digest (MessageDigest/getInstance "SHA-256") b))))

(defn anexar-documento-de-contas!
  "Um documento na prestacao (o tamanho ja' foi limitado na borda). O arquivo sobe ao object storage ANTES da linha (a
  linha so' existe apontando para um blob que existe); se a linha for recusada, o blob sai. `tipo` defesa marca a defesa
  juntada (a primeira). nil = prestacao inexistente nesta Casa (o blob orfao tambem sai)."
  [repo-legislativo objeto-store ator prestacao-id tipo {:keys [nome tipo-midia ^bytes conteudo]}]
  (let [ente-id (:ente-id ator)]
    (when (repo-contas/prestacao repo-legislativo ente-id prestacao-id)
      (let [doc-id (random-uuid)
            chave (logic-contas/chave-do-documento ente-id prestacao-id doc-id)
            remover! #(try (store/remover! objeto-store chave) (catch Exception _ nil))]
        (store/guardar! objeto-store chave conteudo tipo-midia)
        (try
          (or (repo-contas/anexar-documento-de-contas! repo-legislativo ente-id prestacao-id
                                                       {:id doc-id :tipo tipo :nome nome :tipo-midia tipo-midia
                                                        :tamanho-bytes (alength conteudo) :sha256 (sha256-hex conteudo)
                                                        :chave-objeto chave :por (:identidade-id ator)})
              (do (remover!) nil))
          (catch Exception e
            (remover!)
            (throw e)))))))

(defn baixar-documento-de-contas
  "{:documento :stream} (o CHAMADOR fecha o stream), ou nil (inexistente). `publico?` = so' os documentos do TCE."
  [repo-legislativo objeto-store ente-id prestacao-id doc-id publico?]
  (when-let [d (repo-contas/documento-de-contas repo-legislativo ente-id prestacao-id doc-id)]
    (when (or (not publico?) (contains? logic-contas/documentos-publicos (:tipo d)))
      (when-let [in (store/abrir objeto-store (:chave-objeto d))]
        {:documento d :stream in}))))

(defn parametros-de-contas
  "{:prazo-defesa-dias :prazo-julgamento-dias :padrao} — os da Casa, ou os padroes (15/60) se ela nunca gravou."
  [repo-legislativo ente-id]
  (logic-contas/parametros-efetivos (repo-contas/parametros-de-contas repo-legislativo ente-id)))

(defn salvar-parametros-de-contas! [repo-legislativo ator m]
  (logic-contas/parametros-efetivos
   (repo-contas/salvar-parametros-de-contas! repo-legislativo (:ente-id ator) (assoc m :por (:identidade-id ator)))))

(defn motivo-nao-pautavel
  "A costura da PAUTA (sessoes pergunta pelo host): nil se a proposicao pode entrar na pauta; o motivo em palavras se e'
  o PDL de contas e o estado derivado nao e' `pronta_para_pauta` (B3: bloqueio, nao aviso)."
  [repo-legislativo ente-id hoje proposicao-id]
  (some-> (repo-contas/prestacao-da-proposicao repo-legislativo ente-id proposicao-id)
          (logic-contas/motivo-nao-pautavel hoje)))
