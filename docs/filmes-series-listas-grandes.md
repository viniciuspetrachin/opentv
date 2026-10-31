# Filmes e séries sem carregar a lista inteira

Os canais desta playlist já abrem. Filmes e séries não. Não é outro crash no parser. É o sítio onde esses títulos foram parar, e o que os ecrãs de Filmes e Séries fazem quando a categoria é grande.

Nesta playlist os filmes e as séries **não são VOD Xtream**. São linhas M3U normais, com `group-title`. Exemplos reais do ficheiro, com a contagem de entradas:

| Grupo | Entradas |
| --- | ---: |
| `SERIES \| NOVELAS` | 45 139 |
| `SERIES \| LEGENDADAS` | 40 219 |
| `SERIES \| NETFLIX` | 32 768 |
| `FILMES \| DRAMA` | 3 025 |
| `FILMES \| COMEDIA` | 2 368 |
| `FILMES \| AÇÃO` | 2 068 |

O mesmo fornecedor repete a prateleira com outro nome. `SERIES | NOVELAS` (45 139) e `♦️ Novelas` (44 730) são a mesma estante duas vezes. O mesmo padrão aparece em Netflix, Legendadas, Globoplay, HBO Max, Disney+, Doramas.

A importação gravou isto na tabela `channels`, com `categories.kind = LIVE`. As tabelas `movies` e `series` ficaram a **zero**. Os separadores Filmes e Séries só leem essas duas tabelas, por isso continuam vazios. Os grupos grandes, se forem abertos no guia, passam do teto de 2 000 e têm de ser paginados como um canal — a grelha de posters de hoje não pagina.

Uma conta Xtream grande cai no mesmo ecrã por outro caminho. A descarga de `get_vod_streams` / `get_series` já vai em lotes de 300. O que rebenta depois é o ecrã: `MovieDao.observe` e `SeriesDao.observe` fazem `SELECT *` da categoria inteira, e `VodViewModel.loadHomeFeeds` chama `allMovies()` / `allSeries()` (`SELECT *` da tabela, sem `LIMIT`) para montar Recomendados e os géneros.

## Regra

A mesma dos canais. Uma lista pequena não muda de comportamento. Uma lista grande não monta a categoria na RAM.

- Não copiar os canais M3U para as tabelas `movies` e `series`. Era uma segunda cópia de centenas de milhares de linhas, e o ecrã voltava a lê-la toda.
- Um grupo cujo nome é de filmes ou de séries sai do guia ao vivo e aparece no separador certo. O `streamUrl` continua a ser o do canal.
- Acima de 2 000 títulos, o separador não subscreve a categoria inteira. A primeira página cabe no ecrã. O fundo pede a seguinte, com cursor, não com `OFFSET`.
- `loadHomeFeeds` deixa de ler a tabela inteira. As prateleiras de abertura são consultas com `LIMIT`.
- Uma falha a meio não apaga o que já foi gravado. Categorias de filmes e de séries gravam-se por lote, como as de canais.

O teto é o mesmo `GUIDE_LIST_CAP` (2 000) de `ChannelsViewModel`. Não é um modo à parte.

## 1. Classificar o grupo M3U sem mudar o canal

Ficheiros: `CatalogRepository.syncM3u` e `CatalogRepository.repairLiveCategories`.

O canal fica como está: `categoryId` = o `group-title`. Só a linha em `categories` muda de `kind`.

Um grupo é de **filmes** quando o nome, depois de tirar espaços, começa por `FILMES` ou `FILME` (com ou sem `|` a seguir).

Um grupo é de **séries** quando começa por `SERIES`, `SÉRIES` ou `SERIE`.

Tudo o resto continua `LIVE`, incluindo os grupos `♦️ …`. São a cópia decorada das prateleiras `SERIES |` / `FILMES |`. Mostrá-los outra vez duplicava a estante. Ficam de fora dos dois separadores e também saem do guia ao vivo: não são canais de televisão. A comparação é pelo nome já sem o prefixo `♦️` e sem o prefixo `SERIES |` / `FILMES |`, em maiúsculas. `♦️ Novelas` corresponde a `SERIES | NOVELAS`. Um `♦️` sem par fica só escondido do guia, não inventa uma categoria nova.

`repairLiveCategories` hoje só corre quando não há nenhuma categoria `LIVE`. Nesta base as 133 categorias já existem, todas `LIVE`, por isso um arranque novo não as reclassifica. A correção tem de correr na mesma: ler os `categoryId` distintos (a query `liveCategorySeeds` já existe) e fazer `UPDATE` do `kind`, mais esconder o gémeo `♦️`. Não é um `SELECT *` dos canais.

Canais de televisão (`Canais Globo` e o resto que não cai nestas regras) ficam no guia.

## 2. O separador lê esses canais, paginados

Ficheiros: `VodViewModel`, `VodScreens.kt` (`MoviesScreen`, `SeriesScreen`, `MovieCategoryGrid`, `SeriesCategoryGrid`).

As chips de Filmes passam a ser as categorias `MOVIE`. As de Séries, as `SERIES`. Isso já é o que `movieCategories` / `seriesCategories` observam. Falta a grelha não ir a `movies` / `series` quando a categoria é de um M3U.

Para uma categoria M3U:

- Contar com `countInCategories`. Até 2 000, a query actual com `LIMIT` chega. Acima, a mesma página do guia: `pageChannels`, cursor `(sortIndex, id)`, primeira página com `afterSort = Int.MIN_VALUE` e `afterId = -1`.
- Cada cartaz é o canal: `displayName`, `logoUrl` no lugar do poster, play pelo `streamUrl` do canal. Não há ficha Xtream (elenco, ano, TMDB). O clique abre o player ao vivo, não `VodDetail`.
- "Todos" numa biblioteca acima de 2 000 não subscreve a tabela. Mostra a contagem e pede uma categoria, como o guia. A primeira categoria pode ser escolhida uma vez, para o separador não abrir vazio.
- Grelha vazia **não** é o spinner. `MovieCategoryGrid` e `SeriesCategoryGrid` tratam `isEmpty()` como "ainda a carregar" e nunca saem dali. Vazio, com a sincronização parada, é o estado vazio que já existe.

Uma categoria Xtream (linhas reais em `movies` / `series`) usa o mesmo teto e o mesmo cursor, com duas queries novas ao lado de `observe`:

```text
count da categoria
página: categoryId = :id AND (addedMillis, id) < cursor ORDER BY addedMillis DESC, id DESC LIMIT 2000
```

`addedMillis` é a ordem que `MovieDao.observe` já usa. O cursor não é `OFFSET`.

## 3. A abertura do separador não lê a tabela

Ficheiro: `VodViewModel.loadHomeFeeds`.

`allMovies()` e `allSeries()` saem deste caminho. Eram aceitáveis para uns milhares de títulos. Para uma estante de 45 000 são o `SELECT *` que o guia deixou de fazer.

No lugar disso, consultas que já existem e já têm `LIMIT`:

- Adicionados há pouco: `observeRecentlyAdded` (já está no ecrã).
- Um género: uma query `WHERE genre LIKE` com `LIMIT`, não um varrimento da tabela em Kotlin para depois partir por género.
- Recomendados: o mesmo, a partir do género mais visto do perfil, com `LIMIT`. Sem afinidade nenhuma, a fila fica vazia. Não se substitui por "os primeiros 40 000".

`moreLikeThis` e "filmes com este actor" já têm `LIMIT`. Ficam.

## 4. Xtream, o que ainda falta na descarga

A descarga em si já está em lote (`writeMovies`, `writeSeries`, `downloadHttp` sem o teto de 5 minutos). Falta o que os canais já fazem e isto não:

- Gravar a categoria no lote, não só a lista que `movieCategories` / `seriesCategories` devolve antes do corpo. Se o JSON morrer a meio, os filmes ficam e as chips também.
- `delete` dos que deixaram de vir **só** depois do último lote com sucesso. Hoje `flushMovies` / `flushSeries` não têm o equivalente de `deleteStale`. Um Xtream que falha a meio não deve apagar a biblioteca antiga; um que termina deve deixar de mostrar títulos que o painel já não envia. O carimbo é o mesmo esquema de `lastSeenMillis` dos canais. Sem esse carimbo nas entidades, não se apaga nada neste passo — primeiro a coluna, depois o `DELETE`, e só no sucesso.

`get_series_info` e `get_vod_info` continuam a ser de **uma** série ou de **um** filme, quando se abre a ficha. Não passam a descarregar os episódios de todas as séries no sync. Uma série com muitos episódios é uma resposta só; se um dia passar do que cabe, pagina-se essa ficha, não o sync.

## 5. O gestor de canais

`ChannelDao.observeInCategoriesIncludingHidden` ainda devolve a categoria inteira. Uma categoria `SERIES | NOVELAS` aberta no gestor é o mesmo pico de memória. Aplica-se o mesmo teto e o mesmo cursor. O gestor precisa das linhas `hidden`, a query pagina na mesma, só não filtra `hidden = 0`.

`allForMatching()` e `forSource()` continuam no DAO e já não têm chamadas. Não voltam a ser ligados.

## Como confirmar

- Abrir Filmes: chips `FILMES | DRAMA`, `FILMES | COMEDIA`, … e uma grelha da primeira página, não um spinner eterno.
- Abrir Séries: chips `SERIES | …`. `♦️ Novelas` não aparece ao lado de `SERIES | NOVELAS`.
- O guia ao vivo deixa de listar esses grupos e continua a listar `Canais Globo`.
- Uma categoria com mais de 2 000 títulos mostra a primeira página e pede a seguinte no fundo. O heap não cresce com o tamanho da categoria.
- Logcat: `adb logcat -s OpenTV.Load:I AndroidRuntime:E`. Não há `SELECT` da tabela inteira no arranque do separador.
- Uma conta Xtream pequena continua a abrir a ficha, o elenco e os episódios como hoje.
