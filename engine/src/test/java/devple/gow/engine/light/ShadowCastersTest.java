package devple.gow.engine.light;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 그림자 오브젝트 갱신 0x1628E0 / 받는 평면 0x1BF680 / 이웃 슬롯 라이트 (47 문서 §7·§8). */
class ShadowCastersTest {

	static final float[][] ONE_JOINT = {new Matrix4f().get(new float[16])};

	static ShadowGeometry geometry() {
		ShadowGeometry.Piece cube = ShadowVolumeTest.cube();
		try {
			var c = ShadowGeometry.class.getDeclaredConstructor(String.class, List.class);
			c.setAccessible(true);
			return c.newInstance("SHG_test", List.of(cube));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	static LightServer sun(int type) {
		LightServer s = new LightServer();
		s.add(new LightDef("sun", type, new float[3], new float[]{0.3f, 0.8f, 0.2f}, new float[]{1, 1, 1}, 1, 8000, 1, 8000, 0, 0, 0, 0));
		return s;
	}

	static final ShadowCasters.Receiver FLOOR = (from, dir, max) -> {
		// 평면 y = −10, 반직선이 닿으면
		if (dir.y >= 0) {
			return null;
		}
		float t = (-10 - from.y) / dir.y;
		return t <= max ? new Vector4f(0, -1, 0, -10) : null; // 법선 방향은 일부러 반대 (L 쪽으로 맞추는지)
	};

	@Test
	void shadowDirectionComesOnlyFromBit4Lights() {
		ShadowGeometry g = geometry();
		ShadowCasters sc = new ShadowCasters();
		sc.begin();
		sc.submit("a", null, g, ONE_JOINT, null, new Matrix4f(), new Vector4f(0, 0, 0, 1.8f), ShadowVolume.MODE_EXTRUDE, null);
		ShadowVolume.Out out = new ShadowVolume.Out();
		assertEquals(0, sc.build(sun(LightDef.DIRECTIONAL), FLOOR, new Vector3f(0, 0, 50), out), "비트 4 없음 → 방향 0 → 그리지 않음");
		sc.begin();
		sc.submit("a", null, g, ONE_JOINT, null, new Matrix4f(), new Vector4f(0, 0, 0, 1.8f), ShadowVolume.MODE_EXTRUDE, null);
		int tris = sc.build(sun(LightDef.DIRECTIONAL | LightDef.SHADOW), FLOOR, new Vector3f(0, 0, 50), out);
		assertTrue(tris > 0);
		assertEquals(1, sc.lastCasters);
		// 모으기 중이 아니면 (거울 반사 패스 등) 넣지 않는다
		assertFalse(sc.collecting());
		sc.submit("b", null, g, ONE_JOINT, null, new Matrix4f().translate(50, 0, 0), new Vector4f(50, 0, 0, 1.8f), 0, null);
		assertEquals(tris, sc.build(sun(LightDef.DIRECTIONAL | LightDef.SHADOW), FLOOR, new Vector3f(0, 0, 50), new ShadowVolume.Out()));
		assertEquals(1, sc.lastCasters);
	}

	@Test
	void childUsesParentDirectionPlaneAndMode() {
		ShadowGeometry g = geometry();
		ShadowCasters sc = new ShadowCasters();
		LightServer s = sun(LightDef.DIRECTIONAL | LightDef.SHADOW);
		sc.begin();
		// 자식 (블레이드) 이 먼저 넘어와도 부모 (영웅) 를 먼저 계산한다
		sc.submit("blade", "hero", g, ONE_JOINT, null, new Matrix4f().translate(3, 0, 0), new Vector4f(3, 0, 0, 1.8f), ShadowVolume.MODE_EXTRUDE, null);
		sc.submit("hero", null, g, ONE_JOINT, null, new Matrix4f(), new Vector4f(0, 0, 0, 1.8f), ShadowVolume.MODE_GROUND, "hero");
		sc.submitChain("hero", new Vector3f[]{new Vector3f(0, 2, 0), new Vector3f(4, 2, 0)});
		ShadowVolume.Out out = new ShadowVolume.Out();
		assertTrue(sc.build(s, FLOOR, new Vector3f(0, 0, 50), out) > 0);
		assertEquals(2, sc.lastCasters);
		List<String> d = sc.describe();
		assertTrue(d.get(0).contains("모드 2 켬"), d.get(0));
		assertTrue(d.get(1).contains("모드 2 켬"), d.get(1));
		// 받는 평면 법선이 L 쪽 (위) 으로 뒤집혔는지: 모드 2 볼륨이 바닥 −1.6 아래로 내려가지 않는다
		float minY = Float.MAX_VALUE;
		for (int i = 1; i < out.size; i += 3) {
			minY = Math.min(minY, out.data[i]);
		}
		assertEquals(-10 - ShadowVolume.BELOW, minY, 1e-3f);
		// 받는 면이 없으면 (반직선 못 맞음) 모드 2·1 은 그리지 않는다
		sc.begin();
		sc.submit("hero", null, g, ONE_JOINT, null, new Matrix4f(), new Vector4f(0, 0, 0, 1.8f), ShadowVolume.MODE_SLAB, null);
		assertEquals(0, sc.build(s, (f, dir, max) -> null, new Vector3f(), new ShadowVolume.Out()));
	}

	@Test
	void scriptHidesShadowByNameAndCameraDistanceFades() {
		ShadowGeometry g = geometry();
		ShadowCasters sc = new ShadowCasters();
		sc.setHidden("goAoSKS", true);
		sc.begin();
		sc.submit("e", null, g, ONE_JOINT, null, new Matrix4f(), new Vector4f(0, 0, 0, 1.8f), ShadowVolume.MODE_EXTRUDE, "GOAOSKS");
		assertEquals(0, sc.build(sun(LightDef.DIRECTIONAL | LightDef.SHADOW), FLOOR, new Vector3f(), new ShadowVolume.Out()), "Vis 슬롯 2 끔");
		sc.setHidden("goaosks", false);
		assertFalse(sc.hidden("goaosks"));
		assertEquals(1f, ShadowCasters.fade(1000), 0f);
		assertEquals(0.5f, ShadowCasters.fade(1050), 1e-5f);
		assertTrue(ShadowCasters.fade(1100) <= 0);
		sc.begin();
		sc.submit("e", null, g, ONE_JOINT, null, new Matrix4f(), new Vector4f(0, 0, 0, 1.8f), ShadowVolume.MODE_EXTRUDE, null);
		assertEquals(0, sc.build(sun(LightDef.DIRECTIONAL | LightDef.SHADOW), FLOOR, new Vector3f(0, 0, 1200), new ShadowVolume.Out()),
				"카메라 1100 넘으면 끔");
	}

	@Test
	void neighbourSlotLightsJoinAndLeaveTheServer() {
		LightServer s = new LightServer();
		LightDef amb = new LightDef("amb", 0, new float[3], new float[3], new float[]{0.5f, 0.5f, 0.5f}, 1, 8000, 1, 8000, 0, 0, 0, 0);
		Light own = s.add(amb);
		own.source = "ATHN04C";
		Light other = s.add(new LightDef("amb2", 0, new float[]{100, 0, 0}, new float[3], new float[]{1, 0, 0}, 1, 8000, 1, 8000, 0, 0, 0, 0));
		other.source = "ATHN04A";
		assertEquals(2, s.gather(new Vector3f(), 10, false).countAmbient, "이웃 슬롯 라이트도 모으기 후보");
		s.setSourceActive("athn04a", false);
		assertEquals(1, s.gather(new Vector3f(), 10, false).countAmbient, "그룹 숨기기 0x151FC0");
		s.setSourceActive("ATHN04A", true);
		assertEquals(1, s.removeSource("ATHN04A"));
		assertEquals(1, s.lights().size());
		assertSame(own, s.lights().get(0));
	}
}
