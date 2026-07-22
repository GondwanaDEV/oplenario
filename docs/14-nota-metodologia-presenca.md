# Nota de metodologia — o número de presença do perfil público do vereador

**O que este documento é:** o texto que vai ao ar junto com o número, mais as regras que a tela tem de
obedecer para não afirmar sobre uma pessoa algo que o número não sustenta. Escrito junto com a fatia 6 do
carry I-5 (`84ead40`), contra o contrato real de `transparencia/wire/out/parlamentar`.

**Por que ele existe:** a página é **pública e nominal**. Um número de presença errado, ou certo mas com o
rótulo errado, é uma afirmação sobre a conduta de uma pessoa identificada, exposta a qualquer cidadão,
jornalista ou adversário eleitoral. O custo de errar aqui não é um bug — é uma reclamação formal, e com razão.

---

## 1. A frase publicada

> **Compareceu a X das Y sessões com registro de presença** que a Câmara realizou **enquanto este vereador
> estava em exercício do mandato**, descontados os períodos de licença registrados.

Essa frase é o contrato. Cada pedaço dela está lá por um motivo:

- **"compareceu a"** e não "esteve presente em" — o número conta quem **tem registro de presença** na sessão.
  Quem assinou e saiu no primeiro item da pauta conta. Não é medida de permanência.
- **"sessões com registro de presença"** e não "sessões realizadas" — sessão em que ninguém fez check-in não
  existe no read-model e some dos **dois** lados da fração. Chamar isso de "sessões realizadas" transformaria
  uma falha do painel em falta para os 21 vereadores.
- **"enquanto estava em exercício do mandato"** — é o que a fatia 6 corrigiu. O suplente convocado para 3
  sessões publica "3 de 3", não "3 de 600".

---

## 2. Os quatro estados que a tela precisa distinguir

O par de inteiros sozinho **colapsa situações muito diferentes num `0 de 0` indistinguível**. Por isso o
contrato traz `:janela-de-exercicio-conhecida`, e ele é **obrigatório para ler os outros dois**.

| Estado | Como reconhecer | O que a tela mostra |
|---|---|---|
| **Sem período de exercício registrado** | `janela-de-exercicio-conhecida = false` | o bloco do §3. **Nunca** uma fração, nunca "0%" |
| **Em exercício, ainda sem sessão com chamada** | `true` e `sessoes-com-chamada = 0` | "Ainda não houve sessão com registro de presença neste mandato." **Nunca** "0 de 0" cru |
| **Faltou a tudo** | `true` e `sessoes-com-chamada > 0` e `sessoes-presente = 0` | a fração normal: "0 de 12" |
| **Exercício anterior aos dados publicados** | `janela-anterior-a-projecao = true` | a fração **mais** a ressalva do §6 |

O terceiro caso é o único em que o número acusa alguém — e ele **precisa** aparecer. O faltoso crônico
publicando "0 de 12" é o ponto do transparência; o que não pode acontecer é ele sumir num "0 de 0" que parece
ausência de dado. Por isso o denominador **não** tem o vereador no filtro, e há um teste com esse nome exato
travando essa propriedade.

O quarto caso é traiçoeiro e por isso ganhou campo próprio: um mandato **inteiramente anterior** ao início do
registro eletrônico publica `0 de 0` **com `janela-de-exercicio-conhecida = true`**. Sem
`janela-anterior-a-projecao`, a tela leria isso como "está em exercício e ainda não houve sessão" ou como
"faltou a tudo" — as duas falsas, sob o nome de uma pessoa.

---

## 3. Quando não há período de exercício registrado

> **Período de exercício não informado.** Esta Casa ainda não registrou o período de mandato deste vereador,
> e por isso não é possível calcular presença de forma justa. O registro de presença de cada sessão continua
> disponível na ata correspondente.

**Nunca exibir "0%" nesse caso.** Zero sobre zero não é zero por cento — é ausência de informação, e publicar
0% sob o nome de uma pessoa é acusação sem base.

---

## 4. Como o período de exercício é determinado

A partir dos mandatos registrados no cadastro da Casa: início de vigência até o término.

- Quando o mandato termina **antes do prazo** — renúncia, cassação, falecimento, fim de convocação de
  suplente — vale a **data efetiva de término**, não a data nominal do fim da legislatura. (E vale a **menor**
  das duas: uma data efetiva digitada *depois* do fim nominal não alarga a janela.)
- Quem teve **mais de um período de exercício** — suplente convocado duas vezes, por exemplo — tem cada
  período contado separadamente. **O vão entre eles não é exercício e não entra na conta.**
- **Períodos de licença registrados são subtraídos**, e cada licença desconta apenas do seu próprio período de
  mandato — uma licença de um mandato antigo não apaga o mandato exercido hoje.

---

## 5. O que este número **não** diz

1. **Não é "sessões realizadas".** É "sessões com registro de presença" (ver §1).
2. **Não substitui a ata.** O documento de fé é a ata de cada sessão. Este número é um resumo derivado dos
   registros; divergência entre ele e a ata **resolve-se pela ata**.
3. **Não julga a falta.** Não se distingue aqui falta justificada de injustificada — justificativa de ausência
   é ato próprio, com decisão da Mesa, e não é insumo deste cálculo.
4. **Depende do que a Casa registrou.** Licença não lançada não é descontada; posse de suplente não registrada
   não é reconhecida.

---

## 6. Marco de início do registro eletrônico

O registro eletrônico de presença passou a alimentar esta página em **{`presenca-projetada-desde`}**
(constante de deploy, hoje `2026-07-20`). Sessões anteriores a essa data **não constam — nem no numerador,
nem no denominador**, porque não existe ferramenta de re-projeção no sistema.

Quando o período de exercício começa **antes** dessa data, o servidor marca `janela-anterior-a-projecao` e a
tela **deve** trazer a ressalva:

> Há período de exercício deste mandato anterior aos dados publicados; o número cobre apenas a parte coberta
> pelo registro eletrônico.

**Quem decide isso é o servidor, não a tela** — o contrato não publica as datas de exercício, de propósito
(ver §7), então a tela não teria como comparar.

---

## 7. O que o denominador revela — e a premissa que isso assume

**Isto é uma premissa jurídico-institucional, não técnica, e precisa de decisão consciente antes do deploy.**

Como o denominador conta apenas as sessões dentro do período de exercício, ele **para de crescer no início de
uma licença e volta a crescer no fim dela**. Um observador que leia esta página dia após dia consegue,
portanto, **derivar por diferença o intervalo da licença** — numa rota pública, anônima e nominal.

O que continua protegido: **o motivo da licença**. O cadastro recusa devolvê-lo à leitura pública de
propósito, por ser dado potencialmente sensível de saúde. O que deixou de estar protegido é o **período**.

Isso é aceitável porque **licença de vereador é ato de plenário publicado** — não é informação que o mandato
mantenha reservada. Mas a afirmação é sobre o regime jurídico da licença, não sobre o software, e por isso
está escrita aqui em vez de ficar implícita num comentário de código. O contrato **não** publica as datas de
exercício em campo próprio justamente para não ir além do que o denominador já expõe.

---

## 8. Correções

Correção de cadastro — data de posse, período de licença, data de término, reassunção após licença — **altera
este número imediatamente**, sem reprocessamento: a janela é calculada na hora da leitura. Quem identificar
divergência deve procurar a secretaria da Casa.

---

## 9. Regras duras para a tela

- **O servidor nunca calcula percentual.** A tela **nunca** renderiza percentual sem os dois números ao lado.
  Um "100%" sobre uma sessão mente por omissão.
- **`janela-de-exercicio-conhecida` é lido antes dos inteiros**, sempre. Um `nil` inesperado já cai em `false`
  no adapter — o lado seguro é parar de exibir fração, não exibir uma em que não se pode confiar.
- **O rótulo é o do §1, no sentido palavra por palavra.** "Esteve presente em X de Y sessões realizadas" é
  proibido: são duas afirmações que o número não sustenta, numa página nominal.

---

## 10. O dia do deploy

O número de **todos** os vereadores da Casa muda de uma vez, e a mudança é grande nos dois sentidos:

- **o numerador sai de zero.** O que está publicado hoje em produção é `0` para todo parlamentar — o
  predicado antigo comparava com `'presente'`, um valor que produtor nenhum emite (corrigido na fatia 1).
- **o denominador desce** do total histórico da Casa para o período de exercício de cada um.

É a correção pretendida, mas é visível e é nominal. Duas providências, e nenhuma é opcional:

1. **Esta nota sobe junto com a mudança**, não depois.
2. **Aviso prévio à Mesa e à secretaria** — não para aprovar, mas para que não sejam pegos de surpresa por
   pergunta de vereador no dia seguinte.

No lado técnico, o `ANALYZE` que era item de runbook virou a **migration 0070** na revisão da fatia 6 — sem
estatísticas o planner escolhe um plano centenas de vezes pior nesta rota pública, e item de runbook manual é
o que se esquece. Resta a **janela de manutenção** das migrations 0067–0070.
