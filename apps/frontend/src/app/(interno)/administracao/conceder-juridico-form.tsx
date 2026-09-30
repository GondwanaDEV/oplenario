"use client";

// Form "Dar acesso ao jurídico" (ADR-0019) — o administrador da Casa concede o papel `juridico` a um servidor (procurador
// efetivo, assessor comissionado ou advogado contratado): ele redige e assina o parecer jurídico. Além de nome, CPF e
// e-mail institucional, o acesso registra a QUALIFICAÇÃO e a OAB, porque o parecer precisa dizer a que título foi
// assinado. Um clique dispara os 2 passos de use-conceder-acesso.ts (identidade -> acesso, "acesso por último"). O
// vínculo é de servidor: o papel nunca vai para um vereador (o backend também recusa).

import { useState } from "react";
import { useConcederJuridico } from "@/lib/use-conceder-acesso";
import { validarConcederAcesso, apenasDigitos } from "@/lib/cadastro-vereadores-forms";
import { QUALIFICACOES, validarConcessaoJuridico } from "@/lib/juridico-vista";

export function ConcederJuridicoForm({ token, onSucesso, onCancelar }: {
  token: string | null;
  onSucesso: (nome: string) => void;
  onCancelar: () => void;
}) {
  const [nome, setNome] = useState("");
  const [cpf, setCpf] = useState("");
  const [email, setEmail] = useState("");
  const [qualificacao, setQualificacao] = useState("");
  const [oab, setOab] = useState("");
  const [tocado, setTocado] = useState(false);
  const { conceder, estado, erro } = useConcederJuridico(token);
  const base = validarConcederAcesso({ cpf, email });
  const erroNome = nome.trim().length < 3 ? "Informe o nome completo." : undefined;
  const juridico = validarConcessaoJuridico({ qualificacao, oab });
  const valido = base.valido && !erroNome && !juridico.qualificacao && !juridico.oab;

  async function aoEnviar(e: React.FormEvent) {
    e.preventDefault();
    setTocado(true);
    if (!valido) return;
    try {
      await conceder({ nome: nome.trim(), cpf: apenasDigitos(cpf), email: email.trim(), qualificacao, oab: oab.trim() });
      onSucesso(nome.trim());
    } catch { /* o estado 'erro' já aparece abaixo, com a mensagem do servidor */ }
  }

  return (
    <form className="form-cad" onSubmit={aoEnviar} aria-label="Dar acesso ao jurídico">
      <div className="campo">
        <label htmlFor="ju-nome">Nome completo*</label>
        <input id="ju-nome" value={nome} onChange={(e) => setNome(e.target.value)}
          aria-invalid={tocado && !!erroNome} aria-describedby={erroNome ? "ju-nome-erro" : undefined} />
        {tocado && erroNome && <p id="ju-nome-erro" role="alert" className="campo-erro">{erroNome}</p>}
      </div>
      <div className="campo">
        <label htmlFor="ju-cpf">CPF*</label>
        <input id="ju-cpf" inputMode="numeric" placeholder="Somente números" value={cpf} onChange={(e) => setCpf(e.target.value)}
          aria-invalid={tocado && !!base.erros.cpf} aria-describedby={base.erros.cpf ? "ju-cpf-erro" : undefined} />
        {tocado && base.erros.cpf && <p id="ju-cpf-erro" role="alert" className="campo-erro">{base.erros.cpf}</p>}
      </div>
      <div className="campo">
        <label htmlFor="ju-email">E-mail institucional*</label>
        <input id="ju-email" type="email" placeholder="nome@camara.gov.br" value={email} onChange={(e) => setEmail(e.target.value)}
          aria-invalid={tocado && !!base.erros.email} aria-describedby={base.erros.email ? "ju-email-erro" : undefined} />
        {tocado && base.erros.email && <p id="ju-email-erro" role="alert" className="campo-erro">{base.erros.email}</p>}
      </div>
      <div className="campo">
        <label htmlFor="ju-qualificacao">Qualificação*</label>
        <select id="ju-qualificacao" value={qualificacao} onChange={(e) => setQualificacao(e.target.value)}
          aria-invalid={tocado && !!juridico.qualificacao} aria-describedby={juridico.qualificacao ? "ju-qualificacao-erro" : undefined}>
          <option value="">Escolha…</option>
          {QUALIFICACOES.map((q) => <option key={q.valor} value={q.valor}>{q.rotulo}</option>)}
        </select>
        {tocado && juridico.qualificacao && <p id="ju-qualificacao-erro" role="alert" className="campo-erro">{juridico.qualificacao}</p>}
      </div>
      <div className="campo">
        <label htmlFor="ju-oab">OAB*</label>
        <input id="ju-oab" placeholder="CE 12345" value={oab} onChange={(e) => setOab(e.target.value)}
          aria-invalid={tocado && !!juridico.oab} aria-describedby={juridico.oab ? "ju-oab-erro" : undefined} />
        {tocado && juridico.oab && <p id="ju-oab-erro" role="alert" className="campo-erro">{juridico.oab}</p>}
      </div>
      {estado === "erro" && <p role="alert" className="campo-erro">{erro}</p>}
      <div className="form-acoes">
        <button type="submit" className="btn btn-primaria btn-mini" disabled={estado === "enviando" || (tocado && !valido)}>
          {estado === "enviando" ? "Concedendo…" : "Dar acesso ao jurídico"}
        </button>
        <button type="button" className="btn btn-fantasma btn-mini" onClick={onCancelar}>Cancelar</button>
      </div>
    </form>
  );
}
