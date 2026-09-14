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

  **Note 2026-09-14:** `docs/dynamic/decisions.md` D-06 records this as
  **satisfied 2026-09-12** (Sim Trade Only enabled, confirmed visually via
  the Strategy Control Box account selector reading "simulated"). This
  paragraph is left as originally written rather than silently edited: it
  was true when captured, D-06 is the current status. Re-confirm the
  checkbox and account selector live before trusting either, since neither
  a doc entry nor a past decision substitutes for the in-the-moment check
  the hard rule in `CLAUDE.md` requires.

## 2026-09-14

- **[DOC]** No public SDK API lets one `Study` obtain a handle to another
  study instance on the same chart — `Study`, `DataContext`, and
  `DataSeries` (per their Javadoc method lists) expose no `getStudies()`,
  `getStudy(id)`, or equivalent. The mechanism that **does** exist is
  **Export Values**: a study declares
  `desc.exportValue(new ValueDescriptor(key, label, dependentInputs))` in
  `initialize()`, storing computed values on the shared `DataSeries` under
  that key; MotiveWave's Add-Study dialog can then offer an already-placed
  study's exported values as a "Study"-type input to a newly-added study.
  This is GUI-wired per study-to-study connection, not a dynamic runtime
  lookup by arbitrary code (source:
  `pages/user-guide_sdk-programming-guide_overlay-example.md`,
  `pages/user-guide_sdk-programming-guide_study-plot-example.md`,
  `pages/user-guide_sdk-programming-guide_fundamental-classes.md`).

- **[DOC]** `com.motivewave.platform.sdk.profile.SummaryProfile` /
  `VolumeProfile` (the SDK's own profile data classes, not `Study`
  subclasses) expose exactly the getters a volume-profile feature needs:
  `getPOC()`, `getPOCMidpoint()`, `getVAHigh()`, `getVALow()`,
  `getValueArea()`, `getRows()` (→ `VolumeRow.isPOC()/getVolume()/
  getDelta()/isAskImbalance()/isBidImbalance()`), `getHVNs()`/`getLVNs()`.
  These are plain data objects usable by our own `CustomVolumeProfile`
  (D-26) directly. Separately, `com.motivewave.platform.sdk.profile.
  VolumeProfileStudy` — the SDK base class MotiveWave's own built-in Volume
  Profile study is presumably built on — stores its computed profile in a
  **protected** nested class (`VPSummary`), with no public getter visible
  in its Javadoc method list. So even if the built-in study is literally
  this class, its instance state isn't reachable by field/method access
  from outside.

  **Net for Q-08:** whether `BuiltInVolumeProfile` (D-26) can read the
  shipped built-in study's POC/VAH/VAL automatically depends entirely on
  whether that built-in study happens to declare an Export Value for them
  — undocumented either way, since built-in studies' own `initialize()`
  source isn't in the SDK docs. Not resolvable from documentation alone;
  needs a live check: place the built-in Volume Profile study on a chart,
  then open the Add-Study dialog for a new study and check whether its
  "Study" input options include a Volume Profile POC/VAH/VAL entry. No
  code experiment needed for this half — it's a GUI check. If it isn't
  exported, D-26's stated fallback (journal our own values, compare to the
  chart by hand) applies as already decided.

- **[LIVE]** `StudyHeader.supportsEnterOnActivate` and
  `supportsCloseOnDeactivate` **both default to `true`** when left unset —
  confirmed live, not just from the Javadoc default text. Activating
  `ContextRetentionProbe.java` (deployed with neither flag set) prompted
  for a Long/Short direction choice *before allowing activation at all*,
  even though the class contains zero order-placement code anywhere and
  has `autoEntry=false`/`manualEntry=false`. "Enter On Activate" is a
  **platform-level trading option** — per
  `pages/user-guide_strategy-back-testing_strategies.md`, "If enabled the
  strategy will create an initial position when you activate the
  strategy" — that submits an entry order as a side effect of clicking
  Activate, entirely independent of the strategy class's own Java code.
  This is the same class of gap as the 2026-09-11 `onEnterNow` incident:
  an unset default is not a safe default, this time at the StudyHeader
  capability-flag level rather than the inherited-method level. Both
  `FlowStrategySkeleton.java` (pre-existing, previously activated in the
  2026-09-11/12 sessions without this flag set — no position was opened
  then, per that session's logged `pos=0` throughout, but the exposure was
  present) and `ContextRetentionProbe.java` (new) are fixed to explicitly
  set both flags `false`. **Any future diagnostic `Strategy` class must
  set `supportsEnterOnActivate=false, supportsCloseOnDeactivate=false`
  explicitly** — add this to the checklist alongside the no-op
  `OrderContext` hook overrides.

- **[LIVE]** **`autoEntry=false` combined with `manualEntry=false` is an
  unsupported StudyHeader combination — it produces a dead-end "Please
  Choose Long or Short" dialog with no actual chooser, blocking activation
  entirely.** Full trail, so this doesn't need re-discovering:
  1. Both new diagnostic strategies were first written with `autoEntry=
     false, manualEntry=false` (reasoning: neither entry mode should be
     needed for a pure logging probe). Activating either produced a
     modal titled "Choose Long/Short" containing only the text "Please
     Choose Long or Short." and an OK button — no actual Long/Short
     buttons anywhere in it, and no other UI element to set a direction.
     Confirmed via screenshot, not assumption.
  2. First hypothesis: `supportsPositionType=false` (already set) was
     hiding the only UI to choose Position Type, and MotiveWave's
     activation check demands one unconditionally. Fix tried: set
     `supportsPositionType=true`. **This did NOT fix it** — a Position
     Type dropdown did appear in the Add-Study dialog (defaulting to
     "Long"), and it could be explicitly set to "Long" before adding,
     but Activate still produced the identical dead-end dialog. Confirmed
     via screenshot.
  3. Root cause, found by comparing against `FlowStrategySkeleton.java`'s
     history: that file had `autoEntry=true` in its **original,
     already-proven-safe** version (a prior full session logged `pos=0`/
     flat cash throughout, `ACTIVATE` through `DEACTIVATE`) — the file's
     own comment claimed `autoEntry` was `false`, which was wrong, and an
     earlier fix pass in this same session "corrected" the code to match
     the wrong comment, silently introducing this regression. Reverting
     to `autoEntry=true` on both strategies (keeping `manualEntry=false`,
     `supportsPositionType=false`, i.e. its default) fixed it — both
     activated with **no dialog at all**.
  4. **Standing rule for any future diagnostic `Strategy`, regardless of
     whether it contains any actual entry logic: use `autoEntry=true,
     manualEntry=false, supportsPositionType=false` (or leave
     `supportsPositionType` unset — same thing).** `autoEntry=true` only
     *permits* this class's own code to auto-enter; it does not place an
     order by itself, and a class with no `buy`/`sell` call anywhere has
     nothing for that permission to trigger — confirmed by two independent
     full live sessions (`FlowStrategySkeleton`, `ContextRetentionProbe`)
     with position/cash flat throughout. Do not "fix" `autoEntry=false` on
     a diagnostic strategy without testing activation live first — the
     comment-vs-code mismatch that caused this regression looked like an
     obvious, safe cleanup and wasn't.

- **[LIVE]** Q-02 stage (a) answered — **a retained `OrderContext` is
  valid and safely callable (read-only) from a thread other than the one
  that supplied it, with a stable identity across calls.**
  `ContextRetentionProbe.java`, once activated cleanly (see the entry
  above), logged `System.identityHashCode(ctx)` == `1581364256`
  identically across `onActivate` (platform thread `TaskQueue - 16`),
  `onBarClose` (platform thread `Quote Consumer 1 ...`), and every 5s
  `getPosition()`/`getCashBalance()` poll from this class's own background
  thread (`flow-diag-ctx-poller`) — no exceptions, `pos=0`/`cash=99850.0`
  unchanged throughout. This is a single long-lived handle, not a fresh
  wrapper per callback, at least for read-only calls. **Does not by itself
  prove write calls (`buy`/`sell`) are safe off-thread** — only read
  methods were exercised, per the Q-02(a) zero-risk design in
  `FLOW_V2/docs/dynamic/decisions.md`. Q-02 stage (b) (a real order
  placement test) remains optional per its original "only if (a) is
  inconclusive" framing — (a) was not inconclusive — but D-17's flush
  point still can't assume *write* safety off-thread from this evidence
  alone; the safe default (flush at the top of the next platform callback)
  should stand unless stage (b) is deliberately run later.

- **[LIVE]** Q-08 answered — **the built-in Volume Profile study does not
  expose POC/VAH/VAL through either mechanism checked, on a live chart.**
  With the built-in Volume Profile study already on a `@GC` chart: (1) an
  EMA(20) added alongside it has an Input dropdown offering only
  Open/Close/Midpoint/High/Low/Typical Price/Weighted Price — nothing
  Volume-Profile-derived, confirming no Export Value from Volume Profile
  reaches another study's Input selector. (2) Right-clicking empty chart
  space offers "Add Alert at `<price>`" — a generic price-level alert.
  Right-clicking directly **on the Volume Profile plot itself** gives a
  completely different, plot-specific context menu (Show Fill, Show Value
  Area, Show Bid/Ask, Display Side, Format, Duplicate, Lock Figure, Hide,
  Properties, etc.) with **no Create/Add Alert entry anywhere in it** —
  so the Study Alert mechanism (`pages/…motivewave-docs.md` line ~3642,
  "Relative Comparison") isn't reachable for it either, at least not via
  this menu. Net: `BuiltInVolumeProfile` (D-26) is **not** automatic.
  D-26's stated fallback applies — journal `CustomVolumeProfile`'s own
  POC/VAH/VAL on a cadence and compare against the chart by hand.

- **[LIVE]** Q-01 answered — **ticks belonging to a bar reliably arrive
  before `onBarClose` fires for it; no case of a bar closing with its own
  ticks still in flight, on this feed.** `OrderingProbe.java` ran on a
  live `@GC` 1-min chart and logged a monotonic sequence number on every
  `onTick` and every `onBarClose`. Across 2,738 ticks and 12 bar closes:
  for every bar close, every tick received afterward (by sequence number)
  had an exchange timestamp (`tick.getTime()`) *after* that close's
  receipt time — zero violations. In other words, nothing arrived late
  carrying a timestamp that should have belonged to the bar that already
  closed. Less consequential than it would have been pre-D-10 (the
  sequencer records actual arrival order regardless of what this answer
  turned out to be), but confirms a bar-close-triggered aggregate can be
  trusted at the moment it fires, on this connection.

  **Secondary observation, unrelated to Q-01 but worth keeping for D-23's
  feed-latency measurement:** `recvTime` (`System.currentTimeMillis()` at
  receipt) was consistently *before* `tickTime` (`tick.getTime()`) by an
  average of ~668ms (range 30–710ms) across the whole run — i.e. the
  gap this codebase would compute as "feed latency" came out negative.
  Two explanations not yet distinguished: local-machine clock running
  behind the exchange/feed's clock, or `tick.getTime()` not being a pure
  exchange-side timestamp (e.g. including some processing/queueing time
  on MotiveWave's or the broker's side that pushes it later than our
  local receipt). Not investigated further here since it isn't part of
  Q-01; flag it before trusting any absolute feed-latency number D-23
  computes from these two clocks.

- **[LIVE]** Q-03 answered, with a number much larger than the top-of-book
  measurement alone suggested. Two separate live captures on `@GC`:

  **Capture 1 — top-of-book only, ~54 minutes** (`TickDomLogger`, existing
  20-update detail cap already exhausted before this window started, so
  it's pure top-of-book for the whole window): 10,793 ticks and 154,774
  DOM updates in 3,247s, scaled to a full hour → **~11,966 ticks/hr,
  ~171,600 DOM updates/hr, ~28.0 MB/hr raw, ~1.35 MB/hr gzip -9 (~20.7x
  ratio)**.

  **Capture 2 — full per-order detail, self-bounded to 6,000 updates**
  (`DomDetailCapture.java`, new): 6,000 DOM updates in 183.18s (32.76
  updates/sec in this window) produced **16,481,360 `DOMOrder` entries**
  (avg **2,747.6 orders/update**, close to and superseding the original
  2026-09-11 finding's 20-update sample of ~2,549/update — this is a
  300x-larger sample) and 10,673,929 bid/ask row lines (73.7% non-empty).
  Raw size **1,102,379,298 bytes (1.10 GB)** → 183,730 bytes/update.
  Gzip -6: **136,040,021 bytes (136.0 MB)** → only **~8.10x** compression
  (far worse than top-of-book's ~20.7x, because individual exchange order
  IDs are high-entropy and don't repeat/compress well).

  **Extrapolated using Capture 1's hourly update rate (171,600/hr, the
  more statistically robust longer sample) applied to Capture 2's
  per-update density:** full per-order DOM detail would run **~31.5
  GB/hour raw, ~3.89 GB/hour gzipped**. For D-07's 2–3 day raw retention
  window, that's **~1.5–2.3 TB raw, ~187–280 GB gzipped** — roughly
  **1,100x (raw) to 2,900x (gzip)** larger than top-of-book-only.

  **This measurement used naive full-snapshot-per-update logging** — every
  DOM update re-logs the *entire* book, including every order still
  resting unchanged from the previous update (`update(DOM dom)` hands back
  the full current book each time, not a delta). Order books change
  incrementally between consecutive updates far more often than they churn
  completely, so a **delta/incremental encoding** (log only orders
  added/removed/modified since the last update, not the whole book) would
  very plausibly cut this substantially — genuinely unmeasured here,
  flagged as the natural follow-up if full per-order retention is ever
  pursued, rather than assumed.

  **Consequence for D-07/D-15, not decided here:** compressed JSONL is
  clearly sufficient for ticks + top-of-book DOM (the ~97 MB/3-days
  figure). It is **not** viable at anywhere near that retention window for
  full per-order snapshot detail without either a much shorter retention
  window, a delta encoding, or accepting that the raw journal's DOM tier
  stays top-of-book-only (D-12's derived-views principle already applies
  to what *strategies* see; this is now also a live question for what the
  *raw journal* itself should retain). D-22's forward-only feature class
  (order resting time, liquidity-pull frequency) is the one part of the
  design that plausibly needs order-ID-level tracking at all — worth
  checking whether that can be satisfied by a narrower/shorter capture
  than a general 2-3 day raw retention policy before committing to either
  extreme.

- **[LIVE]** E-6 (sdk-capability-findings.md) resolved by jar inspection
  alone, no code needed: `jar tf mwave_sdk.jar` shows **no
  `com.motivewave.platform.common` package at all**, in either that
  namespace or under `sdk.common.Enums`. `VAMethod` does not exist
  anywhere in our jar. Confirmed further via `javap` on `TPOProfile`: its
  real bytecode signature references `getVolumeValueArea(double,
  com.motivewave.platform.common.Enums$VAMethod)` and the equivalent
  `getTimeValueArea` overload — so the type is referenced by the SDK's own
  compiled classes but isn't distributed with the jar, making those
  overloads permanently uncallable from our code. Only `getValueArea(double)`
  (no method parameter) is usable, on `VolumeProfile` or `TPOProfile`.

- **[LIVE]** E-1 (SDK signature sweep) — mostly clean, one real mismatch
  found. `SdkSignatureSweep.java` compiles against our actual
  `mwave_sdk.jar` for every signature in §2 of `sdk-capability-findings.md`
  **except** `TPOProfile`'s constructor: Javadoc says
  `(long, long, Instrument, int)`, `javap` on our actual jar shows
  `(long, long, long, Instrument, int, int, int, boolean)` — confirms T-6
  concretely for at least one class. Not pursued further since TPO is out
  of scope. Everything else (`VolumeProfile`, `SummaryProfile`,
  `VolumeRow`, `AggregateFilter`, `DataSeries.calcSwingPoints`,
  `SwingPoint`, `Instrument.getDOMHistory()`/`getLatestDOMHistory()`,
  `DOMSnapshot`) compiled clean. **Not yet run against a live instrument**
  (deployed, not yet attached to a chart) — compiling confirms the
  signatures exist, but not that they behave sanely at runtime; that's
  still pending.

  **Second T-6 mismatch found while writing `SdkCapabilityProbe.java`
  (the E-2/3/4/5/7/8/9 combined probe):** `DOMSnapshot.getBidPrices()`/
  `getAskPrices()` return `float[]`, not `double[]` as the field/method
  naming convention elsewhere in the SDK would suggest. Fixed at the call
  site; noted here so the next person doesn't re-discover it.

- **[LIVE]** E-10 (studies source bundle) — found a better source than the
  Google Drive link the forum thread names: the official **`MotiveWave/
  motivewave-studies`** GitHub repo (`https://github.com/MotiveWave/
  motivewave-studies`, GPL v3, 339 `.java` files, last commit 2025-08-23),
  reached via `awesome-motivewave-studies`'s pointer to the real org repo.
  Git-clonable, versioned, far more usable than an unversioned Drive
  download. Findings from inventorying it, more consequential than
  expected:

  1. **VWAP.java exists under `ma/`, is genuinely tick-weighted (not bar-
     approximated).** `VWAPCalculator implements TickOperation`, fed via
     `instr.forEachTick(...)`, accumulates `totalPrice += tick.getPrice()
     * tick.getVolumeAsFloat()` per tick — this resolves D-22's open VWAP
     caveat in our favor: the reference implementation is true tick VWAP,
     not typical-price-per-bar. Its standard-deviation bands cite a
     specific published formula (Sierra Chart's site) worth reusing
     alongside the mean calculation.
  2. **But it cannot be compiled as-is against our public SDK jar.** It
     casts to `com.motivewave.platform.ui.draw.component.study.
     DataSeriesImpl` — confirmed via `jar tf` to not exist anywhere in
     `mwave_sdk.jar` (that whole `platform.ui.*` namespace is absent).
     "Adapt their code" (the document's own conclusion) means **port the
     algorithm**, not drop in the file — the internal-class dependency
     has to be designed around.
  3. **No footprint, big-trades, heatmap, Order Heatmap, or DOM Power
     source anywhere in the 339 files** (checked by filename and by
     full-text grep for "footprint"/"imbalance"/"big.?trade"/"heatmap").
     Confirms these are not open-sourced — matches the document's own
     2023-forum-thread caveat about Big Trades being missing, generalized
     to the whole order-flow-analysis family. No reference implementation
     is coming from this source for E-4 or E-5's imbalance/big-trade
     logic; those experiments are on their own.
  4. **Bigger finding: `com.motivewave.platform.sdk.profile.*`
     (`VolumeProfile`, `VolumeRow`, `SummaryProfile`, `TPOProfile`) has
     ZERO usage anywhere in this entire 339-file repo** — confirmed by
     `grep -rl` for the import across the whole tree, zero hits. The
     actual official `DailyVolumeProfile.java` study does **not** use
     `sdk.profile.VolumeProfile` at all — it computes its own bar-based
     (not tick-fed) volume-at-price binning with its own `VolumeBar`/
     `VolumeLegend` `Figure` classes, no `onTick` override. This weakens
     the document's §2.1/§2.2/§2.4 confidence (all tagged `[DOC]`,
     compiles per E-1, but now confirmed **unused by MotiveWave's own
     published studies**) — there is no reference implementation showing
     a safe session-scoped usage pattern, which is exactly what E-3 was
     going to need to lean on for the T-1 memory question. E-2/E-3/E-4 now
     carry more weight: they're not confirming a documented-and-used
     pattern, they're the *first* real-world exercise of this class either
     way.
  5. **`AggregateFilter` also has zero usage anywhere in the repo** — same
     gap, same consequence for E-5.
  6. **`calcSwingPoints`/`SwingPoint` DOES have real usage** — 4 studies
     (`SwingPoints.java`, `AutoTrendLine.java`, `PriceLabels.java`,
     `williams/Fractal.java`). `SwingPoints.java` calls the simple
     single-`int`-strength overload (`series.calcSwingPoints(strength)`,
     `strength` from a user setting) — matches what E-1 already compiled
     and gives real precedent that this is the intended, supported call
     shape. This is the one class in the audit that comes out of E-10
     *more* confident, not less.

- **[LIVE]** Two more mechanics gotchas found while debugging why E-2's
  chart lines weren't appearing:

  1. **`addFigure()` does not trigger a repaint by itself** —
     `notifyRedraw()` must be called explicitly afterward (its Javadoc:
     "Sends a notification that the study needs to be redrawn"). Wrapping
     the clear+add sequence in `beginFigureUpdate()`/`endFigureUpdate()`
     is the idiomatic batching pattern for multiple figure changes.
  2. **Removing a study from the chart does not stop its background
     threads or unregister its listeners on its own.** `destroy()` is the
     documented hook for releasing resources ("called when the study is
     being disposed") but is easy to forget on a diagnostic study built
     around a `ScheduledExecutorService` heartbeat, same pattern as
     `ContextRetentionProbe.java`'s poller. Without overriding it, a
     "removed" instance keeps running as a **zombie** — its
     `DOMListener`/tick callbacks may stop (unclear which; not fully
     isolated), but the heartbeat thread keeps firing on its own schedule
     forever, logging stale/frozen data (a `VolumeProfile` snapshot that
     stopped receiving ticks reports the same `rows`/`totalVol` on every
     heartbeat) interleaved with whatever instance replaced it in the
     same shared log file. Found by noticing two heartbeat lines six
     minutes apart with byte-identical `VolumeProfile` readings.
     `SdkCapabilityProbe.java` now overrides `destroy()` to shut down the
     heartbeat, remove the DOM listener, and close the log writer; every
     log line is also now tagged with `System.identityHashCode(this)` so
     a live vs. zombie instance can be told apart on sight going forward.
     **Any future diagnostic study with a background thread or a
     listener registration should do the same** — add this to the
     checklist alongside the `OrderContext` hook and `autoEntry`/
     `manualEntry` items already there. `ContextRetentionProbe.java`
     likely has the same latent gap (its poller is stopped in
     `onDeactivate`, not `destroy`) — not fixed here since that probe's
     work (Q-02a) is already done, but worth fixing before reusing it.
