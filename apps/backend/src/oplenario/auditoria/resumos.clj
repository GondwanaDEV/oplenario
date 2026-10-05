(ns oplenario.auditoria.resumos
  "O RESUMO DO EFEITO por ACAO (ADR-0017 Eixo 1-C): o rotulo, em palavras de servidor de camara, que a trilha da Casa
  grava quando o ato FOI FEITO e o handler nao deixou rotulo proprio.

  Duas tabelas, ambas dado puro, ambas conferidas contra a tabela de rotas do host por teste estrutural
  (`oplenario.auditoria.resumo-de-toda-escrita-test`): rota de escrita nova sem entrada em NENHUMA das duas reprova o CI.
  - `por-acao`: acao (o `:route-name` da rota) -> rotulo. E' o resumo do efeito DEFAULT;
  - `sem-resumo`: acao -> o MOTIVO de a rota nao ter rotulo (ator que nao entra na corrente da Casa, ou frente em curso
    em outra branch). Motivo vazio reprova.

  Regras do rotulo (o teste confere o que da' para conferir):
  - verbo no passado + o que foi feito (\"Publicou a pauta da sessao\"), no vocabulario do servidor da Casa;
  - SEM nome de rota, SEM enum cru, SEM UUID, SEM texto de pedido, de ementa ou de justificativa, e SEM o que o ato
    escolheu (o voto, o teor): o rotulo diz O QUE foi feito, nunca o conteudo;
  - SEM `:campos`: o nome dos campos alterados depende do corpo de cada pedido, e uma lista fixa por rota afirmaria
    mudanca que pode nao ter havido — quem sabe os campos e' o handler (como o dos setores e o da pauta).

  O rotulo do HANDLER, quando existe (\"Contas 2025 (...)\", o protocolo, o setor), sempre vence este: e' o rotulo do
  OBJETO; o daqui e' o do ATO, para o que o handler nao rotulou.

  So' o ato que ACONTECEU ganha o rotulo daqui (desfecho `permitido`): a negacao, a falha e a tentativa sem desfecho
  nao dizem em palavras que o ato foi feito — a tela os mostra como \"Negado\", \"Nao concluiu\" e \"Sem desfecho\".

  O que cada registro ja' gravado diz NAO muda: o rotulo entra no selo do registro, entao so' os registros novos o
  levam (a corrente e' append-only e nao se reescreve). Um registro antigo sem rotulo segue sem rotulo."
  (:require [clojure.string :as str]))

(def por-acao
  {;; ---- o assistente da Casa (a Clara, ADR-0024) ----
   ;; a frase e' fixa: o texto da pergunta nunca entra na trilha (o handler deixa so' o hash da interacao guardada)
   :agente/perguntar                            "Perguntou à Clara"
   :agente/reportar-erro-ia                     "Reportou um erro numa resposta da IA"

   ;; ---- cadastros ----
   :cadastros/atualizar-setor                   "Atualizou um setor da Casa"
   :cadastros/criar-setor                       "Criou um setor da Casa"
   :cadastros/criar-vereador                    "Cadastrou um vereador"
   :cadastros/editar-vereador                   "Editou o cadastro de um vereador"
   :cadastros/ligar-identidade                  "Ligou o cadastro de um vereador à pessoa com acesso"
   :cadastros/reassumir-mandato                 "Registrou a reassunção do mandato de um vereador"
   :cadastros/registrar-licenca                 "Registrou uma licença de vereador"
   :cadastros/registrar-mandato                 "Registrou um mandato de vereador"
   :cadastros/trocar-membros-do-setor           "Trocou os membros de um setor"

   ;; ---- compliance ----
   :compliance/resposta-remessa                 "Registrou a resposta do TCE a uma remessa"
   :compliance/submeter-remessa                 "Submeteu uma remessa ao TCE"
   :compliance/validar-remessa                  "Validou uma remessa ao TCE"

   ;; ---- comunicados ----
   :comunicacao/anexar                          "Anexou um arquivo a um comunicado"
   :comunicacao/ciencia                         "Deu ciência de um comunicado"
   :comunicacao/enviar                          "Enviou um comunicado"

   ;; ---- exportação ao encerrar a Casa ----
   :exportacao-da-casa/confirmar-recebimento    "Confirmou o recebimento da exportação completa"
   :exportacao-da-casa/gerar                    "Pediu a exportação completa da Câmara"

   ;; ---- acesso e identidade ----
   :identidade/conceder-acesso                  "Concedeu um acesso à Casa"
   :identidade/criar-identidade                 "Cadastrou uma pessoa no sistema"
   :identidade/desligar-agente-institucional    "Desligou um agente institucional de IA"
   :identidade/ligar-agente-institucional       "Ligou um agente institucional de IA"
   :identidade/logout-sessao                    "Saiu do sistema"
   :identidade/mint-sessao                      "Entrou no sistema"
   :identidade/reenviar-convite                 "Reenviou o convite de acesso"
   :identidade/revogar-acesso                   "Revogou um acesso à Casa"

   ;; ---- agente de IA no servidor de ferramentas ----
   :integracao-ia/mcp                           "Um agente de IA usou uma ferramenta do sistema"

   ;; ---- legislativo ----
   :legislativo/abrir-votacao                   "Abriu uma votação"
   :legislativo/acusar-ciencia                  "Acusou ciência de um parecer"
   :legislativo/anexar-documento-contas         "Anexou um documento às contas"
   :legislativo/apreciar-veto                   "Registrou a apreciação de um veto"
   :legislativo/assinar-parecer-juridico        "Assinou um parecer jurídico"
   :legislativo/atualizar-modelo-documento      "Atualizou um modelo de documento"
   :legislativo/cancelar-pedido-juridico        "Cancelou um pedido de parecer jurídico"
   :legislativo/copiloto-analise-parecer        "Pediu a análise do copiloto de IA para um parecer"
   :legislativo/copiloto-requerimento           "Pediu ao copiloto de IA o preenchimento de um requerimento"
   :legislativo/criar-modelo-documento          "Criou um modelo de documento"
   :legislativo/criar-proposicao                "Criou uma proposição"
   :legislativo/criar-proposta-requerimento     "Criou uma proposta de requerimento para colher subscrições"
   :legislativo/decidir-nota-tecnica            "Decidiu sobre uma nota técnica da IA"
   :legislativo/designar-relator                "Designou o relator de um parecer"
   :legislativo/editar-contas                   "Editou o registro das contas de um exercício"
   :legislativo/editar-documento                "Editou um documento"
   :legislativo/editar-proposicao               "Editou uma proposição"
   :legislativo/emitir-parecer                  "Emitiu um parecer de comissão"
   :legislativo/encaminhar-comissoes            "Encaminhou uma proposição às comissões"
   :legislativo/encerrar-votacao                "Encerrou uma votação"
   :legislativo/gerar-autografo                 "Gerou o autógrafo de uma matéria aprovada"
   :legislativo/gerar-documento                 "Gerou um documento"
   :legislativo/meu-copiloto-analise-parecer    "Pediu a análise do copiloto de IA para o próprio parecer"
   :legislativo/meu-emitir-parecer              "Emitiu o próprio parecer de comissão"
   :legislativo/meu-pedido-juridico             "Pediu um parecer jurídico para o próprio parecer"
   :legislativo/meu-salvar-rascunho-parecer     "Salvou o rascunho do próprio parecer"
   :legislativo/meu-voto                        "Registrou o próprio voto em uma votação"
   :legislativo/notificar-contas                "Registrou a notificação do Executivo sobre as contas"
   :legislativo/pedir-parecer-juridico          "Pediu um parecer jurídico"
   :legislativo/previa-requerimento             "Pediu a prévia de um requerimento"
   :legislativo/promulgar-norma                 "Promulgou uma norma"
   :legislativo/protocolar-documento            "Protocolou um documento"
   :legislativo/protocolar-proposta-requerimento "Protocolou uma proposta de requerimento"
   :legislativo/protocolar-requerimento         "Protocolou um requerimento"
   :legislativo/publicar-norma                  "Registrou a publicação de uma norma"
   :legislativo/publicar-resumo                 "Publicou o resumo para o cidadão de uma proposição"
   :legislativo/receber-movimentacao            "Registrou o recebimento de uma proposição"
   :legislativo/registrar-contas                "Registrou as contas de um exercício"
   :legislativo/registrar-resposta-executivo    "Registrou a resposta do Executivo a um autógrafo"
   :legislativo/registrar-voto                  "Registrou o voto de um vereador pela Mesa"
   :legislativo/responder-subscricao            "Respondeu a um pedido de subscrição de requerimento"
   :legislativo/salvar-parametros-de-contas     "Salvou os prazos do julgamento das contas da Casa"
   :legislativo/salvar-parametros-parecer-juridico "Salvou a regra do parecer jurídico da Casa"
   :legislativo/salvar-parecer-juridico         "Salvou o rascunho de um parecer jurídico"
   :legislativo/salvar-rascunho-parecer         "Salvou o rascunho de um parecer de comissão"
   :legislativo/substituir-parecer-juridico     "Substituiu um parecer jurídico"
   :legislativo/tramitar-proposicao             "Tramitou uma proposição"
   :legislativo/usar-nota-como-rascunho         "Aproveitou uma nota técnica da IA como rascunho de parecer jurídico"

   ;; ---- normas de referência da Casa ----
   :normas/conferir                             "Conferiu uma versão de norma da Casa"
   :normas/importar-versao                      "Importou uma versão de norma da Casa"

   ;; ---- painéis ----
   :paineis/marcar-notificacao-lida             "Marcou uma notificação como lida"

   ;; ---- participação: balcão de atendimento (a Casa) ----
   :participacao/anexar-esic                    "Anexou um arquivo à resposta de um pedido de e-SIC"
   :participacao/anexar-lgpd                    "Anexou um arquivo à resposta de uma solicitação de LGPD"
   :participacao/anexar-ouvidoria               "Anexou um arquivo à resposta de uma manifestação da ouvidoria"
   :participacao/arquivar-manifestacao          "Arquivou uma manifestação da ouvidoria"
   :participacao/complementar-esic              "Complementou a resposta de um pedido de e-SIC"
   :participacao/complementar-lgpd              "Complementou a resposta de uma solicitação de LGPD"
   :participacao/complementar-ouvidoria         "Complementou a resposta de uma manifestação da ouvidoria"
   :participacao/decidir-recurso                "Decidiu um recurso de e-SIC"
   :participacao/definir-encarregado            "Definiu o encarregado de dados (LGPD) da Casa"
   :participacao/indeferir-pedido               "Indeferiu um pedido de e-SIC"
   :participacao/indeferir-solicitacao          "Indeferiu uma solicitação de LGPD"
   :participacao/moderar-comentario             "Moderou um comentário de cidadão"
   :participacao/prorrogar-manifestacao         "Prorrogou o prazo de uma manifestação da ouvidoria"
   :participacao/prorrogar-pedido               "Prorrogou o prazo de um pedido de e-SIC"
   :participacao/responder-manifestacao         "Respondeu a uma manifestação da ouvidoria"
   :participacao/responder-pedido               "Respondeu a um pedido de e-SIC"
   :participacao/responder-solicitacao          "Respondeu a uma solicitação de LGPD"
   :participacao/retirar-anexo-esic             "Retirou um anexo de um pedido de e-SIC"
   :participacao/retirar-anexo-lgpd             "Retirou um anexo de uma solicitação de LGPD"
   :participacao/retirar-anexo-ouvidoria        "Retirou um anexo de uma manifestação da ouvidoria"
   :participacao/substituir-anexo-esic          "Substituiu um anexo de um pedido de e-SIC"
   :participacao/substituir-anexo-lgpd          "Substituiu um anexo de uma solicitação de LGPD"
   :participacao/substituir-anexo-ouvidoria     "Substituiu um anexo de uma manifestação da ouvidoria"

   ;; ---- participação: o cidadão no portal ----
   :participacao/anexar-meu-esic                "Anexou um arquivo ao próprio pedido de e-SIC"
   :participacao/anexar-meu-lgpd                "Anexou um arquivo à própria solicitação de LGPD"
   :participacao/anexar-meu-ouvidoria           "Anexou um arquivo à própria manifestação da ouvidoria"
   :participacao/comentar                       "Comentou uma matéria no portal"
   :participacao/denunciar-comentario           "Denunciou um comentário no portal"
   :participacao/interpor-recurso               "Interpôs recurso de um pedido de e-SIC"
   :participacao/protocolar-esic                "Protocolou um pedido de e-SIC"
   :participacao/protocolar-manifestacao        "Protocolou uma manifestação na ouvidoria"
   :participacao/solicitar-titular              "Abriu uma solicitação de LGPD"
   :transparencia/deixar-de-seguir              "Deixou de acompanhar uma matéria"
   :transparencia/seguir                        "Passou a acompanhar uma matéria"

   ;; ---- propostas de ato do assistente ----
   :propostas/confirmar                         "Confirmou uma proposta de ato do assistente"
   :propostas/recusar                           "Recusou uma proposta de ato do assistente"

   ;; ---- sessões ----
   :sessoes/abrir-justificativa                 "Abriu uma justificativa de ausência"
   :sessoes/abrir-minha-justificativa           "Abriu a própria justificativa de ausência"
   :sessoes/adicionar-item-pauta                "Incluiu um item na pauta"
   :sessoes/agendar                             "Agendou uma sessão"
   :sessoes/anunciar-item-pauta                 "Anunciou um item da pauta"
   :sessoes/atualizar-audiencia                 "Atualizou os dados de uma audiência pública"
   :sessoes/chamar-cidadao                      "Chamou um cidadão inscrito na audiência pública"
   :sessoes/conduzir-chamada                    "Conduziu a chamada de presença da sessão"
   :sessoes/confirmar-minha-presenca            "Confirmou a própria presença na sessão"
   :sessoes/decidir-justificativa               "Decidiu uma justificativa de ausência"
   :sessoes/definir-regra-da-pauta              "Definiu a regra de publicação da pauta"
   :sessoes/definir-tempos-regimentais          "Definiu os tempos regimentais da tribuna"
   :sessoes/desistir-da-inscricao               "Desistiu da própria inscrição na audiência pública"
   :sessoes/desistir-inscricao                  "Registrou a desistência de um orador inscrito"
   :sessoes/encerrar-fala                       "Encerrou a fala de um orador"
   :sessoes/encerrar-fala-cidada                "Encerrou a fala de um cidadão na audiência pública"
   :sessoes/gerar-folha                         "Gerou a folha de presença da sessão"
   :sessoes/ingerir-gravacao                    "Recebeu a gravação de uma sessão"
   :sessoes/iniciar-fala                        "Deu a palavra a um orador"
   :sessoes/inscrever-na-audiencia              "Inscreveu um cidadão na audiência pública"
   :sessoes/inscrever-orador                    "Inscreveu um orador na tribuna"
   :sessoes/inscrever-pelo-portal               "Inscreveu-se em uma audiência pública"
   :sessoes/publicar-ata                        "Publicou a ata da sessão"
   :sessoes/publicar-pauta                      "Publicou a pauta da sessão"
   :sessoes/registrar-ausencia-cidada           "Registrou a ausência de um cidadão inscrito na audiência pública"
   :sessoes/registrar-decisao-mesa              "Registrou uma decisão da Mesa"
   :sessoes/registrar-evento-cronometro         "Operou o cronômetro de uma fala"
   :sessoes/registrar-incidente                 "Registrou um incidente na sessão"
   :sessoes/registrar-leitura-ata               "Registrou a leitura da ata anterior"
   :sessoes/registrar-presenca                  "Registrou a presença de um vereador"
   :sessoes/registrar-presenca-lote             "Registrou a presença dos vereadores de uma vez"
   :sessoes/remover-item-pauta                  "Retirou um item da pauta"
   :sessoes/reordenar-item-pauta                "Reordenou um item da pauta"
   :sessoes/solicitar-rascunho-ata              "Pediu à IA o rascunho da ata"
   :sessoes/transicionar                        "Mudou a situação da sessão"
   :sessoes/vincular-gravacao                   "Vinculou uma gravação à sessão"})

(def ^:private motivo-operacao
  "Ator da Operação da plataforma: nao entra na corrente da Casa (ADR-0016); a atuação dele fica na corrente da Operação")

(def ^:private motivo-segredo-do-satelite
  "Chamada do satélite de IA autenticada pelo segredo compartilhado, sem ator da Casa: não entra na trilha")

(def ^:private motivo-antes-da-entrada
  "Porta pública da entrada pelo CPF (ADR-0025), antes de haver sessão: sem ator e sem Casa, nada entra na trilha")

(def sem-resumo
  {:identidade/localizar-casas                  motivo-antes-da-entrada
   :admin-sistema/aprovar-pedido                motivo-operacao
   :admin-sistema/definir-destino-acervo        motivo-operacao
   :admin-sistema/gerar-exportacao              motivo-operacao
   :admin-sistema/iniciar-encerramento          motivo-operacao
   :admin-sistema/logout-sessao                 motivo-operacao
   :admin-sistema/mint-sessao                   motivo-operacao
   :admin-sistema/pedir-apagamento              motivo-operacao
   :admin-sistema/pedir-suspensao               motivo-operacao
   :admin-sistema/provisionar-casa              motivo-operacao
   :admin-sistema/reativar-casa                 motivo-operacao
   :admin-sistema/recusar-pedido                motivo-operacao
   :admin-sistema/reenviar-convite              motivo-operacao
   :admin-sistema/registrar-oficio-de-recebimento motivo-operacao
   :admin-sistema/reprovisionar-realm           motivo-operacao
   :admin-sistema/retomar-apagamento            motivo-operacao
   :integracao-ia/credencial-institucional      motivo-segredo-do-satelite
   :integracao-ia/receber                       motivo-segredo-do-satelite
   :integracao-ia/revogar-credencial-institucional motivo-segredo-do-satelite})

(defn rotulo-da-acao
  "O rotulo default da acao (`:modulo/nome`, keyword), ou nil."
  [acao]
  (get por-acao acao))

(defn rotulo-fraco
  "Por que `rotulo` nao serve como resumo em palavras (string), ou nil se serve. Usado pelo teste estrutural."
  [rotulo]
  (cond
    (not (string? rotulo)) "nao e' texto"
    (str/blank? rotulo) "vazio"
    (not (re-find #"^\p{Lu}" rotulo)) "nao comeca por maiuscula"
    (re-find #"[/_:]" rotulo) "tem barra, sublinhado ou dois-pontos: parece nome de rota ou enum"
    (re-find #"(?i)[0-9a-f]{8}-[0-9a-f]{4}" rotulo) "tem UUID"
    (re-find #"\(s\)|\(a\)|\(es\)" rotulo) "tem plural entre parenteses"
    (re-find #"[.!?]$" rotulo) "termina em pontuacao"
    (re-find #"\p{Ll}-\p{Ll}+-\p{Ll}" rotulo) "tem palavras ligadas por hifen: parece nome de rota"
    (> (count rotulo) 90) "longo demais para uma linha da tabela"))
