package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Locale;

/** Local-only research. Mode and UTC period are explicit; no historical acquisition. */
public final class Setup91TriggerResearchCommand {
    private Setup91TriggerResearchCommand() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 5 || args.length > 6) {
            throw new IllegalArgumentException("Usage: SYMBOL START_DATE END_DATE FIRST_RETURN|PARTIAL_1R_BREAKEVEN_TARGET_3_5R (legacy aliases accepted) OUTPUT_DIR [HISTORY_TEMPLATE]");
        }
        Path output = Path.of(args[4]);
        if (Files.exists(output.resolve("summary.txt")) || Files.exists(output.resolve("triggers.csv"))) {
            throw new IllegalArgumentException("Output report already exists: " + output);
        }
        var report = new Setup91TriggerResearchRunner(args.length == 6 ? args[5] : Setup91BatchRunner.DEFAULT_TEMPLATE)
                .run(args[0], LocalDate.parse(args[1]), LocalDate.parse(args[2]), Setup91TriggerResearch.Mode.valueOf(args[3]),
                        day -> System.out.printf("%s | LASTs: %d | operacoes aceitas: %d%n", day.date(), day.lasts(), day.entries()));
        Files.createDirectories(output);
        Files.writeString(output.resolve("triggers.csv"), csv(report), StandardOpenOption.CREATE_NEW);
        String summary = summary(report);
        Files.writeString(output.resolve("summary.txt"), summary, StandardOpenOption.CREATE_NEW);
        System.out.print(summary);
        System.out.println("CSV: " + output.resolve("triggers.csv").toAbsolutePath());
    }

    public static String summary(Setup91TriggerResearchRunner.Report report) {
        var rows = report.results();
        var text = new StringBuilder("SETUP91 - PESQUISA DOS TRIGGERS (LAST observado, pontos)\n");
        text.append(report.symbol()).append(" | ").append(report.start()).append(" -> ").append(report.end())
                .append(" UTC | ").append(report.mode()).append('\n');
        text.append("Total de triggers: ").append(rows.size()).append('\n');
        text.append("Dias com negocios LAST: ").append(report.tradingDays() < 0 ? "N/A" : report.tradingDays()).append('\n');
        text.append("Operacoes por dia com negocios: ").append(report.tradingDays() > 0
                ? String.format(Locale.ROOT, "%.4f", (double) rows.size() / report.tradingDays()) : "N/A").append('\n');
        for (var side : Setup91.Candidate.values()) {
            text.append(side).append(": ").append(rows.stream().filter(r -> r.trigger().signal().side() == side).count()).append('\n');
        }
        for (var exit : Setup91TriggerResearch.Exit.values()) {
            text.append(exit).append(": ").append(rows.stream().filter(r -> r.exit() == exit).count()).append('\n');
        }
        text.append(String.format(Locale.ROOT, "Saldo acumulado de pontos: %.2f%n", rows.stream().mapToDouble(Setup91TriggerResearch.Result::points).sum()));
        var byMfe = Comparator.comparingDouble(Setup91TriggerResearch.Result::mfe);
        append(text, "MAIOR EXCURSAO FAVORAVEL", rows.stream().max(byMfe).orElse(null));
        append(text, "MENOR EXCURSAO FAVORAVEL", rows.stream().min(byMfe).orElse(null));
        append(text, "STOP LOSS COM MENOR MFE", rows.stream().filter(r -> r.exit() == Setup91TriggerResearch.Exit.STOP_LOSS)
                .min(byMfe).orElse(null));
        long reached = rows.stream().filter(Setup91TriggerResearch.Result::reachedOneR).count();
        text.append("Atingiram pelo menos +1R: ").append(reached).append('\n');
        if (report.mode() != Setup91TriggerResearch.Mode.FIRST_RETURN) {
            text.append("Parcial de 50% em +1R; stop do saldo imediatamente na entrada; alvo fixo do saldo em +3.5R.\n")
                    .append("Filtro de entrada: R >= max(130, 1.0x ATR14) AND R <= min(550, 4.0x ATR14); trigger <= 17:30:00 UTC; sem ATR aquecido, rejeita.\n")
                    .append("Pontos = 50% parcial + 50% saldo; alvo completo equivale a +2.25R.\n");
        }
        text.append("Entrada = LAST do trigger; stop inicial BUY = minima do sinal, SELL = maxima.\n")
                .append("MFE/MAE medidos ate a saida, inclusive; DAY_END usa o ultimo LAST disponivel (nao e stop nem alvo).\n")
                .append("Uma posicao por vez; novos sinais e filtros ficam desativados ate a saida. Parcial e alvo final usam niveis exatos de +1R e +3.5R; stops usam LAST observado arredondado ao tick WIN (5 pontos), inclusive gaps.\n")
                .append("Retorno a entrada nao garante resultado exatamente zero. Sem ordens, custos, slippage modelado ou lucro financeiro.\n")
                .append("MFE nao e lucro realizado. Nao se infere que um retorno teria necessariamente atingido o stop original.\n");
        return text.toString();
    }

    private static String percent(long part, long total) {
        return total == 0 ? "N/A" : String.format(Locale.ROOT, "%.2f%% (%d/%d)", 100.0 * part / total, part, total);
    }

    private static void append(StringBuilder text, String label, Setup91TriggerResearch.Result row) {
        text.append(label).append(": ");
        if (row == null) { text.append("N/A\n"); return; }
        text.append(String.format(Locale.ROOT, "%s | %s | entrada %.8f | stop %.8f | R %.8f | MFE %.8f | MAE %.8f | %s | pontos %.8f%n",
                row.id(), Instant.ofEpochMilli(row.trigger().timeMsc()), row.trigger().lastPrice(), row.initialStop(),
                row.risk(), row.mfe(), row.mae(), row.exit(), row.points()));
    }

    public static String csv(Setup91TriggerResearchRunner.Report report) {
        var text = new StringBuilder("id,side,signal_time_utc,trigger_time_utc,trigger_sequence,entry,initial_stop,risk,mfe,mae,mfe_time_utc,mae_time_utc,reached_1r,exit,exit_time_utc,exit_sequence,exit_price,points,mode,partial_price,partial_sequence,protected_stop\n");
        for (var r : report.results()) {
            text.append(String.join(",", r.id(), r.trigger().signal().side().name(),
                    Instant.ofEpochMilli(r.trigger().signal().candle().bucketStartTimeMsc()).toString(),
                    Instant.ofEpochMilli(r.trigger().timeMsc()).toString(), Long.toString(r.trigger().sequence()),
                    Double.toString(r.trigger().lastPrice()), Double.toString(r.initialStop()), Double.toString(r.risk()),
                    Double.toString(r.mfe()), Double.toString(r.mae()), Instant.ofEpochMilli(r.mfeTimeMsc()).toString(),
                    Instant.ofEpochMilli(r.maeTimeMsc()).toString(), Boolean.toString(r.reachedOneR()), r.exit().name(),
                    Instant.ofEpochMilli(r.exitTimeMsc()).toString(), Long.toString(r.exitSequence()),
                    Double.toString(r.exitPrice()), Double.toString(r.points()), report.mode().name(), r.partialPrice() == null ? "" : Double.toString(r.partialPrice()),
                    r.partialSequence() == null ? "" : r.partialSequence().toString(),
                    r.protectedStop() == null ? "" : Double.toString(r.protectedStop()))).append('\n');
        }
        return text.toString();
    }
}
