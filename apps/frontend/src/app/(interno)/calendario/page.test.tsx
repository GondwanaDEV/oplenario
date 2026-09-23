import { describe, expect, it, vi, afterEach, beforeEach } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import PaginaCalendario from "./page";

// `papeisAtual` e' mutavel para um teste poder trocar o papel ANTES do render (mesmo precedente de
// cadastros/vereadores/page.test.tsx com `buscaParamsAtual`). O mock precisa exportar usePapeis porque a
// pagina resolve a permissao dos PRAZOS pelo papel antes de buscar (GET /compliance/painel e'
// `secretario`-only) — sem isto o mock parcial derruba o render inteiro.
const { papeisAtual } = vi.hoisted(() => ({
  papeisAtual: { papeis: ["secretario"] as string[], estado: "pronto" as "carregando" | "pronto" | "erro" },
}));
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ token: "tok" }),
  usePapeis: () => papeisAtual,
}));
vi.mock("@/lib/tema", () => ({ useTema: () => ({ tema: "claro", alternar: () => {} }) }));

const sessoesFake = {
  sessoes: [
    {
      id: "s1",
      "sessao-legislativa-id": "sl-1",
      "tipo-sessao": "ordinaria",
      "numero-sequencial": 15,
      estado: "agendada",
      modalidade: "presencial",
      delibera: true,
      "transmite-publica": true,
      "gera-ata-regimental": true,
      "permite-voto-secreto": false,
      "permite-modalidade-remota": false,
      "agendada-para": "2026-06-24T17:00:00Z",
      "aberta-em": null,
      "encerrada-em": null,
      "motivo-nao-realizada": null,
      "lock-version": 1,
    },
  ],
};

const sessaoCancelada = {
  ...sessoesFake.sessoes[0],
  id: "s2",
  "numero-sequencial": 16,
  estado: "nao_realizada",
  "agendada-para": "2026-06-26T17:00:00Z",
  "motivo-nao-realizada": "falta de quórum",
};

const painelFake = {
  resumo: { pendente: 1, cumprida: 0, vencida: 0, dispensada: 0, cancelada: 0 },
  "em-aberto": [
    {
      id: "o1",
      "template-chave": "remessa_mensal_sim",
      "objeto-tipo": "ente",
      "objeto-id": "e-1",
      "vence-em": "2026-06-29",
      estado: "pendente",
    },
  ],
  "remessas-recentes": [],
};

function mockarRotas(resposta: (path: string) => { ok: boolean; body?: unknown }) {
  global.fetch = vi.fn(async (path: string) => {
    const r = resposta(String(path));
    return { ok: r.ok, status: r.ok ? 200 : 403, json: async () => r.body } as Response;
  }) as unknown as typeof fetch;
}

const tudoOk = (p: string) =>
  p === "/api/sessoes" ? { ok: true, body: sessoesFake } : { ok: true, body: painelFake };

// A grade (com as células vazias) é renderizada ANTES de a busca responder — então "a grade existe" ou
// "a célula existe" não provam que os dados chegaram, e `findByRole("gridcell")` resolve na hora. Sob
// carga (runner de CI), a asserção sobre o CONTEÚDO da célula corria contra a célula vazia (issue #13).
// O sinal certo é o que a própria página dá: o "Carregando a agenda…" some quando as fontes responderam.
async function esperarAgendaCarregada() {
  await waitFor(() => expect(screen.queryByText("Carregando a agenda…")).toBeNull());
}

describe("PaginaCalendario", () => {
  beforeEach(() => {
    // `shouldAdvanceTime` mantém o waitFor do Testing Library funcionando com relógio falso. Sem cravar
    // a data, o mês inicial seria o do runner e toda asserção de célula viraria loteria.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date("2026-06-22T15:00:00Z")); // 22/06/2026, 12h em Fortaleza
    // o papel e' mutavel (ver `papeisAtual`); repor o default evita que a mutacao de um teste vaze
    papeisAtual.papeis = ["secretario"];
    papeisAtual.estado = "pronto";
  });

  afterEach(() => {
    vi.useRealTimers();
    cleanup();
    vi.restoreAllMocks();
  });

  it("abre no mês de hoje e põe a sessão e o prazo nos seus dias", async () => {
    mockarRotas(tudoOk);
    render(<PaginaCalendario />);

    expect(screen.getByRole("heading", { level: 1, name: "Agenda da Casa" })).toBeDefined();
    await waitFor(() => expect(screen.getByRole("grid", { name: "Junho de 2026" })).toBeDefined());
    await esperarAgendaCarregada();

    const celula24 = await screen.findByRole("gridcell", { name: /24 de junho/i });
    expect(celula24.textContent).toContain("15ª Ordinária");
    const celula29 = screen.getByRole("gridcell", { name: /29 de junho/i });
    expect(celula29.textContent).toContain("remessa_mensal_sim");
    // O dia 23 não tem evento nenhum — a célula não pode ganhar conteúdo fabricado.
    expect(screen.getByRole("gridcell", { name: /23 de junho/i }).textContent).toBe("23");
  });

  it("o tipo do evento é PALAVRA, não só cor", async () => {
    mockarRotas(tudoOk);
    render(<PaginaCalendario />);
    await waitFor(() => expect(screen.getByLabelText(/^Sessão: 15ª Sessão Ordinária/)).toBeDefined());
    expect(screen.getByLabelText(/^Prazo: remessa_mensal_sim/)).toBeDefined();
  });

  it("a agenda lateral lista os próximos com título completo", async () => {
    mockarRotas(tudoOk);
    render(<PaginaCalendario />);
    expect(screen.getByRole("heading", { level: 2, name: "Próximos" })).toBeDefined();
    await waitFor(() => expect(screen.getByText("15ª Sessão Ordinária")).toBeDefined());
  });

  it("navegar de mês troca a grade e não arrasta o evento de junho", async () => {
    mockarRotas(tudoOk);
    render(<PaginaCalendario />);
    await waitFor(() => expect(screen.getByRole("grid", { name: "Junho de 2026" })).toBeDefined());
    await esperarAgendaCarregada();

    fireEvent.click(screen.getByRole("button", { name: "Próximo mês" }));
    expect(screen.getByRole("grid", { name: "Julho de 2026" })).toBeDefined();
    expect(screen.queryByRole("gridcell", { name: /24 de junho/i })).toBeNull();
    // O evento sai da GRADE, mas a agenda lateral é "o que vem a seguir", não "o que há neste mês".
    expect(screen.getByText("15ª Sessão Ordinária")).toBeDefined();

    fireEvent.click(screen.getByRole("button", { name: "Mês anterior" }));
    fireEvent.click(screen.getByRole("button", { name: "Mês anterior" }));
    expect(screen.getByRole("grid", { name: "Maio de 2026" })).toBeDefined();
  });

  it("comissão, audiência pública e recesso saem como EmBreve com motivo concreto — nunca como evento", async () => {
    mockarRotas(tudoOk);
    render(<PaginaCalendario />);
    await waitFor(() => expect(screen.getByRole("grid", { name: "Junho de 2026" })).toBeDefined());
    await esperarAgendaCarregada();

    const emBreve = screen.getAllByRole("status").map((n) => n.textContent ?? "").join(" ");
    expect(emBreve).toMatch(/comissão/i);
    expect(emBreve).toMatch(/audiência pública/i);
    expect(emBreve).toMatch(/recesso/i);
    // motivo concreto, não "em construção": tem de dizer que a Casa não REGISTRA esses eventos.
    expect(emBreve).toMatch(/não (existe|há|registra)/i);

    // E nada disso pode ter virado célula pintada. `/^Comissão:/` e `/^Audiência:/` não serviam: o union
    // de `EventoCalendario["tipo"]` é `"sessao"|"prazo"` e `NOME_TIPO` só tem essas duas chaves — não há
    // entrada no sistema capaz de gerar esses rótulos, então as duas asserções passavam sempre, inclusive
    // sobre uma grade que fabricasse comissão com OUTRO rótulo ("Reunião: …"). A asserção que reprova é
    // sobre o que a grade DE FATO contém: todo item da grade se anuncia como Sessão ou Prazo.
    const itens = within(screen.getByRole("grid")).getAllByRole("listitem");
    expect(itens).toHaveLength(2); // a sessão de 24/06 e o prazo de 29/06 — nada mais
    for (const li of itens) {
      expect(li.getAttribute("aria-label")).toMatch(/^(Sessão|Prazo): /);
    }
  });

  it("403 no painel de compliance: as sessões continuam e a tela DIZ que os prazos não vieram", async () => {
    mockarRotas((p) => (p === "/api/sessoes" ? { ok: true, body: sessoesFake } : { ok: false }));
    render(<PaginaCalendario />);
    await waitFor(() => expect(screen.getByText(/prazos de compliance não puderam ser carregados/i)).toBeDefined());
    expect(screen.getByText("15ª Sessão Ordinária")).toBeDefined();
    // não pode afirmar ausência quando UMA das fontes falhou. A asserção anterior era `/nenhum prazo/i` —
    // string que a página não renderiza em estado NENHUM, logo uma guarda que não podia reprovar.
    expect(screen.queryByText(/Nada agendado/i)).toBeNull();
  });

  // --- permissao dos PRAZOS resolvida pelo PAPEL (nao por 403 na cara do usuario) ---
  // A pagina decide ANTES de buscar: GET /compliance/painel e' `secretario`-only, e mandar um vereador
  // bater na porta so' para tomar 403 poluiria o console dele. Nenhum teste cobria esses dois ramos.

  it("sem o papel secretario, o painel de compliance NAO e' sequer buscado — e a tela diz que os prazos não vieram", async () => {
    papeisAtual.papeis = ["vereador"];
    mockarRotas(tudoOk);
    render(<PaginaCalendario />);

    await waitFor(() => expect(screen.getByText(/prazos de compliance não puderam ser carregados/i)).toBeDefined());
    // o prazo falha na hora (bloqueado pelo papel), mas as sessões ainda podem estar a caminho
    await esperarAgendaCarregada();
    // as sessões (que ele PODE ver) seguem normais
    expect(screen.getByText("15ª Sessão Ordinária")).toBeDefined();
    // e a porta nunca foi batida: é isto que distingue "resolvido pelo papel" de "tomou 403"
    const caminhos = (global.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls.map((c) => String(c[0]));
    expect(caminhos).toContain("/api/sessoes");
    expect(caminhos.some((c) => c.includes("/compliance/painel"))).toBe(false);
  });

  it("enquanto o papel carrega, a tela NAO afirma que os prazos falharam (nem os busca ainda)", async () => {
    papeisAtual.estado = "carregando";
    papeisAtual.papeis = [];
    mockarRotas(tudoOk);
    render(<PaginaCalendario />);

    await waitFor(() => expect(screen.getByRole("grid", { name: "Junho de 2026" })).toBeDefined());
    // "ainda não sei" não pode virar "falhou" — a mesma disciplina do defeito #16
    expect(screen.queryByText(/prazos de compliance não puderam ser carregados/i)).toBeNull();
    const caminhos = (global.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls.map((c) => String(c[0]));
    expect(caminhos.some((c) => c.includes("/compliance/painel"))).toBe(false);
  });

  it("erro nas sessões é dito, e a tela não afirma que nada está agendado", async () => {
    mockarRotas((p) => (p === "/api/sessoes" ? { ok: false } : { ok: true, body: painelFake }));
    render(<PaginaCalendario />);
    await waitFor(() => expect(screen.getByText(/sessões não puderam ser carregadas/i)).toBeDefined());
    expect(screen.queryByText(/Nada agendado/i)).toBeNull();
    expect(screen.getByText("remessa_mensal_sim")).toBeDefined();
  });

  it("Casa sem nada agendado: vazio honesto, e só quando as DUAS fontes responderam", async () => {
    mockarRotas((p) =>
      p === "/api/sessoes"
        ? { ok: true, body: { sessoes: [] } }
        : { ok: true, body: { resumo: {}, "em-aberto": [], "remessas-recentes": [] } },
    );
    render(<PaginaCalendario />);
    await waitFor(() => expect(screen.getByText(/Nada agendado/i)).toBeDefined());
    // "nada adiante" e "a Casa não registrou nada" são coisas distintas — a segunda é o campo `vazio`,
    // que existia no view-model, era testado lá, e NÃO era consumido por esta página.
    expect(screen.getByText(/nenhuma sessão nem prazo registrado/i)).toBeDefined();
  });

  it("uma fonte em erro E nada adiante: a tela NÃO afirma que não há nada agendado", async () => {
    // O único cenário que exercita a guarda `ambasProntas`. Nos dois testes de erro acima a outra fonte
    // devolve um evento futuro, então `proximos.length === 0` já é falso e a guarda não é atravessada:
    // removê-la da página mantinha tudo verde. Aqui, um 403 no painel (papel sem `secretario`) numa
    // semana SEM sessão futura faria a tela afirmar ausência de prazo do TCE que ela não conseguiu ler.
    mockarRotas((p) => (p === "/api/sessoes" ? { ok: true, body: { sessoes: [] } } : { ok: false }));
    render(<PaginaCalendario />);
    await waitFor(() => expect(screen.getByText(/prazos de compliance não puderam ser carregados/i)).toBeDefined());
    expect(screen.queryByText(/Nada agendado/i)).toBeNull();
    expect(screen.queryByText(/nenhuma sessão nem prazo registrado/i)).toBeNull();
  });

  it("estado da sessão aparece na CÉLULA, não só no aria-label", async () => {
    // Uma sessão `nao_realizada` pintava o mesmo ● jade com o mesmo "16ª Ordinária" de uma agendada:
    // quem enxerga ficava sabendo menos que o leitor de tela.
    mockarRotas((p) =>
      p === "/api/sessoes"
        ? { ok: true, body: { sessoes: [sessoesFake.sessoes[0], sessaoCancelada] } }
        : { ok: true, body: painelFake },
    );
    render(<PaginaCalendario />);
    await esperarAgendaCarregada();
    const celula26 = await screen.findByRole("gridcell", { name: /26 de junho/i });
    expect(celula26.textContent).toContain("16ª Ordinária");
    expect(celula26.textContent).toMatch(/não realizada/i);
    // e a sessão agendada não ganha tarja nenhuma (o estado neutro não vira ruído)
    const celula24 = screen.getByRole("gridcell", { name: /24 de junho/i });
    expect(celula24.textContent).toContain("15ª Ordinária");
    expect(celula24.textContent).not.toMatch(/não realizada/i);
    // o prazo `pendente` também não pode ser pintado como vencido
    expect(screen.getByRole("gridcell", { name: /29 de junho/i }).textContent).not.toMatch(/vencida/i);
  });

  it("a lista de prazos cortada pelo backend é DITA na tela, não engolida", async () => {
    // O CRÍTICO da fatia: `GET /compliance/painel` corta `em-aberto` em 100 sem sinalizar, e a Casa com
    // backlog perde justamente os prazos FUTUROS (as vencidas ocupam os primeiros slots). Sem este aviso,
    // um mês sem nenhum losango é indistinguível de uma Casa em dia com o TCE.
    // `em-aberto-total` é o campo AUTORITATIVO server-side (fatia "painel não mente") — o `resumo` abaixo
    // é só contexto realista da Casa, não o sinal de corte; se fosse lido como o sinal antigo (soma
    // pendente+vencida = 140, igual ao total real aqui), o teste ainda passaria por coincidência, então o
    // total vem explícito para provar que é ELE que a tela lê.
    mockarRotas((p) =>
      p === "/api/sessoes"
        ? { ok: true, body: sessoesFake }
        : {
            ok: true,
            body: {
              resumo: { pendente: 40, cumprida: 0, vencida: 100, dispensada: 0, cancelada: 0 },
              "em-aberto": painelFake["em-aberto"],
              "em-aberto-total": 140,
              "remessas-recentes": [],
            },
          },
    );
    render(<PaginaCalendario />);
    // o número vem dentro de um <b>; o papel de anúncio está no <p> que o contém
    const forte = await screen.findByText(/cortada em/i);
    expect(forte.textContent).toMatch(/1 de 140/);
    const aviso = forte.closest('[role="status"]');
    expect(aviso).not.toBeNull();
    expect(aviso!.textContent).toMatch(/pode estar sem prazos que existem/i);
  });

  it("o teto de 6 da agenda lateral é ANUNCIADO, não silencioso", async () => {
    // 6 exibidos de 6 e 6 exibidos de 9 são indistinguíveis na tela sem esta linha, e o rail é o
    // afordance de leitura rápida da agenda — o servidor conclui que a Casa tem 6 compromissos à frente.
    const muitas = Array.from({ length: 9 }, (_, i) => ({
      ...sessoesFake.sessoes[0],
      id: `m${i}`,
      "numero-sequencial": 20 + i,
      "agendada-para": `2026-07-${String(i + 1).padStart(2, "0")}T17:00:00Z`,
    }));
    mockarRotas((p) =>
      p === "/api/sessoes"
        ? { ok: true, body: { sessoes: muitas } }
        : { ok: true, body: { resumo: {}, "em-aberto": [], "remessas-recentes": [] } },
    );
    render(<PaginaCalendario />);
    // Âncora primeiro (o rail já renderizou), asserção depois: um `waitFor` sobre a própria linha do corte
    // faz a ausência dela virar TIMEOUT de 5s em vez de asserção — o relógio acabando, não o defeito falando.
    await screen.findByText("20ª Sessão Ordinária");
    expect(screen.getByText("+3 mais adiante")).toBeDefined();
    expect(screen.queryByText(/Nada agendado/i)).toBeNull();
  });

  it("com o painel íntegro, nenhum aviso de corte aparece", async () => {
    mockarRotas(tudoOk);
    render(<PaginaCalendario />);
    await waitFor(() => expect(screen.getByRole("grid", { name: "Junho de 2026" })).toBeDefined());
    expect(screen.queryByText(/cortada em/i)).toBeNull();
  });

  it("a tela não afirma um LOCAL que o dado não sustenta ('14h · Plenário' era a maquete)", async () => {
    // A guarda morava em calendario-vista.test.ts, camada que o defeito não atravessa: `metaDaSessao`
    // compõe hora+estado e não tem entrada capaz de produzir "Plenário". O defeito plausível é o JSX
    // escrever o local estático do design. `SessaoOut` não tem campo de local (adapters/out/sessao.clj).
    mockarRotas(tudoOk);
    render(<PaginaCalendario />);
    await waitFor(() => expect(screen.getByText("15ª Sessão Ordinária")).toBeDefined());
    expect(screen.getByRole("grid").textContent).not.toMatch(/plen[áa]rio/i);
    const rail = screen.getByRole("heading", { level: 2, name: "Próximos" }).closest(".rcard")!;
    expect(rail.textContent).not.toMatch(/plen[áa]rio/i);
  });

  it("atravessar a meia-noite move o 'hoje' sozinho, sem recarregar a página", async () => {
    // Uma tela de secretaria fica aberta a noite toda. Com `hoje` congelado num `useMemo(..., [])`, às 9h
    // da manhã seguinte a pílula e o `aria-current="date"` ainda apontam ontem, e "Próximos" ainda lista
    // a sessão de ontem como compromisso futuro.
    mockarRotas(tudoOk);
    render(<PaginaCalendario />);
    await waitFor(() => expect(screen.getByRole("grid", { name: "Junho de 2026" })).toBeDefined());
    expect(screen.getByRole("gridcell", { name: /22 de junho/i }).getAttribute("aria-current")).toBe("date");
    expect(screen.getByRole("gridcell", { name: /23 de junho/i }).getAttribute("aria-current")).toBeNull();

    // são 12h em Fortaleza: 12h até a virada do dia DA CASA (+ a margem de 1s do agendamento)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(12 * 3_600_000 + 2_000);
    });

    expect(screen.getByRole("gridcell", { name: /23 de junho/i }).getAttribute("aria-current")).toBe("date");
    expect(screen.getByRole("gridcell", { name: /22 de junho/i }).getAttribute("aria-current")).toBeNull();
  });
});
