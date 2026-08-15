(ns oplenario.sessoes.components.serializador-folha
  "Port de SAIDA SerializadorFolha (Etapa 5 fatia 2): leva o DOCUMENTO INTERMEDIARIO (`FolhaDocumento`,
  produzido pelo renderizador puro `sessoes.gerador-folha/renderizar`, Fatia 1) ao HTML CANONICO da folha de
  presenca. Irmao de forma de `legislativo.components.serializador-publicacao`: o renderizador e'
  formato-agnostico, o ADAPTER conhece o formato. Diferente do irmao (que tem um fixture ilustrativo), este
  adapter e' o FORMATO REAL — o HTML canonico e' o que a tela mostra (Fatia 6) e o que o renderizador de PDF
  consome (Fatia 3), sem reformatacao.

  RESTRICOES DURAS (medidas contra openhtmltopdf, nao preferencia — ver `.superpowers/sdd/etapa5-folha-brief.md`
  e `resources/folha/README.md`): CSS 2.1 estrito, autocontido (o <style> INLINA `resources/folha/folha.css`
  literal — zero <link>, zero <script>, zero requisicao externa), hex literais, layout so' `block`/`table`.

  DETERMINISMO: `serializar` e' funcao pura de `documento` (mesma entrada -> mesmos bytes). Dois cuidados
  proprios deste ns, que ja custaram bug em outra parte do repo: (a) `:serie` e' `map-of` — iterar o HASH-MAP
  diretamente vazaria ordem de hash no HTML; a ordem de exibicao segue SEMPRE a ordem de `:linhas` (a mesma
  'ordem cadastrada' que a relacao nominal usa), nunca `(keys serie)`. (b) toda formatacao de data/hora usa o
  fuso CIVIL do sistema (`kernel.tempo/zona-civil-padrao`, America/Fortaleza) explicito — nunca o default da
  JVM (a data ja recuou um dia em Fortaleza uma vez nesta base por essa omissao).

  D1 por HERANCA: este ns nao importa `sessoes.logic` e nao soma/subtrai nada de `:quorum` — os cinco campos
  saem exatamente como chegam em `documento`, um por linha do demonstrativo (bloco 4). Se algum numero de
  quorum nao aparecer LITERAL num `td`, e' bug deste ns, nao do dado.

  ESCAPE DE HTML OBRIGATORIO: todo texto de origem humana (nome, nome-parlamentar, partido, cargo-mesa,
  motivo-nao-realizada, motivo de justificativa, nome-oficial/nome-curto da Casa) passa por `esc` antes de
  entrar no documento. Vocabulario controlado (estados, tipos, fontes, modalidades — vem de `enum-de` sobre
  os sets de `logic.clj`, nunca digitado por humano) nao precisa de escape, mas passa por ele mesmo assim
  onde e' barato, por defesa em profundidade.

  VERSAO DE CONGELAMENTO — CARRY PARA A FATIA 4: `FolhaDocumento` (Fatia 1) nao carrega `:versao` — quem
  atribui o numero e' o INSERT `MAX(versao)+1` da Fatia 4, DEPOIS deste HTML existir (e' o proprio numero que
  o congelamento carimba). Este adapter, portanto, NAO IMPRIME nenhum numero de versao — imprimir um valor
  fabricado aqui seria mentir sobre o proprio contrato. O bloco de identificacao (2) diz o que E' verdade
  nesta fatia (spec-versao, sessao, instante de apuracao) e que a versao e' atribuida no congelamento; ambos
  os consultores de design pediram 'a versao no cabecalho' — a divergencia e o motivo estao no relatorio da
  fatia."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [oplenario.kernel.tempo :as tempo])
  (:import (java.time.format DateTimeFormatter)))

(set! *warn-on-reflection* true)

;; ---------- escape de HTML (dado livre digitado por humano) ----------

(defn- esc
  "Escapa `&<>\"'` — nesta ordem (& primeiro, ou a propria escapa de `<` produziria um `&` novo que seria
  re-escapado). `nil` vira string vazia: uma celula sem dado e' vazia, nao a string 'nil'."
  [s]
  (if (nil? s)
    ""
    (-> (str s)
        (str/replace "&" "&amp;")
        (str/replace "<" "&lt;")
        (str/replace ">" "&gt;")
        (str/replace "\"" "&quot;")
        (str/replace "'" "&#39;"))))

(defn- nao-branco
  "`s` se nao-nil e nao-so'-espaco; senao `nil`. Distingue 'campo ausente' de 'campo com string vazia'."
  [s]
  (when (and s (not (str/blank? s)))
    s))

;; ---------- data/hora — SEMPRE no fuso civil do sistema, nunca o default da JVM ----------

(def ^:private formatador-data-hora
  (-> (DateTimeFormatter/ofPattern "dd/MM/yyyy 'às' HH:mm")
      (.withZone tempo/zona-civil-padrao)))

(def ^:private formatador-hora
  (-> (DateTimeFormatter/ofPattern "HH:mm")
      (.withZone tempo/zona-civil-padrao)))

(defn- fmt-data-hora [instante]
  (when instante (.format ^DateTimeFormatter formatador-data-hora instante)))

(defn- fmt-hora [instante]
  (if instante (.format ^DateTimeFormatter formatador-hora instante) "—"))

;; ---------- vocabulario controlado -> rotulo por extenso (nunca cor como sinal unico) ----------

(def ^:private rotulo-estado-sessao
  {"encerrada" "Sessão encerrada" "nao_realizada" "Sessão não realizada" "arquivada" "Sessão arquivada"})

(def ^:private rotulo-estado-chamada
  {:presente-plenario "Presente no plenário"
   :presente-remoto "Presente em remoto"
   :ausente "Ausente"
   :ausente-justificado "Ausente — falta justificada"
   :ausente-justificativa-pendente "Ausente — justificativa pendente"
   :licenciado "Licenciado"})

(def ^:private rotulo-estado-justificativa
  {"pendente" "Pendente de decisão da Mesa" "aprovada" "Deferida" "indeferida" "Indeferida"})

(def ^:private rotulo-tipo-evento
  {"entrada" "Entrada" "saida" "Saída" "retorno" "Retorno" "mudanca_modalidade" "Mudança de modalidade"})

(def ^:private rotulo-modalidade
  {"plenario" "Plenário" "remoto" "Remoto"})

(def ^:private rotulo-fonte
  {"painel_eletronico" "Painel eletrônico" "manual_secretaria" "Secretaria (manual)"
   "autoatendimento" "Autoatendimento" "inferida_por_voto" "Inferida por voto"
   "inferida_por_tribuna" "Inferida por tribuna"})

;; ---------- nome de exibicao de um vereador (RAW — quem chama aplica `esc`) ----------

(defn- nome-exibicao [linha]
  (or (nao-branco (:nome-parlamentar linha)) (nao-branco (:nome linha)) "[nome não cadastrado]"))

(defn- nome-civil-secundario
  "A segunda linha (nome civil) da celula do vereador — so' quando existe E difere do nome de exibicao,
  senao duplicaria o mesmo texto duas vezes."
  [linha]
  (let [np (nao-branco (:nome-parlamentar linha)) n (nao-branco (:nome linha))]
    (when (and np n (not= np n)) n)))

(defn- mapa-nomes
  "vereador-id -> nome de exibicao RAW, para resolver `:vereador-id` em `:serie`/`:justificativas`/atos —
  cobre TODAS as linhas (nominal + licenciados + sem-assento), nao so' a relacao principal."
  [linhas]
  (into {} (map (juxt :vereador-id nome-exibicao)) linhas))

;; ---------- particao das linhas (D1-adjacente: e' particao de exibicao, nao aritmetica de quorum) ----------

(defn- particionar-linhas
  "sem-assento tem PRECEDENCIA sobre licenciado: uma linha nunca aparece em duas secoes. `:nominal` e' o
  resto — a mesma regra que `models/folha.clj` documenta para o bloco 1 ('sem-assento=false e estado !=
  licenciado')."
  [linhas]
  (reduce (fn [acc linha]
            (cond
              (:sem-assento linha)          (update acc :sem-assento conj linha)
              (= :licenciado (:estado linha)) (update acc :licenciados conj linha)
              :else                          (update acc :nominal conj linha)))
          {:nominal [] :licenciados [] :sem-assento []}
          linhas))

;; ---------- linha da tabela de relacao (reusada por nominal / licenciados / sem-assento) ----------

(defn- celula-hora [{:keys [licenciado? desde registrado-em]}]
  (cond
    licenciado? "— não se marca"
    desde (str (esc (fmt-hora desde))
               "<span class=\"rotulo-registro\">registro " (esc (fmt-hora registrado-em)) "</span>")
    :else "—"))

(defn- linha-relacao-html
  [{:keys [numero linha licenciado?]}]
  (let [nome (esc (nome-exibicao linha))
        civil (nome-civil-secundario linha)
        inconsistente? (:inconsistencia-cadastro linha)
        risco? (:sem-assento linha)
        partido (esc (or (nao-branco (:partido linha)) "—"))
        mesa (esc (or (nao-branco (:cargo-mesa linha)) "—"))
        situacao (esc (get rotulo-estado-chamada (:estado linha) (str (:estado linha))))]
    (str "<tr class=\"linha-v\">"
         "<td class=\"conf\"></td>"
         "<td class=\"num\">" (if numero (format "%02d" numero) "") "</td>"
         "<td>"
         "<span class=\"nome-vereador\">" nome "</span>"
         (when civil (str "<span class=\"nome-civil\">" (esc civil) "</span>"))
         (when inconsistente? "<span class=\"nome-civil\">Cadastro incompleto</span>")
         (when risco? "<div class=\"rotulo-risco\">Sem assento na data</div>")
         "</td>"
         "<td class=\"partido\">" partido "</td>"
         "<td class=\"mesa\">" mesa "</td>"
         "<td class=\"situacao\">" situacao "</td>"
         "<td class=\"hora\">" (celula-hora {:licenciado? licenciado?
                                              :desde (:desde linha)
                                              :registrado-em (:registrado-em linha)})
         "</td>"
         "</tr>")))

(defn- cabecalho-tabela-linhas [titulo contagem]
  (str "<tr><th colspan=\"7\" class=\"folha-secao-titulo\" style=\"margin:0;border-bottom:0.8pt solid #1B2A20;padding-bottom:2mm;\">"
       (esc titulo) " (" contagem ")</th></tr>"
       "<tr>"
       "<th class=\"conf\">Conf.</th>"
       "<th class=\"num\">Nº<sup class=\"chamada-nota\">1</sup></th>"
       "<th>Vereador</th>"
       "<th>Partido</th>"
       "<th>Mesa</th>"
       "<th>Situação apurada<sup class=\"chamada-nota\">3</sup></th>"
       "<th>Hora do fato / registro<sup class=\"chamada-nota\">4</sup></th>"
       "</tr>"))

(defn- tabela-relacao-nominal [nominal]
  (str "<div class=\"folha-secao-titulo\">4 · RELAÇÃO NOMINAL</div>"
       "<table class=\"tabela-linhas\">"
       "<colgroup><col class=\"conf\"/><col class=\"ordem\"/><col class=\"vereador\"/>"
       "<col class=\"partido\"/><col class=\"mesa\"/><col class=\"situacao\"/><col class=\"hora\"/></colgroup>"
       "<thead>" (cabecalho-tabela-linhas "Relação nominal" (count nominal)) "</thead>"
       "<tbody>"
       (if (seq nominal)
         (apply str (map-indexed (fn [i linha] (linha-relacao-html {:numero (inc i) :linha linha})) nominal))
         "<tr><td colspan=\"7\" class=\"folha-secao-texto\" style=\"padding:3mm 1.5mm;\">Não há nenhum registro de presença nesta sessão. A relação traz a composição da Casa na data.</td></tr>")
       "</tbody></table>"))

(defn- tabela-licenciados [licenciados]
  (when (seq licenciados)
    (str "<div class=\"folha-secao-titulo\">5 · FORA DO DENOMINADOR — LICENCIADOS<sup class=\"chamada-nota\">2</sup></div>"
         "<div class=\"folha-secao-texto\">O vereador licenciado consta desta folha, mas fica fora do total de membros usado na apuração — durante a licença, quem compõe a Casa é o suplente, com mandato próprio.</div>"
         "<table class=\"tabela-linhas\">"
         "<colgroup><col class=\"conf\"/><col class=\"ordem\"/><col class=\"vereador\"/>"
         "<col class=\"partido\"/><col class=\"mesa\"/><col class=\"situacao\"/><col class=\"hora\"/></colgroup>"
         "<thead>" (cabecalho-tabela-linhas "Licenciados" (count licenciados)) "</thead>"
         "<tbody>"
         (apply str (map (fn [linha] (linha-relacao-html {:linha linha :licenciado? true})) licenciados))
         "</tbody></table>")))

(defn- tabela-sem-assento [sem-assento]
  (when (seq sem-assento)
    (str "<div class=\"folha-secao-titulo\">6 · FORA DA COMPOSIÇÃO — PRESENÇAS SEM ASSENTO<sup class=\"chamada-nota\">6</sup></div>"
         "<div class=\"folha-secao-texto\">Há registro de presença para quem não consta como vereador com mandato vigente nesta data. Verifique o cadastro da legislatura antes de dar fé aos totais.</div>"
         "<table class=\"tabela-linhas\">"
         "<colgroup><col class=\"conf\"/><col class=\"ordem\"/><col class=\"vereador\"/>"
         "<col class=\"partido\"/><col class=\"mesa\"/><col class=\"situacao\"/><col class=\"hora\"/></colgroup>"
         "<thead>" (cabecalho-tabela-linhas "Presenças sem assento" (count sem-assento)) "</thead>"
         "<tbody>"
         (apply str (map (fn [linha] (linha-relacao-html {:linha linha})) sem-assento))
         "</tbody></table>")))

;; ---------- 1 · cabecalho da Casa ----------

(defn- cabecalho-casa-html [{:keys [nome-oficial nome-curto legislatura-numero legislatura-ano-inicio legislatura-ano-fim]}]
  (let [nome (or (nao-branco nome-oficial) (nao-branco nome-curto) "Câmara Municipal — nome oficial não cadastrado")
        legislatura (if (and legislatura-numero legislatura-ano-inicio legislatura-ano-fim)
                      (str "Legislatura " legislatura-numero "ª · " legislatura-ano-inicio "–" legislatura-ano-fim)
                      "Legislatura não registrada")]
    (str "<div class=\"folha-cabecalho-casa\">"
         ;; caixa-alta e' TRANSFORMACAO VISUAL da CSS (.nome-casa{text-transform:uppercase}), nao do
         ;; texto: forcar str/upper-case aqui mudaria o CONTEUDO extraivel do documento (round-trip de
         ;; texto do PDF na Fatia 3) e o fallback ('nome oficial nao cadastrado') e' frase, nao rotulo.
         "<div class=\"nome-casa\">" (esc nome) "</div>"
         "<div class=\"legislatura\">" (esc legislatura) "</div>"
         "</div>")))

;; ---------- 2 · identificacao do documento ----------

(defn- identificacao-html [{:keys [spec-versao sessao instante]}]
  (let [id-curto (subs (str (:id sessao)) 0 8)]
    (str "<div class=\"folha-titulo\">Folha de presença</div>"
         "<table class=\"folha-carimbo-tabela\"><tr>"
         "<td></td>"
         "<td class=\"celula-restrito\"><span class=\"carimbo-restrito\">Uso restrito · contém dado pessoal</span></td>"
         "</tr></table>"
         "<div class=\"folha-identificacao mono\">"
         "Sessão " id-curto " · spec " (esc spec-versao) " · presença apurada em " (esc (fmt-data-hora instante))
         " · versão numerada atribuída no ato de congelamento desta folha"
         "</div>")))

;; ---------- 3 · a sessao ----------

(defn- sessao-html [{:keys [sessao instante]}]
  (let [{:keys [estado motivo-nao-realizada]} sessao
        rotulo (get rotulo-estado-sessao estado estado)]
    (str "<div class=\"folha-secao-titulo\">1 · A SESSÃO</div>"
         "<table class=\"tabela-sessao\"><colgroup><col class=\"rotulo\"/><col/></colgroup>"
         "<tr><td class=\"rotulo\">Situação da sessão</td><td>" (esc rotulo) "</td></tr>"
         "<tr><td class=\"rotulo\">Presença apurada em</td><td>" (esc (fmt-data-hora instante)) "</td></tr>"
         "</table>"
         (when (= "nao_realizada" estado)
           (str "<div class=\"faixa-nao-realizada\">"
                "<div class=\"rotulo-motivo\">Sessão não realizada. Motivo registrado no encerramento:</div>"
                (esc motivo-nao-realizada)
                "</div>")))))

;; ---------- 4 · apuracao do quorum (D1 — verbatim, cada campo sua propria linha) ----------

(defn- quorum-html [quorum]
  (let [linha (fn [rotulo valor ultima?]
                (str "<tr" (when ultima? " class=\"ultima\"") "><td>" rotulo "</td>"
                     "<td class=\"valor-quorum\">" valor "</td></tr>"))]
    (str "<div class=\"folha-secao-titulo\">2 · APURAÇÃO DO QUÓRUM</div>"
         "<table class=\"tabela-quorum\"><colgroup><col class=\"rotulo\"/><col class=\"valor\"/></colgroup>"
         (linha "Presentes no plenário" (:presentes-plenario quorum) false)
         (linha "Presentes em remoto" (:presentes-remoto quorum) false)
         (linha "Total de presentes" (:presentes-total quorum) false)
         (linha "Membros da Casa nesta apuração" (:membros-da-casa quorum) false)
         (linha "Presenças fora do roster (sem assento na data)<sup class=\"chamada-nota\">6</sup>"
                (:presencas-fora-do-roster quorum) true)
         "</table>"
         "<div class=\"nota-quorum\">Números apurados pelo sistema no instante indicado acima. Esta folha não recalcula nenhum deles.</div>"
         "<div class=\"nota-quorum\">Esta folha não informa quórum de instalação nem quórum de deliberação<sup class=\"chamada-nota\">5</sup>.</div>")))

;; ---------- 5 · atos de chamada conduzida ----------

(defn- atos-html [atos]
  (str "<div class=\"folha-secao-titulo\">3 · ATOS DE CHAMADA CONDUZIDA</div>"
       (if (seq atos)
         (str "<table class=\"tabela-atos\">"
              "<colgroup><col class=\"hora-fato\"/><col class=\"hora-registro\"/><col class=\"conduzida\"/><col class=\"membros\"/></colgroup>"
              "<tr><th>Hora do fato<sup class=\"chamada-nota\">4</sup></th>"
              "<th>Hora do registro<sup class=\"chamada-nota\">4</sup></th>"
              "<th>Conduzida por (id)</th><th>Membros da Casa no ato</th></tr>"
              (apply str
                     (map (fn [ato]
                            (str "<tr><td>" (esc (fmt-hora (:ocorrido-em ato))) "</td>"
                                 "<td>" (esc (fmt-hora (:registrado-em ato))) "</td>"
                                 "<td>" (esc (subs (str (:conduzida-por ato)) 0 8)) "</td>"
                                 "<td>" (:membros-da-casa ato) "</td></tr>"))
                          atos))
              "</table>")
         "<div class=\"folha-secao-texto\">Nenhum ato de chamada conduzida registrado nesta sessão.</div>")
       "<div class=\"folha-secao-texto\">Cada chamada conduzida é um ato datado e imutável; uma chamada não se apaga — corrige-se com outra chamada, por isso pode haver mais de um ato nesta seção. \"Conduzida por\" identifica o operador por id técnico — a resolução do nome não faz parte desta fatia.</div>"))

;; ---------- 9 · movimentacoes durante a sessao (so' quem tem mais de 1 evento) ----------

(defn- eventos-html [nome eventos]
  (str "<tr class=\"grupo-vereador\"><td colspan=\"5\">" (esc nome) "</td></tr>"
       (apply str
              (map (fn [ev]
                     (str "<tr class=\"evento-serie\">"
                          "<td>" (esc nome) "</td>"
                          "<td>" (esc (get rotulo-tipo-evento (:tipo ev) (:tipo ev))) "</td>"
                          "<td>" (esc (get rotulo-modalidade (:modalidade ev) (:modalidade ev))) "</td>"
                          "<td>" (esc (get rotulo-fonte (:fonte ev) (:fonte ev))) "</td>"
                          "<td class=\"hora\">" (esc (fmt-hora (:ocorrido-em ev))) "</td>"
                          "</tr>"))
                   eventos))))

(defn- movimentacoes-html [linhas serie]
  ;; A ORDEM segue `linhas` (a mesma ordem cadastrada da relacao nominal) e NUNCA `(keys serie)`: um
  ;; map-of do Clojure nao garante ordem de iteracao, e vazar essa ordem no HTML quebraria o determinismo
  ;; "mesma entrada -> mesmos bytes".
  (let [nomes (mapa-nomes linhas)
        grupos (->> linhas
                    (keep (fn [linha]
                            (let [eventos (get serie (:vereador-id linha))]
                              (when (> (count eventos) 1)
                                [(:vereador-id linha) (get nomes (:vereador-id linha)) eventos])))))]
    (when (seq grupos)
      (str "<div class=\"folha-secao-titulo\">7 · MOVIMENTAÇÕES DURANTE A SESSÃO</div>"
           "<div class=\"folha-secao-texto\">Constam aqui apenas os vereadores com mais de um registro nesta sessão. Para os demais, o único registro é o da relação nominal.</div>"
           "<table class=\"tabela-serie\">"
           "<colgroup><col class=\"vereador\"/><col class=\"movimento\"/><col class=\"modalidade\"/><col class=\"fonte\"/><col class=\"hora\"/></colgroup>"
           "<tr><th>Vereador</th><th>Movimento</th><th>Modalidade</th><th>Origem do registro</th><th>Hora do fato<sup class=\"chamada-nota\">4</sup></th></tr>"
           (apply str (map (fn [[_vid nome eventos]] (eventos-html nome eventos)) grupos))
           "</table>"))))

;; ---------- 10 · justificativas de ausencia ----------

(defn- justificativas-html [linhas justificativas]
  (let [nomes (mapa-nomes linhas)]
    (str "<div class=\"folha-secao-titulo\">8 · JUSTIFICATIVAS DE AUSÊNCIA</div>"
         (if (seq justificativas)
           (str "<table class=\"tabela-justificativas\">"
                "<colgroup><col class=\"vereador\"/><col class=\"decisao\"/><col class=\"motivo\"/><col class=\"decidido\"/></colgroup>"
                "<tr><th>Vereador</th><th>Decisão</th><th>Motivo declarado</th><th>Decidida em</th></tr>"
                (apply str
                       (map (fn [j]
                              (str "<tr class=\"linha-justificativa\">"
                                   "<td>" (esc (get nomes (:vereador-id j) "[vereador não identificado]")) "</td>"
                                   "<td class=\"estado\">" (esc (get rotulo-estado-justificativa (:estado j) (:estado j))) "</td>"
                                   "<td class=\"motivo\">«" (esc (:motivo j)) "»</td>"
                                   "<td class=\"decidido\">" (esc (fmt-hora (:decidido-em j))) "</td>"
                                   "</tr>"))
                            justificativas))
                "</table>")
           "<div class=\"folha-secao-texto\">Nenhuma justificativa registrada nesta sessão.</div>")
         "<div class=\"folha-secao-texto\">Quem lança a justificativa é a Secretaria; quem decide é a Mesa. Justificativa pendente não muda a contagem: o vereador segue fora dos presentes até a Mesa decidir<sup class=\"chamada-nota\">9</sup>.</div>")))

;; ---------- 11 · conferencia (assinaturas fisicas) ----------

(def ^:private conferencia-html
  (str "<div class=\"folha-secao-titulo\">9 · CONFERÊNCIA</div>"
       "<div class=\"folha-secao-texto\">As assinaturas abaixo são físicas. O sistema não as coleta, não as valida e não as guarda<sup class=\"chamada-nota\">7</sup>.</div>"
       "<table class=\"tabela-conferencia\"><tr>"
       "<td><div class=\"linha-assinatura\"></div><div class=\"rotulo-assinatura\">Secretaria da Casa — nome e matrícula</div></td>"
       "<td><div class=\"linha-assinatura\"></div><div class=\"rotulo-assinatura\">Presidência da Mesa</div></td>"
       "</tr></table>"))

;; ---------- 12 · registro de congelamento ----------

(defn- congelamento-html [{:keys [spec-versao sessao]}]
  (str "<div class=\"folha-congelamento\">"
       "<div class=\"folha-secao-titulo\" style=\"margin-top:0;\">10 · REGISTRO DE CONGELAMENTO</div>"
       "<div class=\"campo\"><span class=\"rotulo\">Especificação: </span><span class=\"valor\">" (esc spec-versao) "</span></div>"
       "<div class=\"campo\"><span class=\"rotulo\">Sessão: </span><span class=\"valor\">" (esc (subs (str (:id sessao)) 0 8)) "</span></div>"
       "<div class=\"campo\"><span class=\"rotulo\">Versão: </span><span class=\"valor\">atribuída no ato de congelamento — não existe nesta pré-visualização</span></div>"
       "<div class=\"folha-secao-texto\" style=\"margin-top:2mm;\">Os hashes SHA-256 desta folha — o do arquivo HTML e o do PDF — ficam no registro de congelamento da Casa, sob a versão acima. Um arquivo cujo hash não bater com esse registro não é esta folha<sup class=\"chamada-nota\">7</sup>.</div>"
       "</div>"))

;; ---------- 13 · notas desta folha ----------

(def ^:private notas-html
  (str
   "<div class=\"folha-secao-titulo\">NOTAS DESTA FOLHA</div>"
   "<div class=\"folha-notas\">"
   "<div class=\"nota\"><span class=\"num-nota\">1</span> Ordem. Os nomes aparecem na ordem cadastrada na Casa. Esta não é, necessariamente, a ordem oficial de chamada — a ordem varia por regimento. A posição de um nome nesta folha não produz efeito regimental.</div>"
   "<div class=\"nota\"><span class=\"num-nota\">2</span> Licença. O vereador licenciado consta desta folha, mas fica fora do total de membros usado na apuração — durante a licença, quem compõe a Casa é o suplente, com mandato próprio. Enquanto o suplente não entrar no cadastro da legislatura, a Casa é apurada com um membro a menos.</div>"
   "<div class=\"nota\"><span class=\"num-nota\">3</span> Situação apurada. A situação de cada nome é a do último registro dentro da janela de apuração desta sessão, não o resumo da sessão inteira. Quem entrou, saiu e voltou consta como presente, e tem a série completa na seção de movimentações.</div>"
   "<div class=\"nota\"><span class=\"num-nota\">4</span> Os dois instantes. Cada marcação traz a hora do fato (quando aconteceu) e a hora do registro (quando entrou no sistema). Quando as duas diferem, houve registro posterior ao fato. Nenhuma das duas se edita depois de gravada.</div>"
   "<div class=\"nota\"><span class=\"num-nota\">5</span> Quórum regimental. Esta folha não informa quórum de instalação nem quórum de deliberação. A regra que separa os dois está em homologação e não deve sair da Casa antes de confirmada no regimento. Aqui está a presença apurada; o julgamento sobre ela é da Mesa.</div>"
   "<div class=\"nota\"><span class=\"num-nota\">6</span> Presença sem assento. Há registro de presença para quem não consta como vereador com mandato vigente na data. Esse registro soma no total de presentes e não soma no total de membros — por isso os dois totais não fecham entre si. A folha mostra o caso em vez de escondê-lo.</div>"
   "<div class=\"nota nota-stub\"><span class=\"num-nota\">7</span> Integridade e assinatura. Esta folha é congelada: a versão indicada no cabeçalho é imutável e tem hash SHA-256 registrado no ato de congelamento. Esta folha NÃO está assinada digitalmente. A marca STUB-ICP-v0 que o sistema aplica não é assinatura com fé pública: não é certificado ICP-Brasil, não identifica signatário perante terceiros e não substitui a assinatura de quem responde pelo documento. O que esta folha entrega é rastreabilidade e imutabilidade por hash — nada além disso.</div>"
   "<div class=\"nota\"><span class=\"num-nota\">8</span> O que aqui é escolha nossa. Não existe modelo de folha de presença padronizado e aceito de forma uniforme pelas câmaras municipais. São escolhas deste sistema, não exigência legal: o formato desta folha, a divisão em seções, a numeração de ordem, a coluna de conferência manual, o campo de assinatura ao pé, e a decisão de mostrar os dois instantes de cada marcação. Se o jurídico da Casa exigir outro formato, ele prevalece.</div>"
   "<div class=\"nota\"><span class=\"num-nota\">9</span> Dado pessoal. Esta folha é nominal e reproduz o motivo declarado nas justificativas de ausência, que pode conter dado de saúde. Trate-a como documento de uso restrito.</div>"
   "</div>"))

;; ---------- 14 · linha de emissao ----------

(def ^:private emissao-html
  "<div class=\"folha-emissao\">Emitida por O Plenário — sistema de gestão legislativa da Casa.</div>")

;; ---------- composicao final ----------

(defn- css-inline []
  (slurp (io/resource "folha/folha.css")))

(defn- corpo-html [{:keys [cabecalho-da-casa linhas quorum serie justificativas atos-de-chamada-conduzida] :as documento}]
  (let [{:keys [nominal licenciados sem-assento]} (particionar-linhas linhas)]
    (str (cabecalho-casa-html cabecalho-da-casa)
         (identificacao-html documento)
         (sessao-html documento)
         (quorum-html quorum)
         (atos-html atos-de-chamada-conduzida)
         (tabela-relacao-nominal nominal)
         (tabela-licenciados licenciados)
         (tabela-sem-assento sem-assento)
         (movimentacoes-html linhas serie)
         (justificativas-html linhas justificativas)
         conferencia-html
         (congelamento-html documento)
         notas-html
         emissao-html)))

(defn- documento-html [documento]
  (str "<!doctype html>"
       "<html lang=\"pt-BR\">"
       "<head>"
       "<meta charset=\"utf-8\"/>"
       "<title>Folha de presença</title>"
       "<style>" (css-inline) "</style>"
       "</head>"
       "<body>" (corpo-html documento) "</body>"
       "</html>"))

;; ---------- o port ----------

(defprotocol SerializadorFolha
  (serializar [this documento]
    "Renderiza o `FolhaDocumento` (Malli, Fatia 1) no HTML canonico da folha de presenca. Devolve
     {:bytes <byte-array> :content-type \"text/html; charset=utf-8\"}. DETERMINISTICO: mesma entrada
     produz bytes identicos — pre-requisito do hash de integridade que a Fatia 4 vai congelar."))

(defrecord SerializadorFolhaHtml []
  SerializadorFolha
  (serializar [_ documento]
    (let [html (documento-html documento)]
      {:bytes (.getBytes ^String html "UTF-8")
       :content-type "text/html; charset=utf-8"})))

(defn serializador-folha-html
  "Cria o adapter SerializadorFolha HTML (sem estado — o host o constroi e injeta, como
   `serializador-fixture`/`serializador-remessa`)."
  []
  (->SerializadorFolhaHtml))
