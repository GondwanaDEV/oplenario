"use client";

// A inbox do vereador — porte de produto/design-system/o-plenario/telas/notificacoes.html: cabeçalho +
// badge, barra de filtro, agrupamento temporal, a nota de dedup §5.1 e a lista.
//
// ── DECISÃO DE DESENHO (fatia 3): as abas de filtro são DERIVADAS do dado, não cravadas ────────────
// O design desenha seis abas fixas (Tudo/Não lidas/Falhas/Prazos/Tramitação/Sessões). Quatro delas não
// têm produtor nenhum no backend hoje — só `norma_publicada` é emitida de fato, mais o fallback
// `sistema`. Aba permanentemente vazia promete uma cobertura que o produto não tem: é mentira com
// outro nome. E `categoria` é `:string` ABERTO no contrato de propósito, então cravar a lista aqui
// criaria um segundo vocabulário no FE que drifta do backend em silêncio E esconderia a categoria que
// a Track IA vai emitir amanhã. Derivada, a aba nasce sozinha quando o produtor nascer.
// O racional completo, com as referências de arquivo, está no topo de src/lib/notificacoes-vista.ts.
//
// FORA desta fatia, e registrado: "marcar todas como lidas" (não existe rota bulk — a de
// paineis/components/repositorio.clj marca 1 id por vez), reverter lida -> não lida (`MarcarLidaOut` é
// unidirecional) e contador no sino do topo (o sino conta PENDÊNCIAS, não notificações — mudar isso
// quebraria a dedup que a própria tela documenta).
//
// Não-lida é marcada por PONTO + NEGRITO + tinta de fundo — nunca só cor (GUIDELINES-CHECKLIST).
// Composição: useAuth (token, já resolvido pelo GuardVereador do layout) + useMinhasNotificacoes +
// useMarcarLida + derivarInbox (view-model puro; TODA a derivação de aba/filtro mora lá).

import { useEffect, useState } from "react";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { useMinhasNotificacoes } from "@/lib/use-minhas-notificacoes";
import { useMarcarLida } from "@/lib/use-marcar-lida";
import { derivarInbox, FILTRO_TUDO, type NotificacaoVista } from "@/lib/notificacoes-vista";
import { comToken } from "@/lib/nav";
import "./notificacoes.css";

export default function PaginaNotificacoes() {
  const { token } = useAuth();
  const { dados, estado, recarregar } = useMinhasNotificacoes(token);
  const { marcar, estado: estadoMarcacao, erro: erroMarcacao } = useMarcarLida(token);
  // Estado de UI (qual aba está apertada), não regra: a derivação inteira fica em derivarInbox.
  const [filtro, setFiltro] = useState<string>(FILTRO_TUDO);
  // O "agora" é ESTADO, não `new Date()` solto no render: sem isto o rótulo relativo congelava no
  // instante da montagem (a tela não tem revalidação periódica) e só saltava — 3h de uma vez — quando
  // algum outro evento re-renderizava, o que se lê como bug. 60s é o menor passo que o rótulo enxerga.
  const [agora, setAgora] = useState(() => new Date().toISOString());
  useEffect(() => {
    const t = setInterval(() => setAgora(new Date().toISOString()), 60_000);
    return () => clearInterval(t);
  }, []);
  const vista = derivarInbox(dados, agora, filtro);

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

      {/*
        Achado da revisão adversarial (fatia "truncamento-familia" sitio b, conserto): os dois avisos
        eram MUTUAMENTE EXCLUSIVOS (`naoLidasForaDaLista === 0 && totalForaDaLista > 0`) — quando os
        DOIS cortes coexistiam (havia não lida fora da lista E o corte total era maior), só o aviso
        pequeno aparecia e o número grande (o par AUTORITATIVO `totalForaDaLista`) ficava calado atrás
        dele. Um vereador com 2 não lidas fora e 497 no total via só "faltam 2".

        Agora é UM aviso, que sempre nomeia `totalForaDaLista` quando ele existe (é o universo cheio —
        lidas E não lidas — e nunca é menor que `naoLidasForaDaLista` pelo caminho normal do app), e
        dentro dele destaca quantas das que faltam são não lidas. Só cai no aviso "só não lidas" (o
        texto antigo) no caso-limite em que o servidor conta não lida fora da lista mas o total bate
        com o que já foi mostrado — a corrida entre a query de badge e a de listagem documentada em
        `naoLidasForaDaLista` (marcação em voo); nesse instante o total ainda não é a informação útil.
      */}
      {vista.totalForaDaLista > 0 ? (
        <p className="nt-truncada">
          Esta lista mostra só as mais recentes.{" "}
          {vista.totalForaDaLista === 1
            ? "Há 1 notificação mais antiga fora dela"
            : `Há ${vista.totalForaDaLista} notificações mais antigas fora dela`}
          {vista.naoLidasForaDaLista > 0
            ? vista.naoLidasForaDaLista === 1
              ? ", 1 delas não lida"
              : `, ${vista.naoLidasForaDaLista} delas não lidas`
            : " (nenhuma delas não lida)"}
          .
        </p>
      ) : (
        vista.naoLidasForaDaLista > 0 && (
          <p className="nt-truncada">
            Esta lista traz só os avisos mais recentes.{" "}
            {vista.naoLidasForaDaLista === 1
              ? "Há 1 aviso não lido mais antigo fora dela"
              : `Há ${vista.naoLidasForaDaLista} avisos não lidos mais antigos fora dela`}
            {" "}— o número ao lado do título conta todos.
          </p>
        )
      )}

      {/* aria-pressed num role="group": mesma convenção de sessoes/[id]/chamada e de expediente/seletor-modelo
          (toggle single-select). NÃO usar role="status" aqui — a tela já tem uma região viva (o erro do
          POST) e duas competiriam pelo mesmo anúncio; o estado da aba já é lido pelo aria-pressed. */}
      {vista.filtros.length > 0 && (
        <div className="segs" role="group" aria-label="Filtrar notificações">
          {vista.filtros.map((f) => (
            <button
              key={f.chave}
              type="button"
              aria-pressed={f.chave === vista.filtroAtivo}
              onClick={() => setFiltro(f.chave)}
            >
              {f.rotulo}
              <span className="qt">{f.quantidade}</span>
            </button>
          ))}
        </div>
      )}

      <p className="dedup-nota">
        Aqui você <b>acompanha</b>. O que exige a sua ação fica em <b>Início</b> e sai de lá sozinho quando
        o ato é concluído. “Lido” mora só aqui.
      </p>

      {/* role="status": sem região viva a falha do POST só existiria em pixel — ver page.test.tsx. */}
      {erroMarcacao && (
        <p role="status" className="erro-inline">
          {erroMarcacao}
        </p>
      )}

      {vista.vazia && <p className="vazio">Nenhuma notificação por enquanto.</p>}
      {/* distinto do vazio de verdade: a inbox TEM conteúdo, só não neste recorte. */}
      {vista.vaziaNoFiltro && <p className="vazio">Nenhuma notificação neste filtro.</p>}

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
                  {/* `<time>` + `title`: o relativo defasa e não diz o DIA; o instante exato (fuso da
                      Casa) fica sempre disponível, e prazo regimental conta da publicação. */}
                  <time className="nt-quando" dateTime={n.criadoEm} title={n.quandoExato}>
                    {n.quando}
                  </time>
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
