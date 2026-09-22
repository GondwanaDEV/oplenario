// View-model PURO da tela inicial (/inicio) — o ponto de partida que faltava.
//
// O buraco que isto fecha (achado em apresentação guiada, docs/21): depois do login o usuário caía em `/`,
// a capa "front-end em construção", que NÃO é menu. O app interno não tinha home: as telas de escritório
// só se alcançavam pela barra de navegação (que só existe DENTRO delas) e as telas de sessão ao vivo
// (`/sessoes/:id/conduzir|plenario|chamada`) e `/pos-aprovacao/:id` não estão em navegação nenhuma — só
// digitando a URL. Quem apresenta a plataforma ficava colando URL na barra de endereço.
//
// Aqui só se DERIVA (puro, sem IO): quem é a persona, o que está acontecendo com a sessão agora, e para
// onde essa persona deve ir. A resposta a "há sessão agora?" / "qual é a próxima?" vem de
// ./sessao-corrente — a MESMA usada pela home do vereador, para as duas telas nunca divergirem.
//
// Honestidade sobre carregamento (mesma lição do defeito #16): "ainda não sei" NÃO pode virar "não há".
// Por isso `estadoSessoes` entra na derivação e produz as situações `carregando`/`erro` distintas de
// `nenhuma` — a tela nunca afirma "nenhuma sessão" enquanto o fetch está em voo.

import type { SessaoOut } from "./contrato-sessoes.gen";
import type { EstadoSessoes } from "./use-sessoes";
import { proximaSessaoFutura, sessaoAoVivoEm } from "./sessao-corrente";
import { nomeTipoSessao } from "./rotulos-sessao";
import { formatarData } from "./formatar-data";

/** Qual "escritório" a pessoa logada tem. `secretario` vence quando alguém acumula papéis: é a persona com
 * mais superfície, e mandá-la para a área do vereador esconderia o trabalho dela. */
export type Persona = "secretaria" | "vereador" | "sem-area";

export type TomAcao = "primaria" | "neutra";

export interface AcaoInicio {
  rotulo: string;
  href: string;
  tom: TomAcao;
  /** Uma linha do porquê — a home explica o destino em vez de só listar rótulos. */
  descricao?: string;
}

export type SituacaoSessaoInicio = "carregando" | "erro" | "ao-vivo" | "agendada" | "nenhuma";

export interface BlocoSessao {
  situacao: SituacaoSessaoInicio;
  titulo: string;
  detalhe: string;
  /** O id da sessão a que as ações se referem (viva ou próxima); `null` quando não há uma. */
  sessaoId: string | null;
  acoes: AcaoInicio[];
}

export interface InicioVista {
  persona: Persona;
  sessao: BlocoSessao;
  atalhos: AcaoInicio[];
}

export interface EntradaInicio {
  papeis: string[];
  sessoes: SessaoOut[] | null | undefined;
  estadoSessoes: EstadoSessoes;
  agoraIso?: string;
}

function personaDe(papeis: string[]): Persona {
  if (papeis.includes("secretario")) return "secretaria";
  if (papeis.includes("vereador")) return "vereador";
  return "sem-area";
}

/** Rótulo humano da sessão ("2ª sessão ordinária"). Usa `nomeTipoSessao` — o enum do backend
 * (`ordinaria`, `extraordinaria`) sem acento é erro de português visível, e esse rótulo já existe no
 * projeto para isto. Sem inventar dado: só o que SessaoOut traz. */
function rotuloSessao(s: SessaoOut): string {
  return `${s.numeroSequencial}ª sessão ${nomeTipoSessao(s.tipoSessao)}`.trim();
}

const ATALHOS_SECRETARIA: AcaoInicio[] = [
  { rotulo: "Proposições", href: "/proposicoes", tom: "neutra", descricao: "O acervo de matérias da Casa" },
  { rotulo: "Tramitação", href: "/tramitacao", tom: "neutra", descricao: "O quadro da Casa por estágio" },
  { rotulo: "Expediente", href: "/expediente", tom: "neutra", descricao: "Documentos e protocolo geral" },
  { rotulo: "Pauta", href: "/pauta-convocacao", tom: "neutra", descricao: "Pauta e convocação da sessão" },
  { rotulo: "Agendar sessão", href: "/agendar-sessao", tom: "neutra", descricao: "Marcar a próxima sessão" },
  { rotulo: "Vereadores", href: "/cadastros/vereadores", tom: "neutra", descricao: "Cadastro, Mesa e comissões" },
  { rotulo: "Calendário", href: "/calendario", tom: "neutra", descricao: "Sessões e prazos de compliance" },
  { rotulo: "Moderação", href: "/moderacao", tom: "neutra", descricao: "Fila de comentários do portal" },
  { rotulo: "Painéis da Mesa", href: "/paineis/mesa", tom: "neutra", descricao: "Compliance e pendências" },
];

const ATALHOS_VEREADOR: AcaoInicio[] = [
  { rotulo: "Minha home", href: "/vereador", tom: "neutra", descricao: "Pareceres, ciências e sessões" },
  { rotulo: "Votar", href: "/votar", tom: "neutra", descricao: "O cockpit de votação" },
];

const ATALHOS_SEM_AREA: AcaoInicio[] = [
  { rotulo: "Acompanhamentos", href: "/acompanhamentos", tom: "neutra", descricao: "As matérias que você segue" },
];

/** As ações sobre a sessão VIVA, por persona: a Mesa conduz; o vereador vota. O telão serve às duas. */
function acoesAoVivo(persona: Persona, id: string): AcaoInicio[] {
  const telao: AcaoInicio = { rotulo: "Telão do plenário", href: `/sessoes/${id}/plenario`, tom: "neutra", descricao: "Quórum, tribuna e placar ao vivo" };
  if (persona === "secretaria") {
    return [
      { rotulo: "Comando da Mesa", href: `/sessoes/${id}/conduzir`, tom: "primaria", descricao: "Conduzir a sessão, a votação e a tribuna" },
      { rotulo: "Chamada de presença", href: `/sessoes/${id}/chamada`, tom: "neutra", descricao: "Presença, quórum e justificativas" },
      telao,
    ];
  }
  if (persona === "vereador") {
    return [
      { rotulo: "Votar", href: "/votar", tom: "primaria", descricao: "Registrar seu voto nesta sessão" },
      telao,
    ];
  }
  return [telao];
}

function blocoSessao(persona: Persona, entrada: EntradaInicio): BlocoSessao {
  const vazio = { sessaoId: null, acoes: [] as AcaoInicio[] };
  if (entrada.estadoSessoes === "carregando") {
    return { situacao: "carregando", titulo: "Carregando as sessões…", detalhe: "", ...vazio };
  }
  if (entrada.estadoSessoes === "erro") {
    return {
      situacao: "erro",
      titulo: "Não foi possível carregar as sessões",
      detalhe: "Recarregue a página. Se persistir, a plataforma pode estar indisponível.",
      ...vazio,
    };
  }

  const sessoes = entrada.sessoes ?? [];
  const agora = entrada.agoraIso ?? new Date().toISOString();

  const viva = sessaoAoVivoEm(sessoes);
  if (viva) {
    return {
      situacao: "ao-vivo",
      titulo: "A sessão está acontecendo agora",
      detalhe: rotuloSessao(viva),
      sessaoId: viva.id,
      acoes: acoesAoVivo(persona, viva.id),
    };
  }

  const proxima = proximaSessaoFutura(sessoes, agora);
  if (proxima) {
    const acoes: AcaoInicio[] =
      persona === "secretaria"
        ? [
            { rotulo: "Ver a pauta", href: "/pauta-convocacao", tom: "primaria", descricao: "Pauta e convocação da próxima sessão" },
            { rotulo: "Agendar outra sessão", href: "/agendar-sessao", tom: "neutra" },
          ]
        : [{ rotulo: "Minha home", href: "/vereador", tom: "primaria" }];
    return {
      situacao: "agendada",
      titulo: "Próxima sessão marcada",
      // A data é o que a pessoa quer saber aqui; `agendadaPara` é garantido não-nulo por
      // `proximaSessaoFutura` (ela filtra por data futura), mas o guard mantém a função total.
      detalhe: proxima.agendadaPara
        ? `${rotuloSessao(proxima)} · ${formatarData(proxima.agendadaPara)}`
        : rotuloSessao(proxima),
      sessaoId: proxima.id,
      acoes,
    };
  }

  const acoes: AcaoInicio[] =
    persona === "secretaria"
      ? [{ rotulo: "Agendar sessão", href: "/agendar-sessao", tom: "primaria", descricao: "Marcar a próxima sessão da Casa" }]
      : [];
  return {
    situacao: "nenhuma",
    titulo: "Nenhuma sessão agendada",
    detalhe: persona === "secretaria" ? "A Casa não tem sessão marcada no momento." : "Nada acontecendo no plenário agora.",
    sessaoId: null,
    acoes,
  };
}

export function derivarInicio(entrada: EntradaInicio): InicioVista {
  const persona = personaDe(entrada.papeis);
  const atalhos =
    persona === "secretaria" ? ATALHOS_SECRETARIA : persona === "vereador" ? ATALHOS_VEREADOR : ATALHOS_SEM_AREA;
  return { persona, sessao: blocoSessao(persona, entrada), atalhos };
}
