// Detector de teste que depende de TEMPO. Opt-in: só roda com `npm run test:atraso` (vitest.atraso.config.ts).
// Não entra no `npm test` e NÃO é gate do CI.
//
// Para que serve: o job `frontend` do CI reprovou por testes que passavam só quando o React pintava a resposta
// antes de a asserção rodar (out/2026: `painel-tribuna`, `prazos-das-contas` e mais oito). Sob carga a ordem
// muda e o teste reprova "do nada". Este setup torna essa ordem ruim determinística:
//
//   ATRASO_MOCK=1     toda promessa devolvida por um mock `vi.fn` (e por um `fetch` trocado no teste) só resolve
//                     ATRASO_MS depois;
//   ATRASO_PINTURA=1  o commit do React fora de `act` (o Scheduler usa `setImmediate`) só acontece ATRASO_MS depois.
//
// Os dois vêm ligados; ATRASO_MS vale 40. Teste que espera o estado certo (`findBy`/`waitFor` no que só existe
// depois da resposta) passa igual. Teste que conta com "a resposta já chegou" reprova sempre.
//
// Como ler o resultado — o instrumento também erra, e erra de três jeitos conhecidos:
//   1. sob a suíte inteira, o PRIMEIRO teste de um arquivo pode estourar o tempo de um `findBy` correto (o atraso
//      multiplica as fatias de render). Vermelho que não se repete com o arquivo rodado sozinho é do instrumento;
//   2. com timers falsos ligados nada é atrasado (o relógio do teste não anda sozinho) — esses testes não são medidos;
//   3. mock que não passa por `vi.fn` nem por `fetch` (uma função `async` escrita à mão dentro de um `vi.mock`) só é
//      pego pelo modo da pintura.
// Para saber qual modo reprova um teste, rode o arquivo com um deles desligado (`ATRASO_MOCK=0` ou `ATRASO_PINTURA=0`).
import { vi } from "vitest";

const ATRASO_MS = Number(process.env.ATRASO_MS ?? "40");
const ATRASAR_MOCK = (process.env.ATRASO_MOCK ?? "1") === "1";
const ATRASAR_PINTURA = (process.env.ATRASO_PINTURA ?? "1") === "1";

// capturado antes de qualquer `vi.useFakeTimers()`: o atraso é sempre de relógio real
const timeoutReal = globalThis.setTimeout.bind(globalThis);

type Funcao = (...args: unknown[]) => unknown;
const MARCA = "__atraso";

function ePromessa(v: unknown): v is PromiseLike<unknown> {
  return typeof v === "object" && v !== null && typeof (v as { then?: unknown }).then === "function";
}

function atrasar(p: PromiseLike<unknown>): Promise<unknown> {
  return Promise.resolve(p).then(
    (v) => new Promise((res) => timeoutReal(() => res(v), ATRASO_MS)),
    (e) => new Promise((_, rej) => timeoutReal(() => rej(e), ATRASO_MS)),
  );
}

// O mock continua sendo o mesmo para o `expect` (chamadas, `toHaveBeenCalledWith`): só o que ele DEVOLVE atrasa.
function embrulhar<T>(alvo: T): T {
  if (typeof alvo !== "function" || (alvo as unknown as Record<string, unknown>)[MARCA]) return alvo;
  const fn = alvo as unknown as Funcao;
  const proxy: Funcao = new Proxy(fn, {
    apply(f, thisArg, args) {
      const r: unknown = Reflect.apply(f, thisArg, args);
      return ePromessa(r) && !vi.isFakeTimers() ? atrasar(r) : r;
    },
    get(f, prop) {
      if (prop === MARCA) return true;
      const v: unknown = Reflect.get(f, prop, f);
      // `mockResolvedValue` & cia. devolvem o próprio mock para encadear: devolve-se o embrulho no lugar
      if (typeof v === "function" && typeof prop === "string" && prop.startsWith("mock")) {
        return (...a: unknown[]) => {
          const r: unknown = (v as Funcao).apply(f, a);
          return r === f ? proxy : r;
        };
      }
      return v;
    },
  });
  return proxy as unknown as T;
}

if (ATRASAR_MOCK) {
  const viMutavel = vi as unknown as { fn: Funcao; stubGlobal: (nome: string, valor: unknown) => unknown };
  const fnOriginal = viMutavel.fn.bind(vi);
  viMutavel.fn = (...a) => embrulhar(fnOriginal(...a));

  let fetchAtual: unknown = globalThis.fetch;
  Object.defineProperty(globalThis, "fetch", {
    configurable: true,
    enumerable: true,
    get: () => fetchAtual,
    set: (v: unknown) => {
      fetchAtual = embrulhar(v);
    },
  });
  const stubOriginal = viMutavel.stubGlobal.bind(vi);
  viMutavel.stubGlobal = (nome, valor) => stubOriginal(nome, nome === "fetch" ? embrulhar(valor) : valor);
}

if (ATRASAR_PINTURA) {
  const imediatoReal = globalThis.setImmediate as unknown as Funcao;
  (globalThis as unknown as { setImmediate: Funcao }).setImmediate = (fn, ...args) =>
    vi.isFakeTimers() ? imediatoReal(fn, ...args) : timeoutReal(() => imediatoReal(fn, ...args), ATRASO_MS);
}
