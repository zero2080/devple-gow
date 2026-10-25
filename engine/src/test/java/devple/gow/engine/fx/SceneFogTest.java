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

	/**
	 * 안개 GO 가 하나도 걸리지 않은 프레임은 안개가 없다 (43 §33): 안개 설정 0x125970 이 관리자 +0x84 에 프레임 번호 0x3305D8 을 적고, 렌더 0x1281C8 은 +0x84 가
	 * 지금 프레임이 아니면 뷰포트 +0x3A8 = 524287 로 두고 돌아간다. PAND00B 모래 폭풍 층 안개 gofog (CXT_bvfog) 는 크랭크 단 (칸 2) 에서만 켜지고 신전 입구
	 * 홀 (칸 1 · 3: bvfog 끔) 에서는 없다 — 영상 3520–3600 의 맑은 홀. 예전 엔진은 이전 안개를 남겨 홀이 모래 안개에 덮였다.
	 */
	@Test
	void noFogWhenNoFogObjectAppliedThisFrame() throws Exception {
		Elf e = exe();
		// 0x125A24: lw v0, 0x5d8(v0) (프레임 번호) → 0x125A2C: sw v0, 0x84(s0)
		assertTrue(at(e, 0x125a24).text().contains("0x5d8("), at(e, 0x125a24).text());
		assertEquals("sw", at(e, 0x125a2c).mnem());
		assertTrue(at(e, 0x125a2c).ops().contains("0x84(s0)"), at(e, 0x125a2c).text());
		// SCR_Fog 적용 0x20D428 · SCR_LayeredFog 적용 0x20D6A8 이 둘 다 0x125970 을 부른다
		call(e, 0x20d428, 0x125970);
		call(e, 0x20d6a8, 0x125970);
		// 렌더 0x1281C8: s2 = 환경 +0x110 (안개 관리자), +0x84 와 프레임 번호가 같으면 0x128254 (안개), 아니면 +0x3A8 = 524287 · 끝 0x1297E0
		assertTrue(at(e, 0x128214).text().contains("0x5d8("), at(e, 0x128214).text());
		assertTrue(at(e, 0x128218).ops().contains("0x84(s2)"), at(e, 0x128218).text());
		R5900.Insn b = at(e, 0x12821c);
		assertEquals("beq", b.mnem());
		assertEquals(0x128254, b.target());
		assertEquals(524287f, Float.intBitsToFloat(at(e, 0x128224).imm() | at(e, 0x128228).imm() & 0xffff)); // lui 0x48ff · ori 0xffe0
		assertTrue(at(e, 0x128248).text().contains("0x3a8("), at(e, 0x128248).text());
		assertEquals(0x1297e0, at(e, 0x12824c).target());

		Path p = rom().resolve("PAND00B.WAD");
		devple.gow.tools.level.LevelData d = new devple.gow.tools.level.LevelData(wad("PAND00B"));
		FxEnvironment env = new FxEnvironment(new devple.gow.engine.asset.WadAssets(p), d);
		org.joml.Vector3f crank = new org.joml.Vector3f(1508, 60, 1233), hall = new org.joml.Vector3f(800, 80, 1232);
		env.update(1 / 60f, crank);
		assertTrue(env.fogActive(), "크랭크 단 (칸 2 가 bvfog 켬): 모래 안개");
		assertTrue(env.activeFog().contains("gofog"), env.activeFog());
		env.update(1 / 60f, hall);
		assertFalse(env.selectFogAt(hall), "홀 (칸 1 · 3 은 bvfog 끔)");
		assertFalse(env.fogActive(), "걸린 안개가 없으면 안개 없음 (이전 안개를 남기지 않는다)");
		assertEquals(-1f, env.backgroundFactor(), "배경 안개도 없음");
		env.update(1 / 60f, crank);
		assertTrue(env.fogActive(), "다시 크랭크 단: 안개");
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
