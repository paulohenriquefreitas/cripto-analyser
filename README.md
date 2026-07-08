# Crypto Analyzer Starter

## Executar
mvn spring-boot:run

## Frontend

Abrir no navegador:

http://localhost:8080/

O frontend consome o endpoint:

`GET /analysis/sumarry`

## Frontend React + Vite

Projeto em:

`frontend/`

Executar:

```bash
cd frontend
npm install
npm run dev
```

Abrir:

`http://localhost:5173`

Configuração padrão da tela:

* Base URL: `http://localhost:8080`
* Endpoint: `/analysis/sumarry`
* Symbol: `BTCUSDT`
* Interval: `1m`
* Requested Candles: `259200`

## Endpoint

GET http://localhost:8080/crypto/candles

Exemplo:

/crypto/candles?symbol=BTCUSDT&interval=1m&limit=100
