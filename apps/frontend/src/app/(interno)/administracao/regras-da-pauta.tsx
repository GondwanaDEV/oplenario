"use client";

// Bloco "Regras da pauta" da área do administrador (ADR-0019 fatia 3, Eixo 7): QUEM publica a pauta das sessões e a
// ANTECEDÊNCIA mínima. Só o `admin_ente` grava (PUT /regra-da-pauta; o backend exige o papel). Sem configuração vale o
// padrão — a secretaria publica, sem antecedência —, e a tela diz isso em vez de fingir uma escolha. A antecedência
// é AVISO: publicar mais tarde é aceito e fica registrado como fora do prazo.

import { useState, type FormEvent } from "react";
import type { RegraPautaOut } from "@/lib/contrato-sessoes.gen";
import { salvarRegraPauta, useRegraPauta } from "@/lib/use-publicacao-pauta";
import { OPCOES_QUEM_PUBLICA, lerAntecedencia, rotuloQuemPublica, type QuemPublica } from "@/lib/publicacao-pauta-vista";

export function RegrasDaPauta({ token }: { token: string | null }) {
  const { estado, definir } = useRegraPauta(token);
  return (
    <section className="adm-auditoria" aria-labelledby="adm-pauta-titulo">
      <h2 id="adm-pauta-titulo">Regras da pauta</h2>
      <p className="adm-texto">
        Quem publica a pauta das sessões — a versão oficial que o portal e a TV mostram — e com quanta antecedência. Siga o
        Regimento Interno da Casa. A antecedência é um aviso: a publicação fora do prazo é aceita e fica registrada assim.
      </p>
      {estado.fase === "carregando" && <p role="status">Carregando…</p>}
      {estado.fase === "erro" && <p role="alert">{estado.mensagem}</p>}
      {estado.fase === "pronto" && <FormRegra token={token} regra={estado.dado} onSalva={definir} />}
    </section>
  );
}

function FormRegra({ token, regra, onSalva }: { token: string | null; regra: RegraPautaOut; onSalva: (r: RegraPautaOut) => void }) {
  const [quem, setQuem] = useState<QuemPublica>(regra.quemPublica);
  const [horas, setHoras] = useState(regra.antecedenciaMinimaHoras ? String(regra.antecedenciaMinimaHoras) : "");
  const [enviando, setEnviando] = useState(false);
  const [aviso, setAviso] = useState<{ tom: "ok" | "erro"; texto: string } | null>(null);

  async function salvar(e: FormEvent) {
    e.preventDefault();
    const ant = lerAntecedencia(horas);
    if (!ant.ok) {
      setAviso({ tom: "erro", texto: "A antecedência é um número inteiro de horas, de 1 a 720 — ou vazio, sem antecedência." });
      return;
    }
    setEnviando(true);
    const r = await salvarRegraPauta(token, quem, ant.valor);
    setEnviando(false);
    if (r.ok) {
      onSalva(r.dado);
      setAviso({ tom: "ok", texto: `Regra salva: publica ${rotuloQuemPublica(r.dado.quemPublica).toLowerCase()}.` });
    } else {
      setAviso({ tom: "erro", texto: r.mensagem });
    }
  }

  return (
    <form className="adm-regra-pauta" onSubmit={(e) => void salvar(e)}>
      {!regra.configurada && (
        <p className="adm-texto">Esta Casa ainda não configurou: vale o padrão, a secretaria publica, sem antecedência mínima.</p>
      )}
      <fieldset>
        <legend>Quem publica a pauta</legend>
        {OPCOES_QUEM_PUBLICA.map((o) => (
          <label key={o.valor}>
            <input type="radio" name="quem-publica" value={o.valor} checked={quem === o.valor} onChange={() => setQuem(o.valor)} />
            <span>
              {o.rotulo} <small>{o.ajuda}</small>
            </span>
          </label>
        ))}
      </fieldset>
      <div className="campo">
        <label htmlFor="antecedencia-pauta">Antecedência mínima (horas antes da sessão)</label>
        <input
          id="antecedencia-pauta"
          inputMode="numeric"
          placeholder="sem antecedência"
          value={horas}
          onChange={(e) => setHoras(e.target.value)}
        />
      </div>
      <button type="submit" className="btn btn-contorno btn-mini" disabled={enviando}>
        {enviando ? "Salvando…" : "Salvar regras da pauta"}
      </button>
      <p role={aviso?.tom === "erro" ? "alert" : "status"} className="adm-texto">
        {aviso?.texto ?? ""}
      </p>
    </form>
  );
}
