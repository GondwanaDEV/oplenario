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
                            [:motivo :string]]]]])

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
   [:membros-da-casa :int]
   [:presencas-fora-do-roster :int]])

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
  registro e um so' faltar)."
  [:map {:closed true}
   [:sessao-id :string]
   [:sessao-estado (km/enum-de logic/estados-sessao)]
   [:instante :string]
   [:data-de-composicao :string]
   [:composicao-resolvida-em :string]
   [:sem-registro-de-presenca :boolean]
   [:linhas [:sequential LinhaChamadaOut]]
   [:quorum ChamadaQuorumOut]])
