(ns oplenario.sessoes.wire.in
  "Representacao EXTERNA de ENTRADA da sessao (§22.10 wire/in, ADR-0001) — o contrato do corpo de request, em
  tipos JSON (strings). O `adapters/in` valida contra isto e coage p/ o dominio. `:closed true` recusa campos
  extra (defesa de borda); o autor/tenant NAO vem do corpo (vem do `ator` resolvido na auth)."
  (:require [clojure.string :as str]
            [oplenario.kernel.malli :as km]
            [oplenario.sessoes.logic :as logic]))

(def AgendarSessao
  "Corpo de POST /sessoes. `sessao-legislativa-id` = uuid (string); `agendada-para` = ISO-8601 (string).
  capabilities-override fica fora da V1 da borda (o tipo resolve os defaults; override entra quando pedido)."
  [:map {:closed true}
   [:sessao-legislativa-id :string]
   [:tipo-sessao (km/enum-de logic/tipos-sessao)]
   [:modalidade {:optional true} [:maybe (km/enum-de logic/modalidades-sessao)]]
   [:agendada-para {:optional true} [:maybe :string]]])

(def RegistrarPresenca
  "Corpo de POST /sessoes/:id/presenca (§22.6 eixo C). `vereador-id` = uuid (string); `ocorrido-em` = instante
  de DOMINIO (ISO-8601 string) em que o evento ocorreu — OBRIGATORIO (como `iniciou-em` da gravacao: o instante
  de dominio e' dado, nao conveniencia de servidor; o efetivado_em=now() do db e' o instante de auditoria).
  NAO carrega `fonte` (forcada = manual_secretaria no servidor: registro humano autenticado, integridade de
  proveniencia) nem `sessao-id`/autor/tenant (vem do path/ator). `:closed true` recusa campos extra."
  [:map {:closed true}
   [:vereador-id :string]
   [:tipo (km/enum-de logic/tipos-evento-presenca)]
   [:modalidade (km/enum-de logic/modalidades-presenca)]
   [:ocorrido-em :string]])

(def InscreverOrador
  "Corpo de POST /sessoes/:id/inscricoes (§22.6 eixo F, tribuna camada de intencao). `vereador-id` = uuid
  (string); `origem-inscricao` discrimina o caminho (app/secretaria/pedido/autoria) — dado descritivo da fila,
  validado contra o enum (NAO forcado: sem implicacao de precedencia, diferente da `fonte` de presenca);
  `fase` reusa as fases-pauta (a tribuna e' subordinada a fase); `proposicao-ref-id` opcional (uuid). NAO carrega
  autor/tenant (vem do ator) nem `ordem` (numerada server-side). `:closed true` recusa campos extra."
  [:map {:closed true}
   [:vereador-id :string]
   [:origem-inscricao (km/enum-de logic/origens-inscricao)]
   [:fase (km/enum-de logic/fases-pauta)]
   [:proposicao-ref-id {:optional true} [:maybe :string]]])

(def IniciarFala
  "Corpo de POST /sessoes/:id/falas (§22.6 eixo F, tribuna camada de EXECUCAO). `orador-id` = uuid (string);
  `tipo-fala` (principal/aparte/pela_ordem/...) validado contra o enum; `fase` reusa as fases-pauta;
  `iniciou-em` = instante de DOMINIO (ISO-8601 string) em que a fala comecou — OBRIGATORIO (como `ocorrido-em`
  da presenca: o instante de dominio e' dado, nao conveniencia de servidor). `inscricao-id` (a fala que cumpre
  uma inscricao), `fala-pai-id` (aparte de uma fala-mae) e `proposicao-ref-id` sao opcionais (uuid). NAO carrega
  autor/tenant (vem do ator). `:closed true` recusa campos extra."
  [:map {:closed true}
   [:orador-id :string]
   [:tipo-fala (km/enum-de logic/tipos-fala)]
   [:fase (km/enum-de logic/fases-pauta)]
   [:iniciou-em :string]
   [:inscricao-id {:optional true} [:maybe :string]]
   [:fala-pai-id {:optional true} [:maybe :string]]
   [:proposicao-ref-id {:optional true} [:maybe :string]]])

(def RegistrarEventoCronometro
  "Corpo de POST /sessoes/:id/falas/:fala-id/cronometro (§22.6 eixo F). `tipo` so os eventos MANUAIS que a Mesa
  registra (pausada/retomada/aparte_concedido/tempo_adicional_concedido — iniciada/encerrada sao do ciclo da
  fala, internos); `ocorrido-em` = instante de dominio (ISO-8601 string, OBRIGATORIO); `segundos-adicionais`
  opcional (int). A COERENCIA tipo<->segundos (tempo_adicional EXIGE >0; os demais PROIBEM) e' validada no
  adapters/in (fail-closed -> 400, nunca o CHECK do banco -> 500). `:closed true` recusa campos extra."
  [:map {:closed true}
   [:tipo (km/enum-de logic/tipos-evento-cronometro-manual)]
   [:ocorrido-em :string]
   [:segundos-adicionais {:optional true} [:maybe :int]]])

(def EncerrarFala
  "Corpo de POST /sessoes/:id/falas/:fala-id/encerrar (§22.6 eixo F). `encerrou-em` = instante de dominio
  (ISO-8601 string, OBRIGATORIO) em que a fala terminou — o motor COMPUTA o tempo efetivo dos eventos do
  cronometro ate aqui; `lock-version` = inteiro 0..int4 (CAS otimista; ausente/fora do range -> 400 fail-closed
  no adapter, NUNCA 500 do CHECK do banco). NAO carrega o tempo (computado server-side). `:closed true`."
  [:map {:closed true}
   [:encerrou-em :string]
   [:lock-version :int]])

(def RegistrarDecisaoMesa
  "Corpo de POST /sessoes/:id/decisoes-mesa (§22.6 eixo F, tribuna): a DECISAO DA MESA sobre questao de ordem
  (ato regimental p/ a ata, APPEND-ONLY). `questao`/`decisao` = texto OBRIGATORIO e nao-vazio (apos trim — o
  adapters/in valida -> 400, nunca o CHECK da migration -> 500); `fundamentacao` opcional (se presente, nao-vazia
  — campo de peso juridico); `decidido-em` = instante de DOMINIO (ISO-8601 string, OBRIGATORIO) em que o
  presidente decidiu; `fala-id` opcional (uuid — a questao pode ser decidida sem uma fala registrada). NAO carrega
  `presidente-id` (INJETADO do ator no servidor — um cliente nao forja quem decidiu) nem autor/tenant/sessao-id.
  `:closed true` recusa campos extra (defesa de borda)."
  [:map {:closed true}
   [:questao :string]
   [:decisao :string]
   [:decidido-em :string]
   [:fundamentacao {:optional true} [:maybe :string]]
   [:fala-id {:optional true} [:maybe :string]]])

(def RegistrarIncidente
  "Corpo de POST /sessoes/:id/incidentes (§16.13): incidente processual (pedido de vista, verificacao de votacao,
  urgencia, votacao em bloco) — ato regimental p/ a ata, APPEND-ONLY. `tipo`/`resultado` validados contra o enum
  e `descricao` nao-vazia no adapters/in (-> 400, nunca o CHECK da migration -> 500); `ocorrido-em` = instante de
  DOMINIO (ISO-8601, OBRIGATORIO); `objeto-tipo`+`objeto-id` opcionais e COERENTES (ambos ou nenhum — a materia
  que o incidente atinge, forward-ref); `requerente-id` opcional (quem suscitou); `deliberacao` opcional (se
  presente, nao-vazia). NAO carrega `created-by` (INJETADO do ator). `:closed true` recusa campos extra."
  [:map {:closed true}
   [:tipo (km/enum-de logic/tipos-incidente)]
   [:resultado (km/enum-de logic/resultados-incidente)]
   [:descricao [:string {:max 4096}]]               ; cap de campo (review sec BAIXO-1); nao-vazio = adapters/in
   [:ocorrido-em :string]
   [:objeto-tipo {:optional true} [:maybe (km/enum-de logic/tipos-objeto-incidente)]]
   [:objeto-id {:optional true} [:maybe :string]]
   [:requerente-id {:optional true} [:maybe :string]]
   [:deliberacao {:optional true} [:maybe [:string {:max 4096}]]]])

(def AdicionarItemPauta
  "Corpo de POST /sessoes/:id/pauta/itens (§22.6 eixo B, pauta viva): adiciona um item a pauta 1:1 da sessao.
  `fase` (atributo do item) e `tipo-item` validados contra os enums. FK-por-tipo (espelha o `:fn` de
  models/PautaItem + o CHECK pauta_item_proposicao_coerente da mig 0027, defesa-em-profundidade na borda):
  'proposicao' EXIGE `proposicao-id` (uuid string) e PROIBE `texto-descricao`; os demais EXIGEM
  `texto-descricao` nao-vazio (apos trim) e PROIBEM `proposicao-id` — violacao -> 400 na borda, nunca o CHECK
  -> 500. NAO carrega `ordem` (numerada server-side = max+1) nem autor/tenant/sessao-id. `:closed true`."
  [:and
   [:map {:closed true}
    [:fase (km/enum-de logic/fases-pauta)]
    [:tipo-item (km/enum-de logic/tipos-item-pauta)]
    [:proposicao-id {:optional true} [:maybe :string]]
    [:texto-descricao {:optional true} [:maybe :string]]]
   [:fn {:error/message "proposicao exige proposicao-id (e sem descricao); demais tipos exigem texto-descricao nao-vazio"}
    (fn [{:keys [tipo-item proposicao-id texto-descricao]}]
      (if (logic/item-requer-proposicao? tipo-item)
        (and (some? proposicao-id) (nil? texto-descricao))
        (and (nil? proposicao-id) (string? texto-descricao) (not (str/blank? texto-descricao)))))]])
