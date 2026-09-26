(ns acervo
  "Semente do ACERVO LEGISLATIVO da demo (plano `docs/superpowers/plans/2026-09-07-prontidao-de-
  apresentacao.md`, Task 0.4) — sobre a Casa de `casa/semear!`: 24 proposicoes de autoria distribuida
  entre os 17 vereadores, cobrindo os 6 estados de um RITO REAL da Casa (`rito_ordinario`, nome
  apresentavel — NAO 'rito_fixture_portal' como `seed_demo.clj/materias`) + 3 pareceres (rascunho /
  aguardando assinatura do relator / emitido) + 2 autografos pos-aprovacao (1 aguardando o Executivo, 1
  sancionado) + 4 normas promulgadas e publicadas. Usa SO o Repo-Component REAL do legislativo
  (`RepoLegislativo`, ja' booted em `sistema` — `(:repo-legislativo sistema)`/`(:registro-fatos
  sistema)`), o MESMO motor declarativo compartilhado de tramitacao/pareceres (Disciplina 5, §22.4.3/
  §22.5.3) — nenhuma DSL nova.

  VOCABULARIO — lido da FONTE, nao de memoria (regra dura do briefing da Task 0.4):
  - `legislativo.proposicoes.estado` NAO TEM CHECK (migration 20260620000013-legislativo-
    proposicoes.up.sql:29, comentario 'coarse; a maquina fina e' a tramitacao (F3.3)'); os 6 estados
    usados aqui sao DADO, inseridos por este ns em `legislativo.template_estado` (Invariante 4 — regra
    de compliance/rito e' dado, nao codigo).
  - `tipo` — autoridade real `legislativo.logic/tipos` (src/oplenario/legislativo/logic.clj:13-16); as
    24 materias cobrem os 8 valores do vocabulario fechado da V1.
  - `legislativo.pareceres.estado` tambem e' template-driven, sem CHECK (migration 20260620000019-
    legislativo-pareceres.up.sql:32); os 4 UNICOS terminais fixos no trigger de imutabilidade sao
    'aprovado'/'rejeitado'/'prejudicado'/'prazo_vencido' (mesma migration:87, espelhados em
    `legislativo.logic/estados-parecer-terminais`) — o parecer 'emitido' deste ns termina em 'aprovado'
    (vocabulario real de dominio; 'emitido' e' so' o rotulo de UI da Task 0.4/J3).
  - `legislativo.norma.autografo_id` e' `NOT NULL` + `UNIQUE (ente_id, autografo_id)` (migration
    20260620000023-legislativo-norma.up.sql:24,49) — cada norma exige o SEU PROPRIO autografo
    sancionado. Os '2 autografos' do briefing sao os que ficam VISIVEIS na jornada pos-aprovacao SEM
    virar lei ainda (1 aguardando, 1 sancionado); as 4 normas nascem de 4 OUTROS autografos, cada um
    sancionado so' para satisfazer essa FK — nao contam contra os '2' do briefing, que descrevem o que
    a tela de pos-aprovacao mostra pendente."
  (:require [clojure.string :as string]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.cadastros.db.vereador :as vereador]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.assinador-icp :as assinador-icp]
            [oplenario.legislativo.components.repositorio :as repo-leg])
  (:import (java.time LocalDate)))

;; ---------- constantes ----------

(def ^:private rito-chave "rito_ordinario")
(def ^:private parecer-chave "parecer_comissao_permanente")

(def ^:private hoje
  "Mesma vigencia-inicio de `casa/hoje` (LocalDate 2025-01-01, a posse da legislatura 2025-2028 — cobre
  qualquer data em que a demo rodar dentro dela). Duplicado aqui de proposito: `casa/hoje` e' privado ao
  ns `casa` (`^:private`), e este ns so' pode CRIAR `acervo.clj` (escopo da Task 0.4) — os dois leem o
  MESMO invariante (inicio da legislatura vigente), nao dois fatos diferentes por coincidencia."
  (LocalDate/of 2025 1 1))

;; ---------- o RITO ORDINARIO (sujeito 'proposicao', o default de `criar-template!`) ----------

(def ^:private estados-rito
  [{:chave "protocolada"      :nome "Protocolada"            :ordem 1 :terminal false}
   {:chave "em_comissoes"     :nome "Em Comissões"           :ordem 2 :terminal false}
   {:chave "aguardando_pauta" :nome "Aguardando Pauta"       :ordem 3 :terminal false}
   {:chave "em_pauta"         :nome "Em Pauta"               :ordem 4 :terminal false}
   {:chave "aprovada"         :nome "Aprovada"               :ordem 5 :terminal true}
   {:chave "arquivada"        :nome "Arquivada"              :ordem 6 :terminal true}])

(def ^:private transicoes-rito
  [{:de-estado "protocolada"      :para-estado "em_comissoes"     :gatilho "despachar"}
   {:de-estado "protocolada"      :para-estado "arquivada"        :gatilho "arquivar"}
   {:de-estado "em_comissoes"     :para-estado "aguardando_pauta" :gatilho "concluir_comissoes"}
   {:de-estado "aguardando_pauta" :para-estado "em_pauta"         :gatilho "incluir_pauta"}
   {:de-estado "em_pauta"         :para-estado "aprovada"         :gatilho "aprovar"}
   {:de-estado "em_pauta"         :para-estado "arquivada"        :gatilho "rejeitar"}])

;; ---------- as 24 proposicoes — ementas plausiveis de camara municipal (nada de "teste 1"/"foo") ----------
;; :ref identifica o item p/ pareceres/autografos/normas irem buscar um :id especifico depois de protocolar.
;; :caminho = os gatilhos disparados em sequencia por `transicionar!`, a partir de 'protocolada'.
;; :texto (Task C, #15 do briefing de prontidao) — cada materia carrega o SEU corpo, nunca lorem ipsum nem
;; texto repetido entre as 24; helpers abaixo compoem por genero normativo, mas cada chamada passa artigos/
;; pedido/justificativa PROPRIOS aquela ementa. `repositorio.clj:262-277` (protocolar!) so' cria + promove a
;; versao de texto quando o payload traz `:texto` — sem isto, `proposicao_texto_versao` fica vazia p/ o
;; ente da demo (a causa raiz do defeito, ja confirmada na fonte).

(defn- artigos->texto
  "Corpo de um projeto normativo (lei/lei complementar/resolucao/decreto legislativo/emenda a LOM):
  cada string de `artigos` vira UM artigo numerado em sequencia a partir do Art. 1o — o CALLER decide o
  conteudo (nucleo especifico da ementa, complemento, despesas quando cabe, vigencia), nunca este helper.

  Sem sintaxe markdown (`## `) no cabecalho — defeito F3/MATA da caminhada pos-fatia: o dominio grava
  `formato \"markdown\"` mas a ficha da materia e' texto puro e nao renderiza; a versao anterior deste
  helper escrevia `## Lei` e a tela mostrava o `##` cru ao usuario. O rotulo continua presente, so' sem
  o marcador."
  [rotulo artigos]
  (str rotulo "\n\n"
       (string/join "\n\n" (map-indexed (fn [i corpo] (str "Art. " (inc i) "º " corpo)) artigos))))

(defn- texto-indicacao [pedido justificativa]
  (str "Indicação\n\nSenhor Presidente,\n\n"
       "Nos termos regimentais, venho indicar à Mesa Diretora, para que encaminhe ao Poder Executivo "
       "Municipal, a seguinte providência:\n\n" pedido "\n\nJustificativa\n\n" justificativa))

(defn- texto-requerimento [pedido justificativa]
  (str "Requerimento\n\nSenhor Presidente,\n\n"
       "Requeiro a Vossa Excelência, ouvido o Plenário, nos termos regimentais, que seja oficiado ao "
       "Poder Executivo Municipal solicitando:\n\n" pedido "\n\nJustificativa\n\n" justificativa))

(defn- texto-requerimento-pesar [homenageado justificativa]
  (str "Requerimento\n\nSenhor Presidente,\n\n"
       "Requeiro a Vossa Excelência que se consigne, em ata dos trabalhos desta Casa, voto de profundo "
       "pesar pelo falecimento de " homenageado ", dando-se ciência à família enlutada.\n\n"
       "Justificativa\n\n" justificativa))

(defn- texto-mocao [titulo corpo considerandos]
  (str "Moção de " titulo "\n\n" corpo "\n\nConsiderando\n\n"
       (string/join "\n" (map #(str "- " %) considerandos))
       "\n\nA Câmara Municipal de Fortaleza RESOLVE encaminhar a presente Moção aos destinatários "
       "mencionados, dando-lhes ciência de seu inteiro teor."))

(def ^:private materias
  [;; ---- protocolada (4) ----
   {:ref :protocolada-1 :tipo "projeto_lei"
    :ementa "Institui o Programa Municipal de Hortas Comunitárias e dá outras providências."
    :caminho []
    :texto (artigos->texto "Lei"
             ["Fica instituído, no âmbito do Município de Fortaleza, o Programa Municipal de Hortas Comunitárias, destinado a promover a produção de alimentos e a agricultura urbana em áreas públicas e comunitárias."
              "O Poder Executivo disponibilizará espaços públicos ociosos para implantação das hortas comunitárias, mediante termo de cessão de uso às associações e grupos comunitários interessados."
              "As despesas decorrentes da execução desta Lei correrão por conta de dotações orçamentárias próprias, suplementadas se necessário."
              "Esta Lei entra em vigor na data de sua publicação."])}
   {:ref :protocolada-2 :tipo "projeto_lei"
    :ementa "Dispõe sobre a obrigatoriedade de instalação de bebedouros em praças públicas municipais."
    :caminho []
    :texto (artigos->texto "Lei"
             ["Ficam as praças públicas municipais obrigadas a dispor de, no mínimo, 1 (um) bebedouro de água potável em local de fácil acesso ao público."
              "O Poder Executivo instalará os bebedouros previstos no art. 1º no prazo de 180 (cento e oitenta) dias, contado da publicação desta Lei, priorizando as praças de maior fluxo de pessoas, e observará padrões de acessibilidade para pessoas com deficiência e mobilidade reduzida."
              "As despesas decorrentes da execução desta Lei correrão por conta de dotações orçamentárias próprias, suplementadas se necessário."
              "Esta Lei entra em vigor na data de sua publicação."])}
   {:ref :protocolada-3 :tipo "indicacao"
    :ementa "Indica ao Executivo a instalação de iluminação pública na Praça da Gentilândia."
    :objeto-indicacao "Instalação de iluminação pública na Praça da Gentilândia" :caminho []
    :texto (texto-indicacao
             "a instalação de iluminação pública na Praça da Gentilândia, com prioridade para os pontos de acesso de pedestres e as áreas de maior circulação noturna."
             "moradores da região relatam sensação de insegurança no período noturno em razão da precariedade da iluminação existente, o que prejudica a circulação de pedestres e favorece a ocorrência de furtos.")}
   {:ref :protocolada-4 :tipo "requerimento"
    :ementa "Requer informações ao Executivo sobre o andamento das obras do Parque Linear do Rio Cocó."
    :tipo-requerimento "informacao" :caminho []
    :texto (texto-requerimento
             "o andamento das obras do Parque Linear do Rio Cocó, especificando: (i) o percentual de execução física da obra; (ii) o cronograma atualizado de conclusão; e (iii) eventuais pendências que impactem o prazo de entrega."
             "moradores do entorno têm procurado este gabinete parlamentar questionando a aparente paralisação das obras, sem informação oficial disponível ao público.")}

   ;; ---- em_comissoes (4) ----
   {:ref :em-comissoes-1 :tipo "projeto_lei"
    :ementa "Dispõe sobre a criação do Conselho Municipal de Mobilidade Urbana."
    :caminho ["despachar"]
    :texto (artigos->texto "Lei"
             ["Fica criado o Conselho Municipal de Mobilidade Urbana, órgão colegiado de caráter consultivo, vinculado à Secretaria Municipal responsável pela mobilidade urbana."
              "Compete ao Conselho acompanhar a execução da política municipal de mobilidade urbana, opinar sobre projetos de infraestrutura viária e propor diretrizes de acessibilidade e transporte coletivo, sendo composto por representantes do Poder Executivo, da sociedade civil e de entidades de classe ligadas ao transporte, na forma do regulamento."
              "As despesas decorrentes da execução desta Lei correrão por conta de dotações orçamentárias próprias, suplementadas se necessário."
              "Esta Lei entra em vigor na data de sua publicação."])}
   {:ref :em-comissoes-2 :tipo "projeto_lei"
    :ementa "Autoriza o Executivo a firmar convênio com entidades de assistência social do Município."
    :caminho ["despachar"]
    :texto (artigos->texto "Lei"
             ["Fica o Poder Executivo autorizado a firmar convênios com entidades de assistência social sem fins lucrativos sediadas no Município, para execução de ações de proteção social básica e especial."
              "Os convênios de que trata esta Lei observarão critérios objetivos de habilitação das entidades, definidos em regulamento, serão precedidos de chamamento público, nos termos da legislação aplicável, e exigirão prestação de contas anual ao órgão municipal repassador."
              "As despesas decorrentes da execução desta Lei correrão por conta de dotações orçamentárias próprias, suplementadas se necessário."
              "Esta Lei entra em vigor na data de sua publicação."])}
   {:ref :em-comissoes-3 :tipo "projeto_lei_complementar"
    :ementa "Altera o Código de Posturas do Município quanto ao horário de funcionamento do comércio."
    :caminho ["despachar"]
    :texto (artigos->texto "Lei Complementar"
             ["O dispositivo do Código de Posturas do Município que trata do horário de funcionamento do comércio passa a vigorar de modo a permitir o funcionamento dos estabelecimentos comerciais de segunda-feira a sábado, das 6h às 22h, e aos domingos e feriados, das 8h às 18h."
              "Os estabelecimentos que exerçam atividade de interesse turístico ou de lazer poderão requerer horário especial de funcionamento, mediante autorização do órgão municipal competente."
              "Esta Lei Complementar entra em vigor na data de sua publicação."])}
   {:ref :em-comissoes-4 :tipo "mocao"
    :ementa "Manifesta congratulações à comunidade escolar pela conquista na Olimpíada Municipal de Matemática."
    :categoria-mocao "congratulacoes" :caminho ["despachar"]
    :texto (texto-mocao "Congratulações"
             "A Câmara Municipal de Fortaleza manifesta congratulações à comunidade escolar do Município pela expressiva conquista de estudantes e professores na Olimpíada Municipal de Matemática."
             ["a Olimpíada revela e estimula talentos da rede pública e privada de ensino do Município;"
              "o resultado é fruto do empenho de estudantes, do trabalho de professores e do apoio das famílias;"
              "cabe ao Poder Legislativo reconhecer publicamente iniciativas que valorizam a educação municipal."])}

   ;; ---- aguardando_pauta (4) ----
   {:ref :aguardando-pauta-1 :tipo "projeto_lei"
    :ementa "Institui a Semana Municipal de Combate ao Trabalho Infantil."
    :caminho ["despachar" "concluir_comissoes"]
    :texto (artigos->texto "Lei"
             ["Fica instituída, no calendário oficial do Município, a Semana Municipal de Combate ao Trabalho Infantil, a ser realizada anualmente na semana que contempla o dia 12 de junho, Dia Mundial contra o Trabalho Infantil."
              "Durante a Semana Municipal, o Poder Executivo promoverá, em articulação com o Conselho Tutelar e a rede de proteção à criança e ao adolescente, atividades de conscientização nas escolas da rede municipal de ensino."
              "Esta Lei entra em vigor na data de sua publicação."])}
   {:ref :aguardando-pauta-2 :tipo "projeto_lei"
    :ementa "Dispõe sobre a coleta seletiva de resíduos sólidos na Zona Leste do Município."
    :caminho ["despachar" "concluir_comissoes"]
    :texto (artigos->texto "Lei"
             ["Fica instituído o serviço de coleta seletiva de resíduos sólidos domiciliares na Zona Leste do Município, com separação entre resíduos recicláveis e rejeitos."
              "O Poder Executivo definirá, por ato próprio, o cronograma e os itinerários de coleta, priorizando a integração com cooperativas de catadores de materiais recicláveis sediadas no Município."
              "Esta Lei entra em vigor na data de sua publicação."])}
   {:ref :aguardando-pauta-3 :tipo "projeto_resolucao"
    :ementa "Concede título de utilidade pública à Associação Comunitária do Bairro Parangaba."
    :caminho ["despachar" "concluir_comissoes"]
    :texto (artigos->texto "Resolução"
             ["Fica declarada de utilidade pública municipal a Associação Comunitária do Bairro Parangaba, entidade civil sem fins lucrativos, com sede neste Município."
              "A declaração de utilidade pública de que trata esta Resolução não implica repasse automático de recursos públicos, dependendo cada auxílio de lei específica."
              "Esta Resolução entra em vigor na data de sua publicação."])}
   {:ref :aguardando-pauta-4 :tipo "projeto_decreto_legislativo"
    :ementa "Concede Diploma de Honra ao Mérito a profissionais da Educação Municipal."
    :caminho ["despachar" "concluir_comissoes"]
    :texto (artigos->texto "Decreto Legislativo"
             ["Fica concedido o Diploma de Honra ao Mérito a profissionais da Educação Municipal que se destacaram, no exercício de suas funções, por relevantes serviços prestados à comunidade escolar de Fortaleza."
              "A relação dos homenageados constará de anexo a ser publicado por ocasião da entrega do Diploma, em sessão solene especialmente convocada para esse fim."
              "Este Decreto Legislativo entra em vigor na data de sua publicação."])}

   ;; ---- em_pauta (3) ----
   {:ref :em-pauta-1 :tipo "projeto_lei"
    :ementa "Institui o Programa Municipal de Arborização Urbana."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta"]
    :texto (artigos->texto "Lei"
             ["Fica instituído o Programa Municipal de Arborização Urbana, com o objetivo de ampliar e qualificar a cobertura vegetal nas vias e logradouros públicos do Município."
              "O plantio de mudas previsto nesta Lei observará espécies nativas ou adaptadas ao clima local, priorizando vias com baixo índice de arborização, conforme levantamento do órgão ambiental municipal."
              "As despesas decorrentes da execução desta Lei correrão por conta de dotações orçamentárias próprias, suplementadas se necessário."
              "Esta Lei entra em vigor na data de sua publicação."])}
   {:ref :em-pauta-2 :tipo "projeto_lei"
    :ementa "Dispõe sobre a criação de vagas de estacionamento para idosos em logradouros públicos."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta"]
    :texto (artigos->texto "Lei"
             ["Ficam os estacionamentos situados em logradouros públicos municipais obrigados a reservar vagas específicas para idosos, na proporção mínima de 5% (cinco por cento) do total de vagas."
              "As vagas reservadas deverão ser sinalizadas de forma visível, com identificação própria e localização preferencial próxima aos acessos."
              "Esta Lei entra em vigor na data de sua publicação."])}
   {:ref :em-pauta-3 :tipo "proposta_emenda_lom"
    :ementa "Altera a Lei Orgânica do Município quanto à composição da Mesa Diretora."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta"]
    :texto (artigos->texto "Emenda à Lei Orgânica"
             ["O dispositivo da Lei Orgânica do Município de Fortaleza que trata da composição da Mesa Diretora passa a vigorar com nova redação, ampliando o número de membros da Mesa Diretora de 5 (cinco) para 7 (sete) vereadores."
              "Aplicam-se aos novos cargos criados por esta Emenda as mesmas prerrogativas e vedações previstas na Lei Orgânica para os demais membros da Mesa Diretora."
              "Esta Emenda à Lei Orgânica entra em vigor na data de sua publicação, produzindo efeitos a partir da próxima legislatura."])}

   ;; ---- aprovada (6, todas projeto_lei — alimentam os autografos/normas abaixo) ----
   {:ref :aprovada-1 :tipo "projeto_lei"
    :ementa "Institui o Código Municipal de Defesa do Consumidor."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta" "aprovar"]
    :texto (artigos->texto "Lei"
             ["Fica instituído o Código Municipal de Defesa do Consumidor, consolidando as normas municipais de proteção e defesa do consumidor no âmbito do Município de Fortaleza."
              "Compete ao órgão municipal de proteção e defesa do consumidor fiscalizar o cumprimento desta Lei e aplicar as sanções administrativas cabíveis, sem prejuízo das competências estadual e federal."
              "As despesas decorrentes da execução desta Lei correrão por conta de dotações orçamentárias próprias, suplementadas se necessário."
              "Esta Lei entra em vigor na data de sua publicação."])}
   {:ref :aprovada-2 :tipo "projeto_lei"
    :ementa "Cria o Programa Municipal de Incentivo à Leitura nas Escolas Públicas."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta" "aprovar"]
    :texto (artigos->texto "Lei"
             ["Fica criado o Programa Municipal de Incentivo à Leitura nas Escolas Públicas, destinado a fomentar o hábito da leitura entre estudantes da rede municipal de ensino."
              "O Programa contemplará, entre outras ações, a ampliação do acervo das bibliotecas escolares e a realização de feiras literárias anuais em cada unidade de ensino."
              "As despesas decorrentes da execução desta Lei correrão por conta de dotações orçamentárias próprias, suplementadas se necessário."
              "Esta Lei entra em vigor na data de sua publicação."])}
   {:ref :aprovada-3 :tipo "projeto_lei"
    :ementa "Institui multa para o descarte irregular de resíduos da construção civil."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta" "aprovar"]
    :texto (artigos->texto "Lei"
             ["Fica instituída multa administrativa para o descarte irregular de resíduos da construção civil em logradouros públicos, terrenos baldios ou áreas de preservação ambiental do Município."
              "A multa de que trata esta Lei será aplicada em valor correspondente a 100 (cem) a 1.000 (mil) Unidades Fiscais de Referência do Município, conforme a gravidade e a reincidência da infração, sem prejuízo da obrigação de remoção dos resíduos pelo infrator."
              "Esta Lei entra em vigor na data de sua publicação."])}
   {:ref :aprovada-4 :tipo "projeto_lei"
    :ementa "Autoriza a cessão de uso de imóvel público a entidade privada sem fins lucrativos."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta" "aprovar"]
    :texto (artigos->texto "Lei"
             ["Fica o Poder Executivo autorizado a ceder o uso de imóvel público municipal a entidade privada sem fins lucrativos, para fins de execução de atividades de relevante interesse social."
              "A cessão de que trata esta Lei será formalizada por termo próprio, pelo prazo máximo de 10 (dez) anos, renovável, vedada a cessão a título gratuito para fins diversos dos previstos no termo."
              "Esta Lei entra em vigor na data de sua publicação."])}
   {:ref :aprovada-5 :tipo "projeto_lei"
    :ementa "Institui o Programa Municipal de Combate ao Desperdício de Alimentos."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta" "aprovar"]
    :texto (artigos->texto "Lei"
             ["Fica instituído o Programa Municipal de Combate ao Desperdício de Alimentos, com o objetivo de reduzir o descarte de alimentos próprios para consumo e fomentar sua doação a entidades assistenciais."
              "Estabelecimentos comerciais do ramo alimentício poderão firmar termo de adesão ao Programa, comprometendo-se a doar excedentes alimentares a bancos de alimentos e entidades cadastradas, mediante incentivos a serem definidos em regulamento."
              "As despesas decorrentes da execução desta Lei correrão por conta de dotações orçamentárias próprias, suplementadas se necessário."
              "Esta Lei entra em vigor na data de sua publicação."])}
   {:ref :aprovada-6 :tipo "projeto_lei"
    :ementa "Dispõe sobre a acessibilidade em prédios públicos municipais."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta" "aprovar"]
    :texto (artigos->texto "Lei"
             ["Ficam os prédios públicos municipais obrigados a adequar suas instalações aos padrões de acessibilidade previstos na legislação federal, no prazo de 2 (dois) anos, contado da publicação desta Lei."
              "O Poder Executivo priorizará, no cronograma de adequação, os prédios de maior fluxo de atendimento ao público, especialmente unidades de saúde e escolas."
              "As despesas decorrentes da execução desta Lei correrão por conta de dotações orçamentárias próprias, suplementadas se necessário."
              "Esta Lei entra em vigor na data de sua publicação."])}

   ;; ---- arquivada (3) ----
   {:ref :arquivada-1 :tipo "mocao"
    :ementa "Manifesta repúdio a atos de violência contra profissionais da imprensa local."
    :categoria-mocao "repudio" :caminho ["arquivar"]
    :texto (texto-mocao "Repúdio"
             "A Câmara Municipal de Fortaleza manifesta repúdio aos recentes atos de violência praticados contra profissionais da imprensa local no exercício de suas funções."
             ["a liberdade de imprensa e a integridade dos profissionais que a exercem são pilares do regime democrático;"
              "episódios de agressão a jornalistas em cobertura de fatos de interesse público têm se repetido no Município;"
              "cabe ao Poder Legislativo municipal manifestar-se em defesa da imprensa livre e da segurança de seus profissionais."])}
   {:ref :arquivada-2 :tipo "requerimento"
    :ementa "Requer voto de pesar pelo falecimento do ex-vereador Antônio Bezerra."
    :tipo-requerimento "voto_pesar" :caminho ["arquivar"]
    :texto (texto-requerimento-pesar "Antônio Bezerra"
             "o homenageado exerceu mandato de vereador nesta Casa, prestando relevantes serviços ao Município de Fortaleza, sendo justa a manifestação de pesar por seu falecimento.")}
   {:ref :arquivada-3 :tipo "projeto_lei"
    :ementa "Dispõe sobre a redução da jornada de trabalho dos servidores da Guarda Municipal Metropolitana."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta" "rejeitar"]
    :texto (artigos->texto "Lei"
             ["Fica reduzida para 30 (trinta) horas semanais a jornada de trabalho dos servidores integrantes da Guarda Municipal Metropolitana, sem redução da remuneração."
              "As escalas de serviço serão reorganizadas pelo órgão competente de modo a preservar a cobertura operacional da Guarda Municipal Metropolitana em regime de 24 (vinte e quatro) horas."
              "As despesas decorrentes da execução desta Lei correrão por conta de dotações orçamentárias próprias, suplementadas se necessário."
              "Esta Lei entra em vigor na data de sua publicação."])}])

;; ---------- leituras cruas (nenhuma fn exposta no Repo/db do modulo p/ isto; adicionar uma so' pra este
;;            script ficaria fora do escopo da Task 0.4, que e' CRIAR SO acervo.clj) ----------

(defn- template-do-rito
  "O `id` do template `rito-chave` v1 deste ente, se ja' existir — o GATE de idempotencia (mesmo padrao
  de `casa/ja-semeada?`, carry #6 do briefing: os `db/` de proposicao/template NAO tem ON CONFLICT)."
  [tx ente]
  (:id (comum/linha->kebab
        (jdbc/execute-one! tx
          (sql/format {:select [:id] :from [:legislativo.template_tramitacao]
                       :where [:and [:= :ente_id ente] [:= :chave rito-chave] [:= :versao 1]]})))))

(defn estados-do-template
  "As `chave` que `template-id` DECLARA em `legislativo.template_estado` — leitura direta (sem fn
  exposta no Repo/db do modulo p/ listar estados de um template; ver nota da ns acima)."
  [sistema ente template-id]
  (let [ds (get-in sistema [:datasource :ds])]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (set (map :chave
                  (comum/linhas->kebab
                   (jdbc/execute! tx
                     (sql/format {:select [:chave] :from [:legislativo.template_estado]
                                  :where [:and [:= :ente_id ente] [:= :template_id template-id]]})))))))))

(defn tipos-usados
  "Os `tipo` distintos das proposicoes do ente — via `listar-e-contar-proposicoes` (a MESMA leitura
  paginada que a lista real do FE usa), dentro da API do Repo (§22.10), nao um SELECT cru."
  [sistema ente]
  (->> (repo-leg/listar-e-contar-proposicoes (:repo-legislativo sistema) ente {:pagina 1 :tamanho 200})
       :itens (map :tipo) set))

(defn contar-por-estado
  "{estado -> quantidade de proposicoes} do ente — MESMA leitura de `tipos-usados`."
  [sistema ente]
  (->> (repo-leg/listar-e-contar-proposicoes (:repo-legislativo sistema) ente {:pagina 1 :tamanho 200})
       :itens (map :estado) frequencies))

(defn- comissoes-permanentes-do-ente
  "As comissoes permanentes REAIS da Casa (CCJ, Financas, Obras — criadas por `casa.clj`), tipo!='mesa'.
  Leitura crua cross-schema (mesmo racional das leituras acima; `sessoes.clj` ja cruza pra
  `cadastros.sessao_legislativa` do mesmo jeito) — ledger #11 (docs/16-ledger-prontidao.md):
  `semear-pareceres!` gravava `comissao-id` como `(random-uuid)`, guard ref ORFAO (sem FK, §22.10),
  e a tela `/parecer/:id` mostrava esse UUID cru onde deveria ir o nome da comissao."
  [tx ente]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:id :nome] :from [:cadastros.comissao]
                   :where [:and [:= :ente_id ente] [:= :tipo "permanente"]]
                   :order-by [[:nome :asc]]}))))

(defn comissoes
  "As comissoes permanentes reais da Casa — usado pelo teste da Task 0.4 (ledger #11) p/ provar que
  todo `comissao-id` de parecer aponta pra uma comissao que EXISTE."
  [sistema ente]
  (let [ds (get-in sistema [:datasource :ds])]
    (tenancy/com-tenant* ds ente (fn [tx] (comissoes-permanentes-do-ente tx ente)))))

(defn pareceres
  "Leitura crua de auditoria dos pareceres deste ente (id/comissao-id/estado/relator-id) — usado pelo
  teste da Task 0.4 (ledger #11/#12); nenhuma fn exposta no Repo/db do modulo p/ 'listar pareceres do
  ente' sem filtrar por objeto/relator (ver nota da ns acima)."
  [sistema ente]
  (let [ds (get-in sistema [:datasource :ds])]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (comum/linhas->kebab
          (jdbc/execute! tx
            (sql/format {:select [:id :comissao_id :estado :relator_id] :from [:legislativo.pareceres]
                         :where [:= :ente_id ente]})))))))

;; ---------- o rito + as 24 proposicoes ----------

(defn- criar-rito!
  "Persiste o template `rito-chave` (sujeito 'proposicao', o default) + os 6 estados + as 6 transicoes —
  TUDO guard nil (sempre permite; nenhuma regra condicional e' necessaria p/ o roteiro da demo)."
  [repo ente]
  (let [tid (random-uuid)]
    (repo-leg/criar-template! repo ente
      {:id tid :chave rito-chave :versao 1 :nome "Rito Ordinário de Tramitação" :estado-inicial "protocolada"})
    (doseq [e estados-rito]
      (repo-leg/criar-estado! repo ente (assoc e :id (random-uuid) :template-id tid)))
    (doseq [t transicoes-rito]
      (repo-leg/criar-transicao! repo ente (assoc t :id (random-uuid) :template-id tid)))
    tid))

(defn- protocolar-e-tramitar!
  "Protocola 1 materia (autor = vereador `idx` do roster, round-robin) e percorre `:caminho` via o
  ENGINE real (`transicionar!`, Disciplina 5) — nunca `mudar-estado-proposicao!` (bypassaria o motor).

  `:texto` (Task C, #15) vai no payload de `protocolar!` — SO' com essa chave presente e' que
  `repositorio.clj:262-277` cria E promove a versao de texto (`texto/nova-versao!` + `texto/promover!`);
  sem ela `proposicao_texto_versao` fica vazia p/ o ente da demo. `:created-by` fica ausente de proposito
  (vira nil no `p` do Repo, coluna nullable — mesmo padrao ja usado neste ns p/ `updated-by nil` nos
  pareceres; `vereador/listar` NAO devolve `identidade-id`, entao nao ha' ator real disponivel aqui).

  Depois de protocolada, INSCREVE a materia no Livro do Protocolo Geral via `repo-leg/protocolar-geral!`
  (Task C, #14) — `objeto-tipo` 'proposicao' (vocabulario do CHECK, migration 20260620000024:24-25),
  `sentido` 'interno' (corrigido — caminhada pos-fatia, defeito F1/MATA: a versao anterior gravava
  'recebido' com um racional que nao sobrevive a fonte. O proprio modulo, no seu teste de integracao —
  `protocolo_geral_db_test.clj:52` — usa `proposicao|interno` para exatamente este caso: uma proposicao
  protocolada em nome de um vereador da PROPRIA Casa. 'recebido' e' para o que entra de FORA da Casa —
  um oficio da Prefeitura (`oficio_recebido|recebido`, mesmo teste, linha 55) ou um requerimento de
  cidadao; um vereador nao e' externo a sua propria Casa, entao a materia nao 'entra' — ela nasce aqui.
  Numero e' gapless por (ente,ano) via `kernel/sequencial`, escopo 'protocolo_geral:ano' — nunca escrito
  a mao. DECISAO DO CONTROLADOR (nao ampliar): so' as 24 proposicoes entram no Livro por esta fatia;
  oficios/documentos administrativos ficam fora de escopo.

  Devolve o `id` da proposicao. Falha alto se algum gatilho do caminho NAO transicionar (guard bloqueado
  ou rito mal-formado — bug deste ns, nao dado esperado)."
  [repo registro ente template-id vereadores idx
   {:keys [tipo ementa caminho objeto-indicacao tipo-requerimento categoria-mocao texto]}]
  (let [autor (nth vereadores (mod idx (count vereadores)))
        {pid :id} (repo-leg/protocolar! repo ente
                    {:id (random-uuid) :ente-id ente :tipo tipo :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
                     :ementa ementa :autor-tipo "vereador" :autor-id (:id autor) :autor-texto (:nome-parlamentar autor)
                     :objeto-indicacao objeto-indicacao :tipo-requerimento tipo-requerimento
                     :categoria-mocao categoria-mocao :texto texto})]
    (doseq [gatilho caminho]
      (let [r (repo-leg/transicionar! repo ente registro
                {:proposicao-id pid :template-id template-id :gatilho gatilho})]
        (when-not (:transicionou? r)
          (throw (ex-info "acervo/semear!: gatilho do caminho nao transicionou (guard bloqueado ou rito mal-formado)"
                          {:proposicao-id pid :gatilho gatilho :de (:de r)})))))
    (repo-leg/protocolar-geral! repo ente
      {:id (random-uuid) :ano 2026 :objeto-tipo "proposicao" :objeto-id pid :sentido "interno"
       :assunto ementa :interessado-texto (:nome-parlamentar autor)})
    pid))

;; ---------- o template de PARECER (sujeito 'parecer') + os 3 pareceres ----------

(defn- criar-template-parecer!
  "'em_elaboracao' (inicial) -[concluir_relatoria]-> 'aguardando_assinatura' -[emitir]-> 'aprovado'
  (terminal, vocabulario real de `legislativo.logic/estados-parecer-terminais`)."
  [repo ente]
  (let [tid (random-uuid)]
    (repo-leg/criar-template! repo ente
      {:id tid :chave parecer-chave :versao 1 :sujeito "parecer"
       :nome "Parecer de Comissão Permanente" :estado-inicial "em_elaboracao"})
    (repo-leg/criar-estado! repo ente {:id (random-uuid) :template-id tid :chave "em_elaboracao"
                                       :nome "Em Elaboração" :ordem 1 :terminal false})
    (repo-leg/criar-estado! repo ente {:id (random-uuid) :template-id tid :chave "aguardando_assinatura"
                                       :nome "Aguardando Assinatura do Relator" :ordem 2 :terminal false})
    (repo-leg/criar-estado! repo ente {:id (random-uuid) :template-id tid :chave "aprovado"
                                       :nome "Aprovado" :ordem 3 :terminal true})
    (repo-leg/criar-transicao! repo ente {:id (random-uuid) :template-id tid :de-estado "em_elaboracao"
                                          :para-estado "aguardando_assinatura" :gatilho "concluir_relatoria"})
    (repo-leg/criar-transicao! repo ente {:id (random-uuid) :template-id tid :de-estado "aguardando_assinatura"
                                          :para-estado "aprovado" :gatilho "emitir"})
    tid))

(defn- texto-parecer [assunto]
  ;; sem sintaxe markdown no cabecalho — mesmo defeito F3/MATA de `artigos->texto` acima.
  (str "Relatório\n\n" assunto "\n\nAnálise\n\nA proposição atende aos requisitos formais e "
       "materiais de admissibilidade regimental, nos termos do Regimento Interno desta Casa."))

(defn- semear-pareceres!
  "3 pareceres — A: rascunho (relator designado, texto em elaboração, SEM transicionar). B: aguardando
  assinatura do relator (relatoria concluída, ainda não emitido). C: emitido — engine leva a 'aprovado'
  (o vocabulario real; 'emitido' e' rotulo de UI), com assinatura (Onda C Slice C4, `assinador-icp`).

  CORRIGIDO (ledger #12, docs/16-ledger-prontidao.md): a 1a redacao designava relator1/2/3 pelos 3
  PRIMEIROS do roster (`vereadores`, ordem de `vereador/listar`), sem vinculo com identidade nenhuma
  — a identidade `:vereador` da demo NUNCA era relatora, e GET /parecer/:id/assinar respondia 404 pro
  login vereador (a jornada J3 morria). `relator-vereador-id` (o vereador ligado a' identidade
  `:vereador`, resolvido em `semear!`) agora e' o relator do parecer B — o UNICO dos 3 num estado
  NAO-terminal ('aguardando_assinatura'): A e' rascunho (ainda sem relatoria concluida) e C ja'
  termina em 'aprovado' (§22.4 eixo F, `legislativo.logic/estados-parecer-terminais` — um parecer
  terminal nao pode mais ser assinado, migration 20260620000019-legislativo-pareceres.up.sql:87).

  CORRIGIDO TAMBEM (ledger #11): `comissao-id` era `(random-uuid)` — guard ref ORFAO, sem FK
  (§22.10) — e a tela `/parecer/:id` mostrava esse UUID cru onde deveria ir o nome da comissao.
  `comissoes` (as 3 comissoes permanentes REAIS da Casa, de `comissoes-permanentes-do-ente`) agora
  fornece o `comissao-id` de cada parecer, um por comissao (A: CCJ, B: Financas, C: Obras — a ORDEM
  de `comissoes` e' por nome, ver `comissoes-permanentes-do-ente`)."
  [repo registro ente template-id vereadores relator-vereador-id comissoes por-ref]
  (let [relator1 (:id (nth vereadores 0)) relator2 relator-vereador-id relator3 (:id (nth vereadores 2))
        comissao1 (:id (nth comissoes 0)) comissao2 (:id (nth comissoes 1)) comissao3 (:id (nth comissoes 2))]
    ;; A — rascunho
    (let [{pcid :id} (repo-leg/iniciar-parecer! repo ente
                       {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id (get por-ref :em-comissoes-1)
                        :comissao-id comissao1 :template-id template-id})]
      (repo-leg/designar-relator! repo ente {:id pcid :relator-id relator1 :updated-by nil :lock-version 0})
      (repo-leg/nova-versao-parecer! repo ente
        {:id (random-uuid) :parecer-id pcid
         :texto-inline (texto-parecer "Trata-se de projeto de lei que dispõe sobre a criação do Conselho Municipal de Mobilidade Urbana.")
         :origem-versao "redacao" :formato "markdown"}))
    ;; B — aguardando assinatura do relator (relator = o vereador da identidade ':vereador' — ledger #12)
    (let [{pcid :id} (repo-leg/iniciar-parecer! repo ente
                       {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id (get por-ref :aguardando-pauta-1)
                        :comissao-id comissao2 :template-id template-id})]
      (repo-leg/designar-relator! repo ente {:id pcid :relator-id relator2 :updated-by nil :lock-version 0})
      (repo-leg/nova-versao-parecer! repo ente
        {:id (random-uuid) :parecer-id pcid
         :texto-inline (texto-parecer "Trata-se de projeto de lei que institui a Semana Municipal de Combate ao Trabalho Infantil.")
         :origem-versao "redacao" :formato "markdown"})
      (repo-leg/transicionar-parecer! repo ente registro
        {:parecer-id pcid :template-id template-id :gatilho "concluir_relatoria" :updated-by nil :contexto {}}))
    ;; C — emitido (aprovado), assinado
    (let [{pcid :id} (repo-leg/iniciar-parecer! repo ente
                       {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id (get por-ref :em-pauta-1)
                        :comissao-id comissao3 :template-id template-id})]
      (repo-leg/designar-relator! repo ente {:id pcid :relator-id relator3 :updated-by nil :lock-version 0})
      (repo-leg/nova-versao-parecer! repo ente
        {:id (random-uuid) :parecer-id pcid
         :texto-inline (texto-parecer "Trata-se de projeto de lei que institui o Programa Municipal de Arborização Urbana.")
         :origem-versao "redacao" :formato "markdown"})
      (repo-leg/transicionar-parecer! repo ente registro
        {:parecer-id pcid :template-id template-id :gatilho "concluir_relatoria" :updated-by nil :contexto {}})
      ;; lock-version FRESCO (nao hardcoded): le' o que o CAS acumulado ate' aqui realmente deixou.
      (let [lv (:lock-version (repo-leg/buscar-parecer repo ente pcid))]
        (repo-leg/emitir-parecer! repo ente registro
          {:parecer-id pcid :template-id template-id :gatilho "emitir" :voto-relator "favoravel"
           :updated-by nil :agora hoje :contexto {} :lock-version lv
           :assinador (assinador-icp/assinador-stub)})))))

;; ---------- pos-aprovacao: 2 autografos visiveis (1 aguardando, 1 sancionado) + 4 normas ----------

(defn- semear-pos-aprovacao!
  [repo ente por-ref]
  (let [{aid-a :id} (repo-leg/gerar-autografo! repo ente
                      {:id (random-uuid) :proposicao-id (get por-ref :aprovada-1) :ano 2026
                       :texto-versao-id (random-uuid) :destinatario-texto "Prefeito Municipal de Fortaleza"})]
    (repo-leg/iniciar-tramitacao-executiva! repo ente {:id (random-uuid) :autografo-id aid-a}))
  (let [{aid-b :id} (repo-leg/gerar-autografo! repo ente
                      {:id (random-uuid) :proposicao-id (get por-ref :aprovada-2) :ano 2026
                       :texto-versao-id (random-uuid) :destinatario-texto "Prefeito Municipal de Fortaleza"})
        {tid-b :id} (repo-leg/iniciar-tramitacao-executiva! repo ente {:id (random-uuid) :autografo-id aid-b})]
    (repo-leg/registrar-resposta-executivo! repo ente {:id tid-b :resultado "sancionado" :updated-by nil :lock-version 0})))

(defn- promulgar-e-publicar!
  "1 autografo PROPRIO (sancionado, so' pra satisfazer o NOT NULL de `legislativo.norma.autografo_id`) +
  promulga + publica. Devolve o `id` da norma."
  [repo ente pid ementa data-promulgacao]
  (let [{aid :id} (repo-leg/gerar-autografo! repo ente
                    {:id (random-uuid) :proposicao-id pid :ano 2026 :texto-versao-id (random-uuid)
                     :destinatario-texto "Prefeito Municipal de Fortaleza"})
        {tid :id} (repo-leg/iniciar-tramitacao-executiva! repo ente {:id (random-uuid) :autografo-id aid})]
    (repo-leg/registrar-resposta-executivo! repo ente {:id tid :resultado "sancionado" :updated-by nil :lock-version 0})
    (let [{nid :id} (repo-leg/promulgar-norma! repo ente
                      {:id (random-uuid) :proposicao-id pid :autografo-id aid :tipo-norma "lei" :ano 2026
                       :uf "CE" :municipio-nome "Fortaleza" :data-promulgacao data-promulgacao
                       :ementa ementa :texto-versao-id (random-uuid)})]
      (repo-leg/publicar-norma! repo ente
        {:id nid :veiculo-publicacao "Diário Oficial do Município de Fortaleza" :updated-by nil :lock-version 0})
      nid)))

(defn- semear-normas!
  "4 normas promulgadas e publicadas — 1 por cada uma das 4 proposicoes 'aprovada' restantes (as 2
  primeiras alimentaram os autografos standalone de `semear-pos-aprovacao!`)."
  [repo ente por-ref materias-por-ref]
  (mapv (fn [ref data]
          (promulgar-e-publicar! repo ente (get por-ref ref) (:ementa (get materias-por-ref ref)) data))
        [:aprovada-3 :aprovada-4 :aprovada-5 :aprovada-6]
        [(LocalDate/of 2026 3 10) (LocalDate/of 2026 4 22) (LocalDate/of 2026 6 5) (LocalDate/of 2026 7 18)]))

;; ---------- a funcao publica ----------

;; ---------- modelos de requerimento do vereador (fatia 2a, mig 0082) ----------

(def modelos-de-requerimento
  "Os modelos com que o vereador da demo redige o requerimento pelo proprio login. `{{vereador}}` e
  `{{data}}` o sistema preenche (autor do login, data do servidor); os demais viram campos do formulario.
  Textos de DEMONSTRACAO no formato usual de requerimento de Camara — a Casa real edita os seus na aba
  'Modelos' do Expediente."
  [{:chave "req-informacao" :nome "Requerimento de informação"
    :corpo-template (str "REQUERIMENTO DE INFORMAÇÃO\n\n"
                         "Senhor Presidente,\n\n"
                         "O(A) Vereador(a) que este subscreve, {{vereador}}, no uso das atribuições que lhe confere o "
                         "Regimento Interno, requer que seja encaminhado a {{destinatario}} pedido de informações "
                         "sobre {{assunto}}.\n\n"
                         "JUSTIFICATIVA\n\n{{justificativa}}\n\n"
                         "Plenário da Câmara Municipal de Fortaleza, {{data}}.\n\n"
                         "{{vereador}}\nVereador(a)")}
   {:chave "req-voto-pesar" :nome "Requerimento de voto de pesar"
    :corpo-template (str "REQUERIMENTO DE VOTO DE PESAR\n\n"
                         "Senhor Presidente,\n\n"
                         "O(A) Vereador(a) que este subscreve, {{vereador}}, requer, ouvido o Plenário, que seja "
                         "consignado em ata voto de profundo pesar pelo falecimento de {{falecido}}, "
                         "dando-se ciência desta homenagem à família, no endereço {{endereco_familia}}.\n\n"
                         "Plenário da Câmara Municipal de Fortaleza, {{data}}.\n\n"
                         "{{vereador}}\nVereador(a)")}
   {:chave "req-generico" :nome "Requerimento (texto livre)"
    :corpo-template (str "REQUERIMENTO\n\n"
                         "Senhor Presidente,\n\n"
                         "O(A) Vereador(a) que este subscreve, {{vereador}}, requer, na forma regimental, {{pedido}}.\n\n"
                         "JUSTIFICATIVA\n\n{{justificativa}}\n\n"
                         "Plenário da Câmara Municipal de Fortaleza, {{data}}.\n\n"
                         "{{vereador}}\nVereador(a)")}])

(defn- semear-modelos-de-requerimento!
  "Cria os `modelos-de-requerimento` que faltam (idempotente por `chave`: nunca sobrescreve um modelo que a
  Casa ja' editou)."
  [repo ente]
  (doseq [{:keys [chave nome corpo-template]} modelos-de-requerimento]
    (when-not (repo-leg/modelo-por-chave repo ente chave)
      (repo-leg/criar-modelo! repo ente {:id (random-uuid) :chave chave :nome nome
                                         :tipo-documento "requerimento_proposicao"
                                         :corpo-template corpo-template :created-by nil}))))

(defn semear!
  "Semeia (ou rele, se ja' semeada) o ACERVO LEGISLATIVO da Casa `ente`. `sistema` e' um sistema
  Component BOOTADO (mesmo contrato de `casa/semear!`) — usa `(:repo-legislativo sistema)` +
  `(:registro-fatos sistema)` (ja' `using`-ados com :datasource/:bus, nada a fiar aqui) + o `:datasource`
  cru so' pra' as 2 leituras que nao tem fn exposta no modulo (`template-do-rito`/`estados-do-template`).

  `identidade-vereador` e' o `:vereador` de `(:identidades (casa/semear! sistema))` — o UUID de
  IDENTIDADE (nao de vereador) do login usado na jornada J3. Resolvido aqui pro vereador-id real via
  `RepoCadastros/vereador-por-identidade` (mesmo seam de `rotas.clj:37`) — ledger #12
  (docs/16-ledger-prontidao.md): sem isso nenhum parecer tinha esse vereador como relator, e
  GET /parecer/:id/assinar respondia 404 pro login vereador.

  IDEMPOTENCIA (mesmo padrao de `casa/ja-semeada?`, carry #6 do briefing — os `db/` de proposicao/
  template/parecer/autografo/norma NAO tem ON CONFLICT): o gate e' a existencia do template
  `rito-chave` v1 neste ente. Se ja' existe, RELE (devolve so' `:template-id`, sem duplicar as 24
  proposicoes) em vez de tentar recriar — chamar de novo NAO cria um segundo acervo.

  Devolve `{:template-id}`."
  [sistema ente identidade-vereador]
  (let [repo (:repo-legislativo sistema)
        registro (:registro-fatos sistema)
        repo-cad (:repo-cadastros sistema)
        ds (get-in sistema [:datasource :ds])
        existente (tenancy/com-tenant* ds ente (fn [tx] (template-do-rito tx ente)))]
    ;; fora do gate do acervo: uma demo semeada antes da fatia 2a tambem ganha os modelos ao re-rodar o seed
    (semear-modelos-de-requerimento! repo ente)
    (if existente
      {:template-id existente}
      (let [vereadores (tenancy/com-tenant* ds ente (fn [tx] (vereador/listar tx ente hoje)))
            relator-vereador-id (:id (repo-cadastros/vereador-por-identidade repo-cad ente identidade-vereador))
            comissoes-reais (tenancy/com-tenant* ds ente (fn [tx] (comissoes-permanentes-do-ente tx ente)))
            _ (when (< (count comissoes-reais) 3)
                (throw (ex-info (str "acervo/semear!: precisa de >=3 comissoes permanentes da Casa — "
                                     "rode casa/semear! primeiro")
                                {:encontradas (count comissoes-reais)})))
            template-id (criar-rito! repo ente)
            por-ref (into {}
                      (map-indexed
                        (fn [i m] [(:ref m) (protocolar-e-tramitar! repo registro ente template-id vereadores i m)])
                        materias))
            materias-por-ref (into {} (map (juxt :ref identity) materias))
            template-parecer-id (criar-template-parecer! repo ente)]
        (semear-pareceres! repo registro ente template-parecer-id vereadores relator-vereador-id comissoes-reais por-ref)
        (semear-pos-aprovacao! repo ente por-ref)
        (semear-normas! repo ente por-ref materias-por-ref)
        {:template-id template-id}))))
