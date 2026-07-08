const weekDayOrder = [
  "SUNDAY",
  "MONDAY",
  "TUESDAY",
  "WEDNESDAY",
  "THURSDAY",
  "FRIDAY",
  "SATURDAY"
];

const weekDayLabels = {
  SUNDAY: "Dom",
  MONDAY: "Seg",
  TUESDAY: "Ter",
  WEDNESDAY: "Qua",
  THURSDAY: "Qui",
  FRIDAY: "Sex",
  SATURDAY: "Sab"
};

export function buildEmptyAnalysis() {
  return {
    sequences: [],
    totalCandles: 0,
    totalSequences: 0,
    largestBullishSequence: 0,
    largestBearishSequence: 0,
    bullishSequenceDistribution: {},
    bearishSequenceDistribution: {},
    bullishCandles: 0,
    bearishCandles: 0,
    dojiCandles: 0
  };
}

export function normalizeAnalysisPayload(payload) {
  return {
    ...buildEmptyAnalysis(),
    ...payload,
    sequences: Array.isArray(payload?.sequences)
      ? payload.sequences.map((sequence) => ({
          ...sequence,
          length: Number(sequence.length ?? 0),
          startIndex: Number(sequence.startIndex ?? 0),
          endIndex: Number(sequence.endIndex ?? 0)
        }))
      : []
  };
}

export function applySequenceFilters(sequences, filters) {
  const minLength = Number(filters.minLength || 1);

  return (sequences ?? []).filter((sequence) => {
    if (filters.direction !== "ALL" && sequence.direction !== filters.direction) {
      return false;
    }

    if (sequence.length < minLength) {
      return false;
    }

    const startDate = new Date(sequence.startTime);
    const weekDay = weekDayOrder[startDate.getDay()];
    const hour = startDate.getHours();

    if (filters.weekDay !== "ALL" && weekDay !== filters.weekDay) {
      return false;
    }

    if (filters.hour !== "ALL" && hour !== Number(filters.hour)) {
      return false;
    }

    return true;
  });
}

export function buildRecurrenceStats(sequences) {
  return {
    byOccurrence: groupByOccurrence(sequences),
    byTopOccurrences: groupTopOccurrences(sequences),
    byMonthOccurrences: groupByMonthOccurrences(sequences),
    byWeekDay: groupByWeekDay(sequences),
    byHour: groupByHour(sequences),
    byHourMaxLength: groupByHourMaxLength(sequences),
    byLength: groupByLength(sequences)
  };
}

function groupByOccurrence(sequences) {
  const grouped = {
    bullish: [],
    bearish: []
  };

  for (const sequence of sequences) {
    const point = buildOccurrencePoint(sequence);

    if (sequence.direction === "BULLISH") {
      grouped.bullish.push(point);
    }

    if (sequence.direction === "BEARISH") {
      grouped.bearish.push(point);
    }
  }

  grouped.bullish.sort((left, right) => left.timestamp - right.timestamp);
  grouped.bearish.sort((left, right) => left.timestamp - right.timestamp);

  return grouped;
}

function groupByWeekDay(sequences) {
  const counters = new Map(
    weekDayOrder.map((day) => [day, { label: weekDayLabels[day], bullish: 0, bearish: 0 }])
  );

  for (const sequence of sequences) {
    const day = weekDayOrder[new Date(sequence.startTime).getDay()];
    incrementDirectionCounter(counters.get(day), sequence.direction);
  }

  return weekDayOrder.map((day) => counters.get(day));
}

function groupByHour(sequences) {
  const counters = new Map(
    Array.from({ length: 24 }, (_, hour) => [
      hour,
      { label: `${String(hour).padStart(2, "0")}:00`, bullish: 0, bearish: 0 }
    ])
  );

  for (const sequence of sequences) {
    const hour = new Date(sequence.startTime).getHours();
    incrementDirectionCounter(counters.get(hour), sequence.direction);
  }

  return Array.from(counters.values());
}

function groupByHourMaxLength(sequences) {
  const counters = new Map(
    Array.from({ length: 24 }, (_, hour) => [
      hour,
      {
        label: `${String(hour).padStart(2, "0")}:00`,
        bullish: 0,
        bearish: 0
      }
    ])
  );

  for (const sequence of sequences) {
    const hour = new Date(sequence.startTime).getHours();
    const bucket = counters.get(hour);

    if (!bucket) {
      continue;
    }

    if (sequence.direction === "BULLISH") {
      bucket.bullish = Math.max(bucket.bullish, Number(sequence.length ?? 0));
    }

    if (sequence.direction === "BEARISH") {
      bucket.bearish = Math.max(bucket.bearish, Number(sequence.length ?? 0));
    }
  }

  return Array.from(counters.values());
}

function groupTopOccurrences(sequences) {
  const sorted = [...(sequences ?? [])]
    .sort((left, right) => {
      const lengthDifference = Number(right.length ?? 0) - Number(left.length ?? 0);
      if (lengthDifference !== 0) {
        return lengthDifference;
      }

      return new Date(left.startTime).getTime() - new Date(right.startTime).getTime();
    })
    .slice(0, 300);

  return groupByOccurrence(sorted);
}

function groupByMonthOccurrences(sequences) {
  const grouped = new Map();

  for (const sequence of sequences ?? []) {
    const startDate = new Date(sequence.startTime);
    if (Number.isNaN(startDate.getTime())) {
      continue;
    }

    const monthKey = `${startDate.getFullYear()}-${String(startDate.getMonth() + 1).padStart(2, "0")}`;

    if (!grouped.has(monthKey)) {
      grouped.set(monthKey, {
        key: monthKey,
        label: new Intl.DateTimeFormat("pt-BR", {
          month: "long",
          year: "numeric"
        }).format(startDate),
        totalCount: 0,
        data: {
          bullish: [],
          bearish: []
        }
      });
    }

    const point = buildOccurrencePoint(sequence);
    const monthBucket = grouped.get(monthKey);
    monthBucket.totalCount += 1;

    if (sequence.direction === "BULLISH") {
      monthBucket.data.bullish.push(point);
    }

    if (sequence.direction === "BEARISH") {
      monthBucket.data.bearish.push(point);
    }
  }

  return Array.from(grouped.values()).map((entry) => ({
    ...entry,
    displayedCount: Math.min(entry.totalCount, 160),
    data: {
      bullish: limitMonthPoints(entry.data.bullish),
      bearish: limitMonthPoints(entry.data.bearish)
    }
  }));
}

function limitMonthPoints(points) {
  return [...points]
    .sort((left, right) => {
      const lengthDifference = Number(right.length ?? 0) - Number(left.length ?? 0);
      if (lengthDifference !== 0) {
        return lengthDifference;
      }

      return left.timestamp - right.timestamp;
    })
    .slice(0, 160)
    .sort((left, right) => left.timestamp - right.timestamp);
}

function buildOccurrencePoint(sequence) {
  const startDate = new Date(sequence.startTime);
  const endDate = new Date(sequence.endTime);

  return {
    timestamp: startDate.getTime(),
    length: Number(sequence.length ?? 0),
    direction: sequence.direction,
    startTime: sequence.startTime,
    endTime: sequence.endTime,
    dayLabel: Number.isNaN(startDate.getTime())
      ? "-"
      : new Intl.DateTimeFormat("pt-BR", {
          day: "2-digit",
          month: "2-digit"
        }).format(startDate),
    startHourLabel: Number.isNaN(startDate.getTime())
      ? "-"
      : new Intl.DateTimeFormat("pt-BR", {
          hour: "2-digit",
          minute: "2-digit",
          hour12: false
        }).format(startDate),
    endHourLabel: Number.isNaN(endDate.getTime())
      ? "-"
      : new Intl.DateTimeFormat("pt-BR", {
          hour: "2-digit",
          minute: "2-digit",
          hour12: false
        }).format(endDate)
  };
}

function groupByLength(sequences) {
  const counters = new Map();

  for (const sequence of sequences) {
    if (!counters.has(sequence.length)) {
      counters.set(sequence.length, {
        label: String(sequence.length),
        bullish: 0,
        bearish: 0
      });
    }

    incrementDirectionCounter(counters.get(sequence.length), sequence.direction);
  }

  return Array.from(counters.entries())
    .sort((left, right) => left[0] - right[0])
    .map(([, value]) => value);
}

function incrementDirectionCounter(counter, direction) {
  if (!counter) {
    return;
  }

  if (direction === "BULLISH") {
    counter.bullish += 1;
  }

  if (direction === "BEARISH") {
    counter.bearish += 1;
  }
}
