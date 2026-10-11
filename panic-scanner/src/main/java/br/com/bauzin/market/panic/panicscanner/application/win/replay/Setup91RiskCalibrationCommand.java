package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Locale;

/** Risk frequency audit; failed sessions stay explicit and never count as zero-entry trading days. */
public final class Setup91RiskCalibrationCommand {
    private Setup91RiskCalibrationCommand() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: SYMBOL START_DATE END_DATE NEW_OUTPUT_DIR");
        Setup91BatchRunner.validateSymbol(args[0]);
        var start = LocalDate.parse(args[1]);
        var end = LocalDate.parse(args[2]);
        if (start.isAfter(end)) throw new IllegalArgumentException("Invalid period");
        var output = Path.of(args[3]);
        if (Files.exists(output)) throw new IllegalArgumentException("Output exists: " + output);
        Files.createDirectories(output);
        var rows = new ArrayList<Setup91TriggerResearch.Result>();
        var failures = new ArrayList<String>();
        int tradingDays = 0;
        int validDays = 0;
        var mode = Setup91TriggerResearch.Mode.PARTIAL_1R_BREAKEVEN_TARGET_3_5R;
        try (var csv = Files.newBufferedWriter(output.resolve("daily.csv"))) {
            csv.write("date,status,lasts,entries,error\n");
            for (var date = start; !date.isAfter(end); date = date.plusDays(1)) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                var tracker = new Setup91TriggerResearch(mode);
                try {
                    var day = new Setup91BatchRunner().run(args[0], date, tracker);
                    var accepted = tracker.finishDay();
                    rows.addAll(accepted);
                    validDays++;
                    if (day.lastsProcessed() > 0) tradingDays++;
                    csv.write(date + ",OK," + day.lastsProcessed() + "," + accepted.size() + ",\n");
                    System.out.printf("%s OK | LASTs %d | operacoes %d%n", date, day.lastsProcessed(), accepted.size());
                } catch (java.io.IOException | IllegalArgumentException e) {
                    String error = String.valueOf(e.getMessage()).replace('\r', ' ').replace('\n', ' ');
                    failures.add(date + ": " + error);
                    csv.write(date + ",FAILED,0,0,\"" + error.replace("\"", "\"\"") + "\"\n");
                    System.out.println(date + " FAILED | " + error);
                }
                csv.flush();
                System.out.flush();
            }
        }
        var report = new Setup91TriggerResearchRunner.Report(args[0], start, end, mode, rows, tradingDays);
        String coverage = "Cobertura: " + (failures.isEmpty() ? "COMPLETA" : "PARCIAL") + " | dias invalidos: " + failures.size() + "\n";
        String target = "Meta indicativa: aproximadamente 2 operacoes/dia com negocios; medido: "
                + (tradingDays == 0 ? "N/A" : String.format(Locale.ROOT, "%.4f", (double) rows.size() / tradingDays)) + "\n";
        target += "Operacoes por dia corrido validado (" + validDays + " dias; inclui fins de semana): "
                + (validDays == 0 ? "N/A" : String.format(Locale.ROOT, "%.4f", (double) rows.size() / validDays)) + "\n";
        String summary = coverage + target + Setup91TriggerResearchCommand.summary(report)
                + (failures.isEmpty() ? "" : "Dias excluidos da media:\n" + String.join("\n", failures) + "\n");
        Files.writeString(output.resolve("triggers.csv"), Setup91TriggerResearchCommand.csv(report));
        Files.writeString(output.resolve("summary.txt"), summary);
        System.out.print(summary);
        if (!failures.isEmpty()) System.exit(2);
    }
}
