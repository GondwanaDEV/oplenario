"use client";

// O formulário de REGISTRAR PRESTAÇÃO (ADR-0021 B1). Dois tipos, dois conjuntos de campos:
//   - contas de GOVERNO do Prefeito: exercício, responsável (o prefeito DAQUELE exercício, não o atual), recebida em,
//     processo no TCE, parecer prévio e a comissão autora do Projeto de Decreto Legislativo. Registrar PROTOCOLA o PDL
//     na mesma transação (o servidor faz) — a tela diz isso antes e depois;
//   - contas de GESTÃO da Câmara (Mesa): exercício, responsável, recebida em, processo e situação no TCE. Sem PDL.
// Confere antes de enviar (`validarNovaPrestacao`); o servidor confere de novo, e a recusa dele vira frase.

import { useState, type FormEvent } from "react";
import { useComissoes } from "@/lib/use-comissoes";
import { registrarPrestacao } from "@/lib/use-contas";
import { validarNovaPrestacao, type ErrosNovaPrestacao, type FormNovaPrestacao } from "@/lib/contas-vista";
import { ROTULO_PARECER, type ParecerPrevio, type PrestacaoOut, type TipoPrestacao } from "@/lib/contrato-contas";

/** "AAAA-MM-DD" do dia LOCAL (não o UTC de toISOString, que à noite em Fortaleza já é amanhã). */
export function hojeLocal(d = new Date()): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

const INICIAL: FormNovaPrestacao = {
  tipo: "governo_prefeito",
  exercicio: "",
  responsavel: "",
  recebidaEm: "",
  processoTce: "",
  parecerPrevio: "",
  comissaoAutoraId: "",
  situacaoTce: "",
};

const PARECERES = Object.entries(ROTULO_PARECER) as [ParecerPrevio, string][];

export function FormNovaPrestacao({ token, onRegistrada }: { token: string | null; onRegistrada: (p: PrestacaoOut) => void }) {
  const [f, setF] = useState<FormNovaPrestacao>(INICIAL);
  const [erros, setErros] = useState<ErrosNovaPrestacao>({});
  const [erro, setErro] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);
  const governo = f.tipo === "governo_prefeito";
  const comissoes = useComissoes(token, governo);
  const muda = <K extends keyof FormNovaPrestacao>(k: K, v: FormNovaPrestacao[K]) => setF((x) => ({ ...x, [k]: v }));

  async function enviar(e: FormEvent) {
    e.preventDefault();
    setErro(null);
    const errosAgora = validarNovaPrestacao(f, hojeLocal());
    setErros(errosAgora);
    if (Object.keys(errosAgora).length > 0) return;
    setEnviando(true);
    const r = await registrarPrestacao(token, {
      tipo: f.tipo,
      exercicio: Number(f.exercicio),
      responsavel: f.responsavel,
      recebidaEm: f.recebidaEm,
      processoTce: f.processoTce,
      parecerPrevio: f.parecerPrevio || null,
      comissaoAutoraId: f.comissaoAutoraId || null,
      situacaoTce: f.situacaoTce,
    });
    setEnviando(false);
    if (r.ok) onRegistrada(r.dado);
    else setErro(r.mensagem);
  }

  const descr = (k: keyof FormNovaPrestacao) => (erros[k] ? `nova-${k}-erro` : undefined);
  const msgErro = (k: keyof FormNovaPrestacao) =>
    erros[k] ? (
      <p className="cts-erro-campo" id={`nova-${k}-erro`}>
        {erros[k]}
      </p>
    ) : null;

  return (
    <form className="cts-form" onSubmit={(e) => void enviar(e)} noValidate>
      <fieldset className="cts-tipo">
        <legend>Que contas são</legend>
        {(["governo_prefeito", "gestao_camara"] as TipoPrestacao[]).map((t) => (
          <label key={t}>
            <input type="radio" name="tipo" value={t} checked={f.tipo === t} onChange={() => muda("tipo", t)} />
            <span>
              {t === "governo_prefeito" ? "Contas de governo do Prefeito" : "Contas de gestão da Câmara (Mesa)"}
              <small>
                {t === "governo_prefeito"
                  ? "A Câmara julga sobre o parecer prévio do TCE. Registrar protocola o Projeto de Decreto Legislativo."
                  : "Só acompanhamento: processo, situação no TCE e documentos. Sem PDL e sem votação."}
              </small>
            </span>
          </label>
        ))}
      </fieldset>

      <div className="cts-campos">
        <div className="cts-campo">
          <label htmlFor="nova-exercicio">Exercício</label>
          <input id="nova-exercicio" inputMode="numeric" placeholder="2025" maxLength={4} value={f.exercicio}
            onChange={(e) => muda("exercicio", e.target.value)} aria-invalid={!!erros.exercicio} aria-describedby={descr("exercicio")} />
          {msgErro("exercicio")}
        </div>
        <div className="cts-campo">
          <label htmlFor="nova-recebida">Recebida pela Câmara em</label>
          <input id="nova-recebida" type="date" value={f.recebidaEm} max={hojeLocal()}
            onChange={(e) => muda("recebidaEm", e.target.value)} aria-invalid={!!erros.recebidaEm} aria-describedby={descr("recebidaEm")} />
          {msgErro("recebidaEm")}
        </div>
      </div>

      <div className="cts-campo">
        <label htmlFor="nova-responsavel">{governo ? "Prefeito responsável pelo exercício" : "Presidente da Câmara no exercício"}</label>
        <input id="nova-responsavel" maxLength={200} value={f.responsavel} onChange={(e) => muda("responsavel", e.target.value)}
          aria-invalid={!!erros.responsavel} aria-describedby={["nova-responsavel-ajuda", descr("responsavel")].filter(Boolean).join(" ")} />
        <p className="cts-ajuda" id="nova-responsavel-ajuda">
          {governo ? "Quem governou naquele exercício — não necessariamente o prefeito atual." : "Quem presidiu a Mesa naquele exercício."}
        </p>
        {msgErro("responsavel")}
      </div>

      <div className="cts-campo">
        <label htmlFor="nova-processo">Processo no TCE (opcional)</label>
        <input id="nova-processo" maxLength={100} value={f.processoTce} onChange={(e) => muda("processoTce", e.target.value)} />
      </div>

      {governo ? (
        <>
          <fieldset className="cts-tipo" aria-describedby={descr("parecerPrevio")}>
            <legend>Parecer prévio do TCE</legend>
            {PARECERES.map(([v, r]) => (
              <label key={v}>
                <input type="radio" name="parecer" value={v} checked={f.parecerPrevio === v} onChange={() => muda("parecerPrevio", v)} />
                <span>{r}</span>
              </label>
            ))}
            {msgErro("parecerPrevio")}
          </fieldset>

          <div className="cts-campo">
            <label htmlFor="nova-comissao">Comissão autora do Projeto de Decreto Legislativo</label>
            {comissoes.fase === "erro" ? (
              <p className="cts-erro" role="alert">{comissoes.mensagem}</p>
            ) : (
              <select id="nova-comissao" value={f.comissaoAutoraId} disabled={comissoes.fase !== "pronto"}
                onChange={(e) => muda("comissaoAutoraId", e.target.value)} aria-invalid={!!erros.comissaoAutoraId} aria-describedby={descr("comissaoAutoraId")}>
                <option value="">{comissoes.fase === "pronto" ? "— escolha a comissão —" : "Carregando as comissões…"}</option>
                {comissoes.fase === "pronto" && comissoes.comissoes.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
              </select>
            )}
            {msgErro("comissaoAutoraId")}
          </div>

          <p className="cts-nota" role="note">
            Ao registrar, o sistema protocola o Projeto de Decreto Legislativo sobre estas contas, de autoria da comissão
            escolhida. Ele tramita como qualquer outra matéria e só vai à pauta depois do prazo de defesa do responsável.
          </p>
        </>
      ) : (
        <div className="cts-campo">
          <label htmlFor="nova-situacao">Situação no TCE (opcional)</label>
          <input id="nova-situacao" maxLength={200} placeholder="Ex.: em instrução, julgada regular" value={f.situacaoTce}
            onChange={(e) => muda("situacaoTce", e.target.value)} />
        </div>
      )}

      {erro && <p className="cts-erro" role="alert">{erro}</p>}
      <div className="cts-acoes">
        <button type="submit" className="btn btn-primaria" disabled={enviando}>
          {enviando ? "Registrando…" : governo ? "Registrar e protocolar o PDL" : "Registrar prestação"}
        </button>
      </div>
    </form>
  );
}
