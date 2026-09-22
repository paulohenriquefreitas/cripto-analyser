# Cotacao MT5 ao vivo no WIN M5

Pagina: http://localhost:5173/win-m5
WebSocket nativo: ws://localhost:8080/ws/mt5/ticks
REST de candles permanece GET /api/mt5/candles.

## Iniciar

Com JDK 21, python no PATH, MetaTrader5 instalado no Python e terminal conectado,
execute na raiz do repositorio:

```powershell
mvn -B -pl panic-scanner -am verify dependency:copy-dependencies
java -cp "panic-scanner/target/classes;panic-scanner/target/dependency/*" br.com.bauzin.market.panic.PanicScannerApplication
```

No IntelliJ: execute PanicScannerApplication com JRE 21, classpath panic-scanner
e working directory na raiz do repositorio ou do modulo. Nao execute tambem o
main standalone Mt5TickStreamDiagnostics: o Spring ja gerencia o stream.

Em outro PowerShell:

```powershell
cd frontend
npm.cmd install
npm.cmd run dev -- --host localhost --strictPort
```

Se npm nao estiver no PATH desta maquina, antes desses comandos:

```powershell
$env:PATH = "$env:LOCALAPPDATA\JetBrains\IntelliJIdea2026.2\acp-agents\.runtimes\node\24.13.0;$env:PATH"
```

O endereco WebSocket e derivado de VITE_API_URL (padrao http://localhost:8080):
http vira ws; https vira wss; o path /ws/mt5/ticks e acrescentado. Origens locais
localhost/127.0.0.1 nas portas 5173 e 4173 sao permitidas pelo servidor.

## Lifecycle compartilhado

Mt5TickStreamLifecycle implementa SmartLifecycle e inicia, uma vez por contexto
Spring, uma thread virtual que chama o start bloqueante do Mt5TickStreamClient
existente. Abrir ou reconectar navegadores nao cria outro stream Python.
Ao fechar o contexto Spring, chama client.stop e aguarda o worker. O protocolo
stdin EOF e o fallback de encerramento do cliente existente foram preservados.

Para executar um contexto sem feed real, use --mt5.tick-stream.enabled=false.
Por padrao esta habilitado. Os testes de lifecycle usam um processo JVM auxiliar
sem MT5; os testes de broadcast usam sessoes WebSocket simuladas.

Falha de MT5/Python e registrada no backend e publicada como status indisponivel.
O contexto Spring e o REST continuam ativos. Nao existe reconexao automatica ao
MT5 nesta etapa: corrija a causa e reinicie o backend. O frontend reconecta ao
WebSocket, nao reinicia o Python.

## Mensagens e concorrencia

Tick:

```json
{"type":"tick","symbol":"WINV26","time":1789994563,"timeMsc":1789994563184,"last":187585.0,"bid":187580.0,"ask":187585.0,"volume":4.0}
```

Os numeros acima sao ilustrativos. Na execucao real todos os valores numericos
vem do Mt5Tick; time/timeMsc sao copiados sem offsets. O simbolo e WINV26, o mesmo
contrato fixo consultado pelo script existente. O DTO WebSocket usa timeMsc sem
alterar a serializacao time_msc usada pelo diagnostico anterior.

Status:

```json
{"type":"status","available":false}
```

O handler guarda a ultima cotacao em memoria apenas para apresentar a conexoes
novas. A disponibilidade se torna verdadeira ao receber tick; e falsa ao ocorrer
falha/encerramento. Falha limpa a cotacao armazenada. Nao ha deteccao por timeout
de mercado parado, e nao se presume que todo tick seja um novo negocio.

As sessoes ficam em ConcurrentHashMap. Cada sessao tem uma thread virtual de
escrita e uma fila limitada a 64 mensagens. A thread do Python faz somente
serializacao e offer, sem envio de rede. Um cliente lento com fila cheia e
removido/fechado em outra thread; os demais continuam recebendo. As mensagens
sao enviadas sequencialmente por sessao, nunca por writers concorrentes.
Nao ha garantia de replay/entrega completa a clientes lentos.

## Frontend

Mt5LiveQuote possui seu proprio estado: apenas ultimo/bid/ask/status/horario
renderizam a cada tick. Nao atualiza estado na WinMt5Page, React Query ou grafico.
Nao faz requests HTTP por tick e nao chama setData/update no candlestick.

AO VIVO aparece apos tick valido, AGUARDANDO TICK na conexao inicial,
FEED MT5 INDISPONIVEL quando o backend reporta falha, e DESCONECTADO quando a
conexao cai. Nessa ultima situacao tenta reconectar apos 3 segundos. Desmontar o
componente cancela o timer, remove listeners e fecha o socket. Mensagens invalidas
sao tratadas, fechando a conexao e iniciando o mesmo ciclo de reconexao.

A hora exibida usa Intl.DateTimeFormat com timeZone UTC e timeMsc diretamente
como Unix milliseconds. Nao depende do timezone do navegador e nao corrige a
peculiaridade temporal do feed. O grafico de 100 candles permanece inalterado.

## Validacao

- Maven verify: 141 testes aprovados (6 novos, sem MT5 real).
- npm.cmd test: 4 testes com node:test e timers simulados, sem novas dependencias.
  Requer Node 24 (usado neste ambiente); testa mensagens, timestamps, URLs,
  reconexao, cleanup e JSON invalido.
- npm.cmd run build e npm.cmd run lint aprovados. Vite ainda avisa sobre bundle
  acima de 500 kB; nenhuma refatoracao global foi feita para isso.
- Edge headless com duas abas reais: ticks compartilhados, precos distintos
  188130, 188135, 188125, 188115, 188120 e 188110; Python PID 13308 permaneceu
  unico durante conexoes e reconexao. Nenhum request extra de candles durante
  ticks/reconexao e o canvas original permaneceu o mesmo.
- Navegacao para outra pagina fechou o socket; nenhuma excecao JS nao tratada.

Arquivos adicionados: Mt5WebSocketConfig, Mt5TickWebSocketHandler,
Mt5TickStreamLifecycle, Mt5TickWebSocketHandlerTest, Mt5TickStreamLifecycleTest,
mt5LiveConnection.ts, Mt5LiveQuote.tsx, mt5LiveConnection.test.mjs e este documento.
Arquivos modificados: panic-scanner/pom.xml, frontend/package.json e WinMt5Page.tsx.

Referencia Spring: https://docs.spring.io/spring-framework/reference/web/websocket/server.html
