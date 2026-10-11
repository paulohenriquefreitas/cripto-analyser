# Diagnóstico da consulta experimental de 08/10/2026

Investigação por consultas HTTP locais e logpoints não suspensivos no IntelliJ. Nenhum critério, score, flag global ou configuração persistente foi alterado. As sessões auxiliares e os logpoints do agente foram removidos ao terminar.

## Evidências

- Tela original: 59 tentativas, nenhum alerta e falha PROVIDER_UNAVAILABLE em GGBR4. Não há nesta investigação o detalhe da exceção daquela execução de 15:42; não é possível identificar seu erro HTTP ou timeout específico.
- Nova consulta na aplicação existente, porta 8080, às 17:07: 788 ativos descobertos, 377 elegíveis, 65 tentativas, zero alertas e interrupção em ALOS3 por PROVIDER_UNAVAILABLE. Os contadores tradicionais incluem 7 QUALIFIED e 50 WATCH; QUALIFIED não implica alerta experimental.
- GGBR4 respondeu posteriormente na mesma aplicação: candle 2026-10-07, WATCH, Momentum Score 33. A leitura direta de D1 pelo MT5 também teve sucesso.
- Uma sessão auxiliar com a configuração PanicScannerApplication, sem overrides do provider, recebeu BRAPI 401/MISSING_TOKEN no primeiro ticker (COGN3). Esse é um ambiente de reprodução diferente; não explica a falha original de GGBR4.
- Reprodução com a configuração panic e overrides temporários `--server.port=8082 --panic-scanner.historical.provider=mt5`: 367 tentativas de 377 elegíveis, 28 falhas de histórico insuficiente, uma falha de provider e dois alertas retornados.

| Ativo | Candle fechado | Padrão | Status tradicional | Momentum | RSI9 | ADX14 |
| --- | --- | --- | --- | --- | --- | --- |
| SLCE3 | 2026-10-07 | EARLY_REVERSAL | WATCH | 70 | 59.8811 | 45.8846 |
| BBSE3 | 2026-10-07 | ADX_RECOVERY | WATCH | 68 | 55.1028 | 18.3597 |

O debugger observou `signal=NONE` em ativos consultados antes da interrupção inicial. Por exemplo, GGBR4 passou em liquidez, RSI e extensão, mas falhou nas confirmações dos três padrões. COGN3 e MOVI3 também falharam nos gates de RSI e extensão. Foram observados candles de 2026-10-07 e, em ativos menos negociados, sessões anteriores. O endpoint experimental ativa a análise por requisição, independentemente da flag global.

## Falha comprovada na reprodução MT5

Logpoint no catch de Mt5MarketDataClient:

```text
BMIN4 cause=Python retornou exit code 1: Erro ao consultar MT5: Sem candles para BMIN4 D1: (-1, 'Terminal: Call failed')
BMIN4 code=PROVIDER_UNAVAILABLE eligible=377 attempted=367
```

Mt5MarketDataClient converte qualquer IOException em PROVIDER_UNAVAILABLE. ScanB3MarketUseCase considera esse código falha geral e interrompe o loop. O erro observado não estabelece que todos os símbolos estão indisponíveis; dez ativos ficaram sem tentativa. A reprodução demonstra esse caminho, mas não recupera a exceção original de GGBR4.

## Conclusões e correções recomendadas

1. A análise experimental produz alertas e seus filtros estavam ativos. Zero alertas numa consulta interrompida não descreve todo o universo. Os dois alertas da reprodução MT5 não provam o resultado que a fonte original retornaria numa consulta completa.
2. Classificar separadamente falhas por símbolo, ausência de histórico e falhas globais de conexão/autenticação. Preservar a causa no diagnóstico interno, mantendo mensagens públicas sem dados sensíveis.
3. Aplicar tentativas limitadas para erros transitórios e prosseguir após erros isolados; manter interrupção para indisponibilidade global comprovada, autenticação ou limites de requisição.
4. Exibir universo elegível, tentativas, sucessos, falhas e ativos não consultados. Marcar explicitamente resultado parcial.
5. Disponibilizar resumo de NONE/INCONCLUSIVE e checks reprovados, sem flexibilizar thresholds experimentais, QUALIFIED ou scores.

Respostas completas das reproduções estão na raiz do workspace: experimental-diagnosis-original-scan.json e experimental-diagnosis-mt5-scan.json. O primeiro ensaio com BRAPI sem token está em experimental-diagnosis-scan.json.
