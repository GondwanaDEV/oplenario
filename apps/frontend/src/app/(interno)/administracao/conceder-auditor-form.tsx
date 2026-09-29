"use client";

// Form "Dar acesso ao controle interno" (ADR-0017) — o administrador da Casa concede o papel `auditor` a um servidor
// (procuradoria, controladoria): ele só LÊ a trilha de auditoria da Casa inteira, confere a cadeia e exporta. Nome,
// CPF e e-mail institucional; um clique dispara os 2 passos de use-conceder-acesso.ts (identidade -> acesso, "acesso
// por último"). O vínculo é de servidor: o papel de auditor nunca vai para um vereador (o backend também recusa).

import { useState } from "react";
import { useConcederAuditor } from "@/lib/use-conceder-acesso";
import { validarConcederAcesso, apenasDigitos } from "@/lib/cadastro-vereadores-forms";

export function ConcederAuditorForm({ token, onSucesso, onCancelar }: {
  token: string | null;
  onSucesso: (nome: string) => void;
  onCancelar: () => void;
}) {
  const [nome, setNome] = useState("");
  const [cpf, setCpf] = useState("");
  const [email, setEmail] = useState("");
  const [tocado, setTocado] = useState(false);
  const { conceder, estado, erro } = useConcederAuditor(token);
  const base = validarConcederAcesso({ cpf, email });
  const erroNome = nome.trim().length < 3 ? "Informe o nome completo." : undefined;
  const valido = base.valido && !erroNome;

  async function aoEnviar(e: React.FormEvent) {
    e.preventDefault();
    setTocado(true);
    if (!valido) return;
    try {
      await conceder({ nome: nome.trim(), cpf: apenasDigitos(cpf), email: email.trim() });
      onSucesso(nome.trim());
    } catch { /* o estado 'erro' já aparece abaixo, com a mensagem do servidor */ }
  }

  return (
    <form className="form-cad" onSubmit={aoEnviar} aria-label="Dar acesso ao controle interno">
      <div className="campo">
        <label htmlFor="au-nome">Nome completo*</label>
        <input id="au-nome" value={nome} onChange={(e) => setNome(e.target.value)}
          aria-invalid={tocado && !!erroNome} aria-describedby={erroNome ? "au-nome-erro" : undefined} />
        {tocado && erroNome && <p id="au-nome-erro" role="alert" className="campo-erro">{erroNome}</p>}
      </div>
      <div className="campo">
        <label htmlFor="au-cpf">CPF*</label>
        <input id="au-cpf" inputMode="numeric" placeholder="Somente números" value={cpf} onChange={(e) => setCpf(e.target.value)}
          aria-invalid={tocado && !!base.erros.cpf} aria-describedby={base.erros.cpf ? "au-cpf-erro" : undefined} />
        {tocado && base.erros.cpf && <p id="au-cpf-erro" role="alert" className="campo-erro">{base.erros.cpf}</p>}
      </div>
      <div className="campo">
        <label htmlFor="au-email">E-mail institucional*</label>
        <input id="au-email" type="email" placeholder="nome@camara.gov.br" value={email} onChange={(e) => setEmail(e.target.value)}
          aria-invalid={tocado && !!base.erros.email} aria-describedby={base.erros.email ? "au-email-erro" : undefined} />
        {tocado && base.erros.email && <p id="au-email-erro" role="alert" className="campo-erro">{base.erros.email}</p>}
      </div>
      {estado === "erro" && <p role="alert" className="campo-erro">{erro}</p>}
      <div className="form-acoes">
        <button type="submit" className="btn btn-primaria btn-mini" disabled={estado === "enviando" || (tocado && !valido)}>
          {estado === "enviando" ? "Concedendo…" : "Dar acesso à trilha"}
        </button>
        <button type="button" className="btn btn-fantasma btn-mini" onClick={onCancelar}>Cancelar</button>
      </div>
    </form>
  );
}
