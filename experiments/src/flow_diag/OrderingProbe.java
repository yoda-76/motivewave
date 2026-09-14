package flow_diag;

import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.Tick;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Throwaway diagnostic study (see FLOW/experiments convention). Not a
 * strategy -- no `strategy=true`, no OrderContext-taking hook exists on this
 * class to override, places no orders under any circumstance.
 *
 * Purpose (FLOW_V2 Q-01, docs/dynamic/decisions.md): do a bar's ticks
 * reliably arrive (onTick) before onBarClose fires for it, or can a bar
 * close with its last ticks still in flight? Logs a monotonic sequence
 * number on every onTick and every onBarClose so the two streams can be
 * interleaved and checked by wall-clock time after the fact -- specifically,
 * whether any tick whose own timestamp falls inside a bar's interval is
 * logged (by receipt order) after that bar's BAR_CLOSE line.
 */
@StudyHeader(
    namespace = "com.flow.diag",
    id = "FLOW_DIAG_ORDERING",
    name = "FLOW_DIAG_ORDERING",
    label = "FLOW Diag - Bar/Tick Ordering Probe (Q-01, read-only)",
    desc = "Logs onTick/onBarClose receipt order to check tick-before-close ordering",
    menu = "FLOW",
    overlay = true,
    requiresBarUpdates = true,
    supportsBarUpdates = true,
    barUpdatesByDefault = true)
public class OrderingProbe extends Study {
  private static final String LOG_FILE =
      "C:/yadvendra/trading/motivewave/experiments/logs/ordering_probe.log";

  private volatile PrintWriter log;
  private final AtomicLong seq = new AtomicLong(0);

  @Override
  public void initialize(Defaults defaults) {
    createSD();
    createRD();
    try {
      log = new PrintWriter(new FileWriter(LOG_FILE, true));
      log.println("# session start " + System.currentTimeMillis());
      log.flush();
    } catch (IOException e) {
      error("FLOW_DIAG: failed to open ordering probe log: " + e.getMessage());
    }
  }

  @Override
  protected void calculate(int index, DataContext ctx) {
    // Nothing computed per-bar; onTick and onBarClose drive the log below.
  }

  @Override
  public void onTick(DataContext ctx, Tick tick) {
    long s = seq.incrementAndGet();
    logLine(String.format(
        "TICK seq=%d tickTime=%d recvTime=%d price=%.6f vol=%d",
        s, tick.getTime(), System.currentTimeMillis(), tick.getPrice(), tick.getVolume()));
  }

  @Override
  public void onBarClose(DataContext ctx) {
    logLine("BAR_CLOSE seqAtClose=" + seq.get() + " recvTime=" + System.currentTimeMillis());
  }

  private void logLine(String s) {
    if (log != null) {
      log.println(s);
      log.flush();
    }
  }
}
