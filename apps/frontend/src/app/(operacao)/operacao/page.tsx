"use client";

// ADR-0016 — Câmaras na plataforma (porte de console-operador.html, arquétipo cockpit + lista). Metadado de cada
// Casa e o estado do handoff; nada de dentro dela. As colunas de plano/MRR do desenho esperam o billing (12.2),
// que ainda não existe — não aparecem em vez de aparecer vazias.

import Link from "next/link";
import { useMemo, useState } from "react";
import { useAuth } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import { type Casa, type Pedido, rotuloEstadoCasa, rotuloMotivo, useCasas } from "@/lib/use-operacao";
import { atividade, haQuanto } from "./quando";

export default function CamarasNaPlataforma() {
  const { token } = useAuth();
  const { dados, estado } = useCasas(token);
  const [filtroEstado, setFiltroEstado] = useState("todos");
  const [filtroUf, setFiltroUf] = useState("todas");
  const [busca, setBusca] = useState("");

  const casas = useMemo(() => {
    const termo = busca.trim().toLocaleLowerCase("pt-BR");
    return (dados?.casas ?? []).filter(
      (c) =>
        (filtroEstado === "todos" || c.estado === filtroEstado) &&
        (filtroUf === "todas" || c.uf === filtroUf) &&
        (!termo || `${c.nome} ${c.nomeCurto ?? ""} ${c.municipio?.nome ?? ""}`.toLocaleLowerCase("pt-BR").includes(termo)),
    );
  }, [dados, filtroEstado, filtroUf, busca]);
  const ufs = useMemo(() => Array.from(new Set((dados?.casas ?? []).map((c) => c.uf))).sort(), [dados]);

  return (
    <>
      <div className="op-pg-cab">
        <div>
          <p className="eyebrow">Operação · O Plenário</p>
          <h1>Câmaras na plataforma</h1>
          <p className="sub">Visão supratenant. Cada câmara é um tenant isolado — você opera, ela é dona dos dados.</p>
        </div>
        <Link className="btn btn-primaria" href={comToken("/operacao/casas/nova", token)}>Provisionar câmara</Link>
      </div>

      {estado === "sem-sessao" && (
        <p className="op-aviso erro" role="alert">
          Sua sessão do console expirou. <a className="op-link" href="/operacao/entrar">Entrar de novo</a>
        </p>
      )}
      {estado === "erro" && <p className="op-aviso erro" role="alert">Não deu para carregar as câmaras agora.</p>}

      <div className="op-metricas" aria-live="polite">
        <div className="op-mt"><span className="t">Câmaras ativas</span><b>{dados?.resumo.ativas ?? "—"}</b></div>
        <div className="op-mt"><span className="t">Aguardando o 1º administrador</span><b>{dados?.resumo.aguardandoAdmin ?? "—"}</b></div>
        <div className="op-mt"><span className="t">Com acesso restrito</span><b>{dados?.resumo.suspensas ?? "—"}</b></div>
        <div className="op-mt"><span className="t">No registro</span><b>{dados?.resumo.total ?? "—"}</b></div>
      </div>

      {(dados?.pendentes?.length ?? 0) > 0 && <FilaDoSegundoOperador pedidos={dados!.pendentes!} token={token} />}

      <div className="op-barra">
        <label className="op-filtro">
          Status
          <select value={filtroEstado} onChange={(e) => setFiltroEstado(e.target.value)}>
            <option value="todos">todos</option>
            <option value="ativo">Ativa</option>
            <option value="provisionar">Aguardando 1º admin</option>
            <option value="suspenso">Suspensa</option>
          </select>
        </label>
        <label className="op-filtro">
          UF
          <select value={filtroUf} onChange={(e) => setFiltroUf(e.target.value)}>
            <option value="todas">todas</option>
            {ufs.map((u) => <option key={u} value={u}>{u}</option>)}
          </select>
        </label>
        <label className="op-busca">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></svg>
          <span className="sr-only">Buscar câmara</span>
          <input type="search" placeholder="Buscar câmara ou município" value={busca} onChange={(e) => setBusca(e.target.value)} />
        </label>
      </div>

      <div className="op-tabela-card">
        <table>
          <caption className="sr-only">Câmaras-tenant com status, atividade e ações</caption>
          <thead>
            <tr><th scope="col">Câmara</th><th scope="col">Status</th><th scope="col">Atividade</th><th scope="col"><span className="sr-only">Ações</span></th></tr>
          </thead>
          <tbody>
            {casas.map((c) => <Linha key={c.enteId} casa={c} token={token} />)}
          </tbody>
        </table>
        {estado === "pronto" && casas.length === 0 && (
          <p className="op-vazio">
            {dados?.casas.length ? "Nenhuma câmara com esses filtros." : "Nenhuma câmara no registro ainda. Provisione a primeira."}
          </p>
        )}
        {estado === "carregando" && <p className="op-vazio">Carregando as câmaras…</p>}
      </div>

      <div className="op-ciclo">
        <div className="lc">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="M7 11V7a5 5 0 0 1 10 0v4" /><rect x="5" y="11" width="14" height="10" rx="2" /></svg>
          <span><b>Provisionamento é handoff, não controle.</b> A Operação cria o tenant e convida o 1º administrador da câmara; o controle passa quando ele entra. Enquanto “Aguardando 1º admin”, a Operação só configura — não opera dados.</span>
        </div>
        <div className="lc">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="M12 3l8 4v5c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7z" /></svg>
          <span><b>Tudo o que a Operação faz fica registrado.</b> Cada provisionamento, convite e ajuste entra numa corrente selada que ninguém edita depois.</span>
        </div>
      </div>
    </>
  );
}

function Linha({ casa, token }: { casa: Casa; token: string | null }) {
  const href = comToken(`/operacao/casas/${casa.enteId}`, token);
  return (
    <tr>
      <td>
        <div className="op-cam">
          <span className="op-uf" aria-hidden="true">{casa.uf}</span>
          <Link href={href}>
            <b>{casa.nome}</b>
            <span>{casa.municipio?.nome ? `${casa.municipio.nome} · ${casa.uf}` : casa.uf}</span>
          </Link>
        </div>
      </td>
      <td>
        <span className={`op-st op-st-${casa.estado}`}><span className="dot" aria-hidden="true" />{rotuloEstadoCasa(casa.estado)}</span>
      </td>
      <td className="op-ativ">{atividade(casa)}</td>
      <td><Link className="op-link" href={href} aria-label={`Abrir ${casa.nome}`}>Abrir</Link></td>
    </tr>
  );
}

/** ADR-0018: os pedidos de suspensão/encerramento que esperam o 2º operador, de todas as câmaras (mais antigo primeiro). */
function FilaDoSegundoOperador({ pedidos, token }: { pedidos: Pedido[]; token: string | null }) {
  return (
    <section className="op-fila" aria-labelledby="titulo-fila">
      <h2 id="titulo-fila">Aguardando o 2º operador</h2>
      <ul>
        {pedidos.map((p) => (
          <li key={p.id}>
            <b>{p.casaNome ?? "Câmara"}</b>
            <span>
              {p.acao === "encerrar" ? "Encerramento" : "Suspensão"} · {rotuloMotivo(p.motivo)} · pedido por{" "}
              {p.pedidoPor ?? "—"} {haQuanto(p.pedidoEm)}
            </span>
            <Link className="op-link" href={comToken(`/operacao/casas/${p.enteId}`, token)}
              aria-label={`Decidir o pedido de ${p.casaNome ?? "câmara"}`}>Decidir</Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
