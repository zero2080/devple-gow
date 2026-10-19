package devple.gow.engine;

import devple.gow.engine.core.Engine;
import devple.gow.engine.scene.DebugScene;

/**
 * 엔진 진입점.
 * <pre>
 *   ./gradlew :engine:run
 *   ./gradlew :engine:run --args="--screenshot work/shot.png"   (몇 프레임 후 캡처하고 종료)
 * </pre>
 */
public final class GowMain {

	public static void main(String[] args) {
		String shot = null;
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("--screenshot") && i + 1 < args.length) {
				shot = args[++i];
			}
		}
		try (Engine engine = new Engine("God of War (재구현)", 1280, 720)) {
			if (shot != null) {
				engine.captureAfter(30, java.nio.file.Path.of(shot));
			}
			engine.run(new DebugScene(0.2f));
		}
	}
}
