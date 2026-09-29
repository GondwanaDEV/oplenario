import { describe, expect, it } from "vitest";
import { derivarObservabilidade, rotuloJanela, tempo, type ObservabilidadeIA } from "./observabilidade-ia-vista";

const AG = { execucoes: 0, indisponiveis: 0, latenciaP50Ms: null, latenciaP95Ms: null, custo: "0", parcial: false };

function obs(p: Partial<ObservabilidadeIA>): ObservabilidadeIA {
  return { disponivel: true, horas: 24, desde: null, ate: null, casas: 0, moeda: "USD", total: AG, porOperacao: [],
    porFornecedor: [], motivosIndisponivel: [], porHora: [], ...p };
}

describe("observabilidade-ia-vista", () => {
  it("tempo em ms abaixo de 1 s, em segundos acima", () => {
    expect(tempo(null)).toBe("—");
    expect(tempo(120)).toBe("120 ms");
    expect(tempo(1940)).toBe("1,9 s");
  });

  it("janela em horas ou dias", () => {
    expect(rotuloJanela(24)).toBe("24 horas");
    expect(rotuloJanela(168)).toBe("7 dias");
  });

  it("a barra de cada recurso é relativa ao maior p95; sem medida, barra vazia", () => {
    const v = derivarObservabilidade(obs({
      total: { ...AG, execucoes: 3 },
      porOperacao: [
        { ...AG, operacao: "ata.redigir", execucoes: 1, latenciaP95Ms: 4000 },
        { ...AG, operacao: "resumo.redigir", execucoes: 1, latenciaP95Ms: 1000 },
        { ...AG, operacao: "nova.coisa", execucoes: 1 },
      ],
    }));
    expect(v.recursos.map((r) => [r.nome, r.largura])).toEqual([
      ["Rascunho da ata", 100], ["Resumo cidadão", 25], ["nova.coisa", 0],
    ]);
  });

  it("alerta quando 5% ou mais não rodaram; proporção pequena não vira 0%", () => {
    const alto = derivarObservabilidade(obs({ total: { ...AG, execucoes: 20, indisponiveis: 1 } }));
    expect(alto.metricas.find((m) => m.titulo === "Não rodaram")).toMatchObject({ valor: "5%", alerta: true });
    const baixo = derivarObservabilidade(obs({ total: { ...AG, execucoes: 2000, indisponiveis: 1 } }));
    expect(baixo.metricas.find((m) => m.titulo === "Não rodaram")).toMatchObject({ valor: "<0,1%", alerta: false });
  });

  it("série por hora: altura relativa ao pico e legenda terminando em agora", () => {
    const porHora = Array.from({ length: 24 }, (_, i) => ({
      inicio: new Date(Date.UTC(2026, 8, 28, 13 + i)).toISOString(), execucoes: i === 5 ? 10 : 5, indisponiveis: 0,
    }));
    const v = derivarObservabilidade(obs({ total: { ...AG, execucoes: 125 }, porHora }));
    expect(v.barras[5].altura).toBe(100);
    expect(v.barras[0].altura).toBe(50);
    expect(v.legenda).toHaveLength(5);
    expect(v.legenda[0]).toBe("10h"); // 13h UTC = 10h em Fortaleza
    expect(v.legenda.at(-1)).toBe("agora");
  });

  it("vazio só quando a IA respondeu e não houve execução", () => {
    expect(derivarObservabilidade(obs({})).vazio).toMatch(/Nenhuma execução/);
    expect(derivarObservabilidade(obs({ disponivel: false, total: null })).vazio).toBeNull();
  });
});
