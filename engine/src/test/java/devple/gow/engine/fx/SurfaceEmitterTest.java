package devple.gow.engine.fx;

import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.exe.Elf;
import devple.gow.tools.exe.R5900;
import devple.gow.tools.fx.EffectLibrary;
import devple.gow.tools.model.MshMesh;
import devple.gow.tools.tweak.TweakFile;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 표면 이미터 (FXC 서브타입 3) 와 아르테미스의 검 빛 (43 §33, 영상 4014–4018).
 * <ul>
 *   <li>0x1252E0: 난수 × 65535 보다 누적 면적 (MSH 삼각형 +6) 이 작은 동안 다음 삼각형 → 무게중심 점 · 보간 법선 (노드 로컬) → 노드 월드</li>
 *   <li>goBerserkBlade (R_BRSRK0): Bglowemit1 700/초 · Bfireemit1 1000/초 가 칼날 모양 MSH_berzergeomShape1 에서 — Bglowpart1 (PTC 0x30, 검을 따라감) 은
 *   칼날의 빛, Bfirepart1 (0x30 없음, 월드에 남음) 이 휘두를 때 보라 자국</li>
 *   <li>goMAIBSActive: BERSERKENTER 의 SCR_BerserkBladeFX (0x19C798) 가 RWRIST 에 — act 0.2 초 무렵 BBglowemit 이 잠깐</li>
 * </ul>
 */
class SurfaceEmitterTest {

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

	private static R5900.Insn at(Elf e, int pc) {
		return R5900.decode(e.u32(pc), pc);
	}

	/** 0x1252E0 의 삼각형 고르기 · 무게중심 접기 · 정점 보폭 [확실: 디스어셈블]. */
	@Test
	void surfaceSpawnPicksTrianglesByCumulativeArea() throws Exception {
		Elf e = exe();
		assertEquals(65535f, Float.intBitsToFloat(at(e, 0x125344).imm() | at(e, 0x125348).imm() & 0xffff), "난수 × 65535 (lui 0x477f · ori 0xff00)");
		assertEquals("lhu", at(e, 0x125358).mnem());
		assertTrue(at(e, 0x125358).text().contains("0x6("), "삼각형 +6 = 누적 면적: " + at(e, 0x125358).text());
		assertEquals(8, at(e, 0x125374).imm(), "삼각형 레코드 8 바이트");
		assertEquals(0x18, at(e, 0x125398).imm(), "정점 24 바이트 (위치 · 법선)");
		assertEquals(0x28cda8, at(e, 0x12542c).target(), "r1 난수");
		assertEquals(0x28cda8, at(e, 0x12543c).target(), "r2 난수");
		assertEquals("c.lt.s", at(e, 0x12545c).mnem(), "r1 + r2 > 1 이면 접기");
		// 데이터: 누적 면적은 오름차순, 마지막 ≈ 65535, 차이는 실제 면적 비례
		MshMesh m = null;
		for (var t : wad("R_BRSRK0").tags()) {
			if (t.name().equals("MSH_berzergeomShape1") && t.hasData()) {
				m = new MshMesh(t.name(), t.data());
			}
		}
		assertNotNull(m);
		int n = m.tris.length / 3;
		assertEquals(64, n);
		double total = 0;
		double[] area = new double[n];
		for (int i = 0; i < n; i++) {
			Vector3f a = v(m.pos[m.tris[3 * i]]), b = v(m.pos[m.tris[3 * i + 1]]), c = v(m.pos[m.tris[3 * i + 2]]);
			area[i] = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a)).length() / 2;
			total += area[i];
		}
		double acc = 0;
		for (int i = 0; i < n; i++) {
			acc += area[i];
			assertTrue(i == 0 || m.cumArea[i] >= m.cumArea[i - 1], "오름차순 " + i);
			assertEquals(acc / total * 65535, m.cumArea[i], 65535 * 0.01, "면적 비례 " + i);
		}
		assertTrue(m.cumArea[n - 1] >= 65000, "마지막 " + m.cumArea[n - 1]);
	}

	private static Vector3f v(float[] p) {
		return new Vector3f(p[0], p[1], p[2]);
	}

	/** 무게중심 접기: r1 + r2 > 1 이면 1 − r 로 — 점은 늘 삼각형 안, 법선은 보간 후 정규화. */
	@Test
	void surfacePointStaysOnTheChosenTriangle() {
		float[][] pos = {{0, 0, 0}, {10, 0, 0}, {0, 10, 0}, {0, 0, 10}};
		float[][] nrm = {{0, 0, 1}, {0, 0, 1}, {0, 0, 1}, {1, 0, 0}};
		int[] tris = {0, 1, 2, 0, 2, 3};
		int[] area = {32767, 65535};
		Vector3f p = new Vector3f(), n = new Vector3f();
		assertEquals(0, ParticleSystem.surfacePoint(pos, nrm, tris, area, 100f, 0.2f, 0.3f, p, n));
		// P0·0.2 + P1·0.3 + P2·0.5
		assertEquals(3f, p.x, 1e-5f);
		assertEquals(5f, p.y, 1e-5f);
		assertEquals(0f, p.z, 1e-5f);
		assertEquals(1f, n.z, 1e-5f);
		assertEquals(1, ParticleSystem.surfacePoint(pos, nrm, tris, area, 40000f, 0.9f, 0.8f, p, n), "누적 면적이 u 보다 작으면 다음 삼각형");
		// 접기: r1 0.1, r2 0.2 → P0·0.1 + P2·0.2 + P3·0.7
		assertEquals(0f, p.x, 1e-5f);
		assertEquals(2f, p.y, 1e-5f);
		assertEquals(7f, p.z, 1e-5f);
		assertEquals(1f, n.length(), 1e-5f);
		assertEquals(1, ParticleSystem.surfacePoint(pos, nrm, tris, area, 65535f, 0.5f, 0.5f, p, n), "끝을 넘지 않는다");
	}

	/** goBerserkBlade · goMAIBSActive 의 표면 이미터 연결과 PTC 공간 플래그 (R_BRSRK0). */
	@Test
	void artemisBladeGlowComesFromTheBladeSurface() throws Exception {
		EffectLibrary lib = new EffectLibrary();
		lib.add(wad("R_BRSRK0"));
		EffectLibrary.EffectDef blade = lib.resolve("goBerserkBlade");
		assertNotNull(blade);
		assertEquals("MDL_berserkBlade", blade.model());
		assertEquals(0.25f, blade.skeleton().bindWorld()[0][0], 1e-6f, "검 관절 배율 0.25 — 노드 로컬 배율과 곱해진다");
		int glow = 0, fire = 0;
		for (EffectLibrary.EmitterDef ed : blade.emitters()) {
			assertEquals(3, ed.fxc().subtype(), ed.fxc().name());
			assertNotNull(ed.surface(), ed.fxc().name());
			assertEquals("MSH_berzergeomShape1", ed.surface().mesh().name);
			assertEquals(0, ed.surface().joint());
			assertEquals(0f, ed.fxc().speed(), 0f, "속력 0 — 칼날 위에 머문다");
			int fl = ed.ptc().flags() & 0x30;
			if (ed.fxc().name().equals("FXC_Bglowemit1")) {
				glow++;
				assertEquals(700f, ed.fxc().rate(), 1e-3f);
				assertEquals(0x30, fl, "빛: 시스템 행렬 (검) 을 따라감 (0x15BE18)");
			} else if (ed.fxc().name().equals("FXC_Bfireemit1")) {
				fire++;
				assertEquals(1000f, ed.fxc().rate(), 1e-3f);
				assertEquals(0, fl, "불: 월드에 남아 휘두른 자리에 보라 자국");
			}
		}
		assertEquals(1, glow);
		assertEquals(1, fire);
		EffectLibrary.EffectDef draw = lib.resolve("goMAIBSActive");
		assertNotNull(draw);
		assertTrue(draw.oneShot());
		assertEquals(0.933f, draw.lifetime(), 0.01f);
		EffectLibrary.EmitterDef bb = draw.emitters().stream().filter(x -> x.fxc().name().equals("FXC_BBglowemit")).findFirst().orElseThrow();
		assertEquals("MSH_BBEFXgeomShape", bb.surface().mesh().name);
		assertEquals(0f, draw.rate(bb, 0.05f), 1e-3f, "처음엔 안 냄");
		assertTrue(draw.rate(bb, 0.2f) > 200f, "0.2 초 무렵 짧게: " + draw.rate(bb, 0.2f));
		assertEquals(0f, draw.rate(bb, 0.6f), 1e-3f);
	}

	/** 장착 0x19C240 이 종류 &lt; 0xB 부품 (이미터) 의 생성률 배율 +0xB4 를 1 로, BERSERKENTER 의 SCRIPT 이벤트 = SCR_BerserkBladeFX → 0x19C798. */
	@Test
	void equipTurnsOnTheEmittersAndTheDrawEventSpawnsTheFlash() throws Exception {
		Elf e = exe();
		assertEquals("lhu", at(e, 0x19c3f0).mnem());
		assertEquals(0xb, at(e, 0x19c3f4).imm(), at(e, 0x19c3f4).text());
		assertEquals(1f, Float.intBitsToFloat(at(e, 0x19c400).imm()), "lui 0x3f80 = 1.0");
		assertTrue(at(e, 0x19c408).text().contains("0xb4("), at(e, 0x19c408).text());
		assertEquals(0x2b5298, at(e, 0x19cfc8).imm() + at(e, 0x19cfd0).imm(), "Registry_Insert 의 이름");
		assertEquals("SCR_BerserkBladeFX", e.cstring(0x2b5298, 4));
		assertEquals(0x19c798, at(e, 0x19cfcc).imm() + at(e, 0x19cfd4).imm(), "Registry_Insert 의 처리 함수");
		assertEquals("goMAIBSActive", e.cstring(0x2b5220, 4));
		assertEquals("RWRIST", e.cstring(0x2b5218, 4));
		assertEquals(TweakFile.hashUpper("SCR_BerserkBladeFX"), MagicFx.SCR_BERSERK_BLADE_FX);
		Path perm = Content.file(ROM, Region.of(ROM).perm());
		Assumptions.assumeTrue(Files.exists(perm), "R_PERMK 없음");
		boolean found = false;
		for (CombatFile cf : CombatFile.fromWad(WadFile.load(perm))) {
			for (CombatFile.Move mv : cf.moves()) {
				if (mv.id() != 555) {
					continue;
				}
				for (CombatFile.Event ev : mv.events()) {
					found |= ev.hash() == MagicFx.SCR_BERSERK_BLADE_FX && ev.start() == 0f;
				}
			}
		}
		assertTrue(found, "BERSERKENTER (555) 의 [0, 1] SCRIPT SCR_BerserkBladeFX");
	}
}
