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
 *   ./gradlew :engine:run                                         (메인 메뉴 → 새 게임 → ATHN01A)
 *   ./gradlew :engine:run --args="--screenshot work/shot.png"   (몇 프레임 후 캡처하고 종료)
 *   ./gradlew :engine:run --args="--view gow-rom/R_HERO0.WAD [MDL이름]"   (모델 뷰어)
 *   ./gradlew :engine:run --args="--hero [gow-rom] [--blade N] [--auto run|combo|magic] [--enemy R_SKS]"   (평지 조작 + 전투 연습)
 *   ./gradlew :engine:run --args="--anim gow-rom/R_HERO0.WAD MDL_hero hero ANM_hero [애니이름]"   (애니메이션 뷰어)
 *   ./gradlew :engine:run --args="--ui gow-rom/R_SHELLK.WAD FLP_ShellK [menu|hud|pause|powerup] [--ui-warm n]"   (FLP UI)
 *   ./gradlew :engine:run --args="--level gow-rom/ATHN01A.WAD [--cam N|auto] [--col]"   (레벨 배치 뷰어, 23 문서)
 *   ./gradlew :engine:run --args="--game [ATHN01A] [--auto-run s] [--entry 인스턴스]"   (통합 게임: 레벨 + 크레토스 + 전투 + HUD)
 *   ./gradlew :engine:run --args="--game ATHN01E --entry gospawndaddyhydra --boss-demo"   (보스전 자동 연출, 37 문서)
 *   ./gradlew :engine:run --args="--movie SEMO01 [--wide] [--movie-start 초] [--mute]"   (컷신 재생, 33 문서)
 * </pre>
 */
public final class GowMain {

	public static void main(String[] args) {
		String shot = null;
		int levelCam = -1;
		boolean levelCol = false;
		Path level = null;
		// 인자 없이 실행 = 원작처럼 메인 메뉴부터
		Scene first = java.nio.file.Files.exists(Path.of("gow-rom", "R_SHELLK.WAD"))
				? new devple.gow.engine.game.ShellScene(Path.of("gow-rom")) : new DebugScene(0.2f);
		if (java.util.Arrays.asList(args).contains("--boss-demo")) {
			System.setProperty("gow.boss.demo", "1"); // 보스전 자동 연출 (37 문서)
		}
		if (java.util.Arrays.asList(args).contains("--skip-movies")) {
			System.setProperty("gow.skipmovies", "1"); // 컷신 건너뜀 (끝 이벤트 501 만)
		}
		if (java.util.Arrays.asList(args).contains("--no-fx")) {
			System.setProperty("gow.nofx", "1"); // 비·파티클 끔 (스크린샷 확인용)
		}
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("--screenshot") && i + 1 < args.length) {
				shot = args[++i];
			} else if (args[i].equals("--game")) {
				String lvl = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "ATHN01A";
				int ri = java.util.Arrays.asList(args).indexOf("--auto-run");
				int ei = java.util.Arrays.asList(args).indexOf("--entry"); // 시작 인스턴스/오브젝트 이름 (진입점)
				try {
					var session = new devple.gow.engine.game.GameSession(Path.of("gow-rom"));
					int ci = java.util.Arrays.asList(args).indexOf("--costume"); // 코스튬 n (R_HERO<n>)
					session.costume = ci >= 0 ? Integer.parseInt(args[ci + 1]) : 0;
					first = new devple.gow.engine.game.GameScene(session, lvl,
							ei >= 0 ? args[ei + 1] : null, ri >= 0 ? Float.parseFloat(args[ri + 1]) : 0);
				} catch (java.io.IOException ex) {
					throw new java.io.UncheckedIOException(ex);
				}
			} else if (args[i].equals("--hero")) {
				String rom = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "gow-rom";
				java.util.List<String> al = java.util.Arrays.asList(args);
				int bi = al.indexOf("--blade");
				int ai = al.indexOf("--auto");
				HeroTestScene.Auto auto = ai >= 0 ? HeroTestScene.Auto.valueOf(args[ai + 1].toUpperCase()) : HeroTestScene.Auto.NONE;
				int ei = al.indexOf("--enemy");
				first = new HeroTestScene(Path.of(rom), auto, bi >= 0 ? Integer.parseInt(args[bi + 1]) : 1, ei >= 0 ? args[ei + 1] : null);
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
			} else if (args[i].equals("--movie") && i + 1 < args.length) {
				first = movieScene(args[++i], args);
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
				int ti = java.util.Arrays.asList(args).indexOf("--ticks"); // 로직 틱(1/60초) 기준 — 렌더 속도와 무관
				if (ti >= 0) {
					engine.captureAfterTicks(Long.parseLong(args[ti + 1]), Path.of(shot));
				} else {
					engine.captureAfter(fi >= 0 ? Long.parseLong(args[fi + 1]) : 30, Path.of(shot));
				}
			}
			engine.run(first);
		}
	}

	/** --movie NAME [--wide] [--movie-start s] [--mute]: 단독 재생 후 창을 닫는다. */
	private static Scene movieScene(String name, String[] args) {
		java.util.List<String> al = java.util.Arrays.asList(args);
		boolean wide = al.contains("--wide");
		int si = al.indexOf("--movie-start");
		Path rom = Path.of("gow-rom");
		try {
			devple.gow.tools.video.MovieSource src = devple.gow.tools.video.MovieSource.open(name, rom,
					devple.gow.engine.video.MoviePlayer.isoPath(rom), wide);
			if (src == null) {
				throw new IllegalArgumentException("동영상 없음: " + name);
			}
			System.setProperty("gow.movie.stats", "1");
			return new devple.gow.engine.video.MovieScene(new devple.gow.engine.video.MoviePlayer(src, src.name().endsWith(".PSW"),
					si >= 0 ? Double.parseDouble(args[si + 1]) : 0, 1f, al.contains("--mute")), false, null);
		} catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
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
