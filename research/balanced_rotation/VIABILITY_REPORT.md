# Balanced Rotation: generation-only viability study

Research only. No production, Android, Windows 0.4.7, version, installer, or release
changes. No Spotify data or playback APIs. Production implementation is not recommended
on the strength of these simulations alone.

## 1. Summary of harness changes

The original research remains intact. A new duration-aware layer reuses `Occurrence`,
`weighted_permutation`, and existing entropy/Gini metrics. It models current timed
prefix selection, independent consumption, Full/60M/Custom, relative elapsed days,
mutation, duplicates, artist concentration, and downstream production Artist Separation.
Generation-only history accepts generated items and timestamps; it has no listening
input. The evaluator alone knows listening, except for an explicitly labeled oracle.

## 2. Files changed or added

- Updated: `README.md`.
- Added: `viability.py`, `analyze_viability.py`, `test_viability.py`, this report.
- Added under `results/viability/`: `screen.json`, `confirmation.json`,
  `matched_budget.json`, `sensitivity.json`, `count_sensitivity.json`,
  `comparisons.md`, `manifest.json`.

All are inside `research/balanced_rotation/`. This research directory was already
untracked. No existing research results were overwritten outside the new subdirectory.

## 3. Candidate algorithms

| Candidate | Generation signal | Horizon |
| --- | --- | --- |
| True Random | None; uniform seeded shuffle | None |
| Binary N=1/2/3 | Track included in recent output; weight 0.35 versus 1 | Last N generations |
| Binary seven-day | Same binary union | Rolling seven days |
| Ordinal opportunity | exp(-3 * normalized generated position) | Per-generation decay 0.85 |
| Duration opportunity | exp(-generated start minute / 60) | Per-generation decay 0.85 |
| Duration elapsed | Same duration signal | Three-day half-life |
| Duration combined | Same duration signal | Elapsed and generation decay |
| Duration N=2 | Same duration signal | Last two generations |
| Generation count | Each generated occurrence increments its track count | Cumulative |
| Heard oracle | Each simulated heard occurrence increments score | Per-generation decay 0.85 |

Nonbinary weights are `clamp(exp(-0.45*(score-mean)), 0.35, 2)`. Duplicate
occurrences share track state but remain separate permutation members. All weights
remain positive; no exclusion or round-robin scheduler was tested.

The ordinal candidate adapts the prior OpportunityBalance formula to actual selected,
final generated output. The original algorithm/harness are not changed. The oracle is
a listening-informed reference, not a mathematical performance upper bound.

## 4. Methodology

Synthetic durations are lognormal, median 210 seconds, clipped to 90–420 seconds.
They are plausible curated-pop durations, not a fitted real-playlist dataset. Timed
selection includes the target-crossing track. Actual listening has an independent
budget and does not overshoot: >=50% consumed counts as heard.

True Random uses `random.Random(seed).shuffle`, with production's uniform distribution;
production itself continues using SystemRandom. Candidate ordering uses existing
weighted exponential keys. Source metadata, schedules, and playback RNG streams are
paired by seed. Different algorithms do not necessarily make identical random draws.

Generation signals observe final post-separation outputs, including abandoned outputs.
No candidate receives consumption, skipping, or abandonment. The entire study assumes
successful output writes; cancellation and persistence failures are outside this model.

## 5. Scenario matrix and run sizes

| Stage | Scenarios | Seeds | Generations/trial | Trials |
| --- | --- | --- | --- | --- |
| Screening | 150 | 0–7 | 36 | 14,400 |
| Independent confirmation, five candidates | 37 | 100–115 | 48 | 2,960 |
| Fixed 30M consumption comparisons | 15 | 100–115 | 48 | 1,200 |
| Opportunity/binary sensitivity | 15 | 200–207 | 36 | 1,680 |
| Count sensitivity | 15 | 200–207 | 36 | 360 |
| Total | | | | 20,600 |

Sizes: 50, 100, 300, 500, 1,000. Screening includes all requested 60M, 120M,
180M mismatches; Full 5%, 50%, 100%, and Full/30M; variable and mixed sessions;
half-abandonment; three unplayed regenerations before each listen; 37M Custom;
mutation and duplicates; six daily generations, weekly sessions, a 60-day inactivity
gap, rapid regeneration bursts; skipped tracks and shuffled playback. Artist shapes:
even, moderate, K-pop-like, dominant; Full/timed with separation on/off.

The final default matrix also includes 30M/30M and 60M/30M. These were added after
screening to isolate mismatch at fixed listening capacity. The manifest records exact
filters to reproduce each saved run rather than accidentally expanding its matrix.

## 6. Metrics

Unique heard tracks; coverage at 10/24/final generations; mean cumulative coverage
(AUC); repeat-event fraction; repeat distance in generations; observed first-exposure
generation with censoring; exposure Gini/CV; adjacent-generation overlap; artist
total-variation distance from source proportions; unseen generated fraction; new-track
coverage; initial-weight entropy, effective-choice ratio, and minimum weight ratio.

Coverage deltas are percentage points. Paired 95% intervals are approximate Monte
Carlo normal intervals, without multiple-comparison correction. AUC is generation-
indexed, not elapsed-day or listened-minute AUC. First-exposure means are conditional
on exposure; zero-exposure tracks remain censored. Overlap only has eligible pairs
when adjacent generations both contain hearing; zero denotes no eligible pair for
regeneration patterns, not proof of zero repetition.

## 7. True Random baseline

Independent confirmation, 500 tracks, 48 generations:

| Generation / consumption | Unique heard | Coverage | Repeat-event fraction | Exposure Gini |
| --- | --- | --- | --- | --- |
| 60M / 60M | 402.8 | 80.55% | 50.0% | 0.419 |
| 180M / 30M | 277.9 | 55.58% | 31.1% | 0.568 |
| Full / 30M | 277.9 | 55.58% | 31.1% | 0.568 |
| Regenerate three times, then 180M / 45M | 132.6 | 26.53% | 12.5% | 0.763 |

These are ordinary sampling outcomes, not defects. For 60M/60M, observed first
exposure averages generation 18.1 with 19.4% still unheard; at 180M/30M it averages
21.2 with 44.4% unheard. Mean repeat distances are 12.6 and 14.4 generations.

## 8. Candidate results

Screening mean AUC gains across its deliberately heterogeneous, equally weighted
scenario cases: binary N=1/2/3 +0.31/+0.54/+0.68 pp; rolling binary +0.94;
ordinal +0.58; duration-generation +0.68; duration-elapsed +0.47; combined +0.34;
duration-N2 +0.32; cumulative count +1.11; heard oracle +1.12. These are descriptive
matrix averages, not estimates for a real user population or isolated causal effects.

Confirmation final coverage for 500 tracks:

| Scenario | True Random | Binary seven-day | Duration decay | Generation count |
| --- | --- | --- | --- | --- |
| 60M/60M | 80.55% | 84.36% | 81.78% | 89.29% |
| 180M/30M | 55.58% | 57.02% | 57.25% | 58.30% |
| Mixed | 65.74% | 65.65% | 66.73% | 69.27% |
| 37M Custom / 20M | 41.85% | 43.58% | 42.96% | 45.44% |
| Repeated regeneration | 26.53% | 26.55% | 26.30% | 26.83% |

Simple count is strongest in these timed comparisons; a more sophisticated
opportunity proxy did not produce larger gains. Count does not decay and needs a
retention/reset policy before it could be a product design.

## 9. Generation-versus-consumption mismatch

Hold source, seeds, 48 generations, and actual consumption fixed at 30M:

| Generated | True Random coverage | Count gain | Duration gain |
| --- | --- | --- | --- |
| 30M | 55.58% | +5.86 pp | +1.85 pp |
| 60M | 55.58% | +5.19 pp | +1.74 pp |
| 120M | 55.58% | +3.12 pp | +1.65 pp |
| 180M | 55.58% | +2.72 pp | +1.67 pp |
| Full | 55.58% | No distributional benefit | +1.69 pp |

Count AUC gains decline from +2.38 +/-0.49 pp at 30M to +1.23 +/-0.49 at
180M. Duration gains remain approximately +0.76–0.87 pp AUC, with interval half-widths
~0.48–0.49. Duration is more mismatch-stable but the gain is small. There is no
universal acceptable generation/consumption ratio demonstrated by this study.

## 10. Adversarial findings

Overgeneration weakens membership-count gains. Repeated regeneration removes the
measurable gain: count AUC -0.01 +/-0.22 pp, duration -0.21 +/-0.22. Rapid bursts
have the same conclusion. Half-abandonment has modest screening gains, but no
consistent robustness follows from that easier pattern.

Mixed Full outputs neutralize binary-window distinctions because every track becomes
recent. Count retains prior relative differences when Full adds the same increment
to every unique track. Shuffled Full playback removes the ordering opportunity:
duration AUC +0.09 +/-0.61 pp. Shuffled 180M retains a small membership effect for
binary (+0.53 +/-0.45); duration is -0.09 +/-0.38. Mutation and rare duplicates
show no representation failure; old/new count imbalance remains a semantic issue.

## 11. Full-output findings

Binary inclusion and generation counts are mathematically uniform under repeated
unchanged Full outputs with unique tracks. Small measured differences from control
are Monte Carlo differences between uniform permutations, not genuine benefits.

Duration weighting has a small sequential-prefix benefit (+1.69 pp final coverage
at 500 tracks), including after Artist Separation. Full consumed entirely already
covers every track in one session. Half consumption rapidly saturates. No strong
case for Balanced Full emerges. Unconsumed generation is not demonstrated to cause
catastrophic starvation, but it cannot establish hearing and can erase useful signal.

## 12. Artist Separation findings

The production transformation remains separate, after membership fitting, with
injected research randomness. Correctness checks preserve occurrence references,
duplicates, and total duration. All four artist shapes were screened.

Confirmed K-pop-like Full/30M: True Random final coverage changes from 55.58% without
separation to 50.77% with it; duration changes from 57.26% to 52.20%. Thus downstream
ordering materially changes early-listening outcomes even though membership is fixed.
Duration retains a small +1.43 pp gain versus the corresponding separated control.
This is not evidence of an Artist Separation defect or a reason to merge algorithms.

## 13. History-horizon findings

Last-N suppression gets modestly stronger from N=1 to N=3. Seven-day binary performs
better under daily usage but expires completely before a session exactly seven days
later. Heavy generation can make almost everything recent, reducing distinctions.
Elapsed decay forgets across long gaps; generation decay retains the same state across
those gaps. Combined decay is weakest of the default opportunity variants in the
screen. No universally robust horizon wins. Cumulative count's stronger gains are
partly a different long-memory design, not evidence that a weekly window is needed.

## 14. Randomness and rigidity

Default minimum effective-choice ratios across screening cases: binary 0.873,
duration-generation 0.949, count 0.818. Minimum normalized weight entropy: 0.965,
0.987, 0.949 respectively. Count's minimum weight ratio reaches 0.175; binary uses
0.35. Positive weights mean no hard exclusion, not a finite-horizon exposure guarantee.

No deterministic cycle is imposed. These measurements do not prove perceptual
randomness or absence of starvation over longer runs. Source sizes with excess
listening capacity naturally repeat; endpoint coverage saturation hides ordering and
exposure-distribution differences.

## 15. Sensitivity and size

Sweeps: opportunity time constant 20/60/120 minutes; penalty 0.2/0.45/0.9;
generation decay 0.5/0.85/0.95; binary suppression 0.1/0.35/0.65; count penalty
0.2/0.45/0.9. Stronger influence generally improves matching-session coverage but
increases history dependence. At 500 tracks/36 matched 60M sessions, count penalty
0.9 gains +12.42 pp final coverage (effective choice ~0.867); at 180M/30M it gains
only +1.80 pp. Full still has no count benefit.

Confirmed 60M/60M count final gains by size: 50: 0.00 pp; 100: 0.06;
300: 4.58; 500: 8.74; 1,000: 5.51. Small sources saturate. Gains are not constant
across size or listening capacity. Sensitivity is exploratory, not an independently
confirmed optimum, and the sweep does not establish a best parameter.

## 16. Limitations

Synthetic sources and stylized listening, no preference-dependent skipping, repeat
playback, seeking, multiple devices, concurrent users, or realistic correlated
duration/artist/preferences. Only 36/48 generations; no year-scale cumulative-count
drift study or human randomness assessment. No account/state persistence prototype.
Mutation testing is a single 10% replacement mid-run. Generation history observes
successful output, not network partial writes. The oracle is deliberately modest;
these results do not establish whether stronger listening-informed algorithms would
be compelling. No real Spotify API/policy feasibility is tested here.

## 17. Viability assessment

**Conditionally viable for a constrained timed-output interpretation; marginal as a
general Full/timed feature. Not production-ready.**

Count history gives meaningful improvement when generated membership approximates
listening. Its advantage declines with overgeneration, disappears for pure Full,
and is not robust to repeated abandonment/regeneration. Duration opportunity is
more stable across generated lengths but its gains are small and depend on playback
following the generated order. No candidate demonstrates the entire requested
robustness/benefit combination. None needs fragile occurrence identity.

This is evidence of a possible narrow feature, not evidence for claiming actual
listening balance or proceeding with production implementation now.

## 18. Next decision and bounded research step

David + Aurora should first decide whether the measured timed gains justify a
stateful feature with explicit limitations, and define minimum benefit and acceptable
randomness/history dependence. If Full and abandonment robustness are mandatory,
the present evidence does not justify proceeding.

If timed-only value is sufficient, the next bounded study should compare count and
duration opportunity on a few representative duration/artist distributions over
longer horizons, with preference-dependent skips and subjective blinded ordering
review. Focus on the measured tradeoff; do not expand to Energy Flow, Deep Cuts,
pool architecture, playback integration, or production implementation yet.

## Validation and reproducibility

39 research tests pass, including oracle isolation, duplicate preservation,
elapsed/window behavior, determinism, mutation, production prefix parity, and
production Artist Separation reference preservation. Python 3.12.14. No application
suite was run. `manifest.json` records executable/settings, hashes, and reproduction
arguments; `comparisons.md` and trial JSON provide all measured comparisons.
In-memory compilation and explicit whitespace checks passed. `git diff --check`
passed; research is untracked, so its whitespace was also checked independently.
The Windows worktree is clean at `cadc2e861f472018ef789e532a317f18fdbc6d3e`.
The original checkout's pre-existing Android modifications remain untouched.
