package devple.gow.engine;

import devple.gow.engine.core.Engine;
import devple.gow.engine.level.LevelScene;
import devple.gow.engine.scene.DebugScene;
import devple.gow.engine.scene.ModelViewerScene;
import devple.gow.engine.scene.Scene;

import java.nio.file.Path;

/**
 * 엔진 진입점.
 * <pre>
 *   ./gradlew :engine:run
 *   ./gradlew :engine:run --args="--screenshot work/shot.png"   (몇 프레임 후 캡처하고 종료)
 *   ./gradlew :engine:run --args="--view gow-rom/R_HERO0.WAD [MDL이름]"   (모델 뷰어)
 *   ./gradlew :engine:run --args="--level gow-rom/ATHN01A.WAD [--cam N|auto] [--col]"   (레벨 배치 뷰어, 23 문서)
 * </pre>
 */
public final class GowMain {

	public static void main(String[] args) {
		String shot = null;
		int levelCam = -1;
		boolean levelCol = false;
		Path level = null;
		Scene first = new DebugScene(0.2f);
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("--screenshot") && i + 1 < args.length) {
				shot = args[++i];
			} else if (args[i].equals("--view") && i + 1 < args.length) {
				Path wad = Path.of(args[++i]);
				String mdl = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : null;
				first = new ModelViewerScene(wad, mdl);
			} else if (args[i].equals("--level") && i + 1 < args.length) {
				level = Path.of(args[++i]);
			} else if (args[i].equals("--cam") && i + 1 < args.length) {
				String c = args[++i];
				levelCam = c.equals("auto") ? -2 : Integer.parseInt(c);
			} else if (args[i].equals("--col")) {
				levelCol = true;
			}
		}
		if (level != null) {
			first = new LevelScene(level, levelCam, levelCol);
		}
		try (Engine engine = new Engine("God of War (재구현)", 1280, 720)) {
			if (shot != null) {
				engine.captureAfter(30, Path.of(shot));
			}
			engine.run(first);
		}
	}
}
