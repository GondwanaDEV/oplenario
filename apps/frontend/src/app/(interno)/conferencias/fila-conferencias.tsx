"use client";

// A fila das notas técnicas da IA e o painel do agente institucional. Só quem tem o papel `admin_ente` liga e desliga
// a conferência automática (docs/25 3.3); a secretaria vê em que pé ela está.

import Link from "next/link";
import { useState } from "react";
import { usePapeis } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import {
  ABAS,
  linhaDaNota,
  linhaDoAgente,
  numeroDaNota,
  vazioDaFila,
  type AgenteInstitucional,
  type FiltroFila,
} from "@/lib/conferencias-vista";
import { mudarAgente, useAgentesInstitucionais, useNotasTecnicas } from "@/lib/use-conferencias";
import type { NotaTecnicaResumoOut } from "@/lib/contrato-legislativo.gen";

// Exportado: a área do administrador da Casa (/administracao) monta o mesmo painel — é ele quem liga e desliga.
export function PainelAgente({ token, agentes }: { token: string | null; agentes: ReturnType<typeof useAgentesInstitucionais> }) {
  const { papeis } = usePapeis();
  const admin = papeis.includes("admin_ente");
  const { estado, setEstado } = agentes;
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  if (estado.fase !== "pronto") return null;
  const a = estado.dado.itens[0];
  if (!a) return null;

  async function alternar(ag: AgenteInstitucional) {
    setEnviando(true);
    setErro(null);
    const r = await mudarAgente(token, ag.agente, !ag.ligado);
    setEnviando(false);
    if (r.ok) setEstado({ fase: "pronto", dado: r.dado });
    else setErro(r.mensagem);
  }

  return (
    <section className={`conf-agente ${a.ligado ? "conf-agente-ligado" : ""}`} aria-labelledby="conf-agente-titulo">
      <div className="conf-agente-texto">
        <h2 id="conf-agente-titulo">Conferência automática</h2>
        <p className="conf-agente-estado">
          <span className="conf-ponto" aria-hidden="true" /> {linhaDoAgente(a)}
        </p>
        <p className="conf-agente-limite">A IA só lê e deixa rascunho: não protocola, não despacha, não publica nada.</p>
        {erro && <p className="conf-erro" role="alert">{erro}</p>}
      </div>
      {admin ? (
        <button type="button" className={a.ligado ? "btn btn-contorno" : "btn btn-primaria"} disabled={enviando}
          onClick={() => alternar(a)}>
          {enviando ? "Salvando…" : a.ligado ? "Desligar" : "Ligar a conferência"}
        </button>
      ) : (
        <p className="conf-agente-quem">Quem liga e desliga é o administrador da Casa.</p>
      )}
    </section>
  );
}

function Cartao({ n, token }: { n: NotaTecnicaResumoOut; token: string | null }) {
  return (
    <li className="conf-item">
      <Link href={comToken(`/conferencias/${n.id}`, token)}>
        <span className="conf-numero">{numeroDaNota(n)}</span>
        <span className="conf-ementa">{n.ementa}</span>
        <span className="conf-linha">
          {linhaDaNota(n)}
          {n.estado === "pendente" && n.incerteza === "revisar_com_atencao" && (
            <span className="conf-atencao">Ler com atenção</span>
          )}
        </span>
      </Link>
    </li>
  );
}

export function FilaConferencias({ token = null }: { token?: string | null }) {
  const [filtro, setFiltro] = useState<FiltroFila>("pendente");
  const { estado } = useNotasTecnicas(token, filtro);
  const agentes = useAgentesInstitucionais(token);
  const ligado = agentes.estado.fase === "pronto" ? (agentes.estado.dado.itens[0]?.ligado ?? null) : null;
  const itens = estado.fase === "pronto" ? estado.dado.itens : [];
  return (
    <main className="envelope conf">
      <header className="conf-cabeca">
        <h1>Conferência das proposições</h1>
        <p className="conf-sub">
          A cada proposição protocolada, a IA confere o texto contra a Lei Orgânica e o Regimento Interno e deixa aqui
          uma nota técnica em rascunho, citando os dispositivos. Ela não decide nada: você lê, aproveita ou descarta.
        </p>
      </header>

      <PainelAgente token={token} agentes={agentes} />

      <nav className="conf-abas" aria-label="Filtrar as notas">
        {ABAS.map((a) => (
          <button key={a.filtro} type="button" className="conf-aba" aria-pressed={filtro === a.filtro}
            onClick={() => setFiltro(a.filtro)}>
            {a.rotulo}
            {a.filtro === filtro && estado.fase === "pronto" ? ` (${itens.length})` : ""}
          </button>
        ))}
      </nav>

      {estado.fase === "carregando" && <p role="status">Carregando…</p>}
      {estado.fase === "erro" && <p className="conf-erro" role="alert">{estado.mensagem}</p>}
      {estado.fase === "pronto" &&
        (itens.length === 0 ? (
          <p className="conf-vazio">{vazioDaFila(filtro, ligado)}</p>
        ) : (
          <ul className="conf-lista">{itens.map((n) => <Cartao key={n.id} n={n} token={token} />)}</ul>
        ))}
    </main>
  );
}
