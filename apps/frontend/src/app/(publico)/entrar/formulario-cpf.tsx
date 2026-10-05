// ADR-0025 — o formulário da entrada pelo CPF, comum a /entrar e /entrar/[ente].
//
// HTML puro (sem JS): posta para /api/auth/entrar, que pergunta ao backend em quais Câmaras a pessoa tem acesso e a
// leva direto à senha da Câmara dela. O CPF vai no CORPO do POST — nunca na URL. `ente` (o link da Câmara) e
// `redirect` (o destino pedido pelo middleware) vão escondidos; o BFF confere os dois de novo.

export function FormularioCpf({ ente, redirect }: { ente?: string; redirect?: string }) {
  return (
    <form className="entrar-form" method="post" action="/api/auth/entrar">
      {ente && <input type="hidden" name="ente" value={ente} />}
      {redirect && <input type="hidden" name="redirect" value={redirect} />}
      <div className="entrar-campo">
        <label htmlFor="cpf">CPF</label>
        <input
          id="cpf"
          name="cpf"
          type="text"
          inputMode="numeric"
          autoComplete="username"
          placeholder="000.000.000-00"
          maxLength={14}
          required
          autoFocus
        />
      </div>
      <button className="btn btn-primaria entrar-acao" type="submit">
        Continuar
      </button>
      <p className="entrar-passo">A senha vem no próximo passo.</p>
    </form>
  );
}
