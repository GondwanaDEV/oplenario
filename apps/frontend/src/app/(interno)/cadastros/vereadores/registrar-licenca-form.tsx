"use client";

// Form "Registrar licença" (Onda D Slice 4). Só aparece habilitado na página quando o mandato atual está
// `vigente` (regra na própria page.tsx — o backend também recusa via `:conflito/sem-mandato-vigente`, 409,
// mas o FE evita a viagem de rede óbvia). `inicio` é o único campo obrigatório.

import { useState } from "react";
import { useRegistrarLicenca } from "@/lib/use-registrar-licenca";
import { validarLicenca } from "@/lib/cadastro-vereadores-forms";

export function RegistrarLicencaForm({
  token, vereadorId, onSucesso, onCancelar,
}: {
  token: string | null;
  vereadorId: string;
  onSucesso: (vereadorId: string) => void;
  onCancelar: () => void;
}) {
  const [inicio, setInicio] = useState("");
  const [fim, setFim] = useState("");
  const [motivo, setMotivo] = useState("");
  const [tocado, setTocado] = useState(false);
  const { registrar, estado, erro } = useRegistrarLicenca(token, vereadorId);
  const { erros, valido } = validarLicenca({ inicio, fim: fim || undefined });

  async function aoEnviar(e: React.FormEvent) {
    e.preventDefault();
    setTocado(true);
    if (!valido) return;
    try {
      await registrar({ inicio, fim: fim || undefined, motivo: motivo.trim() || undefined });
      onSucesso(vereadorId);
    } catch { /* estado 'erro' já exibido abaixo */ }
  }

  return (
    <form className="form-cad" onSubmit={aoEnviar} aria-label="Registrar licença">
      <div className="campo">
        <label htmlFor="rl-inicio">Início*</label>
        <input id="rl-inicio" type="date" value={inicio} onChange={(e) => setInicio(e.target.value)}
               aria-invalid={tocado && !!erros.inicio} aria-describedby={erros.inicio ? "rl-inicio-erro" : undefined} />
        {tocado && erros.inicio && <p id="rl-inicio-erro" role="alert" className="campo-erro">{erros.inicio}</p>}
      </div>
      <div className="campo">
        <label htmlFor="rl-fim">Fim</label>
        <input id="rl-fim" type="date" value={fim} onChange={(e) => setFim(e.target.value)}
               aria-invalid={tocado && !!erros.fim} aria-describedby={erros.fim ? "rl-fim-erro" : undefined} />
        {tocado && erros.fim && <p id="rl-fim-erro" role="alert" className="campo-erro">{erros.fim}</p>}
      </div>
      <div className="campo">
        <label htmlFor="rl-motivo">Motivo</label>
        <input id="rl-motivo" value={motivo} onChange={(e) => setMotivo(e.target.value)} />
      </div>
      {estado === "erro" && <p role="alert" className="campo-erro">{erro}</p>}
      <div className="form-acoes">
        <button type="submit" className="btn btn-primaria btn-mini" disabled={estado === "enviando" || (tocado && !valido)}>
          {estado === "enviando" ? "Salvando…" : "Registrar licença"}
        </button>
        <button type="button" className="btn btn-fantasma btn-mini" onClick={onCancelar}>Cancelar</button>
      </div>
    </form>
  );
}
