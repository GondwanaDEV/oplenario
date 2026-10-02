(ns oplenario.encerramento
  "HOST (§22.10) — o PLANO DE DADOS do encerramento de uma Casa (ADR-0018, fatia 2, Eixo 4): a EXPORTACAO completa
  (9.6) e o APAGAMENTO irreversivel depois da guarda. E' raiz de composicao porque cruza todos os schemas de tenant (o
  inventario e' descoberto no catalogo do Postgres), o object storage, o IdP e o satelite de IA — nenhum modulo pode
  fazer isso sem importar os outros. O FLUXO (maquina de estados, rotas, telas, console) chama estas duas funcoes; aqui
  nao ha' rota nem estado de pedido.

  Detalhe de cada parte: `encerramento.inventario` (o que e' da Casa), `encerramento.exportacao` (o ZIP),
  `encerramento.arquivos` (os blobs), `encerramento.apagamento` (a ordem retomavel) e a migration `…171` (a funcao do
  dono que apaga no banco e as salvaguardas conferidas la')."
  (:require [oplenario.admin-sistema.components.repositorio :as repo-admin-sistema]
            [oplenario.auditoria.components.repositorio :as repo-auditoria]
            [oplenario.encerramento.apagamento :as apagamento]
            [oplenario.encerramento.exportacao :as exportacao]))

(set! *warn-on-reflection* true)

(defn- ds-de [deps] (or (:ds deps) (get-in deps [:datasource :ds])))

(defn seams-de-auditoria
  "Os seams `:auditoria` da exportacao a partir dos Components do host: a corrente conferida e os selos do dia pelo
  modulo de auditoria (ADR-0017) e, se `repo-admin-sistema` vier, as ancoras do selo do dia na corrente da Operacao."
  ([repo-aud] (seams-de-auditoria repo-aud nil))
  ([repo-aud repo-op]
   (cond-> {:verificar (fn [ente-id] (repo-auditoria/verificar repo-aud ente-id))
            :selos-do-dia (fn [ente-id] (repo-auditoria/selos-do-dia repo-aud ente-id 1000000))}
     repo-op
     (assoc :ancoras (fn [ente-id]
                       (->> (repo-admin-sistema/atuacao-do-ente repo-op ente-id 1000000)
                            (filter #(= "selo-do-dia-da-trilha" (:acao %)))
                            (sort-by :seq)
                            (map (fn [{:keys [em detalhe selo]}]
                                   {:em em :dia (or (:dia detalhe) (get detalhe "dia"))
                                    :seq (or (:seq detalhe) (get detalhe "seq"))
                                    :selo-do-dia (or (:selo detalhe) (get detalhe "selo"))
                                    :selo-da-operacao selo}))))))))

(defn exportar-casa!
  "A EXPORTACAO COMPLETA da Casa (ADR-0018 Eixo 4.2; 9.6). BLOQUEANTE (rode num executor): le tudo da Casa e grava um
  ZIP em formato aberto no object storage, em `exportacoes/<ente-id>/<exportacao-id>.zip`.

  `deps`:
    :ds            javax.sql.DataSource do pool de runtime (`oplenario_pool`; ou `:datasource` = o Component com `:ds`).
                   Os dados de tenant sao lidos em `com-tenant*` (role `oplenario_app`, RLS) — a exportacao de uma
                   Casa nao enxerga outra.
    :objeto-store  o Component `ObjetoStore` (com `listar`).
    :auditoria     {:verificar (fn [ente-id] -> {:integra :total :cabeca :quebra-em})   ; obrigatorio
                    :selos-do-dia (fn [ente-id] -> [{:dia :seq :selo}])
                    :ancoras (fn [ente-id] -> [{:em :dia :seq :selo-do-dia :selo-da-operacao}])}  ; opcional
                   — monte com `(seams-de-auditoria repo-auditoria repo-admin-sistema)`.
    :agora         (opcional) (fn [] -> Instant).

  Devolve {:chave \"exportacoes/<ente>/<id>.zip\" :sha256 <hex64 do ZIP inteiro> :bytes n :manifesto {...}} — o
  manifesto e' o mesmo `manifesto.json` do ZIP (chaves string, pronto para o jsonb de `admin_sistema.exportacao_casa`).
  Falha = ex-info `:encerramento/exportacao-falhou` com mensagem legivel (nada fica no store; o temporario some)."
  [deps ente-id exportacao-id]
  (exportacao/exportar! (assoc deps :ds (ds-de deps)) ente-id exportacao-id))

(defn apagar-casa!
  "O APAGAMENTO IRREVERSIVEL da Casa (ADR-0018 Eixo 4.5). Antes de tocar qualquer coisa, o BANCO confere: pedido
  `apagar` aprovado por outro operador, Casa suspensa com encerramento em curso (ou ja' encerrada — retomada), e
  exportacao pronta confirmada ha' 90 dias ou mais; senao, ex-info `:encerramento/recusado` com o porque.

  `deps`:
    :ds             javax.sql.DataSource do pool (`oplenario_pool`, que herda `oplenario_operacao` — o unico role com
                    EXECUTE nas funcoes do apagamento); ou `:datasource` = o Component com `:ds`.
    :objeto-store   o Component `ObjetoStore` (com `listar`).
    :idp            o `IdentityProvider` das Casas (o mesmo `:idp` do servidor HTTP) — `apagar-realm!`.
    :plataforma-ia  a `PlataformaIA` (cliente HTTP do satelite) — `apagar-ente`.
  Sem `:idp`/`:plataforma-ia` o passo fica PENDENTE (nunca sucesso fingido).

  Idempotente e RETOMAVEL: rodar de novo depois de uma falha termina o que faltou (o banco ja' apagado apaga zero; o
  realm inexistente e' ok). Falha nos blobs ou no banco LANCA (ex-info `:encerramento/falhou`, com `:passo`); falha no
  IdP ou no satelite vira pendencia no resumo.

  Devolve o RESUMO (o que vai, selado, na atuacao da Operacao e em `admin_sistema.ente.apagamento`):
    {:ente-id                     uuid
     :tabelas                     {\"schema.tabela\" linhas-apagadas ...}  ; TODAS do inventario (zeros inclusive) +
                                                                          ; identidade.identidade (orfas) quando houve
     :linhas-total                n
     :objetos                     n blobs removidos (fora as exportacoes)
     :objetos-fora-da-convencao   [chaves referenciadas fora de `<pasta>/<ente>/`, NAO removidas]
     :exportacoes-apagadas        n
     :realm-apagado?              bool
     :realm                       {:realm :existia?} | {:pendente true :motivo}
     :ia                          {:pendente false :apagados {\"ia.tabela\" n} :total n} | {:pendente true :motivo}
     :exportacao                  {:id :sha256 :confirmada-em} — a exportacao entregue que liberou o apagamento
     :pendencias                  [:realm :ia]  (as que faltam)
     :completo?                   bool}
  O resumo de uma RETOMADA conta so' o que ela fez (o banco ja' apagado vem com zeros): guarde o da 1a execucao e
  atualize so' `:realm`/`:ia`/`:pendencias`."
  [deps ente-id pedido-id]
  (apagamento/apagar! (assoc deps :ds (ds-de deps)) ente-id pedido-id))
