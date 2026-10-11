package br.com.bauzin.market.panic.panicscanner.application.experimental;

import java.time.LocalDate;
import java.util.*;
import static br.com.bauzin.market.panic.panicscanner.application.experimental.ExperimentalComparisonCommand.Case;

/** Evaluation metadata only. Future qualification never participates in signal generation. */
final class ExperimentalAnticipation {
    static final int HORIZON = 10;

    enum Status {
        NOT_APPLICABLE, ALREADY_QUALIFIED, MATCHED,
        PARTITION_BOUNDARY, INSUFFICIENT_SESSIONS, NO_MATCH_WITHIN_10
    }

    record Audit(String ticker, LocalDate date, String partition, Status status,
                 Integer leadSessions, LocalDate nextQualifiedDate,
                 boolean previousQualifiedWithin10Sessions, boolean previousWindowComplete,
                 boolean additionalEpisodeStart) {}

    static String key(String ticker, LocalDate date) { return ticker + ":" + date; }

    static Map<String, Audit> audit(List<Case> rows) {
        Map<String, List<Case>> tickers = new TreeMap<>();
        rows.forEach(c -> tickers.computeIfAbsent(c.ticker(), ignored -> new ArrayList<>()).add(c));
        Map<String, Audit> audits = new LinkedHashMap<>();
        for (var values : tickers.values()) {
            values.sort(Comparator.comparing(Case::date));
            for (int i = 0; i < values.size(); i++) {
                Case c = values.get(i);
                boolean previousQualified = false;
                // Past observations across the split are legitimate context, never future labels.
                for (int p = Math.max(0, i - HORIZON); p < i; p++) {
                    previousQualified |= values.get(p).currentSignal().equals("QUALIFIED");
                }
                boolean additional = additional(c);
                boolean episodeStart = additional && (i == 0 || !additional(values.get(i - 1)));
                Status status = Status.NOT_APPLICABLE;
                Integer lead = null;
                LocalDate next = null;
                if (c.experimentalAlert()) {
                    if (c.currentSignal().equals("QUALIFIED")) {
                        status = Status.ALREADY_QUALIFIED;
                        lead = 0;
                        next = c.date();
                    } else {
                        status = Status.INSUFFICIENT_SESSIONS;
                        for (int j = i + 1; j < Math.min(values.size(), i + HORIZON + 1); j++) {
                            Case future = values.get(j);
                            if (!future.partition().equals(c.partition())) {
                                status = Status.PARTITION_BOUNDARY;
                                break;
                            }
                            if (future.currentSignal().equals("QUALIFIED")) {
                                status = Status.MATCHED;
                                lead = j - i;
                                next = future.date();
                                break;
                            }
                            if (j - i == HORIZON) status = Status.NO_MATCH_WITHIN_10;
                        }
                    }
                }
                Audit audit = new Audit(c.ticker(), c.date(), c.partition(), status, lead, next,
                        previousQualified, i >= HORIZON, episodeStart);
                if (audits.put(key(c.ticker(), c.date()), audit) != null)
                    throw new IllegalArgumentException("Duplicate ticker/session in comparison");
            }
        }
        return audits;
    }

    static boolean additional(Case c) {
        return c.experimentalAlert() && !c.currentSignal().equals("QUALIFIED");
    }
}
