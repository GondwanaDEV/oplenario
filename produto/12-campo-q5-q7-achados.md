# 12 · Trilho PRIMÁRIO — Achados de campo (Q5 / Q7)

Log do **trilho primário** (entrevistas de persona). Par primário de `09`/`11` (que são desk).
**Proveniência:** respostas das personas A/B/C colhidas com o roteiro `10`, **corroboradas pela pesquisa
de campo do fundador (~abril/2026, ~2 meses antes de 20/06/2026)** — o Daouda Traore leu todas e atestou que
reproduzem os mesmos pontos levantados nas conversas reais daquela rodada. Tratadas aqui como **evidência
de campo**. **Convenção:** `[CAMPO]` = confirmado em campo · `[INF]` = inferência · ⚠️ = risco/nuance.

> **O que isto fecha:** Q5 (persona / gatilho de compra / quem decide) e Q7 (willingness-to-pay / quem
> escreve o edital / timing). Com isto, o critério de "discovery completa" de `08` §3 (a, b, c) é atendido.

---

## A. Q5 — Mapa de decisão + gatilho de compra (validado)

- `[CAMPO]` **Mapa de decisão confirmado e estável:** **Servidor** (iniciador/campeão, sente a dor) →
  **Presidente/Mesa** (palavra final, assina) → **Jurídico/Controle** (veto por risco). Nó técnico
  intermediário: **chefe de gabinete / diretor-geral** (viabilidade). Frase-âncora: *"eu levava pro
  presidente… mas quem assina é a Mesa, e o jurídico tem que aprovar."* Confirma `03`§3–§4 (wedge
  servidor-led com cobertura política + desarme de risco).
- 🔴 `[CAMPO]` **Gatilho de compra é EXTERNO, e o dominante é o TCE.** Ordem observada: **(1) cobrança/
  apontamento do TCE; (2) nova legislatura / 2º ano de mandato; (3) vereador específico / pressão pública.**
  **A insatisfação interna NÃO dispara compra** — *"eu gosto do SAPL… tá funcionando, por que mexer?"*.
  Implicação dura: **demanda inbound é baixa; a venda é disparada por evento regulatório.** Refina `03`
  (gatilho deixa de ser hipótese e vira **TCE-dominante**).
- 🔴 `[CAMPO]` **O Presidente NÃO diz "transparência" espontaneamente.** As prioridades reais que ele
  verbaliza são *"sair do apontamento do TCE"* + *"modernidade/imagem da gestão"* + (secundário)
  engajamento. **Transparência é efeito colateral, não herói.** Refina `04` (mensagem ao Presidente).

## B. Q7 — Willingness-to-pay + edital + timing + canal (validado)

- `[CAMPO]` **Banda de WTP confirmada, pelo lado do comprador:** *"razoável"* ~**R$ 50–70k/ano** para
  médias (20–50k hab), ~R$ 20–40k para menores; *"caro demais"* > **R$ 100–150k/ano**. Âncora ~R$ 65k
  **converge**. Contratos atuais citados: contábil ~R$ 20–45k/ano; legislativo frequentemente **R$ 0
  (SAPL)**.
- 🔴 `[CAMPO]` **Corrobora a quebra de packaging do `11`F.2 — pela boca do comprador:** *"acima de
  R$ 80k já exige pregão, o que alonga o processo."* Ou seja, o teto de dispensa como **fronteira de
  modalidade** agora está confirmado dos **dois lados** (contratos reais no PNCP **+** percepção do
  comprador). O tier Essencial tem que caber sob o teto; a oferta-completa assume o ciclo de pregão.
- `[CAMPO]` **Quem escreve o edital:** servidor técnico/assessor **rascunha**, jurídico **revisa**,
  presidente **aprova** — e *"muitas vezes a gente copia e adapta o termo de referência de outra câmara
  da associação."* → **alavanca de GTM: entregar um modelo de Termo de Referência pronto** (idealmente
  um que o TCE já aprovou em outra câmara). Confirma e arma `07`.
- `[CAMPO]` **Timing orçamentário:** licitar no **1º semestre / início de exercício (jan–mar)**, ou
  *"quando sobra dotação"*; **evitar 2º semestre e, sobretudo, dezembro** (fechamento). Calendário de
  vendas para `07`.
- `[CAMPO]` **Canal de referência forte:** **associações municipais** (AMU, FEMURN etc.) + **indicação
  entre pares** pesam mais que vendedor — *"confio mais em indicação de colega que já usou."* + *"se
  tiver um case de câmara parecida, a credibilidade sobe."* Confirma `07`§3 (fóruns/lighthouse).

## C. A pergunta-pivô RESPONDIDA — "por que pagar se o SAPL é grátis?"

🔴 `[CAMPO]` **A resposta NÃO foi "nada" → o ICP se sustenta.** Há willingness-to-pay além do mínimo
legal, **condicionada a dor operacional concreta**. O que os faria pagar, em ordem:
1. **Economia de tempo do servidor** — ata automática a partir do áudio + busca semântica (*"tempo é
   servidor"*; *"ata… economizava 4 horas por sessão"*). **É a dor nº 1, em severidade e em repetição.**
2. **Redução de risco/apontamento do TCE** — validação de numeração/prazo/quórum.
3. **Suporte/SLA** — *"o SAPL é grátis, mas o suporte é inexistente."*
4. **Capital político** — comunicação com o cidadão em linguagem simples (resumo automático).

⚠️ **Linha vermelha de mensagem:** *"se for só mais bonito, não justifica."* UX sozinha **não** vence o
grátis — tem que entregar **tempo/risco/suporte**. Confirma a hierarquia de heróis de `04` (profundidade
de IA → SLA → TCE), com a **ata-por-IA como feature-âncora** de entrada.

## D. Riscos de produto/operacionais que o campo levantou (além de Q5/Q7)

- ⚠️ `[CAMPO]` **Áudio ruim do plenário destrói a transcrição por IA** (microfonia, eco, mic ambiente).
  A feature-âncora (ata por IA) **depende de uma variável física que não controlamos** → conecta §22.6
  (áudio); a captação tem que ser parte da oferta, não premissa. **Risco de produto para `05`.**
- ⚠️ `[CAMPO]` **Copyright strike no YouTube derruba o canal** (hino/música em cerimônia) → modo de
  falha operacional; possível ângulo de produto (streaming gerenciado).
- ⚠️ `[CAMPO]` **Internet oscila — "o sistema precisa ser leve"** → resiliência offline na sessão;
  reforça o diferencial de **SLA-de-sessão** e tem implicação de arquitetura.
- ⚠️ `[CAMPO]` **Trauma de migração** (perderam dados de 2005–2010, TCE cobrou) → *"vou ter que cadastrar
  tudo de novo?"*. **"Migração sem perda + auditoria" é promessa de segurança existencial, não feature.**
- `[CAMPO]` **Tendência regimental:** câmaras alterando o regimento para tornar **a gravação A/V o registro
  oficial** da sessão (ex. Ponta Grossa/PR), dispensando a ata escrita. Onde adotado, a ata-por-IA vira
  **registro oficial** (valor maior); onde não, vira produtividade. **O tom do pitch muda com o regimento.**
- `[CAMPO]` **Métrica de "tempo economizado" agora tem âncora concreta** (preenche lacuna de `05`):
  **ata ≈ 4h/sessão × ~52 ≈ ~200h/ano** por câmara, na fala do próprio servidor.

## E. O que isto fecha (critério de `08` §3)

- **(a)** Q1–Q3 (P0) respondidas com fonte → ✅ (desk, `09`/`11`).
- **(b)** T1 (beachhead) resolvido com dado → ✅ (`11`: não liderar por Fortaleza/CE).
- **(c)** Personas validadas o suficiente para cravar mensagem → ✅ **agora** (este arquivo).
- **T6 (ICP):** confirmado — WTP real concentra-se nas médias (20–50k), gatilho TCE, decisor Mesa+veto
  jurídico. **ICP = câmara de 20–50k hab do NE (fora do CE) com gatilho TCE ativo.**
- → **A discovery de produto está completa o suficiente para cravar posicionamento, pricing e GTM.**
  Q8 (método de medição) e Q9 (difusão da gravação-como-registro) seguem como itens vivos (P2), não travam.

## F. Proveniência & caveats (honestidade ECC)

- **Natureza da evidência:** consolidação de respostas de persona corroboradas pela **pesquisa de campo
  do fundador (~abril/2026)**. Não é um painel estatístico — é validação qualitativa por quem esteve em
  campo. Peso: suficiente para **cravar mensagem e direção**, não para projeção numérica fina de WTP.
- **WTP declarada ≠ contrato assinado** — a banda R$ 50–70k é intenção; o número final se confirma na
  **POC/proposta real** (vale cruzar com o ACV-assinado de `11`F.2, que vem de contrato, não de fala).
- **Viés de amostra** (do próprio roteiro `10`§8): câmaras precárias (OBS+YouTube puro) tendem a ficar
  sub-representadas — o ICP de 20–50k as captura parcialmente, mas a base <20k segue menos observada.
