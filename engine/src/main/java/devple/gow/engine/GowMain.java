package devple.gow.engine;

import devple.gow.engine.core.Engine;
import devple.gow.engine.scene.AnimViewerScene;
import devple.gow.engine.level.LevelScene;
import devple.gow.engine.scene.DebugScene;
import devple.gow.engine.scene.HeroTestScene;
import devple.gow.engine.scene.ModelViewerScene;
import devple.gow.engine.scene.Scene;
import devple.gow.engine.ui.UiScene;

import java.nio.file.Path;

/**
 * 엔진 진입점.
 * <pre>
 *   ./gradlew :engine:run
 *   ./gradlew :engine:run --args="--screenshot work/shot.png"   (몇 프레임 후 캡처하고 종료)
 *   ./gradlew :engine:run --args="--view gow-rom/R_HERO0.WAD [MDL이름]"   (모델 뷰어)
 *   ./gradlew :engine:run --args="--hero [gow-rom] [--blade N] [--auto run|combo]"   (평지 조작 + 전투 연습)
 *   ./gradlew :engine:run --args="--anim gow-rom/R_HERO0.WAD MDL_hero hero ANM_hero [애니이름]"   (애니메이션 뷰어)
 *   ./gradlew :engine:run --args="--ui gow-rom/R_SHELLK.WAD FLP_ShellK [menu|hud|pause|powerup] [--ui-warm n]"   (FLP UI)
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
			} else if (args[i].equals("--hero")) {
				String rom = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "gow-rom";
				java.util.List<String> al = java.util.Arrays.asList(args);
				int bi = al.indexOf("--blade");
				int ai = al.indexOf("--auto");
				HeroTestScene.Auto auto = ai >= 0 ? HeroTestScene.Auto.valueOf(args[ai + 1].toUpperCase()) : HeroTestScene.Auto.NONE;
				first = new HeroTestScene(Path.of(rom), auto, bi >= 0 ? Integer.parseInt(args[bi + 1]) : 1);
			} else if (args[i].equals("--anim") && i + 4 < args.length) {
				Path wad = Path.of(args[++i]);
				String mdl = args[++i], skel = args[++i], anm = args[++i];
				String start = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : null;
				first = new AnimViewerScene(wad, mdl, skel, anm, start);
			} else if (args[i].equals("--view") && i + 1 < args.length) {
				Path wad = Path.of(args[++i]);
				String mdl = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : null;
				first = new ModelViewerScene(wad, mdl);
			} else if (args[i].equals("--ui") && i + 2 < args.length) {
				Path wad = Path.of(args[++i]);
				String flp = args[++i];
				String mode = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : null;
				first = new UiScene(wad, flp, mode, uiWarm(args));
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
				int fi = java.util.Arrays.asList(args).indexOf("--frames");
				engine.captureAfter(fi >= 0 ? Long.parseLong(args[fi + 1]) : 30, Path.of(shot));
			}
			engine.run(first);
		}
	}

	private static int uiWarm(String[] args) {
		for (int i = 0; i + 1 < args.length; i++) {
			if (args[i].equals("--ui-warm")) {
				return Integer.parseInt(args[i + 1]);
			}
		}
		return 0;
	}
}
