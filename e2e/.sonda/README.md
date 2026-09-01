# Sonda de varredura pré-demo

Visita TODA rota do frontend num browser real e reporta, por rota: status, tamanho do texto
renderizado, chave de enum vazando na tela (`em_comissoes`, `PROJETO_LEI`), fragmento de UUID onde
deveria ir um nome, mensagens de erro/vazio e erros de console.

Existe porque `curl` devolvendo 200 não prova nada aqui: quase toda tela é Client Component que
busca depois da hidratação — o HTML do servidor é só a casca. E porque um `docker compose up
--build` reusa o volume anônimo de `/app/.next`: rota nova em disco pode ser servida como 404 por
um índice de build velho. A sonda pega os dois casos.

## Rodar

Stack de pé (`cd apps/backend && docker compose up -d`), demo semeada, e então da raiz:

```sh
docker run --rm --network host --shm-size=1g \
  -e PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
  -v "$PWD/e2e:/e2e" -v oplenario_e2e_nm:/e2e/node_modules -w /e2e \
  mcr.microsoft.com/playwright:v1.49.0-noble node .sonda/sonda.mjs
```

Os ids (ente/sessão/proposição) e os tokens estão no topo de `sonda.mjs` — trocar pelos que a
semente imprimiu.

## Duas armadilhas medidas

- **`waitUntil: 'networkidle'` não serve** nas telas de SSE (plenário, chamada, votar): o stream
  nunca deixa a rede ociosa e a navegação estoura. Usa `domcontentloaded` + espera fixa.
- **`--no-sandbox` é obrigatório** rodando como root no container, senão o contexto fecha sozinho.
