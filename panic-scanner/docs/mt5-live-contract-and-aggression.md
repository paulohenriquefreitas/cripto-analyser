# Contrato operacional e agressão MT5 — correção de 21/10/2026

## Classificação restrita aos dados verificados

Python `mt5_trade_stream.aggressor_side` e Java `Mt5AggressorMapper.side` usam a
mesma política. Considerando a máscara oficial `126 = 2|4|8|16|32|64`:

1. Flags negativas ou sem LAST/VOLUME (`flags & 24 == 0`) ficam AMBIGUOUS.
2. Calcular `extra = flags & ~126`. Somente `0`, `1024` ou `1280` são aceitos.
   `1280=1024+256` foi observado em capturas anteriores; isso **não** permite
   `256` isoladamente nem outros bits/combinações.
3. BUY exclusivo (`32`) → BUY; SELL exclusivo (`64`) → SELL.
4. Ambos ou nenhum → AMBIGUOUS. Nenhuma inferência por preço, DOM ou volume.

Não se atribui significado aos bits extras. O `flags` original continua no NDJSON
Python ao lado de `side`, `cursorOrdinal` e `volumeSource=MT5_VOLUME_REAL`.
Preço/volume inválidos mantêm a política existente; volume inválido não vira
contrato fictício. Java ainda recusa proveniência inválida e divergência de lado
entre a mensagem Python e sua própria classificação. Os dois lados precisam
ser atualizados juntos e os processos antigos precisam ser encerrados.

## Configuração única do contrato ao vivo

Em `application.properties`:

```properties
mt5.symbol=${MT5_SYMBOL:WINZ26}
```

`Mt5Symbol` é imutável durante uma instância da aplicação. O bean Spring recebe
essa propriedade; clientes standalone leem o mesmo arquivo de recursos. Java
exporta o símbolo selecionado como `MT5_SYMBOL` para cada subprocesso Python.
Scripts Python standalone usam essa variável ou leem o mesmo padrão do arquivo
de propriedades. Não existe um segundo padrão WINV26 nos scripts de captura.

Configuração efetiva nesta revisão: **WINZ26**. Para configurar outro contrato,
definir `MT5_SYMBOL` antes de iniciar o backend; scripts standalone devem usar o
mesmo ambiente. Reiniciar o backend e recarregar a tela. Não há troca dinâmica
de contrato dentro de uma sessão ativa.

Estão coordenados T&T, quote stream, candles REST, validações de mensagens,
monitor de fluxo, metadados do observador de estrutura/SMA, perfil e tela ao vivo.
O monitor de fluxo reconhece WINZ26 como contrato com dados verificados, mantendo
todos os limiares e fórmulas existentes. Configurações de replay histórico,
datasets antigos, mock e estratégias não foram migradas de contrato.

## Pré-validação e isolamento

Antes de entregar dados ao vivo, Python exige:

- Inicialização MT5 e terminal conectado.
- `symbol_select` bem-sucedido e `symbol_info` com nome exato.
- Símbolo não customizado, vencimento futuro finito e modo não desabilitado.
- Para os streams, tick com timestamp positivo e último preço positivo finito.

Não há fallback automático para contrato vencido, alias contínuo ou outro
símbolo. Falha impede dados/status de sucesso na captura. Leitura histórica com
intervalo explícito continua permitindo contratos vencidos. Seleção do símbolo
no Market Watch é a única seleção efetuada; não há ordens.

O checkpoint Java contém **símbolo + cursor**. Outra configuração de símbolo
descarta o checkpoint, voltando ao warm-up normal do contrato novo. O processo
Java remove variáveis de cursor herdadas antes de configurar uma retomada.
Python também só aceita cursor de ambiente se `MT5_TRADE_CURSOR_SYMBOL` coincide
com o símbolo da sessão. A filtragem/consulta de negócios continua inalterada.

Reiniciar cria nova sessão de processo, monitores, séries e perfil em memória.
`Mt5VolumeProfileService` rejeita negócios de outro símbolo; o motor de cálculo
`VolumeProfile` não foi alterado. Não há mistura nem migração de volume de um
contrato para outro. Reconexão **do mesmo contrato** mantém o comportamento de
retomada existente e as falhas de integridade persistentes.

`GET /api/mt5/config` informa o símbolo configurado, sem alegar que está conectado.
O frontend lê essa seleção antes de criar a sessão do gráfico. WebSocket e perfil
recusam símbolo divergente; pedidos de candles ficam vinculados ao símbolo e
retornam HTTP 409 se o backend reiniciou com outro contrato. É necessário
recarregar a página para iniciar outra sessão, evitando reuso de candles antigos.

## Evidência e testes

Captura local intacta `WINZ26-joint-probe-30s.ndjson`, 566 negócios:

| Flags | Negócios | Lado | Volume |
|---|---:|---|---:|
| 1080 | 206 | BUY | 1444 |
| 1112 | 340 | SELL | 3188 |
| 1144 | 20 | AMBIGUOUS | 53 |

Total **4685**, delta conhecido **−1744**, POC **208350**, 12 níveis. O teste Python
verifica a saída de protocolo e flags preservadas; o teste Java confirma os
mesmos totais recebidos pelo motor de perfil sem mudar sua lógica. A captura
continua parcial. Os testes locais da amostra são pulados se o arquivo real
não estiver presente; nenhuma captura foi inventada ou adicionada ao Git.

Regressões cobrem 56, 88, 1080, 1112, 1144, 1336, 1368, flags sem trade,
ambos/nenhum agressor e máscaras não verificadas, incluindo 256 isoladamente e
2048. Também cobrem configuração Spring, checkpoint entre contratos, recusa de
símbolo estrangeiro, perfil novo independente, pinning REST/WebSocket,
pré-validação do terminal e indisponibilidade/vencimento.

Uma consulta real somente leitura foi tentada nesta revisão com WINZ26. O MT5
retornou **−10005 / IPC timeout** na inicialização. Portanto, disponibilidade
atual do terminal não foi confirmada; streams não foram ativados para validar
mercado ao vivo. O símbolo configurado não equivale a feed operacional confirmado.
Depois de resolver a comunicação com o terminal, a própria captura deve passar
pela pré-validação antes de entregar dados.

## Arquivos desta correção

- Configuração: `src/main/resources/application.properties`.
- Python em `scripts/`: `mt5_live_symbol.py` (novo), `mt5_trade_stream.py`,
  `mt5_tick_stream.py`, `mt5_reader.py`, `test_mt5_live_contract.py` (novo),
  `test_mt5_trade_stream.py`, `test_mt5_tick_stream.py`, `test_mt5_reader.py`.
- Java em `infrastructure/marketdata/`: `Mt5Symbol.java` (novo),
  `Mt5AggressorMapper.java`, `Mt5TradeStreamClient.java`,
  `Mt5TradeMonitoringLifecycle.java`, `Mt5TickStreamClient.java`,
  `Mt5TickStreamLifecycle.java`, `Mt5ProcessClient.java`,
  `Mt5MonitoringFlowService.java`, `Mt5VolumeProfileService.java`,
  `MarketStructureLiveObserver.java`, `Mt5TradeFlowDiagnostics.java`,
  `Mt5TradeIntegrityValidation.java`.
- Outros Java: `config/Mt5Config.java`, `api/Mt5LiveConfigController.java` (novo),
  `api/Mt5CandlesController.java`, `api/Mt5TickWebSocketHandler.java`,
  `infrastructure/ta4j/Ta4jMt5Sma9Adapter.java`,
  `application/win/flow/MonitoringFlowEngine.java` (somente elegibilidade do símbolo).
- Testes Java: `Mt5AggressorMapperTest.java`, `Mt5LiveContractTest.java` (novos),
  `VolumeProfileRealCaptureTest.java`, `Mt5TickWebSocketHandlerTest.java`,
  `Mt5MonitoringFlowServiceTest.java`.
- Frontend em `src/features/win/`: `api/mt5Api.ts`, `api/mt5LiveConnection.ts`,
  `components/Mt5LiveQuote.tsx`, `components/Mt5CandlestickChart.tsx`,
  `components/WinM5MonitoredChart.tsx`, `components/VolumeProfilePanel.tsx`,
  `pages/WinMt5Page.tsx`; teste `tests/mt5LiveConnection.test.mjs`.
- Documentação: este arquivo e `volume-profile.md`.

Validação concluída: **127 testes Java**, **49 testes Python** e **82 testes
frontend** aprovados; compilação Java e TypeScript aprovadas. A consulta nativa
ao terminal foi separada dos testes simulados e não confirmou disponibilidade,
conforme descrito acima. A captura real manteve SHA-256
`6a289aeefa5f95ad9c339b10c705ae7b8046cd4d7703ee69d606f86c8e1fe968`.

Detector de absorção, captura DOM, algoritmos de cursor/consulta, motor do
Volume Profile, regras/limiares de estratégias e execução de ordens não mudaram.
