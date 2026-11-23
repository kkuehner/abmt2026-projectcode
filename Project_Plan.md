# Project Plan – Mobility Credits & Congestion Pricing, Fribourg (eqasim / MATSim)

## Goal
Reduce car use (trips, km) and congestion, and shift demand to PT, bike and walk, using a
daily mobility-credit budget with congestion-dependent credit prices. Then check who carries the burden.

## Status (2026-11-23)

| Phase | Status |
|---|---|
| **0. Baseline** | ✅ Baseline run, evaluation script (`evaluate_matsim_fribourg.py`), congestion per link and 15-min bin (TTI) |
| **1a. Pricing** | ✅ Credit cost per trip, credit utility term (residents only, no income factor), credit-aware car routing |
| **1b. Budget** | ✅ Implemented and unit-tested (budget B, look-ahead constraint, scarcity penalty f, credit output CSV); not yet run on the scenario |
| **2. Testing & tuning** | 🔄 First run of 1a with realistic, moderate effects (see results); repeat once 1b exists |
| 3.–5. | Not started |

### First results: baseline vs. pricing without budget (residents)

| Indicator | Baseline | Pricing (1a) | Change |
|---|---|---|---|
| Car mode share (incl. car passenger) | 66.1 % | 61.3 % | −4.9 pp |
| PT / bike / walk share | 10.4 / 2.4 / 21.1 % | 13.1 / 3.1 / 22.6 % | +2.7 / +0.7 / +1.5 pp |
| Car km (scaled) | 12.69 M | 12.41 M | −2.2 % |
| Network delay, all traffic (veh·h) | 55,663 | 47,420 | −14.8 % |

The shift is strongest for 2–20 km trips (car −5 to −6 pp, mostly to PT); trips above 50 km don't change.
Source: `evaluation_base_vs_phase1/`.

## Decisions & parameters (single source of truth)

### Design decisions

| # | Topic | Decision | Why |
|---|---|---|---|
| D1 | Who takes part | Residents of canton Fribourg (`cantonName = fribourg`); all others exempt | Study targets Fribourg; others still use the roads |
| D2 | Credits in utility | `β_cost · creditValue_CHF · credits`, plain cost coefficient, **no income factor** | A credit should hurt every agent equally |
| D3 | Congestion pricing | Fixed per link and 15-min bin from baseline TTI (option B); dynamic (C) later | Stable, explainable; no feedback oscillation |
| D4 | Routing | Residents' car routing adds the same link credits (as seconds) | Route and mode choice must see the same prices |
| D5 | Car passenger | Pays car credits ÷ occupancy; routed like before | Not linked to a driver in eqasim; otherwise a free loophole |
| D6 | Budget B (equal) | `B = max(6 PT trips, one car round trip of 2 × 10 km)`, both at **off-peak / uncongested** prices | A car day has ≥ 2 trips: pricing the allowance as one 20 km trip would forbid almost every car commute |
| D7 | Budget too small for a day | `B_person = max(B, minimum credits the agent's day needs)` | Every agent must keep a feasible day |
| D8 | Unaffordable options | Excluded by a **look-ahead constraint** (never by utility 0 / −∞) | Tours are chosen one after another; without look-ahead the morning tour can strand the evening tour |
| D9 | Safety net | Options **without car / car passenger** are never rejected; overspending is logged | Guarantees a feasible option (else the run crashes), even for tours DMC doesn't replan or for estimation errors |
| D10 | Future need (look-ahead) | Per remaining trip: walk if ≤ 2 km, bike if ≤ 8 km, else PT; distance = straight line × 1.3 | No routing needed; PT credits don't depend on the route |
| D11 | Scarcity penalty f | `f = w · (credit term per credit) · c_tour · s^k`, `s = (c_tour + future need) / balance` (0..1) | Plenty left → s ≈ 0 → ~no penalty; tight → full penalty |
| D12 | Budget calibration check | Later: check B against the baseline's daily credits per resident ("B covers X %"), may replace D6 | Ties B to observed behaviour instead of a rule of thumb |

### Parameters

`--credit-parameter:<name> <value>` or a yml file (`mobilityCredits.parametersPath`). Values are the current defaults.

| Parameter | Value | Group | Why |
|---|---|---|---|
| `creditValue_CHF` | 1.0 | lever | 1 credit ≈ 1 CHF, easy to interpret |
| `car.constant_credits` | 1.0 | lever | Per-trip charge, hits short (replaceable) car trips |
| `car.distanceFactor_credits` (× ln(1+km)) | 2.0 | lever | Grows with distance, but less per km for long, often necessary trips |
| `car.congestionThresholdMedium` / `High` (TTI) | 1.5 / 2.0 | fixed | Common TTI levels for moderate / heavy congestion; ignore minor delays |
| `car.mediumCongestion_credits_km` / `high…` | 0.5 / 1.0 | lever | Congestion adds up to +1 credit/km |
| `pt.constant_credits` | 0.5 | lever | Clearly cheaper than car, above bike/walk |
| `pt.peakFactor` | 1.5 | lever | Mild peak surcharge |
| `pt.morning/eveningPeak` (h) | 7–9 / 16–19 | fixed | To be checked against the baseline departure profile |
| `bike.constant_credits` | 0.1 | lever | Small, keeps bike above walk |
| `carPassenger.occupancy` | 2.0 | fixed | Driver and passenger share the car credits |
| `routingValueOfTime_CHF_h` | 15.0 | fixed | Same as the tolls value of time |
| `budget.ptTrips` | 6 | lever | D6: allowance for a PT-heavy day |
| `budget.carRoundTripOneWay_km` | 10.0 | lever | D6: allowance for a typical car commute (2 × 10 km) → B ≈ 11.6 credits |
| `budget.walkMax_km` / `bikeMax_km` | 2 / 8 | fixed | D10: matches the baseline mode-by-distance shares |
| `budget.beelineFactor` | 1.3 | fixed | Usual straight-line → network detour |
| `scarcity.weight` (w) | 1.0 | behavioural | At full scarcity a credit counts double |
| `scarcity.exponent` (k) | 2.0 | behavioural | Penalty only bites when credits get tight |

**Groups:** *fixed* = set once from data or convention, not tuned; *lever* = varied in a small scenario grid
(the policy being studied); *behavioural* = unknown, shown as sensitivity. Fix the targets before tuning
(e.g. "−X % car km, no group loses more than Y"), otherwise the result is chosen, not found.

## Open issues

1. **TTI noise in the congestion file.**
   - 55 % of "high congestion" bins (TTI ≥ 2) come from only 1–2 simulated vehicles.
   - 16 % of night bins (0–5h) have TTI ≥ 2: intersection delay on short links, not congestion.
   - Fix: only keep bins with a minimum volume (e.g. ≥ 5 simulated vehicles) when writing the file.
2. **Evaluation merges car passenger into "Car"**: shifts to riding along are invisible, and car km include passenger km.
3. **Evaluation's resident filter** uses `in_boundary` / `is_resident` and falls back to *all persons*;
   the simulation uses `cantonName = fribourg`. Make sure both match.
4. **Congestion file without an end-time column** is read as 15-min bins (hard-coded 900 s in `LinkCongestionTable`).

## Model

### Cost per trip

| Mode | Credits |
|------|---------|
| Car  | constant + `distanceFactor` × ln(1 + km) + Σ congestion price of each link entered |
| PT   | constant × peak factor (in peak windows) |
| Bike | constant |
| Walk | 0 |
| Car passenger | car credits of the same trip ÷ occupancy |

Congestion price of a link = (credits/km of its TTI level when the car enters it) × link length.
Input file: `link_id`, bin start (`time_bin_start_s` or `bin_start_hour`), optional bin end, `tti` or `congestion_ratio_per_link`.

### Utility (per tour)
`U = Σ_trips (U_base − β_cost · creditValue_CHF · credits) − f`

### Budget and look-ahead (per replanning of one agent)
```
for each tour of the day, in order:
  for each mode combination:
     utility (incl. credit term) − f
     look-ahead: credits of earlier tours + this tour + minimum future need ≤ B_person ?
                 no → reject, unless the option uses neither car nor car passenger (D9)
  logit picks one option
```

**Phase-3 extension:** per-agent walk/bike distance limits, drawn once per agent (person id + seed) from a
log-normal fitted to the baseline trip distances per mode.

## Phases

| Phase | Content | Expected outcome |
|-------|---------|------------------|
| **0. Baseline** | Calibrated Fribourg 10% scenario unchanged; link × time congestion | Reference values; input for link prices |
| **1a. Pricing** | Credit costs, credit utility term, credit-aware routing | Runnable "pricing without budget" scenario |
| **1b. Budget** | Budget B, look-ahead constraint, f, credit output CSV | Credit balances visible; budget binds for some agents |
| **2. Testing & tuning** | Scenario grid over levers; sensitivity for behavioural parameters | Fewer car trips and car km, less peak delay, stable convergence |
| **3. Extensions (optional)** | Dynamic pricing (C), departure-time choice, per-agent distance limits, more detailed f | Peak spreading; comparison B vs C |
| **4. Distribution by metrics** | Model from 2–3 fixed; scale B by distance to work and PT availability (OVGK) | Similar effect, smaller burden for poorly served / long-commute groups |
| **5. Analysis** | Baseline vs. pricing only vs. equal budget vs. metric-based budget | Mode shares, car km, delay, utility loss by group (income, OVGK, region), credit balances |

**Limitations:** activity chains are fixed (measure utility loss, not lost activities); no PT crowding; bikes pass
queued cars (seepage, 0.2 PCU); freight is exempt; "dynamic" means "based on the previous iteration".

## Running a scenario
```bash
mvn -Pstandalone -pl switzerland -am package -DskipTests        # in repo root
mkdir -p ~/AbMT/runs/<name> && cd ~/AbMT/runs/<name>             # never run inside the scenario folder
java -Xmx18g -cp <repo>/switzerland/target/switzerland-2.0.0.jar org.eqasim.switzerland.ch_cmdp.RunSimulation \
  --config-path ~/AbMT/abmt-2026/A2.1/Fribourg_10pct/fribourg_config.xml \
  --config:controller.outputDirectory ~/AbMT/runs/<name>/output --config:global.numberOfThreads 16
```
Keep the seed (518745) and the git commit fixed across compared runs.

**With mobility credits** (off by default, so the command above is the baseline), add:
```bash
  --config:mobilityCredits.enabled true \
  --config:mobilityCredits.congestionPath <path/to/link_congestion_15min_bins.csv> \
  --credit-parameter:creditValue_CHF 1.0          # any parameter from the table above
```
Optional: `mobilityCredits.parametersPath` (yml with parameters), `residentAttribute` / `residentValue`.
The log shows how many persons are residents and how many links have congestion data.

**With the budget (1b)**, also add `--config:mobilityCredits.budgetEnabled true`. Without it, the run is
"pricing without budget" (1a). The run scripts then switch the DMC settings themselves (tour estimator
`MobilityCreditsCumulative`, extra tour constraint `MobilityCreditsBudget`); the config needs modelType `Tour` and
tourEstimator `Cumulative`, which the Fribourg config has.

**Credit output** (whenever credits are enabled, also without budget):
- Log line per iteration: mean credits/day, % of residents with < 10 % budget left, % over budget, % budget raised,
  options rejected by the look-ahead, options accepted by the safety net.
- `ITERS/it.N/N.mobility_credits_persons.csv.gz` every `mobilityCredits.outputInterval` iterations (default 10)
  and in the last: budget, credits used, balance, credits per mode per resident.
- `mobility_credits_trips.csv.gz` in the last iteration: credits per trip.
- A pricing-only run's persons file gives today's daily credits per resident: the input for D12.
