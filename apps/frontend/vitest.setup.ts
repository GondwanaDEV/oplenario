import { configure } from "@testing-library/dom";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// O timeout default do `waitFor`/`findBy*` do Testing Library é 1000ms — curto demais para ESTA suíte
// neste ambiente. Medido (2026-07-19, main): 4 rodadas completas da suíte deram 2 / 0 / 1 / 0 falhas,
// sempre no mesmo teste (`cadastros/vereadores/page.test.tsx`), sempre com a ficha ainda em
// "Carregando ficha…" — ou seja, o mock de fetch já resolveu mas o microtask não foi drenado a tempo.
// A causa é fome de event loop, não bug de produto: a suíte gasta ~100s só em `transform` dentro do
// container, e os workers do Vitest disputam CPU entre si.
//
// Subir o teto para 5s não esconde regressão: um componente que de fato não carrega segue falhando
// (só demora 5s em vez de 1s para acusar), enquanto o ruído de escalonamento some. Se um dia um teste
// precisar de janela CURTA de propósito (provar que algo NÃO aparece), ele passa `timeout` explícito.
configure({ asyncUtilTimeout: 5000 });

// Desmonta tudo o que o teste montou, sempre. O Testing Library só faz isso sozinho quando o `afterEach` é global
// (`globals: true`), e esta suíte não liga `globals`: 74 dos 228 arquivos que renderizam não chamavam `cleanup`, e o
// componente do teste anterior seguia vivo no jsdom com os timers e o `fetch` dele. Dois testes reprovaram no CI por
// isso: `rotulos-mesa` (o `Link` agendava trabalho depois de o arquivo terminar, PR #169) e `use-chamada` (o hook do
// teste "falha na carga inicial" seguia re-buscando pelo intervalo real e o GET caía na conta do teste seguinte).
// Quem já chama `cleanup` no próprio arquivo não muda: desmontar duas vezes não faz nada.
afterEach(() => cleanup());
