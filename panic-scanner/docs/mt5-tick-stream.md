# Prova de streaming de ticks MT5

## Executar no IntelliJ

Execute o main de:
`br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5TickStreamDiagnostics`

Use JRE 21, classpath do modulo panic-scanner e working directory na raiz do
repositorio ou do modulo. Requer python no PATH, pacote MetaTrader5 instalado
e terminal MT5 conectado. Nao e necessario iniciar Spring ou frontend.

Com JDK 21 no PowerShell, na raiz:

```powershell
mvn -B -pl panic-scanner -am verify dependency:copy-dependencies
java -cp "panic-scanner/target/classes;panic-scanner/target/dependency/*" br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5TickStreamDiagnostics
```

## Protocolo e ciclo de vida

Mt5TickStreamClient.start(Consumer<Mt5Tick>) e BLOQUEANTE: le e entrega uma linha
por vez na thread chamadora, ate stop ou falha. O consumer recebe objetos Java,
sem acoplamento ao console. O diagnostico formata HH:mm:ss.SSS em UTC usando
Instant.ofEpochMilli(timeMsc). Nenhum timestamp e ajustado.

Cada instancia e de uso unico. start cria um unico processo `python -u`, e uma
segunda chamada a start lanca IllegalStateException. Para outra sessao, crie
outra instancia. Falhas de processo, JSON e consumer propagam para quem chamou
start; nao ha reconexao automatica nem repeticao em loop apos falha.

O Python inicializa MT5 uma unica vez, consulta WINV26 e espera 20 ms entre
consultas via Event.wait(0.02), que tambem permite acordar imediatamente ao parar.
A primeira observacao e emitida; depois so emite quando time_msc difere da
observacao emitida anteriormente. Cada linha e JSON com time, timeMsc, bid,
ask, last e volume, usando tipos Python nativos, allow_nan=False e flush=True.

stop pode ser chamado de outra thread ou pelo consumer e e idempotente. Fecha
stdin do processo; uma thread Python detecta EOF e sinaliza o loop, cujo finally
chama mt5.shutdown(). EOF tambem permite detectar o desaparecimento do Java.
O Java espera ate 3 segundos; se necessario, solicita destroyForcibly e espera
mais 2 segundos. Fecha stdout/stderr e verifica encerramento. O main instala
shutdown hook para Ctrl+C/parada normal da JVM. Encerramento forcado do SO ou
travamento de chamada nativa nao permite garantir execucao do finally Python;
nesses casos o fallback prioriza encerrar o processo.

stderr e drenado por uma thread virtual separada para nao bloquear stdout,
registrado no logger Java, com cauda limitada a 8192 caracteres para mensagem
de erro. EOF inesperado de stdout/processo, inclusive exit code zero sem stop,
e reportado como erro. Nenhum erro de inicializacao ou tick None e ignorado.
O consumer e sincrono: um consumer lento pode atrasar leituras e aplicar
backpressure; esta prova nao adiciona filas ou entrega paralela.

## Compatibilidade

Mt5ProcessClient, diagnosticos temporais, endpoint de candles e frontend nao
foram alterados nesta etapa. Mt5Tick passou a ser publico e ganhou volume;
aceita time_msc (consulta individual existente) e timeMsc (stream) via Jackson.
O construtor Java anterior de cinco campos foi preservado. mt5_reader.py apenas
acrescenta volume ao JSON de --tick para acompanhar o modelo; a consulta de
candles permanece identica.

## Limites desta prova

symbol_info_tick retorna o ULTIMO tick disponivel, nao uma fila de eventos:
https://www.mql5.com/en/docs/python_metatrader5/mt5symbolinfotick_py

Podem existir varios eventos entre polls de 20 ms, ou no mesmo milissegundo.
Esta prova pode perde-los: time_msc nao e um identificador unico de negocio.
Um novo time_msc no mesmo last e emitido; alteracoes com o mesmo time_msc podem
ser descartadas. Sem novos ticks, o processo fica ativo esperando, sem heartbeat
no stdout. Nao ha promessa de captura de todos os negocios ou latencia HFT.

Ticks tambem podem refletir apenas mudancas em bid/ask; last/volume podem
continuar sendo os do ultimo negocio. Nao e uma fita exclusiva de negocios:
https://www.mql5.com/en/docs/constants/structures/mqltick

## Testes e evidencia

```powershell
mvn -B -pl panic-scanner -am verify
python -m unittest discover -s panic-scanner/scripts -p test_mt5_tick_stream.py -v
```

135 testes Java aprovados (16 novos) e 4 testes Python aprovados. Os testes nao
precisam de MT5: parsing e lifecycle Java usam processo JVM auxiliar; Python usa
Mock/Event simulado, sem sleeps. Cobrem ticks consecutivos com mesmo last,
parsing invalido, erros, start duplicado, callback com erro e parada com EOF.

Amostra real de 21/09/2026: 10 ticks recebidos pelo mesmo Python PID 11472,
entre 12:38:51.611 e 12:38:53.547 UTC exibido. Os tres primeiros mantiveram
last=187585, com time_msc diferentes. O processo nao estava mais vivo apos stop.
O diagnostico temporal anterior tambem foi reexecutado com sucesso, recebendo
1000 candles e o tick individual. A discrepancia temporal anterior continua
inalterada; este streaming nao tenta corrigi-la.
