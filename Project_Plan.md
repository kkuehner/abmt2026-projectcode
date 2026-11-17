# Project Plan – Mobility Credits & Congestion Pricing, Fribourg (eqasim / MATSim)

## Goal
Reduce car use (trips, km) and congestion, and shift demand to PT, bike and walk, using a
daily mobility-credit budget with congestion-dependent credit prices. Then check who carries the burden.

## Study population
The experiment only targets **residents of the canton of Fribourg**. Everyone else is still simulated (they use
the same roads and PT), but has unlimited credits, i.e. is fully exempt from the scheme.

| Group (10% sample) | Identified by | Agents | Scheme |
|---|---|---|---|
| Fribourg residents | `cantonName = fribourg` | ~34,500 | credits, link prices, f, look-ahead |
| Residents of other cantons | other `cantonName` | ~17,800 | exempt |
| Cross-border commuters | `isCrossBorder = true` / subpopulation `crossborder` | ~1,200 | exempt (only reroute anyway) |
| Freight | subpopulation `freight` | ~8,100 | exempt (only reroute anyway) |

- **Exempt** means: no credit cost, no f, no look-ahead constraint, and car routing without credit link prices.
- The residence filter is a config parameter (attribute name + value), so it can be changed later.
  Check with the course team whether `cantonName` is the intended definition.
- **Expected side effect:** roads freed by Fribourg residents can attract exempt traffic (rerouting into the
  priced area). This reduces the measured congestion relief and should be reported.
- **Evaluation:** mode shift, credit use and burden are measured on Fribourg residents only.
  Congestion (delay, link speeds) is measured on all traffic.

### Cost per trip
`cost = base cost (CHF, existing model) + credits × scaling factor`

| Mode | Credits |
|------|---------|
| Car  | mode constant + Σ over links on route of credit(link, time slot) + distance term (log for extra km) |
| PT   | mode constant × time-of-day factor (far below car, above bike/walk) |
| Bike | mode constant (optional: scaled with car congestion, low priority) |
| Walk | 0 |
| Car passenger | car credits for the same trip ÷ occupancy (parameter, e.g. 2) |

Car passengers are not linked to a driver in eqasim, so the driver still pays the full car price.
The occupancy split keeps car passenger from becoming a free way out of the scheme.

**Congestion pricing on car links**
- **Main version (B):** a fixed price per link and time slot, derived from baseline congestion (congested / free-flow travel time, grouped into a few levels).
  - Input from Step 0 (CSV, may be gzipped, `,` or `;`): `link_id,time_bin_start_s,time_bin_end_s,congestion_ratio_per_link`.
    The ratio is congested / free-flow travel time of the link in that bin.
    One row per link and time bin; bins of a link must not overlap; links/times without a row count as uncongested (1.0).
  - Ratio → credits (in the simulation, tunable): below `car.congestionThresholdMedium` → 0; medium/high level →
    `car.mediumCongestion_credits_km` / `car.highCongestion_credits_km` × link length in km.
- **Optional extension (C):** dynamic prices, updated every iteration from the previous iteration's travel times, smoothed to avoid oscillation.
- Route choice uses the same link prices as mode choice, through the eqasim policy framework's `RoutingPenalty` (already applied in `EqasimTravelDisutility`; the `tolls/` module is not needed).

### Utility
`U = U_base + β_credit · credits − f(credit cost, balance, future trips)`

- **Credit term without income factor:** `β_credit · credits` (β_credit = the scaling factor) is a separate term.
  It is *not* passed through the existing monetary cost term, which multiplies by `interaction(distance, income)`.
  A credit hurts every agent equally; the CHF costs of the base model stay unchanged.
- **Unaffordable options are excluded by a constraint**, never through the utility value (no 0, no −∞).

The exact shape of f is still open. It must fulfil:
1. Options the remaining balance can't cover are excluded (constraint, see below).
2. Credits the agent still needs later in the day increase the penalty; needing few future credits gives a small penalty.
3. The penalty scales with credit cost relative to the remaining balance.
4. Heuristic for future need: the agent can still afford the car for its remaining trips → almost no penalty; only PT is still affordable → medium penalty; only bike/walk is left → high penalty.

### Look-ahead over the whole day
DMC chooses tours **one after another** (e.g. morning tour, then evening tour). An earlier tour is never reconsidered
within a replanning, and the next replanning repeats the same order. If the morning tour spends all credits,
the evening tour may have no option left, and the run crashes (`fallbackBehaviour = EXCEPTION`).

- **Decision: look-ahead constraint.** The constraint sees all trips of the day. For each candidate of the
  current tour it checks: `balance after this tour ≥ minimum credits needed for all remaining trips`. Otherwise
  the candidate is rejected.
  - "Minimum credits" = the cheapest *acceptable* mode per remaining trip.
  - Walk/bike only count as acceptable up to a maximum distance (parameters). Otherwise walk (0 credits) would
    always count as affordable and the check would never bind.
  - This guarantees that every later tour keeps at least one feasible option. f (property 2) additionally keeps
    a soft reserve.
- **Extension – per-agent thresholds:** instead of one population-wide maximum distance per mode, each agent gets
  its own acceptable-distance thresholds for car, PT, bike and walk.
  - The thresholds are drawn from a distribution fitted to the population, e.g. to the trip distances per mode
    in the baseline output.
  - A Gaussian can produce negative distances. Truncate it at 0, or use a log-normal (positive and right-skewed,
    like real trip distances).
  - Draw once per agent, deterministically from person id + seed, like `tolls/MarginalCostOfTolls`. If the
    thresholds were redrawn at every replanning, choices would jump between iterations.
  - Optional: condition the fit on person groups, e.g. age (older agents walk/bike shorter distances).
- **Rejected – choose the whole day jointly** (`tourFinder = PlanBased`): combinations grow as modes^trips, and the
  `TourLengthFilter` (max 8 trips) would freeze all longer days.

### Budget (equal distribution)
`B = max( credits for 6 PT trips at mean time of day , credits for 20 km by car at mean time of day )`

## Phases

| Phase | Content | Expected outcome |
|-------|---------|------------------|
| **0. Baseline** | Run the calibrated Fribourg 10% scenario unchanged; derive the link × time-slot congestion levels | Reference values (mode shares, car km, delay); input for the link prices |
| **1. Simple model** | Implement the cost functions above, credit tracking and a simple f; equal budget | Runs end to end; credit costs and balances visible in the output |
| **2. Testing & tuning** | Tune the hyperparameters (mode constants, slot factors, log distance, scaling factor, f parameters, budget) | Fewer car trips and less car km, less peak delay, stable convergence; the budget binds for a meaningful share of agents |
| **3. Extensions (optional)** | Dynamic pricing (C), departure-time choice, per-agent look-ahead thresholds (drawn from a fitted distribution), a more detailed f / cost model | Peak spreading (departure times); comparison of B vs C |
| **4. Distribution by metrics** | Use the utility/cost model agreed in phases 2–3, kept unchanged; scale B by distance to main work location and PT availability (OVGK classes) | Similar effect, smaller burden for poorly served / long-commute groups |
| **5. Analysis** | Compare baseline, pricing without budget, equal distribution and metric-based distribution | Mode shares, car km, vehicle-hours of delay, utility loss by group (income, OVGK, region), distribution of credit balances |

**Limitations:** activity chains are fixed (so measure utility loss, not lost activities); no PT crowding; bikes are simulated on the network but pass queued cars (seepage, 0.2 PCU), so they barely cause or suffer congestion; freight/commercial traffic is outside mode choice (exempt); "dynamic" means "based on the previous iteration", not live congestion.

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
  --config:mobilityCredits.congestionPath <path/to/congestion.csv.gz> \
  --credit-parameter:creditValue_CHF 1.0          # any parameter in MobilityCreditsParameters, e.g. car.constant_credits
```
Optional: `mobilityCredits.parametersPath` (yml with parameters), `residentAttribute` / `residentValue` (default `cantonName` = `fribourg`).
The log shows how many persons are residents and how many links have congestion data.

## Brief for the coding agent – current step (Phase 1: simple model)

> We study a mobility-credit scheme for Fribourg in the eqasim Swiss model (`switzerland/.../ch_cmdp`).
> Read `Project_Plan.md` (sections "Model" and "Budget") first.
> **Task:** implement the credit cost and a first, simple credit utility term. Everything must be switchable via config, and with the scheme disabled, results must be identical to the baseline.
>
> 0. **Scope:** the scheme applies only to Fribourg residents (person filter from config, default `cantonName = fribourg`); all other agents (other cantons, cross-border, freight) are exempt. That means no credit cost, no f, no look-ahead constraint, and no credit link prices in routing. The router's `Person` can be null in some routing calls, so treat null as exempt and log how often it happens.
> 1. **Config group** `mobilityCredits`: enabled, resident filter (attribute + value), mode constants, PT time-slot factors, car distance term (log), β_credit (credits → utility), car-passenger occupancy, max acceptable walk/bike distance for the look-ahead, budget rule inputs (6 PT trips / 20 km car), path to link-price file, f parameters.
> 2. **Link prices (B):** a tool that computes credit(link, time slot) from baseline output (congested / free-flow time, binned into levels) and writes a file; it is loaded at startup.
> 3. **Credit cost model:** car = constant + sum of link prices along the routed path at estimated link entry times + log distance term; PT = constant × slot factor; bike = constant; walk = 0; car passenger = car credits ÷ occupancy. Existing costs live in `mode_choice/costs/` and `SwissCostParameters`.
> 4. **Routing:** implement the policy framework's `RoutingPenalty` (`getLinkPenalty(link, person, time, …)`) with the same link prices as in 3, converted to router units as `CityTaxPolicyFactory` does. Do not modify `tolls/`.
> 5. **Utility:** add `β_credit · credits` as a separate term, **not** inside `estimateMonetaryCostUtility` (that one multiplies by the income/distance interaction). Add `− f(...)` using the agent's remaining balance and remaining trips; start with a simple f that satisfies the four properties in the plan. Hooks: the credit term as a policy `UtilityPenalty` (per trip, template `policies/impl/city_tax/`); f in a custom `TourEstimator` wrapping `CumulativeTourEstimator`, because only the tour level sees previous tours (register with `bindTourEstimator`). The DMC model type is `Tour`, with tours chosen sequentially.
> 6. **Look-ahead constraint:** a `TourConstraint` (register like the constraints in `SwissModeChoiceModule` / `EqasimVehicleTourConstraint`). It is created with all trips of the day; it rejects a tour candidate if the balance afterwards is below the minimum credits needed for the remaining trips (cheapest acceptable mode, walk/bike only up to the configured distances). Never exclude options via −∞ utility. Read the distance thresholds through a small interface (fixed per mode now; per-agent draws are a planned extension).
> 7. **Output:** per trip: credits; per person: budget, credits used, remaining balance at end of day, number of candidates rejected by the look-ahead. One CSV per iteration.
> 8. **Unit tests** for the cost model, link prices, budget, f and the look-ahead (a two-tour day where the morning car would leave the evening tour without options must be rejected); plus a regression test that the disabled scheme gives the same results as the baseline.
>
> Do not modify calibration code or existing calibrated parameters.
