package flow_diag;

import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.DataSeries;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;

/**
 * Throwaway diagnostic study (see FLOW/experiments convention). Not a
 * strategy -- no `strategy=true`, no OrderContext-taking hook exists on
 * this class to override, places no orders under any circumstance.
 *
 * Purpose (FLOW_V2, raised 2026-09-20 while scoping the plain-OHLC
 * market-structure backtest, docs/dynamic/todo.md): how much historical
 * OHLC does MotiveWave's own DataSeries actually make available for
 * @GC, at whatever bar interval the chart is set to? This determines
 * whether the backtest can be built entirely on MotiveWave's own data
 * (the user's stated preference, "if the data is provided i want to
 * keep everything in motivewave only") or needs to fall back to an
 * external source (TradingView, per the user's own fallback plan).
 *
 * Logs once on the first calculate() call (series size, earliest/latest
 * bar time, computed span) and again on every later bar close ONLY if
 * the series' own size has grown since the last log -- MotiveWave
 * sometimes progressively loads more history in the background after a
 * chart first opens, so a single one-time read right at attach could
 * understate what's actually reachable. Read-only throughout; never
 * touches an order or a setting.
 *
 * Usage: add to a chart for @GC set to the SAME bar interval FLOW_V2
 * actually uses (1-minute, D-28) -- attaching to a different interval
 * answers a different, less relevant question. If the logged size looks
 * capped, also try scrolling the chart back manually / increasing
 * MotiveWave's own "bars to load" chart setting before concluding the
 * cap is real, since size() reflects what's currently loaded into
 * memory, not necessarily the vendor's absolute available depth.
 */
@StudyHeader(
    namespace = "com.flow.diag",
    id = "FLOW_DIAG_HIST_DEPTH",
    name = "FLOW_DIAG_HIST_DEPTH",
    label = "FLOW Diag - Historical Depth Probe (read-only)",
    desc = "Logs how many historical bars DataSeries actually has available",
    menu = "FLOW",
    overlay = true)
public class HistoricalDepthProbe extends Study {
  private static final String LOG_FILE =
      "C:/yadvendra/trading/motivewave/experiments/logs/historical_depth_probe.log";

  private volatile PrintWriter log;
  private volatile boolean loggedFirst = false;
  private volatile int lastLoggedSize = -1;

  @Override
  public void initialize(Defaults defaults) {
    createSD();
    createRD();
    try {
      log = new PrintWriter(new FileWriter(LOG_FILE, true));
      log.println("# session start " + System.currentTimeMillis());
      log.flush();
    } catch (IOException e) {
      error("FLOW_DIAG: failed to open historical depth probe log: " + e.getMessage());
    }
  }

  @Override
  protected void calculate(int index, DataContext ctx) {
    // Bugfix (found live, 2026-09-20): a one-time "log only the first
    // calculate() call" guard misses history growth from scrolling back
    // or a "bars to load" setting change -- with the market closed, no
    // live bar ever closes to trigger the onBarClose recheck below
    // either, so a manual scroll-back silently went undetected. Instead:
    // re-check every time calculate() is asked for what is CURRENTLY the
    // series' last index -- MotiveWave re-invokes calculate() across the
    // (possibly now longer) series after a history-extending action,
    // and this naturally fires again once it reaches the new end,
    // regardless of what triggered the extension. Dedup on size actually
    // changing keeps this from spamming during the normal one-calculate-
    // per-bar-per-index pass.
    DataSeries series = ctx.getDataSeries();
    if (series == null) return;
    if (index != series.size() - 1) return;
    if (!loggedFirst) {
      loggedFirst = true;
      logDepth(ctx, "FIRST_CALCULATE");
    } else if (series.size() != lastLoggedSize) {
      logDepth(ctx, "SIZE_CHANGED");
    }
  }

  @Override
  public void onBarClose(DataContext ctx) {
    DataSeries series = ctx.getDataSeries();
    if (series == null) return;
    if (series.size() != lastLoggedSize) {
      logDepth(ctx, "BAR_CLOSE_SIZE_CHANGED");
    }
  }

  private void logDepth(DataContext ctx, String tag) {
    DataSeries series = ctx.getDataSeries();
    if (series == null) {
      logLine(tag + " series=null");
      return;
    }
    int size = series.size();
    lastLoggedSize = size;
    if (size == 0) {
      logLine(tag + " size=0 (no bars at all yet)");
      return;
    }
    long earliestStart = series.getStartTime(0);
    long latestEnd = series.getEndTime(size - 1);
    double spanHours = (latestEnd - earliestStart) / 3_600_000.0;
    logLine(String.format(
        "%s size=%d earliestStart=%d (%s) latestEnd=%d (%s) spanHours=%.1f spanDays=%.2f",
        tag, size, earliestStart, Instant.ofEpochMilli(earliestStart),
        latestEnd, Instant.ofEpochMilli(latestEnd), spanHours, spanHours / 24.0));
  }

  private void logLine(String s) {
    if (log != null) {
      log.println(System.currentTimeMillis() + " " + s);
      log.flush();
    }
  }
}
