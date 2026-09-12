package flow_diag;

import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.DOM;
import com.motivewave.platform.sdk.common.DOMListener;
import com.motivewave.platform.sdk.common.DOMOrder;
import com.motivewave.platform.sdk.common.DOMRow;
import com.motivewave.platform.sdk.common.Instrument;
import com.motivewave.platform.sdk.common.Tick;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Throwaway diagnostic study (see FLOW/experiments convention). Not a strategy,
 * places no orders. Purpose: log raw Tick and DOM payloads for whatever
 * instrument it's dropped on, so we can empirically confirm what our actual
 * Rithmic/CQG connection delivers (true tick+MBO vs aggregated MBP vs nothing)
 * before designing any order-flow feature logic against it.
 */
@StudyHeader(
    namespace = "com.flow.diag",
    id = "FLOW_DIAG_TICKDOM",
    name = "FLOW_DIAG_TICKDOM",
    label = "FLOW Diag - Tick/DOM Logger",
    desc = "Logs raw tick and DOM data to a file for feed verification",
    menu = "FLOW",
    overlay = true,
    requiresBarUpdates = true,
    supportsBarUpdates = true,
    barUpdatesByDefault = true)
public class TickDomLogger extends Study implements DOMListener {
  private static final String TICK_LOG = "C:/yadvendra/trading/motivewave/experiments/logs/ticks.log";
  private static final String DOM_LOG = "C:/yadvendra/trading/motivewave/experiments/logs/dom.log";
  private static final int DETAILED_DOM_UPDATES = 20;

  private volatile PrintWriter tickLog;
  private volatile PrintWriter domLog;
  private final AtomicBoolean domSubscribed = new AtomicBoolean(false);
  private final AtomicInteger domCount = new AtomicInteger(0);
  private final AtomicInteger tickCount = new AtomicInteger(0);

  @Override
  public void initialize(Defaults defaults) {
    createSD();
    createRD();
    try {
      tickLog = new PrintWriter(new FileWriter(TICK_LOG, true));
      domLog = new PrintWriter(new FileWriter(DOM_LOG, true));
      tickLog.println("# session start " + System.currentTimeMillis());
      domLog.println("# session start " + System.currentTimeMillis());
      tickLog.flush();
      domLog.flush();
    } catch (IOException e) {
      error("FLOW_DIAG: failed to open log files: " + e.getMessage());
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
  public void onTick(DataContext ctx, Tick tick) {
    int n = tickCount.incrementAndGet();
    if (tickLog == null) return;
    tickLog.printf(
        "%d,price=%.6f,vol=%d,bid=%.6f,bidSz=%d,ask=%.6f,askSz=%d,isAskTick=%b,exchOrderId=%d,aggExchOrderId=%d%n",
        tick.getTime(), tick.getPrice(), tick.getVolume(),
        tick.getBidPrice(), tick.getBidSize(),
        tick.getAskPrice(), tick.getAskSize(),
        tick.isAskTick(), tick.getExchOrderId(), tick.getAggExchOrderId());
    tickLog.flush();
    if (n == 1) info("FLOW_DIAG: first tick received");
  }

  @Override
  public void update(DOM dom) {
    int n = domCount.incrementAndGet();
    if (domLog == null) return;
    boolean detailed = n <= DETAILED_DOM_UPDATES;
    List<DOMRow> bids = dom.getBidRows();
    List<DOMRow> asks = dom.getAskRows();
    domLog.printf("--- DOM update #%d @ %d bidsRows=%d askRows=%d ---%n",
        n, System.currentTimeMillis(), bids.size(), asks.size());
    logSide(bids, "BID", detailed);
    logSide(asks, "ASK", detailed);
    domLog.flush();
    if (n == 1) info("FLOW_DIAG: first DOM update received");
  }

  private void logSide(List<DOMRow> rows, String label, boolean detailed) {
    for (int i = 0; i < rows.size(); i++) {
      DOMRow row = rows.get(i);
      if (!detailed && i > 0) break; // top-of-book only once we've dumped enough detailed snapshots
      domLog.printf("%s price=%.6f size=%.2f orderCount=%d%n",
          label, row.getPrice(), row.getSize(), row.getOrderCount());
      if (detailed) {
        List<DOMOrder> orders = row.getOrders();
        if (orders != null) {
          for (DOMOrder o : orders) {
            domLog.printf("    order id=%s qty=%.2f%n", o.getExchangeOrderId(), o.getQuantity());
          }
        }
      }
    }
  }
}
