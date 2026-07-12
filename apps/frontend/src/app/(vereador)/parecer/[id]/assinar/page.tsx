"use client";

// Assinatura em 2 toques do vereador-relator (Onda C4, feature 7.3) — porte de
// produto/design-system/o-plenario/telas/assinatura-2-toques.html. Toque 1 abre a ilha-papel (leitura);
// toque 2 abre a folha de confirmação (biometria MOCK LOCAL — sem WebAuthn/gov.br real, fast-follow Onda
// D já decidido no plano da track) e dispara useMeuEmitirParecer.emitir(). Backend assina de verdade
// (Repo/emitir-parecer! + assinador-icp stub) — esta página só orquestra a UI do ritual.
//
// ADAPTAÇÃO ao mockup (achado na leitura de "Before You Begin"): o mockup é uma página SOLTEIRA com seu
// próprio `.app`/`.app-topo`/`.voltar`. Esta rota, porém, vive sob app/(vereador)/ — cujo layout.tsx JÁ
// envolve todo `children` no chrome persistente do app (brasão+tema no topo, tabbar fixa embaixo —
// `.app-vereador`/`.app-topo`/`.tabbar`, vereador-shell.css), o MESMO contrato que vereador/page.tsx e
// votar/page.tsx já seguem (nenhuma das duas re-renderiza seu próprio topo). Portar o cabeçalho do mockup
// 1:1 aqui (a) duplicaria a classe GLOBAL `.app-topo` (colisão de nome com vereador-shell.css) e (b)
// aninharia um `<main>` dentro do `<main className="conteudo-vereador">` do shell (2 landmarks `main` —
// quebra de a11y estrutural). Por isso o cabeçalho do mockup vira um "← Voltar" + título comuns, EM FLUXO
// (não sticky, não duplicado) — o resto (toques/papel/sumario/assinar-bar/scrim/sheet) é porte fiel. A
// `.assinar-bar` fixa também foi reancorada ACIMA da tabbar (ver assinar.css) — do jeito que ela vem do
// mockup (fixa em bottom:0), ficaria por baixo da própria tabbar (que também é fixa em bottom:0),
// escondendo o botão "Revisar e assinar".

import { useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { useMeuParecer } from "@/lib/use-meu-parecer";
import { useMeuEmitirParecer } from "@/lib/use-meu-emitir-parecer";
import { deriveEstadoAssinatura } from "@/lib/assinatura-vista";
import { rotularVoto } from "@/lib/parecer-vista";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import { comToken } from "@/lib/nav";
import "./assinar.css";

export default function PaginaAssinarParecer() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { token } = useAuth();
  const { dados, estado } = useMeuParecer(token, id);
  const { emitir, estado: estadoEmissao, erro } = useMeuEmitirParecer(token, id);
  const [sheetAberta, setSheetAberta] = useState(false);

  if (estado === "carregando") {
    return (
      <main className="tela-estado">
        <h1>Carregando…</h1>
      </main>
    );
  }
  if (estado === "erro" || !dados) {
    return (
      <main className="tela-estado">
        <h1>Não foi possível carregar este parecer</h1>
        <p>Ele pode não existir, ou você não é o relator designado.</p>
      </main>
    );
  }

  const situacao = deriveEstadoAssinatura(dados);

  async function confirmar() {
    if (!dados) return;
    try {
      await emitir({ votoRelator: dados.votoRelator ?? "favoravel", lockVersion: dados.lockVersion });
      setSheetAberta(false);
      router.push(comToken("/vereador", token));
    } catch {
      // erro já fica exposto via `erro` (useMeuEmitirParecer) — o botão da sheet mostra a mensagem.
    }
  }

  return (
    <div className="assinar-pagina">
      <button type="button" className="btn btn-fantasma btn-mini assinar-voltar" onClick={() => router.back()}>
        ← Voltar
      </button>
      <h1 className="assinar-titulo">Assinar parecer</h1>

      <div className="toques" aria-hidden="true">
        <span className="toque on">
          <span className="n">1</span>Revisar
        </span>
        <span className="liga" />
        <span className={`toque ${sheetAberta ? "on" : ""}`}>
          <span className="n">2</span>Confirmar
        </span>
      </div>

      {situacao === "sem-texto" ? (
        <p className="vazio">Ainda sem texto pronto para assinar.</p>
      ) : (
        <>
          <section className="papel" aria-label="Documento a assinar">
            <div className="cab">
              <h2>Parecer da Relatoria</h2>
            </div>
            <div className="corpo">
              {dados.objeto && (
                <>
                  <span className="rot">Matéria</span>
                  <p>
                    {formatarNumeroProposicao(dados.objeto.tipo, dados.objeto.sequencial, dados.objeto.ano)} —{" "}
                    {dados.objeto.ementa}
                  </p>
                </>
              )}
              <span className="rot">Conclusão do relator</span>
              <p>{rotularVoto(dados.votoRelator)}</p>
              <span className="rot">Fundamentação (resumo)</span>
              <p>{dados.relatorio}</p>
            </div>
          </section>

          <div className="sumario">
            <h3>O que você está assinando</h3>
            <p>
              É <b>ato definitivo</b>: depois de assinado, o parecer é juntado à matéria e vai à pauta.
            </p>
            <p>
              A assinatura fica <b>registrada</b>, com data e hora.
            </p>
          </div>
        </>
      )}

      {situacao === "pronto-pra-revisar" && (
        <div className="assinar-bar">
          <div className="assinar-bar-in">
            <button className="btn btn-primaria" type="button" onClick={() => setSheetAberta(true)}>
              Revisar e assinar
            </button>
          </div>
        </div>
      )}

      {sheetAberta && (
        <div className="scrim" role="dialog" aria-modal="true" aria-labelledby="sh-tit">
          <div className="sheet">
            <h3 id="sh-tit">Confirmar assinatura</h3>
            <p className="sub">Use a biometria do aparelho para concluir.</p>
            {erro && (
              <p role="status" className="erro-inline">
                Não foi possível assinar: {erro}
              </p>
            )}
            <div className="acoes">
              <button
                className="btn btn-primaria"
                type="button"
                onClick={confirmar}
                disabled={estadoEmissao === "enviando"}
              >
                Confirmar com a biometria
              </button>
              <button className="btn btn-fantasma" type="button" onClick={() => setSheetAberta(false)}>
                Cancelar
              </button>
            </div>
            <p className="legal">
              Ao confirmar, você assina digitalmente este documento. A assinatura tem validade e não pode
              ser desfeita.
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
