# Project Plan – Mobility Credits & Dynamic Pricing in Fribourg (eqasim / MATSim)

## Goal
Reduce car use (trips, km) and congestion in Fribourg, and shift demand to PT, bike and walk,
using a daily **mobility-credit budget** plus **time-dependent credit prices**.
Assess effectiveness *and* fairness (who loses, especially low PT accessibility / long commutes).

## Core design (decisions marked ⚙ are config parameters, defaults in brackets)

**Credit cost per trip**
| Mode | Credits |
|------|---------|
| Car  | mode constant + time-of-day bin factor × distance term ⚙ (linear; log as variant) |
| PT   | mode constant × time-of-day bin factor ⚙ (factor 0 = free) |
| Bike | mode constant ⚙ (0) – bike is teleported, no congestion to price |
| Walk | 0 |
| Car passenger | ⚙ (same as car / 0) – must be decided explicitly |

- Time-of-day bins are derived from baseline congestion (link statistics). Dynamic (previous-iteration) congestion pricing comes later, with smoothing.
- **Into utility:** the credit cost enters the existing monetary cost term as `λ · credits` (λ in CHF/credit ⚙).
- **Budget:** each agent gets a daily balance B. Trips beyond the balance are allowed, but the missing credits are bought at price `p_buy` ⚙ (CHF/credit). No trip is ever "free" because the balance is exhausted.
- **Plan-level evaluation:** the balance depends on all trips of the day, so budget logic runs per plan/tour, not per trip in isolation.
- Optional variant: the heuristic penalty f(balance, remaining required trips) for sensitivity analysis; endogenous λ (market clearing) as an extension.

## Steps

| # | Step | Output / what to expect |
|---|------|-------------------------|
| 0 | **Baseline** – run calibrated Fribourg scenario, extract peak bins from link stats | Reference mode shares, car-km, delay; time-of-day bin definition |
| 1 | **Pricing-only reference** – credit prices converted to CHF, no budget | Isolates the effect of pricing alone (needed for comparison) |
| 2 | **Credit cost model** (see brief below) | Per-trip credit cost for each mode, written to output; unit tests |
| 3 | **Budget + equal distribution** – one B for everyone (e.g. "20 km car at mean time" *or* "6 PT trips" – pick one) | Car share ↓, peak delay ↓; check that the budget actually binds for a meaningful share of agents |
| 4 | **Factor-based distribution** – B scaled by PT accessibility (OVGK class) and distance to work | Similar efficiency, lower burden for poorly served groups; watch the incentive to live far away |
| 5 | **Sensitivity** – λ, p_buy, B, linear vs log, PT factor, f-heuristic | Robustness of conclusions |
| 6 | **Analysis & report** | Mode shares, car trips/km, vehicle-hours of delay (peak), utility loss by income / OVGK / region, credit balance distribution, credits purchased |

**Known limitations:** no departure-time choice (pricing shifts mode only, not timing); activity chains are fixed (activity loss is not modelled, so use utility loss instead); no PT crowding; freight/commercial traffic is outside mode choice (state that it is exempt).

## Brief for the coding agent – current step (Step 2: credit cost model)

> We study a mobility-credit scheme for Fribourg in the eqasim Swiss model (`switzerland/.../ch_cmdp`).
> **Task:** add a configurable credit cost per trip and feed it into mode choice, without changing baseline behaviour when disabled.
>
> 1. New config group `mobilityCredits` (enabled=false by default): per-mode constants, time-of-day bins (start/end hour → factor), car distance function (`linear|log`), `lambda_CHF_per_credit`, `buyPrice_CHF_per_credit`, `dailyBudget`.
> 2. A `CreditCostModel` (car, pt, bike, walk, car_passenger) returning credits per trip from mode, departure time and distance. Look at the existing cost models in `mode_choice/costs/` and `SwissCostParameters`.
> 3. Add `λ · credits` to the monetary cost term of the Swiss utility estimators (e.g. `SwissCarDetailedUtilityEstimator.estimateMonetaryCostUtility`), or use the core policy hook `core/.../policies/utility/UtilityPenalty`. Pick whichever is cleaner and explain why.
> 4. Budget: track credits used across the agent's plan (tour/plan level; check which DMC model type the Swiss config uses). Credits beyond the budget cost `buyPrice` instead of λ. Constraints are registered in `SwissModeChoiceModule`.
> 5. Output: per-trip credits and per-person balance/purchases (CSV per iteration).
> 6. Unit tests for the cost model and budget logic; disabled config ⇒ identical results to baseline.
>
> Do not touch calibration code or existing cost parameters. Keep everything switchable via config.
