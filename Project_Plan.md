# Project Plan – Mobility Credits & Congestion Pricing, Fribourg (eqasim / MATSim)

## Goal
Reduce car use (trips, km) and congestion, and shift demand to PT, bike and walk, using a
daily mobility-credit budget with congestion-dependent credit prices. Then check who carries the burden.

## Model

### Cost per trip
`cost = base cost (CHF, existing model) + credits × scaling factor`

| Mode | Credits |
|------|---------|
| Car  | mode constant + Σ over links on route of credit(link, time slot) + distance term (log for extra km) |
| PT   | mode constant × time-of-day factor (far below car, above bike/walk) |
| Bike | mode constant (optional: scaled with car congestion, low priority) |
| Walk | 0 |

**Congestion pricing on car links**
- **Main version (B):** a fixed price per link and time slot, derived from baseline congestion (congested / free-flow travel time, grouped into a few levels).
- **Optional extension (C):** dynamic prices, updated every iteration from the previous iteration's travel times, smoothed to avoid oscillation.
- Route choice uses the same link prices as mode choice, through the existing `tolls` module (extended to depend on time).

### Utility
`U = U_base − f(credit cost, balance, future trips)`

The exact shape of f is still open. It must fulfil:
1. Trips the remaining balance can't cover are **not** free: they are either infeasible or the missing credits are bought (exact rule still open).
2. Credits the agent still needs later in the day increase the penalty; needing few future credits gives a small penalty.
3. The penalty scales with credit cost relative to the remaining balance.
4. Heuristic for future need: the agent can still afford the car for its remaining trips → almost no penalty; only PT is still affordable → medium penalty; only bike/walk is left → high penalty.

The agent's remaining trips are known from its plan. The balance depends on the modes chosen for all trips of the day, so f is evaluated at plan/tour level.

### Budget (equal distribution)
`B = max( credits for 6 PT trips at mean time of day , credits for 20 km by car at mean time of day )`

## Phases

| Phase | Content | Expected outcome |
|-------|---------|------------------|
| **0. Baseline** | Run the calibrated Fribourg 10% scenario unchanged; derive the link × time-slot congestion levels | Reference values (mode shares, car km, delay); input for the link prices |
| **1. Simple model** | Implement the cost functions above, credit tracking and a simple f; equal budget | Runs end to end; credit costs and balances visible in the output |
| **2. Testing & tuning** | Tune the hyperparameters (mode constants, slot factors, log distance, scaling factor, f parameters, budget) | Fewer car trips and less car km, less peak delay, stable convergence; the budget binds for a meaningful share of agents |
| **3. Extensions (optional)** | Dynamic pricing (C), departure-time choice, a more detailed f / cost model | Peak spreading (departure times); comparison of B vs C |
| **4. Distribution by metrics** | Use the utility/cost model agreed in phases 2–3, kept unchanged; scale B by distance to main work location and PT availability (OVGK classes) | Similar effect, smaller burden for poorly served / long-commute groups |
| **5. Analysis** | Compare baseline, pricing without budget, equal distribution and metric-based distribution | Mode shares, car km, vehicle-hours of delay, utility loss by group (income, OVGK, region), distribution of credit balances |

**Limitations:** activity chains are fixed (so measure utility loss, not lost activities); no PT crowding; bike is teleported; freight/commercial traffic is outside mode choice (exempt); "dynamic" means "based on the previous iteration", not live congestion.

## Running a scenario
```bash
mvn -Pstandalone -pl switzerland -am package -DskipTests        # in repo root
mkdir -p ~/AbMT/runs/<name> && cd ~/AbMT/runs/<name>             # never run inside the scenario folder
java -Xmx18g -cp <repo>/switzerland/target/switzerland-2.0.0.jar org.eqasim.switzerland.ch_cmdp.RunSimulation \
  --config-path ~/AbMT/abmt-2026/A2.1/Fribourg_10pct/fribourg_config.xml \
  --config:controller.outputDirectory ~/AbMT/runs/<name>/output --config:global.numberOfThreads 16
```
Keep the seed (518745) and the git commit fixed across compared runs.

## Brief for the coding agent – current step (Phase 1: simple model)

> We study a mobility-credit scheme for Fribourg in the eqasim Swiss model (`switzerland/.../ch_cmdp`).
> Read `Project_Plan.md` (sections "Model" and "Budget") first.
> **Task:** implement the credit cost and a first, simple credit utility term. Everything must be switchable via config, and with the scheme disabled, results must be identical to the baseline.
>
> 1. **Config group** `mobilityCredits`: enabled, mode constants, PT time-slot factors, car distance term (log), scaling factor (credits → utility), budget rule inputs (6 PT trips / 20 km car), path to link-price file, f parameters.
> 2. **Link prices (B):** a tool that computes credit(link, time slot) from baseline output (congested / free-flow time, binned into levels) and writes a file; it is loaded at startup.
> 3. **Credit cost model:** car = constant + sum of link prices along the routed path at estimated link entry times + log distance term; PT = constant × slot factor; bike = constant; walk = 0. Existing costs live in `mode_choice/costs/` and `SwissCostParameters`.
> 4. **Routing:** extend `tolls/` so car link disutility includes the time-dependent credit price (same prices as in 3).
> 5. **Utility:** add `− f(...)` at plan/tour level using the agent's remaining balance and remaining trips; start with a simple f that satisfies the four properties in the plan. Possible hooks: estimators' `estimateMonetaryCostUtility`, `core/.../policies/utility/UtilityPenalty`, constraints registered in `SwissModeChoiceModule`. The DMC model type is `Tour`.
> 6. **Output:** per trip: credits; per person: budget, credits used, credits beyond the balance. One CSV per iteration.
> 7. **Unit tests** for the cost model, link prices, budget and f; plus a regression test that the disabled scheme gives the same results as the baseline.
>
> Do not modify calibration code or existing calibrated parameters.
