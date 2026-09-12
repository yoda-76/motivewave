# CLAUDE.md

Workflow rules for this project, following the same methodology as FLOW
(`C:\yadvendra\trading\FLOW`). Nothing gets trusted just because it's
documented — MotiveWave's own docs, the SDK Javadoc, none of it — everything
gets checked against what the live Rithmic/CQG feed actually delivers before
any architecture decision depends on it.

## Secrets — `.env`

Same rule as FLOW: **never read `.env`**, not to check a value, not even if
asked indirectly. **Only the user edits `.env`** — if a new credential or
config value is needed, add a placeholder to `.examples.env` describing
what's needed and tell the user; never write `.env` directly. The one
exception on record: 2026-09-12, the user explicitly asked for `.env` to be
created and populated with the account details already surfaced during that
session's live testing — a one-time, explicitly-requested act, not a
standing practice. `.env` is gitignored; `.examples.env` is tracked.

## Hard rule — ask before touching anything under the user's Windows profile

Writing or modifying any file **outside this project's own directory tree**
— concretely, anything under `C:\Users\MSI\...` such as
`MotiveWave Extensions\`, MotiveWave's own AppData config/workspace files,
etc. — requires **explicit permission from the user each time**, even after
a general "yes" was given once. This project's own tree
(`C:\yadvendra\trading\motivewave\...`) doesn't need re-asking for routine
edits; deploying compiled code into the live MotiveWave installation does,
every time, because it's modifying a running trading application outside
this project's sandbox.

## Hard fact — this MotiveWave connection trades real capital

**The connected Rithmic account is REAL CAPITAL, confirmed directly by the
user on 2026-09-12 — not a demo account, despite a "TEST" suffix in its
account ID.** (Account ID/broker specifics deliberately omitted from this
public-facing file; ask the user if the exact identifier is ever needed.) Never infer an account is safe/
simulated from its name, balance appearance, or anything else short of the
user explicitly confirming it. Before any future strategy activation:
confirm out loud which account is selected and whether it's real, every
time — do not rely on a past confirmation carrying forward, since the
selected account in the GUI can change between sessions. Setting up and
switching to MotiveWave's actual built-in Simulated Account feature (see
`docs/static/pages/user-guide_account-management_simulated-account.md`)
before any further strategy activation is strongly recommended and has not
been done yet as of 2026-09-12.

## Hard rule — never place an order

**Never submit, modify, or cancel a real order — on any account, including
the Simulated account — as a side effect of other work.** This applies to
every layer: no `Strategy` gets auto-activated, no experiment auto-arms
trading, no code path calls `OrderContext.buy/sell/submitOrders/...` and then
gets run without the user watching it happen.

The only exception is when the user **explicitly** asks for an order to be
placed right now. Even then: state exactly what will be submitted (account,
instrument, side, quantity, order type) and get one last explicit
confirmation immediately before submitting — a prior general approval (e.g.
approving this file, or approving a plan that mentions trading later) does
not count as that confirmation.

Diagnostic/experiment code in `experiments/` must stay read-only with
respect to orders (logging `Tick`/`DOM`/study data is fine) unless a given
experiment's entire purpose — stated explicitly by the user at the time — is
to test order submission.

**Confirmed 2026-09-11 (see findings.md): not overriding an order method is
not enough.** `com.motivewave.platform.sdk.study.Study.onEnterNow(OrderContext)`
is concrete, not abstract — MotiveWave's base class default implementation
places a market order. The **"Enter Now" button on any activated Strategy
Control Box calls it regardless of `autoEntry`/`manualEntry` StudyHeader
flags and regardless of whether the subclass overrides it.** A real BUY
order for 1 GCZ6 was placed and filled this way on a strategy whose code
contained no order calls at all. Consequence for this rule: from now on,
every diagnostic `Study`/`Strategy` deployed here must **explicitly
override `onEnterNow` (and any other inherited order-capable hook) with a
no-op**, not just omit it — omitting a hook inherits whatever default
MotiveWave's base class ships, and that default is not guaranteed safe.
Never assume "I didn't write a buy() call" is sufficient; verify what the
base class does by default before trusting a hook is inert.

## Directory structure and what each part is for

```
motivewave/
├── CLAUDE.md          this file
├── docs/
│   ├── static/         downloaded MotiveWave docs, Javadoc, PDF, sample project. Never edit.
│   └── dynamic/         decisions.md, findings.md. Our working record.
├── experiments/         throwaway diagnostic studies, deployed into MotiveWave to test empirically
├── app/                 the actual system — stays empty until told otherwise
└── tools/                portable JDK 26 (gitignored, not project content)
```

- **`docs/static/`** — everything pulled from docs.motivewave.com, motivewave.com/sdk,
  and the bundled sample Eclipse project (`sample_project/`). **Never edit files in
  here.** If something in here turns out to be wrong or incomplete (the `llms-full.txt`
  export was found stale/truncated on 2026-09-11 — missing the entire SDK Programming
  Guide — individual `.md` pages were fetched separately into `docs/static/pages/`
  instead), that's a `findings.md` entry, not an edit to the static file.
- **`docs/dynamic/`** — `decisions.md` and `findings.md`, evolving continuously.
  Same distinction as FLOW: decisions are closed answers, findings are the
  empirical evidence behind them.
- **`experiments/`** — small, disposable Java `Study` classes compiled against
  `mwave_sdk.jar` and deployed straight into MotiveWave's extension loader
  (`%USERPROFILE%\MotiveWave Extensions\dev`, via `redeploy.sh`) to answer one
  empirical question each — same role as FLOW's `experiments/`. Nothing here
  places real orders; diagnostic studies only.
- **`app/`** — where the actual system gets built once the scoping/experimentation
  phase confirms what's real. **Stays empty until told otherwise.**

## Build toolchain

MotiveWave 7.x bundles its own JRE at **Java 26** (`jre/release` ->
`JAVA_VERSION="26"`), and `mwave_sdk.jar` is itself compiled to that class file
version (70.0) — a JDK 21 `javac` cannot even read the jar (`bad class file...
wrong version 70.0, should be 65.0`). No JDK, Eclipse, or Ant was present on
this machine, so a portable Temurin JDK 26 zip was fetched into `tools/`
(no installer, no PATH changes, no admin rights). Always compile with
`tools/jdk-26.0.2.1+1/bin/javac.exe`, not whatever `java`/`javac` might later
end up on PATH.

Deployment mechanism (confirmed from the sample project's `build/build.xml`,
not guessed): compiled `.class` files go under
`%USERPROFILE%\MotiveWave Extensions\dev\`, mirroring their package structure,
and touching `%USERPROFILE%\MotiveWave Extensions\.last_updated` tells
MotiveWave to reload. `experiments/redeploy.sh` does both steps.

## Core habit (same as FLOW)

Everything gets checked against a real, running MotiveWave instance connected
to the actual Rithmic/CQG feed before it's trusted — not assumed from the
SDK docs. `docs/dynamic/findings.md` is the running log of what that turns up.
