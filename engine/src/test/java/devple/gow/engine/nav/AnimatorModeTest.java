package devple.gow.engine.nav;

import devple.gow.engine.game.SceneNav;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Animator 효과 모드 (원작 0x1AB0B8, 42 문서 §3): PAND02AA 회전방 gospinroom1 — 정방향(모드 0)은 0 초부터 끝까지 돌아 벽 관절의
 * Marker 202 를 spinRmEnd 볼륨에 넣고 (LevelData:1 = 참), 역방향(모드 1)은 끝에서 0 으로 돌아 Marker 201 이 spinRmBegin 을 지난다 (거짓).
 */
class AnimatorModeTest {

	static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	@Test
	void spinRoomMarkersReachSensors() throws Exception {
		Assumptions.assumeTrue(Files.exists(ROM.resolve("PAND02AA.WAD")), "gow-rom 없음");
		LevelData lv = new LevelData(WadFile.load(ROM.resolve("PAND02AA.WAD")));
		ScriptWorld w = new ScriptWorld(lv, new ScriptHost(), new ScriptListener() {
		});
		w.start();
		Platforms ps = Platforms.of(lv, SceneNav.animatorOwners(w));
		Platforms.Body room = ps.body("gospinroom1");
		assertNotNull(room, "회전방 = CDV + ANM + Animator 대상");
		w.host().levelData().put(1, false);

		assertTrue(ps.play("gospinroom1", "!0", 0, 0.9f, 0));
		assertEquals(0f, room.time(), 1e-6f);
		float dur = room.anim().duration;
		assertEquals(51.7f, dur, 0.2f, "51.7 초 한 바퀴");
		run(w, ps, dur / 0.9f + 1);
		assertFalse(room.playing(), "한 번 재생 → 끝에서 멈춤");
		assertEquals(dur, room.time(), 1e-3f);
		assertEquals(Boolean.TRUE, w.host().levelData().get(1), "Marker 202 → spinRmEnd (Entry202 → LevelData:1 = 참)");

		assertTrue(ps.play("gospinroom1", "!0", 1, 0.9f, 0), "역방향: 시작 시간 0 → 애니 끝에서");
		assertEquals(dur, room.time(), 1e-3f);
		run(w, ps, dur / 0.9f + 1);
		assertEquals(0f, room.time(), 1e-3f);
		assertEquals(Boolean.FALSE, w.host().levelData().get(1), "Marker 201 → spinRmBegin (Entry201 → 거짓)");

		assertTrue(ps.play("gospinroom1", "!0", 0, 1, 0));
		run(w, ps, 5);
		float t = room.time();
		assertTrue(ps.play("gospinroom1", "!0", 0x10, 1, 0), "0x10 일시 정지");
		run(w, ps, 2);
		assertEquals(t, room.time(), 1e-6f);
		assertTrue(ps.play("gospinroom1", "!0", 0x11, 1, 0), "0x11 이어 재생");
		run(w, ps, 1);
		assertTrue(room.time() > t);
		assertTrue(ps.play("gospinroom1", "!0", 0x8, 1, 0), "0x8 시간 유지");
		assertTrue(room.time() > t);
		float jt = room.time();
		assertTrue(ps.play("gospinroom1", "$chiansYo", 0, 0.9f, 0), "$ = 재질 애니 (MAT_chiansYo 시계, 0x1AB0B8 '$' 갈래)");
		assertEquals(jt, room.time(), 1e-6f, "관절 애니는 그대로");
		assertNotNull(ps.materialTimes().get("gospinroom1").get("MAT_chiansYo"));
		assertFalse(ps.play("gospinroom1", "$noSuchMaterial", 0, 1, 0), "재질 ANM 이 없으면 원작도 \"Can't find material\"");
	}

	private static void run(ScriptWorld w, Platforms ps, float sec) {
		for (float s = 0; s < sec; s += 1 / 30f) {
			ps.update(1 / 30f);
			for (Platforms.Body b : ps.bodies()) {
				w.poseOwner(b.owner, b.joints());
			}
			w.tick(1 / 30f, 1e6f, 1e6f, 1e6f); // 영웅은 멀리 (Marker 만)
		}
	}
}
