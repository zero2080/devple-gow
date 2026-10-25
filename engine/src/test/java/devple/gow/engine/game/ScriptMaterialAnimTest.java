package devple.gow.engine.game;

import devple.gow.engine.nav.Platforms;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.fx.MaterialAnim;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 스크립트 재질 애니 (Animator 슬롯 0 "$이름", 원작 0x1AB0B8 '$' 갈래, 43 문서 §14): ATHN02D 미노타우로스 뒤 남쪽 문은 감산 재질 MAT_doorSub
 * (p28 0.5 × 색 2) 이 통로를 검게 덮고, 미노타우로스를 다 잡으면 subShaderAnim1 / doorFadeAnim 이 그 재질 ANM 을 틀어 6–7.5 초에 p28 → 0.
 * 예전 엔진은 "$" 를 무시해 PS2 영상 3126 초의 열린 통로 대신 검은 막이 남았다.
 */
class ScriptMaterialAnimTest {

	static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	static LevelData level(String wad) throws Exception {
		Assumptions.assumeTrue(Files.exists(ROM.resolve(wad)), "gow-rom 없음");
		return new LevelData(WadFile.load(ROM.resolve(wad)));
	}

	private static Map<String, Float> head(Platforms ps) {
		return ps.materialTimes().get("gomatalkinghead01");
	}

	private static void run(Platforms ps, float sec) {
		for (float s = 0; s < sec; s += 1 / 60f) {
			ps.update(1 / 60f);
		}
	}

	@Test
	void doorMaterialsFadeOnlyWhenTheScriptPlaysThem() throws Exception {
		LevelData lv = level("ATHN02D.WAD");
		ScriptWorld w = new ScriptWorld(lv, new ScriptHost(), new ScriptListener() {
		});
		Platforms ps = Platforms.of(lv, SceneNav.animatorOwners(w));
		SceneNav.registerMaterialAnimators(w, ps);
		assertNotNull(head(ps), "MAtalkingHead01 의 '$' Animator 4 개 → 재질 시계");
		// 아직 틀지 않은 0x42 재질 = NaN (렌더: 프레임·UV·불투명도는 시간 0, 색 트랙은 걸지 않음 — MaterialFx.times, 43 §33), 0x41 은 시간
		assertEquals(Map.of("MAT_doorSub", Float.NaN, "MAT_M01DoorFade1", 0f, "MAT_M01AphroFront", Float.NaN, "MAT_SubScrollTex1", Float.NaN), head(ps));

		// 생성 상태: act 0x42 (doorSub) 는 시간 0 에 서 있고, 0x41 (M01DoorFade1 불꽃 흐름) 은 반복
		run(ps, 10);
		assertTrue(Float.isNaN(head(ps).get("MAT_doorSub")), "0x42 = 스크립트가 틀 때까지 (예전 렌더는 레벨 시간으로 저절로 재생)");
		assertArrayEquals(new float[]{0f, -1f}, devple.gow.engine.fx.MaterialFx.times(head(ps).get("MAT_doorSub")), "불투명도 시간 0 (p28 0.5), 색 트랙 끔");
		float loop = head(ps).get("MAT_M01DoorFade1");
		assertTrue(loop > 0 && loop < 7.67f, "0x41 반복 " + loop);

		// 미노타우로스 뒤 MedusaRmEnemies → subShaderAnim1 "$doorSub" 모드 0 (한 번)
		ScriptEntity e = w.find("MAtalkingHead01-main-subShaderAnim1");
		assertEquals("$doorSub", w.slot(e, 0));
		SceneNav.applyAnimator(w, ps, e, List.of(e.ownerName()), String.valueOf(w.slot(e, 0)));
		run(ps, 3);
		assertEquals(3f, head(ps).get("MAT_doorSub"), 0.05f);
		run(ps, 6);
		MaterialAnim.Anim sub = MaterialAnim.load(lv.wad).get("MAT_doorSub");
		float end = head(ps).get("MAT_doorSub");
		assertEquals(sub.duration(), end, 1e-3f, "한 번 재생은 끝에서 멈춤");
		assertEquals(0.5f, sub.value("hdr8", 0, 1), 1e-4f, "시작 p28 0.5 = MAT 헤더 값");
		assertEquals(0f, sub.value("hdr8", end, 1), 1e-4f, "끝 p28 0 → 감산 막이 사라짐");

		// 이음 전환 (ATHN02D → 숙주 ATHN02C, 떠나는 레벨은 다른 슬롯) 에도 열린 문 그대로 (Platforms.State "$MAT_…")
		Platforms carried = Platforms.of(lv, SceneNav.animatorOwners(w));
		carried.restore(ps.states());
		assertEquals(end, carried.materialTimes().get("gomatalkinghead01").get("MAT_doorSub"), 1e-6f);
	}

	@Test
	void materialNameFollowsTheOriginalPrefixRule() {
		assertEquals("MAT_doorSub", Platforms.materialName("$doorSub"));
		assertEquals("MAT_M01DoorFade1", Platforms.materialName("$ \tM01DoorFade1"), "앞 공백·탭 건너뜀 (0x1AB228)");
		assertEquals("MAT_M02sfxLionFaceFire02Blue", Platforms.materialName("$M02sfxLionFaceFire02Blue"));
		assertEquals(0x1F, Platforms.materialName("$abcdefghijklmnopqrstuvwxyz0123456789").length(), "버퍼 0x20, [0x1F] = 0 → 31 자까지");
	}
}
