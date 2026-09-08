# Frente — a plataforma local roda sem falha

> **Objetivo (Daouda, 08/09/2026):** uma versão local de O Plenário rodando **sem falha**, e a
> garantia de que **tudo que foi feito até agora está funcionando**.
>
> Isso é maior que "testar a interface". A prontidão provou que **as telas não mentem sobre o que
> exibem**; falta provar que a plataforma **opera** e que **funciona quando alguém age nela** —
> inclusive nas 42 capacidades que existem no backend e não têm botão.

## Eixo de classificação (mudou — e a mudança importa)

O ledger de prontidão usava `MATA`/`CONSTRANGE`/`PASSA`, que é eixo de **demonstração**. Para este
objetivo o eixo é outro:

| Classe | O que é |
|---|---|
| **QUEBRA** | Não funciona, ou funciona errado: escrita que não persiste, estado corrompido, erro 500, dado de outra Casa vazando, worker parado. |
| **FRÁGIL** | Funciona, mas não sobrevive a condição normal: restart, reload, duplo clique, rede lenta, segunda execução. |
| **COSMÉTICO** | Funciona e persiste; o que está errado é rótulo, acento, espaçamento. **Registra, não bloqueia.** |
| **GAP** | Não foi construído, e a tela **diz isso honestamente** ("EM BREVE"). Não é falha. |

A troca é deliberada: no eixo antigo, um enum cru era `CONSTRANGE` e disputava atenção com uma escrita
que não grava. Aqui, cosmético nunca bloqueia e `QUEBRA` sempre bloqueia.

---

# TRILHA 1 — Operação (primeiro, e bloqueia as outras)

**Por que primeiro:** testar escrita numa stack que não fica de pé mede a stack, não a escrita.

**Estado medido em 08/09/2026, 13:5x:** todos os containers `Exited` há ~4h (código 143 = SIGTERM; o
host parou o Docker, não houve crash). A plataforma **não volta sozinha**. E o app deixou
**`Apparent connection leak detected` ×2** nos últimos 200 log lines.

## T1.1 — Sobe do zero e volta sozinha
- `docker compose down -v` → `up -d --build` → `./demo/semear-tudo.sh`, cronometrado.
- **Depois de parar o Docker e religar**, a stack sobe sozinha? `restart: unless-stopped` cobre o `app`;
  **cobre os outros seis?** Verificar serviço a serviço, não presumir.
- Critério: da máquina fria à Casa semeada, **um comando e nenhuma intervenção manual**.

## T1.2 — Zero erro em log durante operação normal
- **`Apparent connection leak detected` é o primeiro alvo.** É aviso do HikariCP: uma conexão saiu do
  pool e não voltou no prazo. Achar o caminho que vaza (a `transacao` que não fecha, ou o SSE segurando
  conexão), e provar o conserto pela ausência do aviso sob carga.
- Varrer log de `app`, `frontend`, `postgres`, `valkey`, `minio` depois de: subir · semear · abrir as
  27 rotas da sonda · rodar T2 e T3. **Critério: nenhum ERROR, nenhuma exception, nenhum leak.**

## T1.3 — Suíte 100% verde (hoje não está)
Sob "sem falha", estes deixam de ser carries e viram escopo:
- **`demo.casa-test/semear-produz-uma-unica-casa`** — falha na asserção `.exists` e depois lança no
  `slurp`, porque `DEMO_ARTIFACTS_DIR` não existe no comando de suíte. Pré-existência já provada em
  worktree do commit anterior. **Consertar o teste ou o comando, não conviver.**
- **`notificacao-autor-test`** — flake: `outbox/drenar!` **não é escopado por `ente-id`** e drena tudo
  do banco compartilhado. Falha em run cheio, passa isolado. É defeito estrutural da suíte.
- Critério: **dois runs cheios consecutivos, ambos 100%.** Um run verde não prova ausência de flake.

## T1.4 — As armadilhas conhecidas não reproduzem
Quatro registradas, que **nenhum teste pega**:
1. **JVM morrendo com SIGBUS** (perf-data mmapeado sob OrbStack/aarch64). Sintoma: todas as telas em
   500 ao mesmo tempo, `docker ps` sem `oplenario-app-1`. Verificar: `docker logs oplenario-app-1 | grep SIGBUS`.
2. **`.next` obsoleto servindo 404 em rota que existe em disco** — `up -d --build` reusa o volume
   anônimo. `docker restart oplenario-frontend-1` resolve. **Confirmar se ainda reproduz**; se sim, é
   `FRÁGIL` e merece conserto, não procedimento decorado.
3. **Token sem `papeis` navegando como sem papel nenhum** — o FE lê papéis do claim, o backend lê do
   banco. Corrigido na semente; **verificar que continua corrigido**.
4. **Restart do `app` apagando a tribuna ao vivo** — *consertado em 07/09 pelo read-model da tribuna.*
   **Provar que não reproduz mais**: fala aberta → `docker restart oplenario-app-1` → o telão volta a
   dizer quem está com a palavra.

## T1.5 — Os workers estão vivos
Relay de outbox e agendador. Critério: um evento emitido **aparece projetado** sem intervenção, e o
`down -v` → `up` não deixa o migratus com lock `-1` preso (armadilha registrada).

---

# TRILHA 2 — As 42 escritas sem botão, por HTTP

**Por que antes da interface:** cobre 42 rotas contra 24, é mais barata por rota, e ataca o que impede
a plataforma de **funcionar** em vez do que impede de **parecer bem**.

**Método:** estender `e2e/.sonda/`, que já roda contra a stack viva, lê ids reais de
`e2e/.artifacts/demo-ids.edn` e **sai `!= 0` com veredicto**. A sonda cobre leitura; ganha escrita.

**Regra que não pode cair:** a sonda **suja o dado**. Ela roda depois de `semear-tudo.sh` e antes de
qualquer verificação de tela, ou numa Casa própria. Nunca contra a Casa que será demonstrada.

| Grupo | Rotas | O que provar |
|---|---|---|
| **A — condução de sessão** | **17** | Abrir sessão → montar pauta → chamada → abrir votação → votar → encerrar votação → tribuna (inscrever, iniciar fala, cronômetro, encerrar) → questão de ordem → incidente → encerrar sessão. **É a jornada que faz a plataforma existir, e nunca foi percorrida ponta a ponta.** |
| **B — participação, servidor** | 8 | Responder e-SIC · decidir recurso · responder LGPD · responder/prorrogar/arquivar ouvidoria · moderar comentário · definir encarregado. **O painel da Mesa já exibe o prazo legal vencendo destes** — provar que o backend cumpre o que a tela cobra. |
| **C — cidadão** | 8 | Abrir pedido e-SIC, recurso, LGPD, ouvidoria, comentar, denunciar, acompanhar. **Atenção:** a tela diz "EM BREVE — exige gov.br". Se o backend aceitar sem gov.br, o `GAP` é só de interface e isso muda o diagnóstico. Se recusar, é `GAP` de verdade. |
| **D — compliance/TCE** | 3 | Validar → submeter → registrar resposta. É o **M6**, marco dado como fechado e nunca exercido pela borda. |
| **E — identidade** | 3 | Criar identidade · conceder acesso · convite. |
| **F — cadastros** | 2 | Vincular identidade a vereador · reassunção de mandato. |
| **G — legislativo** | 1 | Apreciação de tramitação executiva. |

**Cada rota se prova em três lugares:** a resposta HTTP diz que gravou · o banco tem a linha · **uma
leitura subsequente devolve** o que foi gravado. O terceiro é o que pegou os 9 `MATA` da prontidão.

**Caminho de erro é obrigatório**, e para escrita ele é onde mora o risco: corpo inválido · id
inexistente · conflito de estado (encerrar votação já encerrada) · **papel errado** · **Casa errada**
(isolamento multi-tenant) · **repetição** (idempotência: a segunda chamada duplica ou é recusada?).

---

# TRILHA 3 — As 24 escritas alcançáveis, pela interface

O que a T2 não alcança: **o caminho que o usuário percorre**. Uma rota pode responder 201 e o botão
não submeter, a mensagem de erro ser genérica, a tela não recarregar.

### E1 — Cadastros (servidor) · 4
Criar vereador · editar · mandato · licença.
Erros: campo obrigatório vazio · CPF inválido · CPF duplicado · mandato com fim antes do início ·
licença sem mandato · **duplo clique**.

### E2 — Expediente e protocolo (servidor) · 3
Gerar documento de modelo · editar rascunho · **protocolar e numerar**.
Erros: protocolar duas vezes (**numeração gapless: não pode abrir buraco nem duplicar**) · protocolar
o que não está em rascunho · campo `{{ }}` não preenchido.

### E3 — A matéria nasce (servidor) · 2
Criar proposição · editar. Erros: ementa vazia · espécie inválida · autor inexistente · duplo clique.

### E4 — O parecer (servidor + vereador) · 4
Editar · emitir (secretaria) · emitir "meu parecer" (vereador) · dar ciência.
Erros: emitir sem texto · **emitir sem ser relator (posse)** · emitir duas vezes · papéis trocados.

### E5 — Chamada e presença (servidor + vereador) · 6
Presença · confirmar a própria · lote · conduzir chamada · justificar · decidir justificativa · folha.
Erros: presença fora do roster da data · **lote com um id inválido (a transação inteira tem de
falhar)** · chamada em sessão encerrada · justificativa de terceiro · **decidir a própria
justificativa** · folha duas vezes.

### E6 — Votar (vereador) · 1
Erros: sem votação aberta · duas vezes · sessão de que não participa · **sessão secreta sem direito**.

### E7 — Pós-aprovação (servidor) · 2
Autógrafo · resposta do Executivo. Erros: autógrafo de matéria não aprovada · dois autógrafos ·
resposta sem autógrafo.

### E8 — Notificações (vereador) · 1
Marcar lida. Erros: marcar a de outro · duas vezes.

**Verificação de cada escrita, em três lugares:** a tela diz que gravou · o banco tem a linha · **a
tela recarregada (F5) ainda mostra**.

---

# Método comum

- **Reconstrução limpa antes de cada trilha** (`down -v` → `up --build` → `semear-tudo.sh`). Escrita
  suja dado; exploratório sobre dado remendado mede a remenda.
- **Causa raiz verificada na fonte**, nunca "parece que". Foi o que transformou "a tela do vereador
  está errada" em "não existe `GET /sessoes`".
- **Caminhar depois de consertar.** Provado três vezes em 07/09: consertar abre porta. O card de
  próxima sessão trouxe um enum cru junto; o filtro por data ressuscitava sessão cancelada.
- **Cobertura que impede a volta calada.** Todo defeito `QUEBRA` fechado ganha teste que **reprova** se
  ele voltar — o #15 viveu meses com a suíte verde por falta exatamente disso.
- **Registro:** append em `docs/16-ledger-prontidao.md`, uma fase por trilha.

# O que esta frente NÃO faz

As 42 rotas sem botão serão **exercidas** (T2), não **ganham tela**. Construir interface para elas é
decisão de produto do Daouda, com dois `[GAP]` já nomeados que pesam mais que os outros:

1. **A Mesa não conduz a sessão pela interface** — 17 rotas. A plataforma *mostra* a sessão
   acontecendo e não deixa conduzi-la.
2. **O servidor não responde um e-SIC pela interface** — e `/paineis/mesa` exibe o prazo legal vencendo.

# Ordem e critério de pronto

**T1 → T2 → T3.** A frente está pronta quando: a stack sobe de máquina fria com um comando e volta
sozinha após restart · zero ERROR/exception/leak em log sob uso · **dois runs cheios de suíte 100%
verdes** · as 4 armadilhas conhecidas não reproduzem · as 66 escritas exercidas com caminho feliz e de
erro · e todo `QUEBRA` fechado com teste que o impede de voltar.
