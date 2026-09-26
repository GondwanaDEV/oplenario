import { describe, expect, it, vi } from "vitest";
import { escolherVoz, leitorDoNavegador } from "./voz";

type Fala = { texto: string; lang?: string; voice?: unknown; onstart?: () => void; onend?: () => void; onerror?: (e: unknown) => void };

function sinteseFalsa(vozes = [{ name: "Luciana", lang: "pt-BR" }]) {
  const faladas: Fala[] = [];
  return {
    faladas,
    s: {
      getVoices: () => vozes as unknown as SpeechSynthesisVoice[],
      speak: vi.fn((u: unknown) => faladas.push(u as Fala)),
      cancel: vi.fn(),
      pause: vi.fn(),
      resume: vi.fn(),
    },
    fabrica: (t: string) => ({ texto: t }) as unknown as SpeechSynthesisUtterance,
  };
}

describe("voz", () => {
  it("prefere pt-BR, aceita outro pt, e diz quando não há voz em português", () => {
    expect(escolherVoz([{ name: "A", lang: "en-US" }, { name: "B", lang: "pt-PT" }, { name: "C", lang: "pt_BR" }])).toBe("C");
    expect(escolherVoz([{ name: "B", lang: "pt-PT" }])).toBe("B");
    expect(escolherVoz([{ name: "A", lang: "en-US" }])).toBeNull();
    expect(leitorDoNavegador(undefined).voz()).toBeNull();
  });

  it("lê trecho a trecho, avisa em que trecho está e quando acabou", () => {
    const { s, faladas, fabrica } = sinteseFalsa();
    const vistos: number[] = [];
    const fim = vi.fn();
    leitorDoNavegador(s, fabrica).ler(
      [{ paragrafo: 0, texto: "Um." }, { paragrafo: 1, texto: "Dois." }],
      { trecho: (i) => vistos.push(i), fim, erro: vi.fn() },
    );
    expect(faladas.map((f) => [f.texto, f.lang])).toEqual([["Um.", "pt-BR"]]);
    faladas[0].onstart?.();
    faladas[0].onend?.();
    faladas[1].onstart?.();
    faladas[1].onend?.();
    expect(vistos).toEqual([0, 1]);
    expect(fim).toHaveBeenCalledOnce();
  });

  it("parar cancela, não segue para o próximo trecho e não é erro", () => {
    const { s, faladas, fabrica } = sinteseFalsa();
    const erro = vi.fn();
    const c = leitorDoNavegador(s, fabrica).ler([{ paragrafo: 0, texto: "Um." }, { paragrafo: 0, texto: "Dois." }], {
      trecho: vi.fn(), fim: vi.fn(), erro,
    });
    c.parar();
    faladas[0].onerror?.({ error: "interrupted" });
    faladas[0].onend?.();
    expect(faladas).toHaveLength(1);
    expect(erro).not.toHaveBeenCalled();
    expect(s.cancel).toHaveBeenCalledTimes(2);
  });
});
