(ns oplenario.legislativo.controllers
  "Orquestracao (impura) do legislativo (§22.10 controllers, ADR-0001): coordena Repo-Component + policy.check
  (camada FINA, §22.5 eixo E). Trabalha SO em models (kebab) — a traducao de borda fica no diplomat. A vertical
  da votacao ao vivo (F4 Slice 3) DIRIGE a votacao: a authz e' HERDADA do recurso SESSAO (lido via
  `consultar-sessao` INJETADA pelo host — legislativo NAO importa sessoes, §22.10), e as escritas usam o Repo do
  PROPRIO modulo (que casa ato + emissao do evento de tempo real na MESMA tx, Slice 1)."
  (:require [oplenario.kernel.autorizacao :as authz]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.logic :as logic]
            [oplenario.motor.api :as motor]))

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
  ja fechada -> `sessao-autorizada` lanca `:conflito/sessao-fechada` (-> 409, ledger Fase 8 achado #5)."
  [repo-leg consultar-sessao sessao-fechada? ator sessao-id m]
  (when (sessao-autorizada consultar-sessao sessao-fechada? ator sessao-id)
    (repo/abrir-votacao! repo-leg (:ente-id ator) (assoc m :sessao-id sessao-id))))

(defn registrar-voto
  "Registra um voto na votacao `votacao-id` da sessao `sessao-id`. Authz na sessao + amarra votacao<->sessao.
  DISPATCH EXPLICITO (case 3-vias) pela modalidade da votacao CARREGADA: 'secreta' -> registrar-voto-secreto!
  (DESCARTA a identidade, sigilo §22.6); 'nominal' -> registrar-voto! (exige vereador-id); 'simbolica'
  (aclamacao) -> NAO registra votos individuais (o resultado e' cravado no encerramento via :resultado) ->
  :validacao/invalido (-> 400). Qualquer modalidade futura desconhecida cai no ramo fail-closed (nao no nominal).
  Devolve {:id} ou nil (sessao/votacao inexistente ou de outra sessao -> 404). Nominal sem vereador-id -> 400.
  Sessao ja fechada -> `sessao-autorizada` lanca `:conflito/sessao-fechada` (-> 409)."
  [repo-leg consultar-sessao sessao-fechada? ator sessao-id votacao-id m]
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

;; ========================= FE Onda A1: fila de relatores pendentes (§16.11) =========================

(defn relatores-pendentes
  "Fila de pareceres 'aguardando_designacao' do tenant `ente-id` (leitura tenant-wide, sem ator/policy fina —
  mesmo contrato de `resumo-presenca`/`esic-cumprimento`). Devolve as linhas cruas (kebab, do db); o
  adapters/out projeta+valida o contrato RelatoresPendentesOut."
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
  [repo-legislativo registro ente-id m]
  (when-let [linha (repo/buscar-proposicao repo-legislativo ente-id (:proposicao-id m))]
    (when (nil? (:template-id linha))
      (throw (ex-info "esta materia nao tem rito declarado (a Casa nao vinculou um template de tramitacao a ela) — nao ha o que tramitar"
                      {:tipo :conflito/sem-rito :proposicao-id (:proposicao-id m)})))
    (repo/transicionar! repo-legislativo ente-id registro
                        (assoc m :template-id (:template-id linha)))))

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

(defn buscar-tramitacao
  "Fatia 3 — a LEITURA da tramitacao: o HISTORICO da materia + os GATILHOS que a Casa declara a partir do
  estado ATUAL. nil = materia inexistente no tenant (a borda traduz -> 404), mesmo contrato de
  `buscar-ficha-materia`/`buscar-proposicao-detalhe`.

  NAO AVALIA GUARD, e a decisao e' de desenho (ver `logic/gatilhos-possiveis`): o guard le' `alegado`, que
  e' argumento do POST e nao existe no GET — avaliar aqui daria uma resposta precisa e FALSA. A honestidade
  e' paga em `pode-ser-recusado`, por gatilho, que e' mais informacao que um aviso generico.

  A SONDA DE TRUNCAMENTO: o Repo e' consultado com `limite+1`. Com um teto simples, `n` itens devolvidos
  sao indistinguiveis de 'a materia so' teve n atos', e o operador leria a linha mais antiga MOSTRADA como
  o comeco do processo — uma mentira por omissao num artefato de auditoria. O item excedente nunca vai p/ a
  resposta: ele so' existe p/ a borda poder DIZER que ha' mais. `take-last` porque `historico-da-proposicao`
  ja' devolve os N mais recentes em ordem cronologica — o que sobra p/ descartar e' o mais ANTIGO."
  [repo-legislativo ente-id proposicao-id limite]
  (let [{:keys [proposicao historico candidatas estado-no-template]}
        (repo/tramitacao-da-proposicao repo-legislativo ente-id proposicao-id (inc limite))]
    (when proposicao
      (let [truncado? (> (count historico) limite)
            gatilhos  (logic/gatilhos-possiveis candidatas)
            estado    (:estado proposicao)]
        {:proposicao-id proposicao-id
         :estado-atual estado
         :template-id (:template-id proposicao)
         ;; tri-valorado de proposito: true/false = o rito declara o estado e diz se e' fim; nil = o rito
         ;; NAO declara o estado (ou nao ha' rito). "Desconhecido" e "nao-terminal" sao diagnosticos
         ;; diferentes, e achatar os dois em `false` apagaria justamente o caso que pede intervencao.
         :estado-terminal (:terminal estado-no-template)
         :historico (if truncado? (vec (take-last limite historico)) (vec historico))
         :historico-truncado truncado?
         :gatilhos-possiveis gatilhos
         :nota (when (empty? gatilhos)
                 (nota-de-lista-vazia estado {:template-id (:template-id proposicao)
                                              :estado-no-template estado-no-template}))}))))

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

(defn buscar-ficha-materia
  "Onda B Slice 3 — ficha completa da materia (proposicao + texto + tramitacao + apensadas + emendas +
  pareceres), mesmo gate grosso das rotas irmas (papel 'secretario', sem policy fina adicional). nil se a
  proposicao nao existe no tenant (-> 404 na borda), mesmo contrato de `buscar-proposicao-ficha`. `:texto`
  sai daqui JA extraido (:texto-inline da linha de dominio, ou nil) — mesma disciplina de
  `buscar-proposicao-ficha` (review MENOR fe-9-ficha-materia): o CONTROLLER e' quem decide o nome de campo
  do model, nunca o diplomat/http/in (que so' compoe adapters/out ja' prontos).

  Cada parecer sai com `:comissao-nome` (defeito #11 do ledger de prontidao — a aba mostrava o UUID)."
  [repo-legislativo resolver-comissoes ente-id id]
  (let [{:keys [proposicao texto] :as ficha} (repo/ficha-completa-da-proposicao repo-legislativo ente-id id)]
    (when proposicao
      (-> ficha
          (assoc :texto (:texto-inline texto))
          (update :pareceres #(nomear-comissoes resolver-comissoes ente-id %))))))

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

  CARRY DE SEGURANCA (sec MEDIUM-1, F4 Slice 3): `base-membros` (denominador do quorum p/ maioria
  absoluta/qualificada) vem do CORPO do request — um secretario comprometido poderia falsear o resultado legal
  (ex.: base-membros=1 aprova tudo). Mitigacoes vivas: gate de papel 'secretario' + mesma Casa + o snapshot
  append-only grava o base_membros usado (auditavel). FIX PROPRIO (diferido, shape de F2): resolver a composicao
  da Casa SERVER-SIDE via a relacao `cadastros/membros_da_casa` (ja existe), injetada pelo host como o
  `consultar-sessao` faz — e remover `base-membros` do wire/in. Cross-modulo + data de vigencia do mandato =
  trabalho do resolvedor de fatos (§22.5.3 disc.5), nao desta fatia de borda."
  [repo-leg consultar-sessao sessao-fechada? ator sessao-id votacao-id m]
  (when (sessao-autorizada consultar-sessao sessao-fechada? ator sessao-id)
    (let [ente-id (:ente-id ator)]
      (when-let [v (votacao-na-sessao repo-leg ente-id sessao-id votacao-id)]
        (when (contains? logic/estados-votacao-terminais (:estado v))
          (throw (ex-info "votacao ja em estado terminal (encerrada/anulada)"
                          {:tipo :conflito/votacao-terminal :estado (:estado v)})))
        (when (and (= "simbolica" (:modalidade v)) (nil? (:resultado m)))
          (throw (ex-info "votacao simbolica exige resultado explicito"
                          {:tipo :validacao/invalido :campos [:resultado]})))
        (repo/encerrar-votacao! repo-leg ente-id m)))))

;; ========================= Onda B Slice 6: expediente (documentos + protocolo geral) =========================

(defn listar-modelos-documento
  "Onda B Slice 6 — modelos ATIVOS do tenant p/ o seletor da aba 'Gerar documento' (mesmo gate grosso das
  rotas irmas, papel 'secretario', sem policy fina adicional)."
  [repo-legislativo ente-id]
  (repo/listar-modelos-ativos repo-legislativo ente-id))

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
    {:vereador-id nil :proposicoes [] :pareceres [] :ciencias []}))

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
