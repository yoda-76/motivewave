# motivewave

**This repo is an experimentation lab, not a trading system.** Everything
in it so far is throwaway diagnostic code used to empirically verify what
MotiveWave's SDK actually delivers (tick/DOM data quality, strategy
lifecycle behavior, order-safety gotchas) before any real design work
starts. Nothing here places trades unattended, nothing here is a strategy
meant to be run, and `app/` — where the real system would eventually live —
is still empty. Treat everything under `experiments/` as scratch work whose
value is the finding it produced (see `docs/dynamic/findings.md`), not the
code itself.

Turning discretionary order-flow trading judgment (market structure, volume
profile, footprint, DOM, big trades) into an automated forward-testing
system built on MotiveWave's SDK. Same methodology as
[FLOW](../FLOW) — separate project, separate market (US futures via
Rithmic/CQG, not NSE) — see `CLAUDE.md` and `docs/dynamic/decisions.md`.

## Where things stand (2026-09-15)

SDK reuse-vs-build audit substantially live-verified this session — see
`../FLOW_V2/docs/dynamic/sdk-capability-findings.md` for the audit itself
and `docs/dynamic/findings.md`'s 2026-09-14/15 `[LIVE]` entries for the
evidence. Headline: FLOW_V2 will **not** build a custom volume profile,
footprint, or delta implementation from scratch — the SDK's own
`sdk.profile.VolumeProfile` engine, fed by our own ticks, was confirmed
live to closely match the chart's built-in Volume Profile study.
`AggregateFilter` (big trades) and `DataSeries.calcSwingPoints` (market
structure) also compiled and ran clean against our real jar, with real
repeat-emission and swing-revision behavior captured live. Along the way:
several real Javadoc-vs-jar mismatches found (`TPOProfile`'s constructor,
`DOMSnapshot`'s price arrays are `float[]` not `double[]`), a working
pattern for drawing custom figures on the chart (after three wrong turns
— see findings.md), and two safety gaps in earlier diagnostic strategies
fixed (`autoEntry`/`manualEntry` dead end, zombie background threads
after a study is removed from the chart).

Still open: the liquidity heatmap (is the built-in `Order Heatmap`/`DOM
Power` good enough, or is a custom MBO-fed one still needed) and the raw
journal's DOM retention policy (full per-order detail measured at
~1,000x the volume of top-of-book-only).

## Where things stood (2026-09-11)

Scoping/experimentation day. Feasibility read from docs + Javadoc: the SDK
exposes real tick-level data (price, bid/ask, aggressor side, order IDs) and
live DOM (with Market-by-Order detail if the broker supports it) — enough to
build order-flow constructs from raw data rather than the built-in visual
studies. Strategy automation (order placement, position/PnL tracking, full
order lifecycle) is fully supported. Backtesting exists but is flagged
(by MotiveWave's own docs) as optimistic at bar granularity — kept in future
scope; forward testing against live data + a simulated/demo account is
today's actual target.

Toolchain stood up from scratch this session: portable JDK 26 (matching
MotiveWave's bundled runtime), the official sample project, a first
diagnostic `Study` (`experiments/src/flow_diag/TickDomLogger.java`) compiled
and deployed. Not yet confirmed against live market data — see
`docs/dynamic/findings.md` for what's confirmed vs still open.

## Repo layout

See `CLAUDE.md` for the full breakdown. Short version:
`docs/static/` (never-edited reference material), `docs/dynamic/`
(`decisions.md`, `findings.md` — the actual working record), `experiments/`
(throwaway diagnostic studies + `redeploy.sh`), `app/` (real system, empty
for now), `tools/` (portable JDK, not project content).
