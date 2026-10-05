"use client";

// A moldura dos layouts interno e do app do vereador: envolve a página (o que a Clara empurra quando aberta) e põe a
// Clara ao lado para quem pode perguntar — a secretaria e os vereadores (o core confere de novo: POST /agente/perguntas
// exige um dos dois). A div existe SEMPRE, com ou sem a Clara: trocar o elemento quando os papéis chegam remontaria a
// página inteira. Também guarda a dica da tela (`useDicaDaClara`), que a página publica e a Clara mostra.

import { useRef } from "react";
import { usePathname } from "next/navigation";
import { useAuth, usePapeis } from "@/lib/auth";
import { Clara } from "./clara";
import { ProvedorDaDica } from "./dica";

/** Onde a Clara não aparece: a própria tela cheia do assistente (a da secretaria e a do app do vereador). */
export const ROTAS_SEM_CLARA = new Set(["/assistente", "/vereador/assistente"]);

export function podeUsarAClara(papeis: string[]): boolean {
  return papeis.includes("secretario") || papeis.includes("vereador");
}

/** `publico`: o app do vereador pede sempre o conjunto do vereador (quem também é secretaria escolhe pela tela onde
 *  está); sem ele, quem é secretaria deixa o core escolher e quem só é vereador pede o do vereador. */
export function MolduraDaClara({ children, publico: publicoDaTela }: { children: React.ReactNode; publico?: "vereador" }) {
  const { token } = useAuth();
  const { papeis, estado } = usePapeis();
  const caminho = usePathname() ?? "";
  const moldura = useRef<HTMLDivElement>(null);
  const ativa = estado === "pronto" && podeUsarAClara(papeis) && !ROTAS_SEM_CLARA.has(caminho);
  // Quem só é vereador pergunta com o conjunto do vereador; quem é secretaria deixa o core escolher (o da secretaria).
  const publico = publicoDaTela ?? (papeis.includes("secretario") ? undefined : "vereador");
  return (
    <ProvedorDaDica>
      <div ref={moldura} className="clara-moldura" data-clara-ativa={ativa ? "" : undefined}>
        {children}
      </div>
      {ativa && <Clara token={token} publico={publico} moldura={moldura} />}
    </ProvedorDaDica>
  );
}
