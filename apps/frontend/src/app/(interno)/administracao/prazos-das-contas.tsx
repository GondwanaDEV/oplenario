"use client";

// Bloco "Prazos das contas" da área do administrador (ADR-0021 B3/B4): o prazo de DEFESA do responsável (contado da
// notificação, congelado nela) e o prazo para a Câmara JULGAR as contas do Prefeito (contado do recebimento, congelado
// no registro). Só o `admin_ente` grava (PUT /parametros-de-contas; o backend exige o papel). Sem configuração valem os
// padrões (15 e 60 dias) — e a tela diz que são padrões a conferir na Lei Orgânica, não a regra da Casa.

import { useState, type FormEvent } from "react";
import { salvarParametrosContas, useParametrosContas } from "@/lib/use-contas";
import {
  LIMITES_PRAZO_DEFESA,
  LIMITES_PRAZO_JULGAMENTO,
  PADRAO_PRAZO_DEFESA_DIAS,
  PADRAO_PRAZO_JULGAMENTO_DIAS,
  type ParametrosContas,
} from "@/lib/contrato-contas";

/** Inteiro dentro dos limites, ou `null`. */
export function lerDias(bruto: string, lim: { min: number; max: number }): number | null {
  const t = bruto.trim();
  if (!/^\d{1,3}$/.test(t)) return null;
  const n = Number(t);
  return n >= lim.min && n <= lim.max ? n : null;
}

export function PrazosDasContas({ token }: { token: string | null }) {
  const { estado, setEstado } = useParametrosContas(token);
  return (
    <section className="adm-auditoria" aria-labelledby="adm-contas-titulo">
      <h2 id="adm-contas-titulo">Prazos das contas</h2>
      <p className="adm-texto">
        Os prazos do julgamento das contas do Prefeito: quantos dias o responsável tem para se defender depois de notificado,
        e em quantos dias, contados do recebimento, a Câmara deve julgar. Os padrões ({PADRAO_PRAZO_DEFESA_DIAS} e{" "}
        {PADRAO_PRAZO_JULGAMENTO_DIAS} dias) são um ponto de partida: confira na Lei Orgânica do Município. A mudança vale para as
        próximas prestações e notificações; os prazos já fixados não mudam.
      </p>
      {estado.fase === "carregando" && <p role="status">Carregando…</p>}
      {estado.fase === "erro" && <p role="alert">{estado.mensagem}</p>}
      {estado.fase === "pronto" && (
        <FormPrazos token={token} atual={estado.dado} onSalvo={(p) => setEstado({ fase: "pronto", dado: p })} />
      )}
    </section>
  );
}

function FormPrazos({ token, atual, onSalvo }: { token: string | null; atual: ParametrosContas; onSalvo: (p: ParametrosContas) => void }) {
  const [defesa, setDefesa] = useState(String(atual.prazoDefesaDias));
  const [julgamento, setJulgamento] = useState(String(atual.prazoJulgamentoDias));
  const [enviando, setEnviando] = useState(false);
  const [aviso, setAviso] = useState<{ tom: "ok" | "erro"; texto: string } | null>(null);

  async function salvar(e: FormEvent) {
    e.preventDefault();
    const d = lerDias(defesa, LIMITES_PRAZO_DEFESA);
    const j = lerDias(julgamento, LIMITES_PRAZO_JULGAMENTO);
    if (d === null) return setAviso({ tom: "erro", texto: `O prazo de defesa é um número inteiro de dias, de ${LIMITES_PRAZO_DEFESA.min} a ${LIMITES_PRAZO_DEFESA.max}.` });
    if (j === null) return setAviso({ tom: "erro", texto: `O prazo para julgar é um número inteiro de dias, de ${LIMITES_PRAZO_JULGAMENTO.min} a ${LIMITES_PRAZO_JULGAMENTO.max}.` });
    setEnviando(true);
    const r = await salvarParametrosContas(token, { prazoDefesaDias: d, prazoJulgamentoDias: j });
    setEnviando(false);
    if (!r.ok) return setAviso({ tom: "erro", texto: r.mensagem });
    setAviso({ tom: "ok", texto: `Prazos salvos: defesa em ${r.dado.prazoDefesaDias} dias, julgamento em ${r.dado.prazoJulgamentoDias} dias.` });
    onSalvo(r.dado);
  }

  return (
    <form className="adm-regra-pauta" onSubmit={(e) => void salvar(e)} noValidate>
      {atual.padrao && <p className="adm-texto">Esta Casa ainda não configurou: valem os padrões, a conferir na Lei Orgânica.</p>}
      <div className="campo">
        <label htmlFor="prazo-defesa-contas">Prazo de defesa (dias depois da notificação)</label>
        <input id="prazo-defesa-contas" inputMode="numeric" value={defesa} onChange={(e) => setDefesa(e.target.value)} />
      </div>
      <div className="campo">
        <label htmlFor="prazo-julgamento-contas">Prazo para julgar (dias depois do recebimento)</label>
        <input id="prazo-julgamento-contas" inputMode="numeric" value={julgamento} onChange={(e) => setJulgamento(e.target.value)} />
      </div>
      <button type="submit" className="btn btn-contorno btn-mini" disabled={enviando}>
        {enviando ? "Salvando…" : "Salvar prazos das contas"}
      </button>
      <p role={aviso?.tom === "erro" ? "alert" : "status"} className="adm-texto">
        {aviso?.texto ?? ""}
      </p>
    </form>
  );
}
