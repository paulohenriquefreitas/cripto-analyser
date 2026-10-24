# Observação integrada local

Na raiz do repositório, com backend em execução e Python no PATH:

```powershell
python -B .\panic-scanner\scripts\observe_integrated_live.py --symbol WINZ26 --seconds 1800 --interval 5
```

Não exige pacotes adicionais. Funciona independentemente do Codex. Não inicia MT5,
não reinicia processos, não altera configuração e não envia ordens. O backend deve
estar previamente ligado ao contrato desejado. Divergência de símbolo é um erro.
`--base-url` aceita somente backend HTTP local (`localhost` ou `127.0.0.1`).

Cada execução cria um diretório único em `panic-scanner/dom-captures/integrated-<UTC>`:

- `observations.ndjson`: respostas HTTP, mensagens WebSocket, mudanças do perfil,
  estados FLOW, problemas, comparações de relógio e barras derivadas. `sequence`
  identifica a ordem local de gravação; `receivedUtc` e `elapsedSeconds` registram
  recebimento local e tempo monotônico. `rawText` preserva o JSON original; valores
  decimais no objeto HTTP `payload` são strings para evitar perda de precisão.
- `report.txt`: início/fim, duração, contagens, perfil inicial/final, problemas e
  fontes indisponíveis. Ctrl+C encerra e produz relatório, sem apagar registros.

APIs confirmadas no código existente:

| Fonte | API | Limites |
|---|---|---|
| Contrato | GET `/api/mt5/config` | Consulta repetida para detectar mudança |
| Volume Profile | GET `/api/mt5/volume-profile` | Snapshot parcial; HTTP a cada 5 s |
| LAST e T&T agregado | `/ws/mt5/ticks` | Mensagens `tick`, `FLOW`, `status`; não há negócios individuais |
| Possível absorção | `FLOW.windows[].alert` | Alerta descritivo, não transições do detector |
| DOM e detector de absorção | Indisponíveis | Não há API de leitura correspondente |
| M1/M5 oficiais | Não consultados | `/api/mt5/candles` inicia processo MT5 próprio; violaria a restrição de conexão única |

O observador cria OHLC M1/M5 **derivados de LAST recebido**, sem volume e sempre
parciais. Não substituem candles oficiais. Não soma `tick.volume`: atualizações de
LAST não são uma lista deduplicável de negócios executados. Lacunas não recebem
preenchimento artificial.

Reutiliza o validador do Volume Profile para conferir somas BUY/SELL/AMBIGUOUS,
delta, percentuais, grade, POC/VAH/VAL, cobertura e integridade. Compara snapshots
para detectar regressão de volumes/níveis/timestamps, resets e desaparecimento de
falhas. Verifica delta/volumes e registra mudanças de qualidade/alerta FLOW.

Registra falta de atualização após 15 s (`--stale-seconds`), desconexão WebSocket,
falhas HTTP/schema, mudança de sessão e desvio entre timestamps brutos de LAST e
perfil. Comparações são assíncronas; desvio é diagnóstico, não prova de perda.
Reconexão fica registrada como lacuna, sem presumir recuperação. A diferença entre
horário local e timestamp bruto é registrada sem correção automática de fuso.
Referência temporal não verificada e cobertura parcial permanecem explícitas.

O teste não certifica continuidade tick a tick, recuperação de cursor, duplicatas
na fonte ou pregão completo, pois essas entradas não são expostas pelas APIs.
Retorno 1 indica erro detectado ou interrupção; avisos isolados não causam retorno 1.

Testes locais, incluindo servidor HTTP/WebSocket simulado, sem consultar MT5:

```powershell
python -B -m unittest discover -s .\panic-scanner\scripts -p test_observe_integrated_live.py -v
```
