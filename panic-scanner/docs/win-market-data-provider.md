# WIN Market Data Provider

The WIN scanner supports provider selection by configuration:

```properties
win.market-data.provider=mock
```

or:

```properties
win.market-data.provider=profitdll
profitdll.bridge.url=ws://127.0.0.1:8765
profitdll.ticker=WINFUT
profitdll.exchange=F
profitdll.mock-warm-up-enabled=true
profitdll.mock-warm-up-candles=50
```

## Mock provider

`win.market-data.provider=mock` starts the backend with deterministic generated WIN candles. It does not connect to the Python bridge.

## ProfitDLL provider

`win.market-data.provider=profitdll` connects the Linux/Spring backend to the Python bridge exposed by Windows Python x64 running under Wine.

The integration intentionally does not map or call any order-routing functions.

The Java backend does not load `ProfitDLL.dll` and does not receive Nelogica credentials. Authentication and DLL access remain isolated in the Python/Wine bridge.

For the current mock bridge, the backend performs a configurable warm-up on the first realtime tick. It seeds 50 closed candles of 5 minutes and 250 closed candles of 1 minute before the first realtime window, so the existing technical-analysis validation remains active and indicators such as SMA21, RSI9, ATR14, ADX14, relative volume and slope have enough history.

When switching to the real ProfitDLL bridge with real historical/realtime data, disable the mock warm-up:

```properties
profitdll.mock-warm-up-enabled=false
```

Expected bridge URL:

```text
ws://127.0.0.1:8765
```

Expected trade message:

```json
{
  "type": "trade",
  "ticker": "WINFUT",
  "exchange": "F",
  "timestamp": "2026-09-11T22:47:24.462",
  "tradeNumber": 398,
  "price": 145150.0,
  "quantity": 4,
  "volume": 0.0,
  "buyAgent": 0,
  "sellAgent": 0,
  "tradeType": 0
}
```

## Diagnostic endpoint

```http
GET /api/win/market-data/status
```

Example:

```json
{
  "provider": "profitdll",
  "dllLoaded": false,
  "connected": true,
  "subscribed": true,
  "symbol": "WINFUT",
  "lastTradeTime": "2026-09-08T10:15:31-03:00",
  "lastPrice": 190185,
  "tradesReceived": 12345,
  "lastClosedCandle": "2026-09-08T10:15:00-03:00",
  "message": "ProfitDLL bridge conectado em ws://127.0.0.1:8765"
}
```

## Data flow

```text
ProfitDLL.dll
  -> Python Windows x64 / Wine
  -> WebSocket ws://127.0.0.1:8765
  -> ProfitDllBridgeTrade
  -> immutable WinTrade copy
  -> WinCandleBuilder 1m and 5m
  -> AnalyzeWinUseCase
  -> GET /api/win/analysis
```

The WebSocket listener does not run technical analysis. It only deserializes trade messages and forwards immutable `WinTrade` objects to the existing WIN candle builders.

## Running

Start the Python bridge first, using the command/script that exposes `ws://127.0.0.1:8765` in the Wine environment.

Start the backend with the mock provider:

```bash
mvn -pl panic-scanner -am spring-boot:run -Dspring-boot.run.arguments="--win.market-data.provider=mock"
```

Start the backend with the ProfitDLL bridge provider:

```bash
mvn -pl panic-scanner -am spring-boot:run -Dspring-boot.run.arguments="--win.market-data.provider=profitdll --profitdll.bridge.url=ws://127.0.0.1:8765 --profitdll.ticker=WINFUT --profitdll.exchange=F --profitdll.mock-warm-up-enabled=true"
```

Trades received through the bridge feed the same `WinCandleBuilder`, so realtime candle construction remains identical to the existing WIN flow.
