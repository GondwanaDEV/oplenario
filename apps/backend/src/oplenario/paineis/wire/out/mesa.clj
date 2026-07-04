(ns oplenario.paineis.wire.out.mesa
  "Representacao EXTERNA de SAIDA do DASHBOARD DA MESA (§22.10 wire/out, ADR-0001, F7) — o contrato de borda
  que o `adapters/out` produz e do qual o Eixo 8 gera os tipos TS do front (a tela do comprador / vitrine
  institucional da Mesa, §16.11 item 11.4). Tudo JSON-serializavel.

  O dashboard e' uma COMPOSICAO, NUNCA uma reprojecao: os ROLLUPS (tramitacao/pendencias/sessoes) sao
  agregados das tabelas que o proprio `paineis` ja' projeta; o card `compliance-tce` e' o `PainelOut` do
  modulo `compliance` — recebido JA' PROJETADO/FILTRADO pela borda de compliance (via inversao de dependencia
  no host, espelhando `consultar-sessao`) e embutido OPACO. `paineis` nao conhece — nem redeclara — a forma
  interna de compliance (§22.10: comunicacao so' HTTP/eventos; nunca cross-schema JOIN, nunca import
  cross-modulo). O `[:map]` opaco e' deliberado: redeclarar `PainelOut` aqui duplicaria o contrato de outro
  modulo (drift garantido). Custo: o codegen TS de F1.5 tipa `complianceTce` como `Record<string, unknown>` —
  o FE o estreita reusando o `PainelOut` que o proprio compliance ja' gera (a Mesa consome os dois contratos).")

(def TramitacaoResumoOut
  "Rollup 'proposicoes por status' (§16.11): contagem por estado da maquina de tramitacao + total. `estado`
  fica :string (nao enum fechado) — e' PROJECAO de `legislativo` (mesmo racional de wire/out/tramitacao); o
  vocabulario e' validado na FONTE."
  [:map {:closed true}
   [:total :int]
   [:por-estado [:sequential [:map {:closed true} [:estado :string] [:n :int]]]]])

(def PendenciasResumoOut
  "Rollup do painel 'o que vence' (§16.11): contagens das pendencias de atendimento ao cidadao. `abertas` =
  `pendentes` + `vencidas` (as duas fases abertas do read-model paineis.pendencia)."
  [:map {:closed true}
   [:abertas :int]
   [:vencidas :int]
   [:pendentes :int]])

(def SessoesResumoOut
  "Rollup do SLI de janela de sessao (Inv.9, §16.11): contagem por situacao de negocio derivada + os dois
  numeros-manchete que a Mesa le' (sessoes em curso agora; no-shows/nao realizadas). `situacao` = rotulo
  DERIVADO (em_curso/suspensa/realizada/nao_realizada/agendada) ou o estado cru se a fonte ganhar um estado
  novo (mesmo fallback defensivo de wire/out/sli_sessao)."
  [:map {:closed true}
   [:em-curso :int]
   [:nao-realizadas :int]
   [:por-situacao [:sequential [:map {:closed true} [:situacao :string] [:n :int]]]]])

(def MesaOut
  "O dashboard institucional da Mesa (resposta de GET /paineis/mesa, §16.11 item 11.4): o card de compliance
  do TCE (opaco, do modulo compliance) + os tres rollups que o paineis compoe dos seus proprios read-models +
  `lacunas` (facetas do §16.11 ainda nao materializadas — presenca agregada, engajamento cidadao — expostas
  HONESTAMENTE p/ o FE nao sugerir cobertura que nao existe)."
  [:map {:closed true}
   ;; :compliance-tce e' um mapa ABERTO de proposito: OU o PainelOut de compliance (embutido opaco — paineis
   ;; nao redeclara a forma de outro modulo, §22.10) OU o sentinel {:indisponivel true} quando a leitura
   ;; cross-modulo falha (degradacao por card, diplomat/http/in). O FE distingue pela chave :indisponivel.
   [:compliance-tce :map]
   [:tramitacao TramitacaoResumoOut]
   [:pendencias PendenciasResumoOut]
   [:sessoes SessoesResumoOut]
   [:lacunas [:sequential :string]]])
