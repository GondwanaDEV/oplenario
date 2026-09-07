# Sonda de varredura pré-demo

Visita **27/27 rotas** do frontend num browser real e reporta, por rota: status, tamanho do texto
renderizado, chave de enum vazando na tela (`em_comissoes`, `PROJETO_LEI`), fragmento de UUID onde
deveria ir um nome, mensagens de erro/vazio e erros de console. **Sai com código ≠ 0 se qualquer
rota tiver defeito** — nunca só reporta.

Existe porque `curl` devolvendo 200 não prova nada aqui: quase toda tela é Client Component que
busca depois da hidratação — o HTML do servidor é só a casca. E porque um `docker compose up
--build` reusa o volume anônimo de `/app/.next`: rota nova em disco pode ser servida como 404 por
um índice de build velho. A sonda pega os dois casos.

## Rodar

Stack de pé (`cd apps/backend && docker compose up -d`), demo semeada
(`./demo/semear-tudo.sh` na raiz), e então:

```sh
./e2e/.sonda/rodar.sh
```

(embrulha o `docker run` canônico — ver o próprio script se quiser rodar à mão.)

## De onde vêm os ids

**Nunca cravados no arquivo.** `sonda.mjs` lê `e2e/.artifacts/demo-ids.edn` (gravado por
`casa/semear!`) para ente/vereador/identidades — **falha alto** com mensagem acionável
("rode `./demo/semear-tudo.sh` antes") se o arquivo não existir. Proposição e parecer não têm
artefato (`random-uuid`, não estável) — a sonda os descobre em runtime via `GET /portal/casa/:ente/
materias` (pública) e `GET /legislativo/proposicoes/:id/ficha` (autenticada, embute os pareceres).
As sessões (aberta/encerrada) usam as constantes fixas publicadas em
`apps/backend/demo/sessoes.clj` — não têm artefato nem rota de listagem; ver o comentário em
`sonda.mjs` para a justificativa completa.

**Cuidado ao trocar `/parecer/:id`:** o id ali é do **parecer**, não da proposição — o hook do FE
chama `GET /legislativo/pareceres/:id`. Já foi bug real desta sonda.

## O veredicto

Reprova (código ≠ 0) se, em qualquer rota: status ≥ 400 · fragmento de UUID no texto visível ·
chave de enum crua · erro de console · texto de erro genérico. **`Em breve` conta como AVISO, não
falha** — é lacuna conhecida (classe B do plano de prontidão), listada à parte no relatório.

## Três armadilhas medidas

- **`waitUntil: 'networkidle'` não serve** nas telas de SSE (plenário, chamada, votar): o stream
  nunca deixa a rede ociosa e a navegação estoura. Usa `domcontentloaded` + espera fixa.
- **`--no-sandbox` é obrigatório** rodando como root no container, senão o contexto fecha sozinho.
- **`docker compose up --build` reusa o volume anônimo `/app/.next`** — se uma rota nova em disco
  aparecer 404 na sonda, suspeite do índice de build velho antes de suspeitar do código.
