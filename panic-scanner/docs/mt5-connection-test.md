# Teste de candles MT5

Integracao isolada Java -> ProcessBuilder -> python -> MT5 -> JSON -> List<Mt5Candle>.
Requer JDK 21, `python` no PATH com MetaTrader5 instalado e terminal MT5 conectado.

## IntelliJ

Execute o `main` de:
`br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5ProcessClient`

Na Run Configuration selecione JRE 21, classpath do modulo `panic-scanner` e
working directory na raiz do repositorio ou em `panic-scanner`. Nao inicie Spring.

## PowerShell (raiz do repositorio)

Com JAVA_HOME e PATH configurados para JDK 21:

```powershell
mvn -B -pl panic-scanner -am verify dependency:copy-dependencies
java -cp "panic-scanner/target/classes;panic-scanner/target/dependency/*" br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5ProcessClient
```

Agora Jackson e necessario no classpath; o comando antigo de execucao direta do
arquivo fonte sem dependencias nao se aplica mais.

## Protocolo e validacao

O script solicita WINV26/M5, posicao zero, 1000 candles. O MT5 pode retornar menos;
a quantidade real e exibida. O candle mais recente pode estar em formacao.
Stdout contem exclusivamente um array JSON com time, open, high, low, close,
tickVolume e realVolume. Os tipos NumPy sao convertidos para int/float nativos.
Erros vao para stderr com exit code diferente de zero; shutdown ocorre em finally.

`readCandles()` retorna `List<Mt5Candle>`. Jackson desserializa a resposta apos a
verificacao do exit code. Rejeitamos resposta vazia, JSON invalido, campos ausentes,
precos nao finitos, high < low, open/close <= 0 e timestamps nao crescentes.
Candles com todos os precos iguais e lacunas entre timestamps sao aceitos.
A espera pelo processo e limitada a 60 segundos.

O main mostra quantidade, primeiro e ultimo candle, OHLC e ambos os volumes
(real e ticks, identificados separadamente). Converte Unix seconds apenas para
apresentacao em America/Sao_Paulo; o record preserva o timestamp original.

O script e localizado no checkout a partir do working directory e seus ancestrais;
nao e empacotado no JAR. Nao ha integracao com Spring, TA4J ou scanners existentes.
Os testes unitarios de parsing/validacao nao precisam de Python nem de MT5.


## Diagnostico temporal

Execute no IntelliJ o main de
`br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5TimestampDiagnostics`,
com JRE 21, classpath de panic-scanner e working directory na raiz do checkout
ou do modulo. O main anterior de Mt5ProcessClient continua disponivel.

No PowerShell, com as dependencias preparadas pelo comando acima:

```powershell
java -cp "panic-scanner/target/classes;panic-scanner/target/dependency/*" br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5TimestampDiagnostics
```

A classe consulta candles e, em outro processo sequencial, o ultimo tick via
`python scripts/mt5_reader.py --tick`. Nao e um snapshot atomico. O modo padrao
do script continua retornando o array de candles. O modo --tick retorna um
objeto JSON com time, time_msc, bid, ask e last, sem conversao temporal no Python.
Falhas de tick sao reportadas em stderr/exit code; o diagnostico Java propaga
exceptions e ja tera mostrado o candle se a consulta posterior ao tick falhar.

### Semantica e fontes

- A documentacao de copy_rates_from descreve os tempos de ticks e abertura dos
  candles como UTC: https://www.mql5.com/en/docs/python_metatrader5/mt5copyratesfrom_py
- copy_rates_from_pos define a posicao zero como candle atual:
  https://www.mql5.com/en/docs/python_metatrader5/mt5copyratesfrompos_py
- symbol_info_tick retorna o ultimo tick, ou None em erro:
  https://www.mql5.com/en/docs/python_metatrader5/mt5symbolinfotick_py
- datetime.fromtimestamp sem tz usa o timezone local e retorna datetime sem
  informacao de fuso: https://docs.python.org/3/library/datetime.html#datetime.datetime.fromtimestamp

O pacote instalado observado foi MetaTrader5 5.0.6180. A implementacao anterior
ja fazia Instant.ofEpochSecond e apresentava America/Sao_Paulo corretamente;
nao foi encontrado erro aritmetico nessa conversao. A melhoria e tornar
explicita a semantica e mostrar simultaneamente raw, Instant, UTC e Sao Paulo.
Mt5Candle.time permanece o inteiro original. Nao existe ajuste manual de horas.
Para ticks, time usa segundos e time_msc usa Instant.ofEpochMilli, preservando
os milissegundos. Strings formatadas existem apenas na apresentacao.

### Evidencia e limite da conclusao

O timestamp informado 1789989000 corresponde a 2026-09-21T11:10:00Z e
21/09/2026 08:10:00 em America/Sao_Paulo, tanto na Java Time API quanto no
Python consultado. Isso explica a conversao local, mas nao prova a causa da
diferenca em relacao ao Profit nem comprova que o feed esteja atualizado.

Na execucao real deste diagnostico em 21/09/2026:

- Candle: raw 1789990800, Instant 2026-09-21T11:40:00Z,
  Sao Paulo 08:40:00; O=187465, H=188000, L=187460, C=187750.
- Tick: raw 1789991365, time_msc 1789991365881,
  Instant 2026-09-21T11:49:25.881Z, Sao Paulo 08:49:25.881;
  last=187595, bid=187590, ask=187595.
- O relogio local do Windows consultado logo depois indicou
  2026-09-21T11:49:26.0447614-03:00.

Ha, portanto, uma discrepancia observada entre o instante do tick segundo o
contrato UTC documentado e o relogio local: aproximadamente tres horas.
Nao foi verificada a sincronizacao do relogio Windows nem a convencao real
usada pela corretora/servidor na origem. Os dados podem estar inconsistentes
com o contrato documentado; proximidade de precos nao resolve essa ambiguidade.
O ultimo candle e o tick tambem nao coincidiram no mesmo intervalo M5 nesta
amostra. A abertura do candle nao e o horario do ultimo negocio, e as consultas
sao separadas. Nenhuma dessas observacoes autoriza somar horas ou preencher
candles. A origem da discrepancia permanece a confirmar no terminal/servidor.

Validacao: mvn -B -pl panic-scanner -am verify, 115 testes sem falhas, incluindo
5 novos testes temporais com valores fixos. O teste muda o timezone default
para UTC, Asia/Tokyo e America/Los_Angeles e o restaura em finally. Ha tambem
cobertura de regras historicas de Sao Paulo para evitar um offset fixo.
