# Auditoria das lacunas DOM — WINZ26, 19/10/2026

A lacuna de **12,388305 s está EXPLICADA por desconexão/reconexão do terminal**,
associada a uma troca automática de ponto de acesso. A de **135,277953 s fica
INDETERMINADA quanto à causa integral**: há desconexão comprovada no seu interior,
mas ela só foi registrada 31–32 s depois do início da ausência de snapshots.
Não há bloqueio contínuo do EA durante nenhuma delas. A continuidade do DOM não
está demonstrada nesses períodos; o histórico de ticks recebido depois permite
análise retrospectiva de negócios, com as limitações descritas abaixo.

Fonte: `WINZ26-event-joint-3944703-78015786034571.ndjson`, 54.857.058 bytes.
SHA-256: `c48db00a29f1cd741a1295c5ee873a28be551351149ae6a78681ec727a19d3de`.
Métricas: [JSON reproduzível](../dom-captures/WINZ26-3944703-dom-gaps.json).
Analisador: [mt5_dom_gap_audit.py](../scripts/mt5_dom_gap_audit.py).
Nenhum arquivo original, EA, Java ou módulo operacional foi alterado. Não houve coleta.

## Delimitação e relógios

Os limites são os inícios das consultas `MarketBookGet` em snapshots consecutivos.
Não são instantes conhecidos da interrupção do feed. Contagens de execuções usam
o intervalo **aberto** entre esses limites: início de consulta para book/tick_batch,
início do handler para OnTick/OnTimer. O snapshot final não entra na contagem,
mesmo quando o handler começou 1 us antes de sua consulta.

`GetMicrosecondCount` é relativo à instância EA; `summary.start_us=2875` marca
o início da janela. `TimeLocal` e `TimeGMT` têm resolução de segundos no arquivo.
A diferença observada é 10.800 s, compatível com local UTC−03. Os horários locais
abaixo representam **o segundo registrado**, isto é, `[segundo, segundo+1s)`.
Não se inventa fração de segundo combinando relógios diferentes.

| Limite | Sequência | Monotônico da instância, us | Desde início da janela, s | Local, 19/10/2026 | GMT/UTC, 19/10/2026 | TimeCurrent, relógio servidor |
|---|---:|---:|---:|---|---|---|
| G1 antes | 18421 | 189165376 | 189,162501 | 16:19:02 | 19:19:02Z | 16:19:02 |
| G1 depois | 26351 | 324443329 | 324,440454 | 16:21:18 | 19:21:18Z | 16:21:17 |
| G2 antes | 29004 | 356795789 | 356,792914 | 16:21:50 | 19:21:50Z | 16:21:50 |
| G2 depois | 29750 | 369184094 | 369,181219 | 16:22:02 | 19:22:02Z | 16:22:02 |

DOM tem `dom_market_time_msc=null`: **não existe timestamp de mercado do snapshot**.
TimeCurrent é hora da última cotação conhecida pelo terminal, não do livro nem
uma prova de chegada de dados novos. Os ticks mantêm `time/time_msc` originais
do servidor; sua codificação numérica não foi convertida para UTC nesta análise.

## Comparação dos intervalos

| Métrica no intervalo aberto | G1 | G2 |
|---|---:|---:|
| Duração | 135,277953 s | 12,388305 s |
| Classificação da causa integral | INDETERMINADA | EXPLICADA |
| Snapshots / OnBookEvent registrados no interior | 0 | 0 |
| Execuções OnTick | 2 | 10 |
| Execuções OnTimer | 6.707 | 619 |
| Maior intervalo de timer, incluindo bordas | 52,815 ms | 46,259 ms |
| Consultas CopyTicks | 1.220 | 116 |
| CopyTicks mediana / P99 / máximo | 7 / 23 / 122 us | 7 / 20 / 21 us |
| Ticks novos aceitos nesse período de aquisição | 3 | 12 |
| Negócios por flags / cotações | 3 / 0 | 12 / 0 |
| Consultas sem linhas novas, mas com prefixo | 1.219 | 110 |
| Respostas brutas vazias / erros registrados | 0 / 0 | 0 / 0 |
| Maior duração do handler OnTimer | 15,938 ms | 0,938 ms |
| Maior espera para iniciar escrita de registro adquirido no intervalo | 1.603,818 ms | 46,183 ms |

Os dois OnTick de G1 ocorreram em `189165463` e `189165511` us, imediatamente
após o snapshot inicial. Os dez de G2 ficaram entre `356818646` e `357044395` us.
Eles não se distribuíram ao longo de toda a lacuna. O cursor em G1 passou de
`1792426742426/1` para `1792426742524/1`; em G2, de `1792426910414/1` para
`1792426910687/1`, e então ficou sem progresso até depois do retorno do DOM.

Em G1, 1.219 respostas retornaram só uma linha já consumida; uma retornou quatro,
das quais três novas. Em G2, os tamanhos foram `1:110, 2:3, 3:1, 4:1, 5:1`.
Não eram respostas CopyTicks vazias e não indicavam que o mercado inteiro parou.

O tempo ocupado pela união dos handlers medidos, sem contar filhos duas vezes,
foi 449.397 us em G1 e 37.890 us em G2. São tempos de parede dos handlers, não CPU.
Com timers regulares, isso refuta bloqueio contínuo do EA por 135 s ou 12 s;
não exclui pausas curtas nem problemas de distribuição do terminal.

O pico global da fila própria do EA foi **14/1024**, com **zero drops**, sequências
consecutivas e nenhuma falha de consulta/escrita reportada. Isso não é telemetria
da fila de eventos interna do MQL5. Não há série de ocupação por intervalo.
O atraso de escrita de G1 atravessa o fim da lacuna: um registro foi adquirido
antes e só escrito após uma CopyTicks longa da reconexão. Não é evidência de
1,6 s de bloqueio dentro da lacuna nem de saturação da fila própria.

## Evidência externa: Journal e Experts do mesmo terminal

Arquivos originais sob `.../D0E8209F77C8CF37AD8BF550E51FF075/`:
`logs/20261019.log` e `MQL5/Logs/20261019.log`. Hashes, caminhos e trechos
selecionados com números de linha estão no JSON; números de conta foram ocultados.
O log Experts associa explicitamente esta sessão ao nome do arquivo e registra
início às 16:15:53.614 e encerramento normal às 16:30:53.617.

| Lacuna | Linhas do Journal | Horário local | Evidência |
|---|---|---|---|
| G1 | 21 | 16:19:34.029 | Conexão com Clear perdida |
| G1 | 22 | 16:21:16.967 | Autorização pelo ponto NEW AZR 02 |
| G1 | 24 | 16:21:17.895 | Terminal sincronizado |
| G2 | 30 | 16:21:50.656 | Troca automática para ponto de acesso melhor; qualidade anterior 0% |
| G2 | 31 | 16:21:50.657 | Conexão com Clear perdida |
| G2 | 32 | 16:22:01.499 | Autorização pelo ponto SP2 02 |
| G2 | 34 | 16:22:02.688 | Terminal sincronizado |

**G1 — INDETERMINADA:** a perda de conexão é um fato e explica parte da ausência
de dados. O início efetivo de falha de rede pode anteceder sua detecção no log,
mas isso é hipótese: há 31,029–32,029 s entre o segundo do snapshot inicial e
o registro de perda. Não se pode atribuir comprovadamente todo o intervalo à rede,
nem à corretora, nem a um defeito do EA.

**G2 — EXPLICADA:** desconexão, troca de acesso e reconexão estão alinhadas ao
intervalo, dentro da resolução disponível. Há interrupção de conectividade
comprovada. A classificação explica o mecanismo, **não aprova a continuidade DOM**
nem identifica se a origem física foi Internet local, acesso do servidor ou outra
parte da rede. A distribuição intermediária do livro continua desconhecida.

Nesta taxonomia, `FALHA_CONFIRMADA` seria uma perda/corrupção demonstrada de
observações pelo capturador, além da ausência de snapshots. Não há essa prova.
Isso não nega as desconexões comprovadas nem torna os intervalos adequados a análise
de continuidade do livro. As classes tratam da explicação da lacuna, não de completude.

## Estado do DOM nas bordas

Todos os quatro snapshots de borda retornaram sucesso/erro zero, 20 níveis e
spread de 5 pontos. Tempos MarketBookGet: G1 7→4 us; G2 7→6 us.

| Estado | Melhor bid × quantidade | Melhor ask × quantidade | Volume visível bid / ask, 10 níveis |
|---|---|---|---|
| G1 antes | 209460 × 17 | 209465 × 94 | 2940 / 4078 |
| G1 depois | 209505 × 38 | 209510 × 135 | 3052 / 4039 |
| G2 antes | 209465 × 12 | 209470 × 105 | 3266 / 2853 |
| G2 depois | 209450 × 49 | 209455 × 74 | 4393 / 3201 |

Os estados mudaram. Essa diferença não permite reconstruir ordens, cancelamentos,
reposição ou trajetória do livro entre as bordas. O JSON preserva os níveis completos
das quatro bordas, sem alterar o arquivo fonte.

## Frequência fora das lacunas e efeitos da retomada

Há **2.422 eventos OnBookEvent e 2.423 snapshots**: o adicional é o OnInit vazio.
Mediana de intervalo DOM: 347,812 ms; P99: 382,371 ms. Excluindo apenas as duas
lacunas acima de 10 s: mediana 347,804 ms, P99 379,780 ms, taxa 3,217 intervalos/s.
As lacunas somam 147,666258 s, ou **16,407% da janela solicitada**. Isso é tempo
entre observações selecionadas, não percentual demonstrado de eventos perdidos.

| Janela de comparação | Snapshots no interior | Taxa observada |
|---|---:|---:|
| 30 s antes de G1 | 95 | 3,167/s |
| 30 s após G1 | 97 | 3,233/s |
| 30 s antes de G2 | 103 | 3,433/s |
| 30 s após G2 | 97 | 3,233/s |

Essa comparação da própria sessão basta para caracterizar a ruptura. Não se
presume equivalência com capturas anteriores em condições de mercado diferentes.

Depois de G1, CopyTicks sequência 26353 levou **1.589,634 ms** e aceitou 2.047
ticks; a seguinte, 26356, aceitou outros 2.047. Foram os dois lotes cheios da
sessão. O OnTimer que contém a primeira consulta e escrita levou 2.614,666 ms;
o seguinte, 1.138,561 ms. Há um intervalo DOM adicional de 3.767,257 ms na retomada,
que não está incluído nas duas lacunas selecionadas. Depois de G2, a consulta
29756 levou **1.430,132 ms** e aceitou 337 ticks; seu timer levou 1.469,057 ms.

Esses bloqueios são **posteriores ao primeiro snapshot de retorno** e não causam
as lacunas principais. Duração maior que a própria consulta em G1 é compatível
com o trabalho de serializar/escrever lotes grandes no OnTimer. Não há tempos
separados de FileWriteString/FileFlush para atribuir com precisão o excedente.
O snapshot 26351 foi obtido antes da consulta longa, mas aguardou 1.603,840 ms
para iniciar sua escrita: a ordem de execução no EA explica essa postergação.

## Times & Trades: disponibilidade histórica e uso conjunto

O replay offline conferiu **26.192 ticks = 24.722 negócios por flags + 1.470
cotações**, sem lacunas de sequência, regressões/rupturas de ordinal ou divergência
dos contadores finais. Duplicatas legítimas foram preservadas. As 16.223 consultas
não apresentaram erro registrado. Isso é consistência interna, não certificação
contra a totalidade de negócios da bolsa.

Para contar negócios de mercado separadamente da hora de aquisição, usou-se o
recorte explícito `[TimeCurrent_antes×1000, TimeCurrent_depois×1000)`. Esses limites
são segundos de última cotação e podem estar atrasados; **não são a localização
exata da lacuna no relógio de mercado**. Todos os ticks da captura foram examinados,
inclusive os adquiridos depois da reconexão, sem deduplicar conteúdo.

| Recorte no relógio bruto do servidor | Ticks / negócios encontrados | Adquiridos após o fim monotônico da lacuna, ticks / negócios | Faixa de preço LAST dos negócios |
|---|---:|---:|---|
| G1: 16:19:02 a 16:21:17 | 4746 / 4467 | 4730 / 4451 | 209455 a 209540 |
| G2: 16:21:50 a 16:22:02 | 359 / 341 | 325 / 309 | 209450 a 209470 |

O maior intervalo entre timestamps distintos desses recortes é 342 ms em G1 e
185 ms em G2. Logo, **o histórico recebido não ficou vazio nesses períodos**.
Isso, junto dos lotes de retomada, é evidência de aquisição tardia de negócios.
Não prova que todo o histórico foi recuperado, nem que havia entrega em tempo real
durante o silêncio. Nenhuma consulta MT5 adicional foi feita para esta auditoria.

Para estudos retrospectivos de preço/volume, esses negócios continuam utilizáveis
com marcação de atraso e limites de completude. Para latência/reação em tempo real,
não equivalem a dados disponíveis à época. **Não associar os ticks históricos
ao último DOM conhecido como se ele tivesse permanecido válido por 135 s ou 12 s.**

Absorção exige observar execução junto de permanência/reposição de liquidez; essa
reposição não está registrada. Deltas entre bordas misturam cancelamento, inserção,
execução e deslocamento de preços. Não identificar absorção, manutenção de fila,
liquidez retirada ou reação causal DOM→preço atravessando as lacunas. Excluir ou
marcar essas janelas e a retomada com dados atrasados em qualquer estudo conjunto.

## Inspeção do código e limites das hipóteses

O fonte instalado no terminal tem versão **1.00**, SHA-256
`acef522cc6cc26ebb07f576e6a2575cff236fdbd77088f7b8c4b427c82b5baaa`.
O workspace contém versão 1.01, hash
`d0a1b607f55b0683d3e0c6b43b327c13448947bba1e1ee93ca580880596b10c3`.
O arquivo desta captura não contém `continuity_state`, `recovery_episodes` ou
os limites de recuperação no cabeçalho. Portanto, o contexto de “zero episódios”
deve ser lido como **nenhum episódio documentado; a instrumentação está ausente**.
Os fontes atuais em disco não provam a identidade do EX5 carregado em 16:15:44;
o formato é compatível com a versão anterior. Não se atribuiu a sessão à 1.01.

- OnBookEvent filtra símbolo, stopping, subscribed, fault e fim da janela; cada
  passagem aceita incrementa book_events e chama MarketBookGet. Não existe filtro
  por igualdade do livro nem amostragem por tempo que explique suprimir esses
  snapshots. Inicialização/assinatura e liberação terminaram sem erro; não há
  release/re-add intermediário nesse fonte, mas o bool local não certifica a
  assinatura/distribuição efetiva durante a desconexão.
- OnTick sinaliza pending_ticks e grava uma notificação; não representa uma linha
  por negócio. OnTimer consulta uma vez, com fallback após 100 ms, e escreve a fila.
  Os 1.220/116 polls curtos e repetitivos são compatíveis com histórico local sem
  avanço enquanto a entrega estava interrompida.
- Os records só são serializados no timer/deinit. Não há descarte por igualdade
  de snapshots. Sequências e resumo não indicam perda na fila própria; faltam
  contadores na entrada do handler, antes dos filtros, para provar ausência de
  chamadas rejeitadas.

Segundo a [documentação OnBookEvent](https://www.mql5.com/en/docs/event_handlers/onbookevent),
BookEvent é enfileirado mesmo com processamento anterior pendente. Não se deve
importar automaticamente a regra de coalescência de NewTick/Timer para o DOM.
A [documentação de eventos](https://www.mql5.com/en/docs/event_handlers) descreve
supressão de novos NewTick/Timer quando já pendentes e possibilidade geral de
overflow. O arquivo não revela a geração desses eventos nem ocupação da fila MQL5:
coalescência/perda upstream não pode ser quantificada ou afirmada aqui.

A [documentação CopyTicks](https://www.mql5.com/en/docs/series/copyticks) prevê
sincronização/download de histórico e espera síncrona em EAs. Isso é compatível
com a retomada observada, mas não identifica sozinho o que consumiu cada chamada.
Nenhum desses contratos prova entrega completa de DOM pela bolsa/corretora.

Uma checagem adicional no auditor existente encontrou **27 livros com spread
travado e 5 cruzados** em outros pontos (sequências no JSON), além do OnInit vazio.
As quatro bordas analisadas não apresentam essas condições. Isso é uma limitação
adicional da qualidade de uso do livro, não uma causa demonstrada das lacunas.
O auditor existente classifica globalmente a sessão como INCOMPLETO por seus
critérios de validade DOM; isso é distinto de `completed_window=true`.
Também há `no_final_tick_drain=true`: os últimos 14,158 ms até o prazo não têm
certificado de esgotamento de ticks. Não se inventa quantidade perdida nesse tail.

## Menor instrumentação adicional proposta — não implementada

1. Um heartbeat por segundo, com monotônico + TimeGMT/TimeLocal, conexão
   `TERMINAL_CONNECTED`, subscribed local, fault/stopping, última execução de
   OnTick/OnBookEvent/CopyTicks e último cursor. Guardar contador OnBookEvent na
   entrada, antes dos guards, e contadores de rejeição por motivo. Incluir ocupação
   atual e pico da fila própria. Isso distingue silêncio observado, filtragem e
   conectividade; não certifica feed da corretora nem fila interna MQL5.
2. Apenas quando DOM estiver sem notificação por mais de 1 s, consultar
   MarketBookGet no máximo uma vez por segundo, identificando `timer_probe`, erro,
   hash/estado e horário de consulta. Não chamar isso de OnBookEvent nem de livro
   fresco. Mudança sem notificação é evidência diferente de estado estático, mas
   consulta igual pode ser cache antigo. Não re-assinar automaticamente, para não
   mascarar a causa. A identificação de origem no servidor exige log de protocolo
   ou fonte independente, fora da instrumentação mínima do EA.
3. Registrar início/fim de FlushObservations e FileFlush quando demorados, além
   das consultas existentes. Versionar a sessão e preservar hash do EX5 implantado
   em arquivo lateral antes da captura. Isso resolve atribuição de latência e versão.

Para o trecho inicial não explicado de G1, o heartbeat teria reduzido o intervalo
entre silêncio e detecção observável de conexão, sem assumir que o estado connected
detecta imediatamente uma conexão silenciosa.

## Reprodução e testes

```powershell
$terminalRoot = Join-Path $env:APPDATA 'MetaQuotes/Terminal/D0E8209F77C8CF37AD8BF550E51FF075'
python -B panic-scanner/scripts/mt5_dom_gap_audit.py `
  (Join-Path $terminalRoot 'MQL5/Files/WINZ26-event-joint-3944703-78015786034571.ndjson') `
  --terminal-log (Join-Path $terminalRoot 'logs/20261019.log') `
  --experts-log (Join-Path $terminalRoot 'MQL5/Logs/20261019.log') `
  --ea-source (Join-Path $terminalRoot 'MQL5/Experts/WinZ26EventJointProbe.mq5') `
  --ea-source panic-scanner/scripts/mql5/WinZ26EventJointProbe.mq5 `
  --output panic-scanner/dom-captures/WINZ26-3944703-dom-gaps-reproduced.json

python -B -m unittest discover -s panic-scanner/scripts -p 'test_mt5_dom_gap_audit.py' -v
python -B -m unittest discover -s panic-scanner/scripts -p 'test_mt5_mql5_event_audit.py' -v
python -B -m unittest discover -s panic-scanner/scripts -p 'test_mt5_cursor_recovery_source.py' -v
```

O analisador recusa saída existente ou igual a qualquer entrada. Seleciona todos
os pares DOM com distância >10 s, sem codificar os timestamps desta sessão.
JSON contém regras de janela/percentis, hashes de entradas e do próprio analisador,
reconciliação de cursor e resumo e verificação de hashes ao terminar.
Nove testes sintéticos novos passaram: bordas, relógios, atraso histórico,
duplicatas, sobreposição pai/filho, ausência de eventos, parse UTF-16 e atribuição
conservadora de rede. Passaram também 18 testes do auditor existente e três
verificações estáticas de escopo do EA: **30 testes ao todo**.
