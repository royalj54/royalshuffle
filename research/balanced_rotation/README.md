# Balanced Rotation research harness

This directory is isolated simulation tooling. It is not imported by RoyalShuffle's
production shuffle, UI, CLI, Spotify, CSV, packaging, or Android code.

## Identity model

Every playlist entry has a unique `occurrence_id` and an underlying `track_id`.
Permutations operate on occurrences, so duplicate playlist entries survive. Candidate
history is keyed by track, meaning duplicate occurrences share history and weight. If
two duplicates are heard in one session, Candidate B records two exposures and
Candidate C records the opportunity supplied by both placements. Reports distinguish
occurrence-level and unique-track-level exposure and coverage.

## Algorithms

- True Random assigns every occurrence weight 1.
- Candidate A is an **oracle benchmark**. A never-exposed track receives the explicit
  bounded weight 1.35. After exposure, with age `a`, its raw weight is
  `1 - 0.55 exp(-a/3) + 0.35(1-exp(-a/3))`, bounded to `[0.35, 1.50]`.
  Thus a just-exposed track is penalized, the penalty decays, and a long-unexposed
  track approaches 1.35. Never-exposed state is represented by absence from the
  `last_exposure` map, not a fabricated age.
- Candidate B is an **oracle benchmark**. Scores decay by 0.85 per operation and each
  actual heard occurrence adds 1. The weight is
  `clamp(exp(-0.45(score-mean_score)), 0.35, 2.0)`.
- Candidate C receives no listening depth. It scores generated position `x` using
  `exp(-3x)`, decays prior scores by 0.85, and uses the same centered weight formula
  and bounds as B. Its perceived score is compared with actual synthetic exposure
  only by the evaluator.

These defaults are starting hypotheses, not fitted parameters. All are dataclass
configuration fields.

Weighted permutations use independent exponential keys and sorting. This is a
probabilistic weighted permutation without replacement: every occurrence remains
possible and no order is scheduled.

## Run

Ordinary simulation and topology research are self-contained standard-library
Python tooling. The suite has 39 tests, including two external Windows-reference
integration checks. Supply those files explicitly before running the complete
suite (PowerShell, from the repository root):

```powershell
$env:ROYALSHUFFLE_RESEARCH_ARTIST_MODULE = '<reference-checkout>/artist_separation.py'
$env:ROYALSHUFFLE_RESEARCH_APP_MODULE = '<reference-checkout>/royalshuffle.py'
python -B -m unittest discover -s research/balanced_rotation -p "test_*.py"
```

An unset variable skips only its integration check with an explanatory message.
An empty, missing, or invalid supplied reference fails the check. No checkout is
searched or inferred. The artist reference must match the SHA-256 recorded in
`results/viability/manifest.json`; use the recorded Windows revision for the app
reference too. Viability experiments separately require `--artist-module`.

Historical manifest paths record the original workstation and must be replaced
with explicitly chosen local references when reproducing runs. Historical hashes
describe the original study files, before portability edits. Seeded measurements
are reproducible under the recorded model/reference environment; elapsed-runtime
fields mean output files are not expected to be byte-identical. Report decisions,
validation counts, and worktree statements describe the study when performed, not
current Android release status. This is research analysis and evidence, not
production runtime code.

The commands below reproduce experiments and write their specified output paths.
Use a new output directory outside this tree for validation to preserve historical
evidence. The detailed `results/smoke/smoke-results.json` is intentionally retained
locally and excluded from the research checkpoint; its CSV and summary are retained.

From the repository root:

```text
python -B -m unittest discover -s research/balanced_rotation -p "test_*.py"
python -m research.balanced_rotation.runner --matrix smoke --output-mode detailed --output-dir research/balanced_rotation/results/smoke
python -m research.balanced_rotation.runner --matrix main --output-mode compact --output-dir research/balanced_rotation/results/main
python -m research.balanced_rotation.topology_runner --matrix smoke --output-mode compact --output-dir research/balanced_rotation/results/topology-smoke
python -m research.balanced_rotation.topology_runner --matrix main --output-mode compact --output-dir research/balanced_rotation/results/topology-main
```

The runner emits detailed JSON, flat CSV, and a Markdown comparison table. Every
trial records its seed, playlist size, consumption model, rotation count, algorithm,
oracle status, parameters, reset configuration, and mutation count.

Compact JSON retains metadata, aggregate exposure/coverage/repetition/randomness metrics,
warnings, coverage checkpoints, and runtime, but omits per-track exposure maps and full
coverage curves. Detailed mode retains those structures for targeted investigations.

The topology runner partitions one source corpus into independently balanced pools.
Assignment uses a dedicated seeded shuffle followed by round-robin distribution, and
pool selection and each pool's shuffle use independent deterministic RNG streams.
Every trial has one fixed global session budget regardless of pool count. Compact
topology output includes corpus coverage/zero-exposure checkpoints at global sessions
10, 25, 50, 75, and 100 plus the exact session count allocated to every pool.

## Duration-aware generation-only viability study

`viability.py` extends the research with duration-bearing occurrences, independent
generation/consumption budgets, elapsed time, source mutations, artist distributions,
and an explicitly supplied read-only production Artist Separation implementation.
The original count-based experiments above remain unchanged.

```powershell
python -B -m research.balanced_rotation.viability --output research/balanced_rotation/results/viability/screen.json --artist-module <Windows-worktree>/artist_separation.py --seeds 8 --rotations 36
python -B -m research.balanced_rotation.analyze_viability research/balanced_rotation/results/viability/screen.json --output research/balanced_rotation/results/viability/comparisons.md
```

Use `--seed-start` for independent confirmation seeds, `--candidates` and
`--scenario-filter` for bounded follow-ups, and `--sensitivity` for a small parameter
sweep. All random streams and source metadata are deterministic. The True Random
control uses a seeded uniform `shuffle` with the same distribution as production;
production's operating-system randomness is not changed.

Generation-only state accepts only final generated ordering and generation time.
It never receives listening duration, skips, abandonment, or heard tracks. Only the
explicit `heard_oracle` reference observes simulated hearing. State is track-keyed;
permutations and timed selection preserve duplicate occurrences.

Durations are synthetic lognormal draws (median 210 seconds, bounded 90–420 seconds),
not Spotify-derived data. Timed outputs include the track crossing their duration
target. Listening spends an independent time budget without overshoot; at least
half a track must be consumed to count it as heard. Fractional Full consumption is
relative to generated duration. The simulator models no repeat playback, seeking,
or background Spotify data. Requested consumption totals are budgets, not measured
playback, especially when skips exhaust the available output.

Coverage uses unique heard track IDs; mutation scenarios use the union of encountered
IDs (including removed tracks). Observed first-exposure averages are conditional on
being heard and must be read with the censored fraction. AUC is mean cumulative
coverage over generation events, so abandoned generations remain in its time axis.
Entropy/effective-choice metrics describe initial weights, not final-permutation
entropy. Comparison intervals describe Monte Carlo uncertainty under this synthetic
model, with no multiple-comparison correction. Stored scenario definitions, candidate
parameters, per-seed metrics, and Artist Separation file hash support reproduction.
