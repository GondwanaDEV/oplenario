"use client";

// Lista + ações da fila de moderação (GAP docs/20). Separado de ConteudoModeracao (que só monta o
// TopoInterno + isto) para ser testável com `token` por PROP — sem <AuthProvider>, mesma disciplina de
// acoes-tramitacao.tsx. Liga GET /moderacao/comentarios + POST /comentarios/:id/moderar.
//
// O `corpo` é conteúdo de usuário não sanitizado — renderizado como TEXTO via `{item.corpo}` (o React
// escapa por padrão; nunca dangerouslySetInnerHTML).

import { useState } from "react";
import { useFilaModeracao } from "@/lib/use-fila-moderacao";
import { useModerarComentario, type ModerarEntrada } from "@/lib/use-moderar-comentario";
import { ordenarFila, resumirFila } from "@/lib/moderacao-vista";
import { formatarData } from "@/lib/formatar-data";

export function FilaModeracao({ token = null }: { token?: string | null }) {
  const { dados, estado, recarregar } = useFilaModeracao(token);
  const { moderar, erro } = useModerarComentario(token);
  const [rejeitandoId, setRejeitandoId] = useState<string | null>(null);
  const [motivo, setMotivo] = useState("");
  const [enviandoId, setEnviandoId] = useState<string | null>(null);

  async function aoModerar(id: string, entrada: ModerarEntrada) {
    setEnviandoId(id);
    try {
      await moderar(id, entrada);
      setRejeitandoId(null);
      setMotivo("");
      await recarregar();
    } catch {
      // erro já exposto via `erro` (useModerarComentario).
    } finally {
      setEnviandoId(null);
    }
  }

  const fila = dados ? ordenarFila(dados) : [];
  const resumo = dados ? resumirFila(dados) : { total: 0, denunciados: 0 };

  return (
    <main className="envelope">
      <header className="mod-cabeca">
        <h1>Moderação de comentários</h1>
        {estado === "pronto" && (
          <p className="mod-resumo" role="status">
            {resumo.total === 0
              ? "Nenhum comentário na fila."
              : `${resumo.total} na fila${resumo.denunciados > 0 ? ` · ${resumo.denunciados} denunciado(s)` : ""}`}
          </p>
        )}
      </header>

      {estado === "carregando" && <p role="status">Carregando a fila…</p>}
      {estado === "erro" && <p role="status">Não foi possível carregar a fila de moderação.</p>}

      {erro && (
        <p role="alert" className="campo-erro">
          {erro}
        </p>
      )}

      {estado === "pronto" && fila.length > 0 && (
        <ul className="mod-lista">
          {fila.map((item) => {
            const enviando = enviandoId === item.id;
            return (
              <li key={item.id} className="mod-item">
                <div className="mod-item-meta">
                  {item.denunciado && <span className="mod-denunciado">Denunciado</span>}
                  <span className="mod-data">{formatarData(item.criadoEm)}</span>
                </div>
                <p className="mod-corpo">{item.corpo}</p>
                <p className="mod-origem">
                  Autor <code>{item.autorIdentidadeId}</code> · matéria <code>{item.proposicaoId}</code>
                </p>

                {rejeitandoId === item.id ? (
                  <div className="mod-rejeicao">
                    <label htmlFor={`motivo-${item.id}`}>Motivo da rejeição (opcional)</label>
                    <textarea
                      id={`motivo-${item.id}`}
                      value={motivo}
                      onChange={(e) => setMotivo(e.target.value)}
                      rows={2}
                      maxLength={2000}
                    />
                    <div className="mod-acoes">
                      <button
                        type="button"
                        className="btn btn-primaria"
                        disabled={enviando}
                        onClick={() => aoModerar(item.id, { acao: "rejeitado", motivoRejeicao: motivo.trim() || undefined })}
                      >
                        Confirmar rejeição
                      </button>
                      <button
                        type="button"
                        className="btn btn-fantasma"
                        disabled={enviando}
                        onClick={() => {
                          setRejeitandoId(null);
                          setMotivo("");
                        }}
                      >
                        Cancelar
                      </button>
                    </div>
                  </div>
                ) : (
                  <div className="mod-acoes">
                    <button
                      type="button"
                      className="btn btn-contorno"
                      disabled={enviando}
                      onClick={() => aoModerar(item.id, { acao: "aprovado" })}
                    >
                      Aprovar
                    </button>
                    <button
                      type="button"
                      className="btn btn-contorno"
                      disabled={enviando}
                      onClick={() => {
                        setRejeitandoId(item.id);
                        setMotivo("");
                      }}
                    >
                      Rejeitar
                    </button>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </main>
  );
}
