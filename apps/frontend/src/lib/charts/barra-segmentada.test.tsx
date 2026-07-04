import { describe, expect, it } from "vitest";
import { largurasPercentuais } from "./barra-segmentada";

describe("largurasPercentuais", () => {
  it("distribui proporcional ao total", () => {
    const r = largurasPercentuais([{ rotulo: "a", n: 1 }, { rotulo: "b", n: 3 }]);
    expect(r[0].percentual).toBeCloseTo(25, 1);
    expect(r[1].percentual).toBeCloseTo(75, 1);
  });
  it("total 0 -> todos 0% (sem NaN)", () => {
    const r = largurasPercentuais([{ rotulo: "a", n: 0 }]);
    expect(r[0].percentual).toBe(0);
  });
});
