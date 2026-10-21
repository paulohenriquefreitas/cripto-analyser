# Prova de conceito DOM WINZ26 / Clear

Execução manual, independente dos streams, estratégias e aplicação Java. Reutiliza
`mt5.initialize()` sem parâmetros, como `mt5_reader.py` e `mt5_tick_stream.py`:
usa o terminal MT5 instalado e sua sessão autenticada. `application.properties`
não define credenciais MT5 e não foi alterado. Não envia ordens nem sinais.

No Windows, use o Python que já contém o pacote MetaTrader5 e mantenha o terminal
aberto, conectado à Clear e autorizado a receber dados do WINZ26. O script registra
company/server (sem número da conta) e interrompe se não identificar Clear nesses
campos; essa verificação textual não comprova o entitlement de dados da corretora.

Da raiz do repositório:

```powershell
python panic-scanner/scripts/mt5_dom_probe.py --seconds 60 --interval-ms 100
```

Opcional: `--output caminho/arquivo.ndjson` (recusa sobrescrever arquivo existente).
O padrão salva em `panic-scanner/dom-captures/WINZ26-<UTC>.ndjson`.
O resumo é a última linha do NDJSON e também aparece no console. Ctrl+C mantém
o resumo e executa a limpeza. Encerramento forçado do processo não garante finally.

São registrados symbol_info, resultado de symbol_select e symbol_info_tick,
assinatura, snapshots e resumo. Cada snapshot contém início/fim UTC do relógio
local em milissegundos, latência de market_book_get, campos originais type/price/
volume/volume_dbl na ordem recebida, contagem de entradas, BBO e spread em unidades
de preço. Falha (None/exceção) é distinta de tupla vazia. Nenhuma repetição é removida.
A primeira leitura bem sucedida não conta como mudança; comparações seguintes
incluem vazios e comparam todos os campos e a ordem, pulando consultas com falha.
O resumo agrega contagens, mínimo/máximo/média de níveis, spread e latência,
anomalias e resultado da liberação. Anomalias são contadas por ocorrência.

BOOK_TYPE_SELL=1 e BUY=2 representam ofertas limitadas. SELL_MARKET=3 e
BUY_MARKET=4 são preservados, mas não participam do melhor preço. Nenhum tipo
representa inferência de agressão executada. A ordenação esperada é decrescente
em cada lado (vendas da pior para a melhor), conforme exemplo oficial MT5.
A análise calcula o BBO por min/max sem reordenar ou corrigir o arquivo original.
Detecta preço inválido ou fora do tick, quantidades inválidas/inconsistentes,
níveis duplicados, tipos desconhecidos, desordenação, lado ausente e spread
travado/cruzado. volume inteiro é comparado à parte inteira de volume_dbl;
a parte fracionária deste último é preservada. Preços de ordens a mercado não
são validados como preços limitados. NaN/Infinity são codificados explicitamente
como objetos {"non_finite": "nan"/"inf"/"-inf"}, mantendo JSON válido.

`observed` significa apenas que houve livro não vazio; examine anomalias e erros
para julgar qualidade. `unavailable_in_capture` significa que nenhum livro não
vazio foi recebido nessa execução, não uma conclusão permanente sobre a Clear.
Saída 1 indica ausência observada de DOM ou erros de execução/limpeza. Um livro
estático não prova falta de atualização: polling não observa todas as mudanças,
não mede latência da bolsa nem fornece timestamp de origem do DOM. O intervalo
é alvo entre inícios; consulta lenta e gravação podem alongá-lo. Não se pode
confirmar disponibilidade real sem executar manualmente durante o pregão.

Testes sintéticos, sem importar MetaTrader5 ou abrir conexão:

```powershell
python -m unittest discover -s panic-scanner/scripts -p test_mt5_dom_probe.py -v
```

Referências: https://www.mql5.com/en/docs/python_metatrader5/mt5marketbookget_py
 e https://www.mql5.com/en/docs/constants/tradingconstants/enum_book_type
