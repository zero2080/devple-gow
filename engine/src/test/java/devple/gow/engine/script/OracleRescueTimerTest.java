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
 * GameMgr 스크립트 타이머 (Internal 0x14–0x19, 0x3368F8) 와 ATHN06C 오라클 구출 70 초 (43 문서 §23): 영상 a06Mp03 이 끝나면 (카메라 UprCam21, Internal 0xD)
 * 체크포인트 · 안내 523 · 70 초 타이머를 HUD 모래시계에, 시간이 다 되면 1050 → 오라클이 떨어지고 6.4 초 뒤 실패 화면 (0x13), 구하면 타이머를 지우고
 * 동영상 Semo03.
 */
class OracleRescueTimerTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));
	private static final float DT = 1 / 60f;

	@Test
	void timerTable() {
		ScriptTimers t = new ScriptTimers();
		assertEquals(1, t.create(70));
		assertEquals(2, t.create(1));
		assertNull(t.hud(), "0x19 전에는 모래시계 없음");
		t.show(1);
		assertArrayEquals(new int[]{0, 70}, t.hud());
		List<Integer> fired = new ArrayList<>();
		for (int i = 0; i < 180; i++) {
			t.tick(DT, fired::add);
		}
		assertEquals(List.of(2), fired, "1 초 타이머 2 번만 끝 (0x41A, 인자 = 번호)");
		assertFalse(t.active(2));
		assertArrayEquals(new int[]{4, 67}, t.hud(), "3 초 뒤: 경과 100 − (int)(67/70×100 + 0.5) = 4 %, 남은 (int)(67 + 0.5) (영상 9699.6 \"67\")");
		assertEquals(2, t.create(5), "비운 칸 (2) 을 다시 쓴다");
		t.pause(1);
		float before = t.remaining(1);
		t.tick(1f, fired::add);
		assertEquals(before, t.remaining(1), 1e-6, "0x15 멈춤 비트 2: 줄지 않음");
		t.resume(1);
		t.tick(1f, fired::add);
		assertEquals(before - 1f, t.remaining(1), 1e-4, "0x16 다시 흐름");
		t.show(0);
		assertNull(t.hud(), "0x19(0) = 표시 비트를 모두 지움");
		t.destroy(1);
		assertFalse(t.active(1), "0x17 은 이벤트 없이 지움");
		assertEquals(List.of(2), fired);
		assertEquals(1, t.create(1));
		assertEquals(3, t.create(1));
		assertEquals(4, t.create(1));
		assertEquals(0, t.create(1), "칸 4 개가 다 차면 0 [근사: 원작은 표 밖에 쓴다]");
	}

	/** 마지막 몇 초: 경과 % 가 91 이 되면 (남은 6.65 초 미만) FLP HourGlassMeter 가 프레임 93 — 붉은 깜박임 clip#3 (프레임 92 부터) 이 겹친다. */
	@Test
	void redWarningFromSixSeconds() {
		ScriptTimers t = new ScriptTimers();
		t.show(t.create(70));
		t.tick(63f, x -> {
		});
		assertArrayEquals(new int[]{90, 7}, t.hud(), "남은 7 초: 경과 90 % · 7 (영상 9760.25 흰색 7)");
		t.tick(0.6f, x -> {
		});
		assertArrayEquals(new int[]{91, 6}, t.hud(), "남은 6.4 초: 경과 91 % → 붉은 6 (영상 9760.5)");
	}

	private static LevelData level(String wad) throws Exception {
		Path p = ROM.resolve(wad);
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new LevelData(WadFile.load(p));
	}

	static final class Rec implements ScriptListener {
		final List<String> log = new ArrayList<>();
		String camera = "UprCam21";

		@Override
		public String cameraName(boolean previous) {
			return previous ? "UprCam3" : camera;
		}

		@Override
		public void message(int msgId, float seconds) {
			log.add("message " + msgId);
		}

		@Override
		public void internal(int func, List<Object> args) {
			if (func == 0x13) {
				log.add("failed");
			}
		}

		@Override
		public void movie(String name, int op, int arg) {
			log.add("movie " + name + " " + op);
		}

		@Override
		public void checkpoint(ScriptEntity source) {
			log.add("checkpoint " + source.name());
		}
	}

	private static ScriptEntity entity(ScriptWorld w, String name) {
		return w.entities().stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
	}

	private static float[] centre(ScriptEntity e) {
		LevelData.PlacedVolume v = e.volume;
		float[] c = new float[3];
		Mat4.transformPoint(v.world(), v.hull().sphere[0], v.hull().sphere[1], v.hull().sphere[2], c);
		assertTrue(v.contains(c[0], c[1], c[2]), e.name() + " 볼륨 중심");
		return c;
	}

	/** 한 프레임: GameScene 처럼 세계 → 타이머 (다 되면 1050, 보낸 쪽 = 영웅). */
	private static void frame(ScriptWorld w, ScriptHost host, float[] p) {
		w.tick(DT, p[0], p[1], p[2]);
		host.timers.tick(DT, id -> w.postEvent(ScriptTimers.EVENT, id, 0, 0, w.player()));
	}

	private static ScriptWorld rescueStarted(ScriptHost host, Rec r, float[] stand) throws Exception {
		ScriptWorld w = new ScriptWorld(level("ATHN06C.WAD"), host, r);
		w.start();
		for (int i = 0; i < 10; i++) {
			frame(w, host, stand);
		}
		r.log.clear();
		w.postEvent(ScriptWorld.EVENT_MOVIE_DONE, "a06Mp03", 0, 0, w.player()); // 덩굴 카메라 UprCam21 → a06Mp03 끝 (GameScene.playMovie)
		for (int i = 0; i < 10; i++) {
			frame(w, host, stand);
		}
		return w;
	}

	/**
	 * a06Mp03Done (영상 이름 && (카메라 UprCam21 || UprCam3)): 체크포인트 · L7 · L12 · 안내 523 "건너가려면 가장자리에서 ✕" (영상 9698), 그리고
	 * TIMERoracl-startTimer = CreateTimer(LevelData4 = 70) + 0x19 → 모래시계 70 초. 예전 엔진은 0xD 가 "" 라 조건이 거짓이어서 이 모두가 없었다.
	 */
	@Test
	void movieEndStartsTheSeventySecondHourglass() throws Exception {
		ScriptHost host = new ScriptHost();
		Rec r = new Rec();
		float[] stand = entity(new ScriptWorld(level("ATHN06C.WAD"), new ScriptHost(), null), "Start").position();
		ScriptWorld w = rescueStarted(host, r, stand);
		assertTrue(r.log.contains("message 523"), r.log.toString());
		assertTrue(r.log.contains("checkpoint oraclPuzzle-a06Mp03Done"), r.log.toString());
		assertEquals(true, host.levelData().get(7));
		assertEquals(true, host.levelData().get(12));
		int[] hud = host.timers.hud();
		assertNotNull(hud, "TIMERoracl 이 0x19 로 모래시계에");
		assertEquals(70, hud[1]);

		Rec other = new Rec();
		other.camera = "LwrCam10";
		ScriptHost h2 = new ScriptHost();
		rescueStarted(h2, other, stand);
		assertFalse(other.log.contains("message 523"), "카메라가 UprCam21/UprCam3 가 아니면 (0xD) 시작하지 않음");
		assertNull(h2.timers.hud());
	}

	/** 70 초가 다 되면: TIMERoracl-Timer (1050, 인자 = L14) → L16 · L0, oraclFallDIE (L9) …, 6.4 초 뒤 heroDIE → L10 + FailedScreen. */
	@Test
	void timeOutDropsTheOracleThenFailedScreen() throws Exception {
		ScriptHost host = new ScriptHost();
		Rec r = new Rec();
		float[] stand = entity(new ScriptWorld(level("ATHN06C.WAD"), new ScriptHost(), null), "Start").position();
		ScriptWorld w = rescueStarted(host, r, stand);
		r.log.clear();
		for (int i = 0; i < 69 * 60; i++) {
			frame(w, host, stand);
		}
		assertNotEquals(true, host.levelData().get(9), "69 초: 아직 매달려 있음");
		for (int i = 0; i < 2 * 60; i++) {
			frame(w, host, stand);
		}
		assertEquals(true, host.levelData().get(16), "1050 → TIMERoracl-Timer");
		assertEquals(true, host.levelData().get(9), "oraclFallDIE");
		assertFalse(r.log.contains("failed"), "실패 화면은 6.4 초 뒤");
		for (int i = 0; i < 7 * 60; i++) {
			frame(w, host, stand);
		}
		assertTrue(r.log.contains("failed"), r.log.toString());
		assertEquals(true, host.levelData().get(10));
	}

	/** 접시에 닿으면 (saveOraclTrig, L9 거짓): L3 · GlobalData0, TIMERoracl-stopTimer (DestroyTimer) → 모래시계 사라짐, 0.1 초 뒤 동영상 Semo03 (영상 9761). */
	@Test
	void reachingTheOracleClearsTheTimer() throws Exception {
		ScriptHost host = new ScriptHost();
		Rec r = new Rec();
		float[] stand = entity(new ScriptWorld(level("ATHN06C.WAD"), new ScriptHost(), null), "Start").position();
		ScriptWorld w = rescueStarted(host, r, stand);
		for (int i = 0; i < 60 * 60; i++) {
			frame(w, host, stand);
		}
		int[] hud = host.timers.hud();
		assertNotNull(hud);
		assertEquals(10, hud[1], "60 초 뒤 남은 10 초 (영상 9757 \"10\")");
		float[] dish = centre(entity(w, "oraclPuzzle-saveOraclTrig-saveOrcle"));
		r.log.clear();
		for (int i = 0; i < 30; i++) {
			frame(w, host, dish);
		}
		assertEquals(true, host.levelData().get(3), r.log.toString());
		assertNull(host.timers.hud(), "구출: DestroyTimer → 모래시계 없음");
		assertTrue(r.log.contains("movie Semo03 0"), r.log.toString());
		for (int i = 0; i < 20 * 60; i++) {
			frame(w, host, dish);
		}
		assertFalse(r.log.contains("failed"));
	}
}
