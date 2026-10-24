package devple.gow.engine.game;

import devple.gow.tools.anim.Animation;
import devple.gow.tools.fx.EffectLibrary;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** ATHN03AA / ATHN03A "아테네로 향한 도로" QA (43 문서 §16): 구역 제목 문자열, 스크립트 화면 페이드, 세이브 제단 몸·R2 거리. */
class RoadToAthensTest {

	private static WadFile wad(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	/** ZoneReport(3) → 메시지 803 "아테네로 향한 도로\n0" 의 첫 줄 (0x1B95D0 줄 0), 없는 구역은 "Unknown zone" (0x1B8BA0). */
	@Test
	void zoneTitleIsTheFirstLineOfMessage800PlusZone() throws Exception {
		MessageTable m = MessageTable.fromWad(wad("R_PERMK")).orElseThrow();
		assertEquals("아테네로 향한 도로", GameScene.zoneTitle(m, 3));
		assertEquals("아테네 성문", GameScene.zoneTitle(m, 2));
		assertEquals("Unknown zone", GameScene.zoneTitle(m, 0), "메시지 800 없음");
		assertEquals("Unknown zone", GameScene.zoneTitle(null, 3));
	}

	/**
	 * FadeOut / FadeIn 상태 0x303D50 (0x1AFA20 / 0x1AFAD4 / 매 프레임 0x1AF648): 프레임 수 n = cvt.w.s(초 / 0.016666668) (float 나눗셈, 버림 —
	 * 1 초 = 59, 0.4 초 = 24 [근사: EE FPU 는 0 쪽 반올림이라 0.4 초가 23 일 수 있다]). FadeOut = n 프레임 동안 알파 0 → 1, 끝나도 덮인 채,
	 * FadeIn = n 프레임 1 → 0 뒤 상태 0. 어둡게 하는 중의 FadeIn 은 진행을 뒤집어 잇는다 (현재 = (전체 − 현재)·n / 전체, 정수).
	 */
	@Test
	void scriptFadeFollowsTheOriginalFrameCounter() {
		int n04 = (int) (0.4f / ScreenFade.FRAME), n1 = (int) (1f / ScreenFade.FRAME);
		assertEquals(24, n04);
		assertEquals(59, n1, "1 초 / 0.016666668 = 59.999996 → 59");
		ScreenFade f = new ScreenFade();
		f.tick();
		assertEquals(0f, f.color()[3], "상태 0 = 안 그림");
		f.fadeIn(1f);
		assertEquals(0, f.state(), "어둡게 하지 않았으면 FadeIn 무시");
		f.fadeOut(0.2f, 0.1f, 0f, 0.4f);
		assertEquals(1, f.state());
		f.tick();
		assertEquals(0f, f.color()[3], 1e-6f, "첫 프레임 0/24");
		for (int i = 0; i < 12; i++) {
			f.tick();
		}
		assertEquals(12f / n04, f.color()[3], 1e-6f);
		f.fadeOut(1, 1, 1, 5f);
		assertEquals(0.2f, f.color()[0], 1e-6f, "어둡게 하는 중의 FadeOut 은 무시 (상태 1)");
		for (int i = 0; i < 20; i++) {
			f.tick();
		}
		assertEquals(1f, f.color()[3], 1e-6f, "끝나면 덮인 채");
		assertEquals(1, f.state());
		f.fadeIn(1f);
		f.tick();
		assertEquals(1f, f.color()[3], 1e-6f, "밝게 첫 프레임 1 − 0/n");
		for (int i = 0; i < 30; i++) {
			f.tick();
		}
		assertEquals(1f - 30f / n1, f.color()[3], 1e-5f);
		for (int i = 0; i < n1 - 30 + 1; i++) {
			f.tick();
		}
		assertEquals(0, f.state(), "밝게 끝 → 상태 0");
		// 어둡게 하는 중 (12/24) 의 FadeIn(1): 현재 = (24 − 12)·59/24 = 29 → 알파 1 − 29/59 (≈ 0.51, 이어짐)
		ScreenFade g = new ScreenFade();
		g.fadeOut(0, 0, 0, 0.4f);
		for (int i = 0; i < 12; i++) {
			g.tick();
		}
		g.fadeIn(1f);
		g.tick();
		assertEquals(1f - (float) ((n04 - 12) * n1 / n04) / n1, g.color()[3], 1e-6f);
	}

	/**
	 * 세이브 제단 (Creator Name0 "savepoint"): 원작 0x1ACCA8 이 R_PERMK 오브젝트 savepoint (MDL_SavePoint 빛기둥 + ANM "SavePoint" 4 초 반복 +
	 * SEM_SNDSavePoint) 를 만든다. R2 안내·저장 거리 = 4 m (0x1DC678 길이 × 1/16 < 4.0, 0x1DC3F8) = 64 단위.
	 */
	@Test
	void savePointBodyAndRange() throws Exception {
		assertEquals(64f, GameScene.SAVE_POINT_RANGE, 1e-6f);
		EffectLibrary lib = new EffectLibrary();
		lib.add(wad("R_PERMK"));
		EffectLibrary.EffectDef d = lib.resolve("savepoint");
		assertNotNull(d, "R_PERMK savepoint");
		assertEquals("MDL_SavePoint", d.model());
		assertEquals("SEM_SNDSavePoint", d.sound());
		Animation a = d.act("SavePoint");
		assertNotNull(a);
		assertTrue(a.looping());
		assertEquals(4f, a.duration, 1e-4f);
		assertFalse(d.oneShot(), "반복 = 소유자가 끌 때까지");
	}
}
