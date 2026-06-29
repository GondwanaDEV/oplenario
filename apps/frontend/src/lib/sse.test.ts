import { describe, it, expect } from "vitest";
import { extrairFrames } from "./sse";

describe("extrairFrames — parser de frames SSE sobre buffer de texto", () => {
  it("extrai um frame completo (event/data/id) e não deixa resto", () => {
    const { frames, resto } = extrairFrames('event: presenca.registrada\ndata: {"vereador-id":"v1"}\nid: 7\n\n');
    expect(frames).toEqual([{ event: "presenca.registrada", data: '{"vereador-id":"v1"}', id: "7" }]);
    expect(resto).toBe("");
  });

  it("guarda um frame parcial como resto (chegou cortado no meio do chunk)", () => {
    const { frames, resto } = extrairFrames("event: fala.iniciada\ndata: {\"fala");
    expect(frames).toEqual([]);
    expect(resto).toBe('event: fala.iniciada\ndata: {"fala');
  });

  it("extrai múltiplos frames de um chunk e preserva o parcial final como resto", () => {
    const { frames, resto } = extrairFrames("event: a\ndata: 1\n\nevent: b\ndata: 2\n\nevent: c\ndata: 3");
    expect(frames.map((f) => f.event)).toEqual(["a", "b"]);
    expect(resto).toBe("event: c\ndata: 3");
  });

  it("concatena linhas data: múltiplas com \\n (spec SSE)", () => {
    const { frames } = extrairFrames("data: linha1\ndata: linha2\n\n");
    expect(frames[0].data).toBe("linha1\nlinha2");
  });

  it("tolera o espaço opcional após os dois-pontos (com e sem)", () => {
    const { frames } = extrairFrames("event:semespaco\ndata:x\n\n");
    expect(frames[0]).toMatchObject({ event: "semespaco", data: "x" });
  });

  it("ignora linha de comentário (começa com :) — usada como heartbeat", () => {
    const { frames, resto } = extrairFrames(": keep-alive\n\n");
    expect(frames).toEqual([]);
    expect(resto).toBe("");
  });
});
