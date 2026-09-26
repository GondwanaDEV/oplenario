"use client";

// A fila de gravações sem sessão (Faixa A / A.2). Cada linha é um arquivo que o utilitário de captação enviou do
// PC do OBS: de quando é, quanto dura e de onde veio. Quando o horário casa com uma sessão, a tela sugere e o
// vínculo é um toque; senão, a secretaria escolhe a sessão na lista. Vincular é o que põe a gravação no caminho da
// transcrição e da ata.

import { useState } from "react";
import { useGravacoesPendentes, vincularGravacao } from "@/lib/use-gravacoes-pendentes";
import { useSessoes } from "@/lib/use-sessoes";
import { sessoesVinculaveis, vistaGravacao, type VistaGravacao } from "@/lib/gravacao-vista";

function LinhaGravacao({
  g,
  opcoes,
  token,
  onMudou,
}: {
  g: VistaGravacao;
  opcoes: Array<{ id: string; rotulo: string }>;
  token: string | null;
  onMudou: () => void;
}) {
  const [outra, setOutra] = useState(false);
  const [escolhida, setEscolhida] = useState("");
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  async function vincular(sessaoId: string) {
    setEnviando(true);
    setErro(null);
    const falha = await vincularGravacao(token, sessaoId, g.id, g.lockVersion);
    setEnviando(false);
    if (falha) setErro(falha);
    onMudou();
  }

  return (
    <li className="grav-item">
      <p className="grav-titulo">
        {g.titulo}
        {g.restrita && <span className="grav-tag">Restrita</span>}
      </p>
      {g.detalhe && <p className="grav-detalhe">{g.detalhe}</p>}

      {g.sugestao && !outra ? (
        <div className="grav-sugestao">
          <p>
            Pelo horário, é da <strong>{g.sugestao.rotulo}</strong>.
          </p>
          <div className="grav-acoes">
            <button type="button" className="btn btn-primario" disabled={enviando} onClick={() => vincular(g.sugestao!.sessaoId)}>
              {enviando ? "Vinculando…" : "Vincular a esta sessão"}
            </button>
            <button type="button" className="btn btn-fantasma" onClick={() => setOutra(true)}>
              É de outra sessão
            </button>
          </div>
        </div>
      ) : (
        <form
          className="grav-escolha"
          onSubmit={(e) => {
            e.preventDefault();
            if (escolhida) vincular(escolhida);
          }}
        >
          {!g.sugestao && <p>Nenhuma sessão no horário desta gravação. Escolha a sessão:</p>}
          <label className="grav-rotulo" htmlFor={`sessao-${g.id}`}>
            Sessão
          </label>
          <select id={`sessao-${g.id}`} value={escolhida} onChange={(e) => setEscolhida(e.target.value)}>
            <option value="">Escolha…</option>
            {opcoes.map((o) => (
              <option key={o.id} value={o.id}>
                {o.rotulo}
              </option>
            ))}
          </select>
          <button type="submit" className="btn btn-primario" disabled={!escolhida || enviando}>
            {enviando ? "Vinculando…" : "Vincular"}
          </button>
        </form>
      )}
      {erro && (
        <p className="grav-erro" role="alert">
          {erro}
        </p>
      )}
    </li>
  );
}

export function FilaGravacoes({ token = null }: { token?: string | null }) {
  const { itens, estado, recarregar } = useGravacoesPendentes(token);
  const { sessoes } = useSessoes(token);
  const opcoes = sessoesVinculaveis(sessoes ?? []);

  return (
    <main className="envelope grav">
      <header className="grav-cabeca">
        <h1>Gravações recebidas</h1>
        <p className="grav-sub">
          Arquivos enviados pelo computador da transmissão que ainda não estão ligados a uma sessão. Vincular é o que
          leva a gravação para a transcrição e a ata.
        </p>
        {estado === "pronto" && (
          <p className="grav-resumo" role="status">
            {itens.length === 0
              ? "Nenhuma gravação esperando vínculo."
              : itens.length === 1
                ? "1 gravação esperando vínculo"
                : `${itens.length} gravações esperando vínculo`}
          </p>
        )}
      </header>

      {estado === "carregando" && <p role="status">Carregando as gravações…</p>}
      {estado === "erro" && <p role="status">Não foi possível carregar as gravações.</p>}

      {estado === "pronto" && itens.length > 0 && (
        <ul className="grav-lista">
          {itens.map((g) => (
            <LinhaGravacao key={g.id} g={vistaGravacao(g)} opcoes={opcoes} token={token} onMudou={recarregar} />
          ))}
        </ul>
      )}
    </main>
  );
}
