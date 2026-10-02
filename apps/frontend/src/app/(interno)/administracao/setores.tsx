"use client";

// Bloco "Setores" da área do administrador (ADR-0020, Eixo 1): o `admin_ente` cria os setores da Casa, renomeia,
// desativa e diz QUEM é de cada um. Setor é ENDEREÇO de comunicado, não permissão — a tela diz isso, para ninguém
// lotar uma pessoa achando que lhe dá acesso.
//
// As pessoas vêm de `GET /comunicados/destinos` (campo `pessoas`): a administração não tinha uma lista de "pessoas da
// Casa" (o que ela carrega é o cadastro de VEREADORES). Quem já é do setor e não está nessa lista (o próprio
// administrador, que não entra na própria lista de destinos) continua aparecendo, marcado — a lotação é trocada
// INTEIRA no PUT, e sumir com quem a tela não conhece seria tirá-lo do setor sem ninguém pedir.

import { useMemo, useState, type FormEvent } from "react";
import { useDestinos } from "@/lib/use-comunicados";
import { criarSetor, definirMembros, salvarSetor, useSetores } from "@/lib/use-setores";
import { plural } from "@/lib/comunicacao-vista";
import type { MembroDeSetor, SetorOut } from "@/lib/contrato-comunicacao";

type Aviso = { tom: "ok" | "erro"; texto: string } | null;

export function Setores({ token }: { token: string | null }) {
  const { estado, recarregar } = useSetores(token);
  const destinos = useDestinos(token);
  const [nome, setNome] = useState("");
  const [enviando, setEnviando] = useState(false);
  const [aviso, setAviso] = useState<Aviso>(null);

  const pessoas: MembroDeSetor[] = destinos.estado.fase === "pronto" ? destinos.estado.dado.pessoas : [];

  async function criar(e: FormEvent) {
    e.preventDefault();
    const n = nome.trim();
    if (!n) {
      setAviso({ tom: "erro", texto: "Escreva o nome do setor (por exemplo: Protocolo)." });
      return;
    }
    setEnviando(true);
    const r = await criarSetor(token, n);
    setEnviando(false);
    if (!r.ok) {
      setAviso({ tom: "erro", texto: r.mensagem });
      return;
    }
    setNome("");
    setAviso({ tom: "ok", texto: `Setor ${n} criado. Agora diga quem é dele em “Pessoas”.` });
    recarregar();
  }

  const setores =
    estado.fase === "pronto"
      ? [...estado.dado.setores].sort((a, b) => Number(b.ativo) - Number(a.ativo) || a.nome.localeCompare(b.nome, "pt-BR"))
      : [];

  return (
    <section className="adm-auditoria adm-setores" aria-labelledby="adm-setores-titulo">
      <h2 id="adm-setores-titulo">Setores</h2>
      <p className="adm-texto">
        Os setores são os endereços internos da Casa — Secretaria, Jurídico, Protocolo — para onde a secretaria, a
        administração e a Mesa mandam comunicados. Diga quem é de cada setor; uma pessoa pode estar em mais de um. O setor
        não dá permissão no sistema: o que cada pessoa pode fazer continua vindo do papel dela.
      </p>

      <form className="adm-setor-novo" onSubmit={(e) => void criar(e)}>
        <div className="adm-campo">
          <label htmlFor="setor-novo">Nome do novo setor</label>
          <input id="setor-novo" value={nome} onChange={(e) => setNome(e.target.value)} autoComplete="off" />
        </div>
        <button type="submit" className="btn btn-contorno btn-mini" disabled={enviando}>
          {enviando ? "Criando…" : "Criar setor"}
        </button>
      </form>

      {aviso && (
        <p role={aviso.tom === "erro" ? "alert" : "status"} className={aviso.tom === "erro" ? "adm-erro" : "adm-aviso"}>
          {aviso.texto}
        </p>
      )}

      {estado.fase === "carregando" && <p role="status">Carregando os setores…</p>}
      {estado.fase === "erro" && (
        <p role="alert" className="adm-texto">
          {estado.mensagem}
        </p>
      )}
      {estado.fase === "pronto" && setores.length === 0 && (
        <p className="adm-texto">Nenhum setor ainda. Crie o primeiro acima.</p>
      )}
      {setores.length > 0 && (
        <ul className="adm-lista" aria-label="Setores da Casa">
          {setores.map((s) => (
            <ItemDoSetor
              key={s.id}
              setor={s}
              token={token}
              pessoas={pessoas}
              pessoasIndisponiveis={destinos.estado.fase === "erro"}
              aoMudar={(texto) => {
                setAviso({ tom: "ok", texto });
                recarregar();
              }}
              aoFalhar={(texto) => setAviso({ tom: "erro", texto })}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

function ItemDoSetor({
  setor,
  token,
  pessoas,
  pessoasIndisponiveis,
  aoMudar,
  aoFalhar,
}: {
  setor: SetorOut;
  token: string | null;
  pessoas: MembroDeSetor[];
  pessoasIndisponiveis: boolean;
  aoMudar: (texto: string) => void;
  aoFalhar: (texto: string) => void;
}) {
  const [modo, setModo] = useState<"ver" | "renomear" | "pessoas">("ver");
  const [novoNome, setNovoNome] = useState(setor.nome);
  const [enviando, setEnviando] = useState(false);
  const idNome = `setor-nome-${setor.id}`;

  async function salvar(dados: { nome: string; ativo: boolean }, texto: string) {
    setEnviando(true);
    const r = await salvarSetor(token, setor.id, dados);
    setEnviando(false);
    if (!r.ok) return aoFalhar(r.mensagem);
    setModo("ver");
    aoMudar(texto);
  }

  return (
    <li className="adm-item adm-setor">
      <div className="adm-linha">
        <span className="adm-quem">
          <b>{setor.nome}</b>
          <span>
            {setor.membros.length === 0 ? "Ninguém ainda" : plural(setor.membros.length, "pessoa", "pessoas")}
            {setor.membros.length > 0 && `: ${setor.membros.map((m) => m.nome).join(", ")}`}
          </span>
        </span>
        {!setor.ativo && <span className="chip chip-neutro">Desativado</span>}
      </div>

      {modo === "ver" && (
        <div className="adm-setor-acoes">
          {setor.ativo && (
            <button type="button" className="btn btn-contorno btn-mini" aria-label={`Pessoas do setor ${setor.nome}`} onClick={() => setModo("pessoas")}>
              Pessoas
            </button>
          )}
          <button
            type="button"
            className="btn btn-fantasma btn-mini"
            aria-label={`Renomear o setor ${setor.nome}`}
            onClick={() => {
              setNovoNome(setor.nome);
              setModo("renomear");
            }}
          >
            Renomear
          </button>
          <button
            type="button"
            className="btn btn-fantasma btn-mini"
            disabled={enviando}
            aria-label={`${setor.ativo ? "Desativar" : "Reativar"} o setor ${setor.nome}`}
            onClick={() =>
              void salvar(
                { nome: setor.nome, ativo: !setor.ativo },
                setor.ativo
                  ? `Setor ${setor.nome} desativado. Ele sai do formulário de envio; os comunicados já enviados ficam.`
                  : `Setor ${setor.nome} reativado.`,
              )
            }
          >
            {setor.ativo ? "Desativar" : "Reativar"}
          </button>
        </div>
      )}

      {modo === "renomear" && (
        <form
          className="adm-setor-novo"
          onSubmit={(e) => {
            e.preventDefault();
            const n = novoNome.trim();
            if (!n) return aoFalhar("O setor precisa de um nome.");
            void salvar({ nome: n, ativo: setor.ativo }, `Setor renomeado para ${n}. Os comunicados antigos guardam o nome de quando foram enviados.`);
          }}
        >
          <div className="adm-campo">
            <label htmlFor={idNome}>Novo nome do setor {setor.nome}</label>
            <input id={idNome} value={novoNome} onChange={(e) => setNovoNome(e.target.value)} autoComplete="off" />
          </div>
          <button type="submit" className="btn btn-contorno btn-mini" disabled={enviando}>
            Salvar nome
          </button>
          <button type="button" className="btn btn-fantasma btn-mini" onClick={() => setModo("ver")}>
            Cancelar
          </button>
        </form>
      )}

      {modo === "pessoas" && (
        <EditorDePessoas
          setor={setor}
          token={token}
          pessoas={pessoas}
          pessoasIndisponiveis={pessoasIndisponiveis}
          aoSalvar={(texto) => {
            setModo("ver");
            aoMudar(texto);
          }}
          aoFalhar={aoFalhar}
          aoCancelar={() => setModo("ver")}
        />
      )}
    </li>
  );
}

function EditorDePessoas({
  setor,
  token,
  pessoas,
  pessoasIndisponiveis,
  aoSalvar,
  aoFalhar,
  aoCancelar,
}: {
  setor: SetorOut;
  token: string | null;
  pessoas: MembroDeSetor[];
  pessoasIndisponiveis: boolean;
  aoSalvar: (texto: string) => void;
  aoFalhar: (texto: string) => void;
  aoCancelar: () => void;
}) {
  const [marcadas, setMarcadas] = useState<Set<string>>(() => new Set(setor.membros.map((m) => m.identidadeId)));
  const [filtro, setFiltro] = useState("");
  const [enviando, setEnviando] = useState(false);
  // a Casa toda + quem já é do setor e a lista não traz (ver o cabeçalho)
  const todas = useMemo(() => {
    const porId = new Map<string, MembroDeSetor>();
    for (const p of pessoas) porId.set(p.identidadeId, p);
    for (const m of setor.membros) if (!porId.has(m.identidadeId)) porId.set(m.identidadeId, m);
    return [...porId.values()].sort((a, b) => a.nome.localeCompare(b.nome, "pt-BR"));
  }, [pessoas, setor.membros]);
  const termo = filtro.trim().toLocaleLowerCase("pt-BR");
  const visiveis = termo ? todas.filter((p) => p.nome.toLocaleLowerCase("pt-BR").includes(termo)) : todas;
  const idFiltro = `setor-filtro-${setor.id}`;

  function alternar(id: string) {
    setMarcadas((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  }

  async function salvar() {
    setEnviando(true);
    const r = await definirMembros(token, setor.id, [...marcadas]);
    setEnviando(false);
    if (!r.ok) return aoFalhar(r.mensagem);
    aoSalvar(`Pessoas do setor ${setor.nome} salvas: ${marcadas.size === 0 ? "ninguém" : plural(marcadas.size, "pessoa", "pessoas")}.`);
  }

  return (
    <fieldset className="adm-setor-pessoas">
      <legend>Quem é do setor {setor.nome}</legend>
      {pessoasIndisponiveis && (
        <p className="adm-erro">A lista de pessoas da Casa não carregou agora. Por enquanto só dá para tirar pessoas do setor.</p>
      )}
      <div className="adm-campo">
        <label htmlFor={idFiltro}>Filtrar por nome</label>
        <input id={idFiltro} value={filtro} onChange={(e) => setFiltro(e.target.value)} autoComplete="off" />
      </div>
      {visiveis.length === 0 ? (
        <p className="adm-texto">{todas.length === 0 ? "Nenhuma pessoa com acesso na Casa ainda." : "Ninguém com esse nome."}</p>
      ) : (
        <ul className="adm-setor-opcoes">
          {visiveis.map((p) => (
            <li key={p.identidadeId}>
              <label>
                <input type="checkbox" checked={marcadas.has(p.identidadeId)} onChange={() => alternar(p.identidadeId)} />
                <span>{p.nome}</span>
              </label>
            </li>
          ))}
        </ul>
      )}
      <div className="adm-setor-acoes">
        <button type="button" className="btn btn-contorno btn-mini" disabled={enviando} onClick={() => void salvar()}>
          {enviando ? "Salvando…" : `Salvar (${plural(marcadas.size, "pessoa", "pessoas")})`}
        </button>
        <button type="button" className="btn btn-fantasma btn-mini" onClick={aoCancelar}>
          Cancelar
        </button>
      </div>
    </fieldset>
  );
}
