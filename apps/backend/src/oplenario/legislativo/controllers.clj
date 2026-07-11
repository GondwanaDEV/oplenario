(ns oplenario.legislativo.controllers
  "Orquestracao (impura) do legislativo (§22.10 controllers, ADR-0001): coordena Repo-Component + policy.check
  (camada FINA, §22.5 eixo E). Trabalha SO em models (kebab) — a traducao de borda fica no diplomat. A vertical
  da votacao ao vivo (F4 Slice 3) DIRIGE a votacao: a authz e' HERDADA do recurso SESSAO (lido via
  `consultar-sessao` INJETADA pelo host — legislativo NAO importa sessoes, §22.10), e as escritas usam o Repo do
  PROPRIO modulo (que casa ato + emissao do evento de tempo real na MESMA tx, Slice 1)."
  (:require [oplenario.kernel.autorizacao :as authz]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(defn- sessao-autorizada
  "Carrega a sessao `sessao-id` no tenant do `ator` via `consultar-sessao` (delega ao Repo de sessoes; a RLS
  escopa por tenant) e roda a camada FINA (policy.check/pode-dirigir-votacao? = mesma Casa). Devolve a sessao se
  autorizada; nil se inexistente (a borda traduz -> 404); LANCA negacao (-> 403) se de outra Casa. Fail-closed:
  ator nil NEGA aqui, nunca delega a consultar-sessao com ente-id nil."
  [consultar-sessao ator sessao-id]
  (when (nil? ator) (authz/negar! :ator-ausente {:acao :votacao/dirigir}))
  (when-let [s (consultar-sessao (:ente-id ator) sessao-id)]
    (authz/check! ator :votacao/dirigir s logic/pode-dirigir-votacao?)
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
  adapters/in (sem sessao-id). Devolve o recibo {:id} ou nil se a sessao nao existe no tenant (-> 404)."
  [repo-leg consultar-sessao ator sessao-id m]
  (when (sessao-autorizada consultar-sessao ator sessao-id)
    (repo/abrir-votacao! repo-leg (:ente-id ator) (assoc m :sessao-id sessao-id))))

(defn registrar-voto
  "Registra um voto na votacao `votacao-id` da sessao `sessao-id`. Authz na sessao + amarra votacao<->sessao.
  DISPATCH EXPLICITO (case 3-vias) pela modalidade da votacao CARREGADA: 'secreta' -> registrar-voto-secreto!
  (DESCARTA a identidade, sigilo §22.6); 'nominal' -> registrar-voto! (exige vereador-id); 'simbolica'
  (aclamacao) -> NAO registra votos individuais (o resultado e' cravado no encerramento via :resultado) ->
  :validacao/invalido (-> 400). Qualquer modalidade futura desconhecida cai no ramo fail-closed (nao no nominal).
  Devolve {:id} ou nil (sessao/votacao inexistente ou de outra sessao -> 404). Nominal sem vereador-id -> 400."
  [repo-leg consultar-sessao ator sessao-id votacao-id m]
  (when (sessao-autorizada consultar-sessao ator sessao-id)
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

(defn criar-proposicao
  "Onda B Slice 2 — protocola uma proposicao nova (authz: so' o gate grosso da rota, papel 'secretario',
  mesmo contrato de listar-proposicoes). `resolver-municipio` (injetado pelo host, cross-modulo p/
  cadastros) resolve {:uf :municipio-nome} do ente — precondicao de protocolar! (eixo H). Ente sem perfil
  cadastrado (resolver devolve nil) e' erro de PROVISIONAMENTO, nao de cliente: propaga sem catch (-> 500),
  nunca mascarado como 400."
  [repo-legislativo resolver-municipio ente-id m]
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
  ja' vem coagido pelo adapters/in."
  [repo-legislativo ente-id m]
  (repo/editar-proposicao! repo-legislativo ente-id m))

(defn buscar-ficha-materia
  "Onda B Slice 3 — ficha completa da materia (proposicao + texto + tramitacao + apensadas + emendas +
  pareceres), mesmo gate grosso das rotas irmas (papel 'secretario', sem policy fina adicional). nil se a
  proposicao nao existe no tenant (-> 404 na borda), mesmo contrato de `buscar-proposicao-ficha`. `:texto`
  sai daqui JA extraido (:texto-inline da linha de dominio, ou nil) — mesma disciplina de
  `buscar-proposicao-ficha` (review MENOR fe-9-ficha-materia): o CONTROLLER e' quem decide o nome de campo
  do model, nunca o diplomat/http/in (que so' compoe adapters/out ja' prontos)."
  [repo-legislativo ente-id id]
  (let [{:keys [proposicao texto] :as ficha} (repo/ficha-completa-da-proposicao repo-legislativo ente-id id)]
    (when proposicao (assoc ficha :texto (:texto-inline texto)))))

;; ========================= Onda B Slice 5: editor/emissao do parecer =========================

(defn buscar-parecer-editor
  "Onda B Slice 5 — leitura agregada p/ o editor de parecer (parecer + objeto + texto rascunho/vigente),
  mesmo gate grosso das rotas irmas (papel 'secretario', sem policy fina adicional). nil se o parecer nao
  existe no tenant (-> 404 na borda), mesmo contrato de buscar-ficha-materia/buscar-proposicao-ficha."
  [repo-legislativo ente-id id]
  (repo/buscar-parecer-para-editor repo-legislativo ente-id id))

(defn salvar-rascunho-parecer
  "Onda B Slice 5 — cria uma nova versao 'rascunho' do texto do parecer. `m` ja' vem coagido pelo
  adapters/in."
  [repo-legislativo ente-id m]
  (repo/nova-versao-parecer! repo-legislativo ente-id m))

(defn emitir-parecer
  "Onda B Slice 5 — promove o rascunho a vigente (se houver) + registra o voto do relator + tenta
  transicionar (gatilho recebido, best-effort), 1 tx. `registro` (RegistroFatos do motor, injetado pelo
  host) e' o mesmo que `transicionar-parecer!` ja recebe. `m` ja' vem coagido pelo adapters/in — que ja'
  carrega o template-id do parecer (o DIPLOMAT o extrai antes de chamar o adapters/in; este controller nao
  decide template-id de outra forma)."
  [repo-legislativo registro ente-id m]
  (repo/emitir-parecer! repo-legislativo ente-id registro m))

(defn encerrar-votacao
  "Encerra a votacao `votacao-id` da sessao `sessao-id` (authz na sessao + amarra). `m` carrega o id
  (=votacao-id), lock-version, base-membros e resultado. Devolve o snapshot apurado ou nil se a votacao nao
  existe nesta sessao (-> 404). Pre-condicoes de borda viram :validacao/invalido (-> 400) usando a votacao JA
  carregada — em vez de propagarem como 500 do db: (a) votacao terminal nao reencerra; (b) modalidade
  'simbolica' (aclamacao) exige `resultado` explicito (nao apura individual).

  CARRY DE SEGURANCA (sec MEDIUM-1, F4 Slice 3): `base-membros` (denominador do quorum p/ maioria
  absoluta/qualificada) vem do CORPO do request — um secretario comprometido poderia falsear o resultado legal
  (ex.: base-membros=1 aprova tudo). Mitigacoes vivas: gate de papel 'secretario' + mesma Casa + o snapshot
  append-only grava o base_membros usado (auditavel). FIX PROPRIO (diferido, shape de F2): resolver a composicao
  da Casa SERVER-SIDE via a relacao `cadastros/membros_da_casa` (ja existe), injetada pelo host como o
  `consultar-sessao` faz — e remover `base-membros` do wire/in. Cross-modulo + data de vigencia do mandato =
  trabalho do resolvedor de fatos (§22.5.3 disc.5), nao desta fatia de borda."
  [repo-leg consultar-sessao ator sessao-id votacao-id m]
  (when (sessao-autorizada consultar-sessao ator sessao-id)
    (let [ente-id (:ente-id ator)]
      (when-let [v (votacao-na-sessao repo-leg ente-id sessao-id votacao-id)]
        (when (contains? logic/estados-votacao-terminais (:estado v))
          (throw (ex-info "votacao ja em estado terminal (encerrada/anulada)"
                          {:tipo :validacao/invalido :estado (:estado v)})))
        (when (and (= "simbolica" (:modalidade v)) (nil? (:resultado m)))
          (throw (ex-info "votacao simbolica exige resultado explicito"
                          {:tipo :validacao/invalido :campos [:resultado]})))
        (repo/encerrar-votacao! repo-leg ente-id m)))))
