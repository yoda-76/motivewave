# Decisions

Closed architectural decisions, one per question, each with a date and a
link to the evidence behind it. Same convention as FLOW's decisions.md —
current best answer, not final; expect these to get reopened as
experimentation continues.

- **D-01** (2026-09-11) — This project follows FLOW's exact methodology:
  `docs/static` (never-edited reference), `docs/dynamic` (decisions +
  findings, this pair), `experiments/` (throwaway scripts to resolve
  ambiguity empirically), `app/` (real system, stays empty until told
  otherwise). Rationale: it already worked for FLOW; no reason to invent a
  different process for a second project with the same builder.

- **D-02** (2026-09-11) — Target market/feed is **US futures via
  Rithmic/CQG** (already configured in this MotiveWave install), not NSE.
  This is a separate market from FLOW's Breeze/Dhan NSE stack — no data or
  code sharing assumed between the two projects, only the working
  methodology is shared. See [[findings.md]] for what this feed choice
  implies for tick/MBO data richness (still being verified).

- **D-03** (2026-09-11) — Forward testing (custom Strategy against live data
  + simulated/demo account) is today's actual build target. Strategy
  backtesting/optimization is explicitly kept in future scope — MotiveWave's
  own docs independently recommend the same order (forward test before
  trusting a backtest), see [[findings.md]].

- **D-04** (2026-09-11) — No GEX or options-flow tooling for now (may
  revisit later). Scope for the order-flow entry model is: volume profile
  and its derivatives, footprint/volume imprint, big trades, liquidity map
  (DOM), built from raw `Tick`/`DOM` SDK data rather than the built-in
  visual studies, so the actual imbalance/absorption logic can match what's
  already used discretionarily.

  **Amended 2026-09-15**: "built from raw Tick/DOM SDK data rather than
  the built-in visual studies" still holds exactly as stated — no reading
  of another study's rendered output, ever (confirmed structurally
  impossible anyway, see the 2026-09-14/15 `[LIVE]` entries below). What
  changed, via `../FLOW_V2/docs/dynamic/sdk-capability-findings.md`'s
  live-verified audit: volume profile, footprint, delta, and big trades
  don't need their *accumulation logic* hand-built from that raw data
  either — the SDK ships reusable engine classes
  (`sdk.profile.VolumeProfile`, `sdk.common.AggregateFilter`) that take
  the exact same raw `Tick` stream this decision already committed to,
  and were confirmed live to produce accurate output. Liquidity map
  remains fully custom (no equivalent SDK engine exists for MBO-depth
  heatmaps); market structure/swings gets a documented primitive
  (`DataSeries.calcSwingPoints`) to build interpretation on top of, not a
  finished feature.

- **D-05** (2026-09-11) — Build with a portable Temurin JDK (matching
  MotiveWave's bundled runtime major version, currently 26) unzipped into
  `tools/`, not a system-wide JDK install. No admin rights needed, no PATH
  pollution, and it stays pinned to whatever version the runtime actually
  needs even if that drifts on a MotiveWave update.

- **D-06** (2026-09-12) — **No further strategy activation until a genuine
  MotiveWave Simulated Account is set up and confirmed selected.** Forced by
  the 2026-09-11 incident (see [[findings.md]]): account
  the connected Rithmic account trades real capital despite a "TEST" suffix
  in its account ID,
  and MotiveWave's base `Study.onEnterNow()` places a real market order by
  default even from a study whose own code never calls an order method.
  Every diagnostic study/strategy from here on must explicitly no-op every
  inherited order-capable hook (not just omit it), and every activation
  must have the selected account re-confirmed with the user in the moment
  — see the hard rule/hard fact in `CLAUDE.md`. **Satisfied 2026-09-12** —
  user enabled "Sim Trade Only" in Settings (Configure > Settings > General
  > Simulated Account), confirmed visually. This disables order placement
  on every account except Simulated at the platform level, independent of
  any code we write. Whether the account itself was genuinely real capital
  or routed through Rithmic's simulator remains unresolved
  (the order that caused the incident logged `RithmicService::getTradeRoute()
  ... using default: simulator`, which is in tension with the user's earlier
  "real capital at risk" answer) — moot now that Sim Trade Only is on, but
  worth resolving before this project ever reconnects a real account.
