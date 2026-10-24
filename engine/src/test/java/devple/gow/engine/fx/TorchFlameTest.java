package devple.gow.engine.fx;

import devple.gow.engine.asset.RenderModel;
import devple.gow.tools.anim.Mat4;
import devple.gow.tools.fx.FxResources;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.texture.MaterialResource;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 횃불 불꽃 (43 문서 §25): ATHN05B/05BB 발리스타 복도·홀의 벽 횃불이 영상 (7716 · 7792 · 7852 · 7941) 처럼 받침 위로 솟지 않고 받침 안에 갇혀 있었다.
 * <ol>
 *   <li>FXC_/PTC_ 는 오브젝트 그룹마다 같은 이름으로 다시 정의된다 → 오브젝트 앞의 마지막 정의 (예전: 처음 정의 — 전 레벨 불 이미터 486 개가 렌더 정점 10 밖).</li>
 *   <li>CPU 스트림 PTC (배치 바이트 0xB bit0) 는 파티클 서버 갱신 0x15C0F0 이 위치 = 처음 위치 + 속도 · 나이 로 옮긴다 — VU 식은 위치 C0 그대로.</li>
 *   <li>속력은 이미터 월드 배율로 (0x122C88 → 0x122E78): 방 관절 0.5 × FXC 로컬 6.63 = 3.32.</li>
 * </ol>
 */
class TorchFlameTest {

	private static WadFile wad(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom")).resolve(name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	private static LevelLayout.Placement placement(LevelData d, String inst) {
		return d.layout.placements().stream().filter(p -> p.instance().name().equalsIgnoreCase(inst)).findFirst().orElseThrow();
	}

	private static float[] world(FxResources.Emitter em, LevelLayout.Placement pl) {
		int j = em.header()[0] >= 0 && em.header()[0] < pl.jointWorld().length ? em.header()[0] : 0;
		return Mat4.mul(em.local(), pl.jointWorld()[j]);
	}

	/** 같은 이름 FXC_TFfireemit10 두 정의: 지붕 출입 방 (666, −171, −812) · 궁수 발코니 (349, −50, 557) — 각 오브젝트는 자기 앞의 정의를 쓴다. */
	@Test
	void objectsUseTheDefinitionBeforeTheirGroup() throws Exception {
		LevelData d = new LevelData(wad("ATHN05B"));
		FxResources fx = new FxResources(d.wad);
		LevelLayout.Placement roof = placement(d, "goroofaccessroom"), balcony = placement(d, "gosarcherbalconyareab");
		FxResources.Emitter a = fx.emitter("FXC_TFfireemit10", roof.object().tag());
		FxResources.Emitter b = fx.emitter("FXC_TFfireemit10", balcony.object().tag());
		assertNotSame(a, b);
		assertArrayEquals(new float[]{666.43f, -170.62f, -812.46f}, new float[]{a.local()[12], a.local()[13], a.local()[14]}, 0.01f);
		assertArrayEquals(new float[]{349.24f, -50.17f, 557.05f}, new float[]{b.local()[12], b.local()[13], b.local()[14]}, 0.01f);
		assertSame(a, fx.emitters.get("FXC_TFfireemit10"), "처음 정의 (예전 엔진이 둘 다에 쓰던 것)");
		// 발코니 횃불: 자기 정의로 (174.6, 486.9, 3862.5) — 예전 (333, 427, 3178) 은 가장 가까운 정점이 174 떨어진 허공
		float[] w = world(b, balcony);
		assertArrayEquals(new float[]{174.6f, 486.9f, 3862.5f}, new float[]{w[12], w[13], w[14]}, 0.2f);
		// PTC 도 모양 이름으로 같은 규칙
		assertNotSame(fx.shape("TFfirepartShape10", roof.object().tag()), fx.shape("TFfirepartShape10", balcony.object().tag()));
	}

	/** 05BB 오른쪽 방 FXC_TFfireemit13 (감옥 홀과 같은 이름): 자기 정의 (−614.67, 85.38, −448) → 횃불 받침 근처, 예전 (홀 정의) 은 정점 17 밖. */
	@Test
	void neighbourRoomTorchUsesItsOwnEmitter() throws Exception {
		LevelData d = new LevelData(wad("ATHN05BB"));
		FxResources fx = new FxResources(d.wad);
		LevelLayout.Placement room = placement(d, "goroomright");
		FxResources.Emitter own = fx.emitter("FXC_TFfireemit13", room.object().tag());
		assertEquals(-614.67f, own.local()[12], 0.01f);
		assertEquals(-448.00f, own.local()[14], 0.01f);
		assertNotEquals(fx.emitters.get("FXC_TFfireemit13").local()[14], own.local()[14], "처음 정의 = 감옥 홀 것");
	}

	/**
	 * 방 횃불 TMfire (05B 복도 청동 받침) 의 PTC 는 CPU 스트림 (속도가 VU 레코드에 없고 위치 연산 0 = C0) → CPU 동역학, 제우스 석상 화로 TFfire25 는 VU 가 움직인다.
	 * 받침 (MAT_1decorAthens64Torch) 은 이미터 위아래 −10.5 … +9.7 — 생성 점에 멈춘 불꽃 (반폭 ≤ 4.8) 은 받침 안에 갇힌다.
	 */
	@Test
	void roomTorchFlamesMoveOnTheCpu() throws Exception {
		LevelData d = new LevelData(wad("ATHN05B"));
		FxResources fx = new FxResources(d.wad);
		LevelLayout.Placement piazza = placement(d, "gobridgepiazzagroupb");
		FxResources.Emitter tm = fx.emitter("FXC_TMfireemit14", piazza.object().tag());
		FxResources.Shape tmShape = fx.shape(tm.shape(), piazza.object().tag());
		assertEquals(1, tmShape.vu().attr[11] & 1, "배치 바이트 0xB bit0 = CPU 스트림");
		assertEquals(0, tmShape.vu().attr[2] & 3, "속도 = 스트림 0 (CPU)");
		assertTrue(def(tmShape).cpuMotion());
		LevelLayout.Placement statue = placement(d, "goreakablestatuezeus1");
		FxResources.Emitter tf25 = fx.emitter("FXC_TFfireemit25", statue.object().tag());
		FxResources.Shape tf25Shape = fx.shape(tf25.shape(), statue.object().tag());
		assertEquals(0, tf25Shape.vu().attr[11] & 1);
		assertEquals(1, tf25Shape.vu().attr[2] & 3, "속도 = VU 레코드");
		assertFalse(def(tf25Shape).cpuMotion());

		// 속력 배율: 관절 0.5 × FXC 로컬 6.63 → 3.3155, 불꽃은 0.45·16·3.3155 = 23.9/초로 솟아 수명 0.85 초에 20 → 받침 위 (+9.7) 로 10 남짓
		float[] w = world(tm, piazza);
		assertArrayEquals(new float[]{-482.8f, 516.1f, 4356.2f}, new float[]{w[12], w[13], w[14]}, 0.2f);
		float vs = LevelEmitters.speedScale(w, tm.dir());
		assertEquals(3.3155f, vs, 1e-3f);
		float rise = tm.speed() * 16 * vs * tmShape.lifespan();
		assertTrue(rise > 19.5f && rise < 21f, "솟는 높이 " + rise);
	}

	/** 필드 가속 (0x12B530): ATHN06A gotorchfire1 균일 필드 (2.484, dir (1,0,0), 노드 배율 2) → +x 로 2.484·16 = 39.7 /초². */
	@Test
	void uniformFieldPushesTorchFlames() throws Exception {
		LevelData d = new LevelData(wad("ATHN06A"));
		FxResources fx = new FxResources(d.wad);
		LevelLayout.Placement torch = placement(d, "gotorchfire1");
		float[] a = LevelEmitters.fieldAccel(fx, torch, torch.object().tag());
		assertArrayEquals(new float[]{2.484f * 16, 0, 0}, a, 0.05f);
	}

	/**
	 * 발리스타 손잡이 틀 MAT_M02MetalTrim01b: 첫 레이어 rf 0x86 (bit 1 = VU 환경맵 경로) 의 텍스처는 크롬 공 사진 (원 밖 검정) — 메시 UV 로 찍으면 틀이 검다.
	 * 두 번째 레이어 rf 0x01010080 (GS 0x01, 환경맵 아님) 은 메시 UV. 법선 있는 메시의 첫 레이어 환경맵은 전 디스크 1324 파트 (43 §25).
	 */
	@Test
	void ballistaRailFirstLayerIsAnEnvironmentMap() throws Exception {
		WadFile w = wad("ATHN05B");
		MaterialResource mat = null;
		for (WadTag t : w.tags()) {
			if (t.tag() == WadTagType.RESOURCE && t.hasData() && t.magic() == 0x08 && "MAT_M02MetalTrim01b".equals(t.name())) {
				mat = MaterialResource.parse(t);
				break;
			}
		}
		assertNotNull(mat);
		assertEquals(2, mat.layers().size());
		assertEquals(0x86, mat.layers().get(0).renderFlags() & 0xFF);
		assertNotEquals(0, mat.layers().get(0).renderFlags() & MaterialFx.ENV_FLAG);
		assertEquals("TXR_entities02_file330", mat.layers().get(0).texture());
		assertEquals(0, mat.layers().get(1).renderFlags() & MaterialFx.ENV_FLAG);
		assertEquals(0x01, mat.layers().get(1).gsAlpha());
	}

	private static ParticleDef def(FxResources.Shape sh) {
		return new ParticleDef("t", 1, 1, 0, new float[3], new float[3], new float[3], new float[3], 0, 1, 1, new float[]{1, 1, 1, 1},
				new float[]{1, 1, 1, 1}, 0, 0, 0, null, 1, RenderModel.Blend.ADD, 0.1f, 1, 0, 0, 0, 0).withPtc(sh);
	}
}
