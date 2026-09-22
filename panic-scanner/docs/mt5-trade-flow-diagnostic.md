# Diagnóstico de fluxo de negócios MT5/Clear — WINV26

Data da investigação: 22/09/2026. Pacote Python instalado: MetaTrader5 5.0.6180.

## Resultado real

A captura ao vivo foi executada fora do fluxo do contrato: em 10 segundos,
`symbol_info_tick()` retornou um único estado estático e `copy_ticks_range()`
não retornou eventos no mesmo intervalo. Portanto, essa execução não mede uma
razão de perda durante mercado ativo. Execute novamente o comando abaixo quando
o WINV26 estiver negociando para obter X versus Y no mesmo período.

Para analisar a qualidade efetiva do feed, o diagnóstico carregou os 30 minutos
anteriores ao último tick disponível (21/09/2026 18:01:52–18:31:52 UTC):

- eventos `COPY_TICKS_ALL`: 20.228;
- trades, pelo critério `LAST || VOLUME`: 18.394;
- alterações BID: 928; alterações ASK: 910;
- trades BUY exclusivo: 9.005;
- trades SELL exclusivo: 8.716;
- trades com BUY e SELL simultaneamente: 673;
- trades sem BUY/SELL: 0;
- `volume != volume_real`: 0;
- `volume_real` fracionário: 0;
- volume zero: 0;
- `volume_real` zero: 0;
- valores `time_msc` compartilhados: 2.624;
- eventos adicionais nesses mesmos timestamps: 4.507;
- ordem devolvida: não decrescente, do passado para o presente.

Algumas linhas reais, na ordem devolvida pelo MT5:

```text
18:01:52.057 UTC last=188375 bid=188370 ask=188375 volume=1 volume_real=1 flags=1336 LAST|VOLUME|BUY
18:01:52.180 UTC last=188375 bid=188370 ask=188375 volume=1 volume_real=1 flags=1336 LAST|VOLUME|BUY
18:01:52.569 UTC last=188370 bid=188370 ask=188375 volume=1 volume_real=1 flags=1368 LAST|VOLUME|SELL
18:01:52.797 UTC last=188375 bid=188370 ask=188375 volume=1 volume_real=1 flags=1336 LAST|VOLUME|BUY
18:01:52.969 UTC last=188375 bid=188370 ask=188375 volume=1 volume_real=1 flags=1336 LAST|VOLUME|BUY
```

Numa janela final de dois minutos, 44 trades compartilharam apenas quatro
timestamps e havia 42 eventos adicionais nesses timestamps. Um único
`time_msc=...548` continha negócios distintos com volumes 9000, 8417, 554,
13, 25, 68, 84, 553, 20, 16, 7, 17 etc. Timestamp e conteúdo repetido não
podem ser usados como chave única: negócios legítimos podem ser idênticos.

Os valores observados de `flags` também contêm bits altos 256 e 1024
(máscara adicional 1280), ausentes das constantes TICK_FLAG publicadas pelo
pacote. A classificação usa somente os bits oficiais baixos e preserva o valor
bruto para diagnóstico; nenhum significado foi inventado para os bits extras.

## Semântica confirmada

Constantes da versão instalada:

```text
COPY_TICKS_ALL=-1 INFO=1 TRADE=2
BID=2 ASK=4 LAST=8 VOLUME=16 BUY=32 SELL=64
```

Segundo a documentação MetaQuotes, `COPY_TICKS_INFO` seleciona mudanças de
Bid/Ask; `COPY_TICKS_TRADE` seleciona mudanças de Last/Volume; `ALL` devolve
ambas. Todos os campos carregam o último estado conhecido, então a presença de
um valor não prova que ele mudou: os flags são a fonte dessa distinção.

`volume` é inteiro (`ulong`) do negócio no preço Last; `volume_real` representa
o mesmo volume com maior precisão (`double`). No WINV26 desta amostra foram
iguais e inteiros em 100% dos eventos. Para um futuro Times & Trades, usar
`volume_real` preserva precisão sem alterar a amostra observada. Isso não deve
ser confundido com `realVolume` acumulado do candle M5.

O lado agressor está disponível no feed Clear/MT5 para WINV26: ambos BUY e SELL
apareceram em milhares de trades. Porém 673 eventos carregaram os dois bits.
Logo, buyVolume/sellVolume, Delta e CVD podem ser calculados diretamente apenas
para eventos com lado exclusivo. Nesta etapa, a categoria BUY+SELL é
**DADO AMBÍGUO** e não foi rateada nem inferida. Não existe heurística no código.

## Polling versus histórico completo

`symbol_info_tick()` é apropriado para cotação/candle porque entrega o último
estado rapidamente. Ele não é um log de eventos. A própria documentação avisa
que uma notificação pode representar um lote e disponibilizar apenas o estado
mais recente. A amostra histórica contém muitos negócios no mesmo milissegundo;
uma consulta a cada 20 ms não consegue recuperar cada um individualmente.

A comparação quantitativa simultânea em mercado ativo ficou pendente porque não
houve ticks novos durante a execução. Ainda assim, a amostra prova a limitação
estrutural: em um milissegundo foram retornados vários negócios que uma leitura
do único estado atual não consegue enumerar. O stream existente continua correto
para preço/gráfico e não foi alterado.

`copy_ticks_from/range` é adequado como fonte do Times & Trades: retorna todos os
eventos armazenados, preserva campos completos, flags e ordem do mais antigo ao
mais novo. Sua precisão nominal é milissegundo, não uma identidade única nem uma
ordem temporal total; quando `time_msc` empata, deve-se preservar a ordem do array.

## Cursor recomendado para uma futura Trade Stream

Manter dois conceitos independentes:

```text
symbol_info_tick -> Quote Stream -> preço, book superior, candle visual
copy_ticks_*     -> Trade Stream -> todos os negócios -> futuro TradeFlowEngine
```

O Trade Stream deve consultar continuamente uma janela inclusiva iniciada no
último `time_msc` confirmado. O cursor deve guardar `(time_msc, ordinal)`, onde
ordinal é quantos eventos daquela marca já foram emitidos na ordem do MT5.
Na consulta seguinte, descarta exatamente os primeiros `ordinal` registros do
timestamp inicial e emite todo o restante. Nunca deduplicar por timestamp nem
por tupla de campos, pois negócios idênticos são válidos. Persistir o cursor se
for necessário sobreviver a reinícios e usar sobreposição curta para recuperar
lotes que chegam no limite temporal. O consumidor deve ser idempotente ou usar
uma sequência local monotônica atribuída depois da leitura.

Uma evolução conservadora pode reter temporariamente o último milissegundo até
a próxima consulta, reduzindo o risco de considerar completo um lote ainda em
chegada. Deve haver métricas de atraso, sobreposição, duplicatas descartadas e
falhas de continuidade. O processo Python seria persistente e enviaria NDJSON;
Java manteria janelas 1s/3s/5s/10s. Nada disso foi implementado nesta etapa.

## Execução

```powershell
cd C:\Users\paulo\project\cripto-analyser\panic-scanner\scripts
python .\mt5_ticks_diagnostic.py --seconds 10 --sample 12
```

Se o intervalo ao vivo não tiver eventos, o script também analisa os 60 segundos
anteriores ao último tick disponível. Para ampliar:

```powershell
python .\mt5_ticks_diagnostic.py --seconds 10 --history-seconds 1800 --sample 12
```

Referências oficiais:

- https://www.mql5.com/en/docs/python_metatrader5/mt5copyticksfrom_py
- https://www.mql5.com/en/docs/python_metatrader5/mt5copyticksrange_py
- https://www.mql5.com/en/docs/series/copyticks
- https://www.mql5.com/en/docs/constants/structures/mqltick
