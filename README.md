# Market Analyzer

Monorepo Maven para aplicações e bibliotecas relacionadas a análise de mercado.

## Estrutura

```text
market-analyzer
├── common
├── bolsai-client
├── ta4j-engine
├── panic-scanner
└── crypto-analyzer
```

## Módulos

- `common`: tipos e utilitários compartilhados.
- `bolsai-client`: cliente para integrações com dados da B3/Bolsa.
- `ta4j-engine`: camada para indicadores e estratégias baseadas em TA4J.
- `panic-scanner`: aplicação Spring Boot para scanner de pânico de mercado.
- `crypto-analyzer`: aplicação Spring Boot existente para análise de cripto.

## Build

Executar todos os módulos:

```bash
mvn test
```

Executar apenas o `crypto-analyzer` com dependências do reactor:

```bash
mvn -pl crypto-analyzer -am spring-boot:run
```

Executar apenas o `panic-scanner` com dependências do reactor:

```bash
mvn -pl panic-scanner -am spring-boot:run
```

Executar o WIN scanner com dados mock:

```bash
mvn -pl panic-scanner -am spring-boot:run -Dspring-boot.run.arguments="--win.market-data.provider=mock"
```

Executar o WIN scanner conectado ao bridge Python/Wine da ProfitDLL:

```bash
mvn -pl panic-scanner -am spring-boot:run -Dspring-boot.run.arguments="--win.market-data.provider=profitdll --profitdll.bridge.url=ws://127.0.0.1:8765 --profitdll.ticker=WINFUT --profitdll.exchange=F --profitdll.mock-warm-up-enabled=true"
```

Antes de usar `provider=profitdll`, inicie o bridge Python Windows x64 no Wine expondo `ws://127.0.0.1:8765`. A autenticação Nelogica permanece no bridge; o backend Java consome somente Market Data via WebSocket. Para ProfitDLL real com histórico próprio, use `--profitdll.mock-warm-up-enabled=false`.

## Crypto Analyzer

Frontend estático servido pela aplicação:

```text
crypto-analyzer/src/main/resources/static
```

Frontend React + Vite:

```bash
cd crypto-analyzer/frontend
npm install
npm run dev
```

Configuração padrão da tela:

- Base URL: `http://localhost:8080`
- Endpoint: `/analysis/sumarry`
- Symbol: `BTCUSDT`
- Interval: `1m`
- Requested Candles: `259200`

Endpoint:

```text
GET http://localhost:8080/crypto/candles
```

Exemplo:

```text
/crypto/candles?symbol=BTCUSDT&interval=1m&limit=100
```
