// ADR-0020 — o contrato de fio dos COMUNICADOS INTERNOS (módulo `comunicacao`) e dos SETORES (`cadastros`), como o FE
// os vê DEPOIS de `camelizarChaves` (o backend fala kebab-case; ver boundary.ts). Escrito à mão, não gerado: o módulo
// nasceu em paralelo com este FE, a partir do MESMO contrato (a tabela "Rotas" da ADR), e os detalhes de forma que o
// backend real ajustar se acertam AQUI — um arquivo só, para não espalhar o acerto pelos hooks e telas. Quando o
// backend publicar o `contrato-comunicacao.gen.ts` (o codegen dos outros módulos), estes tipos dão lugar a ele.
//
// O que é contrato e o que é suposição deste lado:
//   - rotas, métodos, campos e status (201/403/422) vêm da ADR e do brief da fatia;
//   - as RESPOSTAS das escritas de setor (POST/PUT /administracao/setores…) NÃO são lidas: a tela recarrega a lista
//     depois de cada escrita. Assim a forma exata do corpo de resposta não importa para o FE;
//   - `GET /comunicados/caixa` GRAVA a marca `recebido` (Eixo 4 da ADR). O contador do topo usa a MESMA rota
//     (`CAMINHO_CONTAGEM_DA_CAIXA`): o número no topo é a caixa chegando à pessoa. Se o backend quiser um contador que
//     não marque, troca-se só a constante.

/** Por onde o comunicado foi endereçado. `todos` = todos os setores (servidores e administração), sem alvo. */
export type TipoDestino = "pessoa" | "vereador" | "setor" | "comissao" | "todos";

/** O que o comunicado pode linkar no sistema (fatia 2). */
export type TipoObjeto = "sessao" | "proposicao" | "protocolo";

export type ObjetoLigado = { tipo: TipoObjeto; id: string };

export type RefComunicado = { id: string; protocolo: string };

// ---- GET /comunicados/destinos — as opções do formulário ----

export type OpcaoDeGrupo = { id: string; nome: string; membros: number };
export type OpcaoDeVereador = { id: string; nome: string };
export type OpcaoDePessoa = { identidadeId: string; nome: string };

export type DestinosOut = {
  podeEnviarAGrupos: boolean;
  setores: OpcaoDeGrupo[];
  comissoes: OpcaoDeGrupo[];
  vereadores: OpcaoDeVereador[];
  pessoas: OpcaoDePessoa[];
};

// ---- POST /comunicados ----

export type DestinoIn = { tipo: TipoDestino; alvoId: string | null };

export type NovoComunicadoIn = {
  assunto: string;
  corpo: string;
  exigeCiencia: boolean;
  /** ISO-8601 (UTC). Só com `exigeCiencia`. */
  cienciaAte: string | null;
  substituiId: string | null;
  objeto: ObjetoLigado | null;
  destinos: DestinoIn[];
};

/** O corpo do POST no formato do fio (chaves kebab). O texto vai como foi escrito — só as pontas são aparadas. */
export function corpoDoNovoComunicado(e: NovoComunicadoIn) {
  return {
    assunto: e.assunto.trim(),
    corpo: e.corpo.trim(),
    "exige-ciencia": e.exigeCiencia,
    "ciencia-ate": e.exigeCiencia ? e.cienciaAte : null,
    "substitui-id": e.substituiId,
    objeto: e.objeto ? { tipo: e.objeto.tipo, id: e.objeto.id } : null,
    destinos: e.destinos.map((d) => ({ tipo: d.tipo, "alvo-id": d.tipo === "todos" ? null : d.alvoId })),
  };
}

// ---- o comunicado (GET /comunicados/:id e o 201 do POST) ----

export type DestinoOut = { tipo: TipoDestino; alvoId: string | null; alvoNome: string | null };

export type AnexoOut = { id: string; nome: string; tipoMidia: string; bytes: number };

/** As marcas DESTA pessoa. `null` no comunicado = quem lê não é destinatário (remetente, secretaria, admin). */
export type MinhasMarcas = { recebidoEm: string | null; lidoEm: string | null; cienteEm: string | null };

export type ComunicadoOut = {
  id: string;
  protocolo: string;
  assunto: string;
  corpo: string;
  remetente: { identidadeId: string; nome: string };
  enviadoEm: string;
  exigeCiencia: boolean;
  cienciaAte: string | null;
  substitui: RefComunicado | null;
  substituidoPor: RefComunicado | null;
  objeto: ObjetoLigado | null;
  destinos: DestinoOut[];
  anexos: AnexoOut[];
  minhasMarcas: MinhasMarcas | null;
  podeVerLeitura: boolean;
};

export type EnvioOut = { comunicado: ComunicadoOut; destinatarios: number; semAcesso: number };

// ---- GET /comunicados/caixa ----

export type ItemDaCaixaOut = {
  id: string;
  protocolo: string;
  assunto: string;
  remetenteNome: string;
  enviadoEm: string;
  exigeCiencia: boolean;
  cienciaAte: string | null;
  vencido: boolean;
  recebidoEm: string | null;
  lidoEm: string | null;
  cienteEm: string | null;
};

export type CaixaOut = {
  itens: ItemDaCaixaOut[];
  naoLidos: number;
  pendentesCiencia: number;
  proximaCienciaAte: string | null;
};

// ---- POST /comunicados/:id/ciencia ----

export type CienciaOut = { cienteEm: string };

// ---- GET /comunicados/enviados[?escopo=casa] ----

export type ItemEnviadoOut = {
  id: string;
  protocolo: string;
  assunto: string;
  enviadoEm: string;
  remetenteNome: string;
  exigeCiencia: boolean;
  cienciaAte: string | null;
  destinatarios: number;
  recebidos: number;
  lidos: number;
  cientes: number;
  pendentesVencidos: number;
};

export type EnviadosOut = { itens: ItemEnviadoOut[] };

// ---- GET /comunicados/:id/leitura ----

export type TotaisDeLeitura = {
  destinatarios: number;
  recebidos: number;
  lidos: number;
  cientes: number;
  pendentesVencidos: number;
};

export type LinhaDeLeitura = {
  identidadeId: string;
  nome: string;
  /** O caminho por onde a pessoa entrou na lista: "direto", "setor Jurídico", "Comissão de Finanças"… */
  via: string;
  recebidoEm: string | null;
  lidoEm: string | null;
  cienteEm: string | null;
  vencido: boolean;
};

export type LeituraOut = {
  comunicado: { id: string; protocolo: string; assunto: string };
  totais: TotaisDeLeitura;
  linhas: LinhaDeLeitura[];
};

// ---- setores (`cadastros`, papel admin_ente) ----

export type MembroDeSetor = { identidadeId: string; nome: string };
export type SetorOut = { id: string; nome: string; ativo: boolean; membros: MembroDeSetor[] };
export type SetoresOut = { setores: SetorOut[] };

// ---- as rotas (relativas ao proxy same-origin /api/* → backend) ----

const enc = encodeURIComponent;

export const ROTAS_COMUNICACAO = {
  destinos: "/api/comunicados/destinos",
  enviar: "/api/comunicados",
  caixa: "/api/comunicados/caixa",
  enviados: (escopo: "meus" | "casa") => (escopo === "casa" ? "/api/comunicados/enviados?escopo=casa" : "/api/comunicados/enviados"),
  comunicado: (id: string) => `/api/comunicados/${enc(id)}`,
  ciencia: (id: string) => `/api/comunicados/${enc(id)}/ciencia`,
  leitura: (id: string) => `/api/comunicados/${enc(id)}/leitura`,
  anexos: (id: string) => `/api/comunicados/${enc(id)}/anexos`,
  anexo: (id: string, anexoId: string) => `/api/comunicados/${enc(id)}/anexos/${enc(anexoId)}`,
  /** Os avisos do sistema (`paineis`), que a caixa junta aos comunicados na tela (Eixo 7). Rota já existente. */
  avisos: "/api/meu/notificacoes",
  setores: "/api/administracao/setores",
  setor: (id: string) => `/api/administracao/setores/${enc(id)}`,
  membrosDoSetor: (id: string) => `/api/administracao/setores/${enc(id)}/membros`,
} as const;

/** Ver o cabeçalho: a contagem do topo lê a própria caixa (que grava `recebido`). */
export const CAMINHO_CONTAGEM_DA_CAIXA = ROTAS_COMUNICACAO.caixa;

/** O nome do campo do multipart de `POST /comunicados/:id/anexos`. */
export const CAMPO_DO_ANEXO = "arquivo";

/** Os limites da ADR (Banco, `comunicacao.anexo`): até 5 anexos de até 10 MB. O servidor confere de novo. */
export const LIMITE_DE_ANEXOS = 5;
export const TAMANHO_MAXIMO_DO_ANEXO = 10 * 1024 * 1024;

// ---- validação mínima de forma (fail-closed: corpo que não bate vira erro na tela, nunca meio-dado) ----

const ehTexto = (v: unknown) => typeof v === "string";
const ehLista = (v: unknown) => Array.isArray(v);

export const formaValida = {
  destinos: (d: unknown) => {
    const x = d as DestinosOut;
    return !!x && ehLista(x.setores) && ehLista(x.comissoes) && ehLista(x.vereadores) && ehLista(x.pessoas);
  },
  comunicado: (d: unknown) => {
    const x = d as ComunicadoOut;
    return !!x && ehTexto(x.id) && ehTexto(x.protocolo) && ehTexto(x.assunto) && ehLista(x.destinos);
  },
  envio: (d: unknown) => {
    const x = d as EnvioOut;
    return !!x && formaValida.comunicado(x.comunicado);
  },
  caixa: (d: unknown) => {
    const x = d as CaixaOut;
    return !!x && ehLista(x.itens) && typeof x.naoLidos === "number";
  },
  ciencia: (d: unknown) => !!d && ehTexto((d as CienciaOut).cienteEm),
  enviados: (d: unknown) => !!d && ehLista((d as EnviadosOut).itens),
  leitura: (d: unknown) => {
    const x = d as LeituraOut;
    return !!x && !!x.totais && ehLista(x.linhas);
  },
  setores: (d: unknown) => !!d && ehLista((d as SetoresOut).setores),
  avisos: (d: unknown) => {
    const x = d as { notificacoes?: unknown; naoLidas?: unknown };
    return !!x && ehLista(x.notificacoes) && typeof x.naoLidas === "number";
  },
};
