import { describe, expect, it } from "vitest";
import { COMISSAO_SEM_NOME, rotularComissao } from "./comissao-vista";
import {
  VOTO_OPCOES,
  rotularVoto,
  rotularEstadoParecer,
  parecerEhTerminal,
  derivarRelatoria,
  derivarMateria,
} from "./parecer-vista";
import type { ParecerEditorOut } from "./contrato-legislativo.gen";

const base: ParecerEditorOut = {
  id: "p1",
  objetoTipo: "proposicao",
  objetoId: "obj1",
  comissaoId: "11111111-1111-1111-1111-111111111111",
  relatorId: null,
  votoRelator: null,
  estado: "em_elaboracao",
  templateId: "t1",
  lockVersion: 0,
  criadoEm: "2026-05-01T10:00:00Z",
  objeto: {
    id: "obj1",
    tipo: "projeto_lei",
    ano: 2026,
    sequencial: 42,
    urnLex: "urn:x",
    ementa: "Institui o Programa Municipal de Hortas Comunitárias.",
  },
  relatorio: "",
  analise: "",
  textoEstado: "vazio",
  textoNumeroVersao: null,
};

describe("VOTO_OPCOES", () => {
  it("expõe as 3 opções fixas, na ordem visual do mockup (com-emendas, favorável, contrário)", () => {
    expect(VOTO_OPCOES.map((o) => o.valor)).toEqual(["favoravel_com_emendas", "favoravel", "contrario"]);
  });

  it("cada opção carrega rótulo humano e classe de estilo do mockup", () => {
    expect(VOTO_OPCOES).toEqual([
      { valor: "favoravel_com_emendas", rotulo: "Favorável com emendas", classe: "ve" },
      { valor: "favoravel", rotulo: "Favorável", classe: "vf" },
      { valor: "contrario", rotulo: "Contrário", classe: "vc" },
    ]);
  });
});

describe("rotularVoto", () => {
  it("rotula as 3 opções conhecidas", () => {
    expect(rotularVoto("favoravel")).toBe("Favorável");
    expect(rotularVoto("favoravel_com_emendas")).toBe("Favorável com emendas");
    expect(rotularVoto("contrario")).toBe("Contrário");
  });

  it("nulo/ausente -> 'Sem voto registrado'", () => {
    expect(rotularVoto(null)).toBe("Sem voto registrado");
    expect(rotularVoto(undefined)).toBe("Sem voto registrado");
  });

  it("voto fora do vocabulário das 3 opções (import legado/outro cliente) -> degrada pro valor cru", () => {
    expect(rotularVoto("pela_retirada")).toBe("pela_retirada");
  });
});

describe("rotularEstadoParecer", () => {
  it("rotula os 4 desfechos terminais", () => {
    expect(rotularEstadoParecer("aprovado")).toBe("Aprovado");
    expect(rotularEstadoParecer("rejeitado")).toBe("Rejeitado");
    expect(rotularEstadoParecer("prejudicado")).toBe("Prejudicado");
    expect(rotularEstadoParecer("prazo_vencido")).toBe("Prazo vencido");
  });

  it("estado não-terminal (template-driven) degrada pro valor cru, nunca lança", () => {
    expect(rotularEstadoParecer("em_elaboracao")).toBe("em_elaboracao");
  });
});

describe("parecerEhTerminal", () => {
  it("true pros 4 desfechos terminais", () => {
    expect(parecerEhTerminal("aprovado")).toBe(true);
    expect(parecerEhTerminal("rejeitado")).toBe(true);
    expect(parecerEhTerminal("prejudicado")).toBe(true);
    expect(parecerEhTerminal("prazo_vencido")).toBe(true);
  });

  it("false pra estado não-terminal (em elaboração etc.)", () => {
    expect(parecerEhTerminal("em_elaboracao")).toBe(false);
  });
});

describe("derivarRelatoria", () => {
  it("relatorId ausente -> relatorRotulo nulo (omite a linha, nunca inventa nome)", () => {
    const v = derivarRelatoria(base);
    expect(v.relatorRotulo).toBeNull();
  });

  // Defeito #11 do ledger (`MATA`): esta asserção era `expect(v.comissaoId).toBe(base.comissaoId)` —
  // um teste que EXIGIA o vazamento. O id não sai mais do view-model, então não há por onde a tela
  // imprimi-lo (ver comissao-vista.ts para por que o nome não existe do lado de cá).
  it("a comissão sai como RÓTULO, nunca como id — o UUID não atravessa o view-model", () => {
    const v = derivarRelatoria(base);
    expect(v.comissaoNome).toBeNull();
    expect(rotularComissao(v.comissaoNome)).toBe(COMISSAO_SEM_NOME);
    expect(JSON.stringify(v)).not.toContain(base.comissaoId);
  });

  it("relatorId presente -> rótulo honesto sem nome resolvido (carry F2/FE)", () => {
    const v = derivarRelatoria({ ...base, relatorId: "r1" });
    expect(v.relatorRotulo).toBe("Relator designado");
  });
});

describe("derivarMateria", () => {
  it("objeto presente -> número formatado + ementa", () => {
    const v = derivarMateria(base);
    expect(v).not.toBeNull();
    expect(v?.numero).toBe("PL 42/2026");
    expect(v?.ementa).toBe(base.objeto?.ementa);
  });

  it("objeto ausente (nil) -> nulo, nunca inventa matéria", () => {
    const v = derivarMateria({ ...base, objeto: null });
    expect(v).toBeNull();
  });
});
