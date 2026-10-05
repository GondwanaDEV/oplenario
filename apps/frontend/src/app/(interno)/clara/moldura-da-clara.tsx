"use client";

// A moldura do layout interno: envolve a página (o que a Clara empurra quando aberta) e põe a Clara ao lado para quem
// pode perguntar — a secretaria e os vereadores (o core confere de novo: POST /agente/perguntas exige um dos dois).
// A div existe SEMPRE, com ou sem a Clara: trocar o elemento quando os papéis chegam remontaria a página inteira.

import { useRef } from "react";
import { usePathname } from "next/navigation";
import { useAuth, usePapeis } from "@/lib/auth";
import { Clara } from "./clara";

/** Onde a Clara não aparece: a própria tela cheia do assistente. */
export const ROTAS_SEM_CLARA = new Set(["/assistente"]);

export function podeUsarAClara(papeis: string[]): boolean {
  return papeis.includes("secretario") || papeis.includes("vereador");
}

export function MolduraDaClara({ children }: { children: React.ReactNode }) {
  const { token } = useAuth();
  const { papeis, estado } = usePapeis();
  const caminho = usePathname() ?? "";
  const moldura = useRef<HTMLDivElement>(null);
  const ativa = estado === "pronto" && podeUsarAClara(papeis) && !ROTAS_SEM_CLARA.has(caminho);
  // Quem só é vereador pergunta com o conjunto do vereador; quem é secretaria deixa o core escolher (o da secretaria).
  const publico = papeis.includes("secretario") ? undefined : "vereador";
  return (
    <>
      <div ref={moldura} className="clara-moldura" data-clara-ativa={ativa ? "" : undefined}>
        {children}
      </div>
      {ativa && <Clara token={token} publico={publico} moldura={moldura} />}
    </>
  );
}
