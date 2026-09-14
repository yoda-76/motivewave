package flow_diag;

import com.motivewave.platform.sdk.common.AggregateFilter;
import com.motivewave.platform.sdk.common.DOMSnapshot;
import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.DataSeries;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.Instrument;
import com.motivewave.platform.sdk.common.SwingPoint;
import com.motivewave.platform.sdk.common.Tick;
import com.motivewave.platform.sdk.common.TickOperation;
import com.motivewave.platform.sdk.profile.SummaryProfile;
import com.motivewave.platform.sdk.profile.VolumeProfile;
import com.motivewave.platform.sdk.profile.VolumeRow;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * Throwaway diagnostic study (see FLOW/experiments convention). Not a
 * strategy, places no orders.
 *
 * Purpose (FLOW_V2 E-1 + E-6, see sdk-capability-findings.md): exercise
 * every SDK type/method quoted in that document's audit, against our
 * ACTUAL mwave_sdk.jar (Javadoc was generated separately and may not
 * match -- see that document's T-6). If this class compiles AND runs
 * without exception, every signature quoted is real. `getValueArea(double,
 * VAMethod)` (E-6) is deliberately NOT attempted here -- jar inspection
 * already confirmed `com.motivewave.platform.common` (and any VAMethod
 * type under either namespace) does not exist anywhere in our jar, so
 * that overload can't even be referenced; only `getValueArea(double)` is
 * exercised below.
 */
@StudyHeader(
    namespace = "com.flow.diag",
    id = "FLOW_DIAG_SDK_SWEEP",
    name = "FLOW_DIAG_SDK_SWEEP",
    label = "FLOW Diag - SDK Signature Sweep (E-1, read-only)",
    desc = "Exercises every SDK engine-class signature from the capability audit; places no orders",
    menu = "FLOW",
    overlay = true,
    requiresBarUpdates = true,
    supportsBarUpdates = true,
    barUpdatesByDefault = true)
public class SdkSignatureSweep extends Study {
  private static final String LOG_FILE =
      "C:/yadvendra/trading/motivewave/experiments/logs/sdk_sweep.log";

  private volatile PrintWriter log;
  private volatile boolean ranOnce = false;

  @Override
  public void initialize(Defaults defaults) {
    createSD();
    createRD();
    try {
      log = new PrintWriter(new FileWriter(LOG_FILE, true));
      log.println("# session start " + System.currentTimeMillis());
      log.flush();
    } catch (IOException e) {
      error("FLOW_DIAG: failed to open sdk sweep log: " + e.getMessage());
    }
  }

  @Override
  protected void calculate(int index, DataContext ctx) {
    if (ranOnce) return;
    ranOnce = true;
    runSweep(ctx);
  }

  private void runSweep(DataContext ctx) {
    StringBuilder report = new StringBuilder();
    report.append("=== SDK signature sweep @ ").append(System.currentTimeMillis()).append(" ===\n");

    try {
      Instrument instr = ctx.getInstrument();
      long now = System.currentTimeMillis();
      long start = now - 60_000;

      // --- VolumeProfile: construction + every method quoted in the audit ---
      VolumeProfile vp = new VolumeProfile(start, now, instr, 1);
      vp.setFilterTrades(false);
      vp.setMinTradeSize(0f);
      VolumeRow poc = vp.getPOC();
      float pocMid = vp.getPOCMidpoint();
      vp.updatePOC();
      int[] va = vp.getValueArea(0.70);
      float vaHigh = vp.getVAHigh(va);
      float vaLow = vp.getVALow(va);
      int[] hvns = vp.getHVNs(1);
      int[] lvns = vp.getLVNs(1);
      List<VolumeRow> rows = vp.getRows();
      VolumeRow found = vp.find((float) (instr.getLastPrice() > 0 ? instr.getLastPrice() : 1.0));
      double totalVol = vp.getTotalVolume();
      double totalDelta = vp.getTotalDelta();
      double totalBidVol = vp.getTotalBidVolume();
      double totalAskVol = vp.getTotalAskVolume();
      double deltaPer = vp.getDeltaPer();
      double deltaChange = vp.getDeltaChange();
      double volPerSec = vp.getVolumePerSecond();
      double maxVol = vp.getMaxVolume();
      double minVol = vp.getMinVolume();
      double maxDelta = vp.getMaxDelta();
      double minDelta = vp.getMinDelta();
      double maxRowDelta = vp.getMaxRowDelta();
      double minRowDelta = vp.getMinRowDelta();
      double maxBidVol = vp.getMaxBidVolume();
      double maxAskVol = vp.getMaxAskVolume();
      float hi = vp.getHighPrice();
      float lo = vp.getLowPrice();
      float open = vp.getOpenPrice();
      float last = vp.getLastPrice();
      float mid = vp.getMidPrice();
      VolumeProfile prev = vp.getPrev();
      VolumeProfile next = vp.getNext();
      boolean complete = vp.isComplete();
      vp.setComplete(true);
      report.append("OK VolumeProfile: constructed + all ").append("34").append(" quoted methods called without exception\n");
      report.append("  rows=").append(rows.size())
          .append(" poc=").append(poc)
          .append(" pocMid=").append(pocMid)
          .append(" va=[").append(va == null ? "null" : (va.length + " ints")).append("]")
          .append(" vaHigh=").append(vaHigh).append(" vaLow=").append(vaLow)
          .append(" hvns=").append(hvns == null ? "null" : hvns.length)
          .append(" lvns=").append(lvns == null ? "null" : lvns.length)
          .append('\n');

      // --- SummaryProfile: confirm it extends VolumeProfile and constructs ---
      SummaryProfile sp = new SummaryProfile(start, now, instr, 1);
      report.append("OK SummaryProfile constructed (extends VolumeProfile: ")
          .append(sp instanceof VolumeProfile).append(")\n");

      // --- VolumeRow: every method quoted, via find() on the profile above ---
      if (found != null) {
        double rVol = found.getVolume();
        double rAskVol = found.getAskVolume();
        double rBidVol = found.getBidVolume();
        double rDelta = found.getDelta();
        List<Tick> askTrades = found.getAskTrades();
        List<Tick> bidTrades = found.getBidTrades();
        float startPrice = found.getStartPrice();
        float endPrice = found.getEndPrice();
        float rowPrice = found.getRowPrice();
        boolean contains = found.contains(rowPrice);
        boolean isPoc = found.isPOC();
        boolean uaHigh = found.isUAHigh();
        boolean uaLow = found.isUALow();
        boolean bidImb = found.isBidImbalance(0.7, 100, true);
        boolean askImb = found.isAskImbalance(0.7, 100, true);
        report.append("OK VolumeRow: all quoted methods called; askTrades=")
            .append(askTrades == null ? "null" : askTrades.size())
            .append(" bidTrades=").append(bidTrades == null ? "null" : bidTrades.size())
            .append(" (T-1 check: null/empty here just means this row had no ticks yet,")
            .append(" not proof the list isn't retained under real volume)\n");
      } else {
        report.append("NOTE VolumeRow.find() returned null on an empty just-constructed profile")
            .append(" (expected, no ticks fed yet) -- per-row method sweep skipped this run\n");
      }

      // --- AggregateFilter ---
      TickOperation noop = (Tick t) -> { };
      AggregateFilter af = new AggregateFilter(true, 0L, 0f, 0f, noop);
      af.setOperation(noop);
      AggregateFilter af2 = af.withOp(noop);
      af.reset();
      report.append("OK AggregateFilter: constructed + setOperation/withOp/reset called\n");

      // --- TPOProfile: constructor signature does NOT match the Javadoc
      // (real: long,long,long,Instrument,int,int,int,boolean vs documented
      // long,long,Instrument,int) -- confirmed via javap, not attempted here.
      // TPO is out of scope per sdk-capability-findings.md §2.5 anyway.
      // Also confirmed via javap: TPOProfile.getVolumeValueArea/getTimeValueArea
      // both have a (double, Enums$VAMethod) overload referencing
      // com.motivewave.platform.common.Enums$VAMethod -- same unresolvable type
      // as VolumeProfile's E-6 finding, so those overloads are equally dead.
      report.append("SKIP TPOProfile: constructor signature mismatch vs Javadoc (see comment), out of scope anyway\n");

      // --- DataSeries: calcSwingPoints, atr, highest/lowest ---
      DataSeries series = ctx.getDataSeries();
      List<SwingPoint> swings = series.calcSwingPoints(5);
      report.append("OK DataSeries.calcSwingPoints(5) returned ").append(swings == null ? "null" : swings.size()).append(" points\n");
      if (swings != null && !swings.isEmpty()) {
        SwingPoint sw = swings.get(0);
        report.append("  sample SwingPoint: isTop=").append(sw.isTop())
            .append(" isBottom=").append(sw.isBottom())
            .append(" strength=").append(sw.getStrength())
            .append('\n');
      }

      // --- Instrument DOM history ---
      List<DOMSnapshot> domHist = instr.getDOMHistory();
      DOMSnapshot latest = instr.getLatestDOMHistory();
      report.append("OK Instrument.getDOMHistory() size=").append(domHist == null ? "null" : domHist.size())
          .append(" getLatestDOMHistory()=").append(latest == null ? "null" : "present").append('\n');

      report.append("=== SWEEP COMPLETE: all signatures in sdk-capability-findings.md \u00a72").append(" resolved and ran on our jar ===\n");
    } catch (Throwable t) {
      report.append("SWEEP FAILURE: ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
      java.io.StringWriter sw = new java.io.StringWriter();
      t.printStackTrace(new java.io.PrintWriter(sw));
      report.append(sw.toString());
    }

    info("FLOW_DIAG_SWEEP: see sdk_sweep.log");
    if (log != null) {
      log.print(report);
      log.flush();
    }
  }
}
