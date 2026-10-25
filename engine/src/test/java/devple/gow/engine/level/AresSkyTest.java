package devple.gow.engine.level;

import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.fx.EnvParams;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SCR_AresSky (ATHN03D/03E 골짜기의 거대한 아레스, 영상 986–987, 43 §30): 하늘 GO 이고 (0x218968 → 0x218848) act 를 무작위로 바꾼다 (0x2189A8).
 */
class AresSkyTest {

	private static WadFile wad(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	/** ATHN03D/03E goaresanimgroup1 은 SCR_AresSky 소유자 → 하늘 이름 (관절 0 = 눈). 예전엔 월드 원점에 놓인 보통 GO 였다. */
	@Test
	void aresSkyOwnerIsASkyObject() throws Exception {
		for (String l : List.of("ATHN03D", "ATHN03E")) {
			WadFile w = wad(l);
			EnvParams env = new EnvParams(w);
			assertTrue(env.aresSkyOwners.stream().anyMatch(o -> o.equalsIgnoreCase("goaresanimgroup1")), l + ": " + env.aresSkyOwners);
			Set<String> names = LevelScene.skyNames(env);
			LevelData d = new LevelData(w);
			List<String> sky = d.layout.placements().stream().filter(p -> p.model() != null && LevelScene.isScrSky(p, names))
					.map(p -> p.instance().name().toLowerCase(Locale.ROOT)).sorted().toList();
			assertEquals(List.of("goaresanimgroup1", "gogroup2"), sky, l);
		}
	}

	private static List<Animation> aresActs(String level) throws Exception {
		WadFile w = wad(level);
		LevelData d = new LevelData(w);
		LevelLayout.Placement p = d.layout.placements().stream().filter(x -> x.instance().name().equalsIgnoreCase("goaresanimgroup1"))
				.findFirst().orElseThrow();
		assertEquals(0, p.instance().startAct(), "시작 act 0 main (인스턴스 0x54)");
		return AnimationSet.load(w, p.object().refs("ANM_").getFirst()).all();
	}

	/**
	 * 0x2189A8: act 가 끝나거나 (d ≤ t) 감기면 (t &lt; 직전 t) [0, act 수) 에서 직전에 고른 것과 다른 act. 시작 act 0 main (0.033 초, 관절 트랙 없음 — 메시 숨김)
	 * 은 곧바로 바뀌고, 반복 act Hl01 (8.83 초) 은 감길 때, 1 회 act 는 끝날 때 바뀐다 (바꾼 다음 프레임은 새 act 시간이 직전 t 보다 작아 한 번 더).
	 */
	@Test
	void actsChangeAtRandomWhenTheyEndOrWrap() throws Exception {
		List<Animation> acts = aresActs("ATHN03D");
		assertEquals(5, acts.size());
		assertEquals("main", acts.get(0).name);
		assertFalse(acts.get(0).hasSkeletalData());
		assertTrue(acts.get(1).looping(), "Hl01 반복");
		AresSkyActs a = new AresSkyActs(acts, 0, 0, 1234);
		a.advanceTo(0);
		assertEquals(0, a.actIndex());
		assertFalse(a.visible(), "main 동안 숨김");
		a.advanceTo(0.1f);
		assertTrue(a.switches() >= 1, "main 은 2 프레임 뒤 바뀜");
		// 300 초: 바뀔 때마다 그 앞 act 의 재생 시간 — 1 회 act 는 길이까지, 반복 act 는 한 바퀴, 한 프레임짜리 (두 번째 바꿈) 는 짧다
		Set<Integer> seen = new HashSet<>();
		List<float[]> runs = new ArrayList<>();
		int cur = a.actIndex();
		float start = 0.1f, t = 0.1f;
		for (int i = 0; i < 300 * 60; i++) {
			t += AresSkyActs.STEP;
			a.advanceTo(t);
			if (a.actIndex() != cur || a.time() < 2 * AresSkyActs.STEP && t - start > 2 * AresSkyActs.STEP) {
				runs.add(new float[]{cur, t - start});
				cur = a.actIndex();
				start = t - a.time();
			}
			seen.add(a.actIndex());
		}
		assertEquals(Set.of(0, 1, 2, 3, 4), seen, "다섯 act 모두");
		int longRuns = 0;
		for (float[] r : runs) {
			Animation x = acts.get((int) r[0]);
			if (r[1] > 0.1f) {
				longRuns++;
				assertEquals(x.duration, r[1], 0.05f, x.name + " 는 길이만큼 (" + r[1] + ")");
			}
		}
		assertTrue(longRuns > 20, "300 초에 act 수십 번: " + longRuns);
	}

	/** act 가 하나뿐이면 바꾸지 않는다 (0x218A3C: 수 &lt; 2). */
	@Test
	void singleActNeverSwitches() throws Exception {
		List<Animation> acts = aresActs("ATHN03E");
		AresSkyActs a = new AresSkyActs(List.of(acts.get(1)), 0, 0, 1);
		for (int i = 1; i <= 60 * 30; i++) {
			a.advanceTo(i * AresSkyActs.STEP);
		}
		assertEquals(0, a.switches());
		assertEquals(0, a.actIndex());
		assertTrue(a.time() < acts.get(1).duration, "반복 act 는 감긴다");
	}
}
