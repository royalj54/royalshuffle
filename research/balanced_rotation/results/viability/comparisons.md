# Generation-only viability: measured comparisons

All coverage deltas are percentage points against the paired True Random control.
AUC is mean cumulative heard coverage across generations, not playback-time AUC.
Intervals are approximate 95% paired Monte Carlo normal intervals; no multiple-test correction.
These synthetic measurements do not establish production viability.

## screen.json

150 scenarios, 12 candidates, 8 seeds, 36 generations; 14400 trials, 274.4 seconds.

| Candidate | Mean delta coverage10 | Mean delta AUC | Mean delta final | Worst AUC delta | Minimum effective choice | Minimum weight ratio |
| --- | --- | --- | --- | --- | --- | --- |
| true_random | +0.00 | +0.00 | +0.00 | +0.00 | 1.000 | 1.000 |
| binary_n1 | +0.41 | +0.31 | +0.09 | -1.94 | 0.873 | 0.350 |
| binary_n2 | +0.63 | +0.54 | +0.35 | -1.94 | 0.873 | 0.350 |
| binary_n3 | +0.71 | +0.68 | +0.52 | -1.94 | 0.873 | 0.350 |
| binary_7days | +1.02 | +0.94 | +0.87 | -1.94 | 0.873 | 0.350 |
| ordinal_decay | +0.63 | +0.58 | +0.45 | -1.24 | 0.949 | 0.232 |
| duration_decay | +0.75 | +0.68 | +0.56 | -1.39 | 0.949 | 0.272 |
| duration_elapsed | +0.55 | +0.47 | +0.31 | -1.57 | 0.975 | 0.327 |
| duration_combined | +0.41 | +0.34 | +0.15 | -1.65 | 0.986 | 0.451 |
| duration_n2 | +0.43 | +0.32 | +0.12 | -1.41 | 0.975 | 0.383 |
| generation_count | +0.90 | +1.11 | +1.49 | -1.94 | 0.818 | 0.175 |
| heard_oracle | +1.21 | +1.12 | +0.98 | -0.01 | 0.917 | 0.175 |

### Selected 500-track comparisons

| Scenario | Candidate | Coverage10 % | Final % | AUC delta +/- CI | Overlap | Mean repeat distance | Exposure Gini | Effective choice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 120M/30M | binary_7days | 16.12 | 47.02 | +0.82 +/- 0.70 | 0.010 | 13.1 | 0.611 | 0.904 |
| 120M/30M | binary_n1 | 15.80 | 45.73 | +0.12 +/- 0.51 | 0.007 | 11.7 | 0.627 | 0.980 |
| 120M/30M | binary_n2 | 15.88 | 46.10 | +0.31 +/- 0.61 | 0.007 | 12.1 | 0.622 | 0.961 |
| 120M/30M | binary_n3 | 15.93 | 46.77 | +0.61 +/- 0.74 | 0.007 | 12.5 | 0.615 | 0.945 |
| 120M/30M | duration_combined | 15.70 | 45.60 | -0.03 +/- 0.71 | 0.013 | 11.5 | 0.629 | 0.998 |
| 120M/30M | duration_decay | 15.90 | 46.15 | +0.29 +/- 0.69 | 0.012 | 11.9 | 0.622 | 0.995 |
| 120M/30M | duration_elapsed | 15.75 | 45.85 | +0.11 +/- 0.72 | 0.013 | 11.6 | 0.626 | 0.997 |
| 120M/30M | duration_n2 | 15.72 | 45.65 | +0.02 +/- 0.56 | 0.010 | 11.6 | 0.628 | 0.997 |
| 120M/30M | generation_count | 16.02 | 47.83 | +0.95 +/- 0.81 | 0.012 | 12.2 | 0.599 | 0.919 |
| 120M/30M | heard_oracle | 15.85 | 46.50 | +0.39 +/- 0.69 | 0.010 | 12.2 | 0.617 | 0.995 |
| 120M/30M | ordinal_decay | 15.80 | 45.98 | +0.17 +/- 0.69 | 0.012 | 11.7 | 0.624 | 0.996 |
| 120M/30M | true_random | 15.60 | 45.98 | +0.00 +/- 0.00 | 0.017 | 11.4 | 0.624 | 1.000 |
| 180M/180M | binary_7days | 75.75 | 99.67 | +6.26 +/- 0.56 | 0.056 | 8.3 | 0.200 | 0.880 |
| 180M/180M | binary_n1 | 67.75 | 98.25 | +1.86 +/- 0.61 | 0.039 | 7.3 | 0.257 | 0.970 |
| 180M/180M | binary_n2 | 69.92 | 98.75 | +3.10 +/- 0.56 | 0.043 | 7.6 | 0.241 | 0.945 |
| 180M/180M | binary_n3 | 71.40 | 99.05 | +4.03 +/- 0.59 | 0.046 | 7.8 | 0.229 | 0.922 |
| 180M/180M | duration_combined | 66.38 | 98.10 | +1.07 +/- 0.74 | 0.092 | 7.1 | 0.267 | 0.998 |
| 180M/180M | duration_decay | 67.08 | 98.40 | +1.62 +/- 0.73 | 0.090 | 7.2 | 0.257 | 0.995 |
| 180M/180M | duration_elapsed | 66.53 | 98.22 | +1.26 +/- 0.74 | 0.093 | 7.1 | 0.264 | 0.997 |
| 180M/180M | duration_n2 | 66.42 | 98.12 | +1.11 +/- 0.72 | 0.090 | 7.1 | 0.268 | 0.997 |
| 180M/180M | generation_count | 71.88 | 99.88 | +5.43 +/- 0.58 | 0.077 | 7.9 | 0.157 | 0.913 |
| 180M/180M | heard_oracle | 70.12 | 99.28 | +3.55 +/- 0.64 | 0.072 | 7.7 | 0.225 | 0.975 |
| 180M/180M | ordinal_decay | 67.05 | 98.45 | +1.62 +/- 0.73 | 0.091 | 7.2 | 0.257 | 0.995 |
| 180M/180M | true_random | 64.90 | 97.42 | +0.00 +/- 0.00 | 0.102 | 6.9 | 0.281 | 1.000 |
| 180M/30M | binary_7days | 15.93 | 46.35 | +0.34 +/- 0.70 | 0.011 | 12.7 | 0.618 | 0.880 |
| 180M/30M | binary_n1 | 15.82 | 45.70 | +0.11 +/- 0.51 | 0.007 | 11.7 | 0.627 | 0.970 |
| 180M/30M | binary_n2 | 15.75 | 46.23 | +0.24 +/- 0.71 | 0.007 | 12.1 | 0.621 | 0.945 |
| 180M/30M | binary_n3 | 15.70 | 46.33 | +0.29 +/- 0.70 | 0.008 | 12.2 | 0.620 | 0.922 |
| 180M/30M | duration_combined | 15.68 | 45.58 | -0.03 +/- 0.72 | 0.012 | 11.5 | 0.629 | 0.998 |
| 180M/30M | duration_decay | 15.85 | 46.10 | +0.24 +/- 0.65 | 0.011 | 11.9 | 0.623 | 0.995 |
| 180M/30M | duration_elapsed | 15.75 | 45.80 | +0.10 +/- 0.72 | 0.013 | 11.7 | 0.626 | 0.997 |
| 180M/30M | duration_n2 | 15.72 | 45.58 | -0.01 +/- 0.60 | 0.010 | 11.6 | 0.629 | 0.997 |
| 180M/30M | generation_count | 15.97 | 47.08 | +0.64 +/- 0.61 | 0.012 | 12.1 | 0.610 | 0.913 |
| 180M/30M | heard_oracle | 15.85 | 46.50 | +0.39 +/- 0.69 | 0.010 | 12.2 | 0.617 | 0.995 |
| 180M/30M | ordinal_decay | 15.83 | 46.05 | +0.20 +/- 0.65 | 0.011 | 11.9 | 0.623 | 0.995 |
| 180M/30M | true_random | 15.60 | 45.98 | +0.00 +/- 0.00 | 0.017 | 11.4 | 0.624 | 1.000 |
| 60M/20M | binary_7days | 11.12 | 34.50 | +0.60 +/- 0.55 | 0.004 | 13.8 | 0.700 | 0.945 |
| 60M/20M | binary_n1 | 10.93 | 33.80 | +0.23 +/- 0.52 | 0.005 | 12.0 | 0.710 | 0.989 |
| 60M/20M | binary_n2 | 10.88 | 33.98 | +0.30 +/- 0.58 | 0.005 | 12.4 | 0.707 | 0.979 |
| 60M/20M | binary_n3 | 11.00 | 34.23 | +0.44 +/- 0.47 | 0.005 | 13.0 | 0.704 | 0.970 |
| 60M/20M | duration_combined | 10.88 | 33.88 | +0.18 +/- 0.58 | 0.011 | 11.6 | 0.709 | 0.998 |
| 60M/20M | duration_decay | 10.95 | 34.17 | +0.36 +/- 0.63 | 0.009 | 12.1 | 0.705 | 0.995 |
| 60M/20M | duration_elapsed | 10.88 | 33.98 | +0.23 +/- 0.53 | 0.009 | 11.8 | 0.707 | 0.998 |
| 60M/20M | duration_n2 | 10.88 | 33.85 | +0.19 +/- 0.61 | 0.010 | 11.6 | 0.710 | 0.997 |
| 60M/20M | generation_count | 10.98 | 35.05 | +0.69 +/- 0.61 | 0.009 | 12.2 | 0.690 | 0.934 |
| 60M/20M | heard_oracle | 10.93 | 34.25 | +0.35 +/- 0.63 | 0.009 | 12.0 | 0.703 | 0.996 |
| 60M/20M | ordinal_decay | 10.90 | 34.15 | +0.31 +/- 0.61 | 0.009 | 11.7 | 0.705 | 0.998 |
| 60M/20M | true_random | 10.62 | 33.62 | +0.00 +/- 0.00 | 0.009 | 11.5 | 0.710 | 1.000 |
| 60M/60M | binary_7days | 31.32 | 74.97 | +3.42 +/- 0.73 | 0.015 | 12.6 | 0.423 | 0.945 |
| 60M/60M | binary_n1 | 29.57 | 71.07 | +0.67 +/- 0.43 | 0.014 | 10.8 | 0.466 | 0.989 |
| 60M/60M | binary_n2 | 30.00 | 71.75 | +1.23 +/- 0.34 | 0.014 | 11.2 | 0.458 | 0.979 |
| 60M/60M | binary_n3 | 30.22 | 72.58 | +1.74 +/- 0.53 | 0.014 | 11.5 | 0.449 | 0.970 |
| 60M/60M | duration_combined | 29.40 | 71.22 | +0.67 +/- 0.50 | 0.028 | 10.7 | 0.464 | 0.998 |
| 60M/60M | duration_decay | 29.67 | 72.17 | +1.23 +/- 0.51 | 0.028 | 10.9 | 0.454 | 0.995 |
| 60M/60M | duration_elapsed | 29.52 | 71.53 | +0.88 +/- 0.49 | 0.028 | 10.8 | 0.461 | 0.998 |
| 60M/60M | duration_n2 | 29.45 | 70.95 | +0.64 +/- 0.51 | 0.027 | 10.7 | 0.468 | 0.997 |
| 60M/60M | generation_count | 30.38 | 78.40 | +3.86 +/- 0.58 | 0.023 | 11.3 | 0.386 | 0.934 |
| 60M/60M | heard_oracle | 30.02 | 72.95 | +1.77 +/- 0.54 | 0.023 | 11.3 | 0.445 | 0.990 |
| 60M/60M | ordinal_decay | 29.37 | 71.42 | +0.74 +/- 0.47 | 0.032 | 10.7 | 0.462 | 0.998 |
| 60M/60M | true_random | 28.18 | 70.72 | +0.00 +/- 0.00 | 0.038 | 10.2 | 0.476 | 1.000 |
| Full/100% | binary_7days | 100.00 | 100.00 | +0.00 +/- 0.00 | 1.000 | 1.0 | 0.000 | 1.000 |
| Full/100% | binary_n1 | 100.00 | 100.00 | +0.00 +/- 0.00 | 1.000 | 1.0 | 0.000 | 1.000 |
| Full/100% | binary_n2 | 100.00 | 100.00 | +0.00 +/- 0.00 | 1.000 | 1.0 | 0.000 | 1.000 |
| Full/100% | binary_n3 | 100.00 | 100.00 | +0.00 +/- 0.00 | 1.000 | 1.0 | 0.000 | 1.000 |
| Full/100% | duration_combined | 100.00 | 100.00 | +0.00 +/- 0.00 | 1.000 | 1.0 | 0.000 | 0.998 |
| Full/100% | duration_decay | 100.00 | 100.00 | +0.00 +/- 0.00 | 1.000 | 1.0 | 0.000 | 0.995 |
| Full/100% | duration_elapsed | 100.00 | 100.00 | +0.00 +/- 0.00 | 1.000 | 1.0 | 0.000 | 0.997 |
| Full/100% | duration_n2 | 100.00 | 100.00 | +0.00 +/- 0.00 | 1.000 | 1.0 | 0.000 | 0.997 |
| Full/100% | generation_count | 100.00 | 100.00 | +0.00 +/- 0.00 | 1.000 | 1.0 | 0.000 | 1.000 |
| Full/100% | heard_oracle | 100.00 | 100.00 | +0.00 +/- 0.00 | 1.000 | 1.0 | 0.000 | 1.000 |
| Full/100% | ordinal_decay | 100.00 | 100.00 | +0.00 +/- 0.00 | 1.000 | 1.0 | 0.000 | 0.983 |
| Full/100% | true_random | 100.00 | 100.00 | +0.00 +/- 0.00 | 1.000 | 1.0 | 0.000 | 1.000 |
| Full/30M | binary_7days | 15.60 | 45.25 | -0.27 +/- 0.57 | 0.019 | 11.1 | 0.633 | 1.000 |
| Full/30M | binary_n1 | 15.60 | 45.25 | -0.27 +/- 0.57 | 0.019 | 11.1 | 0.633 | 1.000 |
| Full/30M | binary_n2 | 15.60 | 45.25 | -0.27 +/- 0.57 | 0.019 | 11.1 | 0.633 | 1.000 |
| Full/30M | binary_n3 | 15.60 | 45.25 | -0.27 +/- 0.57 | 0.019 | 11.1 | 0.633 | 1.000 |
| Full/30M | duration_combined | 15.70 | 45.58 | -0.03 +/- 0.72 | 0.012 | 11.5 | 0.629 | 0.998 |
| Full/30M | duration_decay | 15.85 | 46.05 | +0.24 +/- 0.66 | 0.012 | 11.8 | 0.623 | 0.995 |
| Full/30M | duration_elapsed | 15.75 | 45.80 | +0.10 +/- 0.73 | 0.013 | 11.7 | 0.626 | 0.997 |
| Full/30M | duration_n2 | 15.73 | 45.58 | -0.00 +/- 0.61 | 0.010 | 11.6 | 0.629 | 0.997 |
| Full/30M | generation_count | 15.60 | 45.25 | -0.27 +/- 0.57 | 0.019 | 11.1 | 0.633 | 1.000 |
| Full/30M | heard_oracle | 15.85 | 46.50 | +0.39 +/- 0.69 | 0.010 | 12.2 | 0.617 | 0.995 |
| Full/30M | ordinal_decay | 15.72 | 46.05 | +0.21 +/- 0.62 | 0.013 | 11.8 | 0.624 | 0.983 |
| Full/30M | true_random | 15.60 | 45.98 | +0.00 +/- 0.00 | 0.017 | 11.4 | 0.624 | 1.000 |
| Full/50% | binary_7days | 99.92 | 100.00 | -0.03 +/- 0.15 | 0.503 | 1.9 | 0.092 | 1.000 |
| Full/50% | binary_n1 | 99.92 | 100.00 | -0.03 +/- 0.15 | 0.503 | 1.9 | 0.092 | 1.000 |
| Full/50% | binary_n2 | 99.92 | 100.00 | -0.03 +/- 0.15 | 0.503 | 1.9 | 0.092 | 1.000 |
| Full/50% | binary_n3 | 99.92 | 100.00 | -0.03 +/- 0.15 | 0.503 | 1.9 | 0.092 | 1.000 |
| Full/50% | duration_combined | 99.92 | 100.00 | -0.01 +/- 0.15 | 0.499 | 1.9 | 0.091 | 0.998 |
| Full/50% | duration_decay | 99.92 | 100.00 | +0.00 +/- 0.14 | 0.499 | 1.9 | 0.090 | 0.995 |
| Full/50% | duration_elapsed | 99.92 | 100.00 | -0.01 +/- 0.15 | 0.499 | 1.9 | 0.090 | 0.997 |
| Full/50% | duration_n2 | 99.92 | 100.00 | -0.00 +/- 0.14 | 0.498 | 1.9 | 0.091 | 0.997 |
| Full/50% | generation_count | 99.92 | 100.00 | -0.03 +/- 0.15 | 0.503 | 1.9 | 0.092 | 1.000 |
| Full/50% | heard_oracle | 100.00 | 100.00 | +0.49 +/- 0.13 | 0.443 | 2.0 | 0.050 | 0.950 |
| Full/50% | ordinal_decay | 99.97 | 100.00 | +0.21 +/- 0.13 | 0.477 | 2.0 | 0.072 | 0.983 |
| Full/50% | true_random | 99.90 | 100.00 | +0.00 +/- 0.00 | 0.502 | 1.9 | 0.095 | 1.000 |
| abandon_half | binary_7days | 12.28 | 38.40 | +0.88 +/- 0.23 | 0.000 | 12.9 | 0.666 | 0.880 |
| abandon_half | binary_n1 | 12.12 | 37.62 | +0.55 +/- 0.26 | 0.000 | 12.0 | 0.679 | 0.970 |
| abandon_half | binary_n2 | 12.28 | 37.95 | +0.66 +/- 0.22 | 0.000 | 12.7 | 0.673 | 0.945 |
| abandon_half | binary_n3 | 12.22 | 37.88 | +0.61 +/- 0.17 | 0.000 | 12.5 | 0.674 | 0.922 |
| abandon_half | duration_combined | 12.20 | 37.77 | +0.59 +/- 0.15 | 0.000 | 12.2 | 0.676 | 0.998 |
| abandon_half | duration_decay | 12.20 | 37.95 | +0.71 +/- 0.12 | 0.000 | 12.4 | 0.674 | 0.995 |
| abandon_half | duration_elapsed | 12.20 | 38.00 | +0.68 +/- 0.16 | 0.000 | 12.4 | 0.673 | 0.997 |
| abandon_half | duration_n2 | 12.25 | 37.83 | +0.66 +/- 0.21 | 0.000 | 12.3 | 0.676 | 0.997 |
| abandon_half | generation_count | 12.17 | 38.42 | +0.86 +/- 0.22 | 0.000 | 12.1 | 0.666 | 0.913 |
| abandon_half | heard_oracle | 12.35 | 38.30 | +0.89 +/- 0.24 | 0.000 | 12.6 | 0.669 | 0.996 |
| abandon_half | ordinal_decay | 12.20 | 37.95 | +0.69 +/- 0.14 | 0.000 | 12.4 | 0.674 | 0.995 |
| abandon_half | true_random | 11.62 | 36.67 | +0.00 +/- 0.00 | 0.000 | 11.9 | 0.690 | 1.000 |
| burst_regenerate | binary_7days | 4.90 | 21.12 | +0.30 +/- 0.21 | 0.000 | 15.8 | 0.803 | 0.873 |
| burst_regenerate | binary_n1 | 5.00 | 21.07 | +0.31 +/- 0.22 | 0.000 | 15.3 | 0.804 | 0.970 |
| burst_regenerate | binary_n2 | 5.03 | 20.93 | +0.23 +/- 0.26 | 0.000 | 14.6 | 0.806 | 0.945 |
| burst_regenerate | binary_n3 | 4.95 | 20.90 | +0.20 +/- 0.27 | 0.000 | 14.2 | 0.806 | 0.922 |
| burst_regenerate | duration_combined | 4.97 | 21.07 | +0.29 +/- 0.26 | 0.000 | 15.3 | 0.804 | 0.996 |
| burst_regenerate | duration_decay | 4.95 | 21.05 | +0.29 +/- 0.24 | 0.000 | 15.4 | 0.804 | 0.995 |
| burst_regenerate | duration_elapsed | 4.95 | 21.07 | +0.30 +/- 0.24 | 0.000 | 15.2 | 0.803 | 0.992 |
| burst_regenerate | duration_n2 | 5.00 | 21.07 | +0.30 +/- 0.23 | 0.000 | 14.9 | 0.804 | 0.997 |
| burst_regenerate | generation_count | 5.03 | 21.20 | +0.37 +/- 0.25 | 0.000 | 14.9 | 0.801 | 0.913 |
| burst_regenerate | heard_oracle | 5.05 | 21.30 | +0.40 +/- 0.23 | 0.000 | 15.5 | 0.801 | 0.997 |
| burst_regenerate | ordinal_decay | 4.95 | 21.07 | +0.29 +/- 0.24 | 0.000 | 15.4 | 0.804 | 0.995 |
| burst_regenerate | true_random | 4.75 | 20.45 | +0.00 +/- 0.00 | 0.000 | 13.2 | 0.813 | 1.000 |
| daily_180 | binary_7days | 15.93 | 46.35 | +0.34 +/- 0.70 | 0.011 | 12.7 | 0.618 | 0.880 |
| daily_180 | binary_n1 | 15.82 | 45.70 | +0.11 +/- 0.51 | 0.007 | 11.7 | 0.627 | 0.970 |
| daily_180 | binary_n2 | 15.75 | 46.23 | +0.24 +/- 0.71 | 0.007 | 12.1 | 0.621 | 0.945 |
| daily_180 | binary_n3 | 15.70 | 46.33 | +0.29 +/- 0.70 | 0.008 | 12.2 | 0.620 | 0.922 |
| daily_180 | duration_combined | 15.68 | 45.58 | -0.03 +/- 0.72 | 0.012 | 11.5 | 0.629 | 0.998 |
| daily_180 | duration_decay | 15.85 | 46.10 | +0.24 +/- 0.65 | 0.011 | 11.9 | 0.623 | 0.995 |
| daily_180 | duration_elapsed | 15.75 | 45.80 | +0.10 +/- 0.72 | 0.013 | 11.7 | 0.626 | 0.997 |
| daily_180 | duration_n2 | 15.72 | 45.58 | -0.01 +/- 0.60 | 0.010 | 11.6 | 0.629 | 0.997 |
| daily_180 | generation_count | 15.97 | 47.08 | +0.64 +/- 0.61 | 0.012 | 12.1 | 0.610 | 0.913 |
| daily_180 | heard_oracle | 15.85 | 46.50 | +0.39 +/- 0.69 | 0.010 | 12.2 | 0.617 | 0.995 |
| daily_180 | ordinal_decay | 15.83 | 46.05 | +0.20 +/- 0.65 | 0.011 | 11.9 | 0.623 | 0.995 |
| daily_180 | true_random | 15.60 | 45.98 | +0.00 +/- 0.00 | 0.017 | 11.4 | 0.624 | 1.000 |
| heavy_180 | binary_7days | 16.15 | 46.12 | +0.47 +/- 0.67 | 0.016 | 11.6 | 0.622 | 0.873 |
| heavy_180 | binary_n1 | 15.82 | 45.70 | +0.11 +/- 0.51 | 0.007 | 11.7 | 0.627 | 0.970 |
| heavy_180 | binary_n2 | 15.75 | 46.23 | +0.24 +/- 0.71 | 0.007 | 12.1 | 0.621 | 0.945 |
| heavy_180 | binary_n3 | 15.70 | 46.33 | +0.29 +/- 0.70 | 0.008 | 12.2 | 0.620 | 0.922 |
| heavy_180 | duration_combined | 15.80 | 45.98 | +0.16 +/- 0.66 | 0.011 | 11.8 | 0.624 | 0.996 |
| heavy_180 | duration_decay | 15.85 | 46.10 | +0.24 +/- 0.65 | 0.011 | 11.9 | 0.623 | 0.995 |
| heavy_180 | duration_elapsed | 15.93 | 46.95 | +0.61 +/- 0.80 | 0.011 | 12.2 | 0.611 | 0.986 |
| heavy_180 | duration_n2 | 15.72 | 45.58 | -0.01 +/- 0.60 | 0.010 | 11.6 | 0.629 | 0.997 |
| heavy_180 | generation_count | 15.97 | 47.08 | +0.64 +/- 0.61 | 0.012 | 12.1 | 0.610 | 0.913 |
| heavy_180 | heard_oracle | 15.85 | 46.50 | +0.39 +/- 0.69 | 0.010 | 12.2 | 0.617 | 0.995 |
| heavy_180 | ordinal_decay | 15.83 | 46.05 | +0.20 +/- 0.65 | 0.011 | 11.9 | 0.623 | 0.995 |
| heavy_180 | true_random | 15.60 | 45.98 | +0.00 +/- 0.00 | 0.017 | 11.4 | 0.624 | 1.000 |
| long_inactivity | binary_7days | 15.93 | 46.30 | +0.31 +/- 0.76 | 0.010 | 12.4 | 0.619 | 0.880 |
| long_inactivity | binary_n1 | 15.82 | 45.70 | +0.11 +/- 0.51 | 0.007 | 11.7 | 0.627 | 0.970 |
| long_inactivity | binary_n2 | 15.75 | 46.23 | +0.24 +/- 0.71 | 0.007 | 12.1 | 0.621 | 0.945 |
| long_inactivity | binary_n3 | 15.70 | 46.33 | +0.29 +/- 0.70 | 0.008 | 12.2 | 0.620 | 0.922 |
| long_inactivity | duration_combined | 15.68 | 45.45 | -0.12 +/- 0.65 | 0.013 | 11.4 | 0.631 | 0.998 |
| long_inactivity | duration_decay | 15.85 | 46.10 | +0.24 +/- 0.65 | 0.011 | 11.9 | 0.623 | 0.995 |
| long_inactivity | duration_elapsed | 15.75 | 45.67 | +0.00 +/- 0.64 | 0.013 | 11.6 | 0.628 | 0.997 |
| long_inactivity | duration_n2 | 15.72 | 45.58 | -0.01 +/- 0.60 | 0.010 | 11.6 | 0.629 | 0.997 |
| long_inactivity | generation_count | 15.97 | 47.08 | +0.64 +/- 0.61 | 0.012 | 12.1 | 0.610 | 0.913 |
| long_inactivity | heard_oracle | 15.85 | 46.50 | +0.39 +/- 0.69 | 0.010 | 12.2 | 0.617 | 0.995 |
| long_inactivity | ordinal_decay | 15.83 | 46.05 | +0.20 +/- 0.65 | 0.011 | 11.9 | 0.623 | 0.995 |
| long_inactivity | true_random | 15.60 | 45.98 | +0.00 +/- 0.00 | 0.017 | 11.4 | 0.624 | 1.000 |
| mixed | binary_7days | 20.73 | 56.18 | +0.36 +/- 0.68 | 0.028 | 11.2 | 0.560 | 0.955 |
| mixed | binary_n1 | 20.70 | 56.50 | +0.48 +/- 0.70 | 0.017 | 11.4 | 0.556 | 0.971 |
| mixed | binary_n2 | 20.70 | 56.70 | +0.58 +/- 0.68 | 0.023 | 11.3 | 0.555 | 0.962 |
| mixed | binary_n3 | 20.82 | 56.73 | +0.70 +/- 0.58 | 0.024 | 11.3 | 0.555 | 0.947 |
| mixed | duration_combined | 20.57 | 56.85 | +0.51 +/- 0.81 | 0.023 | 11.4 | 0.553 | 0.998 |
| mixed | duration_decay | 20.70 | 57.50 | +0.87 +/- 0.80 | 0.022 | 11.6 | 0.544 | 0.995 |
| mixed | duration_elapsed | 20.62 | 57.10 | +0.64 +/- 0.78 | 0.024 | 11.5 | 0.550 | 0.997 |
| mixed | duration_n2 | 20.62 | 56.60 | +0.45 +/- 0.71 | 0.021 | 11.3 | 0.556 | 0.997 |
| mixed | generation_count | 20.82 | 58.90 | +1.40 +/- 0.72 | 0.022 | 11.4 | 0.527 | 0.927 |
| mixed | heard_oracle | 20.82 | 57.72 | +1.00 +/- 0.76 | 0.021 | 12.0 | 0.541 | 0.993 |
| mixed | ordinal_decay | 20.62 | 57.20 | +0.66 +/- 0.76 | 0.022 | 11.5 | 0.549 | 0.991 |
| mixed | true_random | 20.12 | 56.25 | +0.00 +/- 0.00 | 0.025 | 10.9 | 0.559 | 1.000 |
| mutation | binary_7days | 23.62 | 58.39 | +1.97 +/- 0.78 | 0.014 | 12.1 | 0.538 | 0.880 |
| mutation | binary_n1 | 22.90 | 57.02 | +0.99 +/- 0.84 | 0.010 | 10.9 | 0.555 | 0.970 |
| mutation | binary_n2 | 23.30 | 57.09 | +1.17 +/- 0.90 | 0.011 | 11.3 | 0.554 | 0.944 |
| mutation | binary_n3 | 23.23 | 57.34 | +1.23 +/- 0.86 | 0.012 | 11.4 | 0.551 | 0.922 |
| mutation | duration_combined | 22.93 | 56.43 | +0.78 +/- 0.97 | 0.022 | 10.7 | 0.561 | 0.998 |
| mutation | duration_decay | 23.07 | 57.27 | +1.23 +/- 0.98 | 0.022 | 10.9 | 0.551 | 0.995 |
| mutation | duration_elapsed | 22.98 | 56.66 | +0.92 +/- 1.00 | 0.023 | 10.7 | 0.558 | 0.997 |
| mutation | duration_n2 | 22.95 | 56.41 | +0.74 +/- 0.94 | 0.021 | 10.6 | 0.562 | 0.997 |
| mutation | generation_count | 23.15 | 59.27 | +2.10 +/- 0.99 | 0.022 | 11.1 | 0.526 | 0.907 |
| mutation | heard_oracle | 23.20 | 57.75 | +1.48 +/- 0.97 | 0.019 | 11.2 | 0.546 | 0.993 |
| mutation | ordinal_decay | 23.07 | 57.23 | +1.20 +/- 1.01 | 0.021 | 10.9 | 0.552 | 0.995 |
| mutation | true_random | 22.30 | 55.30 | +0.00 +/- 0.00 | 0.023 | 10.4 | 0.575 | 1.000 |
| regenerate | binary_7days | 4.95 | 21.00 | +0.27 +/- 0.23 | 0.000 | 14.8 | 0.804 | 0.880 |
| regenerate | binary_n1 | 5.00 | 21.07 | +0.31 +/- 0.22 | 0.000 | 15.3 | 0.804 | 0.970 |
| regenerate | binary_n2 | 5.03 | 20.93 | +0.23 +/- 0.26 | 0.000 | 14.6 | 0.806 | 0.945 |
| regenerate | binary_n3 | 4.95 | 20.90 | +0.20 +/- 0.27 | 0.000 | 14.2 | 0.806 | 0.922 |
| regenerate | duration_combined | 4.97 | 21.05 | +0.26 +/- 0.26 | 0.000 | 14.9 | 0.804 | 0.998 |
| regenerate | duration_decay | 4.95 | 21.05 | +0.29 +/- 0.24 | 0.000 | 15.4 | 0.804 | 0.995 |
| regenerate | duration_elapsed | 4.97 | 21.12 | +0.29 +/- 0.26 | 0.000 | 15.3 | 0.803 | 0.997 |
| regenerate | duration_n2 | 5.00 | 21.07 | +0.30 +/- 0.23 | 0.000 | 14.9 | 0.804 | 0.997 |
| regenerate | generation_count | 5.03 | 21.20 | +0.37 +/- 0.25 | 0.000 | 14.9 | 0.801 | 0.913 |
| regenerate | heard_oracle | 5.05 | 21.30 | +0.40 +/- 0.23 | 0.000 | 15.5 | 0.801 | 0.997 |
| regenerate | ordinal_decay | 4.95 | 21.07 | +0.29 +/- 0.24 | 0.000 | 15.4 | 0.804 | 0.995 |
| regenerate | true_random | 4.75 | 20.45 | +0.00 +/- 0.00 | 0.000 | 13.2 | 0.813 | 1.000 |
| shuffled_180 | binary_7days | 15.93 | 46.85 | +0.66 +/- 0.67 | 0.010 | 12.6 | 0.612 | 0.880 |
| shuffled_180 | binary_n1 | 15.47 | 46.02 | +0.19 +/- 0.60 | 0.005 | 11.5 | 0.623 | 0.970 |
| shuffled_180 | binary_n2 | 15.42 | 45.90 | +0.10 +/- 0.75 | 0.005 | 11.6 | 0.623 | 0.945 |
| shuffled_180 | binary_n3 | 15.78 | 46.83 | +0.64 +/- 0.54 | 0.006 | 12.4 | 0.616 | 0.922 |
| shuffled_180 | duration_combined | 15.57 | 46.12 | +0.31 +/- 0.62 | 0.015 | 11.4 | 0.622 | 0.998 |
| shuffled_180 | duration_decay | 15.58 | 46.58 | +0.37 +/- 0.61 | 0.020 | 11.2 | 0.620 | 0.995 |
| shuffled_180 | duration_elapsed | 15.55 | 46.00 | +0.11 +/- 0.81 | 0.021 | 10.6 | 0.625 | 0.997 |
| shuffled_180 | duration_n2 | 15.60 | 45.67 | +0.08 +/- 0.72 | 0.017 | 11.0 | 0.629 | 0.997 |
| shuffled_180 | generation_count | 15.95 | 47.90 | +1.14 +/- 0.63 | 0.011 | 12.2 | 0.597 | 0.913 |
| shuffled_180 | heard_oracle | 15.78 | 46.55 | +0.56 +/- 0.63 | 0.012 | 12.1 | 0.617 | 0.995 |
| shuffled_180 | ordinal_decay | 15.53 | 46.50 | +0.22 +/- 0.72 | 0.022 | 11.2 | 0.620 | 0.995 |
| shuffled_180 | true_random | 15.70 | 45.50 | +0.00 +/- 0.00 | 0.012 | 11.1 | 0.630 | 1.000 |
| shuffled_None | binary_7days | 15.45 | 45.45 | +0.24 +/- 0.74 | 0.016 | 10.8 | 0.632 | 1.000 |
| shuffled_None | binary_n1 | 15.45 | 45.45 | +0.24 +/- 0.74 | 0.016 | 10.8 | 0.632 | 1.000 |
| shuffled_None | binary_n2 | 15.45 | 45.45 | +0.24 +/- 0.74 | 0.016 | 10.8 | 0.632 | 1.000 |
| shuffled_None | binary_n3 | 15.45 | 45.45 | +0.24 +/- 0.74 | 0.016 | 10.8 | 0.632 | 1.000 |
| shuffled_None | duration_combined | 15.03 | 45.60 | -0.06 +/- 0.58 | 0.014 | 11.3 | 0.627 | 0.998 |
| shuffled_None | duration_decay | 15.60 | 46.23 | +0.43 +/- 0.59 | 0.017 | 11.1 | 0.622 | 0.995 |
| shuffled_None | duration_elapsed | 15.43 | 45.45 | +0.18 +/- 0.66 | 0.015 | 11.5 | 0.631 | 0.997 |
| shuffled_None | duration_n2 | 15.53 | 46.17 | +0.54 +/- 0.72 | 0.016 | 11.3 | 0.624 | 0.997 |
| shuffled_None | generation_count | 15.45 | 45.45 | +0.24 +/- 0.74 | 0.016 | 10.8 | 0.632 | 1.000 |
| shuffled_None | heard_oracle | 15.43 | 45.30 | +0.18 +/- 0.60 | 0.023 | 10.7 | 0.634 | 0.995 |
| shuffled_None | ordinal_decay | 15.80 | 46.18 | +0.54 +/- 0.22 | 0.012 | 11.2 | 0.621 | 0.983 |
| shuffled_None | true_random | 15.38 | 45.20 | +0.00 +/- 0.00 | 0.019 | 10.8 | 0.634 | 1.000 |
| skips_180 | binary_7days | 16.25 | 47.58 | +1.03 +/- 0.57 | 0.010 | 12.8 | 0.603 | 0.880 |
| skips_180 | binary_n1 | 16.03 | 46.12 | +0.40 +/- 0.77 | 0.006 | 11.6 | 0.622 | 0.970 |
| skips_180 | binary_n2 | 15.85 | 46.35 | +0.36 +/- 0.85 | 0.006 | 12.1 | 0.619 | 0.945 |
| skips_180 | binary_n3 | 15.68 | 46.67 | +0.49 +/- 0.62 | 0.007 | 12.7 | 0.615 | 0.922 |
| skips_180 | duration_combined | 15.80 | 46.00 | +0.26 +/- 0.60 | 0.011 | 11.6 | 0.624 | 0.998 |
| skips_180 | duration_decay | 16.03 | 46.90 | +0.67 +/- 0.55 | 0.014 | 11.7 | 0.614 | 0.995 |
| skips_180 | duration_elapsed | 15.85 | 46.40 | +0.43 +/- 0.53 | 0.011 | 11.7 | 0.620 | 0.997 |
| skips_180 | duration_n2 | 15.93 | 45.83 | +0.22 +/- 0.72 | 0.011 | 12.0 | 0.626 | 0.997 |
| skips_180 | generation_count | 16.12 | 47.12 | +0.74 +/- 0.46 | 0.014 | 12.0 | 0.609 | 0.913 |
| skips_180 | heard_oracle | 16.03 | 46.90 | +0.74 +/- 0.53 | 0.010 | 12.6 | 0.613 | 0.995 |
| skips_180 | ordinal_decay | 16.00 | 46.77 | +0.61 +/- 0.61 | 0.015 | 11.6 | 0.616 | 0.995 |
| skips_180 | true_random | 15.50 | 46.02 | +0.00 +/- 0.00 | 0.014 | 11.3 | 0.624 | 1.000 |
| sparse_180 | binary_7days | 15.60 | 45.25 | -0.27 +/- 0.57 | 0.019 | 11.1 | 0.633 | 1.000 |
| sparse_180 | binary_n1 | 15.82 | 45.70 | +0.11 +/- 0.51 | 0.007 | 11.7 | 0.627 | 0.970 |
| sparse_180 | binary_n2 | 15.75 | 46.23 | +0.24 +/- 0.71 | 0.007 | 12.1 | 0.621 | 0.945 |
| sparse_180 | binary_n3 | 15.70 | 46.33 | +0.29 +/- 0.70 | 0.008 | 12.2 | 0.620 | 0.922 |
| sparse_180 | duration_combined | 15.57 | 45.18 | -0.30 +/- 0.57 | 0.018 | 11.1 | 0.634 | 1.000 |
| sparse_180 | duration_decay | 15.85 | 46.10 | +0.24 +/- 0.65 | 0.011 | 11.9 | 0.623 | 0.995 |
| sparse_180 | duration_elapsed | 15.57 | 45.15 | -0.32 +/- 0.57 | 0.018 | 11.1 | 0.635 | 1.000 |
| sparse_180 | duration_n2 | 15.72 | 45.58 | -0.01 +/- 0.60 | 0.010 | 11.6 | 0.629 | 0.997 |
| sparse_180 | generation_count | 15.97 | 47.08 | +0.64 +/- 0.61 | 0.012 | 12.1 | 0.610 | 0.913 |
| sparse_180 | heard_oracle | 15.85 | 46.50 | +0.39 +/- 0.69 | 0.010 | 12.2 | 0.617 | 0.995 |
| sparse_180 | ordinal_decay | 15.83 | 46.05 | +0.20 +/- 0.65 | 0.011 | 11.9 | 0.623 | 0.995 |
| sparse_180 | true_random | 15.60 | 45.98 | +0.00 +/- 0.00 | 0.017 | 11.4 | 0.624 | 1.000 |

### Artist Separation comparisons

| Scenario | Candidate | Balanced AUC delta sep ON | Delta sep OFF | Absolute AUC ON minus OFF | Artist total-variation distance |
| --- | --- | --- | --- | --- | --- |
| artist_dominant_180_30_sep1 | binary_7days | +0.08 | +0.34 | -0.01 | 0.040 |
| artist_dominant_180_30_sep1 | binary_n1 | -0.22 | +0.11 | -0.09 | 0.035 |
| artist_dominant_180_30_sep1 | binary_n2 | -0.14 | +0.24 | -0.13 | 0.038 |
| artist_dominant_180_30_sep1 | binary_n3 | -0.08 | +0.29 | -0.12 | 0.039 |
| artist_dominant_180_30_sep1 | duration_combined | -0.33 | -0.03 | -0.06 | 0.036 |
| artist_dominant_180_30_sep1 | duration_decay | -0.15 | +0.24 | -0.14 | 0.036 |
| artist_dominant_180_30_sep1 | duration_elapsed | -0.29 | +0.10 | -0.15 | 0.037 |
| artist_dominant_180_30_sep1 | duration_n2 | -0.30 | -0.01 | -0.05 | 0.036 |
| artist_dominant_180_30_sep1 | generation_count | +0.40 | +0.64 | +0.01 | 0.035 |
| artist_dominant_180_30_sep1 | heard_oracle | +0.03 | +0.39 | -0.12 | 0.035 |
| artist_dominant_180_30_sep1 | ordinal_decay | -0.13 | +0.20 | -0.09 | 0.037 |
| artist_dominant_180_30_sep1 | true_random | +0.00 | +0.00 | +0.25 | 0.034 |
| artist_dominant_60_45_sep1 | binary_7days | +2.03 | +2.20 | -0.17 | 0.032 |
| artist_dominant_60_45_sep1 | binary_n1 | +0.33 | +0.53 | -0.20 | 0.032 |
| artist_dominant_60_45_sep1 | binary_n2 | +0.76 | +0.87 | -0.12 | 0.030 |
| artist_dominant_60_45_sep1 | binary_n3 | +1.18 | +1.32 | -0.15 | 0.030 |
| artist_dominant_60_45_sep1 | duration_combined | +0.38 | +0.49 | -0.11 | 0.032 |
| artist_dominant_60_45_sep1 | duration_decay | +0.66 | +0.87 | -0.22 | 0.035 |
| artist_dominant_60_45_sep1 | duration_elapsed | +0.43 | +0.68 | -0.26 | 0.033 |
| artist_dominant_60_45_sep1 | duration_n2 | +0.30 | +0.36 | -0.07 | 0.032 |
| artist_dominant_60_45_sep1 | generation_count | +2.34 | +2.54 | -0.21 | 0.028 |
| artist_dominant_60_45_sep1 | heard_oracle | +1.10 | +1.17 | -0.08 | 0.033 |
| artist_dominant_60_45_sep1 | ordinal_decay | +0.45 | +0.57 | -0.13 | 0.033 |
| artist_dominant_60_45_sep1 | true_random | +0.00 | +0.00 | -0.00 | 0.033 |
| artist_dominant_None_0.5_sep1 | binary_7days | -0.05 | -0.03 | -0.01 | 0.007 |
| artist_dominant_None_0.5_sep1 | binary_n1 | -0.05 | -0.03 | -0.01 | 0.007 |
| artist_dominant_None_0.5_sep1 | binary_n2 | -0.05 | -0.03 | -0.01 | 0.007 |
| artist_dominant_None_0.5_sep1 | binary_n3 | -0.05 | -0.03 | -0.01 | 0.007 |
| artist_dominant_None_0.5_sep1 | duration_combined | -0.01 | -0.01 | -0.00 | 0.007 |
| artist_dominant_None_0.5_sep1 | duration_decay | -0.01 | +0.00 | -0.01 | 0.007 |
| artist_dominant_None_0.5_sep1 | duration_elapsed | -0.01 | -0.01 | -0.00 | 0.007 |
| artist_dominant_None_0.5_sep1 | duration_n2 | -0.01 | -0.00 | -0.01 | 0.007 |
| artist_dominant_None_0.5_sep1 | generation_count | -0.05 | -0.03 | -0.01 | 0.007 |
| artist_dominant_None_0.5_sep1 | heard_oracle | +0.49 | +0.49 | +0.01 | 0.007 |
| artist_dominant_None_0.5_sep1 | ordinal_decay | +0.22 | +0.21 | +0.01 | 0.007 |
| artist_dominant_None_0.5_sep1 | true_random | +0.00 | +0.00 | +0.00 | 0.007 |
| artist_dominant_None_30_sep1 | binary_7days | -0.49 | -0.27 | +0.12 | 0.046 |
| artist_dominant_None_30_sep1 | binary_n1 | -0.49 | -0.27 | +0.12 | 0.046 |
| artist_dominant_None_30_sep1 | binary_n2 | -0.49 | -0.27 | +0.12 | 0.046 |
| artist_dominant_None_30_sep1 | binary_n3 | -0.49 | -0.27 | +0.12 | 0.046 |
| artist_dominant_None_30_sep1 | duration_combined | -0.26 | -0.03 | +0.10 | 0.048 |
| artist_dominant_None_30_sep1 | duration_decay | -0.10 | +0.24 | +0.01 | 0.047 |
| artist_dominant_None_30_sep1 | duration_elapsed | -0.19 | +0.10 | +0.04 | 0.047 |
| artist_dominant_None_30_sep1 | duration_n2 | -0.30 | -0.00 | +0.04 | 0.047 |
| artist_dominant_None_30_sep1 | generation_count | -0.49 | -0.27 | +0.12 | 0.046 |
| artist_dominant_None_30_sep1 | heard_oracle | -0.01 | +0.39 | -0.06 | 0.046 |
| artist_dominant_None_30_sep1 | ordinal_decay | -0.25 | +0.21 | -0.12 | 0.048 |
| artist_dominant_None_30_sep1 | true_random | +0.00 | +0.00 | +0.34 | 0.042 |
| artist_even_180_30_sep1 | binary_7days | +1.20 | +0.34 | +0.25 | 0.093 |
| artist_even_180_30_sep1 | binary_n1 | +0.53 | +0.11 | -0.20 | 0.103 |
| artist_even_180_30_sep1 | binary_n2 | +0.97 | +0.24 | +0.11 | 0.103 |
| artist_even_180_30_sep1 | binary_n3 | +1.39 | +0.29 | +0.49 | 0.095 |
| artist_even_180_30_sep1 | duration_combined | +1.16 | -0.03 | +0.57 | 0.099 |
| artist_even_180_30_sep1 | duration_decay | +1.21 | +0.24 | +0.36 | 0.097 |
| artist_even_180_30_sep1 | duration_elapsed | +1.00 | +0.10 | +0.28 | 0.095 |
| artist_even_180_30_sep1 | duration_n2 | +0.80 | -0.01 | +0.19 | 0.105 |
| artist_even_180_30_sep1 | generation_count | +1.54 | +0.64 | +0.28 | 0.105 |
| artist_even_180_30_sep1 | heard_oracle | +1.31 | +0.39 | +0.31 | 0.101 |
| artist_even_180_30_sep1 | ordinal_decay | +1.25 | +0.20 | +0.43 | 0.096 |
| artist_even_180_30_sep1 | true_random | +0.00 | +0.00 | -0.61 | 0.113 |
| artist_even_60_45_sep1 | binary_7days | +2.00 | +2.20 | -0.06 | 0.066 |
| artist_even_60_45_sep1 | binary_n1 | +0.21 | +0.53 | -0.18 | 0.068 |
| artist_even_60_45_sep1 | binary_n2 | +0.62 | +0.87 | -0.11 | 0.064 |
| artist_even_60_45_sep1 | binary_n3 | +0.96 | +1.32 | -0.23 | 0.064 |
| artist_even_60_45_sep1 | duration_combined | +0.09 | +0.49 | -0.26 | 0.070 |
| artist_even_60_45_sep1 | duration_decay | +0.53 | +0.87 | -0.21 | 0.070 |
| artist_even_60_45_sep1 | duration_elapsed | +0.19 | +0.68 | -0.36 | 0.070 |
| artist_even_60_45_sep1 | duration_n2 | +0.13 | +0.36 | -0.09 | 0.072 |
| artist_even_60_45_sep1 | generation_count | +2.16 | +2.54 | -0.24 | 0.064 |
| artist_even_60_45_sep1 | heard_oracle | +0.80 | +1.17 | -0.24 | 0.072 |
| artist_even_60_45_sep1 | ordinal_decay | +0.16 | +0.57 | -0.28 | 0.069 |
| artist_even_60_45_sep1 | true_random | +0.00 | +0.00 | +0.14 | 0.073 |
| artist_even_None_0.5_sep1 | binary_7days | -0.05 | -0.03 | -0.02 | 0.029 |
| artist_even_None_0.5_sep1 | binary_n1 | -0.05 | -0.03 | -0.02 | 0.029 |
| artist_even_None_0.5_sep1 | binary_n2 | -0.05 | -0.03 | -0.02 | 0.029 |
| artist_even_None_0.5_sep1 | binary_n3 | -0.05 | -0.03 | -0.02 | 0.029 |
| artist_even_None_0.5_sep1 | duration_combined | -0.01 | -0.01 | -0.01 | 0.031 |
| artist_even_None_0.5_sep1 | duration_decay | -0.01 | +0.00 | -0.02 | 0.030 |
| artist_even_None_0.5_sep1 | duration_elapsed | -0.00 | -0.01 | -0.01 | 0.030 |
| artist_even_None_0.5_sep1 | duration_n2 | -0.01 | -0.00 | -0.02 | 0.030 |
| artist_even_None_0.5_sep1 | generation_count | -0.05 | -0.03 | -0.02 | 0.029 |
| artist_even_None_0.5_sep1 | heard_oracle | +0.47 | +0.49 | -0.03 | 0.029 |
| artist_even_None_0.5_sep1 | ordinal_decay | +0.20 | +0.21 | -0.01 | 0.030 |
| artist_even_None_0.5_sep1 | true_random | +0.00 | +0.00 | -0.01 | 0.029 |
| artist_even_None_30_sep1 | binary_7days | +0.09 | -0.27 | -0.12 | 0.100 |
| artist_even_None_30_sep1 | binary_n1 | +0.09 | -0.27 | -0.12 | 0.100 |
| artist_even_None_30_sep1 | binary_n2 | +0.09 | -0.27 | -0.12 | 0.100 |
| artist_even_None_30_sep1 | binary_n3 | +0.09 | -0.27 | -0.12 | 0.100 |
| artist_even_None_30_sep1 | duration_combined | +0.63 | -0.03 | +0.18 | 0.103 |
| artist_even_None_30_sep1 | duration_decay | +1.03 | +0.24 | +0.31 | 0.100 |
| artist_even_None_30_sep1 | duration_elapsed | +0.66 | +0.10 | +0.08 | 0.103 |
| artist_even_None_30_sep1 | duration_n2 | +0.56 | -0.00 | +0.08 | 0.113 |
| artist_even_None_30_sep1 | generation_count | +0.09 | -0.27 | -0.12 | 0.100 |
| artist_even_None_30_sep1 | heard_oracle | +1.21 | +0.39 | +0.34 | 0.109 |
| artist_even_None_30_sep1 | ordinal_decay | +0.51 | +0.21 | -0.18 | 0.112 |
| artist_even_None_30_sep1 | true_random | +0.00 | +0.00 | -0.48 | 0.108 |
| artist_kpop_180_30_sep1 | binary_7days | +0.69 | +0.34 | -1.07 | 0.226 |
| artist_kpop_180_30_sep1 | binary_n1 | +0.23 | +0.11 | -1.31 | 0.231 |
| artist_kpop_180_30_sep1 | binary_n2 | +0.51 | +0.24 | -1.16 | 0.233 |
| artist_kpop_180_30_sep1 | binary_n3 | +0.71 | +0.29 | -1.01 | 0.226 |
| artist_kpop_180_30_sep1 | duration_combined | +0.20 | -0.03 | -1.20 | 0.229 |
| artist_kpop_180_30_sep1 | duration_decay | +0.56 | +0.24 | -1.10 | 0.227 |
| artist_kpop_180_30_sep1 | duration_elapsed | +0.26 | +0.10 | -1.27 | 0.227 |
| artist_kpop_180_30_sep1 | duration_n2 | +0.19 | -0.01 | -1.22 | 0.228 |
| artist_kpop_180_30_sep1 | generation_count | +1.01 | +0.64 | -1.06 | 0.228 |
| artist_kpop_180_30_sep1 | heard_oracle | +0.84 | +0.39 | -0.97 | 0.226 |
| artist_kpop_180_30_sep1 | ordinal_decay | +0.58 | +0.20 | -1.05 | 0.227 |
| artist_kpop_180_30_sep1 | true_random | +0.00 | +0.00 | -1.43 | 0.230 |
| artist_kpop_60_45_sep1 | binary_7days | +2.12 | +2.20 | -0.25 | 0.059 |
| artist_kpop_60_45_sep1 | binary_n1 | +0.32 | +0.53 | -0.38 | 0.061 |
| artist_kpop_60_45_sep1 | binary_n2 | +0.59 | +0.87 | -0.45 | 0.061 |
| artist_kpop_60_45_sep1 | binary_n3 | +0.94 | +1.32 | -0.55 | 0.060 |
| artist_kpop_60_45_sep1 | duration_combined | +0.31 | +0.49 | -0.35 | 0.063 |
| artist_kpop_60_45_sep1 | duration_decay | +0.79 | +0.87 | -0.25 | 0.065 |
| artist_kpop_60_45_sep1 | duration_elapsed | +0.58 | +0.68 | -0.28 | 0.063 |
| artist_kpop_60_45_sep1 | duration_n2 | +0.28 | +0.36 | -0.26 | 0.065 |
| artist_kpop_60_45_sep1 | generation_count | +2.31 | +2.54 | -0.41 | 0.068 |
| artist_kpop_60_45_sep1 | heard_oracle | +1.17 | +1.17 | -0.18 | 0.067 |
| artist_kpop_60_45_sep1 | ordinal_decay | +0.56 | +0.57 | -0.19 | 0.060 |
| artist_kpop_60_45_sep1 | true_random | +0.00 | +0.00 | -0.17 | 0.067 |
| artist_kpop_None_0.5_sep1 | binary_7days | -0.03 | -0.03 | -0.25 | 0.091 |
| artist_kpop_None_0.5_sep1 | binary_n1 | -0.03 | -0.03 | -0.25 | 0.091 |
| artist_kpop_None_0.5_sep1 | binary_n2 | -0.03 | -0.03 | -0.25 | 0.091 |
| artist_kpop_None_0.5_sep1 | binary_n3 | -0.03 | -0.03 | -0.25 | 0.091 |
| artist_kpop_None_0.5_sep1 | duration_combined | +0.00 | -0.01 | -0.24 | 0.090 |
| artist_kpop_None_0.5_sep1 | duration_decay | +0.01 | +0.00 | -0.25 | 0.089 |
| artist_kpop_None_0.5_sep1 | duration_elapsed | +0.01 | -0.01 | -0.24 | 0.090 |
| artist_kpop_None_0.5_sep1 | duration_n2 | -0.01 | -0.00 | -0.26 | 0.090 |
| artist_kpop_None_0.5_sep1 | generation_count | -0.03 | -0.03 | -0.25 | 0.091 |
| artist_kpop_None_0.5_sep1 | heard_oracle | +0.55 | +0.49 | -0.19 | 0.090 |
| artist_kpop_None_0.5_sep1 | ordinal_decay | +0.26 | +0.21 | -0.20 | 0.091 |
| artist_kpop_None_0.5_sep1 | true_random | +0.00 | +0.00 | -0.25 | 0.091 |
| artist_kpop_None_30_sep1 | binary_7days | -0.04 | -0.27 | -1.54 | 0.260 |
| artist_kpop_None_30_sep1 | binary_n1 | -0.04 | -0.27 | -1.54 | 0.260 |
| artist_kpop_None_30_sep1 | binary_n2 | -0.04 | -0.27 | -1.54 | 0.260 |
| artist_kpop_None_30_sep1 | binary_n3 | -0.04 | -0.27 | -1.54 | 0.260 |
| artist_kpop_None_30_sep1 | duration_combined | +0.09 | -0.03 | -1.66 | 0.259 |
| artist_kpop_None_30_sep1 | duration_decay | +0.56 | +0.24 | -1.45 | 0.254 |
| artist_kpop_None_30_sep1 | duration_elapsed | +0.30 | +0.10 | -1.58 | 0.254 |
| artist_kpop_None_30_sep1 | duration_n2 | +0.12 | -0.00 | -1.65 | 0.258 |
| artist_kpop_None_30_sep1 | generation_count | -0.04 | -0.27 | -1.54 | 0.260 |
| artist_kpop_None_30_sep1 | heard_oracle | +0.78 | +0.39 | -1.39 | 0.261 |
| artist_kpop_None_30_sep1 | ordinal_decay | +0.20 | +0.21 | -1.79 | 0.259 |
| artist_kpop_None_30_sep1 | true_random | +0.00 | +0.00 | -1.78 | 0.259 |
| artist_moderate_180_30_sep1 | binary_7days | +0.43 | +0.34 | -0.84 | 0.210 |
| artist_moderate_180_30_sep1 | binary_n1 | -0.01 | +0.11 | -1.05 | 0.216 |
| artist_moderate_180_30_sep1 | binary_n2 | +0.34 | +0.24 | -0.83 | 0.209 |
| artist_moderate_180_30_sep1 | binary_n3 | +0.38 | +0.29 | -0.84 | 0.202 |
| artist_moderate_180_30_sep1 | duration_combined | +0.07 | -0.03 | -0.83 | 0.211 |
| artist_moderate_180_30_sep1 | duration_decay | +0.27 | +0.24 | -0.90 | 0.214 |
| artist_moderate_180_30_sep1 | duration_elapsed | +0.13 | +0.10 | -0.90 | 0.214 |
| artist_moderate_180_30_sep1 | duration_n2 | +0.10 | -0.01 | -0.82 | 0.211 |
| artist_moderate_180_30_sep1 | generation_count | +0.74 | +0.64 | -0.83 | 0.208 |
| artist_moderate_180_30_sep1 | heard_oracle | +0.33 | +0.39 | -0.99 | 0.212 |
| artist_moderate_180_30_sep1 | ordinal_decay | +0.32 | +0.20 | -0.81 | 0.212 |
| artist_moderate_180_30_sep1 | true_random | +0.00 | +0.00 | -0.93 | 0.225 |
| artist_moderate_60_45_sep1 | binary_7days | +1.86 | +2.20 | -0.30 | 0.073 |
| artist_moderate_60_45_sep1 | binary_n1 | -0.10 | +0.53 | -0.60 | 0.077 |
| artist_moderate_60_45_sep1 | binary_n2 | +0.17 | +0.87 | -0.66 | 0.076 |
| artist_moderate_60_45_sep1 | binary_n3 | +0.64 | +1.32 | -0.65 | 0.074 |
| artist_moderate_60_45_sep1 | duration_combined | -0.18 | +0.49 | -0.63 | 0.075 |
| artist_moderate_60_45_sep1 | duration_decay | +0.29 | +0.87 | -0.55 | 0.078 |
| artist_moderate_60_45_sep1 | duration_elapsed | -0.07 | +0.68 | -0.72 | 0.076 |
| artist_moderate_60_45_sep1 | duration_n2 | -0.21 | +0.36 | -0.54 | 0.075 |
| artist_moderate_60_45_sep1 | generation_count | +1.95 | +2.54 | -0.55 | 0.070 |
| artist_moderate_60_45_sep1 | heard_oracle | +0.65 | +1.17 | -0.49 | 0.076 |
| artist_moderate_60_45_sep1 | ordinal_decay | -0.10 | +0.57 | -0.64 | 0.076 |
| artist_moderate_60_45_sep1 | true_random | +0.00 | +0.00 | +0.04 | 0.086 |
| artist_moderate_None_0.5_sep1 | binary_7days | -0.02 | -0.03 | -0.25 | 0.097 |
| artist_moderate_None_0.5_sep1 | binary_n1 | -0.02 | -0.03 | -0.25 | 0.097 |
| artist_moderate_None_0.5_sep1 | binary_n2 | -0.02 | -0.03 | -0.25 | 0.097 |
| artist_moderate_None_0.5_sep1 | binary_n3 | -0.02 | -0.03 | -0.25 | 0.097 |
| artist_moderate_None_0.5_sep1 | duration_combined | +0.01 | -0.01 | -0.24 | 0.098 |
| artist_moderate_None_0.5_sep1 | duration_decay | +0.03 | +0.00 | -0.23 | 0.098 |
| artist_moderate_None_0.5_sep1 | duration_elapsed | +0.01 | -0.01 | -0.24 | 0.098 |
| artist_moderate_None_0.5_sep1 | duration_n2 | +0.03 | -0.00 | -0.23 | 0.097 |
| artist_moderate_None_0.5_sep1 | generation_count | -0.02 | -0.03 | -0.25 | 0.097 |
| artist_moderate_None_0.5_sep1 | heard_oracle | +0.53 | +0.49 | -0.22 | 0.098 |
| artist_moderate_None_0.5_sep1 | ordinal_decay | +0.27 | +0.21 | -0.20 | 0.097 |
| artist_moderate_None_0.5_sep1 | true_random | +0.00 | +0.00 | -0.26 | 0.099 |
| artist_moderate_None_30_sep1 | binary_7days | -0.23 | -0.27 | -0.89 | 0.234 |
| artist_moderate_None_30_sep1 | binary_n1 | -0.23 | -0.27 | -0.89 | 0.234 |
| artist_moderate_None_30_sep1 | binary_n2 | -0.23 | -0.27 | -0.89 | 0.234 |
| artist_moderate_None_30_sep1 | binary_n3 | -0.23 | -0.27 | -0.89 | 0.234 |
| artist_moderate_None_30_sep1 | duration_combined | -0.11 | -0.03 | -1.02 | 0.233 |
| artist_moderate_None_30_sep1 | duration_decay | +0.16 | +0.24 | -1.01 | 0.233 |
| artist_moderate_None_30_sep1 | duration_elapsed | -0.07 | +0.10 | -1.11 | 0.235 |
| artist_moderate_None_30_sep1 | duration_n2 | +0.01 | -0.00 | -0.93 | 0.228 |
| artist_moderate_None_30_sep1 | generation_count | -0.23 | -0.27 | -0.89 | 0.234 |
| artist_moderate_None_30_sep1 | heard_oracle | +0.37 | +0.39 | -0.96 | 0.227 |
| artist_moderate_None_30_sep1 | ordinal_decay | -0.30 | +0.21 | -1.44 | 0.237 |
| artist_moderate_None_30_sep1 | true_random | +0.00 | +0.00 | -0.94 | 0.240 |

## confirmation.json

37 scenarios, 5 candidates, 16 seeds, 48 generations; 2960 trials, 57.8 seconds.

| Candidate | Mean delta coverage10 | Mean delta AUC | Mean delta final | Worst AUC delta | Minimum effective choice | Minimum weight ratio |
| --- | --- | --- | --- | --- | --- | --- |
| true_random | +0.00 | +0.00 | +0.00 | +0.00 | 1.000 | 1.000 |
| binary_7days | +0.69 | +0.68 | +0.69 | -0.87 | 0.873 | 0.350 |
| duration_decay | +0.70 | +0.74 | +0.78 | -0.33 | 0.967 | 0.264 |
| generation_count | +0.74 | +1.12 | +1.57 | -0.78 | 0.900 | 0.175 |
| heard_oracle | +1.27 | +1.20 | +1.13 | -0.20 | 0.940 | 0.187 |

### Selected 500-track comparisons

| Scenario | Candidate | Coverage10 % | Final % | AUC delta +/- CI | Overlap | Mean repeat distance | Exposure Gini | Effective choice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 180M/30M | binary_7days | 15.90 | 57.02 | +0.68 +/- 0.47 | 0.011 | 16.0 | 0.549 | 0.880 |
| 180M/30M | duration_decay | 15.86 | 57.25 | +0.75 +/- 0.49 | 0.012 | 15.5 | 0.547 | 0.995 |
| 180M/30M | generation_count | 15.84 | 58.30 | +1.23 +/- 0.49 | 0.014 | 15.5 | 0.534 | 0.909 |
| 180M/30M | heard_oracle | 15.93 | 57.60 | +0.95 +/- 0.48 | 0.011 | 15.8 | 0.543 | 0.995 |
| 180M/30M | true_random | 15.71 | 55.58 | +0.00 +/- 0.00 | 0.016 | 14.4 | 0.568 | 1.000 |
| 60M/60M | binary_7days | 30.90 | 84.36 | +2.91 +/- 0.67 | 0.014 | 15.0 | 0.373 | 0.944 |
| 60M/60M | duration_decay | 29.30 | 81.78 | +0.64 +/- 0.62 | 0.028 | 13.4 | 0.403 | 0.995 |
| 60M/60M | generation_count | 30.01 | 89.29 | +4.47 +/- 0.53 | 0.025 | 14.3 | 0.317 | 0.923 |
| 60M/60M | heard_oracle | 29.69 | 82.70 | +1.35 +/- 0.60 | 0.023 | 13.8 | 0.393 | 0.990 |
| 60M/60M | true_random | 29.31 | 80.55 | +0.00 +/- 0.00 | 0.034 | 12.6 | 0.419 | 1.000 |
| Full/30M | binary_7days | 15.53 | 56.26 | +0.13 +/- 0.52 | 0.017 | 14.5 | 0.559 | 1.000 |
| Full/30M | duration_decay | 15.86 | 57.26 | +0.76 +/- 0.48 | 0.012 | 15.5 | 0.547 | 0.995 |
| Full/30M | generation_count | 15.53 | 56.26 | +0.13 +/- 0.52 | 0.017 | 14.5 | 0.559 | 1.000 |
| Full/30M | heard_oracle | 15.93 | 57.60 | +0.95 +/- 0.48 | 0.011 | 15.8 | 0.543 | 0.995 |
| Full/30M | true_random | 15.71 | 55.58 | +0.00 +/- 0.00 | 0.016 | 14.4 | 0.568 | 1.000 |
| burst_regenerate | binary_7days | 4.89 | 26.61 | -0.06 +/- 0.21 | 0.000 | 18.0 | 0.761 | 0.873 |
| burst_regenerate | duration_decay | 4.84 | 26.30 | -0.21 +/- 0.22 | 0.000 | 17.5 | 0.765 | 0.995 |
| burst_regenerate | generation_count | 4.88 | 26.83 | -0.01 +/- 0.22 | 0.000 | 16.9 | 0.757 | 0.909 |
| burst_regenerate | heard_oracle | 4.86 | 26.35 | -0.20 +/- 0.18 | 0.000 | 17.8 | 0.764 | 0.997 |
| burst_regenerate | true_random | 5.08 | 26.53 | +0.00 +/- 0.00 | 0.000 | 16.0 | 0.763 | 1.000 |
| heavy_180 | binary_7days | 16.00 | 56.62 | +0.58 +/- 0.48 | 0.016 | 15.1 | 0.555 | 0.873 |
| heavy_180 | duration_decay | 15.86 | 57.25 | +0.75 +/- 0.49 | 0.012 | 15.5 | 0.547 | 0.995 |
| heavy_180 | generation_count | 15.84 | 58.30 | +1.23 +/- 0.49 | 0.014 | 15.5 | 0.534 | 0.909 |
| heavy_180 | heard_oracle | 15.93 | 57.60 | +0.95 +/- 0.48 | 0.011 | 15.8 | 0.543 | 0.995 |
| heavy_180 | true_random | 15.71 | 55.58 | +0.00 +/- 0.00 | 0.016 | 14.4 | 0.568 | 1.000 |
| mixed | binary_7days | 20.16 | 65.65 | -0.28 +/- 0.53 | 0.023 | 13.8 | 0.506 | 0.955 |
| mixed | duration_decay | 20.45 | 66.73 | +0.54 +/- 0.53 | 0.020 | 14.7 | 0.493 | 0.995 |
| mixed | generation_count | 20.66 | 69.27 | +1.75 +/- 0.45 | 0.022 | 14.6 | 0.465 | 0.921 |
| mixed | heard_oracle | 20.57 | 67.19 | +0.81 +/- 0.49 | 0.018 | 15.0 | 0.487 | 0.993 |
| mixed | true_random | 20.51 | 65.74 | +0.00 +/- 0.00 | 0.025 | 13.7 | 0.507 | 1.000 |
| regenerate | binary_7days | 4.88 | 26.55 | -0.10 +/- 0.19 | 0.000 | 18.1 | 0.761 | 0.880 |
| regenerate | duration_decay | 4.84 | 26.30 | -0.21 +/- 0.22 | 0.000 | 17.5 | 0.765 | 0.995 |
| regenerate | generation_count | 4.88 | 26.83 | -0.01 +/- 0.22 | 0.000 | 16.9 | 0.757 | 0.909 |
| regenerate | heard_oracle | 4.86 | 26.35 | -0.20 +/- 0.18 | 0.000 | 17.8 | 0.764 | 0.997 |
| regenerate | true_random | 5.08 | 26.53 | +0.00 +/- 0.00 | 0.000 | 16.0 | 0.763 | 1.000 |
| shuffled_180 | binary_7days | 16.16 | 57.30 | +0.53 +/- 0.45 | 0.005 | 16.1 | 0.546 | 0.880 |
| shuffled_180 | duration_decay | 15.68 | 56.16 | -0.09 +/- 0.38 | 0.016 | 14.5 | 0.559 | 0.995 |
| shuffled_180 | generation_count | 15.89 | 57.15 | +0.40 +/- 0.49 | 0.013 | 15.3 | 0.547 | 0.909 |
| shuffled_180 | heard_oracle | 15.93 | 57.56 | +0.63 +/- 0.49 | 0.010 | 15.4 | 0.543 | 0.995 |
| shuffled_180 | true_random | 15.86 | 56.06 | +0.00 +/- 0.00 | 0.019 | 14.3 | 0.562 | 1.000 |
| shuffled_None | binary_7days | 15.34 | 55.06 | -0.25 +/- 0.71 | 0.019 | 13.8 | 0.572 | 1.000 |
| shuffled_None | duration_decay | 15.22 | 55.49 | +0.09 +/- 0.61 | 0.018 | 14.6 | 0.567 | 0.995 |
| shuffled_None | generation_count | 15.34 | 55.06 | -0.25 +/- 0.71 | 0.019 | 13.8 | 0.572 | 1.000 |
| shuffled_None | heard_oracle | 15.59 | 55.54 | +0.07 +/- 0.54 | 0.018 | 14.4 | 0.567 | 0.995 |
| shuffled_None | true_random | 15.46 | 55.36 | +0.00 +/- 0.00 | 0.019 | 14.8 | 0.569 | 1.000 |
| sparse_180 | binary_7days | 15.53 | 56.26 | +0.13 +/- 0.52 | 0.017 | 14.5 | 0.559 | 1.000 |
| sparse_180 | duration_decay | 15.86 | 57.25 | +0.75 +/- 0.49 | 0.012 | 15.5 | 0.547 | 0.995 |
| sparse_180 | generation_count | 15.84 | 58.30 | +1.23 +/- 0.49 | 0.014 | 15.5 | 0.534 | 0.909 |
| sparse_180 | heard_oracle | 15.93 | 57.60 | +0.95 +/- 0.48 | 0.011 | 15.8 | 0.543 | 0.995 |
| sparse_180 | true_random | 15.71 | 55.58 | +0.00 +/- 0.00 | 0.016 | 14.4 | 0.568 | 1.000 |

### Artist Separation comparisons

| Scenario | Candidate | Balanced AUC delta sep ON | Delta sep OFF | Absolute AUC ON minus OFF | Artist total-variation distance |
| --- | --- | --- | --- | --- | --- |
| artist_kpop_None_30_sep1 | binary_7days | +0.42 | +0.13 | -1.94 | 0.249 |
| artist_kpop_None_30_sep1 | duration_decay | +1.01 | +0.76 | -1.98 | 0.251 |
| artist_kpop_None_30_sep1 | generation_count | +0.42 | +0.13 | -1.94 | 0.249 |
| artist_kpop_None_30_sep1 | heard_oracle | +1.21 | +0.95 | -1.98 | 0.250 |
| artist_kpop_None_30_sep1 | true_random | +0.00 | +0.00 | -2.23 | 0.257 |

## matched_budget.json

15 scenarios, 5 candidates, 16 seeds, 48 generations; 1200 trials, 17.4 seconds.

| Candidate | Mean delta coverage10 | Mean delta AUC | Mean delta final | Worst AUC delta | Minimum effective choice | Minimum weight ratio |
| --- | --- | --- | --- | --- | --- | --- |
| true_random | +0.00 | +0.00 | +0.00 | +0.00 | 1.000 | 1.000 |
| binary_7days | +2.00 | +1.62 | +1.36 | +0.38 | 0.873 | 0.350 |
| duration_decay | +1.25 | +1.04 | +1.02 | +0.40 | 0.967 | 0.264 |
| generation_count | +1.80 | +2.02 | +2.68 | +0.80 | 0.895 | 0.175 |
| heard_oracle | +1.91 | +1.51 | +1.21 | +0.46 | 0.958 | 0.232 |

### Selected 500-track comparisons

| Scenario | Candidate | Coverage10 % | Final % | AUC delta +/- CI | Overlap | Mean repeat distance | Exposure Gini | Effective choice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 120M/30M | binary_7days | 15.96 | 57.64 | +0.90 +/- 0.46 | 0.009 | 16.2 | 0.543 | 0.904 |
| 120M/30M | duration_decay | 15.86 | 57.22 | +0.77 +/- 0.49 | 0.012 | 15.6 | 0.547 | 0.995 |
| 120M/30M | generation_count | 15.95 | 58.70 | +1.39 +/- 0.49 | 0.013 | 15.6 | 0.529 | 0.915 |
| 120M/30M | heard_oracle | 15.93 | 57.60 | +0.95 +/- 0.48 | 0.011 | 15.8 | 0.543 | 0.995 |
| 120M/30M | true_random | 15.71 | 55.58 | +0.00 +/- 0.00 | 0.016 | 14.4 | 0.568 | 1.000 |
| 30M/30M | binary_7days | 16.06 | 58.24 | +1.39 +/- 0.39 | 0.007 | 16.9 | 0.534 | 0.969 |
| 30M/30M | duration_decay | 15.91 | 57.42 | +0.87 +/- 0.48 | 0.012 | 15.6 | 0.545 | 0.996 |
| 30M/30M | generation_count | 15.99 | 61.44 | +2.38 +/- 0.49 | 0.011 | 15.7 | 0.495 | 0.950 |
| 30M/30M | heard_oracle | 15.93 | 57.60 | +0.95 +/- 0.48 | 0.011 | 15.8 | 0.543 | 0.995 |
| 30M/30M | true_random | 15.71 | 55.58 | +0.00 +/- 0.00 | 0.016 | 14.4 | 0.568 | 1.000 |
| 60M/30M | binary_7days | 16.04 | 58.02 | +1.25 +/- 0.41 | 0.008 | 16.7 | 0.537 | 0.944 |
| 60M/30M | duration_decay | 15.85 | 57.31 | +0.80 +/- 0.48 | 0.012 | 15.5 | 0.546 | 0.995 |
| 60M/30M | generation_count | 15.99 | 60.76 | +2.21 +/- 0.52 | 0.011 | 15.8 | 0.503 | 0.923 |
| 60M/30M | heard_oracle | 15.93 | 57.60 | +0.95 +/- 0.48 | 0.011 | 15.8 | 0.543 | 0.995 |
| 60M/30M | true_random | 15.71 | 55.58 | +0.00 +/- 0.00 | 0.016 | 14.4 | 0.568 | 1.000 |

### Artist Separation comparisons

| Scenario | Candidate | Balanced AUC delta sep ON | Delta sep OFF | Absolute AUC ON minus OFF | Artist total-variation distance |
| --- | --- | --- | --- | --- | --- |

## sensitivity.json

15 scenarios, 14 candidates, 8 seeds, 36 generations; 1680 trials, 23.6 seconds.

| Candidate | Mean delta coverage10 | Mean delta AUC | Mean delta final | Worst AUC delta | Minimum effective choice | Minimum weight ratio |
| --- | --- | --- | --- | --- | --- | --- |
| true_random | +0.00 | +0.00 | +0.00 | +0.00 | 1.000 | 1.000 |
| duration_tau20_p0.2 | -0.26 | +0.47 | +0.71 | -0.12 | 0.996 | 0.623 |
| duration_tau20_p0.45 | +0.25 | +0.82 | +1.01 | +0.04 | 0.983 | 0.360 |
| duration_tau20_p0.9 | +1.31 | +1.54 | +1.52 | +0.33 | 0.949 | 0.201 |
| duration_tau60_p0.2 | -0.15 | +0.60 | +0.83 | -0.09 | 0.992 | 0.550 |
| duration_tau60_p0.45 | +0.72 | +1.15 | +1.28 | +0.27 | 0.970 | 0.291 |
| duration_tau60_p0.9 | +1.86 | +1.93 | +1.90 | +0.40 | 0.916 | 0.175 |
| duration_tau120_p0.2 | -0.07 | +0.64 | +0.88 | +0.02 | 0.990 | 0.498 |
| duration_tau120_p0.45 | +0.76 | +1.14 | +1.27 | +0.26 | 0.960 | 0.244 |
| duration_tau120_p0.9 | +1.85 | +1.80 | +1.77 | +0.35 | 0.897 | 0.175 |
| duration_decay0.5 | +0.13 | +0.60 | +0.74 | -0.07 | 0.985 | 0.473 |
| duration_decay0.95 | +1.22 | +1.64 | +1.96 | +0.38 | 0.951 | 0.209 |
| binary_n2_p0.1 | +0.59 | +1.04 | +1.00 | -0.49 | 0.539 | 0.100 |
| binary_n2_p0.65 | -0.24 | +0.45 | +0.67 | -0.47 | 0.977 | 0.650 |

### Selected 500-track comparisons

| Scenario | Candidate | Coverage10 % | Final % | AUC delta +/- CI | Overlap | Mean repeat distance | Exposure Gini | Effective choice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 180M/30M | binary_n2_p0.1 | 15.93 | 46.33 | +0.32 +/- 1.07 | 0.002 | 12.7 | 0.619 | 0.861 |
| 180M/30M | binary_n2_p0.65 | 15.70 | 45.70 | +0.07 +/- 1.09 | 0.010 | 12.5 | 0.624 | 0.987 |
| 180M/30M | duration_decay0.5 | 15.55 | 45.73 | -0.07 +/- 1.12 | 0.010 | 12.3 | 0.623 | 0.998 |
| 180M/30M | duration_decay0.95 | 15.80 | 47.08 | +0.59 +/- 1.05 | 0.009 | 13.1 | 0.606 | 0.988 |
| 180M/30M | duration_tau120_p0.2 | 15.50 | 45.83 | +0.02 +/- 1.06 | 0.011 | 12.4 | 0.622 | 0.998 |
| 180M/30M | duration_tau120_p0.45 | 15.72 | 46.17 | +0.26 +/- 1.03 | 0.008 | 13.0 | 0.618 | 0.991 |
| 180M/30M | duration_tau120_p0.9 | 15.90 | 46.95 | +0.58 +/- 0.99 | 0.007 | 13.5 | 0.609 | 0.970 |
| 180M/30M | duration_tau20_p0.2 | 15.43 | 45.70 | -0.12 +/- 1.17 | 0.014 | 12.3 | 0.624 | 1.000 |
| 180M/30M | duration_tau20_p0.45 | 15.50 | 46.00 | +0.04 +/- 1.13 | 0.011 | 12.6 | 0.620 | 0.998 |
| 180M/30M | duration_tau20_p0.9 | 15.82 | 46.50 | +0.40 +/- 1.04 | 0.009 | 13.3 | 0.613 | 0.993 |
| 180M/30M | duration_tau60_p0.2 | 15.42 | 45.73 | -0.09 +/- 1.15 | 0.012 | 12.4 | 0.623 | 0.999 |
| 180M/30M | duration_tau60_p0.45 | 15.72 | 46.33 | +0.27 +/- 1.02 | 0.010 | 12.9 | 0.616 | 0.995 |
| 180M/30M | duration_tau60_p0.9 | 15.90 | 46.85 | +0.58 +/- 1.01 | 0.007 | 13.6 | 0.609 | 0.983 |
| 180M/30M | true_random | 15.62 | 45.65 | +0.00 +/- 0.00 | 0.016 | 11.7 | 0.628 | 1.000 |
| 60M/60M | binary_n2_p0.1 | 29.88 | 73.20 | +1.78 +/- 1.17 | 0.003 | 11.4 | 0.449 | 0.951 |
| 60M/60M | binary_n2_p0.65 | 28.88 | 71.97 | +0.75 +/- 1.31 | 0.020 | 10.7 | 0.462 | 0.995 |
| 60M/60M | duration_decay0.5 | 28.85 | 71.55 | +0.56 +/- 1.30 | 0.022 | 10.5 | 0.466 | 0.998 |
| 60M/60M | duration_decay0.95 | 29.30 | 74.45 | +1.84 +/- 1.20 | 0.024 | 11.1 | 0.432 | 0.988 |
| 60M/60M | duration_tau120_p0.2 | 28.80 | 71.95 | +0.71 +/- 1.28 | 0.026 | 10.6 | 0.461 | 0.999 |
| 60M/60M | duration_tau120_p0.45 | 29.22 | 73.10 | +1.38 +/- 1.33 | 0.022 | 11.1 | 0.448 | 0.993 |
| 60M/60M | duration_tau120_p0.9 | 29.88 | 74.78 | +2.42 +/- 1.30 | 0.015 | 11.8 | 0.428 | 0.978 |
| 60M/60M | duration_tau20_p0.2 | 28.70 | 71.47 | +0.45 +/- 1.26 | 0.027 | 10.5 | 0.466 | 1.000 |
| 60M/60M | duration_tau20_p0.45 | 28.95 | 72.00 | +0.78 +/- 1.29 | 0.026 | 10.7 | 0.460 | 0.998 |
| 60M/60M | duration_tau20_p0.9 | 29.18 | 72.80 | +1.24 +/- 1.29 | 0.024 | 11.0 | 0.451 | 0.993 |
| 60M/60M | duration_tau60_p0.2 | 28.80 | 71.72 | +0.61 +/- 1.31 | 0.026 | 10.6 | 0.463 | 0.999 |
| 60M/60M | duration_tau60_p0.45 | 29.05 | 72.72 | +1.14 +/- 1.26 | 0.024 | 10.9 | 0.451 | 0.995 |
| 60M/60M | duration_tau60_p0.9 | 29.57 | 74.17 | +2.05 +/- 1.31 | 0.019 | 11.5 | 0.436 | 0.984 |
| 60M/60M | true_random | 28.75 | 70.50 | +0.00 +/- 0.00 | 0.031 | 10.4 | 0.479 | 1.000 |
| Full/30M | binary_n2_p0.1 | 15.32 | 45.35 | -0.33 +/- 1.11 | 0.016 | 11.9 | 0.629 | 1.000 |
| Full/30M | binary_n2_p0.65 | 15.32 | 45.35 | -0.33 +/- 1.11 | 0.016 | 11.9 | 0.629 | 1.000 |
| Full/30M | duration_decay0.5 | 15.53 | 45.73 | -0.06 +/- 1.15 | 0.010 | 12.3 | 0.623 | 0.998 |
| Full/30M | duration_decay0.95 | 15.78 | 47.00 | +0.54 +/- 1.06 | 0.009 | 13.0 | 0.607 | 0.988 |
| Full/30M | duration_tau120_p0.2 | 15.47 | 45.93 | +0.03 +/- 1.06 | 0.012 | 12.4 | 0.621 | 0.998 |
| Full/30M | duration_tau120_p0.45 | 15.78 | 46.20 | +0.27 +/- 1.04 | 0.008 | 13.0 | 0.618 | 0.991 |
| Full/30M | duration_tau120_p0.9 | 15.95 | 46.75 | +0.62 +/- 0.97 | 0.007 | 13.5 | 0.611 | 0.971 |
| Full/30M | duration_tau20_p0.2 | 15.43 | 45.70 | -0.12 +/- 1.17 | 0.014 | 12.3 | 0.624 | 1.000 |
| Full/30M | duration_tau20_p0.45 | 15.50 | 46.00 | +0.04 +/- 1.13 | 0.011 | 12.6 | 0.620 | 0.998 |
| Full/30M | duration_tau20_p0.9 | 15.82 | 46.50 | +0.40 +/- 1.04 | 0.009 | 13.3 | 0.613 | 0.993 |
| Full/30M | duration_tau60_p0.2 | 15.42 | 45.73 | -0.09 +/- 1.15 | 0.012 | 12.4 | 0.623 | 0.999 |
| Full/30M | duration_tau60_p0.45 | 15.72 | 46.33 | +0.27 +/- 1.02 | 0.009 | 12.9 | 0.616 | 0.995 |
| Full/30M | duration_tau60_p0.9 | 15.90 | 46.80 | +0.57 +/- 1.00 | 0.007 | 13.7 | 0.610 | 0.983 |
| Full/30M | true_random | 15.62 | 45.65 | +0.00 +/- 0.00 | 0.016 | 11.7 | 0.628 | 1.000 |

### Artist Separation comparisons

| Scenario | Candidate | Balanced AUC delta sep ON | Delta sep OFF | Absolute AUC ON minus OFF | Artist total-variation distance |
| --- | --- | --- | --- | --- | --- |

## count_sensitivity.json

15 scenarios, 3 candidates, 8 seeds, 36 generations; 360 trials, 4.5 seconds.

| Candidate | Mean delta coverage10 | Mean delta AUC | Mean delta final | Worst AUC delta | Minimum effective choice | Minimum weight ratio |
| --- | --- | --- | --- | --- | --- | --- |
| true_random | +0.00 | +0.00 | +0.00 | +0.00 | 1.000 | 1.000 |
| count_p0.2 | -0.10 | +0.78 | +1.34 | -0.49 | 0.954 | 0.196 |
| count_p0.9 | +0.92 | +1.76 | +2.53 | -0.91 | 0.850 | 0.175 |

### Selected 500-track comparisons

| Scenario | Candidate | Coverage10 % | Final % | AUC delta +/- CI | Overlap | Mean repeat distance | Exposure Gini | Effective choice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 180M/30M | count_p0.2 | 15.62 | 46.62 | +0.28 +/- 0.98 | 0.013 | 12.3 | 0.613 | 0.966 |
| 180M/30M | count_p0.9 | 16.05 | 47.45 | +0.84 +/- 0.76 | 0.011 | 12.9 | 0.602 | 0.853 |
| 180M/30M | true_random | 15.62 | 45.65 | +0.00 +/- 0.00 | 0.016 | 11.7 | 0.628 | 1.000 |
| 60M/60M | count_p0.2 | 29.12 | 74.75 | +1.80 +/- 1.11 | 0.025 | 10.7 | 0.428 | 0.983 |
| 60M/60M | count_p0.9 | 31.02 | 82.92 | +6.23 +/- 1.10 | 0.017 | 12.1 | 0.332 | 0.867 |
| 60M/60M | true_random | 28.75 | 70.50 | +0.00 +/- 0.00 | 0.031 | 10.4 | 0.479 | 1.000 |
| Full/30M | count_p0.2 | 15.32 | 45.35 | -0.33 +/- 1.11 | 0.016 | 11.9 | 0.629 | 1.000 |
| Full/30M | count_p0.9 | 15.32 | 45.35 | -0.33 +/- 1.11 | 0.016 | 11.9 | 0.629 | 1.000 |
| Full/30M | true_random | 15.62 | 45.65 | +0.00 +/- 0.00 | 0.016 | 11.7 | 0.628 | 1.000 |

### Artist Separation comparisons

| Scenario | Candidate | Balanced AUC delta sep ON | Delta sep OFF | Absolute AUC ON minus OFF | Artist total-variation distance |
| --- | --- | --- | --- | --- | --- |
