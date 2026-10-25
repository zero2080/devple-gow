package devple.gow.engine.game;

import devple.gow.engine.ui.MinigameIcon;
import devple.gow.tools.content.Content;
import devple.gow.tools.exe.Elf;
import devple.gow.tools.exe.R5900;
import devple.gow.tools.script.EscScript;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA vs PS2 영상 3화 — PAND02B 협곡 → PAND02C 철창 방 · 손잡이 · 크랭크 · 우리 밀기 · 불태우기 → PAND02D 삼지창 (영상 2880–3840 초, 43 §41).
 * GL 없이 데이터 · 실행 파일 · 식만 검사.
 */
class CageSacrificeTest {

	private static Path rom() {
		return Path.of(System.getProperty("gow.rom", "../gow-rom"));
	}

	private static WadFile wad(String name) throws Exception {
		Path p = Content.file(rom(), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	private static Elf exe() throws Exception {
		Path p = rom().toAbsolutePath().normalize().resolveSibling("work").resolve("SCKA_300.02");
		Assumptions.assumeTrue(Files.exists(p), "work/SCKA_300.02 없음");
		return Elf.load(p);
	}

	private static R5900.Insn at(Elf e, int pc) {
		return R5900.decode(e.u32(pc), pc);
	}

	/**
	 * 레버를 당긴 뒤 불태우기 연출 (CAMGRPCgeFRYing CamOn = Idle(true) … CamOff = Idle(false), 영상 3740–3748) 동안 근접 R2 안내가 없다:
	 * 0x1DC3F8 은 0x1BF108 (= lw 0x29DB58, 스크립트 Idle 의 전역 정지) 이 참이면 0x1DC610 으로 건너뛰고, s1 = 0 이라 0x1DC648 이 슬롯 0 에 −1 을 넣는다.
	 * 엔진은 세이브 포인트 안내에만 이 조건을 걸어, 연출 내내 레버 옆 영웅에게 R2 가 떠 있었다.
	 */
	@Test
	void useHintIsClearedDuringScriptIdle() throws Exception {
		Elf e = exe();
		assertEquals(0x1BF108, at(e, 0x1DC450).target(), at(e, 0x1DC450).text());
		assertEquals("bne", at(e, 0x1DC458).mnem());
		assertEquals(0x1DC610, at(e, 0x1DC458).target());
		R5900.Insn get = at(e, 0x1BF110);
		assertEquals("lw", get.mnem());
		assertEquals(0x29DB58, at(e, 0x1BF108).imm() + get.imm()); // lui 0x2a + lw −0x24a8
		assertEquals("movz", at(e, 0x1DC650).mnem()); // a1 = s1 ? 14 : −1
		assertEquals(0x21DA50, at(e, 0x1DC654).target());

		WadFile w = wad("PAND02C");
		assertTrue(DesertSirensQaTest.handler(DesertSirensQaTest.entity(w, "CAMGRPCgeFRYing-CamOn"), 13).matches("push_true; call_int Internal:0xc Idle.*"));
		assertTrue(DesertSirensQaTest.handler(DesertSirensQaTest.entity(w, "CAMGRPCgeFRYing-CamOff"), 13).matches("push_false; call_int Internal:0xc Idle.*"));
		// 레버는 연출 중 되감겨 (1015, 0.5 초) 다시 잡을 수 있다 — 연출이 끝나면 근처에서 R2 안내가 다시 나오는 것은 원작과 같다
		assertTrue(DesertSirensQaTest.handler(DesertSirensQaTest.entity(w, "lever-EventReset"), 0).contains("push_int 1015"));

		assertFalse(MinigameIcon.useQueryOpen(true, -1));
		assertFalse(MinigameIcon.useQueryOpen(false, 430));
		assertTrue(MinigameIcon.useQueryOpen(false, -1));
	}

	/** 크랭크 손잡이 (gocagetriggerhandle, Push 013 Type 1) 는 관절 0 이 아니라 handle1/2 (−2048, 388, −3245, 축 ±x) 로 잡는다 — 크랭크 둘레를 미는 자리. */
	@Test
	void cageCrankHandlesSitOnTheArm() throws Exception {
		devple.gow.tools.level.LevelData lv = new devple.gow.tools.level.LevelData(wad("PAND02C"));
		var p = lv.layout.placements().stream().filter(x -> x.instance().name().equalsIgnoreCase("gocagetriggerhandle")).findFirst().orElseThrow();
		var js = p.object().skeleton().joints;
		assertEquals("handle1", js.get(2).name());
		float[] h = p.jointWorld()[2];
		assertEquals(-2048f, h[12], 1f);
		assertEquals(-3245f, h[14], 1f);
		assertEquals(-1f, h[8], 1e-3f); // z 축 = −x
		assertEquals(-1290f, p.jointWorld()[0][12], 1f); // 관절 0 은 758 떨어진 곳 (잡기 질의는 손잡이 관절만 본다)
	}
}
