package devple.gow.engine.core;

import devple.gow.engine.input.Input;
import devple.gow.engine.render.Renderer;
import devple.gow.engine.render.Screenshot;
import devple.gow.engine.scene.Scene;
import devple.gow.engine.scene.SceneManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import static org.lwjgl.glfw.GLFW.glfwGetTime;

/**
 * 메인 루프. 로직은 고정 60Hz(원작 NTSC 60fps 기준), 렌더는 디스플레이 주기.
 */
public final class Engine implements AutoCloseable {

	public static final float TICK = 1f / 60f;

	private final Window window;
	private final Input input;
	private final Renderer renderer;
	private final SceneManager scenes = new SceneManager();
	private long ticks;
	private long frames;
	private long captureFrame = -1;
	private Path captureFile;

	public Engine(String title, int width, int height) {
		this.window = new Window(title, width, height);
		this.input = new Input(window.handle());
		this.renderer = new Renderer(this);
	}

	/** -Dgow.lockstep: 프레임마다 로직 틱 하나 (검증용, 원작에 없음). */
	public static final boolean LOCKSTEP = Boolean.getBoolean("gow.lockstep") || System.getProperty("gow.record") != null;

	/** -Dgow.record=디렉터리: 로직 틱 gow.record.every(기본 2) 번마다 JPEG 한 장 (영상 만들기용, 원작에 없음). gow.record.secs 초 뒤 종료. */
	private static final Path RECORD = System.getProperty("gow.record") != null ? Path.of(System.getProperty("gow.record")) : null;
	private static final int RECORD_EVERY = Integer.getInteger("gow.record.every", 2);
	private static final long RECORD_END = (long) (Float.parseFloat(System.getProperty("gow.record.secs", "0")) * 60);
	/** -Dgow.record.from=초: 그 전 틱은 기록하지 않는다 (긴 자동 플레이의 한 구간만). */
	private static final long RECORD_FROM = (long) (Float.parseFloat(System.getProperty("gow.record.from", "0")) * 60);
	private int recorded;

	public void run(Scene first) {
		scenes.push(this, first);
		PerfMonitor perf = PerfMonitor.fromSystem(); // -Dgow.perf=파일 (48 문서)
		double prev = glfwGetTime();
		double acc = 0;
		while (!window.shouldClose()) {
			if (perf != null) {
				perf.frameStart();
			}
			double now = glfwGetTime();
			acc += Math.min(now - prev, 0.25);
			prev = now;
			if (LOCKSTEP) {
				acc = TICK; // 검증: 프레임마다 틱 정확히 하나, 보간 0 (벽시계와 무관하게 같은 화면 순서, 48 문서 §9)
			}
			while (acc >= TICK) {
				input.poll();
				scenes.update(this, TICK);
				ticks++;
				acc -= TICK;
			}
			devple.gow.engine.render.NormalMaps.pollToggle(window.handle()); // 5 키 = 노멀맵 (원작에 없음, 44 문서)
			renderer.beginFrame();
			scenes.render(this, (float) (acc / TICK));
			renderer.endFrame(scenes.fade());
			if (RECORD != null && ticks >= RECORD_FROM && ticks % RECORD_EVERY == 0) {
				record();
			}
			if (++frames == captureFrame || (captureTick > 0 && ticks >= captureTick && captureFile != null)) {
				captureTick = -1;
				capture();
			}
			devple.gow.engine.fx.ParticleSystem.hashFrame(frames);
			if (perf != null) {
				perf.frameEnd(this);
			}
			window.swap();
		}
	}

	private long captureTick = -1;

	/** 자동 검증용: 로직 틱 n 번(60Hz, 렌더 속도와 무관) 후 스크린샷을 저장하고 종료. */
	public void captureAfterTicks(long n, Path file) {
		captureTick = n;
		captureFile = file;
	}

	/** 자동 검증용: n 프레임 렌더 후 스크린샷을 저장하고 종료. */
	public void captureAfter(long n, Path file) {
		captureFrame = n;
		captureFile = file;
	}

	private void record() {
		try {
			java.nio.file.Files.createDirectories(RECORD);
			Screenshot.save(window.width(), window.height(), RECORD.resolve(String.format("f%06d.jpg", recorded++)), "jpg");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (RECORD_END > 0 && ticks >= RECORD_END) {
			window.requestClose();
		}
	}

	private void capture() {
		if (devple.gow.engine.render.NormalMaps.enabled()) {
			System.out.println("노멀맵: " + devple.gow.engine.render.NormalMaps.stats());
		}
		try {
			Screenshot.save(window.width(), window.height(), captureFile);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		window.requestClose();
	}

	public Window window() {
		return window;
	}

	public Input input() {
		return input;
	}

	public Renderer renderer() {
		return renderer;
	}

	public SceneManager scenes() {
		return scenes;
	}

	public long ticks() {
		return ticks;
	}

	@Override
	public void close() {
		renderer.close();
		window.close();
	}
}
