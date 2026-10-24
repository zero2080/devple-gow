package devple.gow.engine.fx;

import devple.gow.tools.exe.Elf;
import devple.gow.tools.exe.R5900;
import devple.gow.tools.fx.FxResources;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 안개 앞 반투명 패스의 안개 (16 §1.2, 31 §3.1, 43 §24): 원작 그리기 순서 (안개 노드 = 층 2 칸 5 키 0x7100, 파티클 버킷·층),
 * PTC 플래그 규칙, 파티클 안개 식이 "파티클 → 안개 후처리" 와 같은지.
 */
class SceneFogTest {

	private static Path rom() {
		return Path.of(System.getProperty("gow.rom", "../gow-rom"));
	}

	private static WadFile wad(String name) throws Exception {
		Path p = rom().resolve(name + ".WAD");
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

	/** li reg, imm (= addiu reg, zero, imm 또는 ori reg, zero, imm) 의 값. */
	private static int li(Elf e, int pc, String reg) {
		R5900.Insn i = at(e, pc);
		assertEquals("li", i.mnem(), String.format("%08x %s", pc, i.text()));
		assertTrue(i.ops().startsWith(reg + ","), String.format("%08x %s", pc, i.text()));
		return Integer.decode(i.ops().substring(i.ops().indexOf(',') + 1).trim());
	}

	private static void call(Elf e, int pc, int target) {
		R5900.Insn i = at(e, pc);
		assertEquals(target, i.target(), String.format("%08x %s", pc, i.text()));
	}

	@Test
	void originalFogNodeIsLayer2Slot5AfterParticleBucket5() throws Exception {
		Elf e = exe();
		// 0x125D08 (SCR_Fog 생성자 0x20CED8 · 포세이돈 0x195BA8 이 부름): 노드 vtable 0x2BDC38 → +0x14 = 렌더 0x1281C8
		assertEquals("addiu", at(e, 0x125da0).mnem()); // v0 = 0x2BDC38
		assertTrue(at(e, 0x125dac).text().contains("0x14(s2)"), at(e, 0x125dac).text()); // 노드 +0x14 = vtable
		assertEquals(0x1281c8, e.u32(0x2bdc38 + 0x14));
		// 등록 0x15F288(목록, 층 2, 칸 5, 키 0x7100)
		assertEquals(2, li(e, 0x126190, "a1"));
		assertEquals(5, li(e, 0x126194, "a2"));
		call(e, 0x12619c, 0x15f288);
		assertEquals(0x7100, li(e, 0x1261a0, "a3")); // 지연 슬롯
		// 렌즈플레어 노드 (0x20E728): 같은 층 2 · 칸 5, 키 0 → 안개 앞
		assertEquals(2, li(e, 0x20e7c8, "a1"));
		assertEquals(5, li(e, 0x20e7cc, "a2"));
		call(e, 0x20e7d4, 0x15f288);
		// 층 그리기 0x160CC0: 파티클 버킷 5 (0x15DE50, a3 = 5) → 칸 5 노드 (0x15F4E0, a2 = 5) → 파티클 버킷 6 → 칸 6 → 칸 7
		call(e, 0x161574, 0x15de50);
		assertEquals(5, li(e, 0x161578, "a3"));
		call(e, 0x161594, 0x15f4e0);
		assertEquals(5, li(e, 0x161598, "a2"));
		call(e, 0x1615d4, 0x15de50);
		assertEquals(6, li(e, 0x1615d8, "a3"));
		call(e, 0x1615f4, 0x15f4e0);
		assertEquals(6, li(e, 0x1615f8, "a2"));
		call(e, 0x1616ac, 0x15f4e0);
		assertEquals(7, li(e, 0x1616b0, "a2"));
	}

	@Test
	void originalParticleBucketAndLayer() throws Exception {
		Elf e = exe();
		// 0x15B8D0 (PTC 인스턴스): flags & 0x4040 → 버킷 6, & 0x2000 → 5 (+0x170)
		R5900.Insn b6 = at(e, 0x15bab0);
		assertEquals("andi", b6.mnem());
		assertTrue(b6.ops().endsWith("0x4040"), b6.text());
		assertEquals(6, li(e, 0x15bab8, "v0"));
		assertTrue(at(e, 0x15bac8).ops().endsWith("0x2000"));
		assertEquals(5, li(e, 0x15bad0, "v0"));
		// flags & 0x80000 → 층 (+0xD4) = 3
		assertEquals("lui", at(e, 0x15bb28).mnem());
		assertEquals(0x80000, at(e, 0x15bb28).imm());
		assertEquals(3, li(e, 0x15bb34, "v0"));
		assertTrue(at(e, 0x15bb38).text().contains("0xd4("), at(e, 0x15bb38).text());
		// 0x15D0F0: 목록 = 서버 + 0x13C + 뷰·0x118 + 층(+0xD4)·0x38 + 버킷(+0x170)·8
		assertTrue(at(e, 0x15d0e0).text().contains("0xd4("));
		assertTrue(at(e, 0x15d0f0).text().contains("0x170("));
	}

	@Test
	void flagRule() {
		assertTrue(SceneFog.preFogFlags(0x604));    // FPpart (원반, 알파 0x44, 버킷 1)
		assertTrue(SceneFog.preFogFlags(0x681));    // FWpart4 (불벽, 가산, 버킷 3)
		assertTrue(SceneFog.preFogFlags(0x606));    // 감산 버킷 2
		assertTrue(SceneFog.preFogFlags(0x40606));  // 감산 버킷 4
		assertTrue(SceneFog.preFogFlags(0x2680));   // 버킷 5 (안개 노드 앞)
		assertTrue(SceneFog.preFogFlags(0x20604));  // 깊이 쓰기도 층 2
		assertFalse(SceneFog.preFogFlags(0x4681));  // TFfirepart: 버킷 6 (0x4000)
		assertFalse(SceneFog.preFogFlags(0x244));   // 열기 아지랑이: 버킷 6 (0x40)
		assertFalse(SceneFog.preFogFlags(0x88604)); // HOgpart (체력 오브 빛): 층 3
		assertFalse(SceneFog.preFogFlags(0x80281)); // 층 3 가산
		assertFalse(SceneFog.preFog(null));         // 근사 정의
	}

	@Test
	void rooftopsSmokeIsPreFogAndTorchFireIsNot() throws Exception {
		FxResources a = new FxResources(wad("ATHN05A"));
		int fp = 0, tf = 0;
		for (FxResources.Shape s : a.shapes.values()) {
			if (s.name().startsWith("PTC_FPpart")) {
				fp++;
				assertTrue(SceneFog.preFog(s), s.name());
				assertEquals(0x44, s.gsBlend());
				assertEquals(1, s.renderMode());
			}
			if (s.name().startsWith("PTC_TFfirepart")) {
				tf++;
				assertFalse(SceneFog.preFog(s), s.name());
				assertEquals(6, s.renderMode());
			}
		}
		assertTrue(fp >= 6, "FPpart " + fp);
		assertTrue(tf >= 4, "TFfirepart " + tf);
		FxResources k = new FxResources(wad("R_PERMK"));
		assertFalse(SceneFog.preFog(k.shapes.get("PTC_HOgpart")));
		assertTrue(SceneFog.preFog(k.shapes.get("PTC_BloodSpart")));
	}

	@Test
	void passDecision() {
		assertEquals(SceneFog.PASS_NONE, SceneFog.passFor(false, false, true));
		assertEquals(SceneFog.PASS_NONE, SceneFog.passFor(false, true, true));
		assertEquals(SceneFog.PASS_SKY, SceneFog.passFor(true, true, false));
		assertEquals(SceneFog.PASS_NONE, SceneFog.passFor(true, false, false)); // 깊이 사본 전 (하늘 단계 밖)
		assertEquals(SceneFog.PASS_SCENE, SceneFog.passFor(true, false, true));
	}

	/** GL 블렌드 (미리 곱한 색): 0 알파 (ONE, 1 − As), 1 가산 (ONE, ONE), 2 감산 (역감산). */
	private static float[] blend(float[] dst, float[] p, float as, int mode) {
		float[] o = new float[3];
		for (int i = 0; i < 3; i++) {
			o[i] = switch (mode) {
				case 0 -> p[i] + (1 - Math.min(as, 1)) * dst[i];
				case 1 -> dst[i] + p[i];
				default -> dst[i] - p[i];
			};
		}
		return o;
	}

	/** 원작 안개 패스: 0 (Cs−Cd)·F + Cd, 1 Cd + Cs·F, 2 Cd − Cs·F. */
	private static float[] fog(float[] c, float[] fc, float f, int fogBlend) {
		float[] o = new float[3];
		for (int i = 0; i < 3; i++) {
			o[i] = switch (fogBlend) {
				case 0 -> c[i] + (fc[i] - c[i]) * f;
				case 1 -> c[i] + fc[i] * f;
				default -> c[i] - fc[i] * f;
			};
		}
		return o;
	}

	/**
	 * 장면 깊이 계수로 파티클마다 색을 바꿔 안개 낀 배경 위에 차례로 그리면 = 원래 파티클을 그린 뒤 안개 패스 (원작 0x160CC0 순서)
	 * — 알파·가산·감산이 섞인 겹침, 안개 블렌드 0·1·2 모두 (클램프 없는 선형 범위).
	 */
	@Test
	void particleFogEqualsFogAfterParticles() {
		Random r = new Random(7);
		for (int trial = 0; trial < 2000; trial++) {
			int fogBlend = trial % 3;
			float f = r.nextFloat();
			float[] fc = {r.nextFloat(), r.nextFloat(), r.nextFloat()};
			float[] d = {r.nextFloat(), r.nextFloat(), r.nextFloat()};
			float[] orig = d.clone();
			float[] eng = fog(d, fc, f, fogBlend); // 엔진: 지오메트리는 셰이더 안 안개로 이미 섞였다
			int n = 1 + r.nextInt(5);
			for (int k = 0; k < n; k++) {
				int mode = r.nextInt(3);
				float as = r.nextFloat() * (mode == 0 ? 1f : 2f);
				float[] s = {r.nextFloat(), r.nextFloat(), r.nextFloat()};
				float[] p = {s[0] * as, s[1] * as, s[2] * as};
				orig = blend(orig, p, as, mode);
				eng = blend(eng, SceneFog.fogPremul(p, Math.min(as, 1), fc, f, fogBlend, mode == 0), as, mode);
			}
			float[] want = fog(orig, fc, f, fogBlend);
			for (int i = 0; i < 3; i++) {
				assertEquals(want[i], eng[i], 1e-4f, "trial " + trial + " fogBlend " + fogBlend);
			}
		}
	}

	@Test
	void sharedFogSourceIsIncluded() {
		for (String s : new String[]{"basic.frag", "particle.frag", "lensflare.frag"}) {
			String src = devple.gow.engine.render.Shader.source(s);
			assertTrue(src.lines().noneMatch(l -> l.strip().startsWith("#include")), s);
			assertEquals(1, src.lines().filter(l -> l.strip().startsWith("#version")).count(), s);
			assertTrue(src.contains("vec4 fogAt(float zw, float kBg)"), s);
			assertTrue(src.contains("vec4 fogScene()"), s);
		}
	}
}
