"use client";

// Form "Registrar mandato" (Onda D Slice 4). A legislatura NÃO é escolhida pelo usuário — só existe UMA
// vigente por vez (useLegislaturaVigente, Task 8); o form mostra a faixa (numero/anos) como display e manda
// o `id` oculto. Sem legislatura vigente cadastrada, não há o que registrar — estado desabilitado, sem
// submit (guarda a viagem de rede que o backend recusaria de qualquer forma). Um 409 (mandato sobreposto,
// backend `diplomat/http/in.clj`) chega como `erro` do hook e é exibido tal-qual, já em pt-BR.

import { useState } from "react";
import { useRegistrarMandato } from "@/lib/use-registrar-mandato";
import { validarMandato } from "@/lib/cadastro-vereadores-forms";
import type { LegislaturaVigenteOut } from "@/lib/contrato-cadastros.gen";

export function RegistrarMandatoForm({
  token, vereadorId, legislatura, onSucesso, onCancelar,
}: {
  token: string | null;
  vereadorId: string;
  legislatura: LegislaturaVigenteOut | null;
  onSucesso: (vereadorId: string) => void;
  onCancelar: () => void;
}) {
  const [partido, setPartido] = useState("");
  const [natureza, setNatureza] = useState("titular");
  const [vigenciaInicio, setVigenciaInicio] = useState("");
  const [vigenciaFim, setVigenciaFim] = useState("");
  const [tocado, setTocado] = useState(false);
  const { registrar, estado, erro } = useRegistrarMandato(token, vereadorId);

  if (!legislatura) {
    return (
      <div className="form-cad" role="status" aria-label="Registrar mandato">
        <p className="campo-erro">Cadastre a legislatura vigente primeiro.</p>
        <div className="form-acoes">
          <button type="button" className="btn btn-fantasma btn-mini" onClick={onCancelar}>Fechar</button>
        </div>
      </div>
    );
  }

  // Alias local pra TS reter a checagem de nulidade acima dentro do closure de `aoEnviar` (narrowing de
  // `legislatura` não atravessa a fronteira de uma function declaration aninhada).
  const leg = legislatura;

  const { erros, valido } = validarMandato({
    legislaturaId: leg.id, natureza, vigenciaInicio, vigenciaFim: vigenciaFim || undefined,
  });

  async function aoEnviar(e: React.FormEvent) {
    e.preventDefault();
    setTocado(true);
    if (!valido) return;
    try {
      await registrar({
        legislaturaId: leg.id,
        partido: partido.trim() || undefined,
        natureza,
        vigenciaInicio,
        vigenciaFim: vigenciaFim || undefined,
      });
      onSucesso(vereadorId);
    } catch { /* estado 'erro' já exibido abaixo (inclui 409 de mandato sobreposto) */ }
  }

  return (
    <form className="form-cad" onSubmit={aoEnviar} aria-label="Registrar mandato">
      <div className="campo">
        <span id="rm-legislatura-rotulo">Legislatura</span>
        <p className="mono" aria-labelledby="rm-legislatura-rotulo">
          {legislatura.numero}ª ({legislatura.anoInicio}–{legislatura.anoFim})
        </p>
      </div>
      <div className="campo">
        <label htmlFor="rm-partido">Partido</label>
        <input id="rm-partido" value={partido} onChange={(e) => setPartido(e.target.value)} />
      </div>
      <div className="campo">
        <label htmlFor="rm-natureza">Natureza*</label>
        <select id="rm-natureza" value={natureza} onChange={(e) => setNatureza(e.target.value)}
                aria-invalid={tocado && !!erros.natureza}
                aria-describedby={erros.natureza ? "rm-natureza-erro" : undefined}>
          <option value="titular">Titular</option>
          <option value="suplencia">Suplência</option>
        </select>
        {tocado && erros.natureza && <p id="rm-natureza-erro" role="alert" className="campo-erro">{erros.natureza}</p>}
      </div>
      <div className="campo">
        <label htmlFor="rm-inicio">Início da vigência*</label>
        <input id="rm-inicio" type="date" value={vigenciaInicio} onChange={(e) => setVigenciaInicio(e.target.value)}
               aria-invalid={tocado && !!erros.vigenciaInicio} aria-describedby={erros.vigenciaInicio ? "rm-inicio-erro" : undefined} />
        {tocado && erros.vigenciaInicio && <p id="rm-inicio-erro" role="alert" className="campo-erro">{erros.vigenciaInicio}</p>}
      </div>
      <div className="campo">
        <label htmlFor="rm-fim">Fim da vigência</label>
        <input id="rm-fim" type="date" value={vigenciaFim} onChange={(e) => setVigenciaFim(e.target.value)}
               aria-invalid={tocado && !!erros.vigenciaFim} aria-describedby={erros.vigenciaFim ? "rm-fim-erro" : undefined} />
        {tocado && erros.vigenciaFim && <p id="rm-fim-erro" role="alert" className="campo-erro">{erros.vigenciaFim}</p>}
      </div>
      {estado === "erro" && <p role="alert" className="campo-erro">{erro}</p>}
      <div className="form-acoes">
        <button type="submit" className="btn btn-primaria btn-mini" disabled={estado === "enviando" || (tocado && !valido)}>
          {estado === "enviando" ? "Salvando…" : "Registrar mandato"}
        </button>
        <button type="button" className="btn btn-fantasma btn-mini" onClick={onCancelar}>Cancelar</button>
      </div>
    </form>
  );
}
