# Diagnóstico mínimo de relógios Windows + MT5

Script independente e manual: `scripts/mt5_clock_probe.py`.
Não altera o relógio, não executa resync, não muda configuração do MT5 ou
Market Watch (não chama symbol_select), não assina DOM nem escreve históricos.
Inicializa uma sessão MT5 com timeout de conexão de 3 segundos e encerra em finally.
Use o terminal Clear já conectado e WINZ26 disponível durante o pregão.

Da raiz do repositório:

```powershell
python panic-scanner/scripts/mt5_clock_probe.py --seconds 10 --output panic-scanner/dom-captures/WINZ26-clock-diagnostic.json
```

O arquivo de saída deve ser novo e seu diretório já deve existir. Sem --output,
imprime JSON no console. O padrão --seconds é 5, limitado a >0 e <=10. A janela
de leitura de ticks dura até o prazo configurado, separada da preparação.
Consultas w32tm têm timeout de 2 s cada. Chamadas MT5 são síncronas: uma consulta
já iniciada pode retornar após o prazo; não se garante limite rígido total de 10 s.
Nenhuma consulta real é executada ao importar o módulo ou ao usar --help.

## Evidências registradas

- windows_clock e finished_clock: UTC, horário local com offset explícito, nome
  local do timezone e representação aproximada do epoch em ns. Não se afirma
  exatidão de nanosegundos; os horários vêm do relógio do Windows/Python.
- windows_sync: `w32tm /query /status /verbose` e `w32tm /query /source`, executados
  sem shell, somente leitura; stdout/stderr, código de retorno e falhas/timeout.
  Preserva saída localizada. Linhas contendo offset/deslocamento/defasagem são
  destacadas como evidência, sem interpretação automática de unidade ou sinal.
  A fonte fica na saída de /source; falta de informação não significa offset zero.
  Estado de sincronização reportado não certifica UTC externo nem relógio do feed.
- mt5: empresa e servidor, sem login/credenciais. symbol_info e tick inicial
  preservados integralmente, incluindo time/time_msc, com janelas UTC de consulta.
- batches: COPY_TICKS_ALL incremental a aproximadamente 20 ms, com cursor sobre
  todos os ticks e ordinal, reaproveitando o leitor existente. Preserva campos
  brutos/flags e duplicatas legítimas, janela UTC início/fim e monotônico de cada
  consulta. UTC de término é amostrado imediatamente após retorno da consulta,
  antes do processamento das linhas. Timestamps de lote não são horários de
  recepção individual de cada tick.
- Cada tick mostra time em segundos, time_msc em ms, interpretação explícita epoch
  UTC, consistência entre campos e diferenças locais menos mercado, sem ajuste.

Cursor inicial inclusivo: primeiro lote pode conter o tick inicial e outros ticks
com o mesmo timestamp. Contagem `ticks_strictly_after_initial_time` destaca somente
timestamps posteriores ao inicial; não presume que ticks iguais no mesmo ms sejam
repetições. A inicialização da leitura não transforma hora local em hora de mercado.
Tick inicial zero/ausente produz diagnóstico incompleto, sem selecionar símbolo ou
inventar cursor. Falha de consulta interrompe, registra evidência e mantém cleanup.

## Separação das conclusões

`observed_differences_ms` mede diferenças algébricas início/fim local menos time_msc.
`interpretation.apparent_offset` apresenta o múltiplo inteiro de hora mais próximo
da mediana de ticks novos apenas como hipótese descritiva. O residual é condicional
a essa hipótese, nunca aplicado aos timestamps originais e nunca considerado
latência de rede ou correção de relógio.

`interpretation.reception_delay.measured_independently=false`: offset, idade do
último tick, processamento do feed, transmissão e polling não são separáveis por
este experimento. Ausência de ticks novos produz no_new_ticks_observed, não uma
estimativa inventada do desvio. Havendo erro MT5, status incomplete. Erro do w32tm
fica explicitamente indisponível, mas permite observar MT5.

Esse teste pode reforçar ou refutar a hipótese de relógio local incorreto a partir
do estado w32tm, mas não determina sozinho se o feed codifica horário civil como
epoch. Comparação direta com o terminal e uma referência independente de origem
continua necessária quando persistir ambiguidade. Não existe correção automática
+3h, +709ms ou inferência de causalidade.

## Testes sintéticos

```powershell
python -m unittest discover -s panic-scanner/scripts -p "test_mt5_clock_probe.py" -v
```

6 testes passaram: UTC/local explícitos, limites de duração, comandos w32tm somente
de consulta, offset indisponível/timeout, sessão única, dados brutos e diferenças,
ticks novos/ausentes, tick inicial inválido, erro/interrupt e shutdown. Testes
injetam MT5, relógios e subprocesso falsos; não acessam w32tm nem terminal reais.
