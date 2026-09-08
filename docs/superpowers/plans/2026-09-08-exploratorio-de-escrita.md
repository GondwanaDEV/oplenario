# Frente — teste exploratório das ESCRITAS

> A prontidão verificou que **as telas não mentem sobre o que exibem**. Esta frente verifica se a
> plataforma **funciona quando alguém age nela**. São coisas diferentes, e a segunda nunca foi feita.

## 1. O que já foi verificado, e o que não foi

| | Total | Exercido até 08/09/2026 |
|---|---|---|
| Rotas de **leitura** (GET) | 47 | ~27 pela sonda + as 9 jornadas caminhadas |
| Rotas de **escrita** (POST/PATCH/PUT/DELETE) | **66** | **1** (iniciar fala de tribuna, por `curl`) |

Os 9 defeitos `MATA` mortos eram **todos** da família "a tela afirma o que o dado não sustenta".
Nenhum deles exigia clicar. **Nada nesta plataforma foi verificado sob ação.**

## 2. Inventário — a descoberta que muda a frente

Método: extrair todas as URLs que o frontend de fato chama (`apiFetch`/`fetch`, fora de testes),
normalizar template literals para `:p`, e casar contra as 66 rotas de escrita.

**Instrumento validado** contra um caso conhecido: o cockpit de votação vota por
`/sessoes/:id/votacoes/:votacao-id/meu-voto` (aparece), e **não** por `/votos` (não aparece) — que é a
rota do lado da secretaria. A primeira versão do casamento, por substring solta, dava 58 falsos
positivos; foi descartada.

| | Rotas |
|---|---|
| Escritas **alcançáveis por clique** | **24** |
| Escritas **sem nenhum chamador no frontend** | **42** |

**42 de 66 escritas não têm por onde clicar.** O ledger registrava "13 rotas de condução de sessão sem
tela"; o número real, no conjunto, é três vezes maior.

### As 42 órfãs, classificadas

| Classe | Rotas | O que significa |
|---|---|---|
| **A — a sessão inteira é conduzida sem tela** | **17** | `transicao` (abrir/suspender/encerrar), `votacoes` + `encerramento` + `votos`, `falas` + `cronometro` + `encerrar`, `inscricoes` + `desistir`, `decisoes-mesa`, `incidentes`, `pauta/itens` (POST/PATCH/DELETE), `minha-justificativa`, `gravacoes` + `vincular` |
| **B — participação, lado servidor** | **8** | responder e-SIC, decidir recurso, responder LGPD, responder/prorrogar/arquivar ouvidoria, moderar comentário, definir encarregado |
| **C — cidadão, bloqueado por gov.br** | **8** | abrir pedido e-SIC, recurso, solicitação LGPD, manifestação de ouvidoria, comentar, denunciar, acompanhar matéria (POST/DELETE) |
| **D — compliance / remessa ao TCE** | **3** | validar, submeter, registrar resposta |
| **E — identidade / console do operador** | **3** | criar identidade, conceder acesso, convite |
| **F — cadastros** | **2** | vincular identidade a vereador, reassunção de mandato |
| **G — legislativo** | **1** | apreciação de tramitação executiva |

**A classe A é a mais grave, e é dupla:** o backend conduz a sessão inteira e a Mesa não tem por onde
clicar — mas o **telão exibe** tribuna, pauta e placar. Ou seja: a plataforma **mostra** a sessão
acontecendo e **não deixa** conduzi-la pela interface.

**A classe B tem a mesma forma, e é pior por ser visível:** `/paineis/mesa` exibe "Recurso e-SIC ·
vence em 9 dia(s)" — a tela **avisa do prazo legal** e não existe tela para responder.

## 3. Escopo desta frente

**Dentro:** as **24 escritas alcançáveis por clique**, cada uma pelo caminho feliz e pelos caminhos de
erro. É o único conjunto que um teste *exploratório de interface* pode exercer.

**Fora, e registrado como achado, não como omissão:** as 42 órfãs. Uma capacidade sem interface não é
um defeito que o exploratório acha — é um **`[GAP]` de produto** que este inventário já provou. A
decisão de construir tela para elas é do Daouda e não pertence a esta frente.

## 4. As jornadas de ESCRITA (o roteiro)

Por persona, cada uma com **caminho feliz** e **caminhos de erro**.

### E1 — Cadastros (servidor) · 4 escritas
Criar vereador · editar · registrar mandato · registrar licença.
Erros a forçar: campo obrigatório vazio · CPF inválido · CPF duplicado · mandato com data final antes
da inicial · licença sobrepondo mandato inexistente · **duplo clique** no salvar.

### E2 — Expediente e protocolo (servidor) · 3 escritas
Gerar documento a partir de modelo · editar rascunho · **protocolar e numerar**.
Erros: protocolar duas vezes o mesmo documento (a numeração é gapless — **não pode abrir buraco nem
duplicar**) · protocolar documento que não está em rascunho · campo `{{ }}` do template não preenchido.

### E3 — A matéria nasce (servidor) · 2 escritas
Criar proposição · editar.
Erros: ementa vazia · espécie inválida · autor inexistente · duplo clique.

### E4 — O parecer (servidor + vereador) · 4 escritas
Editar parecer · emitir (secretaria) · emitir "meu parecer" (vereador) · dar ciência.
Erros: emitir parecer sem texto · emitir parecer de que não se é relator (**posse**) · emitir duas
vezes · vereador emitindo pela secretaria e vice-versa.

### E5 — Chamada e presença (servidor + vereador) · 6 escritas
Registrar presença · confirmar a própria presença · lote · conduzir chamada · justificar ausência ·
decidir sobre justificativa · gerar folha.
Erros: presença de vereador fora do roster da data · lote com um id inválido (**a transação inteira
tem de falhar**) · chamada em sessão encerrada · justificativa de terceiro · **decidir a própria
justificativa** (juiz em causa própria) · folha duas vezes.

### E6 — Votar (vereador) · 1 escrita
Registrar meu voto.
Erros: votar sem votação aberta · votar duas vezes · votar em sessão de que não se participa ·
**votar em sessão secreta sem direito**.

### E7 — Pós-aprovação (servidor) · 2 escritas
Gerar autógrafo · registrar resposta do Executivo.
Erros: autógrafo de matéria não aprovada · dois autógrafos · resposta sem autógrafo.

### E8 — Notificações (vereador) · 1 escrita
Marcar como lida. Erros: marcar a de outro · marcar duas vezes.

### E9 — Justificativa e decisão (transversal) — coberto em E5.

## 5. Método

- **Interface, não `curl`.** O objeto do teste é o caminho que o usuário percorre. `curl` só para
  confirmar no banco o que a tela alega ter gravado.
- **Toda escrita se verifica em três lugares:** a tela diz que gravou · o banco tem a linha · a tela
  **recarregada** (F5) ainda mostra. O terceiro é o que pegou os 9 `MATA` da prontidão.
- **Caminho de erro é obrigatório**, não bônus: campo vazio, valor inválido, duplo clique, conflito de
  estado, permissão de outro papel. A pergunta em cada um: *a mensagem diz o que fazer, ou é genérica?*
- **Classificação:** `MATA` (não dá para apresentar) · `CONSTRANGE` (dá, com desculpa) · `PASSA`.
- **Registro:** append em `docs/16-ledger-prontidao.md`, uma fase por jornada, com causa raiz
  verificada na fonte — nunca "parece que".
- **Reconstrução limpa antes de começar** (`down -v` → `up --build` → `semear-tudo.sh`): escrita suja o
  dado, e um exploratório sobre dado remendado mede a remenda.
- **Um `[GAP]` não é defeito.** Se a tela diz "EM BREVE" honestamente, é `PASSA`.

## 6. O que esperar

A caminhada de **leitura** rendeu 6 defeitos em 7 jornadas (3 `MATA`). Escrita tem mais superfície de
falha que leitura — validação, estado, concorrência, permissão, idempotência. **A expectativa razoável
é colheita igual ou maior**, concentrada nos caminhos de erro, que é onde ninguém olhou nunca.
