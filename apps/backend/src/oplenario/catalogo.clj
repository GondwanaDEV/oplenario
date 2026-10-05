(ns oplenario.catalogo
  "Host (§22.10): o CATALOGO DE ACOES inteiro (ADR-0009) — as entradas que cada modulo declara no seu
  `diplomat/catalogo.clj`, agregadas e validadas na carga, e os CONJUNTOS POR PUBLICO. O servidor MCP (B.3) e o agente
  interno so' chegam ao core por aqui; a tela continua pela rota HTTP de mesma acao (`:rotas` de cada entrada).

  Conjunto por publico = o que um agente daquele publico PODE oferecer (o publico vem da credencial delegada,
  ADR-0010); expoe MENOS do que a permissao da pessoa (Eixo 2), e a ferramenta ainda exige o papel dela a cada
  chamada (interseccao, Eixo 3.2). Um papel so' entra no `:papeis` de uma entrada quando as rotas da tela dela (`:rotas`)
  ja' o atendem (o agente nunca le mais do que a tela da pessoa), ou quando o dado e' publico por natureza (as normas
  vigentes da Casa, os vereadores em exercicio).

  Nenhuma ferramenta le a trilha da Casa nem o historico da Clara (ADR-0024: o auditor le os dois pela tela, nunca pela
  Clara; o que a Clara consulta vai ao fornecedor de IA) — `catalogo-consulta-test` reprova a que tentar."
  (:require [oplenario.comunicacao.diplomat.catalogo :as comunicacao]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.catalogo :as catalogo]
            [oplenario.legislativo.diplomat.catalogo :as legislativo]
            [oplenario.normas.diplomat.catalogo :as normas]
            [oplenario.sessoes.diplomat.catalogo :as sessoes]))

(def entradas
  "Todas as entradas, na ordem dos modulos."
  (into [] cat [legislativo/entradas sessoes/entradas normas/entradas comunicacao/entradas]))

(def por-nome (catalogo/validar-catalogo! entradas))

(def conjuntos
  "Publico -> nomes das ferramentas que um agente daquele publico oferece."
  {:secretaria #{"situacao_da_materia" "tramitacao_da_materia" "pauta_da_sessao" "ata_da_sessao" "buscar_dispositivos"
                 "ler_dispositivo"
                 ;; ADR-0019 fatia 2: o caminho da materia. O agente so' PROPOE (a secretaria confirma em /propostas);
                 ;; assinar parecer juridico nao e' ferramenta, e' ato pessoal do advogado.
                 "pareceres_juridicos_da_materia" "pedir_parecer_juridico" "comissoes_da_casa" "vereadores_da_casa"
                 "encaminhar_as_comissoes" "designar_relator"
                 ;; ADR-0020: os comunicados internos. O agente le a caixa SEM marcar e so' PROPOE o envio; a ciencia
                 ;; (`registrar_ciencia`) fica fora de todo conjunto: e' a prova de que a PESSOA reconheceu
                 "ler_caixa" "ler_comunicado" "ler_leitura_do_comunicado" "enviar_comunicado"
                 ;; ADR-0021 Parte B: o julgamento das contas — registrar e notificar sao PROPOSTAS (a secretaria confirma)
                 "contas_da_casa" "prestacao_de_contas" "registrar_prestacao_de_contas" "registrar_notificacao_das_contas"}
   :vereador   #{"situacao_da_materia" "tramitacao_da_materia" "pauta_da_sessao" "ata_da_sessao" "buscar_dispositivos"
                "ler_dispositivo"
                ;; B.6: o requerimento do proprio vereador — o agente so' PROPOE; ele assina na tela (ADR-0012)
                "modelos_de_requerimento" "protocolar_requerimento"
                ;; ADR-0019: o vereador le o que o juridico ja' opinou sobre a materia
                "pareceres_juridicos_da_materia" "vereadores_da_casa"
                ;; ADR-0020: a caixa do vereador e o comunicado que ele propoe enviar (ele confirma na tela)
                "ler_caixa" "ler_comunicado" "ler_leitura_do_comunicado" "enviar_comunicado"
                ;; ADR-0021 Parte B: o vereador le as contas e a ficha (quantos votos rejeitam o parecer)
                "contas_da_casa" "prestacao_de_contas"}
   ;; Fatia 4 da Clara: o juridico, o controle interno (auditor) e o administrador da Casa. So' LEITURA (a credencial
   ;; deste publico nunca recebe `ato`, nem como proposta) e so' o que a tela de algum deles ja' le, mais o que e'
   ;; publico por natureza: as normas da Casa (o texto vigente conferido da LOM e do RI, que o vereador tambem le sem
   ;; tela) e os vereadores em exercicio (portal). A materia, os pareceres juridicos e as contas, so' o juridico
   ;; alcanca (o `:papeis` de cada entrada).
   :consulta #{"situacao_da_materia" "tramitacao_da_materia" "pauta_da_sessao" "ata_da_sessao" "buscar_dispositivos"
               "ler_dispositivo" "vereadores_da_casa" "pareceres_juridicos_da_materia" "contas_da_casa"
               "prestacao_de_contas" "ler_caixa" "ler_comunicado" "ler_leitura_do_comunicado"}
   ;; B.8 (ADR-0013): o agente institucional da Casa (sem pessoa) — le a materia e as normas, e so' deixa RASCUNHO
   :institucional #{"situacao_da_materia" "buscar_dispositivos" "ler_dispositivo" "registrar_nota_tecnica"}})

(defn- publico-do [ator]
  (or (get-in ator [:via :publico])
      (authz/negar! :sem-agente {:motivo "o catalogo so' atende chamada de agente (credencial delegada)"})))

(defn ferramentas
  "As ferramentas que o agente pode oferecer a este `ator` (com `:via`, ADR-0010): o conjunto do publico da
  credencial ∩ o que os papeis da pessoa AGORA alcancam ∩ as classes concedidas a execucao. Descritas como o agente
  as ve (JSON Schema)."
  [ator]
  (let [classes (get-in ator [:via :classes] #{})]
    (->> (get conjuntos (publico-do ator) #{})
         sort
         (map por-nome)
         (filter #(some (set (:papeis ator)) (:papeis %)))
         (filter #(contains? classes (:classe %)))
         (mapv catalogo/descrever))))

(defn executar!
  "Executa a ferramenta `nome` para o `ator` de agente com os `dados` (JSON decodificado, chaves keyword). Ferramenta
  fora do conjunto do publico da credencial nao existe para ele (`:validacao/ferramenta-desconhecida`). nil = nao
  encontrado."
  [deps ator nome dados]
  (let [publico (publico-do ator)
        e (get por-nome nome)]
    (when-not (and e (contains? (get conjuntos publico #{}) nome))
      (throw (ex-info (str "ferramenta desconhecida: " nome) {:tipo :validacao/ferramenta-desconhecida :nome nome})))
    (catalogo/executar e deps ator dados)))

(defn registrador
  "O seam de audit das chamadas de agente (ADR-0010 Eixo 3.5; toda chamada, leitura inclusive, desde a ADR-0024),
  sobre o repositorio da fronteira com a IA: pessoa + agente + execucao + ferramenta + classe + desfecho + o SHA-256
  da saida."
  [repo-integracao-ia]
  (fn [ator e desfecho resultado-sha256]
    (let [via (:via ator)]
      (repo-ia/registrar-chamada-agente! repo-integracao-ia
                                         {:ente-id (:ente-id ator) :execucao-id (:execucao-id via)
                                          :identidade-id (:identidade-id ator) :agente (:agente via)
                                          :ferramenta (:nome e) :classe (name (:classe e)) :desfecho desfecho
                                          :resultado-sha256 resultado-sha256}))))
