(ns oplenario.legislativo.logic
  "PURO: regras, maquina de estados de tramitacao, invariantes do legislativo. Sem I/O.
  Gate eixo H (ADR-0002): a coordenada canonica de identidade legal — numeracao de exibicao,
  URN/LexML — e' computada AQUI (puro/testavel); a numeracao crua (sequencial gapless) e a
  imutabilidade pos-publicacao moram no banco (kernel/sequencial + trigger). O mapa tipo->lexml e
  tipo->sigla sao DADO (vocabulario LexML uniforme do Brasil, nao regra por tribunal — Inv.4)."
  (:require [clojure.string :as str])
  (:import (java.text Normalizer Normalizer$Form)))

(set! *warn-on-reflection* true)

;; --- espécies de proposicao (eixo A + §16.13). Vocabulario fechado da V1; cresce por adicao. ---
(def tipos
  #{"projeto_lei" "projeto_lei_complementar" "projeto_resolucao"
    "projeto_decreto_legislativo" "proposta_emenda_lom"
    "indicacao" "requerimento" "mocao"})

;; --- eixo B: versionamento de texto. Vocabularios do §22.4 (espelham os CHECK da migration 0015). ---
(def origens-versao
  #{"protocolo" "substitutivo" "aplicacao_emenda" "redacao_final" "promulgacao" "importacao_legado"})
(def estados-versao #{"rascunho" "vigente" "superada" "arquivada"})

;; --- eixo D: emendas. Vocabularios do §22.4 (espelham os CHECK da migration 0017). ---
(def tipos-emenda
  #{"modificativa" "supressiva" "aditiva" "substitutiva_total" "substitutiva_parcial" "aglutinativa" "redacao"})
(def momentos-apresentacao #{"no_prazo" "plenario" "redacao_final"})

(def estados-emenda
  "Ciclo de vida da emenda — ENUM SIMPLES na propria tabela (§22.4 eixo D): NAO usa o motor de templates
  porque o ciclo e' universal entre camaras (ao contrario da tramitacao da proposicao, eixo C). Cresce por
  adicao. V1: apresentada -> admitida -> {aprovada|rejeitada|prejudicada|retirada}."
  #{"apresentada" "admitida" "aprovada" "rejeitada" "prejudicada" "retirada"})

(def estados-emenda-terminais
  "Estados terminais da emenda (imutabilidade nivel b): uma vez terminal, a linha so muda sob correcao
  auditada (trigger compartilhado shared.imut_trava_estado_terminal). Espelha os args do trigger (mig 0017)."
  #{"aprovada" "rejeitada" "prejudicada" "retirada"})

;; --- eixo F: parecer. O objeto polimorfico (objeto_tipo) sobre o qual o parecer opina (§22.4 disc.2).
;; Espelha o CHECK da migration 0019. Cresce por adicao (ex.: parecer sobre substitutivo, no futuro). ---
(def objetos-parecer #{"proposicao" "emenda"})

(def estados-parecer-terminais
  "Os 4 desfechos terminais do parecer (§22.4 eixo F) — PISO FIXO da imutabilidade nível (b). Fonte única:
  espelha os args do trigger `trg_pareceres_imut_estado` (mig 0019). Embora o `estado` seja template-driven,
  estes 4 são vocabulário cravado do eixo F (os 4 eventos de desfecho). Usado p/ guard de domínio (ex.:
  `promover!` recusa promover texto de parecer já terminal — erro inspecionável antes de bater no trigger)."
  #{"aprovado" "rejeitado" "prejudicado" "prazo_vencido"})

;; --- eixo F (F3.6b): proveniencia da versao de texto do PARECER. Espelha o CHECK da migration 0020
;; (mesma estrategia do eixo B, vocabulario proprio do parecer). estado_versao reusa `estados-versao`. ---
(def origens-parecer-versao #{"redacao" "substitutivo" "importacao_legado"})

;; --- eixo G: votacao. Vocabularios (espelham os CHECK da migration 0021). Crescem por adicao. ---
(def objetos-votacao #{"proposicao" "emenda" "parecer" "requerimento" "redacao_final"})
(def modalidades-votacao #{"nominal" "simbolica" "secreta"})
(def quoruns #{"maioria_simples" "maioria_absoluta" "maioria_qualificada_2_3" "maioria_qualificada_3_5"})
(def estados-votacao #{"aberta" "encerrada" "anulada"})
(def estados-votacao-terminais #{"encerrada" "anulada"})
(def tipos-voto #{"sim" "nao" "abstencao"})

(defn resultado-votacao
  "VERIFICACAO do quorum (§22.4 eixo G) — devolve 'aprovada' | 'rejeitada'. Aritmetica EXATA em INTEIROS
  (a armadilha do quorum: 2/3*10 em float = 6.6666 e o floor erraria; `quot` acerta). `base-membros` = a
  composicao da Casa (p/ as maiorias absoluta/qualificada); a maioria simples olha os votos validos.
  ceil(p*N/q) = (quot (+ p*N (dec q)) q). NOTA: a BASE exata por tipo (Casa vs votos validos vs presentes)
  e' detalhe REGIMENTAL — default defensavel aqui; refinavel com o especialista (como os templates [GAP])."
  [quorum-tipo {:keys [sim nao]} base-membros]
  (let [aprovado?
        (case quorum-tipo
          "maioria_simples"         (> sim nao)                                ; mais sim que nao (validos)
          "maioria_absoluta"        (>= sim (inc (quot base-membros 2)))       ; > metade da Casa
          "maioria_qualificada_2_3" (>= sim (quot (+ (* 2 base-membros) 2) 3)) ; ceil(2N/3)
          "maioria_qualificada_3_5" (>= sim (quot (+ (* 3 base-membros) 4) 5)) ; ceil(3N/5)
          (throw (ex-info "quorum-tipo desconhecido" {:quorum-tipo quorum-tipo})))]
    (if aprovado? "aprovada" "rejeitada")))

;; --- eixo G / F4 Slice 3: politica da camada FINA p/ DIRIGIR a votacao ao vivo (§22.5 eixo E) ---
;; A votacao em plenario e' dirigida no contexto de uma SESSAO (recurso de outro modulo, lido via
;; `consultar-sessao` injetada — legislativo NAO importa sessoes, §22.10). Como o `tempo_real` fez com
;; `pode-assistir-plenario?`, a politica mora no modulo CONSUMIDOR e opera sobre o mapa de sessao. V1 =
;; defesa-em-profundidade (mesma Casa); a RLS ja escopa por tenant, isto barra um recurso de outra Casa que
;; escape por bug de query (fail-closed). Politicas mais ricas (sessao 'aberta', presidencia da Mesa) plugam
;; aqui sem mudar a borda; em F2 vira expressao da DSL avaliada pelo mesmo motor (disciplina 5). Pura.

(defn pode-dirigir-votacao?
  "Camada FINA p/ ABRIR/REGISTRAR/ENCERRAR votacao na sessao carregada: o ator e a sessao sao da MESMA Casa."
  [ator sessao]
  (= (:ente-id ator) (:ente-id sessao)))

;; --- F3.8 pos-aprovacao (§22.4; doc-mestre L247). Vocabularios espelham os CHECK da migration 0022. ---
;; F3.8a — tramitacao no Executivo (sancao/veto). Ciclo: aguardando -> {sancionado|sancao_tacita|vetado};
;; vetado -> {veto_mantido|veto_derrubado}. Rito/prazos exatos = [GAP] regimental (§22.4.4).
(def estados-executivo
  #{"aguardando" "sancionado" "sancao_tacita" "vetado" "veto_mantido" "veto_derrubado"})

(def estados-executivo-terminais
  "Os 4 desfechos terminais da tramitacao executiva (imutabilidade nivel b). Espelha os args do trigger
  `trg_exec_imut_estado` (mig 0022). 'aguardando'/'vetado' sao intermediarios."
  #{"sancionado" "sancao_tacita" "veto_mantido" "veto_derrubado"})

(def estados-executivo-promulgaveis
  "Desfechos em que o projeto VIRA NORMA (promulgavel, F3.8b): sancao expressa, sancao tacita (silencio do
  Executivo) ou derrubada do veto pela camara. 'veto_mantido' arquiva (nao vira norma); veto parcial ->
  promulgacao parcial e' [GAP] regimental."
  #{"sancionado" "sancao_tacita" "veto_derrubado"})

(defn promulgavel?
  "Predicado PURO: o desfecho da tramitacao no Executivo habilita a promulgacao da norma (F3.8b)?"
  [estado-executivo]
  (contains? estados-executivo-promulgaveis estado-executivo))

;; resultados validos de cada transicao da tramitacao executiva (guard fail-closed, fonte unica).
(def estados-resposta-executivo
  "aguardando -> resposta do Executivo. sancao expressa | sancao tacita (silencio) | veto."
  #{"sancionado" "sancao_tacita" "vetado"})
(def estados-apreciacao-veto
  "vetado -> apreciacao do veto pela camara (votacao maioria absoluta, eixo G)."
  #{"veto_mantido" "veto_derrubado"})

(def tipos-veto #{"total" "parcial"})

;; F3.8b — norma promulgada. Vocabularios espelham os CHECK da migration 0023. Crescem por adicao.
(def tipos-norma #{"lei" "lei_complementar" "resolucao" "decreto_legislativo" "emenda_lom"})
(def estados-norma #{"promulgada" "publicada"})

(def ^:private tipo-proposicao->tipo-norma-map
  "Espécie da proposicao -> especie da norma que ela origina na promulgacao. So as 5 espécies LEGISLATIVAS
  viram norma; indicacao/requerimento/mocao nao produzem ato normativo (fail-closed: lanca)."
  {"projeto_lei"                 "lei"
   "projeto_lei_complementar"    "lei_complementar"
   "projeto_resolucao"           "resolucao"
   "projeto_decreto_legislativo" "decreto_legislativo"
   "proposta_emenda_lom"         "emenda_lom"})

(defn tipo-proposicao->tipo-norma
  "Deriva a especie da norma a partir da especie da proposicao aprovada. Fail-closed: especie sem norma
  (indicacao/requerimento/mocao) ou desconhecida LANCA (nao se promulga o que nao e' ato normativo)."
  [tipo-proposicao]
  (or (get tipo-proposicao->tipo-norma-map tipo-proposicao)
      (throw (ex-info "especie de proposicao nao produz norma (nao e' ato normativo)"
                      {:tipo-proposicao tipo-proposicao}))))

;; --- F3.9a EXPEDIENTE: Protocolo Geral (§16.3, feature 3.23). Vocabularios espelham os CHECK da mig 0024. ---
(def objetos-protocolo
  #{"proposicao" "documento" "oficio_recebido" "requerimento_cidadao" "processo_administrativo" "outro"})
(def sentidos-protocolo #{"recebido" "expedido" "interno"})

;; --- F3.9b EXPEDIENTE: geracao de documentos por modelo (feature 3.22). Espelham os CHECK da mig 0025. ---
(def tipos-documento
  #{"oficio" "certidao" "requerimento_administrativo" "convite" "mala_direta" "outro"})
(def estados-documento #{"rascunho" "emitido"})
(def estados-documento-terminais
  "Emitido = artefato congelado (imutabilidade b; espelha o arg do trigger trg_documento_imut_estado)."
  #{"emitido"})

(defn renderizar-documento
  "MERGE do dominio no template (feature 3.22): substitui cada placeholder {{chave}} pelo valor em `dados`
  (mapa chave-string -> valor). FAIL-CLOSED: placeholder sem valor correspondente LANCA — um documento legal
  nao sai com campo nao-preenchido (mesma disciplina da URN). `dados` = FATOS resolvidos UPSTREAM (cadastro
  etc.), nao JOIN cross-schema (§22.10). Devolve o corpo renderizado."
  [template dados]
  (when (nil? template)
    (throw (ex-info "renderizar-documento: corpo-template nao pode ser nil" {})))
  ;; '-' no INICIO do character class (literal explicito; evita ler '.-' como range na manutencao).
  (str/replace template #"\{\{\s*([-\p{Alnum}_.]+)\s*\}\}"
               (fn [[_ chave]]
                 (let [v (get dados chave)]
                   (when (nil? v)
                     (throw (ex-info "renderizar-documento: placeholder sem valor em dados (campo nao-preenchido)"
                                     {:chave chave})))
                   (str v)))))

(def limite-inline-bytes
  "Threshold inline/URI (§22.4 eixo B; calibravel por observabilidade). Acima disso o conteudo vai p/
  o objeto_store e a versao guarda a URI; ate isso, inline na coluna texto_inline."
  32768)

(defn decidir-armazenamento
  "Dado o conteudo (string), decide :inline (<= 32KB em UTF-8) ou :objeto-store (acima). A API do core
  abstrai a diferenca (quem chama recebe {texto}); esta e' a regra pura de roteamento (§22.4 disc.3)."
  [^String texto]
  (if (<= (alength (.getBytes texto "UTF-8")) limite-inline-bytes) :inline :objeto-store))

;; --- tipo -> vocabulario LexML (ADR-0002 §3). Padrao LexML Brasil/Interlegis. ---
(def ^:private tipo->lexml-map
  {"projeto_lei"                 "projeto.lei"
   "projeto_lei_complementar"    "projeto.lei.complementar"
   "projeto_resolucao"           "projeto.resolucao"
   "projeto_decreto_legislativo" "projeto.decreto.legislativo"
   "proposta_emenda_lom"         "proposta.emenda.lei.organica"
   "indicacao"                   "indicacao"
   "requerimento"                "requerimento"
   "mocao"                       "mocao"})

;; --- tipo -> sigla de exibicao (template default; override por ente e' hook futuro, ADR-0002 §2). ---
(def ^:private tipo->sigla
  {"projeto_lei"                 "PL"
   "projeto_lei_complementar"    "PLC"
   "projeto_resolucao"           "PR"
   "projeto_decreto_legislativo" "PDL"
   "proposta_emenda_lom"         "PELO"
   "indicacao"                   "IND"
   "requerimento"                "REQ"
   "mocao"                       "MOC"})

(defn tipo->lexml
  "Vocabulario LexML do tipo. Fail-closed: tipo sem mapeamento lanca (nao monta URN torta)."
  [tipo]
  (or (get tipo->lexml-map tipo)
      (throw (ex-info "tipo sem mapeamento LexML" {:tipo tipo}))))

(defn municipio-slug
  "Nome do municipio -> slug LexML (sem acento, minusculo, .-separado; ADR-0002 §3). Deriva da
  cadastros.municipios (fonte canonica). NFD + strip de diacriticos combinantes. Fail-LOUD: nome
  vazio ou que reduz a slug vazio LANCA — uma URN sem municipio (urn:lex:br;ce;:camara...) seria
  persistida imutavel para sempre (incidente juridico)."
  [^String nome]
  (when (str/blank? nome)
    (throw (ex-info "municipio-nome nao pode ser vazio (URN exige o municipio)" {:nome nome})))
  (let [slug (-> (Normalizer/normalize nome Normalizer$Form/NFD)
                 (str/replace #"\p{M}+" "")            ; remove diacriticos (o-til -> o)
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" ".")       ; runs de nao-alfanumerico -> .
                 (str/replace #"^\.+|\.+$" ""))]        ; sem . nas pontas
    (when (str/blank? slug)
      (throw (ex-info "municipio-nome produziu slug vazio (caracteres invalidos?)" {:nome nome})))
    slug))

(defn urn-lex
  "Coordenada publica interoperavel da PROPOSICAO (ADR-0002 §3 — formato exato):
   urn:lex:br;{uf};{municipio-slug}:camara.municipal;{tipo-lexml}:{ano};{sequencial}.
  Computada no protocolo; persistida imutavel. (A URN-de-NORMA e' atribuida na promulgacao, F3.8.)"
  [{:keys [uf municipio-nome tipo ano sequencial]}]
  (when (str/blank? uf)
    (throw (ex-info "uf nao pode ser vazia na URN" {:uf uf})))
  (str "urn:lex:br;" (str/lower-case uf) ";" (municipio-slug municipio-nome)
       ":camara.municipal;" (tipo->lexml tipo) ":" ano ";" sequencial))

;; --- tipo-norma -> vocabulario LexML do ATO PROMULGADO (sem 'projeto.', ADR-0002 §3). ---
(def ^:private tipo-norma->lexml-map
  {"lei"                 "lei"
   "lei_complementar"    "lei.complementar"
   "resolucao"           "resolucao"
   "decreto_legislativo" "decreto.legislativo"
   "emenda_lom"          "emenda.lei.organica"})

(defn tipo-norma->lexml
  "Vocabulario LexML da especie de norma. Fail-closed: especie sem mapeamento lanca."
  [tipo-norma]
  (or (get tipo-norma->lexml-map tipo-norma)
      (throw (ex-info "tipo-norma sem mapeamento LexML" {:tipo-norma tipo-norma}))))

(defn urn-norma
  "Coordenada publica LexML da NORMA PROMULGADA (ADR-0002 §3) — nasce na promulgacao, imutavel:
   urn:lex:br;{uf};{municipio-slug}:{tipo-norma-lexml}:{data};{numero}.
  Forma canonica LexML de legislacao (<jurisdicao>:<tipo>:<data>;<numero>); a jurisdicao 'br;uf;municipio'
  ja' encerra o municipio como autoridade da lei municipal — por isso, diferente da URN da PROPOSICAO (que
  carrega 'camara.municipal', o corpo PRODUTOR de um projeto ainda nao-lei), a norma nao repete a autoridade.
  `data` = data da promulgacao (LocalDate, ISO yyyy-MM-dd). NOTA: o segmento exato de autoridade por especie
  (ato da camara vs lei do municipio) e' refino LexML/regimental — defensavel aqui, [GAP] como os templates."
  [{:keys [uf municipio-nome tipo-norma data numero]}]
  (when (str/blank? uf)
    (throw (ex-info "uf nao pode ser vazia na URN" {:uf uf})))
  (when (nil? data)
    (throw (ex-info "data da promulgacao e' obrigatoria na URN-de-norma" {:data data})))
  (when (nil? numero)
    (throw (ex-info "numero e' obrigatorio na URN-de-norma" {:tipo-norma tipo-norma :data data})))
  (str "urn:lex:br;" (str/lower-case uf) ";" (municipio-slug municipio-nome)
       ":" (tipo-norma->lexml tipo-norma) ":" data ";" numero))

(defn numero-exibicao
  "Numero que o cidadao le (ex.: 'PL 042/2026'). Template default por sigla + zero-pad 3 (nao trunca
  acima de 999). O schema guarda tipo/ano/sequencial crus; isto e' a projecao de exibicao."
  [{:keys [tipo ano sequencial]}]
  (str (get tipo->sigla tipo (str/upper-case tipo)) " " (format "%03d" sequencial) "/" ano))
