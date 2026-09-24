# 23 — Plano: Central da Casa (o cockpit do operador) + trilha preparar → conduzir → fechar

> Origem: áudio de stakeholder repassado por Daouda (24/09/2026) sobre o operador da Câmara ("o Elvis") e a
> TV do plenário no sistema antigo. Continua `docs/22-plano-modo-tv.md` (Modo TV v1, em produção desde
> 24/09/2026). Plano aprovado em 24/09/2026 na ordem abaixo: **fechar os buracos da trilha antes da Central**.

## O que o áudio mudou

1. **Existe um operador**, e ele não é o presidente. O Elvis é o "faz-tudo" da Casa: prepara a sessão, conduz
   (faz o operacional do presidente) e cuida da TV do plenário. No nosso sistema ele é o papel **`secretario`**:
   todas as rotas de condução (transição, chamada, presença, inscrição, tribuna, cronômetro, votação, pauta)
   exigem só esse papel — **nenhuma exige ser presidente**. O acesso já cabe; falta a casa dele.
2. **A TV antiga era operada à mão.** A nossa acompanha a condução sozinha, o que é certo *se o operador
   conduz* — e ele conduz. Isso confirma o desenho do `docs/22`. O que falta é o operador poder **pôr a matéria
   na TV antes de abrir a votação** ("colocava a pauta, exibia a pauta").
3. **A TV mostrava mais sobre as pessoas:** autor do requerimento, foto e mandato do vereador na tribuna.

## Medição (24/09/2026, rota por rota)

O que o operador consegue fazer hoje, pelo código:

| Momento | Capacidade | Backend | Tela |
|---|---|---|---|
| Antes | Agendar sessão | ✅ | ✅ `/agendar-sessao` |
| Antes | **Montar a pauta** (incluir, reordenar, retirar) | ✅ `POST/PATCH/DELETE /sessoes/:id/pauta/itens[/:item-id]` com CAS | ❌ **`/pauta-convocacao` é só leitura** |
| Antes | Convocação | derivada da pauta (não persistida) | ✅ leitura |
| Durante | Abrir / suspender / reabrir / encerrar / não realizada / arquivar | ✅ | ✅ `/conduzir` |
| Durante | Chamada, presença ao vivo, quórum, justificativas | ✅ | ✅ `/chamada` |
| Durante | Tribuna: inscrever, chamar, desistência, cronômetro, aparte, encerrar | ✅ | ✅ `/conduzir` |
| Durante | Votação: matéria, modalidade, quórum, abrir, encerrar, declarar (simbólica) | ✅ | ✅ `/conduzir` |
| Durante | Modo TV / Telão | ✅ | ✅ |
| Durante | **Decisão da Mesa** (questão de ordem) | ✅ `POST /decisoes-mesa` | ❌ sem botão |
| Durante | **Incidente** (vista, verificação de votação, urgência, votação em bloco) | ✅ `POST /incidentes` | ❌ sem botão |
| Durante | **Item extrapauta** com a sessão aberta | ✅ (mesmo POST; só sessão *fechada* recusa) | ❌ sem botão |
| Depois | Folha da sessão (PDF versionado) | ✅ | ✅ `/folha` |
| Depois | Ata | ❌ Track IA | ❌ |

Dois achados que o plano corrige:

- **O comentário de `/pauta-convocacao` está vencido.** Ele diz que a pauta é só leitura porque o `GET` não
  expunha `lock-version`; o conserto T2 (`9051787`) passou a expor. A edição da pauta é **trabalho de
  frontend** sobre backend pronto — e o design já existe (`telas/pauta-convocacao.html`, "pauta reordenável").
- **A decisão da Mesa grava o operador como presidente.** `sessoes/controllers.clj` (`registrar-decisao-mesa`)
  injeta `presidente-id = created-by = ator`, com o carry escrito na docstring. Com o Elvis operando, a ata
  diria que o Elvis decidiu. A tabela já separa as colunas (`presidente_id` ≠ `created_by`, mig 0034) — o
  conserto é de controller, não de schema.

## Decisões (recomendações — confirmar)

1. **Nome e lugar:** "Central da Casa". **Não é** o `console-operador.html` do design system — aquele é o
   console do operador **do SaaS** (nós, supratenant). A Central **é o `/inicio` da persona secretaria**
   (a porta pós-login continua a mesma; o vereador segue indo para a área dele).
2. **Quem decide a questão de ordem:** o formulário pré-seleciona o **Presidente da Mesa** da composição da
   sessão e o operador pode trocar por outro membro (o vice em exercício, p.ex.); o servidor valida que o
   escolhido é da composição daquela sessão. `presidente-id` = o vereador que presidiu; `created-by` = o
   operador. As linhas antigas carregam identidade do operador — hoje só há dado de demo; a fatia decide
   entre regravar o seed ou marcar as antigas, sem apagar histórico (Inv. 10).
3. **Convocação continua derivada.** A trilha mostra "ver/imprimir convocação", sem inventar um estado
   "publicada" que o domínio não tem (antecedência é `[Regimento]`).
4. **"Anunciar item" é ato da Mesa, não controle da TV.** Evento novo em `sessoes` (item em apreciação),
   visível na TV, no telão e útil à ata. Nada de "mostrar qualquer coisa na TV" manual.
5. **Foto do vereador:** upload no cadastro (objeto-store MinIO já existe), dado de agente público em
   exercício. Só entra quando a Câmara mandar as fotos — até lá, as iniciais continuam.

## Fatias (uma branch/PR por fatia; TDD; revisão antes do merge)

### Fatia 1 — Montar a pauta (frontend sobre backend pronto) — ✅ PR #24 (em produção 24/09/2026)

- `/pauta-convocacao` vira editável para a sessão agendada **ou aberta**:
  - **incluir**: proposição (busca no acervo, com atalho para as da coluna "Pronta p/ pauta" do quadro de
    tramitação) ou item de texto (`leitura`, `comunicado`, `homenagem`), escolhendo a fase
    (expediente, grande expediente, ordem do dia, explicações pessoais, tribuna livre);
  - **reordenar** (subir/descer → `nova-ordem`);
  - **retirar** (`exclusao` ou `retirada_pedido_autor`, justificativa opcional).
  - 409 de CAS → recarrega a pauta e avisa ("a pauta mudou, veja de novo"), nunca sobrescreve.
- `/conduzir`: botão **"Incluir item extrapauta"** reusando o mesmo formulário.
- Remove o comentário vencido de `pauta-convocacao/page.tsx`.
- Design: portar a parte editorial que `telas/pauta-convocacao.html` já desenha; gate do checklist.
- Testes: hooks (`use-adicionar-item-pauta`, `use-reordenar-item-pauta`, `use-retirar-item-pauta`) +
  página (fluxo feliz, 409, 400, sessão fechada).

### Fatia 2 — Atos da Mesa no cockpit — ✅ implementada (PR desta fatia)

> Linhas antigas (decisão 2): nenhuma tela registrava decisão da Mesa antes desta fatia e o seed da demo não cria
> nenhuma — não há histórico a migrar. `presidente-id` passa a ser OBRIGATÓRIO no corpo (o vereador que presidiu,
> validado contra a composição da data da sessão → 409); `created-by` segue sendo o operador. A leitura ficou
> `GET /sessoes/:id/atos-mesa` (papel `secretario`), com as duas listas em ordem cronológica.

- **Backend:**
  - autoria correta (decisão 2): `registrar-decisao-mesa` recebe `presidente-id` no corpo, validado contra a
    composição da sessão (seam `roster-da-casa` já injetado); `created-by` segue sendo o ator;
  - **leitura dos atos** — hoje não existe `GET` de decisões nem de incidentes, e o cockpit não conseguiria
    mostrar o que já foi registrado depois de recarregar: `GET /sessoes/:id/atos-mesa` (decisões + incidentes,
    em ordem cronológica, papel `secretario`).
- **Frontend (`/conduzir`):** gaveta "Atos da Mesa" com dois formulários curtos — **Questão de ordem**
  (questão, decisão, fundamentação opcional, quem presidiu, vincular à fala em curso) e **Incidente** (tipo,
  resultado, descrição, matéria opcional, requerente opcional) — e a lista do que já foi registrado.
- Design: mockup da gaveta antes de portar (arquétipo cockpit, receita de formulário curto).
- Testes: controller/http-in (presidente fora da composição → 400; sessão fechada → 409; leitura só do
  tenant), hooks e gaveta.

### Fatia 3 — Central da Casa

- **3a. Medir os sinais** (lição da Onda E: nada entra sem API). Estado da medição inicial:

  | Sinal | Fonte | Situação |
  |---|---|---|
  | Sessão de hoje e próximas | `GET /sessoes`, `/paineis/sli/sessoes` | ✅ existe |
  | Pauta da sessão (vazia = alerta) | `GET /sessoes/:id/pauta` | ✅ existe |
  | Folha gerada? | `GET /sessoes/:id/folhas` | ✅ existe |
  | Justificativas a decidir | `GET /sessoes/:id/justificativas` | ✅ existe (por sessão) |
  | Prazos legais e-SIC / LGPD / ouvidoria | `GET /paineis/pendencias` | ✅ existe |
  | Compliance em aberto | `GET /compliance/painel` | ✅ existe |
  | Comentários para moderar | `GET /moderacao/comentarios` | ✅ existe |
  | Matérias prontas para pauta | `GET /paineis/tramitacao` (coluna "Pronta p/ pauta") | ✅ existe |
  | Documentos recebidos a protocolar | `/legislativo/documentos` | ⚠️ verificar se há filtro |
  | Notificações | `GET /meu/notificacoes` | inbox distinta — só contador (INVENTARIO §5.1) |

- **3b. Design** `telas/central-da-casa.html`: parte de `minhas-pendencias.html` (que o INVENTARIO §5.1 já
  destinou a "home do servidor", com cabeçalho situacional acima da fila):
  - **trilha da sessão do dia:** Agendada → Pauta → Convocação → Chamada/quórum → Em curso → Encerrada →
    Folha, cada etapa com estado e **uma** ação (Montar pauta, Abrir, Conduzir, Abrir Modo TV, Gerar folha);
  - **próximas sessões e prontidão** (pauta vazia vira alerta);
  - **fila de trabalho** com os sinais medidos — cada item sai sozinho pelo evento de conclusão, sem
    botão "feito" (regra de fronteira do §5.1);
  - gate: `GUIDELINES-CHECKLIST.md`, AA em pixel composto nos dois temas, consultores do CLAUDE.md §4.
- **3c. Porte:** view-model puro `lib/central-vista.ts` (testado) + `/inicio` renderiza a Central para a
  persona secretaria; o `TopoInterno` passa a abrir com "Central".

### Fatia 4 — Modo TV, fase 2

- **4a. Autor e mandato:** `ProposicaoResumoPautaOut` ganha o autor (`autor-texto` já existe na proposição);
  a composição ganha o partido — hoje o roster que `sessoes` recebe **não** carrega partido (docstring em
  `sessoes/wire/out.clj`), então o seam do host passa a trazê-lo. Reverte a decisão 2 do `docs/22` com
  pedido de cliente.
- **4b. Anunciar item:** backend (evento de item em apreciação), botão no `/conduzir` ao lado de cada item
  da pauta, fase nova na TV ("Em apreciação": matéria, autor, fase) e no letreiro.
- **4c. Foto do vereador:** upload no cadastro de vereadores, leitura autenticada, exibição na tribuna da
  TV e do telão (e no perfil público, se a Casa autorizar). Bloqueada pelas fotos da Câmara.

### Fatia 5 — Roteiro do Elvis

Passo a passo de um dia de sessão, começando sempre pela Central: preparar (agendar, montar pauta,
convocação) → conduzir (chamada, tribuna, votação, atos da Mesa, Modo TV) → fechar (encerrar, folha).
Com capturas das telas reais.

## Verificação (por fatia)

- `tsc`, `eslint`, `vitest` no frontend; backend pelo CI (Postgres real) — o backend não roda neste
  ambiente (clojars 403 no proxy).
- Harness Playwright com a página real e API simulada, screenshots dos estados, AA medido.
- Teste logado ao vivo em produção depende de `keycloak.calvetec.com.br` liberado no ambiente.

## Fora de escopo (deliberado)

- Controle manual livre da TV ("mostrar qualquer coisa") — a TV segue o que a Mesa faz.
- Ata (Track IA) e console do operador SaaS.
- Convocação "publicada"/assinada como estado persistido.
- TV pública sem login (carry MAJOR-1 de SSE, `docs/22`).
