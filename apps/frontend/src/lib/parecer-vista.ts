// View-model puro do editor de parecer de comissão (Onda B Slice 5) — traduz ParecerEditorOut (wire,
// camelizado) pro que o balcão (produto/design-system/o-plenario/telas/parecer.html) mostra. Reusa
// PARECER_APROVADOS/PARECER_ARQUIVADOS de ficha-materia-vista.ts (os 4 desfechos terminais cravados em
// código, eixo F `estados-parecer-terminais`) — mesmo conjunto, nunca duplicado.
//
// `votoRelator` é vocabulário regimental ABERTO no backend (models/parecer.clj: :string sem enum,
// §22.4.4) — a UI oferece só as 3 opções fixas do mockup como CONVENÇÃO de valor enviado
// ("favoravel_com_emendas" | "favoravel" | "contrario"), na MESMA ordem visual da tela-fonte (cartão
// "ve" primeiro, "vf" segundo, "vc" terceiro). Qualquer voto fora desse conjunto (import de outro
// cliente, dado legado) degrada fail-closed pro rótulo cru — nunca inventa, nunca lança.
//
// `comissaoId`/`relatorId` NÃO têm resolução id→nome no backend (mesmo carry documentado do
// vereador-id→nome no F2/FE) — `derivarRelatoria` nunca inventa um nome. O relator sempre foi um rótulo
// honesto sem nome ("Relator designado") ou nulo (omite a linha) quando não há relator-id; a comissão
// ERA exposta como o id cru, o que punha um UUID na tela do parecer (defeito #11 do ledger de prontidão,
// `MATA`) — agora passa pelo mesmo tratamento, via `rotularComissao` (ver comissao-vista.ts para por que
// o nome não existe do lado de cá). O view-model NÃO devolve mais o id: o que não sai daqui não vaza.
//
// Não há prazo/vencimento plumbado pra parecer nesta fatia (o motor de compliance de prazo, §22.7.7, não
// está ligado a pareceres ainda) — este view-model DELIBERADAMENTE não deriva nada de "vence em X dias";
// o componente que consome omite o chip de prazo do mockup por inteiro.

import { nomeDeComissao } from "./comissao-vista";
import { PARECER_APROVADOS, PARECER_ARQUIVADOS } from "./ficha-materia-vista";
import { formatarNumeroProposicao } from "./proposicoes-vista";
import type { ParecerEditorOut } from "./contrato-legislativo.gen";

export type VotoValor = "favoravel_com_emendas" | "favoravel" | "contrario";

export type VotoOpcao = { valor: VotoValor; rotulo: string; classe: "ve" | "vf" | "vc" };

// Ordem = a ordem visual do mockup (DOM: .voto.ve primeiro/checked por padrão na tela-fonte, .voto.vf
// segundo, .voto.vc terceiro) — não a ordem alfabética nem a ordem do union type do backend.
export const VOTO_OPCOES: VotoOpcao[] = [
  { valor: "favoravel_com_emendas", rotulo: "Favorável com emendas", classe: "ve" },
  { valor: "favoravel", rotulo: "Favorável", classe: "vf" },
  { valor: "contrario", rotulo: "Contrário", classe: "vc" },
];

const VOTO_ROTULO_POR_VALOR: Record<string, string> = Object.fromEntries(
  VOTO_OPCOES.map((o) => [o.valor, o.rotulo]),
);

export function rotularVoto(voto: string | null | undefined): string {
  if (!voto) return "Sem voto registrado";
  return VOTO_ROTULO_POR_VALOR[voto] ?? voto; // fail-closed: fora das 3 opções -> valor cru, nunca inventa.
}

const PARECER_ROTULO_POR_ESTADO: Record<string, string> = {
  aprovado: "Aprovado",
  rejeitado: "Rejeitado",
  prejudicado: "Prejudicado",
  prazo_vencido: "Prazo vencido",
};

export function rotularEstadoParecer(estado: string): string {
  return PARECER_ROTULO_POR_ESTADO[estado] ?? estado; // não-terminal (template-driven) -> valor cru.
}

export function parecerEhTerminal(estado: string): boolean {
  return PARECER_APROVADOS.has(estado) || PARECER_ARQUIVADOS.has(estado);
}

export type RelatoriaVista = {
  comissaoNome: string | null; // null = omitir a linha; NUNCA o id (defeito #11) — ver comissao-vista.ts
  relatorRotulo: string | null; // null = omitir a linha (sem resolução id->nome ainda, carry F2/FE)
};

export function derivarRelatoria(parecer: ParecerEditorOut): RelatoriaVista {
  return {
    // O argumento é o NOME da comissão, que o wire ainda não traz. Fica explícito assim (e não como um
    // `null` solto) porque é este o ponto exato que muda no dia em que o backend servir o nome.
    comissaoNome: nomeDeComissao(undefined),
    relatorRotulo: parecer.relatorId ? "Relator designado" : null,
  };
}

export type MateriaVista = { numero: string; ementa: string };

// objeto vem nulo do wire quando objeto-tipo != "proposicao" (YAGNI desta fatia, sempre proposição) OU,
// defensivamente, se o backend não conseguiu casar o objeto — nunca inventa número/ementa nesse caso.
export function derivarMateria(parecer: ParecerEditorOut): MateriaVista | null {
  if (!parecer.objeto) return null;
  return {
    numero: formatarNumeroProposicao(parecer.objeto.tipo, parecer.objeto.sequencial, parecer.objeto.ano),
    ementa: parecer.objeto.ementa,
  };
}
