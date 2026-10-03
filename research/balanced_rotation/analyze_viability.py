"""Reproducible compact comparisons of viability trial files."""
import argparse
import json
from pathlib import Path
import statistics


def table(lines, headers, rows):
    lines.append("| " + " | ".join(headers) + " |")
    lines.append("| " + " | ".join("---" for _ in headers) + " |")
    for row in rows:
        lines.append("| " + " | ".join(str(v) for v in row) + " |")
    lines.append("")


def report(paths):
    lines = ["# Generation-only viability: measured comparisons", "",
        "All coverage deltas are percentage points against the paired True Random control.",
        "AUC is mean cumulative heard coverage across generations, not playback-time AUC.",
        "Intervals are approximate 95% paired Monte Carlo normal intervals; no multiple-test correction.",
        "These synthetic measurements do not establish production viability.", ""]
    for path in paths:
        data = json.loads(path.read_text(encoding="utf-8"))
        summary = data["summary"]
        lines += [f"## {path.name}", "",
            f"{len(data['scenarios'])} scenarios, {len(data['candidates'])} candidates, "
            f"{data['seeds']} seeds, {data['rotations']} generations; "
            f"{len(data['trials'])} trials, {data['runtime_seconds']:.1f} seconds.", ""]
        rows = []
        for c in data["candidates"]:
            group = [r for r in summary if r["candidate"] == c["name"]]
            rows.append([c["name"],f"{statistics.fmean(r['delta_coverage10'] for r in group):+.2f}",
                f"{statistics.fmean(r['delta_coverage_auc'] for r in group):+.2f}",
                f"{statistics.fmean(r['delta_coverage'] for r in group):+.2f}",
                f"{min(r['delta_coverage_auc'] for r in group):+.2f}",
                f"{min(r['effective_choice_min'] for r in group):.3f}",
                f"{min(r['weight_ratio_min'] for r in group):.3f}"])
        table(lines,["Candidate","Mean delta coverage10","Mean delta AUC","Mean delta final",
                    "Worst AUC delta","Minimum effective choice","Minimum weight ratio"],rows)
        lines += ["### Selected 500-track comparisons", ""]
        selected = {"30M/30M","60M/30M","60M/60M","60M/20M","120M/30M","180M/180M","180M/30M",
            "Full/30M","Full/50%","Full/100%","regenerate","abandon_half","mixed",
            "heavy_180","sparse_180","daily_180","shuffled_180","shuffled_None",
            "skips_180","long_inactivity","burst_regenerate","mutation"}
        rows = []
        for r in summary:
            if r["size"] != 500 or r["scenario"] not in selected:
                continue
            rows.append([r["scenario"],r["candidate"],f"{r['coverage10']:.2f}",
                f"{r['coverage']:.2f}",f"{r['delta_coverage_auc']:+.2f} +/- {r['delta_coverage_auc_ci95']:.2f}",
                f"{r['overlap']:.3f}",f"{r['repeat_distance']:.1f}",
                f"{r['exposure_gini']:.3f}",f"{r['effective_choice_min']:.3f}"])
        table(lines,["Scenario","Candidate","Coverage10 %","Final %","AUC delta +/- CI",
                    "Overlap","Mean repeat distance","Exposure Gini","Effective choice"],rows)
        lines += ["### Artist Separation comparisons", ""]
        index = {(r["scenario"],r["candidate"]):r for r in summary}
        rows = []
        for r in summary:
            if not r["scenario"].startswith("artist_") or not r["scenario"].endswith("sep1"):
                continue
            off = index[(r["scenario"][:-1]+"0",r["candidate"])]
            rows.append([r["scenario"],r["candidate"],f"{r['delta_coverage_auc']:+.2f}",
                f"{off['delta_coverage_auc']:+.2f}",f"{r['coverage_auc']-off['coverage_auc']:+.2f}",
                f"{r['artist_distance']:.3f}"])
        table(lines,["Scenario","Candidate","Balanced AUC delta sep ON","Delta sep OFF",
                    "Absolute AUC ON minus OFF","Artist total-variation distance"],rows)
    return "\n".join(lines)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("inputs",nargs="+",type=Path)
    p.add_argument("--output",required=True,type=Path)
    args = p.parse_args()
    args.output.write_text(report(args.inputs),encoding="utf-8")


if __name__ == "__main__":
    main()
