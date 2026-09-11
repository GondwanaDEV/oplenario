(ns oplenario.legislativo.relacoes
  "Funcoes de RELACAO que o legislativo e' dono (§22.5.3 disc.5 / §22.7.5) — o vocabulario com que um
  guard de rito pergunta o que a CASA fez com uma materia. O motor de DSL (tramitacao, autorizacao,
  plenario, compliance) as alcanca POR NOME via o registry injetado pelo host (`sistema.clj` funde os
  mapas `relacoes` de cada modulo no RegistroFatos), nunca por import (§22.10).

  POR QUE ESTE ARQUIVO DEIXOU DE SER UM STUB (decisao 3-B do Daouda). Ate' aqui o catalogo de fatos do
  motor nao tinha UM fato do legislativo: `cadastros` publicava `membros_da_casa`, `é_presidente_da_mesa`,
  `quem_exerce_presidencia`…, `sessoes` publicava a presenca — e NADA que um guard pudesse chamar para
  perguntar sobre a MATERIA. Consequencia direta, medida: todo guard de tramitacao ou era `nil` (passa
  sempre) ou falava de coisa alheia a proposicao. Com isso, quem tem senha de `secretario` levava uma
  materia de 'protocolada' a 'aprovada' em cinco POSTs numa terca a tarde — sem votacao, sem parecer, sem
  sessao, sem ninguem na Casa. E' a forma do achado T3-A1 aplicada ao processo legislativo inteiro.

  A decisao foi a B: a materia nao avanca porque alguem apertou um botao; avanca porque a Casa FEZ O ATO,
  e o rotulo (`proposicoes.estado`) vem atras. Este ns e' o que torna essa frase expressavel COMO DADO —
  o rito do tenant escreve `aprovada_em_votacao(proposicao.id)` no guard da transicao e o motor resolve.
  Nenhuma string de estado ou de gatilho entra aqui (Inv.4): o fato responde sobre o ATO, e QUAL transicao
  ele guarda e' decisao do regimento cadastrado, nao deste codigo.

  FORMA (espelha `cadastros/relacoes/cadastro` e `sessoes/relacoes/presenca`): `(fn tx arg-de-dominio…)`,
  a `tx` do tenant primeiro (FORCE RLS isola a Casa). `ente` NAO e' argumento do DSL (§4-bis/C2 do
  catalogo: a Casa e' 1:1 com o tenant, implicita na tx) — quando a fn de `db/` delegada exige `ente-id`
  explicito, ele e' lido de volta do GUC por `kernel/tenancy/ente-da-sessao`, que LANCA se a tx nao
  estiver em tenant. As assinaturas tipadas vivem em `motor/catalogo` (FUNCOES-RELACAO) e o assert de
  costura do `RegistroFatos` no boot casa fn⋈assinatura — divergencia de nome/aridade NAO sobe.

  DIFERENCA DELIBERADA para as duas relacoes irmas: elas escrevem HoneySQL direto; esta DELEGA ao `db/`
  do proprio modulo. Nao e' inconsistencia — e' que aqui existe fonte para reusar e la' nao existia.
  `db/votacao/aprovada-em-votacao?` ja' e' a fonte unica da pergunta 'a Casa aprovou?' (o autografo de T3-A
  depende dela) e carrega tres exclusoes nada obvias — votacao ainda aberta, votacao anulada, votacao
  corrigida por outra — alem do filtro de `objeto_tipo` polimorfico que inclui `redacao_final` por causa
  do rito de Fortaleza (docs/17 §5.1). Reescrever a consulta aqui criaria uma segunda verdade: o proximo
  conserto daquele predicado (a lista de LIMITES CONHECIDOS na docstring dele ainda esta' aberta) nao
  chegaria ao guard, e o sistema passaria a responder coisas diferentes para a mesma pergunta.

  [3-A FICA PARA DEPOIS — decisao do Daouda] Este ns responde 'a Casa fez o ato?', NUNCA 'quem pode pedir
  este ato?'. A autorizacao POR GATILHO — hoje a borda gateia tudo com o mesmo `exige-papel \"secretario\"`,
  o mesmo de listar proposicoes — entra em OUTRO lugar: uma coluna de politica em `template_transicao`
  (o rito diz quem dispara cada gatilho, Inv.4), avaliada por `motor/politica-dsl` no controller de
  `POST /legislativo/proposicoes/:id/tramitacao`, com `ator`/`recurso` no ambiente. Nao e' guard: guard
  responde sobre o MUNDO e nega com 409 (dominio normal); politica responde sobre o ATOR e nega com 403.
  Colapsar os dois faria a Casa dizer 'este ato nao e' possivel' quando a verdade e' 'voce nao pode pedi-lo'."
  (:require [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.votacao :as votacao]))

(set! *warn-on-reflection* true)

(defn aprovada-em-votacao?
  "A materia `proposicao-id` foi APROVADA pela Casa em votacao? Booleano.

  INVOLUCRO FINO, de proposito: toda a semantica (o que conta como aprovacao, o que fica de fora, quais
  `objeto_tipo` carregam a materia) mora em `db/votacao/aprovada-em-votacao?` e os LIMITES declarados na
  docstring de la' valem INTEGRALMENTE aqui — em especial: 'houve UMA aprovacao' nao e' 'o rito se
  completou' (dois turnos e redacao final passam com um turno so'), e votacao 'simbolica' tem o resultado
  vindo do corpo do request. Este fato eleva a barra de NADA para 'existe o ato'; nao a eleva ate' 'o rito
  inteiro se cumpriu'. Quem quiser exigir mais (2 turnos, parecer de comissao) precisa de fatos NOVOS aqui
  — e o guard do rito os compoe com `e`, sem tocar em codigo.

  Sem `data`: a disciplina 5 pede consulta historica onde a resposta MUDA com a vigencia (mandato, Mesa,
  comissao). Aqui nao muda — votacao encerrada e' ato consumado e append-only; 'foi aprovada em DD/MM' nao
  e' uma pergunta que o rito faca. Acrescentar um `data` decorativo, que nenhum caminho usasse, seria pior
  que a ausencia: o guard pareceria historico sem ser."
  [tx proposicao-id]
  (votacao/aprovada-em-votacao? tx (tenancy/ente-da-sessao tx) proposicao-id))

;; nome canonico (= assinatura em motor/catalogo FUNCOES-RELACAO) -> fn de relacao. O host funde este
;; mapa no RegistroFatos (`sistema/fundir-relacoes`, que FALHA em colisao de nome entre modulos).
(def relacoes
  {"aprovada_em_votacao" aprovada-em-votacao?})
