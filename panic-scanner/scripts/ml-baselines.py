"""Stage 2: audit, fit DISCOVERY, compare VALIDATION. Never evaluate TEST."""
import argparse
import csv
import hashlib
import json
import math
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

CLASSES = ["DOWN", "FLAT", "UP"]
CTX_FIELDS = ["return", "range_atr", "body_atr", "upper_wick_atr", "lower_wick_atr", "ema_delta_atr"]
CURRENT = ["close_return", "range_atr", "body_atr", "upper_wick_atr", "lower_wick_atr", "ema9_delta_atr", "atr_price"]
FEATURES = CURRENT + [f"ctx_lag{lag}_{f}" for lag in range(20, 0, -1) for f in CTX_FIELDS] + ["session_time_sin", "session_time_cos"]


def sealed_rows(path):
    """Peek only date before parsing a row; stop at the September boundary."""
    with path.open(encoding="utf-8-sig", newline="") as source:
        header = next(csv.reader([source.readline()]))
        assert header[:3] == ["symbol", "session_date_utc", "temporal_split"]
        previous = None
        for line in source:
            identity = line.split(",", 3)
            date = identity[1]
            if date >= "2026-09-01":
                break  # Never parse a TEST label or feature vector.
            assert "2026-04-01" <= date <= "2026-08-31"
            values = next(csv.reader([line])); assert len(values) == len(header)
            row = dict(zip(header, values))
            expected = "DISCOVERY" if date < "2026-08-01" else "VALIDATION"
            assert row["temporal_split"] == expected
            key = (date, int(row["bucket_start_msc"]))
            assert previous is None or key > previous, "Duplicate or unordered candle"
            previous = key
            yield row


def market_days(folder):
    groups = {"DISCOVERY": set(), "VALIDATION": set()}
    with (folder / "daily.csv").open(encoding="utf-8-sig") as source:
        for row in csv.DictReader(source):
            if row["date"] >= "2026-09-01":
                break
            if row["status"] == "OK" and int(row["candles"]) > 0:
                groups["DISCOVERY" if row["date"] < "2026-08-01" else "VALIDATION"].add(row["date"])
    return groups


def audit(folder, output):
    if (output / "models").exists() or (output / "metrics.json").exists():
        raise FileExistsError("Do not overwrite an experiment that has already trained models")
    raw = list(sealed_rows(folder / "features.csv")); examples = list(sealed_rows(folder / "dataset.csv"))
    keys = {(r["session_date_utc"], int(r["bucket_start_msc"])): r for r in raw}
    assert len(keys) == len(raw)
    feature_columns = list(raw[0]); assert not any(k.startswith("label_") for k in feature_columns)
    prev = {}
    warmup_count = 0
    insufficient_count = 0
    example_keys = {(r["session_date_utc"], int(r["bucket_start_msc"])) for r in examples}
    for r in raw:
        day = r["session_date_utc"]; close = float(r["close"]); ema = float(r["ema9"]); atr = float(r["atr14"])
        if day in prev:
            pe, pc, pa = prev[day]
            assert math.isclose(ema, pe + .2 * (close - pe), abs_tol=1e-7)
            tr = max(float(r["high"]) - float(r["low"]), abs(float(r["high"]) - pc), abs(float(r["low"]) - pc))
            assert math.isclose(atr, pa + (tr - pa) / 14, abs_tol=1e-7)
        prev[day] = (ema, close, atr)
        # Redundancy of close-EMA vs EMA delta is algebraic, not future leakage.
        assert math.isclose(float(r["close_ema9"]), 4 * float(r["ema9_delta"]), abs_tol=1e-7)
        key = (day, int(r["bucket_start_msc"]))
        ready = all(value for name, value in r.items() if name.startswith("ctx_")) and atr > 0
        if not ready:
            warmup_count += 1
            assert key not in example_keys
        elif all((day, key[1] + i * 300000) in keys for i in (1, 2, 3)):
            assert key in example_keys
        else:
            insufficient_count += 1
            assert key not in example_keys
    raw_ctx = ["close_return", "range_atr", "body_atr", "upper_wick_atr", "lower_wick_atr", "ema9_delta_atr", "close_ema9_atr"]
    ctx = CTX_FIELDS + ["close_ema_atr"]
    for r in examples:
        day, time = r["session_date_utc"], int(r["bucket_start_msc"])
        assert all(r[k] == keys[(day, time)][k] for k in feature_columns)
        assert float(r["atr14"]) > 0
        for lag in range(20, 0, -1):
            previous = keys[(day, time - lag * 300000)]
            for field, original in zip(ctx, raw_ctx):
                assert r[f"ctx_lag{lag}_{field}"] == previous[original] != ""
        future = [keys[(day, time + i * 300000)] for i in (1, 2, 3)]
        close = float(r["close"]); atr = float(r["atr14"]); move = float(future[-1]["close"]) - close
        up = max(0, max(float(f["high"]) for f in future) - close)
        down = max(0, close - min(float(f["low"]) for f in future))
        assert math.isclose(float(r["label_up_excursion"]), up, abs_tol=1e-9)
        assert math.isclose(float(r["label_down_excursion"]), down, abs_tol=1e-9)
        assert math.isclose(float(r["label_return_15m"]), float(future[-1]["close"]) / close - 1, abs_tol=1e-12)
        assert math.isclose(float(r["label_up_excursion_atr"]), up / atr, abs_tol=1e-12)
        assert math.isclose(float(r["label_down_excursion_atr"]), down / atr, abs_tol=1e-12)
        assert math.isclose(float(r["label_return_atr"]), move / atr, abs_tol=1e-12)
        assert r["label_direction"] == ("UP" if move >= atr else "DOWN" if move <= -atr else "FLAT")
        assert r["label_horizon_end_utc"] == datetime.fromtimestamp((time + 1200000)/1000, timezone.utc).isoformat().replace("+00:00", "Z")
        assert r["label_horizon_end_utc"][:10] == day
        for f in FEATURES[:-2]: assert math.isfinite(float(r[f]))
    groups = {p: [r for r in examples if r["temporal_split"] == p] for p in ("DISCOVERY", "VALIDATION")}
    # Counts only: metadata is explicitly allowed for historical reconciliation, never TEST outcomes.
    with (folder / "daily.csv").open(encoding="utf8") as source:
        metadata = list(csv.DictReader(source))
    market_count = sum(r["status"] == "OK" and int(r["candles"]) > 0 for r in metadata)
    all_candles = sum(int(r["candles"]) for r in metadata if r["status"] == "OK")
    previous_period = [r for r in metadata if "2026-04-04" <= r["date"] <= "2026-09-30"]
    previous_candles = sum(max(0, int(r["candles"])-1) for r in previous_period if r["status"] == "OK")
    assert all_candles == 13826 and market_count == 122 and previous_candles == 13704
    prefix_metadata = [r for r in metadata if r["date"] < "2026-09-01" and r["status"] == "OK"]
    assert len(raw) == sum(int(r["candles"]) for r in prefix_metadata)
    assert len(examples) == sum(int(r["valid_examples"]) for r in prefix_metadata)
    assert warmup_count == sum(int(r["warmup_discarded"]) for r in prefix_metadata)
    assert insufficient_count == sum(int(r["insufficient_horizon"]) for r in prefix_metadata)
    assert len(raw) == len(examples) + warmup_count + insufficient_count
    result = {"status": "PASS", "audit_scope": "features and labels April-August only; September metadata counts only",
              "historical_reconciliation": {"ml_candles": all_candles, "previous_policy_candles": previous_candles,
                  "terminal_candles": market_count, "old_window": "2026-04-04..2026-09-30", "new_window": "2026-04-01..2026-09-30",
                  "extra_missing_dates": ["2026-04-01", "2026-04-02"], "extra_valid_empty_date": "2026-04-03"},
              "class_counts": {p: dict(Counter(r["label_direction"] for r in rows)) for p, rows in groups.items()},
              "market_days": {p: len(days) for p, days in market_days(folder).items()}, "feature_count": len(FEATURES),
              "features": FEATURES, "dropped": {"all label_* except target": "future outcomes; never predictors",
                  "identifiers/dates/timestamps/partition": "audit identity, not predictors",
                  "raw OHLC/EMA/ATR/distances": "nominal price level; use normalized fields",
                  "close_ema9_atr and ctx_*close_ema_atr": "exact redundant 4x EMA delta",
                  "session_hour/session_minute": "replaced by fixed sine/cosine clock",
                  "volume/VWAP": "not reliably available; absent from source"}, "dataset_corrected": False,
              "prefix_candles": len(raw), "warmup_context_discarded": warmup_count, "insufficient_horizon": insufficient_count,
              "checks": ["strict chronological order/no duplicates", "closed-only EMA/ATR recurrence", "20 preceding same-session candles",
                         "identical immutable features with/without labels", "same-session exact 3-bucket horizon", "fixed ATR directional labels",
                         "no future columns in predictor allowlist", "September stopped before label parsing"]}
    output.mkdir(parents=True, exist_ok=True)
    (output / "audit.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf8")
    for p, rows in groups.items():
        with (output / f"{p.lower()}.csv").open("w", encoding="utf8", newline="") as target:
            writer = csv.DictWriter(target, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
    print(json.dumps({k:v for k,v in result.items() if k not in ("features",)}, indent=2))


def matrix(rows):
    import numpy as np
    values = []
    for r in rows:
        minute = int(r["session_hour"]) * 60 + int(r["session_minute"])
        angle = 2 * math.pi * minute / 1440
        values.append([float(r[f]) for f in FEATURES[:-2]] + [math.sin(angle), math.cos(angle)])
    return np.asarray(values, dtype=float)


def metrics(y, pred, probabilities):
    import numpy as np
    from sklearn.metrics import accuracy_score, balanced_accuracy_score, classification_report, confusion_matrix, log_loss
    onehot = np.asarray([[int(label == c) for c in CLASSES] for label in y])
    return {"accuracy": float(accuracy_score(y, pred)), "balanced_accuracy": float(balanced_accuracy_score(y, pred)),
            "confusion_matrix": confusion_matrix(y, pred, labels=CLASSES).tolist(), "class_order": CLASSES,
            "classification_report": classification_report(y, pred, labels=CLASSES, output_dict=True, zero_division=0),
            "actual_counts": dict(Counter(y)), "predicted_counts": dict(Counter(pred)),
            "mean_confidence": float(probabilities.max(axis=1).mean()), "log_loss": float(log_loss(y, probabilities, labels=CLASSES)),
            "multiclass_brier": float(np.mean(np.sum((probabilities-onehot)**2, axis=1)))}


def train(output):
    import numpy as np
    import sklearn
    import joblib
    from sklearn.dummy import DummyClassifier
    from sklearn.linear_model import LogisticRegression
    from sklearn.ensemble import RandomForestClassifier
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler
    from sklearn.metrics import precision_score
    first = (output / "first-audit.txt").read_text(encoding="utf8")
    audit_result = json.loads((output / "audit.json").read_text(encoding="utf8")); assert audit_result["status"] == "PASS"
    expected = sum(sum(counts.values()) for counts in audit_result["class_counts"].values())
    assert first.startswith("PASS:") and f"examples={expected};" in first
    rows = {}
    for part in ("DISCOVERY", "VALIDATION"):
        with (output / f"{part.lower()}.csv").open(encoding="utf8") as source: rows[part] = list(csv.DictReader(source))
        assert all(r["temporal_split"] == part and r["session_date_utc"] < "2026-09-01" for r in rows[part])
    xs = {p:matrix(r) for p,r in rows.items()}; ys = {p:np.asarray([r["label_direction"] for r in records]) for p,records in rows.items()}
    estimators = {"naive_prior": DummyClassifier(strategy="prior"),
                  "logistic_balanced": make_pipeline(StandardScaler(), LogisticRegression(C=1.0, class_weight="balanced", max_iter=3000, random_state=42)),
                  "random_forest_balanced": RandomForestClassifier(n_estimators=300, max_depth=6, min_samples_leaf=30,
                         max_features="sqrt", class_weight="balanced_subsample", random_state=42, n_jobs=2)}
    results = {}; threshold_rows = []; lines = ["WIN M5 BASELINES - DISCOVERY training / VALIDATION comparison", "TEST September sealed; no TEST metrics.",
        "Fixed hyperparameters; no grid search or automatic threshold choice. DISCOVERY metrics are resubstitution, not out-of-sample.",
        "Probabilities are raw and not calibrated; class weighting changes their interpretation.", "Observations overlap in 15-minute horizons and are not independent trials.", ""]
    model_dir = output / "models"; model_dir.mkdir(exist_ok=False)
    for name, model in estimators.items():
        model.fit(xs["DISCOVERY"], ys["DISCOVERY"])
        if name == "logistic_balanced":
            scaler = model.named_steps["standardscaler"]
            assert scaler.n_samples_seen_ == len(ys["DISCOVERY"])
            assert np.allclose(scaler.mean_, xs["DISCOVERY"].mean(axis=0))
        assert list(model.classes_) == CLASSES
        results[name] = {}
        for part in ("DISCOVERY", "VALIDATION"):
            probabilities = model.predict_proba(xs[part]); pred = model.predict(xs[part]); y = ys[part]
            confidence = probabilities.max(axis=1); result = metrics(y, pred, probabilities); results[name][part] = result
            lines.extend([f"{name} / {part}: n={len(y)}, accuracy={result['accuracy']:.4f}, balanced_accuracy={result['balanced_accuracy']:.4f}",
                f"Actual: {result['actual_counts']}; predicted: {result['predicted_counts']}",
                "Confusion matrix: rows actual, columns predicted DOWN,FLAT,UP", str(result["confusion_matrix"])])
            for c in CLASSES:
                v = result["classification_report"][c]; lines.append(f"{c}: precision={v['precision']:.4f}, recall={v['recall']:.4f}, F1={v['f1-score']:.4f}, support={v['support']}")
            lines.append(f"Mean confidence={result['mean_confidence']:.4f}; log_loss={result['log_loss']:.4f}; Brier={result['multiclass_brier']:.4f}")
            with (output / f"{name}-{part.lower()}-predictions.csv").open("w",encoding="utf8",newline="") as f:
                writer=csv.writer(f);writer.writerow(["date","timestamp_utc","actual","predicted","confidence","p_DOWN","p_FLAT","p_UP"])
                for r,a,b,cf,ps in zip(rows[part],y,pred,confidence,probabilities):writer.writerow([r["session_date_utc"],r["timestamp_utc"],a,b,float(cf),*ps.tolist()])
            reliability=[]
            for low in np.arange(0.3,1.0,0.1):
                selected=(confidence>=low)&(confidence<low+0.1 if low<0.9 else confidence<=1)
                if selected.any(): reliability.append({"lower":float(low),"count":int(selected.sum()),"mean_confidence":float(confidence[selected].mean()),"observed_correct_fraction":float((pred[selected]==y[selected]).mean())})
            result["reliability_bins"]=reliability
            if part=="VALIDATION":
                for threshold in (.55,.60,.65,.70,.75,.80):
                    selected=confidence>=threshold;n=int(selected.sum());counts=Counter(pred[selected]);actual=Counter(y[selected])
                    row={"model":name,"threshold":threshold,"selected":n,"percent":100*n/len(y),
                         "precision_micro":float((pred[selected]==y[selected]).mean()) if n else None,
                         "mean_per_market_day":n/audit_result["market_days"][part]}
                    for c in CLASSES:
                        row[f"predicted_{c}"]=counts[c];row[f"actual_{c}"]=actual[c]
                        row[f"precision_{c}"]=float(precision_score(y[selected],pred[selected],labels=[c],average=None,zero_division=0)[0]) if counts[c] else None
                    threshold_rows.append(row)
                    lines.append("VALIDATION confidence: "+json.dumps(row))
            lines.append("")
        joblib.dump(model, model_dir / f"{name}.joblib")
    # Impurity importance is descriptive; correlated predictors can split importance.
    forest=estimators["random_forest_balanced"]
    ranking=sorted(zip(FEATURES,forest.feature_importances_),key=lambda item:item[1],reverse=True)
    with (output/"tree-feature-importance.csv").open("w",encoding="utf8",newline="") as f:
        writer=csv.writer(f);writer.writerow(["feature","impurity_importance","leakage_review"])
        for feature,importance in ranking:writer.writerow([feature,float(importance),"Closed current/past candle or fixed clock; no future column"])
    lines.append("Top 20 Random Forest impurity importances (correlation/cardinality bias possible):")
    lines.extend(f"{f}: {i:.6f}" for f,i in ranking[:20])
    lines.append("Leakage review: ATR-normalized inputs and clock are causal; none of the ranked inputs is a label or future timestamp. Importance does not prove causality.")
    with (output/"validation-confidence.csv").open("w",encoding="utf8",newline="") as f:
        writer=csv.DictWriter(f,fieldnames=list(threshold_rows[0]));writer.writeheader();writer.writerows(threshold_rows)
    (output/"metrics.json").write_text(json.dumps(results,indent=2)+"\n",encoding="utf8")
    (output/"comparison.txt").write_text("\n".join(lines)+"\n",encoding="utf8")
    manifest={"status":"VALIDATION_COMPLETE_TEST_SEALED","sklearn":sklearn.__version__,"features":FEATURES,
              "models":{n:str(m) for n,m in estimators.items()},"random_seed":42,"automatic_model_selection":False,"automatic_threshold_selection":False,
              "fit_partition":"DISCOVERY","evaluation_partitions":["DISCOVERY","VALIDATION"],
              "input_sha256":{p:hashlib.sha256((output/f"{p.lower()}.csv").read_bytes()).hexdigest() for p in rows}}
    (output/"experiment.json").write_text(json.dumps(manifest,indent=2)+"\n",encoding="utf8")
    render_report(output)
    for name,value in results.items():print(name,"VALIDATION",value["VALIDATION"]["accuracy"],value["VALIDATION"]["balanced_accuracy"])


def render_report(output):
    results = json.loads((output / "metrics.json").read_text(encoding="utf8"))
    audit_result = json.loads((output / "audit.json").read_text(encoding="utf8"))
    lines = ["# WIN M5 — baselines até VALIDATION", "",
             "Setembro permanece lacrado. Nenhum modelo ou threshold foi escolhido automaticamente. Nenhuma regra de trading foi alterada.", "",
             "## Auditoria", "",
             "Não foi identificado defeito que justifique corrigir ou regenerar o dataset. FIRST foi confirmado em todos os 9.010 exemplos de abril–agosto pela ordem original dos LASTs, em 101 dias. OHLC não foi usado para inferir a primeira passagem.", "",
             "A contagem anterior é reproduzida por 13.826 − 122 = **13.704**: um último bucket por cada um dos 122 dias com negócios. A pesquisa anterior só fechava um candle ao receber um LAST do bucket seguinte; o ML também fecha o último bucket após EOF de arquivo COMPLETE validado. Teste determinístico confirma a diferença de uma barra por sessão não vazia.", "",
             "A janela anterior de 180 dias é 04/04–30/09; a nova janela é 01/04–30/09. A nova janela acrescenta 01 e 02/04 ausentes e 03/04 válido vazio. Isso explica 2→4 dias ausentes e 178→179 dias válidos. Somente contagens de cobertura foram consultadas para setembro; nenhum resultado de TEST foi avaliado.", "",
             f"No prefixo abril–agosto: {audit_result['prefix_candles']} candles, 9.010 exemplos, {audit_result['warmup_context_discarded']} descartes de warm-up/contexto e {audit_result['insufficient_horizon']} sem horizonte. Ordem, duplicatas, contexto, sessões, indicadores fechados e labels passaram na auditoria.", "",
             "## Dados e método", "",
             "129 preditores: 7 normalizados no candle atual, 6 em cada um dos 20 candles anteriores, e seno/cosseno do horário local. A lista exata está em audit.json. Labels futuros, identidade, datas, partição e preços nominais foram descartados. Distância à EMA foi retirada por ser exatamente 4× seu delta. Volume/VWAP não estão disponíveis com confiabilidade verificada.", "",
             "Scaler ajustado exclusivamente em DISCOVERY; os coeficientes foram conferidos contra suas médias e quantidade de amostras. Os parâmetros foram fixados antes do treinamento: Logistic Regression balanceada C=1; Random Forest com 300 árvores, profundidade 6 e folha mínima 30, balanceada por bootstrap. Sem grid search. Seed 42.", "",
             "Métricas de DISCOVERY são de reavaliação do próprio treino, portanto otimistas. Horizons de 15 minutos se sobrepõem; as observações não são ensaios independentes. Probabilidades são brutas e não calibradas, especialmente com pesos de classe.", "",
             "| Partição | Exemplos | DOWN | FLAT | UP | Dias com mercado |", "|---|---:|---:|---:|---:|---:|"]
    for part, counts in audit_result["class_counts"].items():
        lines.append(f"| {part} | {sum(counts.values())} | {counts['DOWN']} | {counts['FLAT']} | {counts['UP']} | {audit_result['market_days'][part]} |")
    lines += ["", "## Comparação", "", "| Modelo | Partição | Accuracy | Balanced accuracy | Macro F1 | Log loss | Brier multiclasses | Confiança média |",
              "|---|---|---:|---:|---:|---:|---:|---:|"]
    for name, parts in results.items():
        for part, v in parts.items():
            lines.append(f"| {name} | {part} | {v['accuracy']:.4f} | {v['balanced_accuracy']:.4f} | {v['classification_report']['macro avg']['f1-score']:.4f} | {v['log_loss']:.4f} | {v['multiclass_brier']:.4f} | {v['mean_confidence']:.4f} |")
    for name, parts in results.items():
        for part, v in parts.items():
            lines += ["", f"### {name} — {part}", "", "Matriz: linhas reais, colunas previstas.", "", "| Real / Previsto | DOWN | FLAT | UP |", "|---|---:|---:|---:|"]
            for c, row in zip(CLASSES, v["confusion_matrix"]): lines.append(f"| {c} | {row[0]} | {row[1]} | {row[2]} |")
            lines += ["", "| Classe | Precision | Recall | F1 | Real | Previsto |", "|---|---:|---:|---:|---:|---:|"]
            for c in CLASSES:
                r = v["classification_report"][c]
                lines.append(f"| {c} | {r['precision']:.4f} | {r['recall']:.4f} | {r['f1-score']:.4f} | {v['actual_counts'].get(c,0)} | {v['predicted_counts'].get(c,0)} |")
            lines += ["", "Probabilidades por exemplo disponíveis no CSV de predictions; bins de confiança versus frequência de acerto disponíveis em metrics.json."]
    lines += ["", "## Confiança — somente agosto", "",
              "Seleção pela maior probabilidade entre as três classes, incluindo FLAT. Precision micro é a fração de previsões selecionadas corretas. Quantidade por dia usa todos os 21 dias com mercado, inclusive dias sem previsões selecionadas. Isso não define BUY/SELL nem uma estratégia. N/A significa nenhum caso selecionado.", "",
              "| Modelo | Threshold | Selecionados | % total | Precision micro | DOWN | FLAT | UP | Média/dia |",
              "|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
    with (output / "validation-confidence.csv").open(encoding="utf8") as source:
        for r in csv.DictReader(source):
            precision = f"{float(r['precision_micro']):.4f}" if r["precision_micro"] else "N/A"
            lines.append(f"| {r['model']} | {float(r['threshold']):.0%} | {r['selected']} | {float(r['percent']):.2f}% | {precision} | {r['predicted_DOWN']} | {r['predicted_FLAT']} | {r['predicted_UP']} | {float(r['mean_per_market_day']):.2f} |")
    lines += ["", "Precision por classe e distribuição real dos exemplos selecionados também estão em validation-confidence.csv.", "",
              "## Importância — Random Forest", "", "Importância por redução de impureza; pode favorecer variáveis contínuas e repartir importância entre variáveis correlacionadas. Não é efeito causal.", "",
              "| Feature | Importância | Revisão de leakage |", "|---|---:|---|"]
    with (output / "tree-feature-importance.csv").open(encoding="utf8") as source:
        for r in list(csv.DictReader(source))[:20]: lines.append(f"| {r['feature']} | {float(r['impurity_importance']):.6f} | Candle atual/anterior fechado ou relógio fixo |")
    lines += ["", "Horário está entre as maiores importâncias, mas é conhecido no momento da previsão. ATR/preço usa somente o candle atual; pode representar o regime de volatilidade e o tamanho do limite utilizado no label, sem informação futura. Deltas do contexto são históricos. Nenhuma das features ranqueadas é um label futuro. Não foi observado indício de leakage; isso não demonstra estabilidade fora de agosto.", "",
              "## Leitura dos resultados", "",
              "A Random Forest tem a maior balanced accuracy de agosto entre estes modelos, mas o ganho sobre 1/3 é modesto e há queda grande em relação ao treino. A precision de UP/DOWN permanece baixa. A baseline tem a maior accuracy porque prevê sempre FLAT. Não foi estabelecida capacidade forte ou estável de prever direção.", "",
              "A Random Forest não selecionou nenhum exemplo de agosto nos thresholds de 55% a 80%. Não alteramos esses thresholds para produzir resultados. Nenhum threshold/modelo foi congelado ou escolhido como vencedor. A próxima decisão depende da revisão de agosto; setembro segue lacrado."]
    (output / "report.md").write_text("\n".join(lines) + "\n", encoding="utf8")


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("mode",choices=["audit","train"])
    parser.add_argument("dataset_directory",type=Path);parser.add_argument("output_directory",type=Path);args=parser.parse_args()
    if args.mode=="audit":audit(args.dataset_directory,args.output_directory)
    else:train(args.output_directory)


if __name__=="__main__":main()
