"use client";

// LEITURA DA ATA ANTERIOR no cockpit da Mesa (Faixa A / A.7 — pedido de Baturité: "IA, presencial ou dispensada").
// Só se lê a versão VIGENTE publicada da ata da sessão anterior (o servidor escolhe e confere na hora de registrar).
// Três jeitos, escolhidos pela Mesa na hora: o sistema lê em voz sintetizada (a voz é a do computador da Mesa —
// provisória, nada sai da Câmara), o secretário lê, ou a Casa dispensa a leitura. O ato fica registrado com o hash do
// texto lido; uma leitura por sessão.

import { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useLeituraAta } from "@/lib/use-leitura-ata";
import { MODOS, linhaDoRegistro, paragrafos, tituloDaAta, trechosParaVoz, type ModoLeitura } from "@/lib/leitura-ata-vista";
import { leitorDoNavegador, type Controle, type Leitor } from "@/lib/voz";
import { formatarData } from "@/lib/formatar-data";

type Fase =
  | { f: "escolher" }
  | { f: "confirmar"; modo: "presencial" | "dispensada" }
  | { f: "lendo"; trecho: number; pausado: boolean }
  | { f: "lida" };

export function PainelLeituraAta({ sessaoId, token, leitor: leitorProp }: { sessaoId: string; token: string | null; leitor?: Leitor }) {
  const io = useLeituraAta(sessaoId, token);
  const leitor = useMemo(() => leitorProp ?? leitorDoNavegador(), [leitorProp]);
  const [voz, setVoz] = useState<string | null>(() => leitor.voz());
  const [fase, setFase] = useState<Fase>({ f: "escolher" });
  const [erro, setErro] = useState<string | null>(null);
  const controle = useRef<Controle | null>(null);

  // as vozes do navegador chegam depois do carregamento da página
  useEffect(() => {
    if (typeof window === "undefined" || !window.speechSynthesis) return;
    const atualizar = () => setVoz(leitor.voz());
    window.speechSynthesis.addEventListener?.("voiceschanged", atualizar);
    return () => window.speechSynthesis.removeEventListener?.("voiceschanged", atualizar);
  }, [leitor]);
  useEffect(() => () => controle.current?.parar(), []);
  // o parágrafo em leitura fica à vista dentro da caixa do texto (ata longa rola sozinha)
  const agora = useRef<HTMLParagraphElement | null>(null);
  const trechoAtual = fase.f === "lendo" ? fase.trecho : null;
  useEffect(() => {
    agora.current?.scrollIntoView?.({ block: "nearest", behavior: "smooth" });
  }, [trechoAtual]);

  if (io.estado === "carregando") return null;
  if (io.estado === "erro")
    return (
      <Bloco>
        <p role="status" className="nota-mesa">Não foi possível carregar a leitura da ata agora.</p>
      </Bloco>
    );

  const { anterior, ata, leitura, podeRegistrar } = io.painel;
  const texto = ata?.texto ?? "";
  const trechos = trechosParaVoz(texto);

  async function registrar(modo: ModoLeitura) {
    if (!anterior || !ata) return;
    setErro(null);
    try {
      await io.registrar(modo, anterior.id, ata.versao);
      setFase({ f: "escolher" });
    } catch (e) {
      setErro(e instanceof Error ? e.message : "Não foi possível registrar a leitura agora.");
    }
  }

  function lerEmVoz() {
    setErro(null);
    setFase({ f: "lendo", trecho: 0, pausado: false });
    controle.current = leitor.ler(trechos, {
      trecho: (i) => setFase({ f: "lendo", trecho: i, pausado: false }),
      fim: () => setFase({ f: "lida" }),
      erro: () => {
        setFase({ f: "escolher" });
        setErro("A voz do computador parou com erro. Leia presencialmente ou tente de novo.");
      },
    });
  }

  if (!anterior)
    return (
      <Bloco>
        <p className="nota-mesa">Não há sessão anterior com ata para ler.</p>
      </Bloco>
    );

  const titulo = tituloDaAta(anterior);

  if (leitura)
    return (
      <Bloco>
        <p className="leitura-feita">
          <span className="chip chip-ok">{MODOS[leitura.modo].rotulo}</span> <b>{titulo}</b>
        </p>
        <p className="nota-mesa">{linhaDoRegistro(leitura)}</p>
      </Bloco>
    );

  if (!ata)
    return (
      <Bloco>
        <p className="nota-mesa">
          A {titulo.charAt(0).toLowerCase() + titulo.slice(1)} ainda não foi publicada.{" "}
          <Link href={`/sessoes/${encodeURIComponent(anterior.id)}/ata`}>Publique-a na tela Ata</Link> para lê-la aqui.
        </p>
      </Bloco>
    );

  const lendo = fase.f === "lendo" ? fase : null;
  const paras = paragrafos(texto);
  const paragrafoAtual = lendo ? trechos[lendo.trecho]?.paragrafo : null;

  return (
    <Bloco>
      <p className="leitura-titulo">
        <b>{titulo}</b>
        <span className="nota-mesa">
          Versão {ata.versao}, publicada em {formatarData(ata.publicadaEm)}
          {ata.origemRedacao === "gerada_automaticamente" ? " · partiu de um rascunho da IA, revisado pela Casa" : ""}
        </span>
      </p>

      {lendo || fase.f === "lida" ? (
        <article className="papel leitura-texto" aria-label="Texto da ata" aria-live="off">
          {paras.map((p, i) => (
            <p key={i} ref={i === paragrafoAtual ? agora : undefined} className={i === paragrafoAtual ? "leitura-agora" : undefined}
              aria-current={i === paragrafoAtual ? "true" : undefined}>
              {p}
            </p>
          ))}
        </article>
      ) : (
        <details className="leitura-ver">
          <summary>Ver o texto da ata</summary>
          <article className="papel leitura-texto">
            {paras.map((p, i) => <p key={i}>{p}</p>)}
          </article>
        </details>
      )}

      {erro && <p role="alert" className="erro-inline">{erro}</p>}

      {!podeRegistrar ? (
        <p className="nota-mesa">A leitura é registrada com a sessão aberta.</p>
      ) : lendo ? (
        <div className="ato-acoes" role="group" aria-label="Controle da leitura em voz">
          <span className="nota-mesa" aria-live="polite">Lendo em voz sintetizada…</span>
          {lendo.pausado ? (
            <button type="button" className="btn btn-contorno btn-mini" onClick={() => { controle.current?.retomar(); setFase({ ...lendo, pausado: false }); }}>
              Continuar
            </button>
          ) : (
            <button type="button" className="btn btn-contorno btn-mini" onClick={() => { controle.current?.pausar(); setFase({ ...lendo, pausado: true }); }}>
              Pausar
            </button>
          )}
          <button type="button" className="btn btn-fantasma btn-mini" onClick={() => { controle.current?.parar(); setFase({ f: "escolher" }); }}>
            Parar
          </button>
        </div>
      ) : fase.f === "lida" ? (
        <div className="ato-acoes">
          <span className="nota-mesa" role="status">Leitura concluída.</span>
          <button type="button" className="btn btn-primaria" disabled={io.enviando} onClick={() => registrar("voz_sintetizada")}>
            {io.enviando ? "Registrando…" : MODOS.voz_sintetizada.registrar}
          </button>
          <button type="button" className="btn btn-fantasma" onClick={() => setFase({ f: "escolher" })}>Voltar</button>
        </div>
      ) : fase.f === "confirmar" ? (
        <div className="ato-detalhe">
          <p className="confirmar-txt">
            {fase.modo === "presencial"
              ? "Confirma que a ata foi lida em plenário pelo secretário?"
              : "Confirma que a Casa dispensou a leitura (a ata foi distribuída antes)?"}
          </p>
          <div className="ato-acoes">
            <button type="button" className="btn btn-primaria" disabled={io.enviando} onClick={() => registrar(fase.modo)}>
              {io.enviando ? "Registrando…" : MODOS[fase.modo].registrar}
            </button>
            <button type="button" className="btn btn-fantasma" disabled={io.enviando} onClick={() => setFase({ f: "escolher" })}>
              Cancelar
            </button>
          </div>
        </div>
      ) : (
        <>
          <div className="ato-acoes">
            <button type="button" className="btn btn-contorno" disabled={!voz || trechos.length === 0} onClick={lerEmVoz}>
              Ler em voz sintetizada
            </button>
            <button type="button" className="btn btn-contorno" onClick={() => { setErro(null); setFase({ f: "confirmar", modo: "presencial" }); }}>
              Lida presencialmente
            </button>
            <button type="button" className="btn btn-fantasma" onClick={() => { setErro(null); setFase({ f: "confirmar", modo: "dispensada" }); }}>
              Dispensar a leitura
            </button>
          </div>
          <p className="nota-mesa">
            {voz
              ? "A voz é a do computador da Mesa (provisória): o texto não sai da Câmara."
              : "Este computador não tem voz em português instalada: leia presencialmente ou instale uma voz pt-BR."}
          </p>
        </>
      )}
    </Bloco>
  );
}

function Bloco({ children }: { children: React.ReactNode }) {
  return (
    <section className="bloco leitura-ata" aria-labelledby="leitura-ata-titulo">
      <div className="bloco-cabeca">
        <h2 id="leitura-ata-titulo">Leitura da ata anterior</h2>
      </div>
      <div className="bloco-corpo">{children}</div>
    </section>
  );
}
