package flow_diag;

import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.DataSeries;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.desc.BooleanDescriptor;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Throwaway diagnostic study (see FLOW/experiments convention). Not a
 * strategy -- no `strategy=true`, no OrderContext-taking hook exists on
 * this class to override, places no orders under any circumstance.
 *
 * Purpose: dump whatever's currently loaded in DataSeries to a plain
 * CSV, for the FLOW_V2 plain-OHLC market-structure backtest
 * (docs/dynamic/todo.md, 2026-09-20) to actually read. Companion to
 * HistoricalDepthProbe, which measured how much history is reachable at
 * all (found: ~25,000 1-min @GC bars / ~24.2 calendar days, capped by
 * the client-side "Max Linear Bars" chart setting, not a data-provider
 * wall -- see ../motivewave/docs/dynamic/findings.md).
 *
 * Deliberately writes into FLOW_V2's own analysis/data/ directory, not
 * this repo's logs/ -- the output is a dataset FLOW_V2's backtest
 * consumes, not a finding about the platform itself (D-01's own
 * "is this true about MotiveWave, or true about our system" split: the
 * CSV's content is just @GC's own price history, not a fact about the
 * SDK/platform).
 *
 * Usage: add to the SAME @GC 1-minute chart already scrolled back to
 * its full loaded depth (HistoricalDepthProbe confirms via its own log
 * how far that actually is), then check "Export current DataSeries to
 * CSV now" in the study's settings. Exports once per activation
 * (unchecking and rechecking without reactivating the study does
 * nothing further -- remove and re-add the study, or restart
 * MotiveWave, to export again).
 */
@StudyHeader(
    namespace = "com.flow.diag",
    id = "FLOW_DIAG_OHLC_EXPORT",
    name = "FLOW_DIAG_OHLC_EXPORT",
    label = "FLOW Diag - OHLC CSV Exporter (read-only)",
    desc = "Dumps the current DataSeries to a CSV file on request",
    menu = "FLOW",
    overlay = true)
public class HistoricalOhlcExporter extends Study {
  private static final String OUTPUT_DIR = "C:/yadvendra/trading/FLOW_V2/analysis/data/";
  private static final String LOG_FILE =
      "C:/yadvendra/trading/motivewave/experiments/logs/historical_ohlc_exporter.log";
  private static final String EXPORT_NOW_KEY = "EXPORT_NOW";

  private volatile PrintWriter log;
  private volatile boolean exportedThisActivation = false;

  @Override
  public void initialize(Defaults defaults) {
    var sd = createSD();
    var tab = sd.addTab("General");
    var grp = tab.addGroup("Export");
    grp.addRow(new BooleanDescriptor(EXPORT_NOW_KEY, "Export current DataSeries to CSV now", false));
    createRD();
    try {
      log = new PrintWriter(new FileWriter(LOG_FILE, true));
      log.println("# session start " + System.currentTimeMillis());
      log.flush();
    } catch (IOException e) {
      error("FLOW_DIAG: failed to open ohlc exporter log: " + e.getMessage());
    }
  }

  @Override
  protected void calculate(int index, DataContext ctx) {
    if (exportedThisActivation) return;
    if (!getSettings().getBoolean(EXPORT_NOW_KEY)) return;
    DataSeries series = ctx.getDataSeries();
    if (series == null || series.size() == 0) return;
    if (index != series.size() - 1) return; // wait until the series is "settled" for this calculation pass
    exportedThisActivation = true;
    doExport(ctx, series);
  }

  private void doExport(DataContext ctx, DataSeries series) {
    String symbol = ctx.getInstrument().getSymbol().replace("@", "");
    long now = System.currentTimeMillis();
    String path = OUTPUT_DIR + symbol + "_1m_" + now + ".csv";
    try {
      new java.io.File(OUTPUT_DIR).mkdirs();
      try (PrintWriter w = new PrintWriter(new FileWriter(path))) {
        w.println("timestampMs,open,high,low,close,volume");
        int size = series.size();
        for (int i = 0; i < size; i++) {
          w.println(series.getStartTime(i) + "," + series.getOpen(i) + "," + series.getHigh(i) + ","
              + series.getLow(i) + "," + series.getClose(i) + "," + series.getVolume(i));
        }
      }
      logLine("EXPORTED path=" + path + " bars=" + series.size());
    } catch (IOException e) {
      logLine("EXPORT_FAILED path=" + path + " error=" + e.getMessage());
      error("FLOW_DIAG: OHLC export failed: " + e.getMessage());
    }
  }

  private void logLine(String s) {
    if (log != null) {
      log.println(System.currentTimeMillis() + " " + s);
      log.flush();
    }
  }
}
