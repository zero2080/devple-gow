package devple.gow.engine.core;

import com.sun.management.GarbageCollectionNotificationInfo;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 장시간 실행 측정 (48 문서) [엔진 전용 진단, 원작 없음]. {@code -Dgow.perf=파일} 이면 {@code gow.perf.interval} 초(기본 10)마다 한 줄:
 * 프레임 작업 시간(스왑 전까지, 평균·p99·최대)·프레임 간격·힙·GC 횟수/시간/최대 멈춤·직접 버퍼·GL 객체 수·등록 게이지(소리 보이스 등).
 * {@code -Dgow.perf.ticks=n} 이면 로직 틱 n 번 뒤 창을 닫는다.
 */
public final class PerfMonitor {

	private static final Map<String, Supplier<Object>> GAUGES = new LinkedHashMap<>();

	/** 씬이 자기 게이지 (예 "voices") 를 건다. 같은 이름은 덮어쓴다. */
	public static synchronized void gauge(String name, Supplier<Object> value) {
		GAUGES.put(name, value);
	}

	public static synchronized void removeGauge(String name) {
		GAUGES.remove(name);
	}

	private static volatile boolean reportRequested;

	/** 다음 프레임 끝에 한 줄 더 (레벨 체인: 떠나기 직전 같은 지점끼리 비교). */
	public static void requestReport() {
		reportRequested = true;
	}

	/** 지금 측정 중인 모니터 (적재 시간 기록용, 없으면 null). */
	private static volatile PerfMonitor active;
	/** 이 이상 걸린 프레임은 한 줄 따로 (`# hitch`). */
	private static final double HITCH_MS = Double.parseDouble(System.getProperty("gow.perf.hitch", "25"));

	/** 적재 단계 시간 (48 문서 §9): {@link #loadTime} 한 줄을 남기고 지금 시각을 돌려준다 (다음 단계의 시작). */
	public static long phase(String what, long startNanos) {
		loadTime(what, startNanos);
		return System.nanoTime();
	}

	/** 적재 구간 시간 (48 문서 §8): {@code -Dgow.perf} 일 때만 `# load 시각 이름 ms` 한 줄. */
	public static void loadTime(String what, long startNanos) {
		PerfMonitor p = active;
		if (p != null) {
			long now = System.nanoTime();
			p.out.printf(java.util.Locale.ROOT, "# load %.3f %s %.1f [%s]%n", (now - p.t0) / 1e9, what, (now - startNanos) / 1e6,
					Thread.currentThread().getName());
		}
	}

	private static final boolean FULL_GC = System.getProperty("gow.perf.fullgc") != null;
	private final PrintWriter out;
	private final double interval;
	private final long quitTicks;
	private long[] work = new long[4096];
	private long[] gap = new long[4096];
	private int n;
	private long frameStart;
	private long lastSwap;
	private double lastReport;
	private long gcCount0, gcTime0;
	private volatile long maxPauseMs;
	private final long t0 = System.nanoTime();

	private PerfMonitor(Path file, double interval, long quitTicks) {
		try {
			if (file.getParent() != null) {
				Files.createDirectories(file.getParent());
			}
			out = new PrintWriter(Files.newBufferedWriter(file), true);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		this.interval = interval;
		this.quitTicks = quitTicks;
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			if (gc instanceof NotificationEmitter em) {
				em.addNotificationListener((note, hb) -> {
					if (GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(note.getType())) {
						var info = GarbageCollectionNotificationInfo.from((CompositeData) note.getUserData());
						// 동시 수집기 (G1 Concurrent …) 는 응용을 멈추지 않으므로 멈춤 최대에서 뺀다
						if (!info.getGcName().contains("Concurrent") && !"System.gc()".equals(info.getGcCause())) { // 측정용 전체 GC 제외
							maxPauseMs = Math.max(maxPauseMs, info.getGcInfo().getDuration());
						}
					}
				}, null, null);
			}
		}
		long[] g = gcTotals();
		gcCount0 = g[0];
		gcTime0 = g[1];
		active = this;
		out.println("# t(s) ticks frames workAvg workP99 workMax(ms) gapAvg gapP99 gapMax(ms) heapUsed heapCommitted(MB) gcN gcMs gcMaxPause(ms) direct(MB) gl... gauges...");
	}

	/** -Dgow.perf 가 없으면 null. */
	public static PerfMonitor fromSystem() {
		String f = System.getProperty("gow.perf");
		if (f == null) {
			return null;
		}
		return new PerfMonitor(Path.of(f), Double.parseDouble(System.getProperty("gow.perf.interval", "10")),
				Long.parseLong(System.getProperty("gow.perf.ticks", "-1")));
	}

	private static long[] gcTotals() {
		long c = 0, t = 0;
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			if (gc.getName().contains("Concurrent")) { // G1 동시 주기 (멈춤 아님)
				continue;
			}
			c += Math.max(0, gc.getCollectionCount());
			t += Math.max(0, gc.getCollectionTime());
		}
		return new long[]{c, t};
	}

	public void frameStart() {
		frameStart = System.nanoTime();
	}

	/** 스왑 직전. */
	public void frameEnd(Engine e) {
		long now = System.nanoTime();
		if (n == work.length) {
			work = Arrays.copyOf(work, n * 2);
			gap = Arrays.copyOf(gap, n * 2);
		}
		work[n] = now - frameStart;
		gap[n] = lastSwap == 0 ? 0 : now - lastSwap;
		n++;
		lastSwap = now;
		double t = (now - t0) / 1e9;
		if (work[n - 1] / 1e6 >= HITCH_MS) {
			out.printf(java.util.Locale.ROOT, "# hitch %.3f work %.1f gap %.1f%n", t, work[n - 1] / 1e6, gap[n - 1] / 1e6);
		}
		if (t - lastReport >= interval || reportRequested) {
			reportRequested = false;
			lastReport = t;
			// 장시간 실행: 30 초 무렵 한 번 + 끝에 한 번 전체 GC 로 살아 있는 힙 (그 사이 증가 = 레벨 안 누수)
			report(e, t, FULL_GC || !liveProbed && t >= 30);
			liveProbed |= t >= 30;
		}
		if (quitTicks > 0 && e.ticks() >= quitTicks) {
			report(e, t, true);
			e.window().requestClose();
		}
	}

	private long frames;

	private boolean liveProbed;

	private void report(Engine e, double t, boolean fullGc) {
		frames += n;
		long[] w = Arrays.copyOf(work, n);
		long[] g = Arrays.copyOf(gap, n);
		Arrays.sort(w);
		Arrays.sort(g);
		MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
		var heap = mem.getHeapMemoryUsage();
		long[] gc = gcTotals();
		long direct = 0;
		for (BufferPoolMXBean bp : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
			direct += bp.getMemoryUsed();
		}
		long live = -1;
		if (fullGc) { // 측정 직전 전체 GC → 살아 있는 힙 (-Dgow.perf.fullgc 면 매번: 레벨 전환 누수 측정용, 멈춤 통계는 흐려진다)
			System.gc();
			live = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
		}
		long oldAfterGc = 0; // 마지막 GC 직후 오래된 세대 (누수 지표)
		for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
			if (pool.getName().contains("Old Gen") && pool.getCollectionUsage() != null) {
				oldAfterGc = pool.getCollectionUsage().getUsed();
			}
		}
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(java.util.Locale.ROOT, "%.0f %d %d %.2f %.2f %.2f %.2f %.2f %.2f %d %d %d %d %d %d ", t, e.ticks(), frames,
				avg(w), pct(w, 0.99), max(w), avg(g), pct(g, 0.99), max(g), heap.getUsed() >> 20, heap.getCommitted() >> 20,
				gc[0] - gcCount0, gc[1] - gcTime0, maxPauseMs, direct >> 20));
		sb.append("oldAfterGC=").append(oldAfterGc >> 20).append(' ');
		if (live >= 0) {
			sb.append("live=").append(live).append(' ');
		}
		sb.append(devple.gow.engine.render.GlStats.describe());
		synchronized (PerfMonitor.class) {
			GAUGES.forEach((k, v) -> {
				Object o;
				try {
					o = v.get();
				} catch (RuntimeException ex) {
					o = "?";
				}
				sb.append(' ').append(k).append('=').append(o);
			});
		}
		out.println(sb);
		n = 0;
		maxPauseMs = 0;
	}

	private static double avg(long[] a) {
		return a.length == 0 ? 0 : Arrays.stream(a).average().orElse(0) / 1e6;
	}

	private static double pct(long[] sorted, double p) {
		return sorted.length == 0 ? 0 : sorted[Math.min(sorted.length - 1, (int) Math.floor(p * sorted.length))] / 1e6;
	}

	private static double max(long[] sorted) {
		return sorted.length == 0 ? 0 : sorted[sorted.length - 1] / 1e6;
	}
}
