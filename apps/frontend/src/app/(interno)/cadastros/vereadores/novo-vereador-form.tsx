"use client";

// Form "Novo vereador" (Onda D Slice 4). Painel inline (não modal — evita o carry de focus-trap da C4);
// reusa .btn/.campo do chassi. Valida com validarNovoVereador (pt-BR), envia com useCriarVereador,
// chama onSucesso(novoId) p/ a página refazer o fetch e selecionar o novo. onCancelar fecha o painel.

import { useState } from "react";
import { useCriarVereador } from "@/lib/use-criar-vereador";
import { validarNovoVereador } from "@/lib/cadastro-vereadores-forms";

export function NovoVereadorForm({
  token, onSucesso, onCancelar,
}: { token: string | null; onSucesso: (novoId: string) => void; onCancelar: () => void }) {
  const [nome, setNome] = useState("");
  const [nomeParlamentar, setNomeParlamentar] = useState("");
  const [tocado, setTocado] = useState(false);
  const { criar, estado, erro } = useCriarVereador(token);
  const { erros, valido } = validarNovoVereador({ nome });

  async function aoEnviar(e: React.FormEvent) {
    e.preventDefault();
    setTocado(true);
    if (!valido) return;
    try {
      const { id } = await criar({ nome: nome.trim(), nomeParlamentar: nomeParlamentar.trim() || undefined });
      onSucesso(id);
    } catch { /* estado 'erro' já exibido abaixo */ }
  }

  return (
    <form className="form-cad" onSubmit={aoEnviar} aria-label="Novo vereador">
      <div className="campo">
        <label htmlFor="nv-nome">Nome*</label>
        <input id="nv-nome" value={nome} onChange={(e) => setNome(e.target.value)}
               aria-invalid={tocado && !!erros.nome} aria-describedby={erros.nome ? "nv-nome-erro" : undefined} />
        {tocado && erros.nome && <p id="nv-nome-erro" role="alert" className="campo-erro">{erros.nome}</p>}
      </div>
      <div className="campo">
        <label htmlFor="nv-parlamentar">Nome parlamentar</label>
        <input id="nv-parlamentar" value={nomeParlamentar} onChange={(e) => setNomeParlamentar(e.target.value)} />
      </div>
      {estado === "erro" && <p role="alert" className="campo-erro">{erro}</p>}
      <div className="form-acoes">
        <button type="submit" className="btn btn-primaria btn-mini" disabled={estado === "enviando" || (tocado && !valido)}>
          {estado === "enviando" ? "Salvando…" : "Criar vereador"}
        </button>
        <button type="button" className="btn btn-fantasma btn-mini" onClick={onCancelar}>Cancelar</button>
      </div>
    </form>
  );
}
