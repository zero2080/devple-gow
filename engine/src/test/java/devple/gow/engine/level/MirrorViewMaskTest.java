package devple.gow.engine.level;

import devple.gow.tools.model.GowModel;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** SCR_Mirror 거울 뷰에 무엇을 그리나 (43 문서 §9): 뷰 마스크 0x1508D0 / 0x129AA8 / 0x12A7A4, MDL +0x28 렌더 플래그. */
class MirrorViewMaskTest {

	private static WadFile wad(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	private static GowModel model(List<GowModel> all, String name) {
		return all.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
	}

	@Test
	void viewMasks() {
		// 거울 뷰 = (0x41 & 0x40000055) << 1 = 0x82, 값 [4] ≠ 0 → 0x02 (영웅 무리 0x80 뺌)
		assertEquals(0x82, Mirrors.mirrorViewMask(Mirrors.MAIN_VIEW, 0));
		assertEquals(0x02, Mirrors.mirrorViewMask(Mirrors.MAIN_VIEW, 1));
		// 레벨 모델 (m = 1): 기본 = 일반만, bit 57 = 거울만, bit 61 = 둘 다
		assertEquals(0x01, Mirrors.modelMask(0, Mirrors.LEVEL_MODEL));
		assertEquals(0x02, Mirrors.modelMask(GowModel.REFLECT_ONLY, Mirrors.LEVEL_MODEL));
		assertEquals(0x03, Mirrors.modelMask(GowModel.REFLECTED, Mirrors.LEVEL_MODEL));
		// 영웅 (0x1DAEC0: bit 61 + m = 0x40) → 0xC0: 값 [4] = 0 인 거울에만 비침
		int hero = Mirrors.modelMask(GowModel.REFLECTED, Mirrors.HERO_GROUP);
		assertEquals(0xC0, hero);
		assertNotEquals(0, hero & Mirrors.mirrorViewMask(Mirrors.MAIN_VIEW, 0));
		assertEquals(0, hero & Mirrors.mirrorViewMask(Mirrors.MAIN_VIEW, 1));
		// 정적 월드 (0x01) 는 거울 뷰 (0x82 / 0x02) 에 들지 않는다
		assertEquals(0, 0x01 & Mirrors.mirrorViewMask(Mirrors.MAIN_VIEW, 0));
		assertEquals(0, Mirrors.modelMask(GowModel.REFLECT_ONLY, Mirrors.LEVEL_MODEL) & Mirrors.MAIN_VIEW);
	}

	@Test
	void mdlRenderFlags() throws Exception {
		// 가짜 반사 대리 메시 = bit 57 (반사 전용), ATHN04B 벽화·PAND05A Mirror* = bit 61
		List<GowModel> o2 = GowModel.load(wad("OLYMP02"));
		assertNotEquals(0, model(o2, "MDL_refkect").renderFlags & GowModel.REFLECT_ONLY);
		assertEquals(0, model(o2, "MDL_mirror").renderFlags & (GowModel.REFLECT_ONLY | GowModel.REFLECTED));
		List<GowModel> a6 = GowModel.load(wad("ATHN06B"));
		assertNotEquals(0, model(a6, "MDL_fakeReflections").renderFlags & GowModel.REFLECT_ONLY);
		assertEquals(0, model(a6, "MDL_floorReflect").renderFlags & (GowModel.REFLECT_ONLY | GowModel.REFLECTED));
		List<GowModel> a4 = GowModel.load(wad("ATHN04B"));
		assertNotEquals(0, model(a4, "MDL_polySurface16576").renderFlags & GowModel.REFLECTED);
	}

	@Test
	void nearestMirrorOnly() {
		// 0x12A6C8: 거울 렌더러 중 가장 가까운 하나만
		Mirrors.Mirror near = new Mirrors.Mirror("a", new org.joml.Vector3f(0, 1, 0), 0, 0, 0, 0, new org.joml.Vector3f(10, 0, 0));
		Mirrors.Mirror far = new Mirrors.Mirror("b", new org.joml.Vector3f(0, 1, 0), 0, 0, 0, 0, new org.joml.Vector3f(500, 0, 0));
		assertSame(near, Mirrors.nearest(List.of(far, near), new org.joml.Vector3f(0, 50, 0)));
		assertNull(Mirrors.nearest(List.of(), new org.joml.Vector3f()));
		// 평면 아닌 거울 → 모델 Y 축 평면 (0x12A330)
		Mirrors.Mirror ax = Mirrors.axisPlane(near, new float[]{1, 0, 0, 0, 0, 2, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
		assertEquals(1f, ax.normal().y, 1e-6f);
		assertEquals(0f, ax.d(), 1e-6f);
	}
}
