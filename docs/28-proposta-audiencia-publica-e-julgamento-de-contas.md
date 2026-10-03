# 28 — Proposta: audiência pública (4.18) e julgamento das contas do Prefeito (4.19)

> **Status: ✅ decidido** — confirmado em 03/10/2026 e implementado: ver
> [ADR-0021](adr/0021-audiencia-publica-e-julgamento-das-contas.md). O texto abaixo é o rascunho de 29/09/2026,
> mantido como registro das opções.
>
> **Rascunho original:** (29/09/2026), eixo a eixo. São as duas últimas telas da Onda E que
> não têm domínio no backend (`audiencia-publica.html`, `julgamento-contas.html`). Aqui está o desenho de produto e
> dados a confirmar antes do código; cada eixo traz opções e uma recomendação.
>
> **Base:** `produto/13` 4.18 e 4.19, `produto/14` G9 e G10 (as duas **ENTRAM** na V1), §22.6 eixo A (tipo de sessão
> com capabilities), §22.7.5 S4 (quórum qualificado no motor), Invariante 4 (regra é dado).
>
> **Leitura jurídica:** o que segue é entendimento prático, não parecer. O rito fino de cada LOM/RI vai ao especialista
> em regimento (doc-mestre §10), como já está dito para G9.

## O que já existe e será reusado

| Peça | Estado |
|---|---|
| Sessão com tipo nominal + capabilities, pauta, presença, tribuna (`inscricao_oradores`), ata-IA, livro de atas, transmissão | ✅ `sessoes` |
| Espécie **Decreto Legislativo** | ✅ `legislativo` (`decreto_legislativo`) |
| Votação nominal com quórum `maioria_qualificada_2_3` (aritmética exata, `ceil(2N/3)`) | ✅ `legislativo/logic.clj` |
| Prazo de domínio monitorado (obrigação com relógio) | ✅ motor de compliance (§22.7.7) |
| Cidadão identificado pelo gov.br + recibo com protocolo | ✅ ADR-0015 |
| Comissões e pareceres | ✅ `legislativo` / `cadastros` |

**Não existe:** audiência como reunião, inscrição do cidadão para falar e prestação de contas com parecer prévio do TCE.

---

## Parte A — Audiência pública (4.18)

A audiência acontece **perante comissão**, não delibera e é aberta ao público. As de metas fiscais
(quadrimestrais, LRF art. 9 §4) e as de PPA/LDO/LOA (LRF art. 48) são **obrigatórias**.

### A1 — Onde mora

- **(a) Novo tipo de sessão `audiencia_publica` em `sessoes` (recomendado).** As capabilities ficam assim:
  `delibera = false`, `exige_quorum = false`, `promovida_por = comissao_id`, `aceita_inscricao_cidadao = true`.
  - Herda pauta, presença, tribuna, transmissão, ata e livro de atas sem código novo.
  - É o caminho que §22.6 eixo A previu: tipo é dado, comportamento é capability.
- **(b) Entidade própria "reunião de comissão".** ✘ Duplica pauta, ata e transmissão. Só vale se reuniões de
  comissão entrarem como produto inteiro, o que hoje não está na V1.

### A2 — Quem pode se inscrever para falar

- **(a) Cidadão pelo gov.br (recomendado).** A mesma porta dos outros formulários do portal: identidade forte e
  recibo com protocolo. A inscrição pede "fala como" (individual, entidade, conselho/movimento) e o tema em uma
  frase.
- **(b) Formulário aberto só com nome**, como o desenho mostra. ✘ Sem identidade: a lista de oradores vira alvo de
  spam e a Mesa não tem como conferir.
- **(c) Só presencial.**

A Mesa **sempre** pode inscrever no dia, presencialmente (origem `presencial_secretaria`), com (a) ou (c).

**LGPD:** quem fala em audiência fala em público. O nome entra na ata e na transmissão. O formulário diz isso antes
de enviar (base legal: exercício de participação pública). Quem se inscreve e não fala sai da lista sem publicação.

### A3 — A ordem e o tempo das falas

- **(a) Ordem de inscrição, tempo único definido pela Mesa na abertura (recomendado),** configurável por audiência.
  Reusa o cronômetro da tribuna.
- **(b) Blocos** (poder público → entidades → cidadãos). Fica como opção de pauta, sem regra nova.

### A4 — As audiências obrigatórias

- **(a) Obrigação no motor de compliance (recomendado).** "Audiência de metas fiscais do 1º/2º/3º quadrimestre" e
  "audiência da LDO/LOA" viram **regras-dado** (Invariante 4) com prazo.
  - O painel de compliance avisa antes de vencer.
  - A audiência realizada (com ata publicada) cumpre a obrigação: é a prova para o TCE.
- **(b) Só agenda manual.** ✘ Perde o aviso e a prova, que são o diferencial.

### A5 — O que o portal mostra

- A página da audiência (tema, comissão, data, local, matéria relacionada, "quero falar") e a lista de próximas.
- Depois de realizada: a ata no livro de atas e o vídeo, se houver.
- **Fora da V1** (guardrail do produto): audiência virtual com fala remota do cidadão e consulta pública estruturada
  — seguem V2.

---

## Parte B — Julgamento das contas do Prefeito (4.19)

A Câmara julga as contas anuais do Prefeito com base no **parecer prévio do TCE**. O parecer só deixa de prevalecer
por decisão de **2/3** dos membros (CF art. 31 §2). O resultado sai como Decreto Legislativo.

### B1 — O objeto

- **(a) Entidade `prestacao_contas` no `legislativo` (recomendado),** com:
  - exercício, responsável (o prefeito do exercício — pode não ser o atual) e data de recebimento;
  - parecer prévio: `favoravel` · `favoravel_com_ressalvas` · `desfavoravel`, com o número do processo no TCE;
  - o PDF do parecer e do relatório, **sem cravar valores** na tela (o desenho já marca `[GAP]`);
  - o estado.

  A entrada gera a proposição de **Decreto Legislativo** que tramita como qualquer outra.
- **(b) Só uma proposição de DL com anexos.** ✘ Perde o parecer como dado. Sem o dado, o sistema não calcula o que o
  quórum significa.

### B2 — O que se vota e com que quórum

- **(a) A votação é sobre o parecer, recomendado.** A regra entra no motor como dado (S4):
  - para **rejeitar** o parecer são precisos `2/3` dos **membros**, não dos presentes;
  - sem esse número, o parecer prevalece, **qualquer que seja o placar simples**;
  - o placar ao vivo e o resultado dizem isso em palavras: "o parecer prevalece: 12 votos pela rejeição, eram
    precisos 14".
- **(b) A Casa escolhe o quórum a cada votação.** ✘ Abre espaço para erro num ato que decide inelegibilidade (Lei da
  Ficha Limpa).

### B3 — Direito de defesa do ex-prefeito

O STF entende que o julgamento sem oportunidade de defesa do responsável é nulo. Recomendação: **modelar o passo**.

- A notificação do responsável é registrada.
- O prazo de defesa é um prazo de domínio; o valor é configurável por Casa, conforme LOM/RI (`[GAP]` rito).
- A defesa escrita entra como documento da prestação.
- A pauta só aceita o DL depois de o prazo vencer ou de a defesa ser juntada.

### B4 — Prazo para julgar

Muitas LOMs fixam um prazo depois do parecer (ex.: 60 dias). Recomendação: **obrigação no motor**, com o valor por
Casa e aviso no painel. O que acontece no vencimento (em algumas LOMs o parecer prevalece) é `[GAP]` por LOM: o motor
avisa, não decide.

### B5 — Depois do julgamento

- O DL é publicado (fluxo de norma que já existe), e o resultado fica na ficha da prestação e no portal
  (transparência).
- **A comunicação ao TCE** do resultado (prazo e forma) é da família da remessa: fica no `[GAP]` do layout do TCE-CE,
  e a tela mostra o que falta enviar.

### B6 — Contas da Mesa (a gestão da própria Câmara)

Na leitura prática, as contas de gestão da Câmara são julgadas **pelo TCE** (CF art. 71 II), não pelo plenário.

- **(a) Fora da V1 como rito (recomendado):** só o registro de acompanhamento — processo no TCE, situação e
  documento.
- **(b) Com rito próprio.** Só se o especialista em regimento apontar LOM que exija deliberação.

`produto/13` citava "+ contas da Mesa": esta leitura corrige, a confirmar com o jurídico.

---

## O que peço para decidir

| # | Pergunta | Recomendação |
|---|---|---|
| A1 | Onde mora a audiência | **tipo de sessão `audiencia_publica`** com capabilities (não delibera, sem quórum, da comissão) |
| A2 | Quem se inscreve para falar | **cidadão pelo gov.br** + inscrição presencial pela Mesa; aviso de que a fala é pública |
| A3 | Ordem e tempo | ordem de inscrição, tempo único da Mesa |
| A4 | Audiências obrigatórias da LRF | **regras-dado no motor** (aviso + prova) |
| B1 | Prestação de contas | **entidade `prestacao_contas`** com o parecer prévio como dado; gera o DL |
| B2 | Votação | sobre o parecer; **2/3 dos membros para rejeitar**, regra no motor |
| B3 | Defesa do ex-prefeito | passo modelado: notificação + prazo de defesa (valor por Casa) |
| B4 | Prazo para julgar | obrigação no motor; o efeito do vencimento fica `[GAP]` por LOM |
| B6 | Contas da Mesa | só acompanhamento (julgamento é do TCE) — confirmar com o jurídico |

**Ordem sugerida depois do "Confirmo":** Parte A primeiro. Reusa quase tudo, tem data marcada pela LRF (metas do
3º quadrimestre em fevereiro) e dá demo para o presidente da Mesa. Parte B em seguida.
