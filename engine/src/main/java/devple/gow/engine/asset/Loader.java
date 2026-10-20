package devple.gow.engine.asset;

import devple.gow.engine.core.PerfMonitor;

import java.io.UncheckedIOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * 백그라운드 적재 (48 문서 §8). 원작은 LoadWad·LoadForWarp 를 백그라운드로 읽는다 (loadCheck = 적재 끝남). 엔진은 GL 이 없는 일
 * (파일 읽기·WAD 해석·레벨 데이터·충돌·배치 메시 굽기·텍스처 해독) 만 여기서 하고, GL 올리기는 메인 스레드가 결과를 받아서 한다.
 * <p>
 * 결과는 스레드와 무관하게 같은 값이고, 쓰는 쪽은 항상 {@link #join} 으로 끝난 결과를 받으므로 게임 진행은 시간과 무관하다.
 * 시간에 따라 달라지는 것은 이웃 슬롯 레벨이 화면에 나타나는 프레임뿐 — {@code -Dgow.syncLoad} (스크린샷 하네스는 자동) 면
 * 제출하는 자리에서 바로 돌려 예전과 같은 프레임에 나타난다.
 */
public final class Loader {

	private Loader() {
	}

	private static volatile ExecutorService exec;

	/** false = 동기 (제출 스레드에서 바로). */
	public static boolean async() {
		return !Boolean.getBoolean("gow.syncLoad");
	}

	private static ExecutorService exec() {
		ExecutorService e = exec;
		if (e == null) {
			synchronized (Loader.class) {
				if (exec == null) {
					int n = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 1));
					int[] seq = {0};
					exec = Executors.newFixedThreadPool(n, r -> {
						Thread t = new Thread(r, "gow-loader-" + seq[0]++);
						t.setDaemon(true);
						t.setPriority(Thread.NORM_PRIORITY - 1);
						return t;
					});
				}
				e = exec;
			}
		}
		return e;
	}

	/** 지금 스레드가 적재 스레드인가 (적재 작업 안에서 다른 적재 작업을 기다리지 않게 — 교착 방지). */
	public static boolean onLoaderThread() {
		return Thread.currentThread().getName().startsWith("gow-loader-");
	}

	/** 작업 제출. 동기 모드면 이 자리에서 실행해 끝난 Future 를 돌려준다. */
	public static <T> Future<T> submit(String what, Callable<T> task) {
		Callable<T> timed = () -> {
			long t0 = System.nanoTime();
			try {
				return task.call();
			} finally {
				PerfMonitor.loadTime("prep " + what, t0);
			}
		};
		if (!async()) {
			FutureTask<T> f = new FutureTask<>(timed);
			f.run();
			return f;
		}
		return exec().submit(timed);
	}

	/** 결과 받기 (끝나지 않았으면 기다림). 작업의 예외는 그대로 다시 던진다. */
	public static <T> T join(Future<T> f, String what) {
		boolean waited = !f.isDone();
		long t0 = System.nanoTime();
		try {
			return f.get();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		} catch (ExecutionException ex) {
			Throwable c = ex.getCause();
			if (c instanceof RuntimeException r) {
				throw r;
			}
			if (c instanceof Error er) {
				throw er;
			}
			if (c instanceof java.io.IOException io) {
				throw new UncheckedIOException(io);
			}
			throw new IllegalStateException(c);
		} finally {
			if (waited) {
				PerfMonitor.loadTime("wait " + what, t0);
			}
		}
	}
}
