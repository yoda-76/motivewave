package flow_diag;

import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.DOM;
import com.motivewave.platform.sdk.common.DOMListener;
import com.motivewave.platform.sdk.common.DOMOrder;
import com.motivewave.platform.sdk.common.DOMRow;
import com.motivewave.platform.sdk.common.Instrument;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Throwaway diagnostic study (see FLOW/experiments convention). Not a
 * strategy, places no orders -- same shape as TickDomLogger, but exists as
 * its own class rather than editing that one, so this stays an isolated,
 * self-contained probe rather than a temporary mutation of an already-
 * measured baseline.
 *
 * Purpose (FLOW_V2 Q-03, docs/dynamic/decisions.md): TickDomLogger caps
 * full per-order (DOMOrder) detail to the first 20 DOM updates after each
 * restart, then falls back to top-of-book only -- by design, to bound its
 * own log size. That means a full hour of TickDomLogger data (already
 * captured) says nothing about steady-state DOMOrder-level volume, which
 * is what D-11's bit-identical-replay requirement actually needs. This
 * class logs FULL per-order detail on every DOM update, deliberately
 * SELF-BOUNDED to `DETAILED_DOM_UPDATES` updates (not unlimited) so a
 * short capture window can't runaway into an unbounded file even if left
 * running -- after the cap, it stops logging DOM updates at all (rather
 * than silently falling back to a different format that would be easy to
 * mistake for more detailed-window data).
 */
@StudyHeader(
    namespace = "com.flow.diag",
    id = "FLOW_DIAG_DOM_DETAIL_CAPTURE",
    name = "FLOW_DIAG_DOM_DETAIL_CAPTURE",
    label = "FLOW Diag - DOM Full-Detail Capture (Q-03, read-only, self-bounded)",
    desc = "Logs full per-order DOM detail for a bounded number of updates, then stops",
    menu = "FLOW",
    overlay = true,
    requiresBarUpdates = true,
    supportsBarUpdates = true,
    barUpdatesByDefault = true)
public class DomDetailCapture extends Study implements DOMListener {
  private static final String DOM_LOG =
      "C:/yadvendra/trading/motivewave/experiments/logs/dom_detail_capture.log";
  // Self-bounding cap: at the ~43 updates/sec measured for @GC in the Q-03
  // top-of-book run, 6000 updates is roughly 2-3 minutes -- enough for a
  // density measurement, small enough to not risk a runaway file size.
  private static final int DETAILED_DOM_UPDATES = 6000;

  private volatile PrintWriter domLog;
  private final AtomicBoolean domSubscribed = new AtomicBoolean(false);
  private final AtomicInteger domCount = new AtomicInteger(0);

  @Override
  public void initialize(Defaults defaults) {
    createSD();
    createRD();
    try {
      domLog = new PrintWriter(new FileWriter(DOM_LOG, true));
      domLog.println("# session start " + System.currentTimeMillis()
          + " cap=" + DETAILED_DOM_UPDATES);
      domLog.flush();
    } catch (IOException e) {
      error("FLOW_DIAG: failed to open dom detail capture log: " + e.getMessage());
    }
  }

  @Override
  protected void calculate(int index, DataContext ctx) {
    if (domSubscribed.compareAndSet(false, true)) {
      Instrument instr = ctx.getInstrument();
      instr.addListener(this);
      info("FLOW_DIAG: subscribed to DOM for " + instr.getSymbol());
    }
  }

  @Override
  public void onTick(DataContext ctx, com.motivewave.platform.sdk.common.Tick tick) {
    // Not needed for this probe -- tick volume is already measured by TickDomLogger.
  }

  @Override
  public void update(DOM dom) {
    int n = domCount.incrementAndGet();
    if (n > DETAILED_DOM_UPDATES) {
      if (n == DETAILED_DOM_UPDATES + 1 && domLog != null) {
        domLog.println("# capture cap reached at update " + DETAILED_DOM_UPDATES
            + " -- no further updates logged, stopping here by design");
        domLog.flush();
      }
      return;
    }
    if (domLog == null) return;
    List<DOMRow> bids = dom.getBidRows();
    List<DOMRow> asks = dom.getAskRows();
    domLog.printf("--- DOM update #%d @ %d bidRows=%d askRows=%d ---%n",
        n, System.currentTimeMillis(), bids.size(), asks.size());
    logSide(bids, "BID");
    logSide(asks, "ASK");
    domLog.flush();
    if (n == 1) info("FLOW_DIAG: first detailed DOM update captured");
    if (n == DETAILED_DOM_UPDATES) info("FLOW_DIAG: detail capture cap reached, stopping");
  }

  private void logSide(List<DOMRow> rows, String label) {
    for (DOMRow row : rows) {
      domLog.printf("%s price=%.6f size=%.2f orderCount=%d%n",
          label, row.getPrice(), row.getSize(), row.getOrderCount());
      List<DOMOrder> orders = row.getOrders();
      if (orders != null) {
        for (DOMOrder o : orders) {
          domLog.printf("    order id=%s qty=%.2f%n", o.getExchangeOrderId(), o.getQuantity());
        }
      }
    }
  }
}
