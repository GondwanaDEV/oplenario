# ADR-0013 — Agente institucional da Casa e a conferência das proposições

- **Status:** aceita (27/09/2026)
- **Contexto de decisão:** docs/25 Eixos 3.1 (b), 3.3, 5.1, 5.3, 5.7 e 7.7 (todos CONFIRMADOS em 26/09/2026), plano
  docs/26 B.8
- **Relacionadas:** ADR-0009 (catálogo), ADR-0010 (identidade delegada), ADR-0011 (normas por dispositivo),
  ADR-0012 (proposta de ato)

## Contexto

O Eixo 3.1 (b) confirmou um segundo tipo de principal: o **agente institucional da Casa**, sem pessoa por trás,
restrito a `leitura` + `rascunho`, com todo resultado caindo numa fila para uma pessoa; 3.3 diz que ele é **concedido
pelo `admin_ente`**. O 7.7 é o pedido do stakeholder que o estreia: a cada `ProposicaoProtocolada` (5.7), ler os
dispositivos aplicáveis da LOM/RI e deixar **um rascunho de nota técnica com citações** para a secretaria — nunca uma
decisão. A ADR-0010 já tinha a credencial sem pessoa (`identidade_id` NULL, nunca `ato`), mas o ator resolvia sem
papel algum "até a concessão existir".

## Decisão

1. **Concessão = o vínculo do agente.** `identidade.concessao_agente` (mig 0096, tenant, RLS): Casa, agente, classes
   (só `leitura`/`rascunho` — `CHECK`), quem concedeu e quando, quem revogou e quando. Uma ativa por (Casa, agente);
   revogar fecha a linha, conceder de novo abre outra. Os agentes que existem estão em
   `identidade.models.identidade/agentes-institucionais` (hoje só `conferencia-normativa`); fora da lista não há
   concessão nem credencial. Telas: `GET /identidade/agentes-institucionais` (admin e secretaria veem),
   `PUT`/`DELETE …/:agente/concessao` (só `admin_ente`).
2. **A credencial institucional** tem o público `institucional` (a mig 0096 acrescenta o valor e amarra: público
   institucional ⇔ sem pessoa). O core só a emite com concessão ativa, pelas classes concedidas, a pedido do satélite
   pela fronteira de serviço (`POST /integracao/ia/v1/entes/:e/agentes/:agente/execucoes`; 404 = não concedido) e a
   revoga ao fim (`DELETE …/execucoes/:id`). Mesmos 15 min de vida da ADR-0010.
3. **O ator é recalculado a cada chamada**, como o da pessoa: com concessão ativa ele recebe o papel
   `agente_institucional` (`kernel.autorizacao/papel-agente-institucional` — nenhuma pessoa o tem, não é concedível
   por rota) e as classes da credencial ∩ as da concessão; desligar derruba o agente **na chamada seguinte**, com a
   credencial ainda válida. Cada entrada do catálogo que o agente institucional pode usar declara esse papel, uma a
   uma. O conjunto do público `institucional`: `situacao_da_materia`, `buscar_dispositivos`, `ler_dispositivo`,
   `registrar_nota_tecnica`.
4. **A nota técnica é rascunho do core** (5.3): `legislativo.nota_tecnica` (mig 0097, tenant, RLS) — proposição,
   agente, execução, texto com as marcas de citação, a conferência de cada citação, parágrafos sem fonte, incerteza,
   modelo; estado `pendente` → `aproveitada` (com o texto que a secretaria guardou) | `descartada`, com quem e quando.
   Uma por proposição e agente: a execução repetida não duplica a fila. Ela entra pela ferramenta
   `registrar_nota_tecnica` (classe `rascunho`, audit em `integracao_ia.chamada_agente` sem pessoa); a execução vem
   da credencial, nunca da entrada.
5. **No satélite é um roteiro, não um laço aberto.** `ProposicaoProtocolada` enfileira `conferir_proposicao`; o
   trabalho pede a credencial (sem concessão: descarta, sem ruído), e pelo MCP lê a matéria, busca pela espécie e pela
   ementa, lê cada dispositivo achado (até 4); o núcleo redige (`conferencia.redigir`, citação por parágrafo
   conferida, incerteza, registro, custo) e a nota volta pela ferramenta. A credencial é encerrada ao fim, dê certo ou
   não. O que ler é código; o modelo só redige — nada que um texto de terceiro possa redirecionar.
6. **A tela é da secretaria** (`/conferencias`): a fila (a conferir, aproveitadas, descartadas), a nota com o selo
   "rascunho produzido por IA", o aviso de incerteza, cada citação numerada ao lado do parágrafo e o parágrafo sem
   fonte marcado; aproveitar (editando, se quiser — o texto vai sem as marcas) ou descartar, uma decisão só. Nada anda
   na tramitação por causa da nota.

## Consequências

- O kernel do catálogo passou a decodificar as chaves de **mapas aninhados** do JSON (só as declaradas no schema) —
  a entrada da nota foi a primeira com lista de mapas; antes só o primeiro nível virava keyword.
- Edição de proposição (`ProposicaoAtualizada`) não confere de novo; conferir a versão nova fica para quando a Casa
  pedir.
- Falha da IA não avisa a fila: a proposição simplesmente fica sem nota (R-IA-1 — a secretaria confere pela tela,
  como sempre). O trabalho falho fica no satélite com o erro.
- **Fica para depois:** a revisão da nota medida de volta na IA (taxa de aproveitamento, como a da ata), apontar
  divergência entre regra configurada e texto (7.6), e outros agentes institucionais — cada um entra na lista do
  item 1 com a concessão do admin.

## Id da execução na IA (reportar erro)

(04/10/2026) O "Reportar erro" (8.4) precisa do id da execução NO SATÉLITE; `nota_tecnica.execucao_id` é o da credencial.

1. **`registrar_nota_tecnica` ganha `execucao-ia`**, opcional (UUID; ausente ou nulo é aceito, para o satélite antigo
   continuar funcionando no deploy; outro valor é recusado como os demais campos). O roteiro o preenche com o id da
   execução do núcleo (`pedido_de_registro`). Guardado em `legislativo.nota_tecnica.execucao_ia` (mig 0186, só no
   INSERT; nota anterior fica NULL e a tela não oferece o botão) e devolvido pela leitura da nota só quando existe.
2. **É chave de correlação, não identidade nem autoridade.** Agente e execução da CREDENCIAL continuam vindo só do
   `:via` (item 4 acima); nenhuma decisão de autorização lê `execucao-ia`. O satélite já confere que a execução é da
   mesma Casa ao receber o reporte.
