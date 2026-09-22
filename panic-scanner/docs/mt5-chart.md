# WIN M5: gráfico MT5

Página: http://localhost:5173/win-m5 (menu WIN M5).
Endpoint: GET http://localhost:8080/api/mt5/candles.

## Execução

Mantenha o MetaTrader 5 conectado, com WINV26 disponível. O `python` do PATH
precisa ter MetaTrader5 instalado. Na raiz do repositório, use JDK 21:

```powershell
mvn -B -pl panic-scanner -am verify dependency:copy-dependencies
java -cp "panic-scanner/target/classes;panic-scanner/target/dependency/*" br.com.bauzin.market.panic.PanicScannerApplication
```

No IntelliJ, também é possível executar PanicScannerApplication com JRE 21,
classpath de panic-scanner e working directory na raiz do repositório ou módulo.

Em outro PowerShell, na raiz:

```powershell
cd frontend
npm install
npm run dev -- --host localhost --strictPort
```

O frontend usa VITE_API_URL (padrão http://localhost:8080). Esta página sempre
consulta o backend real, mesmo se VITE_USE_MOCK_API=true para outras páginas.
Não é necessário BRAPI_TOKEN para esta consulta. O WIN Scanner existente e
seus providers são independentes desta página.

## Fluxo e comportamento

Mt5CandlesController -> GetMt5CandlesUseCase -> Mt5ProcessClient existente.
Mt5Config registra o cliente como bean sem alterar a classe de infraestrutura.
A integração continua consultando 1000 candles; o caso de uso retorna os últimos
100, ou todos quando houver menos. Timestamp, OHLC e volumes são preservados.
Não há filtragem do candle em formação, indicadores, polling ou WebSocket.
Atualizar faz uma nova consulta manual. Não há cache no servidor.

IOException retorna HTTP 502 com mensagem amigável; interrupção retorna 503 e
restaura o status de interrupção da thread. Detalhes ficam no log do backend.
Lista vazia do cliente retorna []; a implementação MT5 atual normalmente trata
a ausência de candles como erro. A página trata carregamento, lista vazia e erro.

## Biblioteca e tempo

O frontend existente usa React/TypeScript/Vite, Material UI, React Query/Axios
e Recharts. Recharts não tem série candlestick nativa. Foi adicionado
Lightweight Charts 5.2.1, biblioteca financeira em canvas com candlesticks,
zoom e pan nativos, licença Apache 2.0. A atribuição aparece na página e no gráfico.
https://github.com/tradingview/lightweight-charts

`time` passa diretamente para UTCTimestamp, em segundos Unix, sem offsets,
arredondamento, ordenação corretiva ou preenchimento de lacunas. A biblioteca
exibe o eixo temporal em UTC; locale pt-BR só muda a formatação. Não é uma
correção do horário do servidor MT5 e não muda o diagnóstico temporal anterior.
https://tradingview.github.io/lightweight-charts/docs/api/type-aliases/UTCTimestamp

O eixo lógico coloca candles consecutivos lado a lado mesmo quando há lacunas
de negociação; nenhum candle sintético é criado. Na API frontend, Zod valida
os campos numéricos e a ordem crescente antes de entregar dados ao gráfico.

## Validação

- Maven verify: 119 testes sem falhas; quatro novos testes do endpoint com cliente
  simulado, cobrindo limite de 100, preservação dos campos, vazio, erro e interrupção.
- Frontend: npm run build e npm run lint aprovados. Não havia script de testes
  unitários no frontend; nenhum novo framework de testes foi adicionado.
- Navegador Edge headless: renderização real de 100 candles via REST, estados de
  vazio/erro simulados apenas na validação e nenhuma exceção JS não tratada.
- CORS validado para origem http://localhost:5173.

O build Vite ainda avisa sobre bundle acima de 500 kB. Não foram feitas
refatorações globais de empacotamento nesta etapa. Para concluir a validação
local foi restaurado o arquivo ausente cjs/react-is.production.js do pacote
react-is 19.2.8, sem alterar sua versão.
