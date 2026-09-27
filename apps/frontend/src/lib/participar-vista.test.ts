import { describe, expect, it } from "vitest";
import { derivarVistaParticipar, hrefEntrarComGovbr } from "./participar-vista";

const ENTE = "10000000-0000-0000-0000-000000000001";
const corpo = (govbr: unknown) => ({ "ente-id": ENTE, "nome-oficial": "Câmara Municipal de Baturité", govbr });

describe("derivarVistaParticipar — a tela 'Entrar para participar' (ADR-0015)", () => {
  it("Casa com gov.br ligado: pronta, com o nome oficial", () => {
    expect(derivarVistaParticipar({ status: 200, corpo: corpo(true) }, undefined)).toEqual({
      estado: "pronto",
      nome: "Câmara Municipal de Baturité",
    });
  });

  it("Casa sem o broker: diz que ainda não está disponível (nunca oferece o botão)", () => {
    expect(derivarVistaParticipar({ status: 200, corpo: corpo(false) }, undefined).estado).toBe("indisponivel");
    expect(derivarVistaParticipar({ status: 200, corpo: corpo(undefined) }, undefined).estado).toBe("indisponivel");
  });

  it("o BFF devolveu erro=indisponivel: vale o que o BFF viu, mesmo que a descoberta mude no meio", () => {
    expect(derivarVistaParticipar({ status: 200, corpo: corpo(true) }, "indisponivel").estado).toBe("indisponivel");
  });

  it("Casa inexistente e falha genérica são estados próprios", () => {
    expect(derivarVistaParticipar({ status: 404, corpo: null }, undefined).estado).toBe("nao-encontrada");
    expect(derivarVistaParticipar({ status: 0, corpo: null }, undefined).estado).toBe("erro");
    expect(derivarVistaParticipar({ status: 400, corpo: null }, undefined).estado).toBe("erro");
  });
});

describe("hrefEntrarComGovbr", () => {
  it("vai ao BFF de login pedindo o gov.br, e repassa o destino quando houver", () => {
    expect(hrefEntrarComGovbr(ENTE, undefined)).toBe(`/api/auth/login?ente=${ENTE}&via=govbr`);
    expect(hrefEntrarComGovbr(ENTE, "/portal/casa/x/materias/1")).toBe(
      `/api/auth/login?ente=${ENTE}&via=govbr&redirect=%2Fportal%2Fcasa%2Fx%2Fmaterias%2F1`,
    );
  });
});
