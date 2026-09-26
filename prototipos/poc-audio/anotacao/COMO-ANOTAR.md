# Como anotar um trecho de sessão (para a PoC de áudio)

A PoC só mede qualidade se houver uma **verdade anotada por uma pessoa** para comparar. São **15 minutos** de uma
sessão, de preferência um trecho com vários oradores, apartes e a Presidência conduzindo. Quem anota precisa
reconhecer as vozes dos vereadores (servidor da secretaria ou alguém da Mesa). Leva cerca de 1 hora.

Ferramenta sugerida: **Audacity** (gratuito). Abra o áudio, selecione cada trecho e use *Editar → Rótulos →
Adicionar rótulo na seleção* (Ctrl+B), escrevendo o nome. No fim: *Arquivo → Exportar → Exportar rótulos*.
Mandar o .txt exportado já basta — a conversão para CSV é nossa.

## Dois arquivos

**1. Quem falou** (`referencia.csv`, modelo em `referencia-modelo.csv`)
- Um trecho por fala contínua: início, fim (em segundos) e o nome de quem fala.
- Aparte ou interrupção: anote como trecho próprio, mesmo que se sobreponha a outro.
- Presidência conduzindo ("Com a palavra o vereador…"): anote como `Presidente` (ou o nome).
- Ignore aplauso, ruído e silêncio — só voz de gente.
- Não precisa ser exato ao décimo de segundo: a avaliação tolera ¼ de segundo nas fronteiras.

**2. Quem estava com a palavra** (`palavra.csv`, modelo em `palavra-modelo.csv`)
- O que a Mesa registraria no O Plenário: quando cada orador **recebeu** e **devolveu** a palavra na tribuna.
- Só os discursos com a palavra concedida — não os apartes nem a condução da Presidência.

Os nomes têm de ser escritos **igual** nos dois arquivos.
