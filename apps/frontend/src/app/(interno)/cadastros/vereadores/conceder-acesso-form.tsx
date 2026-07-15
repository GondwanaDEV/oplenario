"use client";

// Form "Conceder acesso" (Onda D Slice 5, Task 11) — só renderiza para quem tem o papel `admin_ente`
// (guard em page.tsx, mesmo padrão de GuardVereador/pauta-convocacao: authz real é server-side, o guard
// aqui é só UX). Pede CPF e e-mail institucional; o NOME não é pedido — vem do próprio cadastro do
// vereador (`nome`, prop fixa), porque a identidade sendo criada é a DESTE vereador, não uma pessoa nova.
//
// Um único clique dispara os 3 passos encadeados em use-conceder-acesso.ts (identidade -> ligar cadastro ->
// conceder acesso, "acesso por último"). Não há como o usuário pausar entre os passos nem reordená-los —
// a única superfície é "Conceder acesso" (dispara tudo) e "Cancelar" (não dispara nada). Um erro em
// qualquer passo aparece aqui como o mesmo alerta inline dos demais forms desta página (409 de identidade
// já vinculada a outro vereador, 400 de CPF inválido, etc. — a mensagem já vem em pt-BR do backend).

import { useState } from "react";
import { useConcederAcesso } from "@/lib/use-conceder-acesso";
import { validarConcederAcesso, apenasDigitos } from "@/lib/cadastro-vereadores-forms";

export function ConcederAcessoForm({
  token, vereadorId, nome, onSucesso, onCancelar,
}: {
  token: string | null;
  vereadorId: string;
  nome: string;
  onSucesso: (vereadorId: string) => void;
  onCancelar: () => void;
}) {
  const [cpf, setCpf] = useState("");
  const [email, setEmail] = useState("");
  const [tocado, setTocado] = useState(false);
  const { conceder, estado, erro } = useConcederAcesso(token);
  const { erros, valido } = validarConcederAcesso({ cpf, email });

  async function aoEnviar(e: React.FormEvent) {
    e.preventDefault();
    setTocado(true);
    if (!valido) return;
    try {
      await conceder({ vereadorId, cpf: apenasDigitos(cpf), nome, email: email.trim() });
      onSucesso(vereadorId);
    } catch { /* estado 'erro' já exibido abaixo (409 identidade vinculada, 400 cpf inválido, etc.) */ }
  }

  return (
    <form className="form-cad" onSubmit={aoEnviar} aria-label="Conceder acesso">
      <div className="campo">
        <span id="ca-nome-rotulo">Vereador(a)</span>
        <p aria-labelledby="ca-nome-rotulo">{nome}</p>
      </div>
      <div className="campo">
        <label htmlFor="ca-cpf">CPF*</label>
        <input
          id="ca-cpf" inputMode="numeric" placeholder="Somente números"
          value={cpf} onChange={(e) => setCpf(e.target.value)}
          aria-invalid={tocado && !!erros.cpf}
          aria-describedby={erros.cpf ? "ca-cpf-erro" : undefined}
        />
        {tocado && erros.cpf && <p id="ca-cpf-erro" role="alert" className="campo-erro">{erros.cpf}</p>}
      </div>
      <div className="campo">
        <label htmlFor="ca-email">E-mail institucional*</label>
        <input
          id="ca-email" type="email" placeholder="nome@camara.gov.br"
          value={email} onChange={(e) => setEmail(e.target.value)}
          aria-invalid={tocado && !!erros.email}
          aria-describedby={erros.email ? "ca-email-erro" : undefined}
        />
        {tocado && erros.email && <p id="ca-email-erro" role="alert" className="campo-erro">{erros.email}</p>}
      </div>
      {estado === "erro" && <p role="alert" className="campo-erro">{erro}</p>}
      <div className="form-acoes">
        <button type="submit" className="btn btn-primaria btn-mini" disabled={estado === "enviando" || (tocado && !valido)}>
          {estado === "enviando" ? "Concedendo…" : "Conceder acesso"}
        </button>
        <button type="button" className="btn btn-fantasma btn-mini" onClick={onCancelar}>Cancelar</button>
      </div>
    </form>
  );
}
