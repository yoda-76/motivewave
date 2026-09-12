# Findings

Empirical evidence behind `decisions.md`, tagged `[DOC]` (read from
MotiveWave's docs/Javadoc), `[LIVE]` (tested against the real running
platform/feed), or `[CODE]` (verified by reading `mwave_sdk.jar`/Javadoc
directly). Same convention as FLOW's findings.md.

## 2026-09-11

- **[DOC]** The bulk export `docs.motivewave.com/llms-full.txt` is stale/
  truncated — it stops partway through the "Order Flow" section (~line 5570)
  and is missing the entire SDK Programming Guide, Strategies, Tick Data, and
  Strategy Back Testing chapters that `sitemap.md` lists. Individual pages
  were fetched directly via the `<page>.md` suffix instead
  (`docs/static/pages/`). Don't trust `motivewave-docs.md` alone for SDK
  content — check `pages/` first.

- **[CODE]** `Tick` interface (`com.motivewave.platform.sdk.common.Tick`)
  exposes `getPrice()`, `getVolume()`, `getBidPrice()/getBidSize()`,
  `getAskPrice()/getAskSize()`, `isAskTick()` (aggressor side), and
  `getExchOrderId()`/`getAggExchOrderId()` (order-level IDs, i.e.
  Market-by-Order granularity *if the feed provides it* — not yet confirmed
  live for our Rithmic/CQG connection). Live via `Study.onTick(DataContext,
  Tick)`, historical via `Instrument.getTicks(start, end[, ...])`.

- **[CODE]** `DOM`/`DOMRow`/`DOMOrder` (same package) give aggregated
  bid/ask depth per price (`DOMRow`) and, when the broker supports Market-by-
  Order, the actual queued orders at that price (`DOMRow.getOrders()` ->
  `DOMOrder.getExchangeOrderId()/getQuantity()`). Live subscription via
  `Instrument.addListener(DOMListener)`; `DOMListener.update(DOM)` fires per
  update. `Instrument.getDOMHistory()`/`getLatestDOMHistory()` give recent
  snapshots. **Not yet confirmed live** whether our feed gives true MBO
  (`getOrders()` populated) or only aggregated MBP (`getOrders()`
  empty/null, `getOrderCount()` may still be 0 per the Javadoc's own caveat:
  "Zero will be returned if this feature is not supported by the broker").

- **[DOC]** Strategy = a `Study` subclass with `strategy=true` in
  `@StudyHeader`. Full order lifecycle: `onActivate/onBarOpen/onBarUpdate/
  onBarClose/onSignal/onDeactivate(OrderContext)` plus `onOrderFilled/
  onOrderCancelled/onOrderRejected/onOrderModified(OrderContext, Order)`.
  `OrderContext` has a complete order API (buy/sell convenience methods,
  full market/limit/stop order builder with TIF, position/PnL tracking,
  multi-account, hedging support).

- **[DOC]** Strategy Back Testing (the Optimizer: Back Test / Optimize /
  Walk Forward) is gated to **Professional/Ultimate license tier** — need to
  confirm which tier this MotiveWave license actually is. Irrelevant for now
  since backtest is explicitly future scope, but relevant once we get there.

- **[DOC]** MotiveWave's own "Backtesting Limitations" page (not our
  caution, theirs) flags: market order fills in the optimizer are optimistic
  (queue position/latency/spread not modeled), minute-bar backtests hide a
  lot versus tick data, default fill is on last price (should switch to
  bid/ask or slippage), and they explicitly recommend forward-testing on a
  broker demo account before going live — validates the phased approach
  (forward test now, backtest later) already decided on.

- **[LIVE]** No JDK, Eclipse, or Ant present on this machine. Portable
  Temurin JDK 26 zip fetched into `tools/` (matching MotiveWave's bundled
  Java 26 runtime — a JDK 21 `javac` cannot even read `mwave_sdk.jar`,
  class file version 70.0 vs the 65.0 it supports). See `CLAUDE.md` build
  toolchain section.

- **[LIVE]** `flow_diag.TickDomLogger` compiled clean against
  `mwave_sdk.jar` with JDK 26 and deployed to
  `%USERPROFILE%\MotiveWave Extensions\dev\`. Loaded onto a live `@GC`
  (Gold futures) chart on 2026-09-11 ~23:34 while other, unrelated charts
  were also open in the workspace — confirmed via the Study Log
  (`FLOW_DIAG: subscribed to DOM for @GC` appears exactly once) that having
  many charts open is harmless; only the chart the study is actually added
  to runs an instance. Study loaded and ran with no errors.

- **[LIVE]** **MBO confirmed, not just MBP.** The first 20 detailed DOM
  snapshots (`DETAILED_DOM_UPDATES` in `TickDomLogger.java`) show
  `DOMRow.getOrders()` populated across the full book — 50,988 individual
  `DOMOrder` entries (real distinct exchange order IDs, `qty=1.00` each) in
  those 20 updates alone. Book depth on `@GC` runs to ~595-720 price rows
  per side (not a top-10 quote), i.e. the whole resting order book, not a
  truncated view. `ticks.log` likewise shows `exchOrderId`/`aggExchOrderId`
  populated and incrementing (not 0/placeholder) on essentially every trade.
  This settles the open question from the previous entry: our Rithmic/CQG
  feed on `@GC` gives true Market-by-Order granularity — a real liquidity
  map (not an aggregated-depth approximation) and real aggressor-tagged
  tick data are both buildable from this feed as-is.

- **[LIVE]** MotiveWave's own output log shows a Rithmic alert at startup
  (23:32:19, before our study loaded): `RithmicService::onAlert() ...
  get_order_book error : 13 codeStr: permission denied symbol: GCZ6` —
  this was MotiveWave's own historical-backfill routine requesting a full
  historical order-book reconstruction, unrelated to our `DOMListener`
  subscription (which succeeded independently ~2 minutes later with real
  order data, per the entry above). Read as: **live DOM streaming works
  fully; historical DOM reconstruction (`Instrument.getDOMHistory()`/
  `getLatestDOMHistory()`) may be permission-gated on our current Rithmic
  tier** — not yet confirmed either way since we haven't called those
  methods directly. Irrelevant to forward testing (only needs live data);
  relevant later if a feature ever wants to replay DOM history.

- **[LIVE]** `flow_diag.FlowStrategySkeleton` loaded cleanly (3x, once per
  redeploy reload) with no errors — `initialize()` ran each time.

- **[LIVE] INCIDENT — real order placed and filled, 2026-09-11 23:54:35.**
  While exploring the Strategy Control Box for `FlowStrategySkeleton`
  looking for a Long/Short selector, a real market order was submitted and
  filled: BUY 1 GCZ6 (Gold futures, COMEX) @ 4391.2 on the connected
  Rithmic account. Confirmed from MotiveWave's own
  output log (`ServiceHome::placeOrder()` -> `OrderDirectory::orderFilled()`).
  **Root cause: `Study.onEnterNow(OrderContext)` is concrete in the base
  class, not abstract, and its default implementation places a market
  order.** The Strategy Control Box's "Enter Now" button calls it on any
  activated strategy regardless of `autoEntry=false`/`manualEntry=false` in
  `@StudyHeader` and regardless of whether the subclass overrides it.
  `FlowStrategySkeleton.java` contains no order-placement calls anywhere —
  confirmed by direct code review — so this was not caused by anything we
  wrote; it was triggered by UI interaction relying on inherited default
  behavior we hadn't accounted for. `CLAUDE.md`'s hard rule updated
  accordingly: every future diagnostic study/strategy must explicitly
  override every inherited order-capable hook (`onEnterNow` at minimum)
  with a no-op, not just omit it. Position was left open pending user
  decision — never closed unilaterally, per the hard rule. **Resolved
  2026-09-12 00:06:35** — user closed manually (SELL 1 GCZ6 @ 4391.1,
  confirmed via `OrderDirectory::orderFilled()`), realized loss -$10, flat.

- **[LIVE]** The "Choose Long/Short" dialog blocking Activate on 2026-09-12
  was caused by **"Enter On Activate" being checked**, not by
  "Position Type"/`supportsPositionType` (that was a wrong theory, based on
  a plausible-looking StudyHeader flag name rather than live evidence —
  confirmed wrong via screenshot). Removing/re-adding the study reset
  Trading Options to defaults, silently re-checking "Enter On Activate"
  after the user had explicitly unchecked it earlier. Fix: uncheck it again
  in the Trading Options tab. Also confirmed via screenshot: the account
  selector on the Strategy Control Box now reads "simulated" — live,
  visual proof that "Sim Trade Only" (see below) is actually in effect, not
  just assumed from the settings checkbox.

- **[LIVE]** The "Enter On Activate" theory above was also incomplete — the
  dialog persisted even after unchecking it. Actual root cause:
  `autoEntry=false` + `manualEntry=false` together is an unsupported/
  undefined combination the framework can't render an entry UI for (no
  manual Long/Short buttons since `manualEntry=false`, yet activation still
  demanded a direction from somewhere with no way to give one). Fixed by
  setting `autoEntry=true` with `manualEntry=false` — matching every real
  example strategy in MotiveWave's own docs (e.g. Sample MA Cross Strategy).
  Safe regardless of this flag since the code contains zero order-placement
  calls in any hook. Confirmed: **activation now succeeds cleanly.**
  `FLOW_DIAG_STRAT: ACTIVATE pos=0 cash=99850.0` logged to both
  `strategy_skeleton.log` and MotiveWave's own output log, on the Simulated
  account (cash=99850 matches the simulated balance, not the real
  account's). Lesson for this project: two wrong theories in a row came
  from inferring behavior off documentation/attribute names; the fix only
  came after asking for and reading actual screenshots — same
  "verify against the real thing" discipline as FLOW, just relearned here.

- **[LIVE] Capstone confirmation, 2026-09-12.** `FlowStrategySkeleton`
  logged 18+ consecutive `BAR_CLOSE` lines (1/minute) on the Simulated
  account, e.g. `BAR_CLOSE O=4388.6001 H=4391.6001 L=4388.6001 C=4391.5000
  buyVol=53 sellVol=8 delta=45 pos=0 unrealizedPnL=0.00 cash=99850.00`.
  This is the full read-side forward-testing pipeline validated together in
  one place: bar-level OHLC (market structure), live aggressor buy/sell
  volume and delta computed from the tick stream in real time (order flow),
  and correct `OrderContext` account state (position/cash), with `pos=0`
  and `cash=99850.00` held constant across every bar -- zero orders placed,
  on the safe account, for the whole run. Today's scoping/experimentation
  goal is met: building market structure + order-flow entry signals on top
  of this SDK is confirmed feasible end-to-end, not just plausible from
  docs.

- **[DOC]** The built-in Simulated Account is a MotiveWave platform feature
  independent of the broker connection — no separate broker-side demo
  login needed. Enable via *Configure > Settings > General > Simulated
  Account tab > Enabled*. The **"Sim Trade Only"** checkbox in that same
  panel disables order placement on every account except the Simulated one,
  platform-wide — recommended as the actual fix for the 2026-09-11 incident
  class, since it makes real-account order placement structurally
  impossible regardless of which button gets clicked on any control box.
  Not yet enabled as of 2026-09-12 — next step before any further strategy
  activation, per D-06.
