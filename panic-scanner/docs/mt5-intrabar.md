# WIN M5: atualizacao visual do ultimo candle

Abra http://localhost:5173/win-m5 com o backend/frontend existentes e MT5 conectado.
Nao ha mudanca em Java, Python, REST, protocolo WebSocket ou reconexao.

## Tempo e OHLC

O candle REST guarda Unix seconds e o tick guarda time/timeMsc brutos.
O bucket M5 e floor(epochSeconds / 300) * 300; para ticks, epochSeconds e
 timeMsc / 1000. Nao ha Date, fuso local ou offset no calculo. O frontend
confere floor(timeMsc / 1000) === time e ignora ticks inconsistentes, precos
nao finitos/nao positivos e ticks anteriores ao ultimo timeMsc observado.

Amostra real conferida: candle time=1790005500; tick time=1790005785,
timeMsc=1790005785842. Os dois resultaram no bucket 1790005500.

No mesmo bucket: open/time/volumes ficam intactos; high=max(high,last),
low=min(low,last), close=last. Bid, ask e tick.volume nao participam do OHLC.
Ticks de buckets anteriores sao ignorados; ticks posteriores pedem resync,
sem alterar o candle antigo ou criar um candle sintetico.

## Grafico e estado

Mt5LiveQuote continua dono da unica conexao WebSocket da pagina e atualiza seu
card normalmente. Um callback estavel encaminha o mesmo tick ao coordenador
mt5Intrabar, sem colocar ticks no estado React da pagina.

O coordenador e dono das consultas REST inicial, manual e de virada, permitindo
um unico controle de concorrencia. A pagina assina somente estado de carregamento,
erro e quantidade de candles. O grafico e montado depois do primeiro historico
valido, com largura visivel, e fica montado durante todos os resyncs/falhas.

Na Lightweight Charts 5.2.1, series.update(bar) substitui o ultimo candle quando
o time e igual. Essa e a unica chamada por tick; nao ha alteracao do array de
100 candles nem fitContent por tick. series.setData e usado apenas ao carregar
ou substituir o historico oficial REST; fitContent apenas ao inicializar a serie.
https://tradingview.github.io/lightweight-charts/docs/api/interfaces/ISeriesApi#update

## Virada, concorrencia e falhas

Um tick de bucket posterior dispara REST imediatamente. Um AbortController
representa a unica consulta em andamento, compartilhada com o botao Atualizar.
Ticks durante essa consulta nao abrem outras consultas. O ultimo tick valido
fica guardado e e reaplicado depois da resposta se pertencer ao ultimo candle
oficial. Isso tambem cobre ticks anteriores ao primeiro carregamento REST.

Se o MT5 ainda devolver o bucket anterior, nao inventamos o proximo candle:
apresentamos aviso e tentamos novamente apos 5 segundos. Falha HTTP segue a
mesma espera, registra console.error e mostra mensagem; o historico e o card
continuam presentes. Uma resposta vazia durante resync nao apaga o historico.
O botao Atualizar permite tentativa manual; durante requisicao fica desabilitado.

Ticks nao furam a espera de retry. Se varias viradas passarem durante a consulta,
a comparacao com o ultimo tick mantem a necessidade de sincronizacao. Cleanup
aborta requisicao, cancela retry e descarta respostas de geracoes anteriores.

Os volumes permanecem os retornados pelo REST. Polling do MT5 ainda pode perder
eventos entre consultas; esta visualizacao nao promete capturar todos os extremos
do mercado via ticks. O REST continua sendo a fonte oficial do historico.

## Validacao

- npm.cmd test: 12 testes aprovados (8 novos de intrabar), sem MT5.
- npm.cmd run build e npm.cmd run lint aprovados; permanece o aviso Vite sobre
  tamanho do bundle ja existente.
- Navegador real: 100 candles, uma chamada setData, varias chamadas update,
  close variando de 188510 para 188505 na primeira amostra; open=188580 e time
  permaneceram intactos. Sem requests REST adicionais no mesmo bucket, sem
  recriar canvas e sem exceptions JS nao tratadas.
- Navegador com REST/WebSocket simulados: max/min/open corretos, um request na
  virada, tick durante resync reaplicado, falha preservando grafico/card e retry
  apos 5s recuperando o novo candle oficial. Nenhum sleep longo nos testes unitarios.

Arquivos modificados: WinMt5Page.tsx, Mt5LiveQuote.tsx, Mt5CandlestickChart.tsx.
Criados: frontend/src/features/win/models/mt5Intrabar.ts,
frontend/tests/mt5Intrabar.test.mjs e este documento.
