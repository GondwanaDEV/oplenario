# Page Override — App do Vereador (Aposta 2 · mobile)

> **LOGIC:** sobrescreve o `../MASTER.md` para esta superfície. O que não está aqui herda o MASTER.
>
> **Superfície:** `produto/13` módulo **16.7** — app mobile como **entrada principal do vereador**.
> Features: **7.1** app iOS+Android · **7.2** dashboard pessoal (próximas sessões, pautas, minhas
> proposições) `[SSE]` · **7.3** assinatura eletrônica **em 2 toques** · **7.4** push (consolida na
> central 11.6) · **7.5** estatísticas pessoais `[RM]`. É a materialização da **Aposta 2 (experiência
> moderna)** no formato que o vereador toca primeiro. Tags `[DIF]`.
>
> **⛔ Guardrail estrutural (não negociável):** **nada de gabinete** — sem agenda pessoal, demandas de
> bairro, mala direta, CRM eleitoral. Excluído desde a origem (§16.7). O app é **institucional**: a
> câmara, não a campanha. Toda tentação de "feature de gabinete" é recusada aqui.
>
> **Stack deferida (§22.4.4):** RN é candidato natural (app nativo), mas **não decidido**. Isto são
> tokens/layout agnósticos; o protótipo é HTML simulando o formato mobile, não decisão de stack.

---

## 1. Intenção de design

O vereador abre o telefone **minutos antes da sessão**, no corredor, sem tempo. Em um olhar ele
precisa de: *"quando é a próxima sessão, o que está na pauta, o que eu preciso assinar, como vão minhas
proposições."* A tela é **glanceable + ação rápida**, não um painel denso de servidor. Se o servidor
ganha com profundidade, **o vereador ganha com velocidade e dignidade** — assinar em 2 toques no
corredor é a Aposta 2 ficando concreta.

---

## 2. Tipo de produto e estilo
- **Product type:** *Productivity Tool* → **Flat + micro-interactions**, hierarquia clara, cores
  funcionais. Nosso **Accessible & Ethical** + paleta navy/azul do MASTER se mantém.
- **Densidade:** **baixa** (oposto do dashboard do servidor) — cartões grandes, tipografia generosa,
  um foco por tela. Mobile-first de verdade, não desktop encolhido.

---

## 3. Layout — mobile nativo

**Navegação:** **bottom tab bar** (≤5, ícone **+ rótulo**): Início · Pauta · Proposições · Avisos ·
Perfil. Estado ativo destacado (cor + peso). Back previsível (preserva histórico/scroll).

**Home (aba Início), de cima para baixo:**
1. **Header:** saudação ("Olá, Ver. Joana") + avatar + sino (11.6).
2. **Card-herói da próxima sessão** (7.2 `[SSE]`): "Sessão Ordinária · Hoje 19h", countdown ao vivo
   (ponto pulsante), atalho "Ver pauta (12 itens)". É o que ele mais procura.
3. **Ação rápida:** "Assinar (2)" em destaque → abre o sheet de assinatura (7.3).
4. **Minhas proposições** (7.2/7.5 `[RM]`): 2–3 linhas com **status pill** (estágio de tramitação),
   "ver todas".
5. **Resumo pessoal** (7.5 `[RM]`): mini-cards — proposições por status · presença % · pendências.

---

## 4. Componentes-chave

### 4.1 Assinatura em 2 toques (7.3) — o gesto-herói
**Toque 1:** "Assinar (2)" na home → **bottom sheet** lista os documentos (autógrafo, requerimento).
**Toque 2:** "Assinar com ICP-Brasil" → biometria/PIN do device confirma. **Háptico** no sucesso (o
plugin recomenda háptico para confirmações). Dois toques, fim. Assinatura é ICP-Brasil real (§22.5
eixo F) — o "2 toques" é a UX, não atalho de segurança. Confirmação visível pós-assinatura.

### 4.2 Card da próxima sessão `[SSE]`
Tempo real via SSE (§22.6). Mostra estado vivo (em breve / em andamento / encerrada) com ponto
pulsante; durante a sessão vira atalho ao painel/quórum.

### 4.3 Linha de proposição (read-model)
Título + nº + **status pill** do estágio (mesma semântica de cor das outras telas: âmbar=atenção,
verde=ok, neutro=tramitando). Toque → timeline da tramitação (reusa 5.6).

### 4.4 Estados
- **Loading:** skeleton dos cards (>300ms).
- **Vazio (sem sessão):** "Nenhuma sessão agendada" + atalho à pauta.
- **Vazio (nada a assinar):** o atalho "Assinar" some ou vira "Nada pendente ✓".
- **Offline:** banner de estado + dados em cache (push/assinatura aguardam conexão).

---

## 5. Acessibilidade / mobile (reforços — herda o MASTER)
- **Touch 44×44px** mínimo, **gap ≥ 8px** entre alvos (High no checklist do plugin).
- Bottom tab **ícone + rótulo** (nunca só ícone); estado ativo claro.
- **Safe-area** (notch / Dynamic Island / barra de gestos) respeitada.
- `touch-action: manipulation` (sem delay de 300ms); **nada depende de hover**.
- **Dynamic Type** — layout sobrevive a texto ampliado, sem truncar o essencial.
- Back do sistema previsível; gestos do sistema não bloqueados.

---

## 6. Anti-patterns específicos (além do MASTER)
- ⛔ **Qualquer feature de gabinete** (agenda, demandas, CRM, mala direta) — viola o guardrail de origem.
- ❌ Densidade de desktop no mobile — cartões pequenos, listas espremidas.
- ❌ Bottom nav com > 5 itens ou só-ícone.
- ❌ Ação primária dependente de hover/long-press sem alternativa visível.
- ❌ Esconder o resultado da assinatura — confirmação + háptico obrigatórios.

---

## 7. Mockup
`../mockups/app-vereador.html` — dois "telefones": **Home** + **sheet de assinatura em 2 toques**.
Conteúdo ilustrativo; sem inventar pauta/voto reais (`[GAP]`).
