"use client";

// A barra do console (porte de console-operador.html .topo): marca "Operação · console", a trilha "Supratenant"
// e quem está operando. O nome vem do backend (GET /operacao/eu), nunca de um literal; sem sessão (a tela de
// entrar) a barra mostra só a marca.

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import { useTema } from "@/lib/tema";
import { useOperador } from "@/lib/use-operacao";

export function TopoOperacao() {
  const { tema, alternar } = useTema();
  const { token } = useAuth();
  const rota = usePathname();
  const naEntrada = rota?.startsWith("/operacao/entrar");
  return (
    <header className="topo">
      <div className="envelope topo-grade">
        <a className="marca" href="/operacao" style={{ textDecoration: "none" }}>
          <MarcaOperacao />
          <div>
            <p className="marca-nome">O&nbsp;Plenário</p>
            <p className="marca-orgao">Operação · console</p>
          </div>
        </a>
        <div className="topo-sep" aria-hidden="true" />
        <span className="topo-trilha">Supratenant</span>
        <div className="topo-dir">
          <button
            className="tema-btn"
            type="button"
            aria-pressed={tema === "escuro"}
            onClick={alternar}
            title="Alternar tema claro / escuro"
          >
            {tema === "escuro" ? "☾" : "☀"}
            <span className="tema-rotulo">{tema === "escuro" ? "Escuro" : "Claro"}</span>
          </button>
          {!naEntrada && <QuemOpera token={token} />}
        </div>
      </div>
    </header>
  );
}

/** As áreas do console (sem sessão, na tela de entrar, não aparece). */
export function NavOperacao() {
  const { token } = useAuth();
  const rota = usePathname() ?? "";
  if (rota.startsWith("/operacao/entrar")) return null;
  const naIA = rota === "/operacao/ia" || rota.startsWith("/operacao/ia/");
  return (
    <nav className="op-nav" aria-label="Console">
      <Link href={comToken("/operacao", token)} aria-current={naIA ? undefined : "page"}>Câmaras</Link>
      <Link href={comToken("/operacao/ia", token)} aria-current={naIA ? "page" : undefined}>Observabilidade da IA</Link>
    </nav>
  );
}

function QuemOpera({ token }: { token: string | null }) {
  const { operador, estado } = useOperador(token);
  const nome = operador?.nome ?? (estado === "carregando" ? "Carregando…" : "Operação");
  return (
    <>
      <span className="presidente">
        <span className="avatar" aria-hidden="true">
          {nome.split(" ").map((p) => p[0]).slice(0, 2).join("").toUpperCase()}
        </span>
        <span className="quem">
          <b>{nome}</b>
          <span>Operação</span>
        </span>
      </span>
      {operador && (
        <form method="post" action="/api/operacao/logout">
          <button className="op-sair" type="submit">Sair</button>
        </form>
      )}
    </>
  );
}

function MarcaOperacao() {
  return (
    <svg className="marca-simbolo" viewBox="0 0 34 34" role="img" aria-label="O Plenário">
      <rect width="34" height="34" rx="8" fill="var(--jade)" />
      <rect x="7" y="7" width="9" height="9" rx="2" fill="var(--telha)" />
      <rect x="18" y="7" width="9" height="9" rx="2" fill="var(--cobalto)" />
      <rect x="7" y="18" width="9" height="9" rx="2" fill="var(--amarelo)" />
      <rect x="18" y="18" width="9" height="9" rx="2" fill="var(--jade-claro)" />
    </svg>
  );
}
