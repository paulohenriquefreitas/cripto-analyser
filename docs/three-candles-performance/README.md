# Travamento do frontend de 3 Candles

## Evidências

A imagem do usuário mostra o navegador sem resposta com as camadas desligadas e 1.219 candidatos. O código ainda construía tabelas completas dentro de `<details>` fechados, formatava datas com um novo `Intl.DateTimeFormat` por célula e analisava Fibonacci oculto. O replay também consultava snapshots a cada 250 ms enquanto pausado.

O script `frontend/scripts/profileThreeCandlesWorkspace.mjs` executa o componente real com um host JSX leve e uma carga sintética de 1.219 candidatos derivada da fixture `1155.json`. Não mede React DOM, tempo de rede ou latência de mercado.

| Uma renderização inicial, tudo desligado | Antes | Depois |
|---|---:|---:|
| Tempo observado no host JSX | 5.648 ms | 155 ms |
| Instâncias de DateTimeFormat | 14.154 | 1 |
| Elementos JSX criados | 55.239 | 1.263 |

Resultados originais em `before.json` e `after.json`. Os tempos variam com carga da máquina; contagens e comportamento são a evidência mais direta. Não foi demonstrado vazamento de memória.

## Correção

- Conteúdo dos painéis de candidatos, configurações avançadas e auditoria só monta depois de aberto; fechar desmonta o conteúdo.
- Um formatador de data é reutilizado por fuso.
- Seleção de candidatos usa opções nativas, mantendo todos os candidatos e o ID selecionado.
- Fibonacci não recebe o histórico enquanto estiver oculto e seus controles/auditoria não forem necessários.
- Auditoria de duplicidades só calcula com seu painel de candidatos aberto.
- Replay pausado ou concluído não consulta snapshots continuamente. Play reativa o polling; comandos e passos individuais aplicam suas respostas diretamente.

Nenhum detector, regra, indicador ou resultado do backend foi alterado. Live continua consultando snapshots; camadas desligadas ainda não desativam motores do servidor. Dados não foram descartados para acelerar a tela.

## Verificação

`checkThreeCandlesBrowser.mjs` usa Edge isolado e intercepta todas as APIs de mercado com uma fixture gravada. Não inicia nem consulta MT5 e não altera a sessão real do usuário. A verificação de `browser.json` confirmou:

- 168 candidatos disponíveis, todas as opções desligadas e gráfico nativo montado;
- zero linhas de auditoria dentro dos painéis fechados;
- 168 linhas ao abrir o painel de candidatos e zero ao fechá-lo;
- ausência de exceções de JavaScript.

Os testes de workspace exercitam o comportamento lazy, escolhas manuais de Fibonacci, independência das camadas e preservação dos snapshots. Os testes de página verificam que pausar/concluir elimina consultas repetidas sem impedir Play e Next.

Para repetir a medição sintética na raiz:

```powershell
node frontend/scripts/profileThreeCandlesWorkspace.mjs profile
```

Com o frontend dev de 3 Candles já rodando:

```powershell
node frontend/scripts/checkThreeCandlesBrowser.mjs
```

A sessão real e o tempo de resposta do backend continuam precisando de observação após recarregar a página. Estes resultados não estabelecem equivalência de latência com o Profit.
