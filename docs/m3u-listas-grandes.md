# Listas M3U grandes sem crash

A app fechou ao abrir uma playlist M3U de **185 MB**. Não foi o guia de 24 horas. Foi a leitura do ficheiro.

No emulador o heap máximo era **192 MB**. O processo morreu com `OutOfMemoryError` por volta dos **405 000 canais**, ainda dentro de `M3uParser.parse`, na linha `parseAttributes`. Não havia espaço nem para uma alocação de 16 bytes. A exceção chegou à thread principal e o sistema matou a activity. O emulador voltou à home.

Isto tem de valer para qualquer M3U, não só para esta lista. Uma lista pequena continua a abrir como hoje. Uma lista enorme abre a mesma função, só que nunca monta a lista inteira na RAM.

## Porque é que 185 MB não cabem

O parser já lê o HTTP linha a linha. O comentário em `M3uParser` diz isso. O que rebenta é o que fica **depois** de cada linha:

1. `parseAttributes` corre um `Regex.findAll` em cada `#EXTINF` e constrói um `Map` novo. Aos 400 mil canais isso, somado aos objetos, esgotou o heap. O stack foi `MatcherMatchResult.getGroupValues` → `M3uParser.parseAttributes`.
2. Cada canal aceite vai para um `ArrayList<Channel>` que só é devolvido no fim. Cada `Channel` segura nome, URL, logo e ids. 400 mil objetos passam facilmente dos 192 MB. O ficheiro de 185 MB vira bem mais do que 185 MB em objetos Java.
3. `seenStreamIds` é um `HashSet` com uma `String` por canal.
4. `syncM3u` ainda faz `normalized(parsed.channels)`, uma segunda lista completa, e `replaceCatalogue` carrega o catálogo antigo (`userStateForSource` faz `SELECT *`) e constrói uma terceira lista antes de gravar.
5. Se a gravação chegasse ao fim, o passo seguinte também rebentava: `EpgRepository.runMatcher` chama `channelDao.allForMatching()` (`SELECT * FROM channels`) e o guia, em "Todos", faz `observe(...)` sem `LIMIT`. A lista só mudava de sítio: deixava de morrer no parser e morria ao aparecer.

`largeHeap` não resolve. O teto sobe e a próxima lista, ou a segunda cópia da mesma, volta a matar o processo. O `MemoryGovernor` evita `largeHeap` de propósito.

`OutOfMemoryError` não é `Exception`. O `catch (e: Exception)` de `syncLive` não o apanha. O `catch (t: Throwable)` atual regista e **relança**. Por isso a coroutine morre na main e a app fecha.

## Regra

Uma só pipeline, para listas pequenas e grandes:

- Ler o corpo HTTP como stream. Nunca `body.string()` nem `parse(text)`.
- Não acumular os canais. Normalizar e gravar em lotes de poucas centenas, e largar o lote.
- Não fazer `SELECT *` da tabela inteira para fundir estado, para o matcher, nem para pintar "Todos".
- Uma falha, incluindo falta de memória, termina o sync com erro visível e a app continua aberta. O que já foi gravado fica. Não apagar o catálogo a meio.

O lote de 400 ids que o guia já usa continua válido. Este documento é o import M3U e tudo o que, depois dele, volta a puxar a tabela inteira.

## 1. Parser que não devolve a lista

Ficheiro: `app/src/main/java/app/opentv/data/parser/M3uParser.kt`.

Substituir o `Result` com `List<Channel>` por uma leitura com callback. Algo nesta forma:

```kotlin
fun parse(
    reader: BufferedReader,
    sourceId: Long,
    onChannel: (Channel) -> Unit,
): Header
```

`Header` fica só com `declaredEpgUrl` e `skippedEntries`. Quem chama é que decide quando gravar.

Dentro do loop:

- Não criar um `Map` por linha. Os atributos que interessam são poucos: `tvg-id`, `tvg-name`, `tvg-logo` / `logo`, `group-title`, `tvg-chno`, e no cabeçalho `url-tvg` / `x-tvg-url`. Extrair cada um com `indexOf` para um punhado de variáveis locais, reutilizadas em cada linha. O `Regex` pode sair.
- `displayNameOf` mantém-se: texto depois da última vírgula, senão `tvg-name`.
- Normalizar **neste** momento (`ChannelNameNormalizer.normalize` mais a qualidade que hoje está na categoria) e passar já o `Channel` com `displayName`, `groupKey`, `qualityRank`, `qualityLabel` e `hidden` se for linha separadora. Assim `normalized()` deixa de receber a lista inteira.
- `onChannel` é chamado e a função não guarda o objeto.

Duplicados (`tvg-id` repetido em variantes de qualidade): hoje o primeiro ganha e o resto é ignorado, para não chocar com o índice único `(sourceId, streamId)`. Não uses um `HashSet<String>` de 400 mil ids. Usa um conjunto de hashes de 64 bit (o FNV que `stableHash` já calcula, guardado como `Long`). 400 mil longs são cerca de 3 MB. Colisão é rara; o índice único do Room é a rede de segurança. Na gravação, canal novo entra com `OnConflictStrategy.IGNORE`, para o segundo `tvg-id` não substituir o primeiro. O estado do utilizador (favorito, nome, override de EPG) continua a ser copiado à parte, no lote, como `replaceCatalogue` faz hoje.

Categorias: um `LinkedHashSet` só com os `group-title` distintos. São dezenas ou centenas de strings, não centenas de milhares. Gravar isso uma vez, no fim ou assim que o conjunto estabilizar. Não voltar a percorrer todos os canais para inventar categorias.

O `parse(text: String)` que existe para testes pode ficar, desde que também passe pelo callback e não seja usado no sync.

## 2. Gravar em lotes durante o download

Ficheiro: `CatalogRepository.syncM3u`.

Manter o `OkHttp` com `byteStream()`. Não mudar isso.

```text
lote vazio
para cada canal do parser:
    acrescentar ao lote
    a cada 300 canais: gravar o lote e limpá-lo
gravar o resto
gravar categorias
adotar url-tvg se o source não tiver epgUrl
deleteStale só aqui, no fim com sucesso
markCatalogSynced
```

300 é de propósito abaixo do limite de 999 variáveis do SQLite no `minSdk` 23, porque o passo do estado antigo vai consultar esses `streamId`.

`ChannelDao.replaceCatalogue(incoming: List<Channel>)` não serve para isto: exige a lista completa e ainda faz `SELECT *` do source. Criar uma operação de lote:

- `userStateForStreamIds(sourceId, ids)` com `streamId IN (:ids)`, no máximo 300.
- Fundir favorito, `hidden`, `sortIndex`, `customName`, `epgOverrideId`, `matchedEpgId` e `lastSeenMillis` só nesses 300, como o `replaceCatalogue` atual.
- `INSERT` com `IGNORE` nos novos, e update nos que já existiam (ou um upsert que não pise o primeiro `streamId` duplicado do ficheiro; o conjunto de hashes já os filtrou).
- `deleteStale(sourceId, syncStamp)` **uma vez**, depois do último lote. Se o sync for cancelado ou falhar, não chamar `deleteStale`. Os canais velhos permanecem. Os lotes novos ficam com o carimbo novo e o próximo sync completo acaba por os reconciliar. Apagar a tabela no início continua proibido: um sync a meio deixaria a app vazia.

`userStateForSource` (`SELECT * FROM channels WHERE sourceId`) não pode ser usado neste caminho.

Reportar progresso com o `StatusBus` que a barra de baixo já mostra: "A ler canais… 120000", atualizado a cada lote. Sem isto, dois minutos de silêncio parecem crash.

Cancelar o `Call` do OkHttp quando a coroutine for cancelada, senão o download continua depois do ecrã ter saído.

## 3. Falha não fecha a app

Em `syncLive` / `saveAndSync`:

- Apanhar `CancellationException` e relançar, como agora.
- Apanhar `OutOfMemoryError` e qualquer outra `Throwable` **sem** relançar para o `viewModelScope`.
- Devolver `SyncResult.Failed` com uma frase curta: a lista é grande de mais para terminar, o que já foi gravado mantém-se, tenta outra vez.
- Limpar o `StatusBus` nesse ramo. O spinner de "Loading your channels" não pode ficar preso, e a activity não pode morrer.

O `LoadTrace` que já escreve `files/load-trace.log` e o logcat `OpenTV.Load` fica. Uma linha por lote chega (`m3u batch n=120000`). Não regresses ao log de 5 em 5 mil dentro do parser se isso acrescentar pressão; o lote já é o marco.

## 4. Depois de gravar, ninguém lê a tabela inteira

Importar 400 mil linhas para o SQLite e em seguida carregá-las outra vez é o mesmo crash cinco segundos mais tarde.

### Guia ("Todos")

`ChannelDao.observe` e `observeInCategories` devolvem `Flow<List<Channel>>` sem teto. `ChannelsViewModel.rows` monta uma `Row` por canal lógico.

Quando `COUNT(*)` do source (ou do filtro atual) passar de um teto, por exemplo **2 000 linhas visíveis**:

- Não subscrever "Todos". Selecionar a primeira categoria do rail. Cada categoria de uma lista deste tamanho é muito menor do que o ficheiro inteiro.
- Se mesmo uma categoria passar do teto, a query dessa categoria leva `LIMIT` e a lista pede a página seguinte ao chegar ao fundo (`sortIndex` / id como cursor, não `OFFSET` grande).
- "Todos" numa lista enorme mostra um estado curto: quantos canais existem e que é preciso escolher uma categoria ou pesquisar. Não uma grelha de 400 mil linhas.
- Favoritos e a pesquisa continuam como estão. A pesquisa já é limitada no DAO. Não a ligues ao `rows` do guia.

O teto é da quantidade de linhas **nesta query**, não um modo especial com nome. Lista de 200 canais nem dá por ele.

### Matcher do EPG

`runMatcher` não pode chamar `allForMatching()` depois de um import grande. Percorrer a tabela em páginas (`id > :after ORDER BY id LIMIT 500`), atualizar `matchedEpgId` dessa página, seguir. O mesmo para `renormalizeAll` se alguma vez correr sobre esta tabela.

O guia em si já não faz `SELECT` da janela de 24 horas inteira. Não voltes a ligar `observeGuideWindow` ao `rows`.

### Xtream

`syncXtreamLive` também faz `api.liveStreams()` para uma lista e depois `replaceCatalogue`. Uma conta Xtream com centenas de milhares de canais cai no mesmo sítio. O mesmo lote serve: o cliente HTTP emite canais num callback em vez de devolver `List<Channel>`. Não é o bug que vimos desta vez, mas a regra é a mesma. Não deixes o M3U seguro e o Xtream com a lista inteira.

## 5. O que não fazer

- Não ligar `android:largeHeap`.
- Não gravar os 185 MB num `String` "para parser depois".
- Não baixar o ficheiro todo para disco e só então parsear para uma lista. Disco pode ser cache opcional; a RAM continua em lotes.
- Não mostrar os 400 mil canais num `LazyColumn`. O lazy só compõe o que está no ecrã; a `List` do `StateFlow` continua toda na heap.
- Não relançar `OutOfMemoryError` para a main "para não engolir o erro". O erro fica no `LoadTrace` e no `SyncResult.Failed`.

## Ordem de implementação

1. Callback no `M3uParser`, sem `Regex` e sem `ArrayList` de canais. Teste unitário com um M3U pequeno: cabeçalho `url-tvg`, duplicado de `tvg-id` (fica o primeiro), linha sem `#EXTINF` contada como skipped, separador `####` marcado `hidden`.
2. `syncM3u` grava lotes de 300 e só no fim chama `deleteStale`. Estado do utilizador lido por `streamId IN (:ids)` do lote.
3. `OutOfMemoryError` e o resto das falhas viram `SyncResult.Failed` sem fechar a activity. Barra de progresso com a contagem.
4. Guia: acima de ~2 000 linhas, não abrir "Todos"; categorias e pesquisa. Matcher e `renormalizeAll` em páginas.
5. O mesmo lote no Xtream, quando o parser M3U já estiver estável.

## Como confirmar

Não commits o ficheiro de 185 MB. Gera um M3U sintético no teste (ou aponta o sync de debug para um ficheiro local) com mais canais do que o heap aguenta se fossem uma `List`.

Nesta máquina o heap de debug foi 192 MB e a lista real passou dos 405 mil canais antes de morrer. O critério:

- Durante o import, o heap não cresce em função do tamanho da lista. Oscila à volta de um lote, não sobe até aos 190 MB e fica lá.
- A app não fecha. Se algo correr mal, aparece a mensagem de falha e o ecrã anterior continua lá.
- No fim, `COUNT(*)` bate certo com os canais aceites (descontando `tvg-id` repetido e linhas skipped).
- Abrir a app de seguida mostra categorias e uma categoria, não uma segunda morte em `observe` ou `allForMatching`.
- Uma playlist de poucas dezenas de canais continua a abrir direto em "Todos", com guia, como agora.

Logcat: `adb logcat -s OpenTV.Load:I AndroidRuntime:E`. O ficheiro no aparelho é `files/load-trace.log` (no debug, `app.opentv.debug`). Não registes a URL da playlist: o usuário e a senha vão no query string.
