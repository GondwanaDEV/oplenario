(ns oplenario.sessoes.components.repositorio
  "Component de PERSISTENCIA do modulo SESSOES — banco DISPONIBILIZADO como Stuart Sierra Component
  (ADR-0001 §3). O protocolo RepoSessoes expoe as ACOES (tenant-aware: trata `com-tenant*` por dentro); o
  record segura o :datasource (via `using`); o db/ e' a IMPL. O controller depende DESTE Component, nunca do
  db/ direto. (Eventos de dominio Sessao*/real-time = eixos posteriores do F4.)"
  (:require [oplenario.kernel.tenancy :as tenancy]
            [oplenario.sessoes.db.gravacao :as gravacao]
            [oplenario.sessoes.db.pauta :as pauta]
            [oplenario.sessoes.db.presenca :as presenca]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sessoes.db.tribuna :as tribuna]
            [oplenario.sessoes.relacoes.presenca :as rel-presenca]))

(defprotocol RepoSessoes
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant — compoe acoes atomicamente.")
  ;; §22.6 eixo A — sessao
  (agendar-sessao! [this ente-id m] "Numera gapless + resolve capabilities + insere 'agendada', atomico.")
  (transicionar-sessao! [this ente-id m] "Move o estado pela maquina (fail-closed) com CAS.")
  (buscar-sessao [this ente-id id])
  (sessoes-da-legislativa [this ente-id sessao-legislativa-id])
  ;; §22.6 eixo B — pauta (camada viva)
  (criar-pauta! [this ente-id m] "Cria a pauta 1:1 da sessao.")
  (buscar-pauta-por-sessao [this ente-id sessao-id])
  (adicionar-item! [this ente-id m] "Insere item (ordem=max+1) + LOGA inclusao, atomico.")
  (reordenar-item! [this ente-id m] "Move item p/ nova ordem + LOGA inversao, atomico.")
  (remover-item! [this ente-id m] "Remocao soft (ativo=false) + LOG, atomico (nunca DELETE).")
  (buscar-item [this ente-id id])
  (listar-itens [this ente-id pauta-sessao-id] "Itens ATIVOS em ordem.")
  (listar-alteracoes [this ente-id pauta-sessao-id])
  ;; §22.6 eixo B — versionamento canonico (snapshots append-only)
  (publicar-versao! [this ente-id m] "Congela a pauta num snapshot canonico (numera local), append-only.")
  (buscar-versao [this ente-id id])
  (listar-versoes [this ente-id pauta-sessao-id])
  (versao-publica-corrente [this ente-id pauta-sessao-id] "Maior numero_versao com publica=true.")
  ;; §22.6 eixo C — presenca e quorum (camada de fatos)
  (registrar-presenca! [this ente-id m] "Grava evento de presenca append-only (entrada/saida/retorno/mudanca).")
  (listar-presenca [this ente-id sessao-id] "Eventos da sessao em ordem cronologica (auditoria).")
  (esta-presente? [this ente-id sessao-id vereador-id instante] "Presenca DERIVADA do ultimo evento ate o instante.")
  (presentes-plenario [this ente-id sessao-id instante] "Quorum presencial em `instante` (insumo da DSL do motor).")
  (presentes-remoto [this ente-id sessao-id instante] "Quorum remoto em `instante`.")
  (criar-justificativa! [this ente-id m] "Abre justificativa de ausencia 'pendente' (ato apartado).")
  (buscar-justificativa [this ente-id id])
  (decidir-justificativa! [this ente-id m] "aprovada|indeferida (terminal) via maquina + CAS.")
  ;; §22.6 eixo D — gravacao (audio/video)
  (registrar-segmento! [this ente-id m] "Grava segmento de gravacao (captura/ingestao); sessao_id opcional (Opcao A).")
  (vincular-segmento! [this ente-id m] "Vincula um segmento a sessao (uma-vez, CAS).")
  (buscar-segmento [this ente-id id])
  (listar-segmentos-da-sessao [this ente-id sessao-id] "Segmentos da sessao em ordem cronologica (read-model).")
  ;; §22.6 eixo F — tribuna: inscricao de oradores (intencao)
  (inscrever! [this ente-id m] "Inscreve um orador (intencao); numera a fila por (sessao, fase). Devolve {:id :ordem}.")
  (buscar-inscricao [this ente-id id])
  (listar-inscricoes [this ente-id sessao-id] "Fila de oradores da sessao (por fase + ordem).")
  (desistir! [this ente-id m] "Move a inscricao para 'desistencia' (terminal) via maquina + CAS."))

(defrecord RepoSessoesPg [datasource bus]
  RepoSessoes
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  (agendar-sessao! [this ente-id m] (transacao this ente-id #(sessao/agendar! % (assoc m :ente-id ente-id))))
  (transicionar-sessao! [this ente-id m] (transacao this ente-id #(sessao/transicionar! % (assoc m :ente-id ente-id))))
  (buscar-sessao [this ente-id id] (transacao this ente-id #(sessao/buscar % ente-id id)))
  (sessoes-da-legislativa [this ente-id slid] (transacao this ente-id #(sessao/listar-por-sessao-legislativa % ente-id slid)))
  (criar-pauta! [this ente-id m] (transacao this ente-id #(pauta/criar-pauta! % (assoc m :ente-id ente-id))))
  (buscar-pauta-por-sessao [this ente-id sessao-id] (transacao this ente-id #(pauta/buscar-pauta-por-sessao % ente-id sessao-id)))
  (adicionar-item! [this ente-id m] (transacao this ente-id #(pauta/adicionar-item! % (assoc m :ente-id ente-id))))
  (reordenar-item! [this ente-id m] (transacao this ente-id #(pauta/reordenar-item! % (assoc m :ente-id ente-id))))
  (remover-item! [this ente-id m] (transacao this ente-id #(pauta/remover-item! % (assoc m :ente-id ente-id))))
  (buscar-item [this ente-id id] (transacao this ente-id #(pauta/buscar-item % ente-id id)))
  (listar-itens [this ente-id pauta-sessao-id] (transacao this ente-id #(pauta/listar-itens % ente-id pauta-sessao-id)))
  (listar-alteracoes [this ente-id pauta-sessao-id] (transacao this ente-id #(pauta/listar-alteracoes % ente-id pauta-sessao-id)))
  (publicar-versao! [this ente-id m] (transacao this ente-id #(pauta/publicar-versao! % (assoc m :ente-id ente-id))))
  (buscar-versao [this ente-id id] (transacao this ente-id #(pauta/buscar-versao % ente-id id)))
  (listar-versoes [this ente-id pauta-sessao-id] (transacao this ente-id #(pauta/listar-versoes % ente-id pauta-sessao-id)))
  (versao-publica-corrente [this ente-id pauta-sessao-id] (transacao this ente-id #(pauta/versao-publica-corrente % ente-id pauta-sessao-id)))
  (registrar-presenca! [this ente-id m] (transacao this ente-id #(presenca/registrar-evento! % (assoc m :ente-id ente-id))))
  (listar-presenca [this ente-id sessao-id] (transacao this ente-id #(presenca/listar-eventos % ente-id sessao-id)))
  (esta-presente? [this ente-id sessao-id vereador-id instante] (transacao this ente-id #(rel-presenca/esta-presente-em? % sessao-id vereador-id instante)))
  (presentes-plenario [this ente-id sessao-id instante] (transacao this ente-id #(rel-presenca/presentes-plenario % sessao-id instante)))
  (presentes-remoto [this ente-id sessao-id instante] (transacao this ente-id #(rel-presenca/presentes-remoto % sessao-id instante)))
  (criar-justificativa! [this ente-id m] (transacao this ente-id #(presenca/criar-justificativa! % (assoc m :ente-id ente-id))))
  (buscar-justificativa [this ente-id id] (transacao this ente-id #(presenca/buscar-justificativa % ente-id id)))
  (decidir-justificativa! [this ente-id m] (transacao this ente-id #(presenca/decidir-justificativa! % (assoc m :ente-id ente-id))))
  (registrar-segmento! [this ente-id m] (transacao this ente-id #(gravacao/registrar-segmento! % (assoc m :ente-id ente-id))))
  (vincular-segmento! [this ente-id m] (transacao this ente-id #(gravacao/vincular-segmento! % (assoc m :ente-id ente-id))))
  (buscar-segmento [this ente-id id] (transacao this ente-id #(gravacao/buscar % ente-id id)))
  (listar-segmentos-da-sessao [this ente-id sessao-id] (transacao this ente-id #(gravacao/listar-segmentos-da-sessao % ente-id sessao-id)))
  (inscrever! [this ente-id m] (transacao this ente-id #(tribuna/inscrever! % (assoc m :ente-id ente-id))))
  (buscar-inscricao [this ente-id id] (transacao this ente-id #(tribuna/buscar-inscricao % ente-id id)))
  (listar-inscricoes [this ente-id sessao-id] (transacao this ente-id #(tribuna/listar-inscricoes % ente-id sessao-id)))
  (desistir! [this ente-id m] (transacao this ente-id #(tribuna/desistir! % (assoc m :ente-id ente-id)))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoSessoesPg nil nil))
