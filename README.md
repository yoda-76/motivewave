# motivewave

Turning discretionary order-flow trading judgment (market structure, volume
profile, footprint, DOM, big trades) into an automated forward-testing
system built on MotiveWave's SDK. Same methodology as
[FLOW](../FLOW) — separate project, separate market (US futures via
Rithmic/CQG, not NSE) — see `CLAUDE.md` and `docs/dynamic/decisions.md`.

## Where things stand (2026-09-11)

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
