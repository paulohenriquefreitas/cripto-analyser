# Integridade do Trade Stream LIVE

Validação executada em 22/09/2026, sem alterações no Quote Stream, no
`TradeFlowEngine`, no WebSocket ou no frontend.

## Método

`Mt5TradeIntegrityValidation` captura a fonte A pelo pipeline real:

```text
copy_ticks_from -> mt5_trade_stream.py -> NDJSON
-> Mt5TradeStreamClient -> MarketTrade -> captura
```

O warm-up é drenado antes da captura. O início só é aberto no primeiro
`time_msc` posterior ao último timestamp do warm-up. Após a duração solicitada,
o fim só é fechado quando chega o primeiro timestamp posterior. Assim, nenhuma
fronteira divide o grupo de negócios de um mesmo milissegundo.

Depois de fechar o LIVE, a ferramenta aguarda um segundo e executa
`mt5_trade_history.py`. A fonte B usa `copy_ticks_range` e filtra exatamente o
intervalo inclusivo `[validationStart, validationEnd]` pelos `time_msc` brutos.
Não há ajuste de timezone nem consulta enquanto o intervalo ainda se forma.

O comparador mantém listas ordenadas e atribui ordinal apenas para diagnóstico.
Ele compara exatamente `timeMsc`, bits de `price` e `volume`, e `side`. Não usa
`Set`, `distinct` ou `timeMsc` como chave. Um diff sequencial com lookahead de
128 eventos distingue ausências, extras, alteração de campo e blocos fora de
ordem sem propagar uma única ausência para o restante da sequência.

```powershell
java -cp "target/classes;target/dependency/*" `
  br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5TradeIntegrityValidation `
  --duration-seconds 60
```

## Lotes de 100.000

Um retorno com exatamente 100.000 ticks dispara outra consulta imediatamente.
O cursor também avança sobre a cauda de quotes de `COPY_TICKS_ALL`, mantendo
ordinal zero quando não houve trade naquele timestamp. Se um lote cheio não
permitir avanço, o processo falha explicitamente; ele não declara que alcançou
o presente e não entra em loop silencioso.

## Resultado real 1 — 60 segundos

```text
Symbol: WINV26
Start: 1790094225968
End:   1790094285980

LIVE/HISTORICAL:
trades=2209 BUY=1200 SELL=998 AMBIGUOUS=11 volume=22579
buyVolume=11063 sellVolume=11487 ambiguousVolume=29 knownDelta=-424
first=188730 last=188750 min=188700 max=188775 change=20

exactMatches=2209
missing=0 extra=0 different=0 orderMismatch=0
timestampMismatch=0 priceMismatch=0 volumeMismatch=0 sideMismatch=0

timestampsWithMultipleTrades=150
maxTradesAtSameTimeMsc=16
tradesSharingTimeMsc=415
identicalConsecutiveTrades=8

matchPercentage=100.000%
```

## Resultado real 2 — 30 segundos

```text
Symbol: WINV26
Start: 1790094317750
End:   1790094347749

LIVE/HISTORICAL:
trades=1595 BUY=970 SELL=587 AMBIGUOUS=38 volume=22370
buyVolume=16280 sellVolume=5875 ambiguousVolume=215 knownDelta=10405
first=188735 last=188785 min=188725 max=188840 change=50

exactMatches=1595
missing=0 extra=0 different=0 orderMismatch=0
timestampMismatch=0 priceMismatch=0 volumeMismatch=0 sideMismatch=0

timestampsWithMultipleTrades=160
maxTradesAtSameTimeMsc=13
tradesSharingTimeMsc=435
identicalConsecutiveTrades=5

matchPercentage=100.000%
```

Nas duas amostras, LIVE e histórico coincidiram evento a evento, uma vez e na
mesma ordem. `rawFlags` não faz parte do protocolo LIVE atual; a prova cobre os
campos normalizados que chegam ao domínio: timestamp, preço, `volume_real` e
agressor.
