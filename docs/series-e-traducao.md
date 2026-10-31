# Séries no mesmo sítio que Filmes, e o app em Canais, Filmes e Séries

A aba Filmes já abre. A aba que ainda se chama Shows abre vazia. Este texto descreve o que Filmes passou a fazer, para a aba Séries repetir o mesmo, e a tradução: o inglês deixa de dizer Live TV / Shows, e o português do Brasil passa a dizer Canais, Filmes e Séries em todo o app.

Não se copia nada para a tabela `series`. Os episódios desta playlist continuam na tabela `channels`. A aba só muda de `kind` e de qual página lê.

## O que a aba Filmes já faz

A classificação está em `M3uGroups.kindOf`. O canal não muda de sítio: `categoryId` continua a ser o `group-title`, `streamUrl` o mesmo. Só a linha em `categories` muda.

- `FILMES | DRAMA`, `FILMES | AÇÃO` e o resto dos `FILMES |` / `FILME |` ficam `MOVIE`. São a prateleira, cerca de 17 mil entradas nesta playlist.
- `Filmes e Séries` (AMC e as variantes HD/FHD) fica `LIVE`. É uma lista de canais, não de filmes.
- Qualquer grupo com `24H`, `24 H` ou `24 HORAS` fica `LIVE`, mesmo que o nome comece por Filmes ou Séries. `Filmes e Séries 24H`, `Cine 24H`, `Seriados 24H`, `Séries Tokusatsus 24H` são canais contínuos.
- Um nome que começa por `♦️` (U+2666 ou U+25C6) não é categoria. A linha é apagada. `♦️ Drama ✔️` não aparece ao lado de `FILMES | DRAMA`.

`repairLiveCategories` corre no arranque de `ChannelsViewModel` e reaplica esta regra às categorias já gravadas. Não é preciso sincronizar a lista outra vez. A chave de `categories` é `(sourceId, id, kind)`, por isso a correção apaga a linha antiga e insere a nova. Um segundo `upsert` deixava as duas.

O ecrã, em `VodViewModel` e `MoviesScreen`:

- As chips vêm de `movieCategories` (`kind = MOVIE`).
- A grelha não faz `SELECT *`. Se a categoria tem canais (`countInCategories` dessa categoria), é prateleira M3U: `pageChannels`, cursor `(sortIndex, id)`, primeira página com `afterSort = Int.MIN_VALUE` e `afterId = -1`, teto `GUIDE_LIST_CAP` (2 000). O fundo pede a página seguinte. A lista acumulada fica no ecrã.
- O cartaz usa `shownName` e `logoUrl`. O clique é `onPlayChannel` (`Routes.player(channel.id)`), o mesmo do guia. Não abre a ficha Xtream.
- Se a categoria não tem canais, é Xtream: página por `(addedMillis, id)` descendente, o mesmo teto, e o clique abre a ficha.
- A tabela `movies` desta playlist está a zero. Havendo prateleiras M3U, a primeira categoria é escolhida uma vez, para a aba não abrir vazia. Voltar a Todos continua disponível. Todos acima de 2 000 não subscreve a tabela: mostra a contagem e pede uma categoria.
- Lista vazia com a sincronização parada é `EmptyVod`. O spinner fica só enquanto a primeira página não voltou ou a sincronização ainda corre.

`FILMES | DRAMA` (3 025) mostra os primeiros 2 000 e pede o resto no fundo. `FILMES | CINEMA` (21) cabe numa página.

## O que a aba Séries tem de repetir

A aba chama-se Shows no inglês (`nav_shows`, `Tab.SHOWS`). O texto passa a Séries na tradução, mais abaixo. O código da grelha já está ao lado do de Filmes (`seriesShelf`, `SeriesScreen`, `seriesShelfFor`, `loadMoreSeries`). O que falta é ela chegar a desenhar a primeira página. Hoje o utilizador vê o estado vazio.

`SERIES | NOVELAS` (45 139), `SERIES | LEGENDADAS` (40 219), `SERIES | NETFLIX` (32 768) e os outros `SERIES |` / `SÉRIES |` / `SERIE |` já são `kind = SERIES` pela mesma função. São cerca de 295 mil entradas. `Séries Tokusatsus 24H` não entra aqui: tem `24H`, fica em Canais. Os `♦️` (Netflix, Novelas, Globoplay, …) continuam apagados.

O arranque da aba Séries decide se abre uma categoria com `librarySize`, que faz um único `countInCategories` de **todas** as categorias `SERIES` ao mesmo tempo. Em Filmes isso são cerca de 17 mil linhas e a primeira categoria abre. Em Séries são cerca de 295 mil. Esse count está dentro de `runCatching`: se falha, o resultado vira 0. Com a tabela `series` também a 0, a escolha automática não corre, as prateleiras de abertura (género, adicionados há pouco) não têm linhas Xtream, e o ecrã cai em `EmptyVod` — o “não exibe nada”.

Repetir Filmes, sem esse count da biblioteca inteira:

1. Chips só das categorias `SERIES`, como `movieCategories` já faz para `MOVIE`. `SERIES | AMAZON PRIME` aparece. `Séries Tokusatsus 24H` não. `♦️ Netflix` não.
2. Se a tabela `series` está a zero e existe pelo menos uma categoria `SERIES`, escolher a primeira uma vez. Não contar os 295 mil canais para tomar essa decisão. A contagem de uma categoria, quando fizer falta, é dessa categoria, não da aba.
3. A grelha dessa categoria é a mesma de Filmes: `pageChannels` com o cursor `(sortIndex, id)`, 2 000 de cada vez, cartaz `shownName` + `logoUrl`, clique no player ao vivo. Não abrir `VodDetail`. Não ler a tabela `series` para uma prateleira M3U.
4. Todos acima de 2 000 mostra a contagem da categoria escolhida, ou pede uma categoria, e não subscreve os 295 mil. A contagem não é `seriesCount()` — essa tabela está a zero.
5. Vazio, com a sincronização parada e a página já respondida, é `EmptyVod`. Spinner só na primeira página ou com sync a correr.
6. Uma conta Xtream, sem estes canais, continua na página por `addedMillis` e na ficha, como `pagedShows` já faz. Isso não se mexe.

`SERIES | NOVELAS` é a prova: a primeira página aparece, o fundo pede a seguinte, e o guia ao vivo não lista esse grupo.

## Tradução

Dois alvos. Os outros idiomas (`values-es`, `values-fr`, …) ficam como estão.

Os identificadores não mudam: `Tab.SHOWS`, `R.string.nav_shows`, `StreamKind.SERIES`, `vod_no_shows`. Muda o texto que o utilizador lê. A rail, a pesquisa e as definições já usam `nav_live_tv`, `nav_movies` e `nav_shows`, por isso o nome das três abas acompanha a string.

### Inglês (`values/strings.xml`)

A origem do app. As três abas passam a Channels, Movies, Series.

| Chave | Agora | Passa a |
| --- | --- | --- |
| `nav_live_tv` | Live TV | Channels |
| `nav_movies` | Movies | Movies |
| `nav_shows` | Shows | Series |
| `vod_loading_shows` | Loading shows | Loading series |
| `vod_no_shows` | No shows | No series |
| `vod_no_shows_provider` | This provider didn't return any shows. | This provider didn't return any series. |
| `vod_no_shows_add` | Add a provider to see its shows here. | Add a provider to see its series here. |
| `vod_search_hint` | Search movies & shows | Search movies and series |

O resto do ficheiro leva a mesma passagem onde “Live TV” ou “Shows” é o nome destas abas (definições, pesquisa, nota do TMDB). “Programme”, “guide” e “recording” mantêm-se: não são o nome da aba. Placeholders (`%1$s`, `%1$d`) ficam no mesmo sítio e na mesma ordem.

### Português do Brasil (`values-pt-rBR/strings.xml`)

Ficheiro novo, catálogo completo das 474 strings de `values/strings.xml`. Não se edita `values-pt`: esse é português de Portugal (“TV em direto”, “Definições”, “descarregar”) e já cobre 458 chaves. Um telemóvel em pt-BR escolhe `values-pt-rBR` primeiro; se a pasta não existir, cai no de Portugal e a aba continua “TV em direto” em vez de “Canais”.

As três abas:

| Chave | pt-BR |
| --- | --- |
| `nav_live_tv` | Canais |
| `nav_movies` | Filmes |
| `nav_shows` | Séries |

Vocabulário do resto do ficheiro, no mesmo sentido: você, tela, baixar, Configurações, aplicativo, pasta, usuário, arquivo, ativar, padrão. Não usar direto, ecrã, descarregar, definições, aplicação, utilizador, ficheiro, predefinição. “Guia” mantém-se para o EPG. “Gravações” mantém-se.

As 16 chaves que `values-pt` ainda não tem entram neste ficheiro, traduzidas, não copiadas do inglês:

`common_edit`, `exit_title`, `exit_body`, `exit_confirm`, `exit_cancel`, `settings_edit_provider_title`, `recset_pw_show`, `recset_pw_hide`, `about_support_body`, `about_sponsor_github`, `about_donate_paypal`, `rec_watching_live_badge`, `rec_live_warn_title`, `rec_live_warn_body`, `rec_live_warn_watch`, `rec_live_warn_keep`.

Cada secção de `values/strings.xml` tem par no pt-BR, da rail até ao aviso de gravação ao vivo. Aspas e `&amp;` seguem o XML que já está no inglês. Nenhuma string nova sem chave no inglês.

## Como confirmar

- Filmes continua a abrir `FILMES |` e não mostra `Filmes e Séries` nem `Filmes e Séries 24H`.
- Séries abre um `SERIES |` com a primeira página de cartazes. O toque vai para o player ao vivo. `Séries Tokusatsus 24H` fica em Canais. `♦️ Novelas` não aparece.
- Idioma inglês: as abas leem-se Channels, Movies, Series.
- Idioma português (Brasil): Canais, Filmes, Séries, e o resto das definições em pt-BR, não em português de Portugal.
- Um aparelho em português de Portugal continua a ler `values-pt`.
