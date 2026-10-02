(ns oplenario.cadastros.controllers
  "Orquestracao (fina) do modulo cadastros (§22.10 controllers, ADR-0001): a diplomat/borda HTTP (Task 5)
  depende DESTE ns, nunca do protocolo `RepoCadastros` direto. Fatia de leitura de vereador (Task 4) — a
  agregacao ja mora no Repo (Task 2: `listar-vereadores`/`ficha-vereador` rodam numa UNICA tx). Sem logica
  aqui: nao le `hoje`/`data` (a borda resolve e passa `data` pronta)."
  (:require [oplenario.cadastros.components.repositorio :as repo]))

(set! *warn-on-reflection* true)

(defn listar-vereadores
  "Vereadores da Casa `ente-id` com mandato+cargo-na-Mesa vigentes em `data` — pass-through do Repo."
  [repo-cadastros ente-id data]
  (repo/listar-vereadores repo-cadastros ente-id data))

(defn ficha-vereador
  "Ficha composta {:vereador :mandato :legislatura :comissoes} do vereador `id`, ou nil se nao existe —
  pass-through do Repo (a agregacao ja roda numa unica tx la')."
  [repo-cadastros ente-id id data]
  (repo/ficha-vereador repo-cadastros ente-id id data))

(defn criar-vereador
  "INSERT de vereador (identidade_id NULL, efetivado_em=now()). Devolve {:id} com o id gerado no adapters/in."
  [repo-cadastros ente-id m]
  (repo/criar-vereador! repo-cadastros ente-id m)
  {:id (:id m)})

(defn editar-vereador
  "UPDATE parcial de nome/nome-parlamentar — devolve o update-count (o diplomat mapeia 0 -> 404)."
  [repo-cadastros ente-id id campos]
  (repo/atualizar-vereador! repo-cadastros ente-id id campos))

(defn registrar-mandato
  "Pass-through: {:id} | nil (404) | throws :conflito/mandato-sobreposto (409)."
  [repo-cadastros ente-id m]
  (repo/registrar-mandato! repo-cadastros ente-id m))

(defn registrar-licenca
  "Pass-through: {:id} | nil (404) | throws :conflito/sem-mandato-vigente (409)."
  [repo-cadastros ente-id vereador-id l data]
  (repo/registrar-licenca! repo-cadastros ente-id vereador-id l data))

(defn reassumir-mandato
  "Pass-through: {:id :fim} | nil (404) | throws :conflito/sem-mandato-licenciado,
  :conflito/retorno-anterior-ao-inicio, :conflito/mandato-sobreposto (409). `reassumiu-em` vai CRU (o dia
  da volta) — o -1 dia e' do Repo."
  [repo-cadastros ente-id vereador-id reassumiu-em]
  (repo/reassumir-mandato! repo-cadastros ente-id vereador-id reassumiu-em))

(defn ligar-identidade
  "Pass-through: update-count (0 -> 404 no diplomat) | throws :conflito/identidade-ja-vinculada (409)."
  [repo-cadastros ente-id id identidade-id]
  (repo/ligar-identidade! repo-cadastros ente-id id identidade-id))

(defn legislatura-vigente
  "A legislatura vigente da Casa (p/ o seletor do form de mandato), ou nil."
  [repo-cadastros ente-id]
  (repo/legislatura-vigente repo-cadastros ente-id))

;; ---------- ADR-0020 (Eixo 1): os setores da Casa ----------

(defn listar-setores
  "Os setores da Casa (ativos e inativos) com a lotacao. A borda ja' exigiu `admin_ente`."
  [repo-cadastros ente-id]
  (repo/listar-setores repo-cadastros ente-id))

(defn criar-setor!
  "Cria o setor. Nome repetido -> :conflito/setor-nome-repetido (409)."
  [repo-cadastros ente-id setor]
  (repo/criar-setor! repo-cadastros ente-id setor))

(defn atualizar-setor!
  "Renomeia e/ou desativa; nil = inexistente nesta Casa (404). Nome repetido -> 409."
  [repo-cadastros ente-id id campos]
  (repo/atualizar-setor! repo-cadastros ente-id id campos))

(defn trocar-membros-do-setor!
  "Troca a lotacao inteira. `pessoas` = o conjunto de identidades ATIVAS da Casa (seam do host — cadastros nunca importa
  identidade, §22.10): quem nao esta' nele (cidadao, vinculo suspenso, pessoa de outra Casa, id inventado) recusa o
  pedido inteiro com `:conflito/pessoas-fora-da-casa` (422), nomeando os ids — nunca lota em silencio so' uma parte.
  nil = setor inexistente (404)."
  [repo-cadastros pessoas ente-id id identidades]
  (let [fora (vec (remove pessoas identidades))]
    (when (seq fora)
      (throw (ex-info "pessoas sem vinculo ativo nesta Casa" {:tipo :conflito/pessoas-fora-da-casa :identidades fora})))
    (repo/trocar-membros-do-setor! repo-cadastros ente-id id identidades)))
