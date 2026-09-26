import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useCampainha } from "./use-campainha";
import type { ObservacaoTempo } from "./cronometro";

// AudioContext FALSO: conta quantos osciladores foram agendados (cada toque da campainha agenda 4 parciais,
// e são 3 toques → 12 por campainha) e simula a política de autoplay (nasce `suspended`; `resume()` só
// "libera" quando o teste diz que houve gesto).
let osciladores = 0;
let gestoHouve = false;

class FakeAudioContext {
  state: "suspended" | "running" = gestoHouve ? "running" : "suspended";
  currentTime = 0;
  destination = {};
  resume() {
    if (gestoHouve) this.state = "running";
    return Promise.resolve();
  }
  createGain() {
    const param = { value: 0, setValueAtTime() {}, exponentialRampToValueAtTime() {} };
    return { gain: param, connect: (n: unknown) => n };
  }
  createOscillator() {
    osciladores += 1;
    return {
      type: "sine",
      frequency: { setValueAtTime() {} },
      connect: (n: unknown) => n,
      start() {},
      stop() {},
    };
  }
}

const OSC_POR_CAMPAINHA = 12;

beforeEach(() => {
  osciladores = 0;
  gestoHouve = false;
  vi.stubGlobal("AudioContext", FakeAudioContext);
});

afterEach(() => vi.unstubAllGlobals());

const obs = (falaId: string, esgotado: boolean): ObservacaoTempo => ({ falaId, esgotado });

describe("useCampainha", () => {
  it("toca UMA vez na transição para esgotado vista ao vivo", async () => {
    gestoHouve = true; // TV em quiosque (autoplay liberado)
    const { rerender } = renderHook(({ o }) => useCampainha(o), { initialProps: { o: obs("f1", false) as ObservacaoTempo | null } });
    await act(async () => {});
    expect(osciladores).toBe(0);
    rerender({ o: obs("f1", true) });
    expect(osciladores).toBe(OSC_POR_CAMPAINHA);
    rerender({ o: obs("f1", true) }); // segue esgotada: não repete
    expect(osciladores).toBe(OSC_POR_CAMPAINHA);
  });

  it("abrir a TV com a fala já esgotada não toca", async () => {
    gestoHouve = true;
    renderHook(() => useCampainha(obs("f1", true)));
    await act(async () => {});
    expect(osciladores).toBe(0);
  });

  it("depois do +1 min, se esgotar de novo, toca de novo", async () => {
    gestoHouve = true;
    const { rerender } = renderHook(({ o }) => useCampainha(o), { initialProps: { o: obs("f1", false) as ObservacaoTempo | null } });
    await act(async () => {});
    rerender({ o: obs("f1", true) });
    rerender({ o: obs("f1", false) }); // +1 min
    rerender({ o: obs("f1", true) });
    expect(osciladores).toBe(2 * OSC_POR_CAMPAINHA);
  });

  it("sem gesto o som fica bloqueado; o primeiro clique na tela libera", async () => {
    const { result } = renderHook(() => useCampainha(obs("f1", false)));
    await act(async () => {});
    expect(result.current.somLiberado).toBe(false);
    gestoHouve = true;
    await act(async () => {
      window.dispatchEvent(new Event("pointerdown"));
    });
    expect(result.current.somLiberado).toBe(true);
  });

  it("sem Web Audio no navegador: não lança e nunca diz que o som está liberado", async () => {
    vi.stubGlobal("AudioContext", undefined);
    const { result, rerender } = renderHook(({ o }) => useCampainha(o), { initialProps: { o: obs("f1", false) as ObservacaoTempo | null } });
    await act(async () => {
      window.dispatchEvent(new Event("pointerdown"));
    });
    rerender({ o: obs("f1", true) });
    expect(result.current.somLiberado).toBe(false);
  });
});
