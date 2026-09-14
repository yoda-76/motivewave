package flow_diag;

import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.DataSeries;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.Tick;
import com.motivewave.platform.sdk.common.TimeFrame;
import com.motivewave.platform.sdk.order_mgmt.Order;
import com.motivewave.platform.sdk.order_mgmt.OrderContext;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Throwaway diagnostic STRATEGY (see FLOW/experiments convention).
 *
 * HARD RULE (see ../../CLAUDE.md): this class contains NO call to
 * buy/sell/closeAtMarket/submitOrders/createMarketOrder/createLimitOrder/
 * createStopOrder or any other order-placement method, anywhere. Activating
 * it in MotiveWave cannot place an order under any circumstance, regardless
 * of what the user clicks in the Strategy Control Box -- autoEntry and
 * manualEntry are both declared false below so MotiveWave doesn't even offer
 * an "enter on activate" option for it.
 *
 * 2026-09-11 incident (see docs/dynamic/findings.md): the base Study class's
 * onEnterNow(OrderContext) is concrete, not abstract, and its default
 * implementation places a real market order when the Strategy Control Box's
 * "Enter Now" button is pressed -- regardless of autoEntry/manualEntry flags,
 * and regardless of whether a subclass overrides it. Omitting an override is
 * NOT the same as it being safe. Every OrderContext-taking lifecycle hook is
 * therefore explicitly overridden below with a log-only body, so there is no
 * inherited default left unaccounted for.
 *
 * 2026-09-14 fix: `autoEntry` had been left `true` (contradicting this
 * comment, which already claimed it was `false` -- a stale/incorrect
 * comment, now corrected in code) and `supportsEnterOnActivate` /
 * `supportsCloseOnDeactivate` had been left unset, which defaults BOTH to
 * `true` in StudyHeader. "Enter On Activate" places a position as a
 * platform-level side effect of clicking Activate, independent of this
 * file's Java code -- discovered live on a sibling probe
 * (`ContextRetentionProbe.java`) when activation prompted for a Long/Short
 * direction choice before allowing activation. All three flags are now
 * explicitly `false`, same rationale as the `onEnterNow` no-op below: an
 * unset default is not a safe default.
 *
 * Purpose: prove the Strategy lifecycle (onActivate/onBarClose/onDeactivate)
 * fires correctly and that OrderContext account state (position, cash,
 * unrealized PnL) is readable, while combining bar-level OHLC (market
 * structure) with a live aggressor-delta signal built from the same Tick
 * data already proven by TickDomLogger -- i.e. the actual shape of a real
 * entry model, computed live, logged only.
 */
@StudyHeader(
    namespace = "com.flow.diag",
    id = "FLOW_DIAG_STRATEGY_SKELETON",
    name = "FLOW_DIAG_STRATEGY_SKELETON",
    label = "FLOW Diag - Strategy Skeleton (read-only, places no orders)",
    desc = "Logs bar OHLC + live aggressor delta + account state; places no orders",
    menu = "FLOW",
    overlay = true,
    strategy = true,
    autoEntry = false,
    manualEntry = false,
    supportsPositionType = false,
    supportsEnterOnActivate = false,
    supportsCloseOnDeactivate = false,
    requiresBarUpdates = true,
    supportsBarUpdates = true,
    barUpdatesByDefault = true)
public class FlowStrategySkeleton extends Study {
  private static final String LOG_FILE =
      "C:/yadvendra/trading/motivewave/experiments/logs/strategy_skeleton.log";

  private volatile PrintWriter log;
  private final AtomicLong buyVol = new AtomicLong(0);
  private final AtomicLong sellVol = new AtomicLong(0);

  @Override
  public void initialize(Defaults defaults) {
    createSD();
    createRD();
    try {
      log = new PrintWriter(new FileWriter(LOG_FILE, true));
      log.println("# session start " + System.currentTimeMillis());
      log.flush();
    } catch (IOException e) {
      error("FLOW_DIAG: failed to open strategy log: " + e.getMessage());
    }
  }

  @Override
  protected void calculate(int index, DataContext ctx) {
    // Bar OHLC is read directly in onBarClose via OrderContext.getDataContext().
    // Nothing to compute per-bar here; ticks drive the delta accumulator instead.
  }

  @Override
  public void onTick(DataContext ctx, Tick tick) {
    if (tick.isAskTick()) buyVol.addAndGet(tick.getVolume());
    else sellVol.addAndGet(tick.getVolume());
  }

  @Override
  public void onActivate(OrderContext ctx) {
    logLine("ACTIVATE pos=" + ctx.getPosition() + " cash=" + ctx.getCashBalance());
  }

  @Override
  public void onBarClose(OrderContext ctx) {
    long bv = buyVol.getAndSet(0);
    long sv = sellVol.getAndSet(0);
    DataSeries series = ctx.getDataContext().getDataSeries();
    int idx = series.size() - 1;
    logLine(String.format(
        "BAR_CLOSE O=%.4f H=%.4f L=%.4f C=%.4f buyVol=%d sellVol=%d delta=%d pos=%d unrealizedPnL=%.2f cash=%.2f",
        series.getOpen(idx), series.getHigh(idx), series.getLow(idx), series.getClose(idx),
        bv, sv, (bv - sv), ctx.getPosition(), ctx.getUnrealizedPnL(), ctx.getCashBalance()));
  }

  @Override
  public void onDeactivate(OrderContext ctx) {
    logLine("DEACTIVATE pos=" + ctx.getPosition());
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
    logLine("POSITION_CLOSED pos=" + ctx.getPosition());
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
    info("FLOW_DIAG_STRAT: " + s);
    if (log != null) {
      log.println(System.currentTimeMillis() + " " + s);
      log.flush();
    }
  }
}
