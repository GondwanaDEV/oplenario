# Revisão adversarial — 8 specs T3

`npx tsc --noEmit` (typescript 5.6.3, container Playwright, `include: t3/*.spec.ts`): **0 erros nos 8 arquivos** — gate provado capaz de reprovar (erro plantado → exit 2). Nenhum `getByTestId` em nenhum spec. Nenhum erro de tipo esconde os defeitos abaixo: todos são semânticos.

---

## E1.spec.ts — 4 defeitos, 2 deles BLOQUEANTES

| # | Linha | Problema | Correção exata |
|---|---|---|---|
| 1 | **143 e 156** | **REPROVA HOJE.** `.ficha h2` renderiza `nomeExibicao = nomeParlamentar ?? nome` (`page.tsx:43-45`). O teste só preenche `#ev-nome`; o alvo `45c0a7d4` tem `nome_parlamentar='Otávio Monteiro'` **no banco (verificado por psql)**. O h2 nunca vira `novoNome`. | Preencher **também** `#ev-parlamentar` com `novoNome`, ou trocar a asserção para o `<dd>`/campo de nome civil. Sem isso o caminho feliz de "editar vereador" nunca passa. |
| 2 | **231, 246, 331, 344** | **ASSERÇÃO QUE NÃO PODE REPROVAR.** `.ficha` contém `.ficha-acoes` (`page.tsx:356-397`), com os botões literais "Registrar **mandato**" e "Registrar **licença**". `toContainText("Mandato")`/`("Licença")` passa **antes** de qualquer escrita. As 2 "telas dizem que gravou" + os 2 F5 do grupo são vácuo. | Mandato: `expect(page.locator(".ficha .chip")).toHaveText("Mandato ativo")` (`estadoChip`, `cadastro-vereadores-vista.ts:72`). Licença: `toHaveText("Licença")` **no chip**, e/ou `expect(botaoLicenca).toBeDisabled()` com `title="Requer um mandato vigente."`. |
| 3 | **374-398** | **EFEITO COLATERAL FORA DO ALVO DECLARADO.** "registrar licenca — duplo clique" licencia de fato `vereadorParaEditarId` (Otávio). O próprio artefato avisa: *"registrar licenca muda o estado do vereador para 'licenciado' e mexe no roster/quorum das sessoes"* e por isso reservou Thiago. Otávio sai do **denominador de quórum** (`cadastros/db/vereador.clj:315-319`) da sessão de E5. | Apontar o duplo-clique para `vereadorSemMandatoId` (que E1 acabou de dar mandato vigente na linha 205), não para Otávio. |
| 4 | 105-120, 289-303, 388-398 | Comentário overclaim, não defeito de asserção. `enviandoRef` **existe** (`lib/use-*.ts`), mas com `dispatchEvent` o 2º clique também pode ser barrado só pelo `disabled={estado==="enviando"}` do re-render. `expect(posts.length).toBe(1)` não distingue os dois mecanismos. | Manter a asserção; ajustar o comentário, ou provar o ref removendo o `disabled` via `page.evaluate` antes do 2º dispatch. |

Verificações a/b/c: completas nos 4 caminhos felizes — mas 2 delas (mandato, licença) são vácuo, ver #2.

---

## E2.spec.ts — 1 defeito estrutural, 1 lacuna declarada

| # | Linha | Problema | Correção exata |
|---|---|---|---|
| 1 | **347-390** e **403-450** | **NÃO É T3 — É T2 REPETIDO.** "conflito de lock_version" e "protocolar duas vezes" fazem **gerar + editar + protocolar inteiramente por `request.post/patch`**. Zero clique, zero tela. O único que se salva é o de linha 317 (o documento vem do fluxo de UI do `describe.serial`). | Para lock_version: gerar o documento **pela UI** (o fluxo feliz já deixa um), e mandar só a 2ª PATCH stale por HTTP. Para "protocolar duas vezes": idem — usar o `docId` do serial e só reenviar o 2º POST. Se não der, mover os dois para o inventário de T2 e não contá-los como cobertura de interface. |
| 2 | 122, 175 | **F5 ausente em 2 das 3 escritas** (gerar, editar) — substituído por `page.request.get`. Justificado no comentário (não há rota `/expediente/[id]`) e o F5 real existe no "protocolar" (linha ~272). | Aceito como está. Registrar explicitamente no relatório que "gerar" e "editar" têm 2/3 verificações, não 3/3. |

Seletores conferidos contra `formulario-preenchimento.tsx`, `preview-documento.tsx`, `tabela-protocolo.tsx`, `bloco-merge.tsx`: **todos existem** (`#assunto`, `#corpo`, `#dados-chave-N`, `.comando-ctx span`, `article.documento[role=document]`, `div.carimbo.reservar`, `div.carimbo .num`, `table.protocolo`, `.bloco-cabeca:has(#preench-titulo) .passo`, "+ Adicionar campo"). `getByRole("button",{name:"Gerar documento"})` não colide com o **link** de mesma nome em `abas-expediente.tsx:20` porque o role difere. Correto.

---

## E3.spec.ts — 1 defeito de prova, resto sólido

| # | Linha | Problema | Correção exata |
|---|---|---|---|
| 1 | **186** | **STATUS CRAVADO A MÃO.** `registrarEscrita({... status: 200 ...})` no teste das 2 abas, enquanto `rA`/`rB` estão em mãos. A linha de prova afirma um status que ninguém mediu. | `status: corpo === corpoA ? rA.status() : rB.status()` — ou iterar sobre `[[rA,corpoA],[rB,corpoB]]`. |
| 2 | 335-346 | **Prova negativa fraca:** `expect(page.locator("#f-autor-id")).toHaveCount(0)` sobre um id que nunca existiu passa até com a página em branco. | Já há o `getByLabel(/^autor$/i)).toBeVisible()` antes — suficiente como âncora. Baixa prioridade. |

Verificado contra o fonte: `ESPECIES` (`formulario-proposicao.tsx:13-22`) tem **exatamente as 8 opções na ordem exata** que a linha 231 afirma. `#f-ementa` label "Ementa" ✔; `#f-especie`/`#f-ano` com `disabled={bloquearIdentidade}` ✔; `td.ementa` ✔; ordenação default do backend é `atualizado_em desc` (`db/proposicao.clj:105-112`), logo a linha nova cai na página 1 ✔. a/b/c completas nos 2 caminhos felizes.

---

## E4.spec.ts — 1 asserção que não pode reprovar

| # | Linha | Problema | Correção exata |
|---|---|---|---|
| 1 | **62 e 104** | **ASSERÇÃO QUE NÃO PODE REPROVAR.** `getByRole("status", { name: "Carregando…" })`. O elemento é `<p role="status">Carregando…</p>` (`parecer/[id]/page.tsx:70`) e `status` é **nameFrom: author** — não recebe nome do conteúdo. O locator **nunca casa**; `toHaveCount(0)` passa sempre, inclusive com a página travada carregando. | `await expect(page.getByText("Carregando…")).toHaveCount(0)` — ou simplesmente esperar o campo real: `await expect(page.getByLabel("Relatório")).toBeVisible({timeout: 30_000})`. |
| 2 | 118-135 | `expect(page.getByRole("alert")).toBeVisible()` sem `.first()` — risco de strict-mode se o form expuser mais de um alert. | `page.getByRole("alert").first()`, ou casar o texto do 403. |
| 3 | 190-217 | `expect(resp.status()).toBe(500)` congela um defeito. Aceito neste projeto (documenta o achado central), mas amarra o spec a um bug: **corrigir o 500 para 409 reprova a suíte**. | Manter, com comentário TODO explícito de que a correção do achado exige editar esta linha. |

Restante conferido: `aria-label="Relatório"`/`"Análise"` (`formulario-parecer.tsx:135,148`), `label[for="voto-favoravel"]` (`:170`), `.comando-ctx` (`:188`), "Emitir parecer"/"Salvar rascunho"/"Pré-visualizar" (`:197,207,215`), `h1 "Não foi possível carregar este parecer"` (`page.tsx:81`). Todos reais. a/b/c completas em T1, T2 e T6.

---

## E5.spec.ts — o spec mais quebrado do lote: 4 defeitos, 2 BLOQUEANTES

| # | Linha | Problema | Correção exata |
|---|---|---|---|
| 1 | **63-64 + 165** | **ID CRAVADO A MÃO + REPROVA HOJE.** `OTAVIO_ID = "45c0a7d4-…"` não vem de `t3-ids.json` — e é **exatamente `e1.vereadorParaEditarId`**. Pior: `OTAVIO_NOME = "Otávio Cesar Monteiro"` é o campo `nome` da API, mas a tela renderiza `nomeParlamentar ?? nome` (`chamada/page.tsx:83`) e no banco `nome_parlamentar='Otávio Monteiro'`. `toContainText("Otávio Cesar Monteiro")` **falha na primeira corrida, sem E1 nenhum**. | Ler do artefato: `const alvo = ids.e5.rosterDaSessaoChamada.find(v => v.vereadorId !== ids.e5.confirmarPresenca.vereadorId && v.vereadorId !== ids.e1.vereadorParaEditarId)`. E remover a asserção de nome, ou casar por `nomeParlamentar ?? nome` lido da própria API. |
| 2 | **392 e 407** | **VIOLAÇÃO DE STRICT MODE.** `getByText("Versão 1", { exact: false })` casa ao menos 2 nós: a nota `"Versão 1 congelada."` (`folha/page.tsx:129`) e o `.versao-num` `"Versão 1"` (`:272`). O teste erra, não reprova por mérito. | `await expect(page.locator(".versao-num", { hasText: "Versão 1" })).toBeVisible()` — ou `.first()`. |
| 3 | **138-139 + 152** | **ASSERÇÃO QUE NÃO PODE REPROVAR (2x).** No "reconduzir": (a) `heading "Chamadas desta sessão"` (`page.tsx:946`) já renderizava desde a 1ª chamada; (b) o F5 checa `"Chamada já registrada."`, que também já era verdade. Nenhuma das duas vê o 2º ato. | Contar os atos: `await expect(page.locator('section[aria-labelledby="atos-titulo"] li')).toHaveCount(2)` antes/depois — ou casar o `ocorridoEm` do ato novo devolvido em `respAto`. |
| 4 | **~340-370 (grupo B)** | **DEPENDÊNCIA DE ORDEM NÃO DECLARADA.** A CTA "Confirme sua presença" só existe com `placar.kind !== "nenhuma"` (`votar/page.tsx:135-141`) e o placar **só vem do SSE, janela de 5 min** (`t3-ids.json/bloqueios["janela-sse-5min"]`, verificado). E5 **não tem `beforeAll` que reabra a votação**; E6 tem. Na ordem alfabética (E5 antes de E6) o grupo B falha por precondição morta. | Copiar o `beforeAll` de E6 para o `describe.serial` do grupo B (abrir votação em `sessaoQueOVotarAbre` com `tokens.secretaria.json`), **ou** rodar E6 antes de E5 e mover o grupo B para logo depois. Ver ordem recomendada abaixo. |
| 5 | 459 | `page.locator("p.gerar-erro")` casa 2 nós potenciais (`erroBaixar` :219, `erroGerar` :228). No cenário só um renderiza, mas é frágil. | `page.locator(".gerar-bloco p.gerar-erro")`. |
| — | 401-420 | O dedup D9 depende de **cair dentro de 30 s** do teste anterior, que faz `reload()` com timeout de 30 s. Risco real de flake. | Encurtar: no teste da 1ª versão, remover o `reload` final e mover a prova de F5 para o dedup. |

Contra o fonte, o que **está certo** em E5: `li[data-v="…"]` (`page.tsx:585-587`), `#motivo-<id>` e `#motivo-<id>-erro` (`:693,736,745`), texto do erro de motivo vazio (`:707`), "Deferir"/"Indeferir" (`:657,918`), "Falta justificada"/"Justificativa pendente de decisão" (`:63-64`), "A sessão não aceita mais alteração de presença." (`:334`), `"Versão 1 congelada."` e `/já havia uma versão gerada/i` (`folha:126-129`). Nada inventado.

---

## E6.spec.ts — sólido; 2 ressalvas

| # | Linha | Problema | Correção exata |
|---|---|---|---|
| 1 | **186 e 200** | Dois `test.skip(true, "…")`. Passam como "skipped" e não provam nada — inflam o placar sem cobertura. | Trocar por `test.fixme(...)`, que é o idioma que E1/E4/E8 já usam para "inalcançável". |
| 2 | 54-84 | `beforeAll` abre votação e presença por `fetch` direto. **É setup declarado**, não escrita sob teste — legítimo, e o mapa pediu isso. Confirmei que não há UNIQUE de "votação aberta por sessão" (`migrations/…021`), então reabrir não estoura. | Nenhuma. Mas: **este `beforeAll` é a precondição de E5 grupo B** — ver ordem. |

`getByRole("region",{name:"Votação ao vivo"})`, `getByText("Ao vivo",{exact:true})`, `getByRole("group",{name:"Seu voto na votação corrente"})`, `/Você votou\s*Sim/`, "Nenhuma votação aberta no momento.", "Não foi possível abrir o cockpit", "Não foi possível confirmar sua identificação agora" — **todos verificados em `votar/page.tsx:110-180`**. a/b/c completas.

---

## E7.spec.ts — 1 defeito que reprova hoje, 1 id cravado

| # | Linha | Problema | Correção exata |
|---|---|---|---|
| 1 | **125 e 198** | **REPROVA HOJE.** `getByRole("status", { name: "Autógrafo gerado e enviado ao Executivo" })` — o nó é `<p role="status">{mensagemStatus}</p>` (`conteudo-pos-aprovacao.tsx:141,178`), e `status` é nameFrom:author. Locator nunca casa → `toBeVisible()` estoura por timeout. Isto derruba **as duas** provas de "a tela diz que gravou" do grupo. | `await expect(page.getByText("Autógrafo gerado e enviado ao Executivo")).toBeVisible()` e idem para "Retorno do Executivo registrado". |
| 2 | **46** | **ID CRAVADO A MÃO.** `ID_SEM_AUTOGRAFO = "f95ac1a5-…"` literal. (Confirmei que o id existe em `e7.candidatosSemAutografo` — mas está copiado, não lido.) | `const ID_SEM_AUTOGRAFO = ids.e7.candidatosSemAutografo.find(c => c.id !== ID_ALVO).id;` |
| 3 | 160, 234, 251 | 3 casos de erro por `fetch` direto. Justificados (a UI muda de branch e o botão some), e o padrão foi estabelecido por E6. Aceito — mas são T2, não T3. | Contabilizar separado: E7 tem **2 escritas por interface** e 3 confirmações por HTTP. |

`"Gerar autógrafo e enviar ao Executivo"` (`:159`), `"Nenhum autógrafo foi gerado ainda para esta matéria."` (`:151`), `"Registrar retorno"` gatilho vs. submit (`:186` some com `{!mostrarForm && …}`; submit em `form-registrar-retorno.tsx:124`), `radiogroup "Resultado do Executivo"` + radio "Sancionado" (`:76,18`), `"Desfecho"` (`:203`), `"A matéria foi sancionada e segue para promulgação/publicação."` (`:212`), `link "Ver pós-aprovação"` (`acoes-card.tsx:25`) — **todos reais**, e o radio não é `opacity:0` (o `.check()` funciona).

---

## E8.spec.ts — 1 defeito que derruba a prova inteira

| # | Linha | Problema | Correção exata |
|---|---|---|---|
| 1 | **77-80, 129, 133** | **CHAVE ERRADA + CADEIA DE ASSERÇÕES VAZIAS.** O backend devolve **`:lida-em`** (kebab) — `paineis/adapters/out/notificacao.clj:31`. O spec lê `corpoResposta.lidaEm` → `undefined` → **linha 79 `toBeTruthy()` reprova**. Se alguém "consertar" removendo o `toBeTruthy`, então `lidaEmDaPrimeiraChamada` fica `undefined`, o `test.skip` da linha 121 **pula o teste de idempotência inteiro**, e a linha 133 (`undefined === undefined`) vira asserção que não pode reprovar. A prova central de E8 — "o COALESCE preservou o 1º carimbo" — depende de uma chave que não existe. | `const corpoResposta = (await resposta.json()) as { id: string; "lida-em": string };` e usar `corpoResposta["lida-em"]` nas 3 linhas (77-80, 129, 133). |
| 2 | 61-63 | `expect(Number(badgeAntes)).toBeGreaterThanOrEqual(1)` — o `.badge` só renderiza com `naoLidas > 0` (`page.tsx:58`), então o `toBeVisible()` anterior já garante ≥1. Redundante, não errado. | Nenhuma. |

Restante verificado em `notificacoes/page.tsx`: `h1 "Notificações"` + `.badge` (`:56-62`), `article.nt.nao-lida` (`:86`), `.nt-lida-marca "Lida"` (`:106`), `.nt-ponto` (`:109`), `"Marcar como lida"` (`:116`). a/b/c completas.

---

## Placar de gravidade

| Spec | Reprova hoje sem tocar em nada | Asserção vácua | Não é interface | Id cravado | Ordem não declarada |
|---|---|---|---|---|---|
| E1 | **sim** (143/156) | **sim** (231/246/331/344) | não | não | escreve p/ E5 |
| E2 | não | não | **sim** (347-450) | não | não |
| E3 | não | fraca (335) | não | não | não |
| E4 | não | **sim** (62/104) | não | zero-uuid, ok | não |
| E5 | **sim** (165, 392/407) | **sim** (139/152) | não | **sim** (63) | **sim** (grupo B ← E6) |
| E6 | não | 2 skips inúteis | setup declarado | não | é a precondição de E5 |
| E7 | **sim** (125/198) | não | 3 casos de erro | **sim** (46) | não |
| E8 | **sim** (79) | **sim** (133, se "consertado" errado) | 1 caso de erro | não | não |

**Quatro specs não passam da primeira corrida** por defeito próprio, independente de ordem: E1, E5, E7, E8.

---

## Ordem de execução recomendada

Playwright roda os arquivos em ordem alfabética e `fullyParallel:false` — a ordem abaixo exige renomear ou passar os specs explicitamente na linha de comando.

| # | Spec | Por que aqui |
|---|---|---|
| 1 | **E6** | Seu `beforeAll` abre uma votação **fresca** em `10000000-…-0211` e emite presença do presidente. É a **única fonte** que reabre o placar dentro da janela SSE de 5 min. Nada depende de E6 antes dele. |
| 2 | **E5** | O grupo B ("confirmar a própria presença") consome a votação que E6 acabou de abrir, e **não sabe reabri-la sozinho**. Tem de correr logo em seguida, dentro dos 5 min. Precisa também rodar **antes de E1**, que renomeia e licencia Otávio. ⚠ Se E5 grupo A gastar >5 min antes de chegar ao B, o B morre mesmo nesta ordem — a correção estrutural é dar a E5 o seu próprio `beforeAll` (defeito #4 de E5). |
| 3 | **E8** | Isolado (`paineis.notificacao_caixa`, fixtures próprias). Não toca nada de ninguém. Cedo porque consome fixture irreversível — falhar cedo economiza a fixture. |
| 4 | **E3** | Cria/edita proposições. Edita `1511bc9e`, que é o objeto da votação de E6 — por isso **depois** de E6 já ter aberto. Só mexe em ementa, não em estado. |
| 5 | **E2** | Documentos + `protocolo_geral`. O único acoplamento é o numerador gapless, compartilhado com as proposições que E3 acabou de criar — rodar depois de E3 deixa a asserção de gapless de E2 medindo um acervo estável. |
| 6 | **E4** | Pareceres. Independente de todos; alvos (`50a690c2`, `ce76c191`) não são tocados por ninguém. |
| 7 | **E7** | Consome `8344f6b3` (autógrafo + tramitação, irreversível). Independente, mas irreversível — tarde. |
| 8 | **E1** | **Último, obrigatoriamente.** Renomeia `45c0a7d4` (o Otávio do roster de E5), altera seu `nome_parlamentar` (linha 181) e o **licencia** (linha 374-398), tirando-o do denominador de quórum. Qualquer spec de sessão que rode depois de E1 lê uma Casa diferente da que o `preparar.mjs` mediu. |

**Dependências duras, em uma linha cada:**
- E5 grupo B ← E6 `beforeAll` (votação aberta viva, janela de 5 min).
- E5 ← **antes** de E1 (nome, nome parlamentar e mandato de Otávio).
- E2 gapless ← depois de E3 (mesmo numerador `protocolo_geral`).
- Todo o resto: sem dependência real — E4, E7, E8 são ilhas.

**Uma ressalva que a ordem não resolve:** enquanto E5 depender de E6 para existir, a suíte não é re-executável isoladamente por spec. A correção certa é E5 abrir a própria votação; a ordem acima é o paliativo até lá.