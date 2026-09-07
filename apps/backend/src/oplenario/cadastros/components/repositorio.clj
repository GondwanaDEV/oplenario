(ns oplenario.cadastros.components.repositorio
  "Component de PERSISTENCIA do cadastros — o banco DISPONIBILIZADO como Stuart Sierra Component (ADR-0001
  §3, revisao). O protocolo RepoCadastros expoe as ACOES do banco (tenant-aware: trata `com-tenant*` por
  dentro); o record RepoCadastrosPg segura o `:datasource` (injetado via `using`); o `db/` e' a IMPL
  atras do protocolo. O controller depende DESTE Component, nunca do `db/` direto. Trocavel/fakeavel
  como cache/objeto_store/idp. `transacao` permite compor varias acoes numa UNICA tx do tenant."
  (:require [oplenario.cadastros.db.comissao :as comissao]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.cadastros.db.vereador :as vereador]
            [oplenario.cadastros.relacoes.cadastro :as rel-cadastro]
            [oplenario.kernel.tenancy :as tenancy])
  (:import (java.time LocalDate)
           (org.postgresql.util PSQLException)))

(defprotocol RepoCadastros
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant — compoe varias acoes atomicamente.")
  ;; ente / legislatura / sessao
  (criar-ente! [this ente-id ente])
  (buscar-ente [this ente-id])
  (uf-e-municipio [this ente-id]
    "uf + nome do municipio do ente — o FATO que legislativo/protocolar! precisa (injetado pelo host,
     inversao de dependencia §22.10, Onda B Slice 2).")
  (criar-legislatura! [this ente-id legislatura])
  (buscar-legislatura [this ente-id id])
  (legislatura-vigente [this ente-id])
  (criar-sessao-legislativa! [this ente-id sessao])
  ;; vereador / mandato / licenca / suplencia
  (criar-vereador! [this ente-id vereador])
  (buscar-vereador [this ente-id id])
  (listar-vereadores [this ente-id data]
    "Vereadores da Casa com mandato+cargo-na-Mesa vigentes em `data` (Task 1, `vereador/listar`).")
  (roster-da-casa [this ente-id data]
    "ADITIVO (`listar-vereadores` e `membros-da-casa` ficam INTACTOS): as LINHAS dos vereadores que compoem
     a Casa em `data` — o mesmo predicado de mandato vigente que `membros-da-casa` CONTA, exposto como
     conjunto. E' o insumo do roster da CHAMADA; `listar-vereadores` nao serve (devolve todo vereador
     cadastrado, inclusive sem mandato). Ver `vereador/roster-da-casa` p/ o porque completo.")
  (roster-da-casa-em-datas [this ente-id datas]
    "ADITIVO (Etapa 6 fatia 1 — `roster-da-casa` fica INTACTO): o LOTE de `roster-da-casa` para VARIAS
     datas, `{data -> [roster-linha ...]}`. E' o insumo da apuracao de assiduidade — evita reabrir o roster
     sessao a sessao para um periodo inteiro. Compartilha o predicado de mandato com o singular (I3).

     CUSTO EXATO, medido e nao alegado (a alegacao anterior era 'sem tocar o banco' / 'uma query so'', e
     nenhum teste podia reprova-la porque observava `jdbc/execute!` enquanto `com-tenant*` usa
     `jdbc/execute-one!`):
     - `datas` vazio/nil -> `{}` com ZERO statements e ZERO conexoes do pool: o curto-circuito e' AQUI,
       antes de `transacao`, entao a tx de tenant nem abre.
     - entrada invalida (elemento nao-`LocalDate`) ou acima do teto de datas -> lanca AQUI, tambem antes de
       `transacao`: rejeicao nao empresta conexao.
     - caso normal -> UMA query de LEITURA (`jdbc/execute!`) mais os DOIS `jdbc/execute-one!` que
       `com-tenant*` emite para abrir a tx do tenant (`SET LOCAL ROLE` + `set_config`). Tres statements no
       total, nao um.

     Tetos fail-closed: `vereador/teto-de-datas-lote` (366 datas distintas — o periodo maximo do brief) e
     `vereador/teto-de-linhas-lote` (366 x 150 linhas). Ver `vereador/roster-da-casa-em-datas`.")
  (ficha-vereador [this ente-id id data]
    "Leitura composta NUMA UNICA tx (mesma disciplina de ficha-completa-da-proposicao):
     {:vereador :mandato :legislatura :comissoes}, ou nil se o vereador nao existe.")
  (ficha-e-mandatos-do-vereador [this ente-id id data]
    "SUPERCONJUNTO de `ficha-vereador` NA MESMA UNICA tx: {:vereador :mandato :legislatura :comissoes}
     MAIS {:mandatos :licencas}. nil (e nenhuma leitura extra) se o vereador nao existe neste ente.
     `:mandatos` = TODOS os stints (`mandatos-do-vereador`), nao so' o que cobre `data` — e' a unica fonte
     capaz de descrever o ex-vereador, cujo `:mandato` e' nil. `:licencas` = as licencas DESSES mandatos,
     `{:mandato-id :inicio :fim}` (`fim` nil = em curso). Existe para que a borda publica de transparencia
     derive a janela de exercicio SEM uma segunda TRANSACAO (e sem um segundo seam com BEGIN/SET LOCAL/
     COMMIT proprio numa rota anonima sem cache): e' a MESMA tx que ja' rodava como guard de 404. NAO e'
     custo zero — sao 2 statements a mais que `ficha-vereador` (`mandatos-do-vereador` + a licenca; 1 a
     mais quando o vereador nao tem mandato nenhum, pelo curto-circuito de lista vazia).
     ADITIVO — `ficha-vereador` fica intacto e a rota autenticada de cadastros nao paga por isto.")
  (vereador-por-identidade [this ente-id identidade-id])
  (criar-mandato! [this ente-id mandato])
  (mudar-estado-mandato! [this ente-id mandato])
  (mandatos-do-vereador [this ente-id vereador-id])
  (criar-licenca! [this ente-id licenca])
  (criar-suplencia! [this ente-id suplencia])
  (atualizar-vereador! [this ente-id id campos]
    "UPDATE parcial de nome/nome-parlamentar da linha efetivada. Devolve update-count (0 = inexistente).")
  (ligar-identidade! [this ente-id id identidade-id]
    "Liga vereador -> identidade. Passo (2) do provisionamento; NAO concede acesso (spec §4.2). Devolve
     update-count (0 = vereador inexistente/de-outro-tenant); throws :conflito/identidade-ja-vinculada se
     a identidade ja' estiver ligada a OUTRO vereador nesta Casa (indice UNIQUE parcial, 23505).")
  (registrar-mandato! [this ente-id mandato]
    "INSERT de mandato 'vigente' numa tx: 404 (nil) se vereador/legislatura ausente; throws
     :conflito/mandato-sobreposto se ja' ha' vigente sobreposto (guard + a rede EXCLUDE); senao {:id}.")
  (registrar-licenca! [this ente-id vereador-id licenca data]
    "Licenca record-only numa tx: 404 (nil) se vereador ausente; throws :conflito/sem-mandato-vigente se
     nao ha' mandato vigente cobrindo `data`; senao INSERT licenca + UPDATE mandato.estado='licenciado' -> {:id}.")
  (reassumir-mandato! [this ente-id vereador-id reassumiu-em]
    "REASSUNCAO numa UNICA tx — o inverso exato de `registrar-licenca!`. `reassumiu-em` e' o dia em que a
     pessoa VOLTOU A EXERCER; a licenca e' fechada na VESPERA dele (ver `encerrar-licencas-abertas!`).
     404 (nil) se vereador ausente/de-outro-tenant; throws :conflito/sem-mandato-licenciado (409) se nao ha'
     mandato 'licenciado' cobrindo `reassumiu-em`; :conflito/retorno-anterior-ao-inicio (409) se NENHUMA
     licenca fechou e ainda ha' aberta; :conflito/mandato-sobreposto (409) se reabrir violaria o EXCLUDE.
     Senao UPDATE das licencas abertas + UPDATE mandato.estado='vigente' -> {:id :fim}, com `:fim` = a
     vespera GRAVADA ou nil quando o UPDATE casou zero linhas (licenca ja' vencida).")
  ;; comissao / cargo / membro
  (criar-comissao! [this ente-id comissao])
  (buscar-comissao [this ente-id id])
  ;; `resolver-comissoes` do host (§22.5.3) — id -> nome em LOTE, p/ o legislativo nomear a comissao do
  ;; parecer sem importar `cadastros` (defeito #11 do ledger de prontidao).
  (nomes-de-comissoes [this ente-id ids])
  (mesa-vigente [this ente-id data])
  (criar-cargo! [this ente-id cargo])
  (criar-membro! [this ente-id membro])
  (membros-da-comissao [this ente-id comissao-id])
  (membros-da-casa [this ente-id data]
    "Nº de vereadores com mandato vigente em `data` (relacao ja usada pelo motor de regras — F2; exposta
     aqui p/ o host injetar em outros modulos via inversao de dependencia, §22.10, FE Onda A1)."))

(defrecord RepoCadastrosPg [datasource]
  RepoCadastros
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  (criar-ente! [this ente-id ente] (transacao this ente-id #(estrutura/inserir-ente! % ente)))
  (buscar-ente [this ente-id] (transacao this ente-id estrutura/buscar-ente))
  (uf-e-municipio [this ente-id] (transacao this ente-id estrutura/uf-e-municipio))
  (criar-legislatura! [this ente-id leg] (transacao this ente-id #(estrutura/inserir-legislatura! % leg)))
  (buscar-legislatura [this ente-id id] (transacao this ente-id #(estrutura/buscar-legislatura % id)))
  (legislatura-vigente [this ente-id] (transacao this ente-id #(estrutura/legislatura-vigente % ente-id)))
  (criar-sessao-legislativa! [this ente-id s] (transacao this ente-id #(estrutura/inserir-sessao-legislativa! % s)))
  (criar-vereador! [this ente-id v] (transacao this ente-id #(vereador/inserir! % v)))
  (buscar-vereador [this ente-id id] (transacao this ente-id #(vereador/buscar % ente-id id)))
  (listar-vereadores [this ente-id data] (transacao this ente-id #(vereador/listar % ente-id data)))
  (roster-da-casa [this ente-id data] (transacao this ente-id #(vereador/roster-da-casa % ente-id data)))
  (roster-da-casa-em-datas [this ente-id datas]
    ;; A VALIDACAO/DEDUP/TETO roda ANTES de `transacao` de proposito. `transacao` -> `com-tenant*` ja'
    ;; EMPRESTOU uma conexao do pool (10 slots) e ja' emitiu 2 statements (`SET LOCAL ROLE` + `set_config`)
    ;; antes de a checagem de dentro da fn de `db/` poder rejeitar — um pedido invalido, que deveria custar
    ;; ZERO, consumia slot de pool e round-trips. A checagem continua tambem dentro de
    ;; `vereador/roster-da-casa-em-datas` como REDE (a fn e' publica e outro caller pode chega la' direto).
    (let [distintas (vereador/normalizar-datas! datas)]
      (if (empty? distintas)
        {}
        (transacao this ente-id #(vereador/roster-da-casa-em-datas % ente-id distintas)))))
  (ficha-vereador [this ente-id id data]
    (transacao this ente-id
      (fn [tx]
        (when-let [v (vereador/buscar tx ente-id id)]
          (let [m (vereador/mandato-vigente tx ente-id id data)
                leg (when (:legislatura-id m) (estrutura/buscar-legislatura tx (:legislatura-id m)))
                cs (comissao/comissoes-do-vereador tx ente-id id data)]
            {:vereador v :mandato m :legislatura leg :comissoes cs})))))
  (ficha-e-mandatos-do-vereador [this ente-id id data]
    (transacao this ente-id
      (fn [tx]
        (when-let [v (vereador/buscar tx ente-id id)]
          (let [m (vereador/mandato-vigente tx ente-id id data)
                leg (when (:legislatura-id m) (estrutura/buscar-legislatura tx (:legislatura-id m)))
                cs (comissao/comissoes-do-vereador tx ente-id id data)
                ms (vereador/mandatos-do-vereador tx ente-id id)
                ls (vereador/licencas-de-mandatos tx ente-id (mapv :id ms))]
            {:vereador v :mandato m :legislatura leg :comissoes cs :mandatos ms :licencas ls})))))
  (vereador-por-identidade [this ente-id ident] (transacao this ente-id #(vereador/por-identidade % ente-id ident)))
  (criar-mandato! [this ente-id m] (transacao this ente-id #(vereador/inserir-mandato! % m)))
  (mudar-estado-mandato! [this ente-id m] (transacao this ente-id #(vereador/mudar-estado! % ente-id m)))
  (mandatos-do-vereador [this ente-id ver-id] (transacao this ente-id #(vereador/mandatos-do-vereador % ente-id ver-id)))
  (criar-licenca! [this ente-id l] (transacao this ente-id #(vereador/inserir-licenca! % l)))
  (criar-suplencia! [this ente-id s] (transacao this ente-id #(vereador/inserir-suplencia! % s)))
  (atualizar-vereador! [this ente-id id campos]
    (transacao this ente-id #(vereador/atualizar! % ente-id id campos)))
  (ligar-identidade! [this ente-id id identidade-id]
    ;; 23505 do indice UNIQUE parcial (ente_id,identidade_id) WHERE identidade_id IS NOT NULL -> conflito
    ;; de dominio (409, nunca 500) — mesmo predicado 23505 de participacao/interpor-recurso! e
    ;; legislativo/registrar-voto!.
    (try
      (transacao this ente-id #(vereador/ligar-identidade! % ente-id id identidade-id))
      (catch PSQLException e
        (if (= "23505" (.getSQLState e))
          (throw (ex-info "identidade ja vinculada a outro vereador nesta Casa"
                          {:tipo :conflito/identidade-ja-vinculada :id id :identidade-id identidade-id}))
          (throw e)))))
  (registrar-mandato! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (cond
          (nil? (vereador/buscar tx ente-id (:vereador-id m)))                 nil
          (nil? (estrutura/buscar-legislatura tx (:legislatura-id m)))         nil
          (vereador/mandato-sobreposto? tx ente-id (:vereador-id m)
                                        (:vigencia-inicio m) (:vigencia-fim m))
          (throw (ex-info "mandato vigente sobreposto" {:tipo :conflito/mandato-sobreposto}))
          :else (do (vereador/inserir-mandato! tx m) {:id (:id m)})))))
  (registrar-licenca! [this ente-id vereador-id l data]
    (transacao this ente-id
      (fn [tx]
        (if (nil? (vereador/buscar tx ente-id vereador-id))
          nil
          (if-let [mv (vereador/mandato-vigente-de-vereador tx ente-id vereador-id data)]
            (do (vereador/inserir-licenca! tx (assoc l :mandato-id (:id mv)))
                (vereador/mudar-estado! tx ente-id {:id (:id mv) :estado "licenciado"})
                {:id (:id l)})
            (throw (ex-info "sem mandato vigente para licenciar" {:tipo :conflito/sem-mandato-vigente})))))))
  ;; sem `^LocalDate` no arglist: hint de classe em parametro de metodo de protocolo quebra o casamento de
  ;; assinatura ("Can't find matching method ... leave off hints for auto match"). O hint vai no USO.
  (reassumir-mandato! [this ente-id vereador-id reassumiu-em]
    ;; 23P01 do EXCLUDE `uq_mandato_vigente_sem_overlap` (mig 0059) -> conflito de DOMINIO, nunca 500. E'
    ;; alcancavel de verdade: o contorno que o operador tinha ATE' esta fatia era registrar um mandato NOVO
    ;; 'vigente' para representar o retorno, e ele sobrepoe o stint licenciado (que fica fora do predicado
    ;; do EXCLUDE justamente por nao ser 'vigente'). O catch fica FORA da `transacao` — a excecao aborta a
    ;; tx, entao nao ha o que capturar por dentro (mesmo padrao de `ligar-identidade!` com o 23505).
    (try
      (transacao this ente-id
        (fn [tx]
          (when (some? (vereador/buscar tx ente-id vereador-id))
            (let [m (or (vereador/mandato-licenciado-de-vereador tx ente-id vereador-id reassumiu-em)
                        ;; GUARD ANTI-RESSURREICAO: exigir 'licenciado' e' o que impede que um POST
                        ;; devolva a 'vigente' um mandato cassado/renunciado/falecido. Note que o guard NAO
                        ;; e' "existe licenca aberta": a licenca COM data de fim ja' vencida fecha zero
                        ;; linhas e ainda assim precisa devolver o mandato a 'vigente' — senao quem se
                        ;; licenciou por 5 dias em 2024 fica sem poder se licenciar de novo para sempre.
                        (throw (ex-info "sem mandato licenciado para reassumir"
                                        {:tipo :conflito/sem-mandato-licenciado})))
                  fim (.minusDays ^LocalDate reassumiu-em 1)
                  encerradas (vereador/encerrar-licencas-abertas! tx ente-id (:id m) fim)]
              ;; RELE p/ detectar sobra: `encerrar-licencas-abertas!` nao casa licenca cujo `inicio` seja
              ;; POSTERIOR ao `fim` calculado. Sem esta checagem o estado flipava para 'vigente' com a
              ;; licenca ainda ABERTA — o pior dos dois mundos, porque a janela publica continuaria comida
              ;; enquanto o mandato parecia normal.
              ;;
              ;; O `zero?` e' o que separa os DOIS estados que "sobrou aberta" mistura, e que o dado sozinho
              ;; nao distingue: (a) "voltei antes de sair" — a UNICA licenca aberta comeca depois da volta,
              ;; nada fechou, a data e' invalida -> 409 fail-closed; (b) "ha' tambem uma licenca que ainda
              ;; nao comecou" — a licenca EM CURSO fechou (`encerradas` > 0), e a futura apenas segue
              ;; subtraindo a PROPRIA janela, que e' a semantica correta. Sem o `zero?`, (b) lancava e o
              ;; mandato ficava preso em 'licenciado' sem remedio — exatamente o que a docstring de
              ;; `encerrar-licencas-abertas!` diz nao querer.
              (when (and (zero? encerradas)
                         (seq (filter (comp nil? :fim)
                                      (vereador/licencas-de-mandatos tx ente-id [(:id m)]))))
                (throw (ex-info "reassuncao anterior ao inicio da licenca em curso"
                                {:tipo :conflito/retorno-anterior-ao-inicio})))
              ;; seguro: o `:fim_efetivo [:coalesce fim-efetivo :fim_efetivo]` de `mudar-estado!` NUNCA
              ;; sobrescreve — reassumir nao apaga o `fim_efetivo` de um mandato que ja' o tenha.
              (vereador/mudar-estado! tx ente-id {:id (:id m) :estado "vigente"})
              ;; `:fim` e' um FATO GRAVADO, nao a aritmetica local: ZERO linhas fechadas (licenca ja' vencida
              ;; — caminho de primeira classe) devolve nil, e a borda serializa `null`. Anunciar a vespera
              ;; calculada faria a tela dizer "licenca encerrada em D-1" sobre uma licenca que terminou anos
              ;; antes, e mascararia a reassuncao perdedora de uma corrida como se tivesse gravado algo.
              {:id (:id m) :fim (when (pos? encerradas) fim)}))))
      (catch PSQLException e
        (if (= "23P01" (.getSQLState e))
          (throw (ex-info "reabrir este mandato sobreporia outro mandato vigente do mesmo vereador"
                          {:tipo :conflito/mandato-sobreposto :vereador-id vereador-id}))
          (throw e)))))
  (criar-comissao! [this ente-id c] (transacao this ente-id #(comissao/inserir! % c)))
  (buscar-comissao [this ente-id id] (transacao this ente-id #(comissao/buscar % id)))
  (nomes-de-comissoes [this ente-id ids] (transacao this ente-id #(comissao/nomes-por-id % ids)))
  (mesa-vigente [this ente-id data] (transacao this ente-id #(comissao/mesa-vigente % data)))
  (criar-cargo! [this ente-id c] (transacao this ente-id #(comissao/inserir-cargo! % c)))
  (criar-membro! [this ente-id m] (transacao this ente-id #(comissao/inserir-membro! % m)))
  (membros-da-comissao [this ente-id com-id] (transacao this ente-id #(comissao/membros % com-id)))
  (membros-da-casa [this ente-id data] (transacao this ente-id #(rel-cadastro/membros-da-casa % data))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoCadastrosPg nil))

(defn identidade-do-vereador-em-tx
  "vereador-id -> identidade-id NESTA Casa, na `tx` JA' ABERTA do chamador (Onda E fatia 1). Fn PLANA (nao
  metodo do protocolo) de proposito: o chamador e' um consumer do relay, que ja' esta' dentro de uma tx —
  abrir `com-tenant*` aqui seria redundante e trocaria o role, quebrando o UPDATE seguinte do relay em
  shared.outbox (mesmo racional de `projetar-evento!`). Devolve nil quando o vereador nao existe neste ente
  ou nao tem identidade vinculada — o consumer trata como 'nao notifica', nunca como erro."
  [tx ente-id vereador-id]
  (:identidade-id (vereador/buscar tx ente-id vereador-id)))
