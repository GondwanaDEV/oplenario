import Link from "next/link";

// Home provisória do track FE: a 1ª fatia portada é o painel do plenário ao vivo. As demais telas
// (login, editor, portal) entram por fatia vertical conforme as rotas do backend chegam (docs/11 Track FE).
export default function Home() {
  return (
    <main className="envelope" style={{ maxWidth: 640, padding: "4rem 1.5rem" }}>
      <p className="eyebrow">O Plenário</p>
      <h1 style={{ fontFamily: "var(--display)", fontSize: "var(--t-40)", lineHeight: 1.1, margin: "0.4rem 0 1rem", color: "var(--texto)" }}>
        Onde a câmara acontece.
      </h1>
      <p style={{ color: "var(--texto-2)", maxWidth: "46ch" }}>
        Front-end em construção (track FE). A primeira fatia portada é o <b>painel do plenário ao vivo</b>,
        que abre pelo link específico da sua Câmara — não há uma sessão de demonstração pública nesta origem.
      </p>
      <p style={{ display: "flex", gap: "0.75rem", flexWrap: "wrap", marginTop: "1.5rem" }}>
        {/* Antes o CTA era "Abrir painel de exemplo" -> /sessoes/exemplo/plenario, mas o tenant "exemplo" não
            resolve: caía no card "Acesse pela URL da sua Câmara". Um call-to-action que não demonstra nada é
            promessa vazia (achado do teste exploratório). Agora aponta para páginas públicas REAIS: entrar
            (login por Câmara) e o status da plataforma. */}
        <Link className="btn btn-primaria" href="/entrar">
          Entrar na sua Câmara
        </Link>
        <Link className="btn" href="/status">
          Status da plataforma
        </Link>
      </p>
    </main>
  );
}
