package flow_diag;

import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.TimeFrame;
import com.motivewave.platform.sdk.order_mgmt.Order;
import com.motivewave.platform.sdk.order_mgmt.OrderContext;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Throwaway diagnostic STRATEGY (see FLOW/experiments convention).
 *
 * HARD RULE (see ../../CLAUDE.md): this class contains NO call to
 * buy/sell/closeAtMarket/submitOrders/createMarketOrder/createLimitOrder/
 * createStopOrder or any other order-placement method, anywhere. Every
 * inherited OrderContext-taking lifecycle hook is explicitly overridden
 * below with a log-only, read-only body -- per the 2026-09-11 incident
 * (findings.md), omitting a hook is not the same as it being safe, since
 * MotiveWave's base class default for onEnterNow places a real market
 * order.
 *
 * StudyHeader flags (settled 2026-09-14 after live back-and-forth -- see
 * findings.md for the full trail, including two wrong turns): `autoEntry
 * =true` permits this class's OWN code to auto-enter; it does not itself
 * place an order, and this class contains no buy/sell call for it to
 * trigger. `autoEntry=false` combined with `manualEntry=false` was tried
 * first and turned out to be an unsupported combination -- MotiveWave's
 * Activate flow demanded a Long/Short choice with no actual chooser to
 * satisfy it (a dead end). Setting `supportsPositionType=true` was tried
 * next and did NOT fix it either (still hit the same dead end even with
 * Position Type explicitly set to "Long" in the Add dialog) -- confirming
 * the real cause was the autoEntry/manualEntry combination, not Position
 * Type, so `supportsPositionType` reverts to `false` (its default) here.
 * `manualEntry` stays `false` (no manual entry buttons offered).
 * `supportsEnterOnActivate=false` / `supportsCloseOnDeactivate=false`
 * stay explicitly set -- both default to `true` if left unset, and "Enter
 * On Activate" places a position as a platform-level side effect of
 * clicking Activate, independent of this file's Java code (though moot
 * here in practice, since this class's own onActivate never checks
 * `getSettings().isEnterOnActivate()` or calls buy/sell either way).
 *
 * Purpose (FLOW_V2 Q-02 stage (a), docs/dynamic/decisions.md): can an
 * OrderContext captured in onActivate be retained and used later, and from
 * a thread other than the one that supplied it? Retains the reference,
 * then a background scheduled thread calls two READ-ONLY methods
 * (getPosition, getCashBalance) on it every 5 seconds and logs the result
 * plus System.identityHashCode(ctx) alongside the calling thread's name.
 * The same identity hash is also logged from onActivate/onBarClose/
 * onDeactivate for comparison -- a stable hash across all of these is
 * strong evidence of a single long-lived handle rather than a fresh
 * wrapper per callback.
 */
@StudyHeader(
    namespace = "com.flow.diag",
    id = "FLOW_DIAG_CTX_RETENTION",
    name = "FLOW_DIAG_CTX_RETENTION",
    label = "FLOW Diag - OrderContext Retention Probe (Q-02a, read-only)",
    desc = "Retains OrderContext, polls it read-only from a timer thread; places no orders",
    menu = "FLOW",
    overlay = true,
    strategy = true,
    autoEntry = true,
    manualEntry = false,
    supportsPositionType = false,
    supportsEnterOnActivate = false,
    supportsCloseOnDeactivate = false,
    requiresBarUpdates = true,
    supportsBarUpdates = true,
    barUpdatesByDefault = true)
public class ContextRetentionProbe extends Study {
  private static final String LOG_FILE =
      "C:/yadvendra/trading/motivewave/experiments/logs/context_retention_probe.log";
  private static final long POLL_SECONDS = 5;

  private volatile PrintWriter log;
  private volatile OrderContext retained;
  private volatile ScheduledExecutorService poller;

  @Override
  public void initialize(Defaults defaults) {
    createSD();
    createRD();
    try {
      log = new PrintWriter(new FileWriter(LOG_FILE, true));
      log.println("# session start " + System.currentTimeMillis());
      log.flush();
    } catch (IOException e) {
      error("FLOW_DIAG: failed to open context retention log: " + e.getMessage());
    }
  }

  @Override
  protected void calculate(int index, DataContext ctx) {
    // Nothing computed per-bar; the retained OrderContext drives the log.
  }

  @Override
  public void onActivate(OrderContext ctx) {
    retained = ctx;
    logLine("ACTIVATE thread=" + Thread.currentThread().getName()
        + " identityHash=" + System.identityHashCode(ctx)
        + " pos=" + safePosition(ctx) + " cash=" + safeCash(ctx));

    poller = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "flow-diag-ctx-poller");
      t.setDaemon(true);
      return t;
    });
    poller.scheduleAtFixedRate(this::pollRetainedContext, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
  }

  private void pollRetainedContext() {
    OrderContext ctx = retained;
    if (ctx == null) return;
    try {
      logLine("POLL thread=" + Thread.currentThread().getName()
          + " identityHash=" + System.identityHashCode(ctx)
          + " pos=" + ctx.getPosition() + " cash=" + ctx.getCashBalance());
    } catch (Exception e) {
      logLine("POLL_EXCEPTION thread=" + Thread.currentThread().getName()
          + " identityHash=" + System.identityHashCode(ctx)
          + " exception=" + e);
    }
  }

  @Override
  public void onBarClose(OrderContext ctx) {
    logLine("BAR_CLOSE thread=" + Thread.currentThread().getName()
        + " identityHash=" + System.identityHashCode(ctx)
        + " pos=" + safePosition(ctx) + " cash=" + safeCash(ctx));
  }

  @Override
  public void onDeactivate(OrderContext ctx) {
    logLine("DEACTIVATE thread=" + Thread.currentThread().getName()
        + " identityHash=" + System.identityHashCode(ctx));
    if (poller != null) {
      poller.shutdownNow();
      poller = null;
    }
    retained = null;
  }

  private String safePosition(OrderContext ctx) {
    try { return String.valueOf(ctx.getPosition()); } catch (Exception e) { return "ERR:" + e; }
  }

  private String safeCash(OrderContext ctx) {
    try { return String.valueOf(ctx.getCashBalance()); } catch (Exception e) { return "ERR:" + e; }
  }

  // Everything below is an explicit no-op override of an inherited
  // OrderContext-taking hook, per the hard rule above. None of these call
  // any order-placement method -- confirmed by inspection, not just omission.

  @Override
  public void onEnterNow(OrderContext ctx) {
    logLine("ENTER_NOW_IGNORED (no-op override; base class default was the cause of the 2026-09-11 incident)");
  }

  @Override
  public void onSignal(OrderContext ctx, Object signal) {
    logLine("SIGNAL_IGNORED " + signal);
  }

  @Override
  public void onSessionStarted(OrderContext ctx, TimeFrame session) {
    logLine("SESSION_STARTED " + session);
  }

  @Override
  public void onSessionEnded(OrderContext ctx, TimeFrame session) {
    logLine("SESSION_ENDED " + session);
  }

  @Override
  public void onReset(OrderContext ctx) {
    logLine("RESET");
  }

  @Override
  public void onPositionClosed(OrderContext ctx) {
    logLine("POSITION_CLOSED");
  }

  @Override
  public void onOrderFilled(OrderContext ctx, Order order) {
    logLine("ORDER_FILLED " + order);
  }

  @Override
  public void onOrderCancelled(OrderContext ctx, Order order) {
    logLine("ORDER_CANCELLED " + order);
  }

  @Override
  public void onOrderRejected(OrderContext ctx, Order order) {
    logLine("ORDER_REJECTED " + order);
  }

  @Override
  public void onOrderModified(OrderContext ctx, Order order) {
    logLine("ORDER_MODIFIED " + order);
  }

  private void logLine(String s) {
    info("FLOW_DIAG_CTX: " + s);
    if (log != null) {
      log.println(System.currentTimeMillis() + " " + s);
      log.flush();
    }
  }
}
