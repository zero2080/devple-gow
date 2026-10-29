package devple.gow.engine.game;

import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.content.Content;
import devple.gow.tools.exe.Elf;
import devple.gow.tools.exe.R5900;
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
 * 판도라의 반지 마지막 빛줄기 → 엘리베이터 (QA vs PS2 영상 4화 3330–3485, 43 §49).
 * <ul>
 *   <li>PlayMovie / PreloadMovie 의 "Levels" 힙 검사 0x1B3490: 빈 블록 ≤ 0x322417 이면 영웅이 없는 슬롯을 내린다 — PAND00C (7 MB) + PAND00D (8 MB)</li>
 *   <li>p00MP03 끝 FakeEyeDoor-ReloadWadC 가 PAND00C 를 다시 읽으면 시작 전송기 EyeDoor-EvtLoaded (202) 를 PAND00D 가 받아 205 → 엘리베이터 켬</li>
 * </ul>
 */
class EyeBeamElevatorTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static Path wadPath(String name) {
		Path p = Content.file(ROM, name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return p;
	}

	private static LevelData level(String name) throws Exception {
		return new LevelData(WadFile.load(wadPath(name)));
	}

	private static Elf exe() throws Exception {
		Path p = ROM.toAbsolutePath().normalize().resolveSibling("work").resolve("SCKA_300.02");
		Assumptions.assumeTrue(Files.exists(p), "work/SCKA_300.02 없음");
		return Elf.load(p);
	}

	private static String insn(Elf e, int pc) {
		R5900.Insn i = R5900.decode(e.u32(pc), pc);
		return i.mnem() + " " + i.ops();
	}

	/** 0x1B3490: "Levels" 힙 (0x29DAF4) 의 가장 큰 빈 블록 (0x13E238(힙, 8)) 이 0x322417 이하면 0x1BC940(1 − 영웅 슬롯, 3). Levels 힙 = 0x1000300. */
	@Test
	void movieHeapCheckDropsTheOtherSlot() throws Exception {
		Elf e = exe();
		assertTrue(insn(e, 0x1B34F0).contains("-0x250c("), insn(e, 0x1B34F0)); // lw a0, [0x29DAF4] = "Levels" 힙
		assertTrue(insn(e, 0x1B3504).contains("13e238"), insn(e, 0x1B3504));
		assertTrue(insn(e, 0x1B350C).startsWith("lui") && insn(e, 0x1B350C).contains("0x32"), insn(e, 0x1B350C));
		assertTrue(insn(e, 0x1B3510).startsWith("ori") && insn(e, 0x1B3510).contains("0x2417"), insn(e, 0x1B3510));
		assertTrue(insn(e, 0x1B3594).contains("1bc940"), insn(e, 0x1B3594));
		assertTrue(insn(e, 0x1BB7C4).startsWith("lui") && insn(e, 0x1BB7C4).contains("0x100"), insn(e, 0x1BB7C4)); // "Levels" 0x1000300
		assertTrue(insn(e, 0x1BB7D8).startsWith("ori") && insn(e, 0x1BB7D8).contains("0x300"), insn(e, 0x1BB7D8));

		int c = LevelLink.levelHeap(wadPath("PAND00C")), d = LevelLink.levelHeap(wadPath("PAND00D"));
		assertEquals(7 << 20, c);
		assertEquals(8 << 20, d);
		assertTrue(LevelLink.movieDropsOther(d, c), "눈 방 p00MP03: 빈 1 MB");
		// 죽어 가는 병사 p00MP02 (PAND00E 2 MB, 영웅은 PAND00B 9 MB) — 빈 5 MB 라 이웃 슬롯이 남아 501 을 받는다 (43 §32)
		assertFalse(LevelLink.movieDropsOther(LevelLink.levelHeap(wadPath("PAND00B")), LevelLink.levelHeap(wadPath("PAND00E"))));
		assertFalse(LevelLink.movieDropsOther(d, 0), "다른 슬롯이 비어 있으면 그대로");

		LevelLink link = new LevelLink("PAND00D", "PAND00C", w -> true);
		link.dropOther();
		assertNull(link.other());
		link.handle(new ScriptListener.LevelRequest(ScriptListener.LevelOp.LOAD_WAD, "Pand00C", null, null));
		assertEquals("PAND00C", link.other(), "내린 레벨을 LoadWad 가 다시 올린다");
		assertTrue(link.otherActive());
	}

	/**
	 * 마지막 손잡이 (EyeDoor 과제 22 = 100 %, 고리 1·3·4 남쪽 · 해골 문 · 고리 3 문 · 물속 해골 문 · 분쇄기 끔) → PAND00C EyeDoor-EvtMovieMsg 204 →
	 * PAND00D p00MP03 → 501 → ReloadWadC (LoadWad Pand00C) → 다시 읽은 PAND00C 의 EyeDoor-EvtLoaded 202 → PAND00D FakeEyeDoor-EvtCReloaded →
	 * EvtSendSwitchStatue 205 (AllLightsOn) → PAND00C ElevatorHousing-EventSwitchStatue → LevelData 22 · ElevatorLever-LeverOn.
	 */
	@Test
	void reloadedHubTellsEyeRoomAndEnablesTheElevator() throws Exception {
		LevelData lc = level("PAND00C"), ld = level("PAND00D");
		List<String> movies = new ArrayList<>(), loads = new ArrayList<>(), dVis = new ArrayList<>();
		List<String> cVis = new ArrayList<>();
		ScriptListener dl = new ScriptListener() {
			@Override
			public void movie(String name, int op, int arg) {
				if (op == 0) {
					movies.add(name);
				}
			}

			@Override
			public void level(LevelRequest r) {
				loads.add(r.op() + " " + r.wad());
			}

			@Override
			public void vis(devple.gow.engine.script.ScriptEntity source, List<String> objects, int[] values) {
				dVis.add(source.name());
			}
		};
		ScriptListener cl = new ScriptListener() {
			@Override
			public void vis(devple.gow.engine.script.ScriptEntity source, List<String> objects, int[] values) {
				cVis.add(source.name());
			}
		};
		ScriptHost main = new ScriptHost();
		ScriptWorld d = new ScriptWorld(ld, main, dl);
		ScriptWorld c = new ScriptWorld(lc, ScriptHost.neighbour(main), cl);
		d.linkEvents(c);
		d.start();
		c.start();
		c.presetState("L3=true,L4=true,L5=true,L7=true,L8=true,L9=true,GCrushersOff=true");
		float[] eye = {-250, 0, 3450};
		Runnable tick = () -> {
			d.tick(1 / 60f, eye[0], eye[1], eye[2]);
		};
		for (int i = 0; i < 30; i++) {
			tick.run();
			c.tick(1 / 60f, eye[0], eye[1], eye[2]);
		}
		c.postEvent(1023, 100, 22, 0, c.player()); // 손잡이 100 %
		for (int i = 0; i < 10; i++) {
			tick.run();
			c.tick(1 / 60f, eye[0], eye[1], eye[2]);
		}
		assertEquals(List.of("p00MP03"), movies);
		assertNotEquals(Boolean.TRUE, c.host().levelData().get(22));

		// 동영상이 PAND00C 슬롯을 내림 (0x1B3490) → 레벨 기억, 이벤트 큐 끊김
		ScriptWorld.Memory mem = c.memory();
		d.linkEvents(null);
		d.postEvent(501, "p00MP03", 0, 0, d.player());
		for (int i = 0; i < 20; i++) {
			tick.run();
		}
		assertTrue(loads.contains("LOAD_WAD PAND00C"), "FakeEyeDoor-ReloadWadC " + loads);

		// 다시 읽기: SceneNav.setOther 와 같이 큐를 먼저 잇고 시작 큐 (모드 5 전송기 202)
		ScriptWorld c2 = new ScriptWorld(lc, ScriptHost.neighbour(main), cl);
		c2.restore(mem);
		d.linkEvents(c2);
		c2.start();
		for (int i = 0; i < 20; i++) {
			tick.run();
			c2.tick(1 / 60f, eye[0], eye[1], eye[2]);
		}
		assertTrue(dVis.contains("FakeEyeDoor-VisOff"), "202 → 가짜 눈 문 숨김 " + dVis);
		assertEquals(Boolean.TRUE, c2.host().levelData().get(22), "205 → ElevatorHousing-EventSwitchStatue");
		assertTrue(cVis.contains("ElevatorLever-LeverOn"), cVis.toString());
	}
}
