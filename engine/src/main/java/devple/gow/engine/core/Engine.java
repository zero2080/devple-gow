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

	public void run(Scene first) {
		scenes.push(this, first);
		double prev = glfwGetTime();
		double acc = 0;
		while (!window.shouldClose()) {
			double now = glfwGetTime();
			acc += Math.min(now - prev, 0.25);
			prev = now;
			while (acc >= TICK) {
				input.poll();
				scenes.update(this, TICK);
				ticks++;
				acc -= TICK;
			}
			renderer.beginFrame();
			scenes.render(this, (float) (acc / TICK));
			renderer.endFrame(scenes.fade());
			if (++frames == captureFrame || (captureTick > 0 && ticks >= captureTick && captureFile != null)) {
				captureTick = -1;
				capture();
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

	private void capture() {
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
