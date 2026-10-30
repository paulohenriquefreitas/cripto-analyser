# Agrupamento visual M5 — WIN$N, 16/10/2026

Replay completo: 31 pivôs M5, 113 candles fechados; 9 linhas H1 preservadas.

| Tolerância | Linhas por região | Redução | Separando movimentos |
|---|---:|---:|---:|
| Sem agrupamento | 31 | 0% | 31 |
| 15 | 28 | 9.7% | 30 |
| 30 | 26 | 16.1% | 30 |
| 50 | 23 | 25.8% | 28 |

## Critério

Somente visual: mesmo símbolo, sessão e tipo; H1 não participa. Ordem de confirmação, origem e ID. A largura total máximo–mínimo deve caber na tolerância (sem encadeamento). Se mais de um grupo servir, vence a menor distância ao representante; empate favorece o primeiro confirmado e depois origem/ID. O representante é o preço real do primeiro pivô confirmado e permanece fixo. Zero desativa o agrupamento.

Regiões de preço podem ser retomadas por movimentos posteriores. Isso é adequado ao modo padrão como região visual, mas não equivale a um único movimento estrutural. A auditoria conta um novo movimento quando um membro do episódio anterior foi rompido por fechamento até a origem do novo extremo. A opção “Separar movimentos apos rompimento” impede essa associação. Não inferimos outros movimentos a partir de tempo ou distância arbitrários, e esta contagem não gera sinais operacionais.

Rompimentos posteriores à origem do novo pivô não alteram sua associação anterior. IDs, preços, origens, confirmações e estados individuais continuam no registro original. O gráfico recebe somente o primeiro pivô de cada região; a API e a memória estrutural não são agrupadas.

## Pivôs associados — padrão 30 pontos

| Tipo | Linha | Faixa | Movimentos | Membros: preço / origem / confirmação |
|---|---:|---|---:|---|
| TOP | 209375 | 209375–209375 | 1 | 209375 / 09:25 / 09:40 |
| BOTTOM | 208675 | 208675–208705 | 2 | 208675 / 09:30 / 09:45; 208705 / 12:20 / 12:35 |
| TOP | 209435 | 209415–209435 | 2 | 209435 / 09:45 / 10:00; 209415 / 16:45 / 17:00 |
| TOP | 209690 | 209680–209690 | 2 | 209690 / 10:00 / 10:15; 209680 / 11:55 / 12:10 |
| BOTTOM | 208640 | 208640–208640 | 1 | 208640 / 10:10 / 10:25 |
| TOP | 210210 | 210210–210210 | 1 | 210210 / 10:40 / 10:55 |
| BOTTOM | 209310 | 209310–209310 | 1 | 209310 / 10:50 / 11:05 |
| TOP | 210095 | 210095–210095 | 1 | 210095 / 11:00 / 11:15 |
| TOP | 210535 | 210535–210535 | 1 | 210535 / 11:20 / 11:35 |
| BOTTOM | 208340 | 208340–208340 | 1 | 208340 / 11:45 / 12:00 |
| TOP | 209265 | 209265–209265 | 1 | 209265 / 12:35 / 12:50 |
| BOTTOM | 208280 | 208280–208280 | 1 | 208280 / 13:05 / 13:20 |
| BOTTOM | 208380 | 208375–208380 | 2 | 208380 / 13:25 / 13:40; 208375 / 14:25 / 14:40 |
| TOP | 208965 | 208965–208965 | 1 | 208965 / 13:55 / 14:10 |
| BOTTOM | 208215 | 208215–208215 | 1 | 208215 / 14:05 / 14:20 |
| TOP | 208730 | 208730–208730 | 1 | 208730 / 14:15 / 14:30 |
| TOP | 209055 | 209055–209055 | 1 | 209055 / 14:40 / 14:55 |
| BOTTOM | 208520 | 208520–208520 | 1 | 208520 / 15:00 / 15:15 |
| TOP | 209605 | 209605–209605 | 1 | 209605 / 15:45 / 16:00 |
| BOTTOM | 208990 | 208980–208990 | 1 | 208990 / 16:05 / 16:20; 208980 / 16:30 / 16:45 |
| TOP | 209550 | 209550–209550 | 1 | 209550 / 16:15 / 16:30 |
| TOP | 209480 | 209480–209480 | 1 | 209480 / 16:30 / 16:45 |
| BOTTOM | 209165 | 209165–209165 | 1 | 209165 / 17:00 / 17:15 |
| TOP | 210150 | 210150–210150 | 1 | 210150 / 17:40 / 17:55 |
| BOTTOM | 209915 | 209915–209915 | 1 | 209915 / 17:55 / 18:10 |
| TOP | 210250 | 210250–210250 | 1 | 210250 / 17:55 / 18:10 |

Todos os IDs, estados e horários de rompimento de cada membro, para todas as tolerâncias e ambas as políticas de movimento, constam em [report.json](report.json).

## Validação e capturas

113 prefixos × 8 configurações: independência de níveis/rompimentos futuros, cobertura integral dos pivôs, largura máxima, ausência de IDs duplicados, representantes e associações anteriores estáveis, igualdade do fluxo incremental com reconstrução, remount e reset. H1 e memória bruta permanecem iguais. Mudanças de tolerância/visibilidade não recriam os candles.

[Antes, 31 linhas](before.png) · [15 pontos](15.png) · [30 pontos](30.png) · [50 pontos](50.png) · [30 pontos separando movimentos](30-separated.png).

Capturas da mesma página real, mesmo cursor e viewport, em Edge headless com API interceptada entregando o snapshot do Replay Java sobre histórico local MT5. Não representam acompanhamento de pregão ao vivo. O controle é alterado sem reiniciar Replay. Cores e critérios H1 2+2/1+1 permanecem intactos.

## Como repetir

Na raiz do repositório, com JDK 21, Node 24 e o frontend em localhost:5173:

```powershell
mvn -pl panic-scanner -am test '-Dtest=M5SessionReplayTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dthreecandles.m5Cache=C:/Users/paulo/project/cripto-analyser/historical-data/WIN$N/three-candles-m5/2026-10-16.json'
node --experimental-strip-types frontend/src/features/threecandles/validation/auditM5Grouping.mjs panic-scanner/target/threecandles-m5-validation/1825.json
node frontend/src/features/threecandles/validation/captureM5Grouping.mjs
```

Nesta execução, os 123 testes de frontend e TypeScript passaram. O Replay Java passou novamente, incluindo a igualdade com os nove candidatos H1 anteriores. ESLint dos três arquivos TypeScript alterados: zero erros e um aviso preexistente no cleanup do polling.
