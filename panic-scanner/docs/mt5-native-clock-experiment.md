# Experimento de relógios nativos MQL5 versus API Python

## Escopo e artefatos

- `scripts/mql5/WinZ26NativeClockProbe.mq5`: Script MQL5 OnStart, somente leitura,
  polling SymbolInfoTick(WINZ26) a 20 ms por padrão, 10 segundos, sem ordens,
  SymbolSelect, assinatura DOM ou mudanças de configuração.
- `scripts/mt5_native_clock_probe.py`: reutiliza diagnóstico Python existente e
  cursor incremental de todos os ticks; acrescenta versão e terminal/data_path
  na mesma inicialização. TimeCurrent/TimeTradeServer/TimeGMT não estão disponíveis
  como funções equivalentes da API Python e são declarados indisponíveis.
- `scripts/mt5_native_clock_compare.py`: compara TSV nativo e JSON Python offline,
  conferindo SHA-256 dos arquivos antes/depois e recusando contrato divergente.

Nada é alterado em Java, streams operacionais, estratégias, históricos, relógios
ou configuração de produção. Não se aplica correção de 3 horas ou residual.

## Execução manual simultânea, durante o pregão

1. No terminal Clear desejado, confirme que WINZ26 já está disponível no Market
   Watch. Use esse mesmo terminal para ambos os scripts; não use Strategy Tester.
2. Abra Arquivo > Abrir Pasta de Dados. Copie manualmente
   `scripts/mql5/WinZ26NativeClockProbe.mq5` para MQL5/Scripts dessa pasta.
   Abra no MetaEditor e compile com F7. A compilação MQL5 não foi executada neste
   ambiente: MetaEditor não foi encontrado no PATH. Verifique erros antes de rodar.
3. Prepare o comando Python abaixo, com arquivo de saída novo. Inicie o Python
   e imediatamente arraste WinZ26NativeClockProbe para um gráfico no terminal.
   A ordem inversa também funciona se houver sobreposição. Ambos duram até 10 s
   de observação; w32tm/preparação Python ocorrem antes da janela de ticks.
   Se a preparação consumir a sobreposição, repita manualmente com novos nomes.
4. O script MQL5 grava `WINZ26-native-clock-<contador>.tsv` em MQL5/Files e imprime
   o caminho no log Experts. O nome usa GetTickCount64; se existir, recusa sobrescrever.
   Não altera parâmetros do gráfico nem habilita negociação automática.
5. Confirme que server e terminal_data_path nativos coincidem com mt5.server e
   native_comparison_metadata.terminal.data_path do Python. initialize() usa a
   seleção automática existente, portanto essa verificação é obrigatória antes
   de interpretar o cruzamento. Divergência de terminal invalida a comparação forte.
6. Copie o TSV resultante para dom-captures, preservando o original, e execute
   o comparador. Ele exige saída nova e não modifica nenhuma das entradas.

Da raiz do repositório:

```powershell
python panic-scanner/scripts/mt5_native_clock_probe.py --seconds 10 --output panic-scanner/dom-captures/WINZ26-python-native-check.json
python panic-scanner/scripts/mt5_native_clock_compare.py panic-scanner/dom-captures/WINZ26-native-clock-<contador>.tsv panic-scanner/dom-captures/WINZ26-python-native-check.json --output panic-scanner/dom-captures/WINZ26-native-comparison.json
```

Substitua <contador> pelo nome real. Diretorios devem existir. O primeiro comando
é live, exclusivamente manual; o segundo é offline. Chamadas MT5 síncronas podem
ultrapassar o prazo nominal. Não foi executado nenhum dos capturadores nesta tarefa.

## Campos e significado

MQL5: time/time_msc originais, bid/ask/last, volume/volume_real, flags, símbolo,
servidor, data_path, build, sequência, início/fim monotônico e TimeGMT antes/depois
da consulta. Também TimeCurrent, TimeTradeServer, TimeLocal amostrados após consulta.
Valores datetime são inteiros brutos; não recebem sufixo UTC artificial.
Arquivo TSV UTF-8 com cabeçalho; números double são escritos com 16 casas decimais.

Python: mesmos campos disponíveis de tick, metadados de terminal, horário UTC/local
com offset explícito antes/depois de cada lote, cursor e diferenças local/mercado.
Preserva quotes, flags, volumes e ticks duplicados legítimos. Os campos de relógios
MQL5 indisponíveis não são substituídos pelo horário do Windows.

TimeCurrent é a última hora de servidor conhecida para algum símbolo do Market
Watch, não necessariamente WINZ26 neste script. TimeTradeServer é uma estimativa
calculada no terminal e depende do relógio do computador. TimeGMT é calculado a
partir do horário local/configuração do computador; não é referência NTP externa.
Essas três funções têm resolução de segundos, insuficiente para validar 709 ms.
Referências oficiais:
https://www.mql5.com/en/docs/dateandtime/timecurrent
https://www.mql5.com/en/docs/dateandtime/timetradeserver
https://www.mql5.com/en/docs/dateandtime/timegmt

## Comparação sem presumir o mesmo negócio

O MQL5 consulta último tick e pode saltar eventos. O Python recupera um lote
incremental. Chamadas próximas não demonstram que observaram o mesmo evento.

Chave de igualdade: time, time_msc, bid, ask, last, volume, volume_real e flags.
O comparador conta grupos de payload exatamente iguais e suas multiplicidades,
sem juntar por posição, proximidade de consulta ou remover observações repetidas.
Mesmo igualdade completa não comprova identidade de negócio individual, já que
podem existir negócios idênticos no mesmo ms. Nenhum lado tem identificador de
negócio da bolsa. Falta de igualdade não comprova transformação no Python.

Se preço/volume/flags coincidirem, mas timestamps divergirem, são listados apenas
candidatos quando houver um único timestamp distinto de cada lado para esse
fingerprint. Essa unicidade no recorte não prova que sejam o mesmo tick. Não se
usa esse caso para autorizar shift. Períodos sem sobreposição podem dar zero matches.

Se vários payloads exatos forem observados no mesmo terminal, os dados sustentam
que a diferença em relação ao UTC já existe no tick nativo nesses casos, em vez
de surgir na serialização Python. Ainda não identificam se servidor/feed codifica
hora civil, relógio de origem está deslocado ou timestamp tem outra convenção.
Diferença de relógio permanece distinta de latência de entrega comprovada.

## Validação executada

```powershell
python -m unittest discover -s panic-scanner/scripts -p "test_mt5_native_clock.py" -v
python -m unittest discover -s panic-scanner/scripts -p "test_mt5_clock_probe.py" -q
```

11 testes Python passaram: 5 novos (payloads iguais/repetidos, ticks diferentes,
candidatos por timestamp, ambiguidade, contrato/terminal, integridade e sessão
única) e 6 do diagnóstico reutilizado. --help de ambos os scripts foi verificado.
MQL5 não foi compilado/executado aqui; nenhum resultado nativo foi fabricado.
