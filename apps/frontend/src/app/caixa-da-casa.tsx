"use client";

// A CAIXA da pessoa da Casa (ADR-0020, Eixo 7) — UM componente para as duas portas: o interno em /caixa e o vereador
// na aba "Avisos" (/notificacoes), que passa a mostrar a mesma caixa. Porte de
// produto/design-system/o-plenario/telas/notificacoes.html (arquétipo lista/inbox: cabeçalho + badge, barra de
// filtro, agrupamento por dia, lista), agora com duas origens na mesma lista: os COMUNICADOS (escritos por uma pessoa,
// com protocolo e, às vezes, ciência) e os AVISOS do sistema (projeção de `paineis`).
//
// Decisões de tela:
//   - os filtros são os quatro da ADR, fixos (ver o cabeçalho de comunicacao-vista.ts);
//   - a faixa do topo diz quantos aguardam ciência e o prazo mais próximo (fatia 3) — é o único acionável da caixa;
//   - o comunicado é lido ABRINDO-O (o servidor grava "lido" ao entregar o detalhe); não há "marcar como lido" de
//     comunicado, que seria uma marca sem leitura. O aviso do sistema continua com "Marcar como lido" (a rota de
//     `paineis`, idempotente);
//   - cada fonte falha sozinha: se os avisos não vêm, a caixa mostra os comunicados e diz o que faltou, e vice-versa;
//   - não lido = ponto com rótulo + negrito + traço lateral, nunca só cor (GUIDELINES-CHECKLIST §5).

import { useEffect, useState } from "react";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import { useMarcarLida } from "@/lib/use-marcar-lida";
import { avisarCaixaMudou, useAvisosDoSistema, useCaixaDeComunicados } from "@/lib/use-comunicados";
import { derivarCaixa, type FiltroDaCaixa, type ItemDaCaixaVista } from "@/lib/comunicacao-vista";
import "./caixa-da-casa.css";

export type SuperficieDaCaixa = "interno" | "vereador";

const CHIP_DA_CIENCIA = { pendente: "chip chip-alerta", vencida: "chip chip-risco", dada: "chip chip-ok" } as const;

export function CaixaDaCasa({
  superficie,
  hrefComunicado,
}: {
  superficie: SuperficieDaCaixa;
  /** A tela do comunicado nesta superfície (o vereador fica no app dele; o interno vai a /comunicados/:id). */
  hrefComunicado: (id: string) => string;
}) {
  const { token } = useAuth();
  const comunicados = useCaixaDeComunicados(token);
  const avisos = useAvisosDoSistema(token);
  const { marcar, estado: estadoMarcacao, erro: erroMarcacao } = useMarcarLida(token);
  const [filtro, setFiltro] = useState<FiltroDaCaixa>("tudo");
  // o "agora" é ESTADO (mesmo racional da antiga inbox): o "há 5min" não congela no instante da montagem
  const [agora, setAgora] = useState(() => new Date().toISOString());
  useEffect(() => {
    const t = setInterval(() => setAgora(new Date().toISOString()), 60_000);
    return () => clearInterval(t);
  }, []);

  const c = comunicados.estado;
  const a = avisos.estado;
  const titulo = (
    <header className="cx-cab">
      <div>
        <p className="eyebrow">Comunicados e avisos</p>
        <h1>
          Caixa
          <Contador n={(c.fase === "pronto" ? Math.max(0, c.dado.naoLidos) : 0) + (a.fase === "pronto" ? Math.max(0, a.dado.naoLidas) : 0)} />
        </h1>
        <p className="cx-sub">Os comunicados da Casa e os avisos do sistema, numa lista só.</p>
      </div>
      <nav className="cx-acoes" aria-label="Comunicados">
        <Link className="btn btn-primaria btn-mini" href={comToken("/comunicados/novo", token)} prefetch={false}>
          Escrever comunicado
        </Link>
        <Link className="btn btn-contorno btn-mini" href={comToken("/comunicados/enviados", token)} prefetch={false}>
          Enviados
        </Link>
      </nav>
    </header>
  );

  if (c.fase === "carregando" && a.fase === "carregando") {
    return (
      <div className={`cx cx-${superficie}`}>
        {titulo}
        <p className="cx-estado" role="status">Carregando a caixa…</p>
      </div>
    );
  }
  if (c.fase === "erro" && a.fase === "erro") {
    return (
      <div className={`cx cx-${superficie}`}>
        {titulo}
        <div className="cx-estado" role="alert">
          <p><b>Não foi possível carregar a sua caixa.</b></p>
          <p>{c.mensagem}</p>
          <button type="button" className="btn btn-contorno btn-mini" onClick={() => { comunicados.recarregar(); avisos.recarregar(); }}>
            Tentar de novo
          </button>
        </div>
      </div>
    );
  }

  const vista = derivarCaixa(c.fase === "pronto" ? c.dado : null, a.fase === "pronto" ? a.dado : null, agora, filtro, hrefComunicado);

  async function marcarLido(item: ItemDaCaixaVista) {
    try {
      await marcar(item.id);
      avisos.recarregar();
      avisarCaixaMudou();
    } catch {
      // o erro já fica exposto via `erroMarcacao` (região viva); aqui só evita a promessa rejeitada solta
    }
  }

  return (
    <div className={`cx cx-${superficie}`}>
      {titulo}

      {vista.faixaCiencia && (
        <p className="cx-faixa" role="note">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
            <circle cx="12" cy="12" r="9" />
            <path d="M12 7v5l3 2" />
          </svg>
          <span>
            {vista.faixaCiencia}{" "}
            {filtro !== "ciencia" && (
              <button type="button" className="cx-faixa-ver" onClick={() => setFiltro("ciencia")}>
                Ver só os que pedem ciência
              </button>
            )}
          </span>
        </p>
      )}

      {c.fase === "erro" && (
        <p className="cx-parcial" role="alert">
          Os comunicados não carregaram: {c.mensagem}{" "}
          <button type="button" className="cx-faixa-ver" onClick={comunicados.recarregar}>Tentar de novo</button>
        </p>
      )}
      {a.fase === "erro" && (
        <p className="cx-parcial" role="alert">
          Os avisos do sistema não carregaram agora. Os comunicados abaixo estão completos.{" "}
          <button type="button" className="cx-faixa-ver" onClick={avisos.recarregar}>Tentar de novo</button>
        </p>
      )}
      {vista.avisoDeCorte && <p className="cx-corte">{vista.avisoDeCorte}</p>}

      {/* aria-pressed num role="group": a mesma convenção da antiga inbox e de expediente/seletor-modelo */}
      <div className="cx-segs" role="group" aria-label="Filtrar a caixa">
        {vista.filtros.map((f) => (
          <button key={f.chave} type="button" aria-pressed={f.chave === vista.filtroAtivo} onClick={() => setFiltro(f.chave)}>
            {f.rotulo}
            <span className="cx-qt">{f.quantidade}</span>
          </button>
        ))}
      </div>

      {/* role="status": sem região viva a falha do POST só existiria em pixel (WCAG 4.1.3) */}
      {erroMarcacao && (
        <p role="status" className="cx-erro">
          {erroMarcacao}
        </p>
      )}

      {vista.vazia && (
        <p className="cx-vazio">
          Sua caixa está vazia. Quando a Casa enviar um comunicado a você, ou o sistema tiver um aviso, ele aparece aqui.
        </p>
      )}
      {vista.vaziaNoFiltro && (
        <p className="cx-vazio">
          {vista.filtroAtivo === "ciencia"
            ? "Nenhum comunicado aguardando a sua ciência."
            : vista.filtroAtivo === "nao-lidos"
              ? "Tudo lido por aqui."
              : "Nada neste filtro."}
        </p>
      )}

      {vista.grupos.map((g) => (
        <section className="cx-grupo" key={g.chave} aria-labelledby={`cx-grupo-${g.chave}`}>
          <h2 id={`cx-grupo-${g.chave}`}>{g.rotulo}</h2>
          <div className="cx-lista">
            {g.itens.map((item) => (
              <Item
                key={item.chave}
                item={item}
                token={token}
                marcando={estadoMarcacao === "enviando"}
                aoMarcar={() => void marcarLido(item)}
              />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

function Contador({ n }: { n: number }) {
  if (n <= 0) return null;
  return (
    // branco sobre --telha pura falha AA (4.0, §5.1): --telha-fundo (5.44). O número é visual; o título se lê
    // "Caixa, 3 por ler" (um span com aria-label e sem papel é ignorado por parte dos leitores de tela).
    <>
      <span className="cx-badge" aria-hidden="true">
        {n}
      </span>
      <span className="sr-only">, {n === 1 ? "1 por ler" : `${n} por ler`}</span>
    </>
  );
}

function Item({ item, token, marcando, aoMarcar }: { item: ItemDaCaixaVista; token: string | null; marcando: boolean; aoMarcar: () => void }) {
  const ehComunicado = item.origem === "comunicado";
  const classe = ["cx-item", item.lido ? "" : "cx-nao-lido", item.ciencia?.estado === "vencida" ? "cx-vencido" : ""].filter(Boolean).join(" ");
  return (
    <article className={classe}>
      <span className={ehComunicado ? "cx-ic" : "cx-ic cx-ic-sistema"} aria-hidden="true">
        {ehComunicado ? (
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <rect x="3" y="5" width="18" height="14" rx="2" />
            <path d="m3 7 9 6 9-6" />
          </svg>
        ) : (
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M14 3v4a1 1 0 0 0 1 1h4" />
            <path d="M17 21H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7l5 5v11a2 2 0 0 1-2 2Z" />
            <path d="M9 13h6" />
          </svg>
        )}
      </span>
      <div className="cx-mid">
        <p className="cx-origem">{ehComunicado ? "Comunicado" : "Aviso do sistema"}</p>
        <h3>
          {ehComunicado ? (
            <Link href={comToken(item.href, token)} prefetch={false}>
              {item.titulo}
            </Link>
          ) : (
            item.titulo
          )}
        </h3>
        <p className="cx-detalhe">{item.detalhe}</p>
        {item.ciencia && <span className={`cx-chip ${CHIP_DA_CIENCIA[item.ciencia.estado]}`}>{item.ciencia.rotulo}</span>}
        {!ehComunicado && item.href && (
          <Link className="cx-ir" href={comToken(item.href, token)} prefetch={false}>
            Abrir a ficha →
          </Link>
        )}
      </div>
      <div className="cx-dir">
        {/* <time> + title: o relativo defasa e não diz o DIA; o instante exato (fuso da Casa) fica disponível */}
        <time className="cx-quando" dateTime={item.em} title={item.quandoExato}>
          {item.quando}
        </time>
        {item.lido ? (
          <span className="cx-lido-marca">Lido</span>
        ) : (
          <>
            <span className="cx-ponto" role="img" aria-label="Não lido" />
            {!ehComunicado && (
              <button className="cx-marcar" type="button" disabled={marcando} onClick={aoMarcar}>
                Marcar como lido
              </button>
            )}
          </>
        )}
      </div>
    </article>
  );
}
