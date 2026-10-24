package devple.gow.engine.script;

import devple.gow.tools.anim.Mat4;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ATHN03AA "아테네로 향한 도로" 스크립트 (43 문서 §16): 구역 제목 Internal 0x10 과 오라클 영상 앞뒤의 화면 페이드 (Entity 0x5 / 0x6).
 */
class RoadToAthensScriptTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static LevelData level(String wad) throws Exception {
		Path p = ROM.resolve(wad);
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new LevelData(WadFile.load(p));
	}

	/** 통지 기록 (구역 제목 · 페이드 · 영상 · 레벨). */
	static final class Rec implements ScriptListener {
		final List<String> log = new ArrayList<>();

		@Override
		public void internal(int func, List<Object> args) {
			if (func == GameScriptHost.ZONE_REPORT) {
				log.add("zone " + args);
			}
		}

		@Override
		public void fade(float r, float g, float b, float seconds, boolean out) {
			log.add(String.format(java.util.Locale.ROOT, "fade %s %.1f (%.0f,%.0f,%.0f)", out ? "out" : "in", seconds, r, g, b));
		}

		@Override
		public void movie(String name, int op, int arg) {
			log.add("movie " + name + " " + op);
		}

		@Override
		public void level(LevelRequest r) {
			log.add("level " + r.op() + " " + r.wad());
		}
	}

	private static float[] centre(ScriptEntity e) {
		LevelData.PlacedVolume v = e.volume;
		float[] c = new float[3];
		Mat4.transformPoint(v.world(), v.hull().sphere[0], v.hull().sphere[1], v.hull().sphere[2], c);
		assertTrue(v.contains(c[0], c[1], c[2]), e.name() + " 볼륨 중심");
		return c;
	}

	private static ScriptEntity entity(ScriptWorld w, String name) {
		return w.entities().stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
	}

	/**
	 * ZoneReportGroup (계단 위 구) 진입: GlobalData:0 ≠ 3 이면 Internal 0x10 ZoneReport(3) → 메시지 803 "아테네로 향한 도로" (GameScene.zoneReport,
	 * 0x1B0A28 → 0x1B8BA0), 그리고 GlobalData:0 = 3 이라 다시 들어가도 한 번뿐 (PS2 영상 4788–4796 계단 위 제목).
	 */
	@Test
	void zoneTitleOnceOnTheStairs() throws Exception {
		Rec r = new Rec();
		ScriptHost host = new ScriptHost();
		ScriptWorld w = new ScriptWorld(level("ATHN03AA.WAD"), host, r);
		w.start();
		float[] s = w.startEntity().position();
		float[] c = centre(entity(w, "ZoneReportGroup-nurbsSphere2-EntrySensor1"));
		for (int k = 0; k < 2; k++) {
			for (int i = 0; i < 30; i++) {
				w.tick(1 / 60f, s[0], s[1], s[2]);
			}
			for (int i = 0; i < 30; i++) {
				w.tick(1 / 60f, c[0], c[1], c[2]);
			}
		}
		assertEquals(List.of("zone [3]"), r.log.stream().filter(x -> x.startsWith("zone")).toList(), r.log.toString());
		assertEquals(3, ((Number) host.global("ZoneArea")).intValue(), "GlobalData:0 (ATHN03AA 의 ZoneArea) = 3");
	}

	/**
	 * 오라클 영상 a03Mp02MPEG: idle 볼륨 진입 → FadeOut(0, 0, 0, 0.4) → 0.4 초 뒤 PlayMovie a03Mp02 → 영상 끝 (501, "a03Mp02") → FadeIn(1) +
	 * 0.3 초 뒤 LoadWad Athn03A (Vis 지연 = H11) [확실: 스크립트]. 예전 엔진은 Entity 0x5/0x6 을 버려 영상 앞뒤가 바로 잘렸다.
	 */
	@Test
	void oracleMovieFadesOutAndBackIn() throws Exception {
		Rec r = new Rec();
		ScriptWorld w = new ScriptWorld(level("ATHN03AA.WAD"), new ScriptHost(), r);
		w.start();
		float[] c = centre(entity(w, "a03Mp02MPEG-pCube1-idle"));
		for (int i = 0; i < 60; i++) {
			w.tick(1 / 60f, c[0], c[1], c[2]);
		}
		int fadeOut = r.log.indexOf("fade out 0.4 (0,0,0)");
		int movie = r.log.indexOf("movie a03Mp02 0");
		assertTrue(fadeOut >= 0 && movie > fadeOut, "페이드 아웃 뒤 영상: " + r.log);
		r.log.clear();
		w.postEvent(ScriptWorld.EVENT_MOVIE_DONE, "a03Mp02", 0, 0, w.player()); // EvtMpegDone Filter 1 = 보낸 이 플레이어 (GameScene.playMovie 와 같음)
		for (int i = 0; i < 60; i++) {
			w.tick(1 / 60f, c[0], c[1], c[2]);
		}
		assertTrue(r.log.contains("fade in 1.0 (0,0,0)"), "영상 끝 FadeIn(1): " + r.log);
	}
}
