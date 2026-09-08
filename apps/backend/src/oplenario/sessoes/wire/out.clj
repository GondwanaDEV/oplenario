(ns oplenario.sessoes.wire.out
  "Representacao EXTERNA de SAIDA da sessao (§22.10 wire/out, ADR-0001) — o contrato de borda que o
  `adapters/out` produz e do qual o Eixo 8 gera os tipos TS do front. Tudo como tipo serializavel a JSON:
  uuid/Instant viram string. NAO expoe o token interno de concorrencia (`lock-version`) nem campos
  sensiveis — a defesa anti-vazamento mora no adapters/out."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.sessoes.logic :as logic]))

(def SessaoOut
  "Projecao publica de uma sessao (resposta REST). Strings p/ uuid; ISO-8601 p/ Instant; marcos opcionais."
  [:map {:closed true}
   [:id :string]
   [:sessao-legislativa-id :string]
   [:tipo-sessao (km/enum-de logic/tipos-sessao)]
   [:numero-sequencial :int]
   [:estado (km/enum-de logic/estados-sessao)]
   [:modalidade (km/enum-de logic/modalidades-sessao)]
   [:delibera :boolean]
   [:transmite-publica :boolean]
   [:gera-ata-regimental :boolean]
   [:permite-voto-secreto :boolean]
   [:permite-modalidade-remota :boolean]
   [:agendada-para {:optional true} [:maybe :string]]
   [:aberta-em {:optional true} [:maybe :string]]
   [:encerrada-em {:optional true} [:maybe :string]]
   [:motivo-nao-realizada {:optional true} [:maybe :string]]])

(def SessoesOut
  "Resposta de `GET /sessoes` (listagem geral, ledger de prontidao #16) — as sessoes do ente ja' FILTRADAS
  linha a linha por `logic/pode-ver-quorum-da-sessao?` (nunca `authz/check!` unico na entrada: uma sessao
  secreta que reprova simplesmente NAO entra em `:sessoes`, invisivel por omissao) e ORDENADAS para a home
  (aberta/suspensa primeiro; agendadas por data-agendada crescente; fechadas por data-de-referencia
  decrescente — `logic/chave-ordenacao-listagem-geral`). Cada item e' o MESMO `SessaoOut` de
  `GET /sessoes/:id` — vocabulario unico, nunca dois formatos para a mesma sessao."
  [:map {:closed true}
   [:sessoes [:sequential SessaoOut]]])

(def TransicaoSessaoOut
  "Recibo da transicao de estado da sessao (resposta 200 de POST /sessoes/:id/transicao, Mesa de conducao).
  Carrega a `sessao-id` + o par `de`/`para` (espelha o payload do evento sessao.transicionou). NAO expoe o
  lock-version. O canal SSE do plenario ja recebeu o mesmo fato pelo evento; este recibo confirma ao chamador."
  [:map {:closed true}
   [:sessao-id :string]
   [:de (km/enum-de logic/estados-sessao)]
   [:para (km/enum-de logic/estados-sessao)]])

(def PresencaReciboOut
  "Recibo do registro de presenca (resposta 201 de POST /sessoes/:id/presenca e de /presenca/confirmar). O `id`
  do evento gravado + os DOIS carimbos de tempo. O canal SSE do plenario ja recebeu o fato
  (presenca.registrada) p/ o quorum ao vivo; este recibo confirma ao chamador. NAO expoe internos.

  `ocorrido-em` = a hora do FATO (o instante de DOMINIO, ecoado como o servidor o aceitou — util justamente
  porque o servidor pode RECUSAR uma hora fora da janela da sessao); `registrado-em` = a hora do REGISTRO
  (carimbo de AUDIT do banco, quando o sistema soube). Os dois viajam juntos porque, enquanto nao existir um
  tipo de evento de RETIFICACAO, esse par e' a UNICA forma de o juridico distinguir 'o vereador saiu as 15h'
  de 'o servidor corrigiu as 17h um registro das 15h'. Espelha `desde`/`registrado-em` de LinhaChamadaOut —
  o mesmo par, na leitura."
  [:map {:closed true}
   [:id :string]
   [:ocorrido-em :string]
   [:registrado-em :string]])

(def PresencaLoteReciboOut
  "Resposta 201 de POST /sessoes/:id/presenca/lote (Etapa 2c). Os N recibos do lote, na MESMA ORDEM dos
  `registros` do corpo (o cliente casa `recibos[i]` com `registros[i]` por posicao — o lote inteiro entrou
  numa unica transacao, entao a ordem e' estavel e nao ha' recibo parcial). Cada recibo e' o MESMO shape de
  PresencaReciboOut: o lote agrega N atos, nao inventa um vocabulario de saida novo."
  [:map {:closed true}
   [:recibos [:sequential PresencaReciboOut]]])

;; ---------- §22.6 eixo C — justificativa de ausencia (Etapa 2 da chamada) ----------
;; Estes tres contratos EXPOEM `lock-version`, e sao a excecao consciente a regra do cabecalho deste ns. A
;; razao: aqui o token de CAS nao e' interno — e' PARTE DO PROTOCOLO da decisao (PATCH .../decisao exige o
;; lock que o cliente leu). Sem devolve-lo, a tela precisaria de uma leitura extra por linha so' para poder
;; deferir, e a alternativa (aceitar decisao sem CAS) e' a que perde a decisao de um membro da Mesa em
;; silencio. Mesmo desenho de `legislativo/wire/out/documento` e `.../parecer`.
;; `motivo` viaja nestes contratos e SO' nestes: rotas autenticadas, papel exigido na borda, e nenhum evento
;; de dominio o carrega (LGPD — pode ser dado de saude).

(def JustificativaAbertaOut
  "Recibo da abertura de justificativa (resposta 201 de POST /sessoes/:id/justificativas e de
  POST /sessoes/:id/minha-justificativa). Devolve o recurso criado com o estado ('pendente' — quem abre nao decide) e ja'
  com o `lock-version`, p/ a Mesa poder decidir sem uma segunda leitura. NAO ecoa o `motivo`: o cliente
  acabou de envia-lo, e nao ha' ganho em fazer dado sensivel trafegar de volta."
  [:map {:closed true}
   [:id :string]
   [:sessao-id :string]
   [:vereador-id :string]
   [:estado (km/enum-de logic/estados-justificativa)]
   [:lock-version :int]])

(def LinhaJustificativaOut
  "Uma linha de GET /sessoes/:id/justificativas — o ato apartado por vereador, com o token de CAS.
  `decidido-por`/`decidido-em` sao nil enquanto 'pendente' e NOT NULL depois (CHECK
  justificativa_decisao_coerente da mig 0029), entao a nulidade aqui e' o espelho fiel do estado."
  [:map {:closed true}
   [:id :string]
   [:vereador-id :string]
   [:estado (km/enum-de logic/estados-justificativa)]
   [:motivo :string]
   [:decidido-por [:maybe :string]]
   [:decidido-em [:maybe :string]]
   [:lock-version :int]])

(def JustificativasOut
  "Resposta de GET /sessoes/:id/justificativas (papel 'secretario'): as justificativas da sessao, em ordem
  deterministica por `vereador-id` (a Mesa confere linha a linha e a lista nao pode reordenar entre dois
  carregamentos). NAO e' read-model publico — `motivo` pode ser dado de saude."
  [:map {:closed true}
   [:sessao-id :string]
   [:justificativas [:sequential LinhaJustificativaOut]]])

(def JustificativaDecididaOut
  "Recibo da decisao (resposta 200 de PATCH /sessoes/:id/justificativas/:jid/decisao). Carrega a
  `justificativa-id` + o par `de`/`para` — espelha `TransicaoSessaoOut`/`DesistenciaInscricaoOut`, os outros
  dois recibos de maquina de estados do modulo. NAO ecoa o motivo."
  [:map {:closed true}
   [:justificativa-id :string]
   [:de (km/enum-de logic/estados-justificativa)]
   [:para (km/enum-de logic/estados-justificativa)]])

(def InscricaoReciboOut
  "Recibo da inscricao de orador (resposta 201 de POST /sessoes/:id/inscricoes). `id` da inscricao + `ordem` na
  fila (por sessao+fase). O canal SSE ja recebeu inscricao.registrada; este recibo confirma ao chamador."
  [:map {:closed true}
   [:id :string]
   [:ordem :int]])

(def DesistenciaInscricaoOut
  "Recibo da desistencia de inscricao (resposta 200 de POST /sessoes/:id/inscricoes/:insc-id/desistir). Carrega
  a `inscricao-id` + o par `de`/`para` (espelha o recibo de transicao da sessao). NAO expoe o lock-version."
  [:map {:closed true}
   [:inscricao-id :string]
   [:de (km/enum-de logic/estados-inscricao)]
   [:para (km/enum-de logic/estados-inscricao)]])

(def FalaReciboOut
  "Recibo do inicio de fala (resposta 201 de POST /sessoes/:id/falas). So a `fala-id` criada — o canal SSE ja
  recebeu fala.iniciada; este recibo confirma ao chamador a fala a cronometrar/encerrar a seguir."
  [:map {:closed true}
   [:fala-id :string]])

(def CronometroEventoReciboOut
  "Recibo do registro de evento do cronometro (resposta 201 de .../cronometro). So o `id` do evento append-only
  gravado — o canal SSE ja recebeu fala.cronometro p/ atualizar o relogio ao vivo; este recibo confirma."
  [:map {:closed true}
   [:id :string]])

(def FalaEncerradaOut
  "Recibo do encerramento de fala (resposta 200 de .../encerrar). Carrega a `fala-id` + o `tempo-segundos`
  EFETIVAMENTE usado (computado dos eventos do cronometro, projecao). NAO expoe o lock-version."
  [:map {:closed true}
   [:fala-id :string]
   [:tempo-segundos :int]])

(def DecisaoMesaReciboOut
  "Recibo do registro da decisao da mesa (resposta 201 de POST /sessoes/:id/decisoes-mesa). So o `id` da decisao
  append-only gravada — confirma ao chamador o ato lavrado p/ a ata. NAO expoe internos."
  [:map {:closed true}
   [:id :string]])

(def IncidenteReciboOut
  "Recibo do registro de incidente processual (resposta 201 de POST /sessoes/:id/incidentes, §16.13). So o `id`
  do incidente append-only gravado — confirma ao chamador o ato lavrado p/ a ata. NAO expoe internos."
  [:map {:closed true}
   [:id :string]])

(def PautaItemOut
  "Projecao publica de um item ATIVO da pauta (§22.6 eixo B). NAO expoe internos (ente-id, pauta-sessao-id,
  lock-version, ativo). FK-por-tipo: 'proposicao' carrega proposicao-id (string); os demais, texto-descricao."
  [:map {:closed true}
   [:id :string]
   [:fase (km/enum-de logic/fases-pauta)]
   [:tipo-item (km/enum-de logic/tipos-item-pauta)]
   [:proposicao-id {:optional true} [:maybe :string]]
   [:texto-descricao {:optional true} [:maybe :string]]
   [:ordem :int]])

(def PautaOut
  "Pauta viva da sessao (resposta de GET /sessoes/:id/pauta) — o sessao-id + os itens ativos em ordem.
  Pauta opcional: sessao sem pauta criada projeta `itens` vazio."
  [:map {:closed true}
   [:sessao-id :string]
   [:itens [:sequential PautaItemOut]]])

(def GravacaoReciboOut
  "Recibo da ingestao de gravacao (resposta 201 de POST /gravacoes). Carrega o `id` do segmento + o
  `audio-hash` (sha256) p/ o utilitario CLI confirmar integridade/dedup (§22.3.4). NAO expoe a chave interna
  do store (container-bruto-uri)."
  [:map {:closed true}
   [:id :string]
   [:audio-hash :string]])

(def SegmentoOut
  "Projecao publica de um segmento de gravacao no read-model do painel (GET /sessoes/:id/gravacao). NAO expoe
  internos: container-bruto-uri (chave do store), audio-hash, ente-id, lock-version. `audio-disponivel` = se a
  IA ja extraiu o audio (audio-uri presente)."
  [:map {:closed true}
   [:id :string]
   [:sessao-id {:optional true} [:maybe :string]]
   [:iniciou-em :string]
   [:encerrou-em {:optional true} [:maybe :string]]
   [:motivo-inicio (km/enum-de logic/motivos-inicio-gravacao)]
   [:motivo-fim {:optional true} [:maybe (km/enum-de logic/motivos-fim-gravacao)]]
   [:fonte-ingestao (km/enum-de logic/fontes-ingestao-gravacao)]
   [:acesso-restrito :boolean]
   [:audio-disponivel :boolean]])

(def SegmentosOut
  "Read-model dos segmentos de gravacao de uma sessao (GET /sessoes/:id/gravacao)."
  [:map {:closed true}
   [:sessao-id :string]
   [:segmentos [:sequential SegmentoOut]]])

(def VinculoGravacaoOut
  "Recibo da VINCULACAO de um segmento a uma sessao (resposta 201 de POST /sessoes/:id/gravacao/:seg-id/vincular,
  Opcao A pos-upload). Carrega o `id` do segmento + a `sessao-id` a que foi vinculado. NAO expoe internos
  (lock-version, acesso-restrito recalculado, chave do store)."
  [:map {:closed true}
   [:id :string]
   [:sessao-id :string]])

(def PautaItemAdicionadoOut
  "Recibo da adicao de item a pauta (resposta 201 de POST /sessoes/:id/pauta/itens). `id` do item criado +
  `ordem` numerada server-side (max+1). NAO expoe internos (pauta-sessao-id, lock-version, ativo)."
  [:map {:closed true}
   [:id :string]
   [:ordem :int]])

(def PautaItemReordenadoOut
  "Recibo da reordenacao de item (resposta 200 de PATCH /sessoes/:id/pauta/itens/:item-id). `id` do item + o
  par `de`/`para` (ordem anterior/destino). NAO expoe o lock-version."
  [:map {:closed true}
   [:id :string]
   [:de :int]
   [:para :int]])

(def PautaItemRemovidoOut
  "Recibo da remocao SOFT de item (resposta 200 de DELETE /sessoes/:id/pauta/itens/:item-id). So o `id` do item
  removido — a remocao e' ativo=false (nunca DELETE fisico, Inv.10), detalhe interno nao exposto."
  [:map {:closed true}
   [:id :string]])

(def PresencaResumoOut
  "Presenca agregada do tenant (§16.11, FE Onda A1 — card 'o que a Casa entregou'). `media-percentual`
  nil quando nao ha sessao encerrada ainda (0/0 e' indefinido, o FE NAO mostra '0%')."
  [:map {:closed true}
   [:media-percentual [:maybe :int]]
   [:sessoes-consideradas :int]
   [:membros-da-casa :int]])

;; ---------- §22.6 eixo C — a CHAMADA (resposta de GET /sessoes/:id/chamada) ----------

(def LinhaChamadaOut
  "Uma linha da CHAMADA (§22.6 eixo C). `estado` e' o vocabulario PROPRIO da chamada
  (`logic/estados-chamada`) — keyword no dominio (sem CHECK de banco que o espelhe: e' DERIVADO, nao
  persistido), string aqui via `(map name ...)` (a serializacao JSON exige string). `inconsistencia-cadastro`
  = o cadastro contradiz o fato observado (licenciado-mas-presente, ou evento de quem nao tem assento) — e'
  o UNICO canal pelo qual esse conflito chega ao servidor que pode corrigi-lo (`logic/estado-de-presenca`),
  por isso fica no contrato (nao e' detalhe de tela a se perder). `sem-assento` = a linha nao veio do roster
  (evento de vereador que `cadastros` nao situa na Casa naquela data): ela CONTA no numerador do quorum,
  porque e' o que o motor de votacao conta, e por isso `nome` e' nullable (nao ha' identidade a exibir).
  `desde`/`fonte`/`registrado-em` vem do ULTIMO evento de presenca do vereador
  (todos nil se ele nao tem nenhum na sessao): `desde` = o instante de DOMINIO (`ocorrido-em`, quando
  ENTROU/SAIU de fato); `registrado-em` = o instante de AUDIT (quando o evento foi DIGITADO) — sao tempos
  diferentes que a ata precisa distinguir ('entrou as 10h' != 'a secretaria digitou as 11h'). `justificativa`
  so' expoe {:estado :motivo}: o `id`/`lock-version` da decisao sao insumo da Etapa 2 (decidir), que esta
  borda de LEITURA nao serve."
  [:map {:closed true}
   [:vereador-id :string]
   [:nome [:maybe :string]]
   [:nome-parlamentar [:maybe :string]]
   [:partido [:maybe :string]]
   [:cargo-mesa [:maybe :string]]
   [:estado (km/enum-de (map name logic/estados-chamada))]
   [:inconsistencia-cadastro :boolean]
   [:sem-assento :boolean]
   [:desde [:maybe :string]]
   [:fonte [:maybe (km/enum-de logic/fontes-presenca)]]
   [:registrado-em [:maybe :string]]
   [:justificativa [:maybe [:map {:closed true}
                            [:estado (km/enum-de logic/estados-justificativa)]
                            [:motivo :string]
                            ;; `decidido-em` (revisao da Etapa 2): a chamada CONGELA o instante da presenca,
                            ;; mas le' a justificativa no estado CORRENTE — e' o efeito desejado (a Mesa
                            ;; aprecia a falta dias DEPOIS da sessao). Sem este campo, porem, reabrir a
                            ;; chamada de uma sessao encerrada devolvia um estado diferente do da ata
                            ;; impressa, com o MESMO `instante` congelado e nenhum sinal de quando mudou: o
                            ;; juridico via divergencia e nao tinha como saber qual das duas envelheceu. Com
                            ;; ele, a tela marca "justificada apos o encerramento, em <data>" e as duas
                            ;; reconciliam. nil enquanto 'pendente' (CHECK justificativa_decisao_coerente).
                            [:decidido-em [:maybe :string]]]]]])

(def ChamadaQuorumOut
  "A contagem de quorum DESTA chamada (§22.6 eixo C) — numerador (presentes por modalidade) e denominador
  (`membros-da-casa`, que EXCLUI licenciados E linhas sem assento: `logic/contar-quorum`;
  `presencas-fora-do-roster` publica quantas linhas sem assento entraram — e' por isso que os presentes
  PODEM passar de `membros-da-casa`, e a desigualdade e' o sintoma visivel de cadastro furado).
  Distinto de `PresencaResumoOut`: aquele
  e' MEDIA cross-sessao (F7/Onda A1); este e' a contagem literal desta chamada, num instante."
  [:map {:closed true}
   [:presentes-plenario :int]
   [:presentes-remoto :int]
   ;; O NUMERADOR pronto. Existe para o cliente NUNCA somar quorum (revisao adversarial desta branch): o
   ;; telao pintava `presentes-plenario + presentes-remoto`, uma segunda aritmetica do quorum no ponto mais
   ;; distante da regra — e uma terceira categoria positiva em `logic/estados-presentes` a subcontaria em
   ;; silencio, sem erro de tipo e sem teste vermelho.
   [:presentes-total :int]
   [:membros-da-casa :int]
   [:presencas-fora-do-roster :int]])

;; ---------- §22.6 eixo C — o ATO da CHAMADA CONDUZIDA (Etapa 2d) ----------

(def ChamadaConduzidaOut
  "Um ATO de chamada conduzida (Etapa 2d): quando foi conduzida, quem conduziu, e quantos membros a Casa
  tinha NAQUELE instante (`membros-da-casa`, o denominador CONGELADO — pode diferir do quorum atual se a
  composicao mudou entre uma chamada e outra na MESMA sessao). Usada em DOIS lugares com o MESMO shape
  (`recibo-presenca->wire`/`recibos-presenca-lote->wire` sao o precedente): o recibo de
  `POST /sessoes/:id/chamada` (201) e cada item da lista `ChamadaOut.chamadas-conduzidas`. Existe para
  DISTINGUIR 'ninguem chamou ainda' (lista vazia) de 'a chamada ocorreu e a Casa toda faltou' (lista
  nao-vazia com zero presentes) — o read-model de presenca_evento sozinho e' cego a essa diferenca (os dois
  casos produzem zero linhas nele)."
  [:map {:closed true}
   [:id :string]
   [:conduzida-por :string]
   [:membros-da-casa :int]
   [:ocorrido-em :string]
   [:registrado-em :string]])

(def ChamadaOut
  "A CHAMADA da sessao (resposta de GET /sessoes/:id/chamada, §22.6 eixo C). `data-de-composicao` e' a data
  civil que resolveu QUEM compoe a Casa (`aberta-em` se a sessao ja abriu, senao `agendada-para` — nunca
  'hoje' implicito: reabrir a chamada de uma sessao do mes passado nao pode mostrar a composicao de hoje).
  `composicao-resolvida-em` e' o instante de AUDIT em que este calculo RODOU (o relogio do servidor no
  momento da leitura, distinto de `instante`). Os dois juntos sao o que permite a folha (Etapa 5) e a ata
  futura serem FIEIS em vez de readivinhar a composicao. `instante` e' o instante de AVALIACAO da presenca
  corrente: 'agora' enquanto a sessao esta aberta/suspensa; `encerrada-em` (congelado) se ja fechou.
  `sem-registro-de-presenca` = true quando NENHUM vereador tem QUALQUER evento na sessao inteira — distinto
  de uma linha individual `:ausente` (que so' diz que AQUELE vereador nao tem evento; a Casa toda pode ter
  registro e um so' faltar). `chamadas-conduzidas` (Etapa 2d) e' o que desambigua ESSE `sem-registro-de-
  presenca=true`: vazio = 'ninguem conduziu a chamada ainda'; nao-vazio = 'a chamada aconteceu e a Casa toda
  faltou'."
  [:map {:closed true}
   [:sessao-id :string]
   [:sessao-estado (km/enum-de logic/estados-sessao)]
   [:instante :string]
   [:data-de-composicao :string]
   [:composicao-resolvida-em :string]
   [:sem-registro-de-presenca :boolean]
   [:linhas [:sequential LinhaChamadaOut]]
   [:quorum ChamadaQuorumOut]
   [:chamadas-conduzidas [:sequential ChamadaConduzidaOut]]])

;; ---------- §22.6 eixo C — a leitura MAGRA de quorum (Etapa 4a) ----------

(def QuorumSessaoOut
  "So' os NUMEROS do quorum de uma sessao (resposta de GET /sessoes/:id/quorum, Etapa 4a). E' `ChamadaOut`
  MENOS `linhas` e MENOS `chamadas-conduzidas` — o mesmo `quorum` (ChamadaQuorumOut, produzido pelo mesmo
  `logic/contar-quorum`) e os mesmos carimbos que o situam no tempo.

  EXISTE POR AUTHZ, nao por payload. `GET /sessoes/:id/chamada` exige o papel 'secretario' na borda porque a
  sua resposta e' NOMINAL e carrega o `motivo` da justificativa — que pode ser dado de saude (LGPD). Mas o
  painel do plenario (o telao) abre pelo SSE `/sessoes/:id/plenario`, que nao exige papel algum: quem ve o
  telao tomava 403 na unica rota que sabia o DENOMINADOR, e a tela mostrava 'N presentes' sem 'de M'.
  Ampliar a chamada nominal para esse publico levaria o `motivo` junto; emitir uma segunda conta de quorum
  daria duas aritmeticas da composicao da Casa para a mesma sessao (o defeito que as Etapas 1 e 2 gastaram
  uma revisao cada para matar). Este contrato e' a terceira saida: MESMA conta, MENOS campos.

  `:closed true` nao e' decoracao aqui — `adapters/out` valida contra ele, entao um campo nominal que
  reapareca nesta projecao por descuido vira erro de servidor, nao vazamento silencioso.

  Nao carrega `chamadas-conduzidas` de proposito: aquele ato expoe `conduzida-por` (a identidade de quem
  conduziu), que nao e' necessario para contar cabecas."
  [:map {:closed true}
   [:sessao-id :string]
   [:sessao-estado (km/enum-de logic/estados-sessao)]
   [:instante :string]
   [:data-de-composicao :string]
   [:composicao-resolvida-em :string]
   [:sem-registro-de-presenca :boolean]
   [:quorum ChamadaQuorumOut]])

;; ---------- Tribuna nominal — a COMPOSICAO da sessao (resposta de GET /sessoes/:id/composicao) ----------

(def ComposicaoMembroOut
  "Um membro da COMPOSICAO. So' os campos que a rota PUBLICA de vereador (`GET
  /portal/casa/:ente/vereadores/:id`, sem autenticacao nenhuma) ja' devolve — `nome-parlamentar` e
  `cargo-mesa` estao no payload dela; `partido` NAO esta, e por isso nao entra aqui (verificado campo a
  campo contra a rota real — a primeira versao deste contrato o incluia por uma premissa que nao se
  sustentou). Nulaveis pelo MESMO motivo de `LinhaChamadaOut` (roster incompleto, ou vereador sem cargo).
  NUNCA `:nome` civil nem qualquer campo de ESTADO de presenca — esses so' saem pela chamada NOMINAL
  (papel 'secretario', `GET /sessoes/:id/chamada`)."
  [:map {:closed true}
   [:vereador-id :string]
   [:nome-parlamentar [:maybe :string]]
   [:cargo-mesa [:maybe :string]]])

(def ComposicaoSessaoOut
  "A COMPOSICAO da sessao (resposta de `GET /sessoes/:id/composicao`) — resolve o NOME de quem o painel ao
  vivo do plenario so' conhece por `vereador-id` (o SSE de `tempo-real` carrega so' o id no evento).
  `data-de-composicao`/`composicao-resolvida-em` tem o MESMO significado de `ChamadaOut`/`QuorumSessaoOut`
  (a data civil que resolveu a Casa, e o instante de audit em que este calculo rodou) — os dois contratos
  compartilham a MESMA leitura por dentro (`chamada-da-sessao*`), entao os carimbos batem campo a campo
  entre as tres rotas irmas.

  Deliberadamente SEM `instante`, `sem-registro-de-presenca` e `quorum`: esses sao do DOMINIO da presenca
  (o que `/chamada` e `/quorum` respondem), e esta rota responde uma pergunta diferente — 'quem sao', nao
  'quantos/quem esta'. Juntar os dois dava um contrato que repete `ChamadaOut` com metade dos campos por
  um motivo errado (a Etapa 4a ja fez essa distincao para o QuorumSessaoOut; aqui a distincao e' a mesma,
  so' que por IDENTIDADE em vez de por NUMERO)."
  [:map {:closed true}
   [:sessao-id :string]
   [:sessao-estado (km/enum-de logic/estados-sessao)]
   [:data-de-composicao :string]
   [:composicao-resolvida-em :string]
   [:membros [:sequential ComposicaoMembroOut]]])

;; ---------- Tribuna nominal — o ORADOR e a FILA (resposta de GET /sessoes/:id/tribuna) ----------

(def OradorAtualOut
  "O orador COM A PALAVRA agora, ou nil quando ninguem esta na tribuna. Espelha `events.tribuna/
  FalaIniciadaPayload` MENOS `sessao-id` (redundante no path, ja fixado na URL) — o MESMO publico do SSE
  do plenario ja recebe estes campos pelo evento `fala.iniciada`; esta rota so' devolve o SNAPSHOT
  corrente do mesmo dado, para quem abriu a tela DEPOIS do evento ter passado (reload, reconexao longa,
  ou abrir a tela com a fala ja em curso — os tres momentos que apagam o telao hoje). `inscricao-id` e'
  `[:maybe :string]` (nunca `:optional`) porque a projecao sempre inclui a chave — uma fala que nao veio
  de inscricao (ex.: aparte, questao de ordem) tem valor nil, nao chave ausente."
  [:map {:closed true}
   [:fala-id :string]
   [:orador-id :string]
   [:tipo-fala (km/enum-de logic/tipos-fala)]
   [:fase (km/enum-de logic/fases-pauta)]
   [:iniciou-em :string]
   [:inscricao-id [:maybe :string]]])

(def MarcoCronometroOut
  "Um marco ESTRUTURAL do cronometro da fala em curso — espelha `events.tribuna/FalaCronometroPayload`
  MENOS os ids (o marco ja vem aninhado sob a fala em `TribunaOut`; `fala-id` seria redundante). SO' os
  4 tipos MANUAIS (`logic/tipos-evento-cronometro-manual`: pausada/retomada/aparte_concedido/
  tempo_adicional_concedido) — 'iniciada'/'encerrada' NAO aparecem aqui: o SSE nunca os emite pelo evento
  `fala.cronometro` (eles tem os PROPRIOS eventos, `fala.iniciada`/`fala.encerrada`), e `iniciou-em` de
  'iniciada' ja' viaja em `OradorAtualOut`. A tabela `fala_cronometro_evento` grava os 6 tipos (
  `iniciar-fala!`/`encerrar-fala!` logam 'iniciada'/'encerrada' tambem, de proposito, para a serie
  completa existir) — incluir 'iniciada'/'encerrada' aqui seria um campo que o canal nunca serviu por
  este evento, vazamento por omissao do filtro, nao conveniencia (Constraint 7)."
  [:map {:closed true}
   [:tipo (km/enum-de logic/tipos-evento-cronometro-manual)]
   [:ocorrido-em :string]
   [:segundos-adicionais [:maybe :int]]])

(def InscritoTribunaOut
  "Um inscrito da FILA ATIVA (desistencias fora — `logic/estados-inscricao-terminais`) — espelha
  `events.tribuna/InscricaoRegistradaPayload` MENOS `sessao-id` (redundante no path). Ordenado por
  (fase, ordem), a MESMA ordem de `db/tribuna/listar-inscricoes`."
  [:map {:closed true}
   [:inscricao-id :string]
   [:vereador-id :string]
   [:origem-inscricao (km/enum-de logic/origens-inscricao)]
   [:fase (km/enum-de logic/fases-pauta)]
   [:ordem :int]])

(def TribunaOut
  "O estado corrente da TRIBUNA (resposta de `GET /sessoes/:id/tribuna`) — o read-model que faltava ao
  painel ao vivo do plenario: as 5 rotas de ESCRITA da tribuna (inscrever/desistir/iniciar-fala/
  cronometro/encerrar-fala) nunca tiveram uma de LEITURA, e o telao so' sabia reconstruir o estado por
  SSE — um reload, uma reconexao > 5 min (a janela de replay do canal) ou abrir a tela DEPOIS da fala
  comecar deixavam 'Ninguem com a palavra' com alguem efetivamente falando (ledger de prontidao #7).

  `orador-atual` nil = ninguem com a palavra agora; `marcos-cronometro` vazio quando nao ha' fala em
  curso (nao ha' cronometro de ninguem para mostrar). Payload = a UNIAO EXATA do que
  `sessoes.events.tribuna` ja' transmite pelo canal do plenario (Constraint 7) — SEM roster e SEM nome de
  vereador: este ns nunca importa `cadastros` (§22.10), e o nome de quem esta na tribuna vem de
  `/composicao` (o irmao que resolve identidade), nao daqui. O PUBLICO e' o do SSE MAIS o secretario nas
  sessoes SECRETAS (o SSE recusa a subscricao inteira nessas; esta rota nao) — nao 'o mesmo publico',
  ver a docstring de `tribuna-handler`."
  [:map {:closed true}
   [:sessao-id :string]
   [:orador-atual [:maybe OradorAtualOut]]
   [:marcos-cronometro [:sequential MarcoCronometroOut]]
   [:inscritos [:sequential InscritoTribunaOut]]])

;; ---------- Etapa 5 fatia 5 — a FOLHA DA SESSAO (metadados de congelamento) ----------

(def FolhaMetadadosOut
  "Metadados de UMA versao congelada da folha de presenca — resposta de `POST /sessoes/:id/folha` (201) e de
  cada item de `GET /sessoes/:id/folhas` (200). NUNCA o binario nem os `*_objeto_store_ref` (detalhe de
  armazenamento interno) — so' os DOIS hashes de integridade (o cliente confere sem baixar o conteudo), a
  versao IMPRESSA no papel (D7) e os carimbos de proveniencia. `:ja-congelada` so' aparece no caminho do
  dedup de D9 (reenvio do MESMO ator dentro da janela de 30s devolve a versao EXISTENTE) — `:closed true`
  com o campo `:optional` deixa a chave simplesmente AUSENTE no caminho normal, nunca `false` explicito."
  [:map {:closed true}
   [:id :string]
   [:versao :int]
   [:spec-versao :string]
   [:html-hash :string]
   [:pdf-hash :string]
   [:gerada-por :string]
   [:gerada-em :string]
   [:ja-congelada {:optional true} :boolean]])

(def FolhasDaSessaoOut
  "Resposta de `GET /sessoes/:id/folhas` — todas as versoes congeladas da sessao, mais recente primeiro
  (metadados apenas, mesma disciplina de `FolhaMetadadosOut`)."
  [:map {:closed true}
   [:sessao-id :string]
   [:folhas [:sequential FolhaMetadadosOut]]])

;; ---------- Etapa 6 fatia 3 — a APURACAO DE ASSIDUIDADE (resposta de GET /assiduidade) ----------
;; O payload de `logic/apurar-assiduidade` (ver a docstring la' para a semantica de cada campo) projetado p/
;; JSON: uuid vira string, `LocalDate` vira string ISO, o `:estado` KEYWORD de `logic/estados-chamada` vira
;; string (mesmo `(name ...)` de `LinhaChamadaOut.estado`, acima). `IDENTIDADE UMA SO VEZ` (LGPD, carry da
;; revisao da Fatia 1): o nome/partido do vereador vive SO em `AssiduidadeVereadorOut`; `AssiduidadeDetalheLinhaOut`
;; referencia por `:vereador-id` e NUNCA repete o nome civil por linha — no teto (54.900 linhas de detalhe)
;; seriam dezenas de milhares de repeticoes do mesmo nome num payload so'.

(def AssiduidadeSessaoOut
  "Uma sessao do periodo apurado — `GET /assiduidade`. `:quorum` reusa `ChamadaQuorumOut` POR REFERENCIA
  (a MESMA aritmetica de `contar-quorum`, nunca uma segunda forma de contagem so' para este contrato).
  `:sigilosa` = a sessao NAO transmite publicamente (Etapa 4); ela ainda entra nos totais (o dever de
  comparecer e' real), mas o CSV de detalhe (Fatia 3) marca CADA linha dela, nunca so' o agregado."
  [:map {:closed true}
   [:id :string]
   [:numero :int]
   [:tipo (km/enum-de logic/tipos-sessao)]
   [:estado (km/enum-de logic/estados-sessao)]
   [:data-de-referencia :string]
   [:sigilosa :boolean]
   [:quorum ChamadaQuorumOut]])

(def AssiduidadeVereadorOut
  "A identidade de UM vereador que aparece em pelo menos uma linha do periodo — publicada UMA SO VEZ (ver
  o comentario do topo desta secao). `:partido` e' HONESTO, nao constante presumida: mandatos sequenciais
  com partidos diferentes dentro do periodo publicam `:partido nil` + `:partido-variou true` (a Casa lista
  por partido no oficio, mas o campo nao pode fixar um rotulo escolhido pela ordem das sessoes). `:nome`
  nulo = presenca SEM ASSENTO em toda a janela pedida (evento de quem `cadastros` nao situa na Casa em
  nenhuma das datas do periodo) — `sessoes` nao inventa identidade que `cadastros` nao devolveu."
  [:map {:closed true}
   [:id :string]
   [:nome [:maybe :string]]
   [:nome-parlamentar [:maybe :string]]
   [:partido [:maybe :string]]
   [:partido-variou :boolean]])

(def AssiduidadePorVereadorOut
  "O agregado de UM vereador ao longo do periodo — a linha-resumo do CSV `recorte=resumo` e a fonte do
  JSON. Nomes DISTINTOS do card publico de `transparencia` de proposito (`:sessoes-computadas`/
  `:comparecimentos`, nunca `:presenca`/`:sessoes-presente`): a Casa nao pode ver dois numeros de
  assiduidade do MESMO vereador com o MESMO rotulo, um vindo da vitrine publica e outro desta apuracao
  interna. `:ausencias-com-justificativa-pendente` e' bucket PROPRIO — nunca colapsado em
  `:ausencias-injustificadas` (I4: a Mesa ainda nao decidiu, e contar como injustificada e' acusacao falsa).
  `:percentual` nil quando `:sessoes-computadas` e' zero (NUNCA 0 — leria como 'faltou a tudo'; a diferenca
  entre 'nao podia comparecer a nada' e 'faltou a tudo' e' a diferenca entre um suplente e um faltoso). NAO
  clampado a [0,100] de proposito — ver `logic/apurar-assiduidade`."
  [:map {:closed true}
   [:vereador-id :string]
   [:sessoes-computadas :int]
   [:comparecimentos :int]
   [:ausencias-justificadas :int]
   [:ausencias-com-justificativa-pendente :int]
   [:ausencias-injustificadas :int]
   [:sessoes-licenciado :int]
   [:percentual [:maybe :int]]])

(def AssiduidadeDetalheLinhaOut
  "Uma linha (sessao, vereador) — a fonte do CSV `recorte=detalhe`. `:estado` e' o vocabulario PROPRIO da
  chamada (`logic/estados-chamada`, keyword no dominio, string aqui via `name` — mesmo contrato de
  `LinhaChamadaOut.estado`). `:sigilosa` marca CADA LINHA, nao so' o total (`AssiduidadeTotaisOut.
  sessoes-sigilosas` diz QUANTAS, nao QUAIS) — a Fatia 3 serializa isto para um CSV que circula por e-mail,
  e a linha de uma sessao secreta identica a uma ordinaria e' o achado que a revisao da Fatia 2 pegou. NAO
  carrega `motivo` da justificativa (dado de saude, LGPD) — este contrato nao serve decisao, so' contagem."
  [:map {:closed true}
   [:sessao-id :string]
   [:vereador-id :string]
   [:estado (km/enum-de (map name logic/estados-chamada))]
   [:sigilosa :boolean]])

(def AssiduidadeTotaisOut
  "Os agregados do PERIODO inteiro + o texto que faz o payload (e o CSV que dele deriva) se EXPLICAR
  SOZINHO: `:criterio-de-inclusao` e `:nota-de-metodologia` sao PROSA fixa (nunca dado de usuario), porque o
  CSV circula solto, sem o JSON ao lado. `:sessoes-sem-data-de-referencia` (I7) e' o que a data de referencia
  EXCLUIU do periodo — sem ele o denominador de todo vereador encolheria sem explicacao."
  [:map {:closed true}
   [:sessoes-consideradas :int]
   [:vereadores-considerados :int]
   [:sessoes-sigilosas :int]
   [:sessoes-sem-data-de-referencia :int]
   [:criterio-de-inclusao :string]
   [:nota-de-metodologia :string]])

(def AssiduidadeOut
  "A resposta de `GET /assiduidade` (Etapa 6 fatia 3, papel 'secretario') — o mapa completo de
  `logic/apurar-assiduidade` projetado a JSON. `:closed true`: um campo novo em `logic/apurar-assiduidade`
  que este contrato nao souber vira 500 de servidor (drift, nunca resposta silenciosamente incompleta) — o
  gate do codegen (`gerar-sessoes-test/b4-manifesto-cobre-TODO-o-wire-out`) faz o mesmo do lado do TS."
  [:map {:closed true}
   [:sessoes [:sequential AssiduidadeSessaoOut]]
   [:vereadores [:sequential AssiduidadeVereadorOut]]
   [:por-vereador [:sequential AssiduidadePorVereadorOut]]
   [:detalhe [:sequential AssiduidadeDetalheLinhaOut]]
   [:totais AssiduidadeTotaisOut]])
