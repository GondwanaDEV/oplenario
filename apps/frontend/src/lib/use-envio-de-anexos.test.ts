import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { useEnvioDeAnexos } from "./use-envio-de-anexos";

const arq = (nome: string) => new File(["x"], nome);

/** Um `enviarUm` cujas chamadas so' terminam quando o teste manda (para ver o hook no meio do envio). */
function enviadorManual() {
  const chamadas: { nome: string; termina: (r: { ok: true } | { ok: false; mensagem: string }) => void }[] = [];
  const enviarUm = (a: File) => new Promise<{ ok: true } | { ok: false; mensagem: string }>((termina) => chamadas.push({ nome: a.name, termina }));
  return { chamadas, enviarUm };
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("useEnvioDeAnexos — a fila de envio", () => {
  it("sobe um a um, na ordem escolhida, e chama aoTerminar UMA vez no fim", async () => {
    const m = enviadorManual();
    const fim = vi.fn();
    const { result } = renderHook(() => useEnvioDeAnexos(m.enviarUm, fim));
    let p: Promise<void>;
    act(() => { p = result.current.enviar([arq("a.pdf"), arq("b.pdf")]); });
    await waitFor(() => expect(m.chamadas).toHaveLength(1));
    expect(m.chamadas[0].nome).toBe("a.pdf");
    expect(result.current.enviando).toBe(true);
    await act(async () => { m.chamadas[0].termina({ ok: true }); });
    await waitFor(() => expect(m.chamadas).toHaveLength(2));
    await act(async () => { m.chamadas[1].termina({ ok: true }); await p; });
    expect(result.current.itens.map((i) => i.fase)).toEqual(["ok", "ok"]);
    expect(result.current.enviando).toBe(false);
    expect(fim).toHaveBeenCalledTimes(1);
  });

  it("SEM reentrância: enviar e tentarDeNovo sao ignorados enquanto ha envio em andamento", async () => {
    const m = enviadorManual();
    const { result } = renderHook(() => useEnvioDeAnexos(m.enviarUm));
    let p: Promise<void>;
    act(() => { p = result.current.enviar([arq("a.pdf")]); });
    await waitFor(() => expect(m.chamadas).toHaveLength(1));
    await act(async () => {
      await result.current.enviar([arq("outro.pdf")]);   // segundo `enviar` no meio: nada acontece
      await result.current.tentarDeNovo(0);              // e `tentarDeNovo` tambem
    });
    expect(m.chamadas).toHaveLength(1);
    expect(result.current.itens.map((i) => i.arquivo.name)).toEqual(["a.pdf"]);
    await act(async () => { m.chamadas[0].termina({ ok: true }); await p; });
    expect(result.current.enviando).toBe(false);
  });

  it("callback que LANCA vira erro do item, a fila segue e aoTerminar roda uma vez", async () => {
    const fim = vi.fn();
    const enviarUm = vi.fn(async (a: File) => {
      if (a.name === "quebra.pdf") throw new Error("explodiu");
      return { ok: true as const };
    });
    const { result } = renderHook(() => useEnvioDeAnexos(enviarUm, fim));
    await act(async () => { await result.current.enviar([arq("a.pdf"), arq("quebra.pdf"), arq("c.pdf")]); });
    expect(enviarUm).toHaveBeenCalledTimes(3);
    expect(result.current.itens.map((i) => i.fase)).toEqual(["ok", "erro", "ok"]);
    expect(result.current.itens[1].mensagem).toMatch(/Não foi possível anexar agora/);
    expect(result.current.enviando).toBe(false);
    expect(fim).toHaveBeenCalledTimes(1);
  });

  it("tentarDeNovo refaz SO' o item que falhou", async () => {
    let n = 0;
    const enviarUm = vi.fn(async () => (++n === 2 ? { ok: false as const, mensagem: "ruim" } : { ok: true as const }));
    const { result } = renderHook(() => useEnvioDeAnexos(enviarUm));
    await act(async () => { await result.current.enviar([arq("a.pdf"), arq("b.pdf")]); });
    expect(result.current.itens.map((i) => i.fase)).toEqual(["ok", "erro"]);
    await act(async () => { await result.current.tentarDeNovo(1); });
    expect(result.current.itens.map((i) => i.fase)).toEqual(["ok", "ok"]);
    expect(enviarUm).toHaveBeenCalledTimes(3);
  });

  it("avisa antes de sair da pagina (beforeunload) SO' enquanto envia", async () => {
    const m = enviadorManual();
    const { result } = renderHook(() => useEnvioDeAnexos(m.enviarUm));
    const disparar = () => {
      const ev = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(ev);
      return ev.defaultPrevented;
    };
    expect(disparar()).toBe(false);   // ocioso: sai sem aviso
    let p: Promise<void>;
    act(() => { p = result.current.enviar([arq("a.pdf")]); });
    await waitFor(() => expect(m.chamadas).toHaveLength(1));
    expect(disparar()).toBe(true);    // enviando: o navegador pergunta
    await act(async () => { m.chamadas[0].termina({ ok: true }); await p; });
    expect(disparar()).toBe(false);   // terminou: libera
  });

  it("desmontar no meio do envio: tira o aviso, nao mexe em estado e nao chama aoTerminar", async () => {
    const m = enviadorManual();
    const fim = vi.fn();
    const erros = vi.spyOn(console, "error").mockImplementation(() => {});
    const { result, unmount } = renderHook(() => useEnvioDeAnexos(m.enviarUm, fim));
    let p: Promise<void>;
    act(() => { p = result.current.enviar([arq("a.pdf"), arq("b.pdf")]); });
    await waitFor(() => expect(m.chamadas).toHaveLength(1));
    unmount();
    const ev = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(ev);
    expect(ev.defaultPrevented).toBe(false);     // o aviso foi embora com o componente
    await act(async () => { m.chamadas[0].termina({ ok: true }); await p; });
    expect(fim).not.toHaveBeenCalled();           // ninguem esta' olhando: nao dispara a releitura
    expect(erros).not.toHaveBeenCalled();         // e nenhum setState em componente desmontado
    expect(m.chamadas.length).toBeLessThanOrEqual(2);
  });
});
