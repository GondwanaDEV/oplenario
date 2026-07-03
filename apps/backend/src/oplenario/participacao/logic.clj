(ns oplenario.participacao.logic
  "PURO (§22.10 logic): o vocabulario do e-SIC (ciclo VISIVEL ao cidadao do pedido + ciclo do prazo,
  forma disc.6) + a matematica do prazo LAI + read-derivations. Sem banco, sem motor — funcoes puras +
  vocabularios (fonte unica dos enums; os CHECK da mig 0039 espelham). Espelha a disciplina de
  compliance/logic (enum FIXO em codigo, nao template).

  Prazo do e-SIC = 20 dias (LAI 12.527/2011, art. 11 §1º). [GAP] DE CONTEUDO: a LAI nao cravou
  'corridos vs uteis' -> V1 conta DIA-CORRIDO (LocalDate.plusDays), NAO afirma dias uteis nem adiciona
  feriados. Quando o conteudo resolver p/ dias uteis, a matematica migra p/ um `kernel/calendario.clj`
  compartilhado (disc.5; toca o motor — fora do caminho critico do e-SIC)."
  (:import (java.time LocalDate)))

(set! *warn-on-reflection* true)

;; ---- ciclo VISIVEL ao cidadao (pedido_esic.estado) — enum FIXO em codigo ----
(def estados-pedido
  "Ciclo do pedido e-SIC visivel ao cidadao. `respondido`/`indeferido` sao terminais (trava a linha)."
  #{"protocolado" "em_analise" "respondido" "indeferido"})

(def ^:private estados-terminais-pedido
  "Desfechos do pedido: o orgao ja respondeu (a linha congela — trg imut_trava_estado_terminal)."
  #{"respondido" "indeferido"})

(def ^:private transicoes-pedido
  "Grafo de transicoes LEGAIS (de -> conjunto de proximos). Terminais nao tem saida."
  {"protocolado" #{"em_analise" "respondido" "indeferido"}
   "em_analise"  #{"respondido" "indeferido"}
   "respondido"  #{}
   "indeferido"  #{}})

;; ---- ciclo do PRAZO (prazo_ativo.estado) — forma disc.6 (polimorfico, o ciclo E a maquina) ----
(def estados-prazo
  "Ciclo do prazo materializado (prazo_ativo.estado), forma disc.6 (espelha compliance/prazo_dominio_ativo).
  `cumprida` disparada quando o agregado chega ao desfecho respondido; `vencida` pelo sweep (F6.3)."
  #{"pendente" "cumprida" "vencida" "dispensada" "cancelada"})

(def objeto-tipos-prazo
  "Objeto polimorfico que um prazo pode monitorar (prazo_ativo.objeto_tipo). `manifestacao_ouvidoria` entra
  no fast-follow de ouvidoria (mig 0042 estende o CHECK)."
  #{"pedido_esic" "recurso_esic" "solicitacao_titular" "manifestacao_ouvidoria"})

;; ---- GENERALIZACAO (fast-follow): prorrogacao do prazo (compartilhada pelas 4 especies) ----
(defn vencimento-efetivo
  "Read-derivation PURA: o vencimento QUE VALE de um prazo materializado, respeitando prorrogacao —
  COALESCE(prorrogado-ate, vence-em). Sem prorrogado-ate (nil), DEGENERA para o vence-em cru (backward-safe:
  e-SIC/LGPD nunca prorrogam em V1, so a ouvidoria usa o mecanismo). Reusar SEMPRE no lugar de `(:vence-em
  prazo)` cru em toda leitura de vencimento (dias-restantes/vencido?/sweep) — o 'anel' do cidadao ja mostra
  a data prorrogada."
  ^LocalDate [{:keys [vence-em prorrogado-ate]}]
  (or prorrogado-ate vence-em))

;; ---- ciclo VISIVEL do RECURSO (recurso_esic.estado) — enum FIXO em codigo (Slice 2) ----
(def estados-recurso
  "Ciclo do recurso e-SIC visivel ao cidadao. `decidido` e' terminal (trava a linha — trg_recurso_esic_trava_terminal)."
  #{"protocolado" "decidido"})

(def ^:private estados-terminais-recurso
  "Desfecho do recurso: a autoridade ja decidiu (a linha congela)."
  #{"decidido"})

(def ^:private transicoes-recurso
  "Grafo de transicoes LEGAIS do recurso (de -> conjunto de proximos). Terminal nao tem saida."
  {"protocolado" #{"decidido"}
   "decidido"    #{}})

(def ^:private estados-pedido-recorriveis
  "Estados do PEDIDO a partir dos quais o cidadao pode INTERPOR recurso: so os DESFECHOS (respondido|indeferido).
  Recorrer de um pedido ainda em curso (protocolado|em_analise) nao faz sentido — nao ha o que recorrer ainda."
  #{"respondido" "indeferido"})

;; ---- matematica do prazo LAI ----
(def dias-lai-esic
  "Prazo do e-SIC em dias (LAI art. 11 §1º = 20). [GAP]: corridos-vs-uteis nao cravado -> V1 = corridos."
  20)

(def dias-recurso-esic
  "Prazo (em dias) do RECURSO e-SIC — o relogio PROPRIO da instancia recursal.

  [GAP] DE CONTEUDO: a LAI da a autoridade superior/CGU um prazo PROPRIO no julgamento do recurso, MAS o
  numero exato NAO esta cravado nesta fatia (varia por instancia/autoridade e por corridos-vs-uteis, o mesmo
  [GAP] do pedido). O ponto da fatia e' o MECANISMO de RELOGIO INDEPENDENTE (o recurso materializa a 2a linha
  de prazo_ativo, com vence_em proprio), NAO o valor. V1 = DEFAULT DOCUMENTADO HARDCODED [GAP] — constante de
  compile-time, AINDA SEM seam de config por ente/env (ajustar exige deploy; fiar a config = carry). Escolhido
  deliberadamente != 20 do pedido, p/ nao mascarar a independencia dos relogios; confirmar com juridico antes
  de prod. NAO afirmar este numero como lei."
  10)

(defn vence-em
  "Data de vencimento do prazo LAI a partir do LocalDate do recibo (marco de inicio do relogio). DIA-CORRIDO:
  `.plusDays 20` sobre a data civil do recibo. [GAP] de conteudo: NAO afirma dias uteis nem adiciona feriados."
  ^LocalDate [^LocalDate recibo-data]
  (.plusDays recibo-data (long dias-lai-esic)))

(defn dias-restantes
  "Read-derivation PURA (§ Arch B: monitoramento = derivacao sobre vence_em, sem cross-schema): diferenca
  em dias civis entre `vence-em` e `hoje`. Positivo = ainda ha prazo; 0 = ultimo dia; negativo = vencido
  (quem transiciona p/ 'vencida' e' o sweep de F6.3 — aqui e' leitura pura, como motor/runtime monitorar)."
  [^LocalDate venc ^LocalDate hoje]
  (- (.toEpochDay venc) (.toEpochDay hoje)))

(defn vencido?
  "Predicado PURO de vencimento: o prazo esta vencido em `hoje`? Vencido ⟺ `hoje` ESTRITAMENTE apos `venc`
  (equivalente a `dias-restantes < 0`) — o proprio dia do vencimento NAO vence ('0 = ultimo dia', ainda
  valido). Coerente com `dias-restantes` e com o sweep do compliance (vence_em < data, estrito). `venc` nil =
  NAO vencido (guarda anti-NPE, espelha o guard sec do compliance/logic vencido?, review sec MEDIO-1). O SQL do
  sweep (`vencer-se-pendente!` CAS) e' a FONTE DE VERDADE da transicao; este predicado e' so p/ derivacao/teste."
  [^LocalDate venc ^LocalDate hoje]
  (boolean (and venc (.isAfter hoje venc))))

(defn vence-em-recurso
  "Data de vencimento do prazo do RECURSO a partir do LocalDate do recibo do recurso (marco de inicio do
  RELOGIO PROPRIO da instancia recursal). DIA-CORRIDO `.plusDays dias-recurso-esic`. [GAP] de conteudo
  (ver dias-recurso-esic): o numero e' default documentado, nao lei; o mecanismo (relogio independente do
  pedido) e' o que a fatia crava."
  ^LocalDate [^LocalDate recibo-data]
  (.plusDays recibo-data (long dias-recurso-esic)))

(defn protocolo-esic
  "Numero de PROTOCOLO humano do pedido a partir do (ano, sequencial gapless). Formato estavel
  'ESIC-<ano>-<seq 6 digitos>' (ex.: ESIC-2026-000001). PURO — o sequencial gapless vem do kernel."
  [ano sequencial]
  (format "ESIC-%d-%06d" (long ano) (long sequencial)))

(defn protocolo-recurso
  "Numero de PROTOCOLO humano do recurso a partir do (ano, sequencial gapless). Formato 'REC-<ano>-<seq 6
  digitos>' (ex.: REC-2026-000001) — namespace distinto do pedido (ESIC-) p/ nao colidir na leitura humana."
  [ano sequencial]
  (format "REC-%d-%06d" (long ano) (long sequencial)))

;; ---- validadores (guardas de profundidade; espelham os demais validar-* do projeto) ----

(defn- validar! [conjunto rotulo v]
  (when-not (contains? conjunto v)
    (throw (ex-info (str rotulo " invalido: " (pr-str v)) {:valor v :validos conjunto})))
  nil)

(defn validar-estado-pedido
  "Lanca se `v` nao e' estado do pedido (protocolado|em_analise|respondido|indeferido)."
  [v] (validar! estados-pedido "estado de pedido e-SIC" v))

(defn validar-estado-prazo
  "Lanca se `v` nao e' estado do prazo (pendente|cumprida|vencida|dispensada|cancelada)."
  [v] (validar! estados-prazo "estado de prazo" v))

(defn validar-objeto-tipo-prazo
  "Lanca se `v` nao e' objeto_tipo de prazo (pedido_esic|recurso_esic|solicitacao_titular|manifestacao_ouvidoria)."
  [v] (validar! objeto-tipos-prazo "objeto_tipo de prazo" v))

(defn validar-estado-recurso
  "Lanca se `v` nao e' estado do recurso (protocolado|decidido)."
  [v] (validar! estados-recurso "estado de recurso e-SIC" v))

(defn terminal-pedido?
  "O estado do pedido e' terminal (o orgao ja respondeu/indeferiu)?"
  [estado] (contains? estados-terminais-pedido estado))

(defn terminal-recurso?
  "O estado do recurso e' terminal (a autoridade ja decidiu)?"
  [estado] (contains? estados-terminais-recurso estado))

(defn transicao-pedido-valida?
  "A transicao `de`->`para` do ciclo do pedido e' legal? (pura — so o grafo fixo). Terminais nao transicionam."
  [de para]
  (contains? (get transicoes-pedido de) para))

(defn transicao-recurso-valida?
  "A transicao `de`->`para` do ciclo do recurso e' legal? (pura — so o grafo fixo). Terminal nao transiciona."
  [de para]
  (contains? (get transicoes-recurso de) para))

(defn pedido-admite-recurso?
  "O pedido no `estado` dado admite a interposicao de recurso? So os DESFECHOS (respondido|indeferido) —
  recorrer de um pedido ainda em curso e' conflito (a borda mapeia p/ 409)."
  [estado]
  (contains? estados-pedido-recorriveis estado))

;; ========================= SLICE 4: LGPD — solicitacao do titular (contador SEPARADO) =========================

;; ---- os 5 DIREITOS do titular (LGPD 13.709/2018 art. 18) — enum FIXO em codigo (o CHECK da mig 0041 espelha) ----
(def tipos-solicitacao-titular
  "Os 5 direitos que o titular pode exercer (solicitacao_titular.tipo). `revogar_consentimento` PODE ser cumprido
  automaticamente via o modulo identidade (guard futuro do host sobre repo-identidade) — CARRY: em V1 todos os 5
  tipos sao registrados UNIFORMEMENTE e o DPO processa manual (nada de cross-modulo especulativo agora)."
  #{"acessar" "corrigir" "eliminar" "com_quem_compartilhado" "revogar_consentimento"})

;; ---- ciclo VISIVEL da solicitacao (solicitacao_titular.estado) — enum FIXO em codigo ----
(def estados-solicitacao-titular
  "Ciclo da solicitacao do titular. `respondida`/`indeferida` sao terminais (trava a linha — trg da mig 0041).
  Feminino (a solicitacao) — distinto do ciclo masculino do pedido e-SIC."
  #{"protocolada" "em_analise" "respondida" "indeferida"})

(def ^:private estados-terminais-solicitacao-titular
  "Desfechos da solicitacao: o controlador ja respondeu/indeferiu (a linha congela)."
  #{"respondida" "indeferida"})

(def ^:private transicoes-solicitacao-titular
  "Grafo de transicoes LEGAIS da solicitacao (de -> conjunto de proximos). Terminais nao tem saida.
  [GAP] DE PRODUTO (espelha o e-SIC 'indeferido' da Slice 2): em V1 o unico desfecho PRODUZIDO por codigo e'
  `respondida` (o servico so tem `responder!`); `indeferida` fica MODELADA no grafo + no CHECK da mig 0041, mas
  SEM caminho de controller/db que a alcance ainda (estado terminal reservado p/ um 'indeferir' explicito futuro)."
  {"protocolada" #{"em_analise" "respondida" "indeferida"}
   "em_analise"  #{"respondida" "indeferida"}
   "respondida"  #{}
   "indeferida"  #{}})

;; ---- prazo LGPD: CONTADOR SEPARADO do e-SIC ----
(def dias-titular
  "Prazo (em dias) da solicitacao do titular LGPD — o CONTADOR SEPARADO do e-SIC.

  [GAP] DE CONTEUDO: a LGPD (13.709/2018) NAO cravou um prazo unico p/ todas as requisicoes do titular (o art.
  19 fixa 15 dias so p/ a confirmacao/acesso; outros direitos nao tem numero estatutario unico; e corridos-vs-
  uteis segue o mesmo [GAP] da LAI). O ponto desta fatia e' o MECANISMO de CONTADOR SEPARADO (a solicitacao
  materializa a 3a especie de prazo_ativo, com vence_em PROPRIO — nao reusa os 20 dias da LAI), NAO o valor.
  V1 = DEFAULT DOCUMENTADO HARDCODED — constante de compile-time, AINDA SEM seam de config por ente/env (ajustar
  exige deploy; fiar a config = carry). Escolhido != 20 do e-SIC p/ nao mascarar que os contadores sao distintos;
  confirmar com juridico antes de prod. NAO afirmar este numero como lei."
  15)

(defn vence-em-titular
  "Data de vencimento do prazo LGPD a partir do LocalDate do recibo (marco de inicio do relogio do titular).
  DIA-CORRIDO `.plusDays dias-titular` — CONTADOR SEPARADO do e-SIC (reusa a mesma matematica de dia-corrido, nao
  o mesmo numero). [GAP] de conteudo (ver dias-titular): default documentado, nao lei; sem feriados/dias uteis."
  ^LocalDate [^LocalDate recibo-data]
  (.plusDays recibo-data (long dias-titular)))

(defn protocolo-titular
  "Numero de PROTOCOLO humano da solicitacao do titular a partir do (ano, sequencial gapless). Formato
  'LGPD-<ano>-<seq 6 digitos>' (ex.: LGPD-2026-000001) — namespace distinto de ESIC-/REC- p/ nao colidir."
  [ano sequencial]
  (format "LGPD-%d-%06d" (long ano) (long sequencial)))

(defn validar-tipo-solicitacao-titular
  "Lanca se `v` nao e' um dos 5 direitos do titular (acessar|corrigir|eliminar|com_quem_compartilhado|revogar_consentimento)."
  [v] (validar! tipos-solicitacao-titular "tipo de solicitacao do titular" v))

(defn validar-estado-solicitacao-titular
  "Lanca se `v` nao e' estado da solicitacao (protocolada|em_analise|respondida|indeferida)."
  [v] (validar! estados-solicitacao-titular "estado de solicitacao do titular" v))

(defn terminal-solicitacao-titular?
  "O estado da solicitacao e' terminal (o controlador ja respondeu/indeferiu)?"
  [estado] (contains? estados-terminais-solicitacao-titular estado))

(defn transicao-solicitacao-titular-valida?
  "A transicao `de`->`para` do ciclo da solicitacao e' legal? (pura — so o grafo fixo). Terminais nao transicionam."
  [de para]
  (contains? (get transicoes-solicitacao-titular de) para))

;; ========================= FAST-FOLLOW: Slice 5 — Ouvidoria (Lei 13.460/2017 art. 10) =========================

;; ---- os 5 TIPOS de manifestacao (padrao CGU/Lei 13.460) — enum FIXO em codigo (o CHECK da mig 0042 espelha) ----
(def tipos-manifestacao
  "Os 5 tipos padrao de manifestacao de ouvidoria (Lei 13.460/2017, padrao CGU): reclamacao, denuncia,
  sugestao, elogio, solicitacao (NAO confundir com pedido_esic — 'solicitacao' aqui e' um TIPO de
  manifestacao de ouvidoria, ex.: pedido de servico)."
  #{"reclamacao" "denuncia" "sugestao" "elogio" "solicitacao"})

;; ---- ciclo VISIVEL da manifestacao (manifestacao_ouvidoria.estado) — enum FIXO em codigo ----
(def estados-manifestacao
  "Ciclo da manifestacao de ouvidoria. `respondida`/`arquivada` sao terminais (trava a linha — trg da mig
  0042). `arquivada` NAO e' desfecho de merito (a manifestacao foi encerrada sem resposta de conteudo —
  o prazo correspondente vira 'cancelada', nao 'cumprida')."
  #{"protocolada" "em_analise" "respondida" "arquivada"})

(def ^:private estados-terminais-manifestacao
  "Desfechos da manifestacao: respondida (com merito) OU arquivada (sem merito). A linha congela nos dois."
  #{"respondida" "arquivada"})

(def ^:private transicoes-manifestacao
  "Grafo de transicoes LEGAIS da manifestacao (de -> conjunto de proximos). Terminais nao tem saida."
  {"protocolada" #{"em_analise" "respondida" "arquivada"}
   "em_analise"  #{"respondida" "arquivada"}
   "respondida"  #{}
   "arquivada"   #{}})

;; ---- prazo da ouvidoria: 30 dias, prorrogavel POR IGUAL PERIODO uma unica vez (Lei 13.460 art. 10) ----
(def dias-ouvidoria
  "Prazo da manifestacao de ouvidoria em dias (Lei 13.460/2017 art. 10 = 30, prorrogavel por igual periodo
  mediante justificativa). [GAP] DE CONTEUDO: corridos-vs-uteis nao cravado (mesmo GAP do e-SIC/LGPD) -> V1
  = DIA-CORRIDO. Distinto dos demais contadores (LAI 20; LGPD [GAP] 15) — nao reusa nenhum dos dois."
  30)

(def dias-prorrogacao-ouvidoria
  "A prorrogacao da Lei 13.460 art. 10 e' 'por igual periodo' — MESMO valor de dias-ouvidoria (30), somado
  a partir do vence_em ORIGINAL (a CAS de prorrogar! so permite 1x; nao ha prorrogado_ate previo a somar)."
  dias-ouvidoria)

(defn vence-em-ouvidoria
  "Data de vencimento do prazo da ouvidoria a partir do LocalDate do recibo (marco de inicio do relogio).
  DIA-CORRIDO `.plusDays 30`. [GAP] de conteudo: NAO afirma dias uteis nem adiciona feriados."
  ^LocalDate [^LocalDate recibo-data]
  (.plusDays recibo-data (long dias-ouvidoria)))

(defn vence-prorrogado-ouvidoria
  "Nova data de vencimento apos a UNICA prorrogacao possivel (Lei 13.460 art. 10: +30 'por igual periodo').
  Soma a partir do `vence-em-original` (NAO de um prorrogado_ate previo — a CAS de db/prazo-ativo/prorrogar!
  so admite prorrogar quando prorrogado_ate AINDA e' nil, entao so ha 1 base possivel: a original)."
  ^LocalDate [^LocalDate vence-em-original]
  (.plusDays vence-em-original (long dias-prorrogacao-ouvidoria)))

;; ========================= SLICE 6: Comentarios/moderacao (feature 6.3) =========================
;;
;; Comentario NAO tem protocolo/prazo (nao e' protocolo juridico nem obrigacao com relogio LAI/LGPD/13.460
;; — e' um comentario de cidadao numa materia). Autor SEMPRE obrigatorio (SEM variante anonima — diferente
;; da ouvidoria). Dois niveis de vocabulario distintos: `estados-comentario` (o ciclo COMPLETO da linha,
;; incl. o estado inicial 'pendente') vs `acoes-moderacao` (o que o SERVIDOR pode DECIDIR — so' os 2
;; desfechos; 'pendente' nunca e' uma ACAO, so' um ESTADO de chegada).

;; ---- ciclo do comentario (comentario.estado) — enum FIXO em codigo (o CHECK da mig 0043 espelha) ----
(def estados-comentario
  "Ciclo do comentario. `aprovado`/`rejeitado` sao terminais (trava a linha — trg da mig 0043)."
  #{"pendente" "aprovado" "rejeitado"})

(def ^:private estados-terminais-comentario
  "Desfechos do comentario: a moderacao ja decidiu (a linha congela)."
  #{"aprovado" "rejeitado"})

(def ^:private transicoes-comentario
  "Grafo de transicoes LEGAIS do comentario (de -> conjunto de proximos). Terminais nao tem saida."
  {"pendente"  #{"aprovado" "rejeitado"}
   "aprovado"  #{}
   "rejeitado" #{}})

;; ---- acoes que a MODERACAO pode tomar — subconjunto de estados-comentario (SEM 'pendente': a moderacao
;; so' DECIDE um desfecho, nunca devolve a 'pendente') ----
(def acoes-moderacao
  "As 2 acoes que o SERVIDOR pode registrar ao moderar (moderacao_comentario.acao, o CHECK da mig 0043
  espelha). Distinto de `estados-comentario`: nao inclui 'pendente' (nao e' uma decisao, e' o ponto de
  partida)."
  #{"aprovado" "rejeitado"})

;; ---- os 5 MOTIVOS de rejeicao — enum FIXO em codigo. TAMBEM checado por CHECK IN no banco (mig 0043,
;; `comentario_motivo_rejeicao_valido` + `moderacao_comentario_motivo_valido` — defesa-em-profundidade,
;; espelha `estados-comentario`/`acoes-moderacao` acima). Estender este set exige migration (o CHECK IN
;; fecha o vocabulario no schema, nao so' em codigo). A validacao do vocabulario fixo mora AQUI + no wire/in
;; + no CHECK. ----
(def motivos-rejeicao-comentario
  "Os 5 motivos padrao de rejeicao de um comentario (moderacao)."
  #{"ofensivo" "spam" "fora-do-tema" "conteudo-ilegal" "dados-pessoais"})

(defn validar-estado-comentario
  "Lanca se `v` nao e' estado do comentario (pendente|aprovado|rejeitado)."
  [v] (validar! estados-comentario "estado de comentario" v))

(defn validar-acao-moderacao
  "Lanca se `v` nao e' uma acao de moderacao valida (aprovado|rejeitado — NUNCA pendente)."
  [v] (validar! acoes-moderacao "acao de moderacao de comentario" v))

(defn validar-motivo-rejeicao-comentario
  "Lanca se `v` nao e' um dos 5 motivos FIXOS de rejeicao."
  [v] (validar! motivos-rejeicao-comentario "motivo de rejeicao de comentario" v))

(defn terminal-comentario?
  "O estado do comentario e' terminal (a moderacao ja decidiu)?"
  [estado] (contains? estados-terminais-comentario estado))

(defn transicao-comentario-valida?
  "A transicao `de`->`para` do ciclo do comentario e' legal? (pura — so o grafo fixo). Terminais nao transicionam."
  [de para]
  (contains? (get transicoes-comentario de) para))

(defn protocolo-ouvidoria
  "Numero de PROTOCOLO humano da manifestacao a partir do (ano, sequencial gapless). Formato
  'OUV-<ano>-<seq 6 digitos>' (ex.: OUV-2026-000001) — namespace distinto de ESIC-/REC-/LGPD- p/ nao colidir."
  [ano sequencial]
  (format "OUV-%d-%06d" (long ano) (long sequencial)))

(defn validar-tipo-manifestacao
  "Lanca se `v` nao e' um dos 5 tipos de manifestacao (reclamacao|denuncia|sugestao|elogio|solicitacao)."
  [v] (validar! tipos-manifestacao "tipo de manifestacao de ouvidoria" v))

(defn validar-estado-manifestacao
  "Lanca se `v` nao e' estado da manifestacao (protocolada|em_analise|respondida|arquivada)."
  [v] (validar! estados-manifestacao "estado de manifestacao de ouvidoria" v))

(defn terminal-manifestacao?
  "O estado da manifestacao e' terminal (ja respondida OU arquivada)?"
  [estado] (contains? estados-terminais-manifestacao estado))

(defn transicao-manifestacao-valida?
  "A transicao `de`->`para` do ciclo da manifestacao e' legal? (pura — so o grafo fixo). Terminais nao transicionam."
  [de para]
  (contains? (get transicoes-manifestacao de) para))
