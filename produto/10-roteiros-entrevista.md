# 10 · Roteiros de Entrevista — Trilho PRIMÁRIO (Q5 / Q7)

Skill: `ecc:market-research` (pesquisa primária). Reusa `03` (3 personas + ICP), `06` (pricing/
willingness-to-pay), `07` (quem escreve o edital), `08` (Q5/Q7). **Este arquivo é executável pelo
Daouda/time comercial** — o ECC entrega o roteiro; a execução (entrevistas) é humana (§13: "entrevistar
5–10 câmaras"). **Convenção:** 🎯 = o que a pergunta testa · ⚠️ = armadilha a evitar.

> **Por que existe:** `08` define Q5 (validação de persona / gatilho de compra / quem decide) e Q7
> (willingness-to-pay / norma de contrato / quem escreve o edital) como **P1 humano-liderado**. Sem
> entrevista, `03`/`04`/`06`/`07` são teoria informada, não evidência. Este é o instrumento que
> converte hipótese em dado.

---

## 0. Antes de tudo — regras de entrevista (para não viciar o dado)

- **Não pitchar.** A entrevista é para aprender, não vender. Se o entrevistado pedir demo, agende
  outra conversa. Misturar venda e descoberta contamina a resposta.
- **Perguntar sobre o passado concreto, não o futuro hipotético.** "Como foi a última vez que vocês
  contrataram um sistema?" > "Vocês comprariam um sistema com IA?". O futuro hipotético mente; o
  passado concreto não.
- **Silêncio é ferramenta.** Depois da resposta, esperar 3 segundos. O segundo que vem é o verdadeiro.
- **Nunca dizer o preço primeiro** (seção Q7). Deixar a âncora vir deles.
- **Gravar (com consentimento) ou anotar verbatim os números e as palavras exatas.** "Caro",
  "burocrático", "o TCE pega" — as palavras viram copy depois.
- **Amostra-alvo (§13):** 5–10 câmaras, com viés para o ICP de `03` (10k–100k hab, NE, fora do CE
  forte). Buscar as 3 personas em câmaras diferentes — idealmente 2–3 servidores, 2–3 presidentes/
  assessores de Mesa, 1–2 jurídico/controle interno.

---

## 1. Triagem (1 min, antes de marcar) — confirma que a câmara é ICP

1. Quantos habitantes tem o município? *(filtro 10k–100k; fora disso, anotar mas despriorizar)*
2. Quantos vereadores? *(proxy de porte e de complexidade de sessão)*
3. Hoje, como vocês fazem a **tramitação de projetos** e o **registro da sessão**? *(SAPL? sistema
   pago? papel? OBS+YouTube? nada?)* — 🎯 classifica o status quo: **SAPL grátis** vs. **especialista
   pago** vs. **precário/nada**. Esta resposta decide qual versão do roteiro de dor usar.
4. Quem é responsável por escolher e contratar um sistema desses? *(abre o mapa de decisão de Q5)*

---

## 2. Persona A — Servidor legislativo (campeão / usuário) — valida Q5 + dor

**Objetivo:** confirmar que a dor diária existe, é aguda, e que IA+UX a resolvem (Aposta 1+2).

### Dor e workflow atual
1. Me conta o seu dia numa **semana de sessão**. O que toma mais tempo? ⚠️ deixar descrever sem
   interromper; anotar onde aparece "manual", "retrabalho", "de novo".
2. Qual a parte mais **chata ou demorada** da tramitação de um projeto, do protocolo à publicação?
3. Quando o vereador pede "acha aquele projeto de 2019 sobre X", como você acha? Quanto demora?
   🎯 testa a dor de **busca** (Aposta 1 — IA).
4. Já aconteceu de um **erro de tramitação** (prazo, numeração, quórum) virar problema? Conta.
   🎯 testa o valor de "erro reduzido".

### Status quo e troca
5. O sistema/jeito de hoje — o que funciona e o que te irrita? *(se SAPL: cavar UX, suporte, busca)*
6. Se pudesse apagar **uma** tarefa braçal da sua semana com um botão, qual seria? 🎯 prioriza feature.
7. Última vez que mudou de sistema/processo aqui — como foi? O que deu medo? 🎯 testa objeção de
   migração ("vou ter que retrabalhar tudo?", `03`).

### Decisão (Q5)
8. Se você achasse uma ferramenta que resolve isso, **o que você faria** com essa informação? Para
   quem leva? 🎯 mapeia se o servidor é **iniciador** real ou só usuário.

---

## 3. Persona B — Presidente / Mesa (ou chefe de gabinete da Mesa) — valida Q5 + valor político

**Objetivo:** confirmar quem **assina**, o gatilho de compra, e se "engajamento cidadão" move o ponteiro
no horizonte de mandato (2 anos, `03`).

1. Pensando na sua gestão da Mesa, quais são as **2–3 prioridades** que você quer deixar marcadas?
   ⚠️ não sugerir "transparência" — ver se vem sozinho.
2. Como a câmara se comunica hoje com o **cidadão**? Isso é uma preocupação sua? 🎯 testa Aposta 2.
3. Quando a câmara **decidiu modernizar** alguma coisa de tecnologia, o que **disparou** isso? (cobrança
   do TCE? pressão pública? nova legislatura? um vereador específico?) 🎯 **gatilho de compra (Q5)** —
   a pergunta mais importante desta persona.
4. Numa contratação de sistema, **quem participa da decisão**? Quem dá a palavra final? E o jurídico/
   controle interno, entra quando? 🎯 mapa de decisão real (Q5) — desenhar o organograma de compra.
5. O que faria você **não** aprovar uma contratação dessas? 🎯 objeção do comprador.

---

## 4. Persona C — Jurídico / Controle interno / Procurador — valida Q5 (veto) + risco

**Objetivo:** entender o que **trava** uma compra e como o risco regulatório (TCE) é avaliado.

1. Quando chega um pedido de contratar um sistema novo, o que **você** checa antes de liberar?
2. Vocês contratam mais por **dispensa** ou por **pregão**? O que decide um ou outro? 🎯 confirma a
   mecânica de `01`/`07` na boca de quem opera.
3. Já tiveram **apontamento do TCE** sobre sistema/TI ou sobre sessão? O que foi? 🎯 testa "compliance
   TCE-como-dado" como desarme de risco (`04`) e mede o medo real.
4. O que te deixaria **inseguro** de aprovar um SaaS (dado na nuvem, cair na sessão, sair do ar)?
   🎯 objeções de confiança operacional → munição para SLA/migração.
5. Como funciona o **reajuste** num contrato plurianual de vocês? Tem índice? 🎯 alimenta `06`.

---

## 5. Bloco Q7 — Willingness-to-pay + edital (fazer com B e C, nunca abrir com preço)

> ⚠️ **Regra de ouro:** nunca diga o número primeiro. A âncora tem que vir deles, senão o dado é lixo.

1. Vocês têm hoje algum **contrato de sistema** (legislativo, contábil, jurídico)? Quanto custa por
   ano, mais ou menos? 🎯 ancora o **orçamento de TI real** da câmara sem você sugerir nada.
2. Para um sistema que resolvesse [a dor nº 1 que ELE citou], **quanto pareceria razoável** pagar por
   ano? E o que seria **caro demais**? 🎯 banda de willingness-to-pay; cruzar com âncora de ~R$ 65k (`06`).
3. *(se usa SAPL grátis hoje)* Vocês usam o **SAPL do Senado**, que é de graça. O que faria valer a
   pena **pagar** por algo, em vez de continuar no grátis? 🎯 **a pergunta-pivô:** mede a dor "além do
   mínimo legal" que justifica sair do zero (`04`/`09`). Se a resposta for "nada", o ICP está errado.
4. Quando vocês contratam por **dispensa**, como é o processo? Quem escreve o **termo de referência**?
   É o servidor, a assessoria, o jurídico — ou copiam de outra câmara? 🎯 **coração da motion de
   pregão/dispensa (`07`)** — quem redige o edital é quem podemos influenciar.
5. Existe uma **época do ano** em que sobra/falta orçamento para isso? Quando vocês costumam licitar?
   🎯 timing orçamentário (`07`, lacuna aberta).
6. Se um colega de outra câmara recomendasse um sistema, **isso pesaria**? Vocês conversam entre
   câmaras / em associação? 🎯 testa o canal de referência e fóruns setoriais (`07` §3).

---

## 6. Fechamento (toda entrevista)

- "O que eu **deveria ter perguntado** e não perguntei?" 🎯 a melhor pergunta de descoberta.
- "Tem mais alguém — aí ou em outra câmara — com quem eu deveria falar?" 🎯 snowball para chegar às 5–10.
- Pedir permissão para voltar quando houver protótipo. *(transição honesta para POC futura.)*

---

## 7. Como consolidar (o que vira decisão)

Depois de 5–10 entrevistas, preencher e **devolver para os arquivos**:

| Sai da entrevista | Confirma/derruba | Vai para |
|---|---|---|
| Gatilho de compra recorrente (P3) | hipótese de `03` (nova legislatura / TCE / insatisfação) | `03`, `07` |
| Quem realmente decide e quem veta | wedge servidor-led de `03` §4 | `03`, `07` |
| Banda de willingness-to-pay observada | âncora de ~R$ 65k de `06` | `06` |
| "Por que pagar se o SAPL é grátis?" respondido | herói de mensagem de `04` | `04`, `06` |
| Quem escreve o edital + timing orçamentário | motion de pregão/dispensa de `07` | `07` |

**Critério de fechamento (de `08` §3c):** personas validadas "o suficiente para cravar mensagem"
quando ≥5 entrevistas convergirem em **um gatilho de compra dominante** e **um mapa de decisão estável**.
Até lá, `03`/`04` seguem 🟡.

## 8. Riscos & lacunas deste instrumento

- **Viés de amostra:** câmaras que aceitam entrevista tendem a ser as mais organizadas/abertas — as
  precárias (o coração do ICP) podem ficar sub-representadas. Mitigar buscando ativamente câmaras que
  ainda usam OBS+YouTube.
- **Não substitui a POC:** willingness-to-pay declarada ≠ contrato assinado. Q7 dá banda, não preço final.
- **Execução é humana:** o ECC não pode rodar isto. Sem o Emilio/time agendar, Q5/Q7 ficam abertas e a
  discovery **não fecha** (`08` §3).
