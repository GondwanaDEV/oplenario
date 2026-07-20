"use client";

// A inbox do vereador (Onda E fatia 1) — porte reduzido de
// produto/design-system/o-plenario/telas/notificacoes.html: cabeçalho + badge, agrupamento temporal, a
// nota de dedup §5.1 e a lista. FORA desta fatia (spec §5, deliberado): "marcar todas como lidas",
// abas de filtro por tipo (com uma só categoria existindo, aba é teatro) e contador no sino do topo
// (o sino conta PENDÊNCIAS, não notificações — mudar isso quebraria a dedup que a própria tela documenta).
//
// Não-lida é marcada por PONTO + NEGRITO + tinta de fundo — nunca só cor (GUIDELINES-CHECKLIST).
// Composição: useAuth (token, já resolvido pelo GuardVereador do layout) + useMinhasNotificacoes +
// useMarcarLida + derivarInbox (view-model puro).

import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { useMinhasNotificacoes } from "@/lib/use-minhas-notificacoes";
import { useMarcarLida } from "@/lib/use-marcar-lida";
import { derivarInbox, type NotificacaoVista } from "@/lib/notificacoes-vista";
import { comToken } from "@/lib/nav";
import "./notificacoes.css";

export default function PaginaNotificacoes() {
  const { token } = useAuth();
  const { dados, estado, recarregar } = useMinhasNotificacoes(token);
  const { marcar, estado: estadoMarcacao, erro: erroMarcacao } = useMarcarLida(token);
  const vista = derivarInbox(dados);

  if (estado === "erro") {
    return (
      <main className="tela-estado">
        <h1>Não foi possível carregar suas notificações</h1>
        <p>Tente novamente em instantes.</p>
      </main>
    );
  }
  if (estado === "carregando") {
    return (
      <main className="tela-estado">
        <h1>Carregando…</h1>
      </main>
    );
  }

  async function marcarLida(n: NotificacaoVista) {
    try {
      await marcar(n.id);
      await recarregar();
    } catch {
      // o erro já fica exposto via `erroMarcacao`; aqui só evita a unhandled promise rejection.
    }
  }

  return (
    <>
      <div className="nt-cab">
        <span className="eyebrow">Central de notificações</span>
        <h1>
          Notificações
          {vista.naoLidas > 0 && (
            <span className="badge" aria-label={`${vista.naoLidas} não lida${vista.naoLidas > 1 ? "s" : ""}`}>
              {vista.naoLidas}
            </span>
          )}
        </h1>
        <p className="sub">O que mudou no que é seu.</p>
      </div>

      <p className="dedup-nota">
        Aqui você <b>acompanha</b>. O que exige a sua ação fica em <b>Início</b> e sai de lá sozinho quando
        o ato é concluído. “Lido” mora só aqui.
      </p>

      {erroMarcacao && <p className="erro-inline">{erroMarcacao}</p>}

      {vista.vazia && <p className="vazio">Nenhuma notificação por enquanto.</p>}

      {vista.grupos.map((g) => (
        <section className="grupo" key={g.chave}>
          <h2>{g.rotulo}</h2>
          <div className="lista">
            {g.itens.map((n) => (
              <article className={n.lida ? "nt" : "nt nao-lida"} key={n.id}>
                <span className="nt-ic" aria-hidden="true">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M14 3v4a1 1 0 0 0 1 1h4" />
                    <path d="M17 21H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7l5 5v11a2 2 0 0 1-2 2Z" />
                    <path d="M9 13h6" />
                  </svg>
                </span>
                <div className="nt-mid">
                  <h3>{n.assunto}</h3>
                  <p>{n.corpo}</p>
                  {n.href && (
                    <Link className="ir" href={comToken(n.href, token)}>
                      Abrir a ficha →
                    </Link>
                  )}
                </div>
                <div className="nt-dir">
                  <span className="nt-quando">{n.quando}</span>
                  {n.lida ? (
                    <span className="nt-lida-marca">Lida</span>
                  ) : (
                    <>
                      <span className="nt-ponto" role="img" aria-label="Não lida" />
                      <button
                        className="nt-lida"
                        type="button"
                        disabled={estadoMarcacao === "enviando"}
                        onClick={() => marcarLida(n)}
                      >
                        Marcar como lida
                      </button>
                    </>
                  )}
                </div>
              </article>
            ))}
          </div>
        </section>
      ))}
    </>
  );
}
