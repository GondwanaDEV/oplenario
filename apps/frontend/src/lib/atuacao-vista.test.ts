import { describe, expect, it } from "vitest";
import { agruparPorSituacao, derivarAtuacao, linhaDeVoto } from "./atuacao-vista";
import type { MeuPainelOut, MeusVotosOut, MeuVotoOut } from "./contrato-legislativo.gen";
import type { PerfilVereadorOut } from "./contrato-portal.gen";

const m = (id: string, estado: string) => ({
  proposicaoId: id, tipo: "projeto_lei", ano: 2026, sequencial: 1, ementa: id, estado,
});

describe("agruparPorSituacao", () => {
  it("conta por estado do rito, com a largura proporcional ao que foi listado", () => {
    const r = agruparPorSituacao([m("a", "em_comissoes"), m("b", "em_comissoes"), m("c", "arquivada"), m("d", "x_y")]);
    expect(r).toEqual([
      { estado: "em_comissoes", rotulo: "Em comissões", quantidade: 2, largura: 50 },
      { estado: "arquivada", rotulo: "Arquivada", quantidade: 1, largura: 25 },
      { estado: "x_y", rotulo: "X y", quantidade: 1, largura: 25 },
    ]);
  });

  it("estado desconhecido nunca sai como chave crua de banco", () => {
    const [g] = agruparPorSituacao([m("a", "aguardando_leitura_em_plenario")]);
    expect(g.rotulo).toBe("Aguardando leitura em plenario");
  });

  it("lista vazia → nenhum grupo", () => {
    expect(agruparPorSituacao([])).toEqual([]);
  });
});

const voto = (over: Partial<MeuVotoOut> = {}): MeuVotoOut => ({
  votacaoId: "v1",
  voto: "sim",
  registradoEm: "2026-09-10T15:00:00Z",
  anulada: false,
  portal: "publico",
  materiaTipo: "projeto_lei",
  materiaAno: 2026,
  materiaSequencial: 22,
  materiaEmenta: "Dispõe sobre a poda de árvores",
  ...over,
});

describe("linhaDeVoto", () => {
  it("voto de sessão pública não leva aviso", () => {
    const l = linhaDeVoto(voto());
    expect(l.aviso).toBeNull();
    expect(l.titulo).toBe("Dispõe sobre a poda de árvores");
    expect(l.subtitulo).toBe("PL 022/2026 · 10/09/2026");
    expect(l.votoRotulo).toBe("A favor");
    expect(l.votoClasse).toBe("chip-ok");
  });

  it("sessão fechada ao público: só o vereador vê, e o aviso diz isso", () => {
    expect(linhaDeVoto(voto({ portal: "sessao-fechada" })).aviso).toBe(
      "Sessão fechada ao público — só você vê este voto.",
    );
  });

  it("votação fora de sessão: o portal não publica", () => {
    expect(linhaDeVoto(voto({ portal: "sem-sessao" })).aviso).toMatch(/não aparece no portal/);
  });

  it("votação anulada: o aviso é o da anulação, mesmo em sessão fechada", () => {
    expect(linhaDeVoto(voto({ anulada: true, portal: "sessao-fechada" })).aviso).toBe(
      "Votação anulada — este voto não entra nos números acima.",
    );
  });

  it("sem matéria identificada a linha não some, não repete o texto e não vaza chave de cadastro", () => {
    const l = linhaDeVoto(
      voto({ materiaTipo: null, materiaAno: null, materiaSequencial: null, materiaEmenta: null }),
    );
    expect(l.titulo).toBe("Votação sem proposição identificada");
    expect(l.subtitulo).toBe("10/09/2026");
  });

  it("opção desconhecida sai crua e neutra, nunca lança", () => {
    const l = linhaDeVoto(voto({ voto: "ausente" }));
    expect(l.votoRotulo).toBe("ausente");
    expect(l.votoClasse).toBe("chip-neutro");
  });
});

describe("derivarAtuacao — os votos são os do ator, não os do perfil público", () => {
  const perfil = {
    materias: [],
    materiasTotal: 0,
    normasDeAutoria: 0,
    votos: [],
    votosTotal: 1,
    votosPorOpcao: { sim: 1, nao: 0, abstencao: 0 },
    presenca: { sessoesPresente: 0, sessoesComChamada: 0, janelaDeExercicioConhecida: false, janelaAnteriorAProjecao: false },
    acervoComEloDeAutoriaDesde: "2025-03-04",
    presencaProjetadaDesde: "2026-07-20",
  } as unknown as PerfilVereadorOut;
  const painel = { pareceres: [], pareceresTruncado: false } as unknown as MeuPainelOut;
  const meus = (over: Partial<MeusVotosOut> = {}): MeusVotosOut => ({
    vereadorId: "ver",
    votos: [voto(), voto({ votacaoId: "v2", portal: "sessao-fechada", voto: "nao" })],
    votosTotal: 2,
    votosPorOpcao: { sim: 1, nao: 1, abstencao: 0 },
    ...over,
  });

  it("números e lista saem de MeusVotosOut", () => {
    const v = derivarAtuacao(perfil, painel, meus());
    expect(v.votos).toEqual({ sim: 1, nao: 1, abstencao: 0 });
    expect(v.linhasVotos.map((l) => l.votacaoId)).toEqual(["v1", "v2"]);
    expect(v.votosVazio).toBeNull();
    expect(v.votosTruncamento).toBeNull();
  });

  it("vazio e truncamento vêm do total do ator", () => {
    expect(derivarAtuacao(perfil, painel, meus({ votos: [], votosTotal: 0 })).votosVazio).toMatch(/Ainda não há votos/);
    expect(derivarAtuacao(perfil, painel, meus({ votosTotal: 60 })).votosTruncamento).toBe(
      "Mostrando os 2 votos mais recentes, de 60 no total.",
    );
  });
});
