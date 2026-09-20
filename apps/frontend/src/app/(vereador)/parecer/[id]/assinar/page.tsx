"use client";

// Assinatura em 2 toques do vereador-relator (Onda C4, feature 7.3) — porte de
// produto/design-system/o-plenario/telas/assinatura-2-toques.html. Toque 1 abre a ilha-papel (leitura);
// toque 2 abre a folha de confirmação (biometria MOCK LOCAL — sem WebAuthn/gov.br real, fast-follow Onda
// D já decidido no plano da track) e dispara useMeuEmitirParecer.emitir(). Backend assina de verdade
// (Repo/emitir-parecer! + assinador-icp stub) — esta página só orquestra a UI do ritual.
//
// FIX (achado docs/20 — jornada circular): quando o parecer chega SEM voto do relator ('escolher-voto'),
// a tela agora oferece ao relator ESCOLHER o voto e assinar num ato só (o endpoint /meu/pareceres/:id/
// emissao já aceita `voto-relator`). Antes disso o único jeito de setar o voto era a secretaria "Emitir",
// que já terminaliza — então a assinatura pelo relator nunca fechava. A escolha é EXPLÍCITA (não um
// default fabricado — ver assinatura-vista.ts). Se o voto JÁ vem setado, mostra como leitura, como antes.
//
// ADAPTAÇÃO ao mockup: esta rota vive sob app/(vereador)/, cujo layout.tsx já envolve `children` no chrome
// persistente (topo+tabbar) — por isso o cabeçalho do mockup vira um "← Voltar" + título em fluxo.

import { useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { useMeuParecer } from "@/lib/use-meu-parecer";
import { useMeuEmitirParecer } from "@/lib/use-meu-emitir-parecer";
import { deriveEstadoAssinatura } from "@/lib/assinatura-vista";
import { rotularVoto, VOTO_OPCOES, type VotoValor } from "@/lib/parecer-vista";
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
  const [votoEscolhido, setVotoEscolhido] = useState<VotoValor | null>(null);

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
  // O voto que será assinado: o já registrado (leitura) OU o escolhido agora pelo relator. NUNCA um
  // default fabricado — sem escolha, `votoEfetivo` é null e o CTA/confirmar ficam bloqueados.
  const votoEfetivo: string | null = dados.votoRelator ?? votoEscolhido;
  const podeAssinar = situacao === "pronto-pra-revisar" || (situacao === "escolher-voto" && votoEscolhido !== null);

  async function confirmar() {
    if (!dados || !votoEfetivo) return; // defesa em profundidade — o CTA só abre a sheet quando há voto.
    try {
      await emitir({ votoRelator: votoEfetivo, lockVersion: dados.lockVersion });
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
              <p>{rotularVoto(votoEfetivo)}</p>
              <span className="rot">Fundamentação (resumo)</span>
              <p>{dados.relatorio}</p>
            </div>
          </section>

          {situacao === "escolher-voto" && (
            <section className="assinar-voto" aria-label="Escolha da conclusão do relator">
              <p className="assinar-voto-rot">
                Registre sua conclusão como relator antes de assinar. Ela fica gravada com a assinatura.
              </p>
              <div className="votos" role="radiogroup" aria-label="Conclusão do relator">
                {VOTO_OPCOES.map((opcao) => (
                  <div key={opcao.valor} className={`voto ${opcao.classe}`}>
                    <input
                      type="radio"
                      id={`voto-${opcao.valor}`}
                      name="voto-relator-assinar"
                      value={opcao.valor}
                      checked={votoEscolhido === opcao.valor}
                      onChange={() => setVotoEscolhido(opcao.valor)}
                    />
                    <label htmlFor={`voto-${opcao.valor}`}>{opcao.rotulo}</label>
                  </div>
                ))}
              </div>
            </section>
          )}

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

      {podeAssinar && (
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
            {/* [GAP] esta copy descreve assinatura ICP-Brasil/biometria REAIS; hoje o backend produz
                'STUB-ICP-v0' (assinador_icp.clj/assinador-stub) e a "biometria" acima é mock local, sem
                WebAuthn/ICP-Brasil por trás. Uma assinatura criptográfica de verdade PRECISA existir antes
                desta tela ir a um vereador real em produção — gate = fast-follow Onda D (já decidido). */}
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
