# Page Override — Sessão ao Vivo (telão / painel eletrônico)

> **LOGIC:** sobrescreve o `../MASTER.md` para esta superfície. O que não está aqui herda o MASTER.
>
> **Superfície:** `produto/13` módulo **16.4** — a sessão acontecendo. Mockup = o **telão / painel
> eletrônico de votação** (4.2). Features: **4.2** painel de votação · **4.3** votação nominal /
> simbólica / **secreta** · **4.4** quórum em tempo real · **4.5** presença · **4.6** cronômetro de
> tribuna. `[PAR]+[DIF]` `[SSE]` (real-time via SSE, §22.6).
>
> **É a superfície que dá nome ao produto** — *"onde a câmara acontece."* O momento mais visível e de
> maior pressão (SLA de janela de sessão, §22.1 inv.9). Aqui confiança operacional = **não falhar ao vivo**.
>
> **Stack deferida (§22.4.4):** tokens/layout; protótipo agnóstico.

---

## 1. Intenção de design

Esta tela é projetada num **telão/TV no plenário**, lida **a distância** por vereadores, plateia e
imprensa — e espelhada no portal para quem assiste de casa. Logo: **tipografia enorme, contraste
máximo, um foco absoluto** (a matéria em votação + o placar). Zero densidade de dashboard. Em um
relance, de qualquer canto da sala: *o que se vota, como está o placar, quem já votou.*

---

## 2. Desvio deliberado de tema (sancionado)

O MASTER é claro (Accessible & Ethical). **Esta superfície é a exceção: tema escuro de alto contraste.**
Justificativa: **legibilidade de projeção** — fundo escuro com números claros lê melhor num telão a
distância e sob luz de plenário (o plugin recomenda *Real-Time Monitoring → Dark + status colors*).
**Não é dark-mode por estética** (anti-pattern do MASTER) — é requisito de contexto de exibição.
A versão **web para o cidadão** que assiste de casa usa o tema claro padrão (acessibilidade pessoal).

---

## 3. Layout — telão

**Barra superior:** identidade da câmara · **"SESSÃO ORDINÁRIA · AO VIVO"** (ponto pulsante) · relógio ·
**quórum em tempo real (4.4)** "9/11 presentes" com sinal de atingido/não-atingido.

**Centro (foco absoluto):**
1. **Matéria em votação:** tipo + nº + título grande + modo ("VOTAÇÃO NOMINAL ABERTA").
2. **Placar grande (4.2):** SIM · NÃO · ABSTENÇÃO — números gigantes, **cor + ícone + rótulo**.
3. **Grade de vereadores (4.5):** 1 card por vereador com nome + voto (SIM/NÃO/ABST/aguardando),
   atualizando ao vivo. Presença e voto são **eventos append-only** (auditável).
4. **Rodapé:** progresso "9 de 11 votaram · faltam 2".

**Modos da DSL de plenário (mesmo motor, §22.6/§22.7.5 S4):**
- **Nominal:** mostra voto individual na grade.
- **Simbólica:** mostra só o resultado agregado.
- **Secreta (4.3):** **esconde o voto individual** — só o placar; votos secretos em tabela separada
  (§22.4). A grade vira "votou/não votou", sem o teor.
- **Cronômetro de tribuna (4.6):** quando em discussão, o centro vira o tempo do orador (regra de
  tempo = config no motor).

---

## 4. Componentes-chave e estados
- **Placar:** número gigante tabular + cor semântica + **ícone + rótulo** (nunca cor sozinha — daltonismo
  na plateia/imprensa). Verde=Sim, Vermelho=Não, Cinza=Abstenção.
- **Card de vereador:** nome + estado de voto; "aguardando" apagado; vira colorido ao registrar.
- **Quórum (4.4):** contador presente/total + limiar (regra do motor, não hard-code).
- **Tempo real (`[SSE]`):** atualização ao vivo; `aria-live` para a versão acessível; **degradação
  graciosa** se a conexão cair (SLA de sessão — mostrar "reconectando", nunca tela congelada/mentirosa).
- **Encerramento:** ao fechar a votação, congela o resultado + "APROVADO/REJEITADO" grande.

---

## 5. Acessibilidade (mesmo no escuro)
- Contraste **alto** (o telão é, por natureza, um ganho de contraste) — números claros sobre escuro ≥ 7:1.
- Voto/resultado **nunca por cor só**: ícone (✓/✕/–) + rótulo textual sempre.
- Versão web espelhada: respeita tamanho de fonte do sistema, `aria-live` discreto, tema claro opcional.
- Sem animação gratuita; transições de estado de voto suaves mas curtas (não distrair o plenário).

---

## 6. Anti-patterns específicos (além do MASTER)
- ❌ Densidade de dashboard — o telão tem **um** foco; nada de mini-cards e gráficos.
- ❌ Voto/resultado por **cor sozinha** — ícone + rótulo obrigatórios (plateia daltônica).
- ❌ Expor **voto secreto** individual — viola 4.3/§22.4.
- ❌ **Tela congelada** sob falha de rede passando por "ao vivo" — mostrar estado de reconexão (SLA).
- ❌ Usar o tema escuro como desculpa para dark-mode nas outras telas — é exceção **só** desta superfície.

---

## 7. Mockup
`../mockups/sessao-ao-vivo.html` — telão em votação nominal. Matéria/votos **ilustrativos** (`[GAP]`).
