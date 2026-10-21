# Auditoria temporal offline DOM + T&T

Script independente `scripts/mt5_dom_temporal_audit.py`. Usa exclusivamente
`dom-captures/WINZ26-joint-probe-30s.ndjson`, sem MT5, coleta, modificação da fonte
ou integração com streams/Java/histórico. Reutiliza timestamp/validação DOM e o
classificador experimental corrigido, nunca o side antigo armazenado.

```powershell
python panic-scanner/scripts/mt5_dom_temporal_audit.py panic-scanner/dom-captures/WINZ26-joint-probe-30s.ndjson --output panic-scanner/dom-captures/nova-auditoria-temporal.json
```

Arquivo de saída deve ser novo. Sem --output imprime JSON. --max-gap-ms padrão
500 é critério diagnóstico de continuidade, sem função operacional. Sequências,
contrato, ordem serial monotônica e time/time_msc são validados. Fonte conferida
por SHA-256 antes e depois. Duplicatas não são removidas.

## Relógios e método de associação

Intervalos de consulta e cadência usam monotônico. Diferenças mercado/local usam
os timestamps originais e UTC local capturado; não há alteração do mercado nem
conversão presumida de fuso. A janela UTC tick_batch inclui processamento local
até completed_utc; monotonic_end representa fim da consulta. Assim, a diferença
UTC de timestamps não é medida isolada da latência de transporte.

Para cada par de snapshots DOM consecutivos, registra-se:
- janela possível: início da consulta anterior até fim da consulta seguinte;
- janela conservadora: fim da consulta anterior até início da consulta seguinte;
- utilizável somente com ambos os livros válidos, sem vazio/falha/anomalia, gap
  monotônico <=500 ms e bordas UTC ordenadas.

Em cada cenário, t_local hipotético = time_msc + offset. O evento ocupa a faixa
[t_local - incerteza, t_local + incerteza]. Bordas locais são expandidas em 1 ms,
considerando a resolução textual, sem afirmar exatidão de 1 ms. Associação única
condicional exige a faixa inteiramente dentro da janela conservadora e exatamente
um par candidato. Caso contrário: fora da cobertura, período DOM desconhecido,
ou indeterminada por borda/incerteza. Os cenários 0 e +3 h com ±0/25/50/100/1000 ms
são hipóteses explícitas, não calibração. Nenhuma delas é adotada como verdadeira.

Sem limite conhecido para offset/incerteza: zero associações estabelecidas e
566 indeterminadas. Não é aplicado ajuste obtido da mediana da recepção, pois
atraso do feed, transporte, polling e relógio não podem ser separados por esse dado.
Não se atribui qualquer negócio à causa de uma mudança do livro.

## Resultado da captura

Relatório: `dom-captures/WINZ26-joint-probe-30s.temporal-audit.json`.
SHA-256 fonte: 6a289aeefa5f95ad9c339b10c705ae7b8046cd4d7703ee69d606f86c8e1fe968.

| Métrica | Mediana | P90 | Máximo |
| --- | ---: | ---: | ---: |
| Intervalo entre consultas DOM (ms) | 100,344 | 100,966 | 166,654 |
| Intervalo entre consultas de ticks (ms) | 20,415 | 20,994 | 120,653 |
| Intervalo entre negócios no relógio de mercado (ms) | 25 | 126 | 569 |
| Duração consulta DOM (ms) | 0,177 | 0,252 | 0,525 |
| Duração consulta ticks (ms) | 0,275 | 0,355 | 20,913 |

297 snapshots: 294 válidos e 3 vazios. 1.434 consultas de ticks, sem registros de
falha. Nenhum intervalo entre consultas >500 ms; isso não certifica ausência de
perdas do feed. O maior excesso sobre as cadências alvo é 66,654 ms DOM e
100,653 ms ticks. Existem 91 intervalos zero entre negócios; são preservados.
Os 569 ms entre negócios não comprovam lacuna de captura: podem representar
simples ausência de novos negócios.

566 negócios: 206 BUY, 340 SELL, 20 AMBIGUOUS; 50 quotes. Bit desconhecido 1024
nos 616 ticks, separado da classificação direcional.

DOM local: 20:47:15.280 a 20:47:45.224 UTC em 15/10/2026.
Mercado bruto: 17:47:14.470 a 17:47:44.466 UTC segundo interpretação epoch.
Diferença início de consulta menos mercado: mínimo 10.800.689 ms, mediana
10.800.709 ms, P90 10.800.724 ms, máximo 10.800.810 ms.
Fim de consulta menos mercado: mediana 10.800.710 ms, máximo 10.800.831 ms.
Sob a hipótese +3h, resta diferença início/mercado de 689–810 ms, mediana 709 ms.
Isso não permite distinguir offset residual de atraso da fonte/recepção.
A amplitude UTC local menos monotônico é aproximadamente 1,003 ms, compatível
com estabilidade interna nesta curta janela, sem validar o relógio de mercado.

| Hipótese de relógio | Únicas condicionais | Indeterminadas por borda | DOM desconhecido | Fora da cobertura |
| --- | ---: | ---: | ---: | ---: |
| Sem deslocamento, ±0 ms | 0 | 0 | 0 | 566 |
| +3 h, ±0 ms | 541 | 16 | 3 | 6 |
| +3 h, ±25 ms | 268 | 289 | 3 | 6 |
| +3 h, ±50 ms | 7 | 551 | 3 | 5 |
| +3 h, ±100 ms | 0 | 558 | 4 | 4 |
| +3 h, ±1000 ms | 0 | 565 | 1 | 0 |

Cenário ±0 inclui somente a margem textual local de 1 ms; não é ausência real
comprovada de erro de relógio. Percentis usam posto mais próximo, mediana usual.

## Parecer

Cadência local permite exploração por janelas, mas os dados atuais não sustentam
associação temporal validada para identificar absorção. É possível apenas explorar
sensibilidade sob hipóteses explícitas, sem rotular absorção como fato. Antes de
uma análise conjunta mais forte, verificar a origem/fuso do time_msc, calibrar
independentemente a relação entre relógios e limitar o atraso/idade do livro e dos
ticks. A coleta atual não possui timestamp de origem DOM nem referência externa
para fazer essa calibração retrospectivamente. A janela curta e o polling limitam
a conclusão mesmo após eventual verificação dos relógios.

## Testes

```powershell
python -m unittest discover -s panic-scanner/scripts -p "test_mt5_dom_temporal_audit.py" -v
```

6 testes sintéticos: intervalos e diferenças de relógio, duplicatas/flags,
incerteza/bordas/offset, vazios/gaps, quotes/AMBIGUOUS, ausência de dados,
contrato/ordem/sequência e hash da fonte. Nenhuma coleta real foi executada.
