package devple.gow.engine.light;

import devple.gow.tools.wad.WadFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** LightServer 라이트 모으기 0x1521B0·세기 감쇠·블레이드 라이트 램프·LGT_ 해석 (47 문서). */
class LightServerTest {

	static LightDef def(String name, int type, float[] pos, float[] dir, float[] col, float i0, float r0, float i1, float r1, int group) {
		return new LightDef(name, type, pos, dir, col, i0, r0, i1, r1, group, 0, 0, 0);
	}

	static final float[] WHITE = {1, 1, 1};

	@Test
	void intensityPiecewiseFalloff() {
		LightDef d = def("p", LightDef.POINT, new float[3], new float[3], WHITE, 2.5f, 8f, 1.5f, 160f, 0);
		assertEquals(2.5f, LightServer.intensity(d, 0, 1), 1e-6f, "중심 = I0");
		assertEquals(2.0f, LightServer.intensity(d, 4, 1), 1e-6f, "안쪽 반경 절반 = lerp(I0, I1, 0.5)");
		assertEquals(1.5f, LightServer.intensity(d, 8, 1), 1e-6f, "r0 = I1");
		assertEquals(0.75f, LightServer.intensity(d, 84, 1), 1e-6f, "r0..r1 선형 → I1·(1 − 76/152)");
		assertEquals(0f, LightServer.intensity(d, 160, 1), 1e-6f);
		assertEquals(0f, LightServer.intensity(d, 161, 1), 0f, "r1 넘으면 0");
		assertEquals(0.375f, LightServer.intensity(d, 84, 0.5f), 1e-6f, "동적 배율 +0xE0 곱");
		// 거리 = |P − C| − R/3: 반경 30 물체는 10 만큼 가깝게
		LightServer s = new LightServer();
		Light l = s.add(d);
		LightRig r = s.gather(new Vector3f(94, 0, 0), 30, false);
		assertEquals(0.75f, r.col0.x, 1e-5f);
		assertEquals(-1f, r.dir0.x, 1e-6f, "점 라이트 방향 = normalize(P − C)");
		assertEquals(1, r.count0);
		assertTrue(l.active);
	}

	@Test
	void groupsMergeByMaxColourAndWeightedDirection() {
		LightServer s = new LightServer();
		float[] up = {0, 1, 0}, side = {1, 0, 0};
		s.add(def("a", LightDef.DIRECTIONAL, new float[3], up, new float[]{1, 0.5f, 0}, 1, 100, 1, 100, 0));
		s.add(def("b", LightDef.DIRECTIONAL | LightDef.SHADOW, new float[3], side, new float[]{0.2f, 1, 0.2f}, 0.5f, 100, 0.5f, 100, 0));
		s.add(def("torch", LightDef.DIRECTIONAL, new float[]{10, 0, 0}, side, new float[]{0.8f, 0.4f, 0.1f}, 0.75f, 100, 0.75f, 100, 1));
		s.add(def("amb1", 0, new float[3], up, new float[]{0.5f, 0.5f, 0.5f}, 0.5f, 8000, 0.5f, 8000, 0));
		s.add(def("amb2", 0, new float[3], up, new float[]{1, 0, 0}, 0.3f, 8000, 0.3f, 8000, 0));
		LightRig r = s.gather(new Vector3f(), 3, false);
		// 슬롯 0: 색 = 채널별 최댓값 (1, 0.5, 0) · 1 과 (0.2, 1, 0.2) · 0.5 → (1, 0.5, 0.1), w = max
		assertEquals(1f, r.col0.x, 1e-6f);
		assertEquals(0.5f, r.col0.y, 1e-6f);
		assertEquals(0.1f, r.col0.z, 1e-6f);
		assertEquals(1f, r.col0.w, 1e-6f);
		// 방향 = 세기 가중 합 (0,1,0)·1 + (1,0,0)·0.5 정규화
		assertEquals(new Vector3f(0.5f, 1, 0).normalize().x, r.dir0.x, 1e-5f);
		assertEquals(2, r.count0);
		// 슬롯 1 = 그룹 1 (횃불)
		assertEquals(1, r.count1);
		assertEquals(0.6f, r.col1.x, 1e-5f);
		// 주변광 = 평균 ((0.25,0.25,0.25) + (0.3,0,0)) / 2
		assertEquals(2, r.countAmbient);
		assertEquals(0.275f, r.ambient.x, 1e-5f);
		assertEquals(0.125f, r.ambient.y, 1e-5f);
		// 그림자 방향 = 비트 4 라이트만
		assertEquals(1f, r.shadowDir.x, 1e-6f);
		// VU 커널 식: 위를 향한 법선 = 주변 + 슬롯 0·cos, 255/128 로 자름
		Vector3f c = r.shade(new Vector3f(0, 1, 0), new Vector3f());
		float cos0 = r.dir0.y;
		assertEquals(Math.min(0.275f + 1f * cos0, LightRig.MAX), c.x, 1e-5f);
		LightRig hot = new LightRig();
		hot.dir0.set(0, 1, 0);
		hot.col0.set(3, 0.5f, 0, 3);
		Vector3f h = hot.shade(new Vector3f(0, 1, 0), new Vector3f());
		assertEquals(LightRig.MAX, h.x, 1e-6f, "VU minii 255 → 255/128 배");
		assertEquals(0.5f, h.y, 1e-6f);
		assertEquals(0f, hot.shade(new Vector3f(0, -1, 0), new Vector3f()).y, 0f, "뒷면 max(n·L, 0)");
	}

	@Test
	void selectionBySphereOverlapAndActivity() {
		LightServer s = new LightServer();
		Light far = s.add(def("far", LightDef.POINT, new float[]{200, 0, 0}, new float[3], WHITE, 1, 10, 1, 50, 0));
		LightRig r = s.gather(new Vector3f(), 20, false);
		assertEquals(0, r.count0, "|P − C| 200 > R + r1 70 → 빠짐");
		assertEquals(0f, r.col0.x);
		r = s.gather(new Vector3f(160, 0, 0), 20, false);
		assertEquals(1, r.count0);
		far.active = false;
		assertEquals(0, s.gather(new Vector3f(160, 0, 0), 20, false).count0, "꺼진 라이트 (서버 목록 밖) 는 빠짐");
		// 물체 반경 상한 1000
		far.active = true;
		LightRig big = s.gather(new Vector3f(1100, 0, 0), 5000, false);
		assertEquals(0, big.count0, "R 은 1000 으로 잘려 R + r1 = 1050 ≥ 900 은 뽑히지만 d = 900 − 1000/3 > r1 → 세기 0 (|f| ≤ 1e-5 는 빠짐)");
	}

	@Test
	void bladeLightRampMatchesTrailRule() {
		Light l = new Light(def("LeftBladeLight", LightDef.POINT, new float[]{0, -0.3f, -8}, new float[]{0, 0, 1},
				new float[]{1, 0.622f, 0.288f}, 2.5f, 8, 1.5f, 160, 0));
		l.dyn = 0;
		l.active = false;
		float dt = 1 / 60f;
		for (int i = 0; i < 15; i++) {
			l.ramp(true, dt);
		}
		assertEquals(1f, l.dyn, 1e-5f, "+4/초 → 0.25 초에 1");
		assertTrue(l.active);
		for (int i = 0; i < 15; i++) {
			l.ramp(false, dt);
		}
		assertEquals(0.5f, l.dyn, 1e-5f, "−2/초");
		for (int i = 0; i < 15; i++) {
			l.ramp(false, dt);
		}
		assertEquals(0f, l.dyn, 1e-6f);
		assertFalse(l.active, "0 이 되면 서버에서 뺌 (vt+0x40)");
	}

	@Test
	void jointSphereCoversJoints() {
		float[][] j = {{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}, {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 36, 0, 1}};
		Vector4f s = LightServer.jointSphere(j, new Matrix4f().translation(10, 0, 0), new Vector4f());
		assertEquals(10, s.x, 1e-5f);
		assertEquals(18, s.y, 1e-5f);
		assertEquals(18, s.w, 1e-5f);
	}

	static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("PAND01A.WAD")), "gow-rom 없음");
		return rom;
	}

	/** 디스크 데이터: PAND01A 횃불 (PSFirelite, 그룹 1, 주황) · 주변광, 무기 WAD 블레이드 라이트 (점, I0 2.5 / r0 8 / I1 1.5 / r1 160). */
	@Test
	void parsesLevelAndWeaponLights() throws Exception {
		List<LightDef> pand = LightDef.load(WadFile.load(rom().resolve("PAND01A.WAD")));
		LightDef fire = pand.stream().filter(d -> d.name().equals("PSFirelite")).findFirst().orElseThrow();
		assertEquals(6, fire.type());
		assertEquals(1, fire.group());
		assertEquals(0.777f, fire.color()[0], 1e-3f);
		assertEquals(0.284f, fire.color()[1], 1e-3f);
		assertEquals(36.8f, fire.r0(), 1e-2f);
		assertEquals(86.4f, fire.r1(), 1e-2f);
		LightDef amb = pand.stream().filter(d -> d.name().equals("PSambLite")).findFirst().orElseThrow();
		assertTrue(amb.ambient());
		assertEquals(8000f, amb.r1(), 1e-3f);
		List<LightDef> wpn = LightDef.load(WadFile.load(rom().resolve("R_WPN0_0.WAD")));
		LightDef left = wpn.stream().filter(d -> d.name().equals("LeftBladeLight")).findFirst().orElseThrow();
		assertEquals(LightDef.POINT, left.type());
		assertEquals(2.5f, left.i0(), 1e-5f);
		assertEquals(8f, left.r0(), 1e-5f);
		assertEquals(1.5f, left.i1(), 1e-5f);
		assertEquals(160f, left.r1(), 1e-5f);
		// PAND01A 횃불 앞 (30 유닛) 에 선 크레토스 (반경 ≈ 20) → 슬롯 1 에 주황 횃불 색
		LightServer s = new LightServer();
		s.addAll(WadFile.load(rom().resolve("PAND01A.WAD")));
		Vector3f c = new Vector3f(fire.pos()[0] - 30, fire.pos()[1], fire.pos()[2]);
		LightRig r = s.gather(c, 20, false);
		assertTrue(r.count1 >= 1, r.toString());
		assertTrue(r.col1.x > r.col1.y && r.col1.y > r.col1.z, "주황: " + r);
		assertTrue(r.countAmbient >= 1, "PSambLite 반경 8000");
	}
}
