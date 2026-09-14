package flow_diag;

import com.motivewave.platform.sdk.common.AggregateFilter;
import com.motivewave.platform.sdk.common.BarSize;
import com.motivewave.platform.sdk.common.DOM;
import com.motivewave.platform.sdk.common.DOMListener;
import com.motivewave.platform.sdk.common.DOMSnapshot;
import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.DataSeries;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.Instrument;
import com.motivewave.platform.sdk.common.SwingPoint;
import com.motivewave.platform.sdk.common.Tick;
import com.motivewave.platform.sdk.common.TickOperation;
import com.motivewave.platform.sdk.draw.Line;
import com.motivewave.platform.sdk.profile.VolumeProfile;
import com.motivewave.platform.sdk.profile.VolumeRow;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

import java.awt.Color;
import java.awt.Font;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Throwaway diagnostic study (see FLOW/experiments convention). Not a
 * strategy, places no orders.
 *
 * Purpose: covers E-2, E-3, E-4, E-5, E-7, E-8, E-9 from
 * sdk-capability-findings.md in one live @GC session, per that document's
 * own suggested bundling (\u00a75, "Schedule one live @GC session"). Every
 * engine class used here (VolumeProfile, AggregateFilter,
 * calcSwingPoints) was confirmed by E-10 to have EITHER zero real-world
 * reference usage in MotiveWave's own published studies (VolumeProfile,
 * AggregateFilter) or real usage (calcSwingPoints) -- see
 * ../motivewave/docs/dynamic/findings.md 2026-09-14. For the
 * zero-reference classes this run IS the reference implementation, so the
 * memory guard below (E-3 / T-1) is not a nice-to-have, it's the actual
 * safety net.
 */
@StudyHeader(
    namespace = "com.flow.diag",
    id = "FLOW_DIAG_SDK_CAPABILITY_PROBE",
    name = "FLOW_DIAG_SDK_CAPABILITY_PROBE",
    label = "FLOW Diag - SDK Capability Probe (E-2/3/4/5/7/8/9, read-only)",
    desc = "VolumeProfile, AggregateFilter, calcSwingPoints, DOM history, secondary timeframe; places no orders",
    menu = "FLOW",
    overlay = true,
    requiresBarUpdates = true,
    supportsBarUpdates = true,
    barUpdatesByDefault = true)
public class SdkCapabilityProbe extends Study implements DOMListener {
  private static final String LOG_FILE =
      "C:/yadvendra/trading/motivewave/experiments/logs/sdk_capability_probe.log";
  private static final double VALUE_AREA_PCT = 0.70; // matches typical MotiveWave default; adjust to match your chart's setting
  private static final int RANGE_TICKS = 1; // finest resolution, per the audit's footprint suggestion
  private static final long HEARTBEAT_SECONDS = 20;
  // E-3/T-1 safety guard: stop feeding the session-scoped profile if heap
  // growth since baseline crosses this, since VolumeProfile has zero
  // reference usage anywhere in MotiveWave's own published studies and
  // VolumeRow.getAskTrades()/getBidTrades() may retain every tick per row.
  private static final long HEAP_GUARD_BYTES = 300_000_000L; // 300 MB
  private static final long TICK_COUNT_GUARD = 500_000L; // generous backstop, ~40+ hrs at measured @GC rates

  private volatile PrintWriter log;
  private volatile Instrument instrument;
  private final AtomicBoolean subscribed = new AtomicBoolean(false);
  private final AtomicBoolean secondaryTfChecked = new AtomicBoolean(false);

  // E-2 / E-3: session-scoped profile
  private volatile VolumeProfile sessionProfile;
  private final AtomicBoolean sessionProfileHealthy = new AtomicBoolean(true);
  private final AtomicLong sessionTickCount = new AtomicLong(0);
  private final AtomicLong sessionProcessingNanos = new AtomicLong(0);
  private volatile long baselineHeapBytes = -1;
  private volatile long sessionStartTime;

  // E-4: bar-scoped footprint profile
  private volatile VolumeProfile barProfile;
  private final AtomicLong barProfileTickCount = new AtomicLong(0);

  // E-5: big-trade aggregation, tracked two ways to discover which id field repeats (T-3)
  private volatile AggregateFilter bigTradeFilter;
  private final Map<Long, Float> seenExchOrderSize = new ConcurrentHashMap<>();
  private final Map<Long, Float> seenAggExchOrderSize = new ConcurrentHashMap<>();

  // E-7: swing point stability tracking, one set per strength tested
  private final int[] swingStrengths = {3, 5, 8};
  private final Map<Integer, Set<Integer>> lastSwingIndexesByStrength = new HashMap<>();

  // E-9: our own live DOM top-of-book, for cross-check against getDOMHistory()
  private volatile double lastBidPrice, lastAskPrice;
  private volatile double lastBidSize, lastAskSize;
  private volatile long lastDomUpdateTime;

  private volatile ScheduledExecutorService heartbeat;
  private final int instanceId = System.identityHashCode(this);

  @Override
  public void initialize(Defaults defaults) {
    createSD();
    createRD();
    try {
      log = new PrintWriter(new FileWriter(LOG_FILE, true));
      log.println("# session start " + System.currentTimeMillis()
          + " instance=" + instanceId
          + " valueAreaPct=" + VALUE_AREA_PCT + " rangeTicks=" + RANGE_TICKS
          + " heapGuardBytes=" + HEAP_GUARD_BYTES);
      log.flush();
    } catch (IOException e) {
      error("FLOW_DIAG: failed to open sdk capability probe log: " + e.getMessage());
    }
  }

  // Confirmed live: removing this study from the chart does NOT stop
  // onTick/DOMListener callbacks or the heartbeat thread on its own --
  // destroy() is the documented place to release resources, and without
  // this override a "removed" instance keeps running forever as a
  // zombie, logging stale data interleaved with whatever instance
  // replaced it. Discovered by seeing identical VolumeProfile readings
  // (rows=50 totalVol=555.0, unchanged) six minutes apart in the log.
  @Override
  public void destroy() {
    logLine("DESTROY instance=" + instanceId);
    if (heartbeat != null) {
      heartbeat.shutdownNow();
      heartbeat = null;
    }
    if (instrument != null) {
      instrument.removeListener((DOMListener) this);
    }
    if (log != null) {
      log.flush();
      log.close();
      log = null;
    }
  }

  @Override
  protected void calculate(int index, DataContext ctx) {
    if (subscribed.compareAndSet(false, true)) {
      instrument = ctx.getInstrument();
      instrument.addListener(this);
      long now = System.currentTimeMillis();
      sessionStartTime = now;
      sessionProfile = new VolumeProfile(now, now + 12L * 3600_000L, instrument, RANGE_TICKS);
      logLine("SUBSCRIBED symbol=" + instrument.getSymbol() + " tickSize=" + instrument.getTickSize());

      heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "flow-diag-sdk-probe-heartbeat");
        t.setDaemon(true);
        return t;
      });
      heartbeat.scheduleAtFixedRate(this::onHeartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    // E-8: secondary timeframe availability, one-shot
    if (secondaryTfChecked.compareAndSet(false, true)) {
      try {
        DataSeries secondary = ctx.getDataSeries(BarSize.minute(5));
        logLine("E8_SECONDARY_TF result=" + (secondary == null ? "NULL" : ("size=" + secondary.size())));
      } catch (Throwable t) {
        logLine("E8_SECONDARY_TF exception=" + t);
      }
    }

    // E-5: big-trade filter, constructed once we have an instrument
    if (bigTradeFilter == null && instrument != null) {
      TickOperation onBigTrade = this::onBigTrade;
      bigTradeFilter = new AggregateFilter(true, 0L, 10f, 0f, onBigTrade);
      logLine("E5_FILTER_CONFIGURED aggByOrder=true minSize=10 maxSize=0(none) aggPeriod=0");
    }
  }

  @Override
  public void onTick(DataContext ctx, Tick tick) {
    sessionTickCount.incrementAndGet();

    if (sessionProfileHealthy.get() && sessionProfile != null) {
      long t0 = System.nanoTime();
      try {
        sessionProfile.onTick(tick);
      } catch (Throwable t) {
        sessionProfileHealthy.set(false);
        logLine("E3_SESSION_PROFILE_EXCEPTION " + t + " -- marking unhealthy, no longer feeding it");
      }
      sessionProcessingNanos.addAndGet(System.nanoTime() - t0);
    }

    VolumeProfile bp = barProfile;
    if (bp != null) {
      try {
        bp.onTick(tick);
        barProfileTickCount.incrementAndGet();
      } catch (Throwable t) {
        logLine("E4_BAR_PROFILE_EXCEPTION " + t);
      }
    }

    AggregateFilter af = bigTradeFilter;
    if (af != null) {
      try {
        af.onTick(tick);
      } catch (Throwable t) {
        logLine("E5_FILTER_EXCEPTION " + t);
      }
    }
  }

  private void onBigTrade(Tick tick) {
    long exchId = tick.getExchOrderId();
    long aggId = tick.getAggExchOrderId();
    float size = tick.getVolumeAsFloat();

    Float prevExch = seenExchOrderSize.put(exchId, size);
    Float prevAgg = seenAggExchOrderSize.put(aggId, size);

    StringBuilder sb = new StringBuilder("E5_BIG_TRADE price=").append(tick.getPrice())
        .append(" size=").append(size)
        .append(" isAskTick=").append(tick.isAskTick())
        .append(" exchOrderId=").append(exchId)
        .append(" aggExchOrderId=").append(aggId);
    if (prevExch != null) {
      sb.append(" T3_REPEAT_BY_EXCH_ID prevSize=").append(prevExch).append(" newSize=").append(size);
    }
    if (prevAgg != null) {
      sb.append(" T3_REPEAT_BY_AGG_ID prevSize=").append(prevAgg).append(" newSize=").append(size);
    }
    logLine(sb.toString());
  }

  @Override
  public void onBarClose(DataContext ctx) {
    // E-4: finalize the closing bar's footprint profile
    VolumeProfile closingBar = barProfile;
    if (closingBar != null) {
      logBarFootprint(closingBar, barProfileTickCount.get());
    }
    long now = System.currentTimeMillis();
    barProfile = new VolumeProfile(now, now + 10 * 60_000L, instrument, RANGE_TICKS);
    barProfileTickCount.set(0);

    // E-7: swing points at several strengths, diffed against the last call for stability
    try {
      DataSeries series = ctx.getDataSeries();
      for (int strength : swingStrengths) {
        List<SwingPoint> swings = series.calcSwingPoints(strength);
        Set<Integer> indexes = new java.util.HashSet<>();
        StringBuilder sb = new StringBuilder("E7_SWINGS strength=").append(strength)
            .append(" count=").append(swings == null ? 0 : swings.size());
        if (swings != null) {
          for (SwingPoint sp : swings) indexes.add(sp.getIndex());
        }
        Set<Integer> prev = lastSwingIndexesByStrength.get(strength);
        if (prev != null) {
          Set<Integer> added = new java.util.HashSet<>(indexes);
          added.removeAll(prev);
          Set<Integer> removed = new java.util.HashSet<>(prev);
          removed.removeAll(indexes);
          if (!added.isEmpty() || !removed.isEmpty()) {
            sb.append(" CHANGED addedIdx=").append(added).append(" removedIdx=").append(removed);
          } else {
            sb.append(" STABLE");
          }
        }
        lastSwingIndexesByStrength.put(strength, indexes);
        logLine(sb.toString());
      }
    } catch (Throwable t) {
      logLine("E7_SWINGS_EXCEPTION " + t);
    }
  }

  private void logBarFootprint(VolumeProfile bar, long tickCount) {
    try {
      List<VolumeRow> rows = bar.getRows();
      logLine("E4_BAR_CLOSE ticks=" + tickCount + " rows=" + (rows == null ? 0 : rows.size())
          + " totalVol=" + bar.getTotalVolume() + " totalDelta=" + bar.getTotalDelta());
      if (rows != null) {
        for (VolumeRow row : rows) {
          boolean bidImb70 = row.isBidImbalance(0.70, 0, false);
          boolean askImb70 = row.isAskImbalance(0.70, 0, false);
          boolean bidImb300 = row.isBidImbalance(0.0, 300, true);
          boolean askImb300 = row.isAskImbalance(0.0, 300, true);
          logLine(String.format(
              "  ROW price=%.4f askVol=%.2f bidVol=%.2f delta=%.2f isPOC=%b isUAHigh=%b isUALow=%b"
                  + " bidImb(per=.70)=%b askImb(per=.70)=%b bidImb(delta=300)=%b askImb(delta=300)=%b",
              row.getRowPrice(), row.getAskVolume(), row.getBidVolume(), row.getDelta(),
              row.isPOC(), row.isUAHigh(), row.isUALow(), bidImb70, askImb70, bidImb300, askImb300));
        }
      }
    } catch (Throwable t) {
      logLine("E4_LOG_EXCEPTION " + t);
    }
  }

  @Override
  public void update(DOM dom) {
    try {
      List<com.motivewave.platform.sdk.common.DOMRow> bids = dom.getBidRows();
      List<com.motivewave.platform.sdk.common.DOMRow> asks = dom.getAskRows();
      if (bids != null && !bids.isEmpty()) {
        lastBidPrice = bids.get(0).getPrice();
        lastBidSize = bids.get(0).getSize();
      }
      if (asks != null && !asks.isEmpty()) {
        lastAskPrice = asks.get(0).getPrice();
        lastAskSize = asks.get(0).getSize();
      }
      lastDomUpdateTime = System.currentTimeMillis();
    } catch (Throwable t) {
      logLine("E9_DOM_UPDATE_EXCEPTION " + t);
    }
  }

  private void onHeartbeat() {
    try {
      // E-3: memory + throughput
      Runtime rt = Runtime.getRuntime();
      long used = rt.totalMemory() - rt.freeMemory();
      if (baselineHeapBytes < 0) baselineHeapBytes = used;
      long growth = used - baselineHeapBytes;
      long ticks = sessionTickCount.get();
      long procNanos = sessionProcessingNanos.get();
      double avgMicrosPerTick = ticks == 0 ? 0 : (procNanos / 1000.0) / ticks;
      logLine(String.format("E3_HEARTBEAT usedHeap=%d growthSinceBaseline=%d ticks=%d avgProcessingMicrosPerTick=%.3f healthy=%b",
          used, growth, ticks, avgMicrosPerTick, sessionProfileHealthy.get()));

      if (sessionProfileHealthy.get() && (growth > HEAP_GUARD_BYTES || ticks > TICK_COUNT_GUARD)) {
        sessionProfileHealthy.set(false);
        logLine("E3_T1_GUARD_TRIPPED growth=" + growth + " ticks=" + ticks
            + " -- session profile feed STOPPED, this bounds the memory growth measurement");
      }

      // E-2: session profile snapshot, guarded
      VolumeProfile sp = sessionProfile;
      if (sp != null) {
        try {
          VolumeRow poc = sp.getPOC();
          int[] va = sp.getValueArea(VALUE_AREA_PCT);
          float vaHigh = va == null ? Float.NaN : sp.getVAHigh(va);
          float vaLow = va == null ? Float.NaN : sp.getVALow(va);
          logLine(String.format("E2_SESSION_PROFILE rows=%d poc=%s vaHigh=%.4f vaLow=%.4f totalVol=%.1f totalDelta=%.1f -- compare against your chart's Volume Profile study",
              sp.getRows() == null ? 0 : sp.getRows().size(),
              poc == null ? "null" : String.format("%.4f", poc.getRowPrice()),
              vaHigh, vaLow, sp.getTotalVolume(), sp.getTotalDelta()));
          if (poc != null && va != null) {
            drawSessionLines(poc.getRowPrice(), vaHigh, vaLow);
          }
        } catch (Throwable t) {
          logLine("E2_SNAPSHOT_EXCEPTION " + t);
        }
      }

      // E-9: DOM history cadence/depth vs our own live top-of-book
      if (instrument != null) {
        List<DOMSnapshot> hist = instrument.getDOMHistory();
        DOMSnapshot latest = instrument.getLatestDOMHistory();
        String latestDesc = "null";
        if (latest != null) {
          float[] bidPrices = latest.getBidPrices();
          float[] askPrices = latest.getAskPrices();
          latestDesc = "bidDepth=" + (bidPrices == null ? 0 : bidPrices.length)
              + " askDepth=" + (askPrices == null ? 0 : askPrices.length)
              + " topBid=" + (bidPrices != null && bidPrices.length > 0 ? bidPrices[0] : Double.NaN)
              + " topAsk=" + (askPrices != null && askPrices.length > 0 ? askPrices[0] : Double.NaN);
        }
        logLine(String.format("E9_DOM_HISTORY size=%d latest=[%s] ourLiveTopOfBook=[bid=%.4f/%.2f ask=%.4f/%.2f @ %d]",
            hist == null ? 0 : hist.size(), latestDesc,
            lastBidPrice, lastBidSize, lastAskPrice, lastAskSize, lastDomUpdateTime));
      }
    } catch (Throwable t) {
      logLine("HEARTBEAT_EXCEPTION " + t);
    }
  }

  // E-2, drawn on the chart so it can be visually compared against the
  // built-in Volume Profile study directly, instead of matching log
  // timestamps by hand. Re-drawn every heartbeat under a fixed tag so old
  // lines are replaced, not accumulated.
  private void drawSessionLines(float poc, float vaHigh, float vaLow) {
    try {
      beginFigureUpdate();
      try {
        clearFigures("e2_lines");
        long now = System.currentTimeMillis();
        addFigure("e2_lines", makeLine(sessionStartTime, poc, now, poc, Color.YELLOW, "OUR POC " + poc));
        addFigure("e2_lines", makeLine(sessionStartTime, vaHigh, now, vaHigh, Color.CYAN, "OUR VAH " + vaHigh));
        addFigure("e2_lines", makeLine(sessionStartTime, vaLow, now, vaLow, Color.CYAN, "OUR VAL " + vaLow));
      } finally {
        endFigureUpdate();
      }
      notifyRedraw(); // addFigure alone does not trigger a repaint -- confirmed via Javadoc, found live
    } catch (Throwable t) {
      logLine("E2_DRAW_EXCEPTION " + t);
    }
  }

  private Line makeLine(long startTime, double startValue, long endTime, double endValue, Color color, String label) {
    Line line = new Line(startTime, startValue, endTime, endValue);
    line.setColor(color);
    line.setExtendRightBounds(true);
    line.setText(label, new Font("Dialog", Font.PLAIN, 11));
    return line;
  }

  private void logLine(String s) {
    if (log != null) {
      log.println(System.currentTimeMillis() + " inst=" + instanceId + " " + s);
      log.flush();
    }
  }
}
