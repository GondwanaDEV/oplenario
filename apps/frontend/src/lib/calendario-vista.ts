// View-model PURO do Calendário institucional (Onda E, fatia 2) — a grade do mês + a agenda lateral.
// Nenhum componente React interpreta SessaoOut / obrigação de compliance direto; tudo passa por aqui
// (mesmo padrão de mesa-vista.ts / notificacoes-vista.ts).
//
// O que entra são as DUAS únicas famílias de evento que a Casa registra hoje:
//   - SESSÃO  — GET /sessoes (SessaoOut.agendadaPara é um INSTANTE, ISO com fuso);
//   - PRAZO   — GET /compliance/painel, bloco `em-aberto` (ObrigacaoEmAbertoOut.venceEm é DATE-ONLY).
// Comissão, audiência pública e recesso NÃO entram: não existem como evento agendável no backend
// (ver o EmBreve da página). Este módulo não tem como fabricá-los, por construção — não há entrada.
//
// FUSO (a armadilha real, com dois lados opostos):
//   - `agendadaPara` é instante: o dia da célula é o dia LOCAL da Casa. `iso.slice(0,10)` poria uma
//     sessão das 23h de 24/06 (= 25/06 02:00Z) no dia 25. Por isso a conversão passa por Intl —
//     COM `timeZone` explícito (ver FUSO_DA_CASA): sem ele o Intl usa o fuso do NAVEGADOR, e um
//     vereador consultando de Lisboa veria a sessão das 23h30 de 24/06 cair no dia 25.
//   - `venceEm` é date-only: `new Date("2026-06-01")` é MEIA-NOITE UTC e, formatado em Fortaleza
//     (UTC−3), volta um dia — o prazo apareceria em 31/05. Por isso date-only é recorte TEXTUAL puro,
//     sem `Date` nenhum (mesma lição de formatarDataSimples em formatar-data.ts).
// A grade em si é aritmética em UTC (Date.UTC + getUTC*), que é fuso-independente por construção.

import { nomeTipoSessao } from "./rotulos-sessao";
import type { SessaoOut } from "./contrato-sessoes.gen";

/** Espelha ObrigacaoEmAbertoOut (apps/backend .../compliance/wire/out/painel.clj) já camelizada.
 *  O codegen Malli→TS ainda não cobre o módulo compliance; quando cobrir, este alias sai daqui.
 *  Mesma convenção do widening local de ComplianceCard em mesa-vista.ts. */
export interface ObrigacaoEmAberto {
  id: string;
  templateChave: string;
  objetoTipo: string;
  objetoId: string;
  venceEm: string;
  estado: string;
}

export type TipoEvento = "sessao" | "prazo";

export interface EventoCalendario {
  /** Prefixado pelo tipo: um id de sessão e um id de obrigação vivem em espaços distintos. */
  id: string;
  tipo: TipoEvento;
  /** Dia LOCAL da Casa, `AAAA-MM-DD`. */
  dia: string;
  /** `14h` / `9h30`; `null` quando o evento é date-only (prazo não tem hora no wire). */
  hora: string | null;
  /** Texto curto da célula da grade. */
  rotulo: string;
  /** Texto completo da agenda lateral. */
  titulo: string;
  /** Linha de apoio (hora, estado, vencimento). */
  meta: string;
  /** O que muda o SIGNIFICADO da linha e por isso precisa aparecer na própria célula da grade, não só
   *  no `aria-label`: "não realizada", "vencida", "suspensa"… `null` no caso neutro (sessão agendada,
   *  prazo pendente). Quem enxerga não pode ficar sabendo menos que o leitor de tela. */
  alerta: string | null;
}

export interface CelulaDia {
  iso: string;
  dia: number;
  /** Nome acessível da célula ("24 de junho de 2026") — a grade ARIA precisa dele: sem isso o leitor de
   *  tela anuncia só o número solto, sem mês, e as células de fora do mês ficam ambíguas. */
  rotuloDia: string;
  foraDoMes: boolean;
  hoje: boolean;
  eventos: EventoCalendario[];
}

export interface Mes {
  ano: number;
  mes: number; // 1..12
}

export interface CalendarioVista {
  titulo: string;
  celulas: CelulaDia[];
  /** As mesmas células em linhas de 7. A grade ARIA (`role="grid"`) exige `role="row"` entre a grade e
   *  as células — sem linhas, um leitor de tela lê 35 células soltas sem noção de semana. */
  semanas: CelulaDia[][];
  proximos: EventoCalendario[];
  /** Quantos eventos futuros o teto de `proximos` deixou de fora. `0` quando não há corte. Existe para
   *  que o card NUNCA corte em silêncio: 6 exibidos com 6 existentes e 6 exibidos com 20 existentes são
   *  indistinguíveis na tela, e o rail é o afordance de leitura rápida da agenda. */
  proximosOcultos: number;
  /** true quando a Casa não tem NENHUM evento registrado (nem no mês exibido, nem em outro) — a
   *  tela diz isso em vez de deixar uma grade vazia parecer erro de carga. */
  vazio: boolean;
}

export const TETO_PROXIMOS = 6;

export const DIAS_DA_SEMANA = ["Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb"] as const;

// ---------------------------------------------------------------- fuso e formatação

/** O fuso da CASA, espelhando `apps/backend .../kernel/tempo.clj` (`ZoneId/of "America/Fortaleza"`) —
 *  o mesmo fuso em que o backend decide vencimento de obrigação e janela de presença. Esta tela se chama
 *  "Agenda da Casa": o dia da célula é o dia civil da Casa, NÃO o do dispositivo de quem consulta.
 *  Sem `timeZone` explícito o `Intl` cai no fuso do navegador, e a sessão das 23h30 de 24/06 em Fortaleza
 *  aparece no dia 25 para quem abre de Lisboa — o vereador falta à sessão.
 *  CARRY (o mesmo do backend): quando houver Casa fora do CE, isto vira atributo do ente, não constante. */
export const FUSO_DA_CASA = "America/Fortaleza";

const PARTES_DIA_LOCAL = new Intl.DateTimeFormat("pt-BR", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  timeZone: FUSO_DA_CASA,
});

const PARTES_HORA_LOCAL = new Intl.DateTimeFormat("pt-BR", {
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
  timeZone: FUSO_DA_CASA,
});

/** Hora/minuto/segundo do relógio da CASA — só serve a `msAteViradaDoDia`. */
const RELOGIO_DA_CASA = new Intl.DateTimeFormat("pt-BR", {
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hourCycle: "h23",
  timeZone: FUSO_DA_CASA,
});

const SO_DATA = /^\d{4}-\d{2}-\d{2}$/;

// Abreviação de mês em pt-BR, literal e sem `Intl`: o Intl exigiria construir um `Date` a partir de um
// date-only, que é exatamente a armadilha de fuso descrita no cabeçalho.
const MESES_CURTOS = ["jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"];

function porTipo(partes: Intl.DateTimeFormatPart[]): Record<string, string> {
  const m: Record<string, string> = {};
  for (const p of partes) m[p.type] = p.value;
  return m;
}

/** Instante ISO -> dia LOCAL `AAAA-MM-DD`. Date-only entra e sai igual (nunca vira `Date`). */
export function diaLocal(iso: string): string | null {
  if (SO_DATA.test(iso)) return iso;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  const p = porTipo(PARTES_DIA_LOCAL.formatToParts(d));
  return `${p.year}-${p.month}-${p.day}`;
}

/** Instante ISO -> hora LOCAL de tela (`14h`, `9h30`). Date-only não tem hora: `null`. */
export function horaLocal(iso: string): string | null {
  if (SO_DATA.test(iso)) return null;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  const p = porTipo(PARTES_HORA_LOCAL.formatToParts(d));
  const h = String(Number(p.hour)); // sem zero à esquerda: "9h30", não "09h30"
  return p.minute === "00" ? `${h}h` : `${h}h${p.minute}`;
}

function diaBr(isoData: string): string {
  const m = SO_DATA.exec(isoData);
  if (!m) return isoData;
  const [ano, mes, dia] = isoData.split("-");
  return `${dia}/${mes}/${ano}`;
}

/** Dia local `AAAA-MM-DD` -> a data grande da agenda lateral (`29` + `jun`). Recorte TEXTUAL puro, pelo
 *  mesmo motivo de `diaBr`: `new Date("2026-01-01")` é meia-noite UTC e, formatado em Fortaleza (UTC-3),
 *  volta para 31/dez/2025 — a agenda mostraria o dia anterior ao do vencimento.
 *  Dia malformado devolve o texto cru (feio, mas verdadeiro) em vez de uma data inventada. */
export function partesDoDia(dia: string): { numero: string; mesCurto: string } {
  if (!SO_DATA.test(dia)) return { numero: dia, mesCurto: "" };
  const [, mes, d] = dia.split("-");
  return { numero: String(Number(d)), mesCurto: MESES_CURTOS[Number(mes) - 1] ?? "" };
}

function capitalizar(s: string): string {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}

// ---------------------------------------------------------------- rótulos de domínio

// Valores da FONTE — o contrato gerado (contrato-sessoes.gen.ts, SessaoOut.estado), que por sua vez sai
// do CHECK de `sessoes.sessao.estado`. Chave desconhecida cai no fallback e aparece crua (feia, mas
// verdadeira) em vez de sumir da tela — mesma regra de rotulos-sessao.ts.
const NOME_ESTADO_SESSAO: Record<string, string> = {
  agendada: "agendada",
  aberta: "em andamento",
  suspensa: "suspensa",
  encerrada: "encerrada",
  nao_realizada: "não realizada",
  arquivada: "arquivada",
};

/** "agendada" é o estado neutro do calendário — dizê-lo em toda linha é ruído. Qualquer OUTRO estado
 *  muda o que a linha significa (uma sessão não realizada não é um compromisso futuro) e é DITO. */
function alertaDaSessao(s: SessaoOut): string | null {
  if (s.estado === "agendada") return null;
  return NOME_ESTADO_SESSAO[s.estado] ?? s.estado;
}

function metaDaSessao(hora: string | null, alerta: string | null): string {
  const partes: string[] = [];
  if (hora) partes.push(hora);
  if (alerta) partes.push(alerta);
  return partes.join(" · ");
}

// ---------------------------------------------------------------- eventos

function eventoDeSessao(s: SessaoOut): EventoCalendario | null {
  if (!s.agendadaPara) return null; // sem data não há onde pôr — e não se inventa uma
  const dia = diaLocal(s.agendadaPara);
  if (!dia) return null;
  const hora = horaLocal(s.agendadaPara);
  const tipo = capitalizar(nomeTipoSessao(s.tipoSessao));
  const alerta = alertaDaSessao(s);
  return {
    id: `sessao:${s.id}`,
    tipo: "sessao",
    dia,
    hora,
    rotulo: `${s.numeroSequencial}ª ${tipo}`,
    titulo: `${s.numeroSequencial}ª Sessão ${tipo}`,
    meta: metaDaSessao(hora, alerta),
    alerta,
  };
}

function eventoDeObrigacao(o: ObrigacaoEmAberto): EventoCalendario | null {
  const dia = diaLocal(o.venceEm);
  if (!dia) return null;
  return {
    id: `prazo:${o.id}`,
    tipo: "prazo",
    dia,
    hora: null,
    // A chave do template é dado de tenant (Invariante 4: regra de compliance é DADO). O front não tem
    // dicionário dela e não vai fabricar um nome bonito — mostra a chave, como o painel da Mesa já faz.
    // O prefixo "Prazo · " existe por DOIS motivos, os dois de tela: (1) na grade do mês o tipo do evento
    // não pode ser só a cor, e "remessa_mensal_sim" sozinho não se identifica como prazo (uma sessão se
    // identifica: "15ª Ordinária"); (2) sem ele o rótulo da célula seria a MESMA string do título da
    // agenda lateral, e o mesmo texto em dois lugares distintos da tela é ambíguo para quem lê e para
    // quem testa. O `titulo` (agenda lateral) segue sendo a chave crua.
    rotulo: `Prazo · ${o.templateChave}`,
    titulo: o.templateChave,
    meta: o.estado === "vencida" ? `vencida em ${diaBr(dia)}` : `vence em ${diaBr(dia)}`,
    // Na grade, `pendente` e `vencida` pintam o MESMO losango telha. Só o alerta separa "prazo em dia"
    // de "prazo estourado" para quem enxerga.
    alerta: o.estado === "vencida" ? "vencida" : null,
  };
}

function ordenar(a: EventoCalendario, b: EventoCalendario): number {
  if (a.dia !== b.dia) return a.dia < b.dia ? -1 : 1;
  // Sem hora (prazo) vai depois dos horários do dia; entre iguais, ordem estável pelo id.
  const ha = a.hora ?? "~";
  const hb = b.hora ?? "~";
  const na = a.hora ? Number(a.hora.split("h")[0]) : Number.MAX_SAFE_INTEGER;
  const nb = b.hora ? Number(b.hora.split("h")[0]) : Number.MAX_SAFE_INTEGER;
  if (na !== nb) return na - nb;
  if (ha !== hb) return ha < hb ? -1 : 1;
  return a.id < b.id ? -1 : 1;
}

// ---------------------------------------------------------------- grade

function iso(ano: number, mes: number, dia: number): string {
  return `${ano}-${String(mes).padStart(2, "0")}-${String(dia).padStart(2, "0")}`;
}

const NOME_MES = new Intl.DateTimeFormat("pt-BR", { month: "long", timeZone: "UTC" });

function rotuloDoDia(d: Date): string {
  return `${d.getUTCDate()} de ${NOME_MES.format(d)} de ${d.getUTCFullYear()}`;
}

export function mesAnterior({ ano, mes }: Mes): Mes {
  return mes === 1 ? { ano: ano - 1, mes: 12 } : { ano, mes: mes - 1 };
}

export function mesSeguinte({ ano, mes }: Mes): Mes {
  return mes === 12 ? { ano: ano + 1, mes: 1 } : { ano, mes: mes + 1 };
}

export function tituloDoMes({ ano, mes }: Mes): string {
  return `${capitalizar(NOME_MES.format(new Date(Date.UTC(ano, mes - 1, 1))))} de ${ano}`;
}

/** O `AAAA-MM-DD` de hoje no fuso da Casa. Impuro por natureza — fica FORA de derivarCalendario, que
 *  recebe `hoje` como parâmetro justamente para poder ser testado. */
export function hojeLocal(agora: Date = new Date()): string {
  const p = porTipo(PARTES_DIA_LOCAL.formatToParts(agora));
  return `${p.year}-${p.month}-${p.day}`;
}

/** Milissegundos daqui até a próxima meia-noite DA CASA (nunca 0: na virada exata devolve um dia cheio).
 *  Uma tela institucional fica aberta o dia inteiro num monitor de secretaria — atravessar a meia-noite é
 *  o caso normal, não o excepcional. Sem revalidar `hoje`, a pílula "hoje" e o `aria-current="date"`
 *  continuam apontando o dia de ONTEM, e o card "Próximos" segue listando a sessão de ontem como
 *  compromisso futuro. Fortaleza não tem horário de verão, então a aritmética de relógio basta. */
export function msAteViradaDoDia(agora: Date = new Date()): number {
  const p = porTipo(RELOGIO_DA_CASA.formatToParts(agora));
  const decorrido = Number(p.hour) * 3_600_000 + Number(p.minute) * 60_000 + Number(p.second) * 1000;
  const DIA = 86_400_000;
  return DIA - decorrido;
}

export function derivarCalendario({
  ano,
  mes,
  hoje,
  sessoes,
  obrigacoes,
}: Mes & {
  hoje: string;
  sessoes: SessaoOut[] | null;
  obrigacoes: ObrigacaoEmAberto[] | null;
}): CalendarioVista {
  const eventos = [
    ...(sessoes ?? []).map(eventoDeSessao),
    ...(obrigacoes ?? []).map(eventoDeObrigacao),
  ]
    .filter((e): e is EventoCalendario => e !== null)
    .sort(ordenar);

  const porDia = new Map<string, EventoCalendario[]>();
  for (const e of eventos) {
    const lista = porDia.get(e.dia);
    if (lista) lista.push(e);
    else porDia.set(e.dia, [e]);
  }

  // Aritmética em UTC: `Date.UTC` + `getUTC*` não passa perto de fuso, então a grade é a mesma em
  // qualquer máquina. O deslocamento inicial põe a primeira célula no domingo <= dia 1.
  const primeiro = new Date(Date.UTC(ano, mes - 1, 1));
  const deslocamento = primeiro.getUTCDay(); // 0 = domingo
  const diasNoMes = new Date(Date.UTC(ano, mes, 0)).getUTCDate();
  const quantidade = Math.ceil((deslocamento + diasNoMes) / 7) * 7;

  const celulas: CelulaDia[] = [];
  for (let i = 0; i < quantidade; i += 1) {
    const d = new Date(Date.UTC(ano, mes - 1, 1 - deslocamento + i));
    const chave = iso(d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate());
    celulas.push({
      iso: chave,
      dia: d.getUTCDate(),
      rotuloDia: rotuloDoDia(d),
      foraDoMes: d.getUTCMonth() + 1 !== mes,
      hoje: chave === hoje,
      eventos: porDia.get(chave) ?? [],
    });
  }

  const futuros = eventos.filter((e) => e.dia >= hoje);
  const proximos = futuros.slice(0, TETO_PROXIMOS);

  const semanas: CelulaDia[][] = [];
  for (let i = 0; i < celulas.length; i += 7) semanas.push(celulas.slice(i, i + 7));

  return {
    titulo: tituloDoMes({ ano, mes }),
    celulas,
    semanas,
    proximos,
    proximosOcultos: futuros.length - proximos.length,
    vazio: eventos.length === 0,
  };
}
