"""Duration-aware, generation-only viability experiments; never production code.

Run with --help. Listening is private to the evaluator; generation candidates
have no API accepting it. The optional oracle is explicitly a reference only.
"""
from __future__ import annotations

import argparse
from collections import Counter, deque
from dataclasses import asdict, dataclass
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import random
import statistics
import time

from .algorithms import weighted_permutation
from .metrics import effective_choice_ratio, gini, normalized_entropy
from .model import Occurrence


@dataclass(frozen=True)
class Track(Occurrence):
    duration_ms: int = 210000
    artist_id: str = "artist"


@dataclass(frozen=True)
class Session:
    day: float
    generated_minutes: float | None
    consumed: float
    fraction: bool = False


@dataclass(frozen=True)
class Scenario:
    name: str
    size: int
    sessions: tuple[Session, ...]
    artists: str = "even"
    separation: bool = False
    mutation: bool = False
    duplicates: bool = False
    playback: str = "prefix"
    skip_probability: float = 0.0


@dataclass(frozen=True)
class Candidate:
    name: str
    signal: str = "binary"
    horizon: str = "last_n"
    strength: float = 0.45
    last_n: int = 2
    days: float = 7.0
    half_life_days: float = 3.0
    decay: float = 0.85
    opportunity_minutes: float = 60.0
    minimum: float = 0.35
    maximum: float = 2.0


class GenerationHistory:
    """Only ordered generated items and generation time can enter this state."""
    def __init__(self, candidate: Candidate):
        self.candidate = candidate
        self.events = deque()
        self.scores = {}
        self.last_day = None

    def weights(self, tracks, day):
        c = self.candidate
        if c.name == "true_random":
            return [1.0] * len(tracks)
        if c.horizon == "rolling":
            while self.events and self.events[0][0] <= day - c.days:
                self.events.popleft()
        if c.horizon in ("last_n", "rolling"):
            scores = {}
            for _, event in self.events:
                for key, value in event.items():
                    if c.signal == "binary":
                        scores[key] = 1.0
                    else:
                        scores[key] = scores.get(key, 0.0) + value
        else:
            factor = 1.0
            if c.horizon in ("elapsed", "combined") and self.last_day is not None:
                factor = 2 ** (-max(0, day - self.last_day) / c.half_life_days)
            scores = {key: value * factor for key, value in self.scores.items()}
        values = [scores.get(t.track_id, 0.0) for t in tracks]
        mean = statistics.fmean(values) if values else 0
        if c.signal == "binary":
            return [c.strength if value else 1.0 for value in values]
        return [min(c.maximum, max(c.minimum,
                math.exp(max(-50, min(50, -c.strength * (v - mean)))))) for v in values]

    def observe_generated(self, generated, day):
        c = self.candidate
        if c.name == "true_random":
            return
        event = {}
        start_minutes = 0.0
        for index, track in enumerate(generated):
            if c.signal in ("binary", "count", "oracle"):
                influence = 1.0
            elif c.signal == "ordinal":
                influence = math.exp(-3 * index / max(1, len(generated) - 1))
            elif c.signal == "duration":
                influence = math.exp(-start_minutes / c.opportunity_minutes)
            else:
                raise ValueError(c.signal)
            if c.signal == "binary":
                event[track.track_id] = 1.0
            else:
                event[track.track_id] = event.get(track.track_id, 0.0) + influence
            start_minutes += track.duration_ms / 60000
        if c.horizon in ("last_n", "rolling"):
            self.events.append((day, event))
            if c.horizon == "last_n":
                while len(self.events) > c.last_n:
                    self.events.popleft()
        else:
            factor = c.decay if c.horizon in ("generation", "combined") else 1.0
            if c.horizon in ("elapsed", "combined") and self.last_day is not None:
                factor *= 2 ** (-max(0, day - self.last_day) / c.half_life_days)
            self.scores = {k: v * factor for k, v in self.scores.items()}
            for key, influence in event.items():
                self.scores[key] = self.scores.get(key, 0.0) + influence
        self.last_day = day


def candidates():
    result = [Candidate("true_random")]
    result += [Candidate(f"binary_n{n}", strength=0.35, last_n=n) for n in (1, 2, 3)]
    result += [Candidate("binary_7days", horizon="rolling", strength=0.35)]
    result += [Candidate("ordinal_decay", signal="ordinal", horizon="generation")]
    result += [Candidate("duration_decay", signal="duration", horizon="generation")]
    result += [Candidate("duration_elapsed", signal="duration", horizon="elapsed")]
    result += [Candidate("duration_combined", signal="duration", horizon="combined")]
    result += [Candidate("duration_n2", signal="duration", last_n=2)]
    result += [Candidate("generation_count", signal="count", horizon="count")]
    result += [Candidate("heard_oracle", signal="oracle", horizon="generation")]
    return result


def make_tracks(size, seed, shape="even", offset=0):
    rng = random.Random(seed)
    choices = {
        "even": [1] * 20,
        "moderate": [20, 15, 10] + [5] * 11,
        "kpop": [25, 20, 15, 10, 8, 6, 4, 4, 4, 4],
        "dominant": [75] + [5] * 5,
    }[shape]
    result = []
    for index in range(offset, offset + size):
        # Synthetic curated-pop distribution: median ~3.5m, bounded 1.5-7m.
        duration = int(max(90, min(420, rng.lognormvariate(math.log(210), 0.24))) * 1000)
        artist = rng.choices(range(len(choices)), weights=choices)[0]
        result.append(Track(f"occ-{index}", f"track-{index}", duration, f"artist-{artist}"))
    return result


def duration_prefix(tracks, minutes):
    if minutes is None:
        return list(tracks)
    if minutes <= 0:
        raise ValueError("generated minutes must be positive")
    selected, total = [], 0
    for track in tracks:
        selected.append(track)
        total += track.duration_ms
        if total >= minutes * 60000:
            break
    return selected


def separate(tracks, rng, function):
    items = [{"primary_artist_id": t.artist_id, "track": t} for t in tracks]
    return [item["track"] for item in function(items, rng=rng)]


def heard_tracks(generated, minutes, rng, playback="prefix", skip_probability=0.0):
    remaining = max(0, minutes) * 60000
    order = list(generated)
    if playback == "shuffle":
        rng.shuffle(order)
    heard = []
    for track in order:
        if remaining <= 0:
            break
        if rng.random() < skip_probability:
            continue
        spent = min(remaining, track.duration_ms)
        # Primary endpoint: >=50% of the track consumed. No completion overshoot.
        if spent >= track.duration_ms / 2:
            heard.append(track)
        remaining -= spent
    return heard


def simulate(scenario, candidate, seed, separator=None):
    active = make_tracks(scenario.size, seed + 991, scenario.artists)
    if scenario.duplicates:
        t = active[0]
        active.append(Track("duplicate", t.track_id, t.duration_ms, t.artist_id))
    universe = {t.track_id for t in active}
    history = GenerationHistory(candidate)
    counts, first, last, artist_counts = Counter(), {}, {}, Counter()
    repeats, gaps, overlaps, curves, choices, entropies, ratios = [], [], [], [], [], [], []
    previous = set()
    generated_counts = Counter()
    generated_total_minutes = consumed_total_minutes = 0.0
    mutated_ids = set()
    for operation, session in enumerate(scenario.sessions):
        if scenario.mutation and operation == len(scenario.sessions) // 2:
            remove = max(1, scenario.size // 10)
            active = active[remove:]
            additions = make_tracks(remove, seed + 1777, scenario.artists, scenario.size + 10)
            active += additions
            mutated_ids = {t.track_id for t in additions}
            universe.update(mutated_ids)
        weights = history.weights(active, session.day)
        choices.append(effective_choice_ratio(weights))
        entropies.append(normalized_entropy(weights))
        ratios.append(min(weights) / max(weights))
        rng = random.Random(seed * 1000003 + operation * 8191)
        if candidate.name == "true_random":
            ordered = list(active)
            rng.shuffle(ordered)  # Same uniform shuffle distribution as production.
        else:
            ordered = weighted_permutation(active, weights, rng)
        generated = duration_prefix(ordered, session.generated_minutes)
        if scenario.separation:
            if separator is None:
                raise ValueError("Artist Separation function required")
            generated = separate(generated, random.Random(seed * 1741 + operation), separator)
        generated_minutes = sum(t.duration_ms for t in generated) / 60000
        consume = session.consumed * generated_minutes if session.fraction else session.consumed
        heard = heard_tracks(generated, consume, random.Random(seed * 4421 + operation),
                             scenario.playback, scenario.skip_probability)
        # Enforced boundary: ONLY the explicitly labeled oracle receives heard.
        observation = heard if candidate.signal == "oracle" else generated
        history.observe_generated(observation, session.day)
        generated_counts.update(t.track_id for t in generated)
        generated_total_minutes += generated_minutes
        consumed_total_minutes += min(consume, generated_minutes)
        current = {t.track_id for t in heard}
        if previous and current:
            overlaps.append(len(previous & current) / min(len(previous), len(current)))
        previous = current  # Empty generations explicitly break consecutive overlap.
        for t in heard:
            repeats.append(int(counts[t.track_id] > 0))
            if t.track_id in last:
                gaps.append(operation - last[t.track_id])
            counts[t.track_id] += 1
            artist_counts[t.artist_id] += 1
            first.setdefault(t.track_id, operation + 1)
            last[t.track_id] = operation
        # Mutation denominator is the union encountered to date, not final source size.
        curves.append(len(counts) / len(universe) * 100)
    exposure = [counts[k] for k in universe]
    source_artist = Counter(t.artist_id for t in active)
    total_heard = sum(counts.values())
    artist_l1 = sum(abs(artist_counts[k] / max(1, total_heard)
                       - source_artist[k] / len(active)) for k in source_artist) / 2
    return {
        "scenario": scenario.name, "size": scenario.size, "seed": seed,
        "candidate": candidate.name, "oracle": candidate.signal == "oracle",
        "coverage": len(counts) / len(universe) * 100,
        "coverage10": curves[min(9, len(curves) - 1)],
        "coverage24": curves[min(23, len(curves) - 1)],
        "coverage_auc": statistics.fmean(curves),
        "unique_heard": len(counts), "heard_events": total_heard,
        "repeat_fraction": statistics.fmean(repeats) if repeats else 0,
        "repeat_distance": statistics.fmean(gaps) if gaps else 0,
        "first_exposure_observed": statistics.fmean(first.values()) if first else 0,
        "first_exposure_censored": 1 - len(first) / len(universe),
        "exposure_gini": gini(exposure),
        "exposure_cv": statistics.pstdev(exposure) / statistics.fmean(exposure) if total_heard else 0,
        "overlap": statistics.fmean(overlaps) if overlaps else 0,
        "artist_distance": artist_l1,
        "effective_choice_min": min(choices), "entropy_min": min(entropies),
        "weight_ratio_min": min(ratios),
        "unheard_generated_fraction": len(set(generated_counts) - set(counts)) / max(1, len(generated_counts)),
        "added_coverage": len(mutated_ids & set(counts)) / len(mutated_ids) * 100 if mutated_ids else None,
        "generated_minutes": generated_total_minutes, "consumed_minutes": consumed_total_minutes,
    }


def scenarios(rotations=36):
    result = []
    def add(name, size, pairs, **kwargs):
        sessions = tuple(Session(i, *pairs[i % len(pairs)]) for i in range(rotations))
        result.append(Scenario(name, size, sessions, **kwargs))
    # Full fractions use actual generated duration; timed consumption is independent.
    for size in (50, 100, 300, 500, 1000):
        for generated, consumption in ((30,30),(60,30),(60,60),(60,45),(60,20),(120,120),(120,60),(120,30),
                                        (180,180),(180,60),(180,45),(180,30)):
            add(f"{generated}M/{consumption}M", size, [(generated, consumption)])
        for fraction in (0.05, 0.5, 1.0):
            add(f"Full/{fraction:.0%}", size, [(None, fraction, True)])
        add("Full/30M", size, [(None,30)])
        add("variable", size, [(180,20),(180,90),(180,0),(180,45),(180,180)])
        add("mixed", size, [(60,45),(180,30),(60,20),(None,45),(120,60)])
        add("abandon_half", size, [(180,0),(180,45)])
        add("regenerate", size, [(180,0),(180,0),(180,0),(180,45)])
        add("custom37", size, [(37,20)])
    for shape in ("even", "moderate", "kpop", "dominant"):
        for mode, consume in ((60,45),(180,30),(None,30),(None,0.5)):
            for separation in (False, True):
                add(f"artist_{shape}_{mode}_{consume}_sep{int(separation)}", 500,
                    [(mode, consume, mode is None and consume == 0.5)],
                    artists=shape, separation=separation)
    for size in (50,500,1000):
        add("mutation", size, [(180,45)], mutation=True)
        add("duplicates", size, [(60,45)], duplicates=True)
    for interval, label in ((1/6,"heavy"),(7,"sparse"),(1,"daily")):
        for mode in (60,180,None):
            result.append(Scenario(f"{label}_{mode}", 500,
                tuple(Session(i * interval, mode,30) for i in range(rotations))))
    for mode in (60,180,None):
        add(f"shuffled_{mode}",500,[(mode,30)],playback="shuffle")
        add(f"skips_{mode}",500,[(mode,30)],skip_probability=0.3)
    result.append(Scenario("long_inactivity",500,tuple(
        Session(i if i < 12 else i + 60,180,30) for i in range(rotations))))
    # Same listening budget, but unplayed generations occur within minutes.
    result.append(Scenario("burst_regenerate",500,tuple(
        Session((i // 4) * 2 + (i % 4)/1440,180,45 if i % 4 == 3 else 0)
        for i in range(rotations))))
    return result


def aggregate(rows):
    grouped = {}
    for row in rows:
        grouped.setdefault((row["scenario"],row["size"],row["candidate"]),[]).append(row)
    lookup = {(r["scenario"],r["size"],r["seed"]):r for r in rows if r["candidate"] == "true_random"}
    output = []
    for (scenario,size,candidate), group in sorted(grouped.items()):
        record = {"scenario":scenario,"size":size,"candidate":candidate,"seeds":len(group)}
        for key,value in group[0].items():
            if key not in record and isinstance(value,(float,int)) and not isinstance(value,bool):
                record[key] = statistics.fmean(r[key] for r in group)
        for metric in ("coverage","coverage10","coverage_auc","overlap","repeat_fraction"):
            deltas = [r[metric]-lookup[(scenario,size,r["seed"])][metric] for r in group]
            record[f"delta_{metric}"] = statistics.fmean(deltas)
            record[f"delta_{metric}_ci95"] = (1.96 * statistics.stdev(deltas) / math.sqrt(len(deltas))
                                                 if len(deltas)>1 else None)
        output.append(record)
    return output


def load_separator(path):
    spec = importlib.util.spec_from_file_location("research_artist_separation",path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.separate_artists


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--artist-module",type=Path,required=True)
    parser.add_argument("--seeds",type=int,default=8)
    parser.add_argument("--seed-start",type=int,default=0)
    parser.add_argument("--rotations",type=int,default=36)
    parser.add_argument("--candidates",default="")
    parser.add_argument("--scenario-filter",default="")
    parser.add_argument("--sensitivity",action="store_true")
    args = parser.parse_args()
    if args.seeds < 2 or args.rotations < 2:
        parser.error("at least two seeds and sessions required")
    selected = candidates()
    if args.sensitivity:
        selected = [Candidate("true_random")]
        for tau in (20,60,120):
            for strength in (0.2,0.45,0.9):
                selected.append(Candidate(f"duration_tau{tau}_p{strength}",signal="duration",
                    horizon="generation",strength=strength,opportunity_minutes=tau))
        for decay in (0.5,0.95):
            selected.append(Candidate(f"duration_decay{decay}",signal="duration",
                horizon="generation",decay=decay))
        for penalty in (0.1,0.65):
            selected.append(Candidate(f"binary_n2_p{penalty}",strength=penalty,last_n=2))
        for strength in (0.2,0.9):
            selected.append(Candidate(f"count_p{strength}",signal="count",
                horizon="count",strength=strength))
    if args.candidates:
        names = set(args.candidates.split(",")) | {"true_random"}
        selected = [c for c in selected if c.name in names]
    matrix = scenarios(args.rotations)
    if args.scenario_filter:
        names = set(args.scenario_filter.split(","))
        matrix = [s for s in matrix if s.name in names]
    separator = load_separator(args.artist_module)
    start = time.perf_counter()
    rows = []
    for index,scenario in enumerate(matrix):
        for candidate in selected:
            for seed in range(args.seed_start,args.seed_start + args.seeds):
                rows.append(simulate(scenario,candidate,seed,separator))
        if index % 10 == 0:
            print(f"{index+1}/{len(matrix)} scenarios; {len(rows)} trials",flush=True)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    payload = {
        "method": "synthetic durations; >=50% listened counts heard; paired source/schedule seeds",
        "seeds":args.seeds,"seed_start":args.seed_start,"rotations":args.rotations,
        "runtime_seconds":time.perf_counter()-start,
        "artist_module_sha256":hashlib.sha256(args.artist_module.read_bytes()).hexdigest(),
        "candidates":[asdict(c) for c in selected],"scenarios":[asdict(s) for s in matrix],
        "trials":rows,"summary":aggregate(rows),
    }
    args.output.write_text(json.dumps(payload,indent=2),encoding="utf-8")
    print(f"Saved {len(rows)} trials to {args.output}; {payload['runtime_seconds']:.1f}s",flush=True)


if __name__ == "__main__":
    main()
