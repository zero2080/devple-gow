package devple.gow.engine.game;

import devple.gow.engine.nav.Platforms;
import devple.gow.engine.script.ScriptActor;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.anim.Mat4;
import devple.gow.tools.content.Content;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 영상 8066–8806 (제우스의 분노 뒤 → 오라클의 사원) 구간 (43 문서 §21).
 * ATHN05D 엘리베이터 (WarpElevator5D) → Warp → ATHN04A (오라클 동영상) 를 실제 데이터로 다시 돈다.
 */
class OracleApproachTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static LevelData level(String name) throws Exception {
		Path p = Content.file(ROM, name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new LevelData(WadFile.load(p));
	}

	private static LevelData.PlacedVolume volume(LevelData lv, String hull) {
		return lv.volumes.stream().filter(v -> v.hull().name.equals(hull)).findFirst().orElseThrow();
	}

	/**
	 * ENV_WarpElevator5D 조각 0 (영역 0 = PlayerVolume0) 은 관절 2 (pressPlate) 에 붙은 r 32 구 (로컬 y 17.55) — 발판 윗면 870 에 선 영웅은 안이다.
	 * 관절 0 (승강로 바닥 713.6) 으로 두면 구 중심이 731 이라 같은 점이 밖이었다 [확실: BallHull off[6] · 데이터].
	 */
	@Test
	void plateSphereSitsOnThePressPlateJoint() throws Exception {
		LevelData lv = level("ATHN05D");
		LevelData.PlacedVolume v = volume(lv, "ENV_WarpElevator5D");
		assertNotNull(v.partInverse(), "관절 0 이 아닌 조각이 있는 볼륨");
		assertEquals(2, v.hull().jointOfHull(0));
		assertEquals(1, v.hull().jointOfHull(1));
		float[] onPlate = {-944, 870.3f, 1096};
		assertTrue(v.regions(onPlate[0], onPlate[1], onPlate[2]).contains(0), "발판 위 = PlayerVolume0");
		assertTrue(v.contains(onPlate[0], onPlate[1], onPlate[2], 0));
		// 예전 판정 (모든 조각을 관절 0 공간으로) — 같은 점이 밖
		float[] l = new float[3];
		Mat4.transformPoint(v.inverse(), onPlate[0], onPlate[1], onPlate[2], l);
		assertFalse(v.hull().regionsAt(l[0], l[1], l[2]).contains(0), "관절 0 공간이면 구 중심이 150 아래");
		// 관절 0 에 붙은 조각 (DownVol2 · UpVol3 · beginFade) 은 그대로
		assertTrue(v.regions(-944, 866, 1096).contains(3), "UpVol3 (관절 0, 로컬 y 148.9..156.9)");
	}

	/**
	 * 발판에 서면 PlayerEntryAtTop → LoadForWarp Athn04A + AnimDown → 눌림 MarkerEntry → beginFade (FadeOut 0.6) → Marker599 가 DownVol2 → Warp.
	 * DownVol2 는 승강로 바닥 668.6–676.6, act 끝 (7 초) Marker599 는 676.2 — 끝 틱에 Marker 를 끝 자세로 옮겨야 든다 ({@link SceneNav#poseBody}).
	 */
	@Test
	void elevatorRideReachesTheWarp() throws Exception {
		LevelData lv = level("ATHN05D");
		List<ScriptListener.LevelRequest> levels = new ArrayList<>();
		List<Boolean> fades = new ArrayList<>();
		ScriptWorld[] w = new ScriptWorld[1];
		Platforms[] ps = new Platforms[1];
		ScriptListener l = new ScriptListener() {
			@Override
			public void level(LevelRequest r) {
				levels.add(r);
			}

			@Override
			public void animate(ScriptEntity source, List<String> objects, String anim) {
				SceneNav.applyAnimator(w[0], ps[0], source, objects, anim);
			}

			@Override
			public void fade(float r, float g, float b, float seconds, boolean out) {
				fades.add(out);
			}
		};
		w[0] = new ScriptWorld(lv, new ScriptHost(), l);
		ps[0] = Platforms.of(lv, SceneNav.animatorOwners(w[0]), Set.of());
		w[0].start();
		Platforms.Body elev = ps[0].body("gowarpelevator5d");
		assertNotNull(elev);
		Map<Platforms.Body, float[][]> posed = new IdentityHashMap<>();
		float dt = 1 / 60f;
		for (int i = 0; i < 30; i++) { // 복도에서 다가옴
			step(w[0], ps[0], lv, elev, posed, dt, -944, 864, 1250);
		}
		assertTrue(levels.isEmpty());
		float warpAt = -1, fadeAt = -1;
		for (int i = 0; i < 60 * 12 && warpAt < 0; i++) {
			float y = elev.joints()[2][13] + 6.26f; // pressPlate 윗면 (CDV 조각 0 로컬 y 6.26) 에 실려 내려감
			step(w[0], ps[0], lv, elev, posed, dt, -944, y, 1096);
			if (fadeAt < 0 && fades.contains(true)) {
				fadeAt = i * dt;
			}
			if (levels.stream().anyMatch(r -> r.op() == ScriptListener.LevelOp.WARP)) {
				warpAt = i * dt;
			}
		}
		assertTrue(levels.stream().anyMatch(r -> r.op() == ScriptListener.LevelOp.LOAD_FOR_WARP && "ATHN04A".equalsIgnoreCase(r.wad())),
				"PlayerEntryAtTop → LoadForWarp Athn04A: " + levels);
		assertTrue(fadeAt > 0, "beginFade (Marker599, 승강로 중간) → 페이드 아웃");
		assertTrue(warpAt > fadeAt, "DownVol2 → Warp (페이드 뒤): fade " + fadeAt + " warp " + warpAt);
		assertTrue(warpAt > 6.5f && warpAt < 8f, "act 7 초 끝에 Warp: " + warpAt);
		ScriptActor marker = w[0].markers("gowarpelevator5d").getFirst();
		assertEquals(676.2f, marker.pos[1], 0.3f, "끝 자세 Marker599");
	}

	private static void step(ScriptWorld w, Platforms ps, LevelData lv, Platforms.Body elev, Map<Platforms.Body, float[][]> posed, float dt,
			float x, float y, float z) {
		ps.update(dt);
		SceneNav.poseBody(w, lv, elev, posed);
		w.tick(dt, x, y, z);
	}

	/**
	 * 이웃 슬롯 세계가 시작 큐에서 낸 적 (ATHN05C group1 하피 — SpawnAtStart · Respawn · 템플릿 70) 의 제거는 그 세계로 가야 한다.
	 * 엔진은 시작 큐를 도는 동안 이웃 자리가 비어 있어 전투 다리 세계를 null 로 잡았고, 제거가 현재 레벨 세계 (ATHN05D) 로 가 Respawn 이 그 리스너로
	 * 다시 냈다 — 현재 레벨에 없는 템플릿이라 첫 적 종류 (R_SKD) 대역으로 3 초마다 (e3 이전 로그) → {@link SceneNav#spawningNeighbour} (43 §21).
	 */
	@Test
	void neighbourStartSpawnsDieInTheirOwnWorld() throws Exception {
		List<ScriptListener.SpawnRequest> mainSpawns = new ArrayList<>(), nbSpawns = new ArrayList<>();
		ScriptHost mainHost = new ScriptHost();
		ScriptWorld main = new ScriptWorld(level("ATHN05D"), mainHost, new ScriptListener() {
			@Override
			public void spawnEnemy(SpawnRequest r) {
				mainSpawns.add(r);
			}
		});
		ScriptWorld nb = new ScriptWorld(level("ATHN05C"), ScriptHost.neighbour(mainHost), new ScriptListener() {
			@Override
			public void spawnEnemy(SpawnRequest r) {
				nbSpawns.add(r);
			}
		});
		main.start();
		nb.start();
		List<ScriptListener.SpawnRequest> harpies = nbSpawns.stream().filter(r -> r.templateId() == 70).toList();
		assertEquals(3, harpies.size(), "group1 하피 1·3·5 (SpawnAtStart)");
		mainSpawns.clear();
		nbSpawns.clear();
		nb.destroy(harpies.get(0).actor()); // 고친 길: 낸 세계로
		for (int i = 0; i < 60; i++) {
			main.tick(1 / 60f, -1440, 864, 2300);
			nb.tick(1 / 60f, -1440, 864, 2300);
		}
		assertTrue(mainSpawns.isEmpty(), "현재 레벨 리스너는 이웃 템플릿을 내지 않는다");
		assertEquals(1, nbSpawns.size(), "Respawn 은 이웃 세계 리스너로 (이웃 스폰 규칙)");
		main.destroy(harpies.get(1).actor()); // 예전 길: 현재 레벨 세계로 → 그 리스너가 템플릿 70 을 낸다 (대역 R_SKD)
		for (int i = 0; i < 60; i++) {
			main.tick(1 / 60f, -1440, 864, 2300);
		}
		assertTrue(mainSpawns.stream().anyMatch(r -> r.templateId() == 70), "예전 결함의 꼴");
	}
}
