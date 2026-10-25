package devple.gow.engine.game;

import devple.gow.engine.nav.Platforms;
import devple.gow.tools.exe.Elf;
import devple.gow.tools.exe.R5900;
import devple.gow.tools.fx.EffectLibrary;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 판도라의 반지 — PAND00D 눈 방 레버 · 석상 손잡이 → PAND00C 고리 1 크랭크 (QA vs PS2 영상 3690–3958, 43 §36).
 * <ul>
 *   <li>카메라 노드 관절 = CAM 헤더 +0x08 (0x12FEE0 → 0x1300C8 → 노드 +0x60, 0x1303D0): Crank1cam 은 고리 1 RingRot (관절 1)</li>
 *   <li>Vis 슬롯 17 JointVisibility (0x1ABAA8 끝): 관절 표 [Vis +0x40] = 1 / 0 / 뒤집기, 그리기 0x137290 은 자손까지 건너뜀</li>
 *   <li>커브 이미터 (서브타입 4) 는 GO 이펙트에서도 0xD 노드의 NCV 커브 위에서 낸다</li>
 * </ul>
 */
class RingsRoomsTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static WadFile wad(String name) throws Exception {
		Path p = ROM.resolve(name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
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

	/** 노드 헤더 복사 0x12FEE0 (+8 → 정의 +8) 와 노드 만들기 0x1300C8 (정의 +8 → 노드 +0x60), 노드 월드 0x1303D0 이 +0x60 을 관절로 읽는다. */
	@Test
	void cameraNodeJointComesFromHeader() throws Exception {
		Elf e = exe();
		assertTrue(insn(e, 0x12FEFC).startsWith("lhu") && insn(e, 0x12FEFC).contains("0x8("), insn(e, 0x12FEFC));
		assertTrue(insn(e, 0x130100).startsWith("sh") && insn(e, 0x130100).contains("0x60("), insn(e, 0x130100));
		assertTrue(insn(e, 0x130400).startsWith("lh") && insn(e, 0x130400).contains("0x60("), insn(e, 0x130400));

		LevelData d = new LevelData(wad("PAND00C"));
		LevelData.PlacedCamera c = d.camera("Crank1cam");
		assertEquals(1, c.def().p().joint());
		assertEquals(1, c.joint()); // RingRot (고리 중심 (−240, 4, 1232))
		float[] eye = c.eye();
		// 고리 1 안쪽 해골 문 앞에서 바깥 벽 (+z) 을 본다 — 예전 (루트 관절) 은 고리 바깥 z 2586
		assertEquals(-273f, eye[0], 2f);
		assertEquals(1864f, eye[2], 2f);
		assertTrue(c.forward()[2] > 0.9f);
		// 대부분은 루트 (0), IdleCam 류는 이름과 같은 관절 1
		assertEquals(0, d.camera("SklDrCam").joint());
	}

	/** 0x1ABAA8 끝: 값 1 → 1, 2 → 0, 3 → 뒤집기 를 GO+0x104 → +0x54 [Vis +0x40] 에. 0x137290 은 표가 0 이거나 부모 비트가 없으면 건너뛴다. */
	@Test
	void jointVisibilityHidesJointAndDescendants() throws Exception {
		Elf e = exe();
		assertTrue(insn(e, 0x1ABDEC).contains("0x54("), insn(e, 0x1ABDEC)); // 관절 표
		assertTrue(insn(e, 0x1ABE18).contains("0x40("), insn(e, 0x1ABE18)); // Vis +0x40 관절 번호
		assertTrue(insn(e, 0x137368).contains("0x0("), insn(e, 0x137368)); // 표 값 (0 이면 건너뜀)

		LevelData lv = new LevelData(wad("PAND00C"));
		Platforms ps = Platforms.of(lv, Set.of(), Set.of("goring1crank", "goeyedoor"));
		Platforms.Body crank = ps.body("goring1crank");
		assertNotNull(crank);
		assertFalse(crank.jointsHidden());
		assertNull(crank.hidden());
		crank.setJointVisible(2, 2); // CrankOff: crankPlatform01
		boolean[] h = crank.hidden();
		assertTrue(h[2] && h[3] && h[4] && h[5] && h[6] && h[7] && h[8]); // 받침 · LocalRotation · 손잡이 · Platform1 · 받침 톱니
		assertFalse(h[0] || h[1] || h[9] || h[10] || h[11]); // 루트 · RingRot · 고리 장식 · 벽 톱니
		crank.setJointVisible(2, 1); // CrankOn
		assertFalse(crank.hidden()[2]);
		assertTrue(crank.jointsHidden()); // 받은 적이 있으면 계속 관절별로 그린다
		crank.setJointVisible(2, 3);
		assertTrue(crank.hidden()[4]);

		Platforms.Body eye = ps.body("goeyedoor");
		eye.setJointVisible(8, 2); // RightAnim-lever02 Off
		assertTrue(eye.hidden()[8]);
	}

	/** 고리 1 크랭크 GO 의 FXC_emitter3 (서브타입 4) = 벽 톱니 중심 반지름 26 원 (NCV_nurbsCircle5, 노드 관절 9 RingArt1). */
	@Test
	void ringCrankCurveEmitterHasCurve() throws Exception {
		EffectLibrary lib = new EffectLibrary();
		lib.add(wad("PAND00C"));
		EffectLibrary.EffectDef d = lib.resolve("goring1crank");
		assertNotNull(d);
		EffectLibrary.EmitterDef em3 = d.emitters().stream().filter(x -> x.fxc().name().equals("FXC_emitter3")).findFirst().orElseThrow();
		assertEquals(4, em3.fxc().subtype());
		assertNotNull(em3.curve());
		assertEquals(9, em3.curve().joint());
		assertEquals("NCV_nurbsCircle5", em3.curve().curve().name);
		float[] p = new float[3];
		em3.curve().curve().eval(0, p);
		assertEquals(26f, Math.hypot(p[0], p[1]), 1.5f);
		// 표면 · 커브가 아닌 이미터 (상자 emitter7) 는 둘 다 없음
		EffectLibrary.EmitterDef em7 = d.emitters().stream().filter(x -> x.fxc().name().equals("FXC_emitter7")).findFirst().orElseThrow();
		assertNull(em7.curve());
	}
}
