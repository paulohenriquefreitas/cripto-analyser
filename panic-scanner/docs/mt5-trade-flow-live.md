# Times & Trades LIVE — MT5 para TradeFlowEngine

Pipeline independente validado em 22/09/2026:

```text
MT5 WINV26 copy_ticks_from
        -> mt5_trade_stream.py persistente
        -> NDJSON em stdout
        -> Mt5TradeStreamClient
        -> MarketTrade
        -> TradeFlowEngine
        -> snapshots 1s / 3s / 5s / 10s
```

O quote stream existente, baseado em `symbol_info_tick()`, não foi modificado.
O novo pipeline usa somente `WINV26`; `WIN$N` não participa do fluxo agressor.

## Python e protocolo

`mt5_trade_stream.py` inicializa o terminal e seleciona o símbolo uma vez. Faz
um warm-up limitado aos dez segundos anteriores ao tick atual com
`copy_ticks_range`; em seguida consulta `copy_ticks_from` a cada 20 ms, filtra
eventos com LAST ou VOLUME e emite um negócio por linha:

```json
{"type":"trade","symbol":"WINV26","timeMsc":1790090740673,"price":188375.0,"volume":2.0,"side":"BUY"}
```

Somente NDJSON é escrito em stdout. Diagnósticos e erros usam stderr. O volume
vem exclusivamente de `volume_real`. Zero, negativo, NaN ou infinito encerra o
stream com evidência explícita contendo também `volume`; não existe fallback
silencioso.

O lado é normalizado assim:

```text
BUY exclusivo  -> BUY
SELL exclusivo -> SELL
BUY + SELL     -> AMBIGUOUS
sem BUY/SELL   -> AMBIGUOUS + contador em stderr
```

Não há inferência por bid/ask, tick rule ou movimento de preço.

## Cursor sem deduplicação por conteúdo

O cursor é `(timeMsc, emittedAtTime)`. Cada nova consulta inclui novamente o
último timestamp. Entre os trades desse timestamp, o stream ignora apenas os
primeiros `emittedAtTime` eventos, na ordem devolvida pelo MT5. Eventos extras
são emitidos mesmo quando timestamp, preço, volume e flags são idênticos.

Na validação real, `copy_ticks_from` arredondou o `datetime` inicial para o
segundo e devolveu alguns eventos anteriores ao cursor. O stream ignora esses
timestamps anteriores e aplica o ordinal somente ao `timeMsc` exato. Isso evita
reemitir a parte anterior do segundo sem descartar negócios adicionais no
milissegundo corrente.

O lote máximo por chamada é 100.000 eventos. A ordem devolvida pelo MT5 é
preservada até `TradeFlowEngine`, que também valida ordem não decrescente.

O cursor resultante do warm-up começa no último trade emitido, incluindo seu
ordinal, portanto a primeira consulta LIVE não o duplica. Se o warm-up estiver
vazio, o cursor começa no timestamp do tick atual. Não são carregados minutos
ou horas anteriores.

Uma consulta vazia representa mercado sem novos negócios e apenas aguarda o
próximo poll. `None` representa erro real: ele é registrado em stderr e tentado
novamente após um segundo, sem mover o cursor. Três erros consecutivos encerram
o processo explicitamente.

## Cliente Java e lifecycle

`Mt5TradeStreamClient` inicia um único `python -u`, converte cada linha
diretamente em `MarketTrade` e chama o consumer sincronamente. stderr é drenado
em thread virtual separada, evitando bloqueio do processo. JSON inválido, saída
inesperada, erro Python e falha do consumer encerram o processo e propagam a
falha.

O cliente é de uso único. `stop()` fecha stdin, permitindo que o Python execute
`mt5.shutdown()`, espera até três segundos e usa encerramento forçado somente
como fallback. Não existe reconexão silenciosa nesta versão de diagnóstico.

## Diagnóstico LIVE

`Mt5TradeFlowDiagnostics` conecta o cliente ao motor e usa um scheduler para
imprimir snapshots uma vez por segundo. O scheduler somente lê snapshots; não
avança nem altera as janelas, que continuam baseadas exclusivamente no
`MarketTrade.timeMsc`. Sem novos trades, o último estado permanece visível até
que uma futura API explícita de avanço temporal seja definida.

O cliente mantém `tradesReceived`, BUY, SELL, AMBIGUOUS, erros de parsing,
eventos fora de ordem e último `timeMsc`. O Python registra ao encerrar
`ticksRead`, trades emitidos, lados, trades sem lado, erros de consulta e último
timestamp.

Execução após compilar e copiar dependências:

```powershell
java -cp "panic-scanner/target/classes;panic-scanner/target/dependency/*" br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5TradeFlowDiagnostics
```

Amostra real inicial de 22/09/2026 em uma janela de 10 segundos:

```text
trades=345 volume=2706
buy=1044 sell=1641 ambiguous=21
knownDelta=-597
priceChange=-35
priceVelocity=-3.50 pontos/s
```

Outras janelas observadas mudaram independentemente conforme trades expiravam,
confirmando o funcionamento incremental de 1s, 3s, 5s e 10s. A amostra contém
BUY, SELL e AMBIGUOUS reais do contrato `WINV26`.

Uma execução controlada posterior permaneceu aproximadamente 37 segundos ao
vivo, além dos dez segundos de warm-up, e terminou com:

```text
tradesReceived=1407
BUY=747 SELL=651 AMBIGUOUS=9
parseErrors=0 outOfOrderErrors=0

último snapshot 10s:
trades=227 volume=2008 delta=+414
priceChange=-5 range=20 priceVelocity=-0.50
```

## Limites desta etapa

O pipeline permanece uma integração diagnóstica explícita. Não foi registrado
como bean Spring, não publica no WebSocket e não altera frontend, candles ou
quote stream. Também não implementa replay histórico, CVD, alertas, estratégias
ou persistência.
