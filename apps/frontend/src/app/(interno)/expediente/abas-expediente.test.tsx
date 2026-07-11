import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { AuthProvider } from "@/lib/auth";
import { AbasExpediente } from "./abas-expediente";

// Mirror do padrão de composição de topo.test.tsx — useAuth() exige AuthProvider ancestral.
describe("AbasExpediente", () => {
  it("marca a aba atual com aria-current e preserva o ?token= dev nos links", () => {
    render(
      <AuthProvider tokenQuery="abc123">
        <AbasExpediente atual="protocolo" />
      </AuthProvider>,
    );
    expect(screen.getByRole("link", { name: "Gerar documento" }).getAttribute("href")).toBe(
      "/expediente?token=abc123",
    );
    expect(screen.getByRole("link", { name: "Protocolo geral" }).getAttribute("aria-current")).toBe("page");
    expect(screen.getByRole("link", { name: "Modelos" }).getAttribute("aria-current")).toBeNull();
    expect(screen.getByRole("link", { name: "Recebidos" }).getAttribute("href")).toBe(
      "/expediente/recebidos?token=abc123",
    );
  });
});
