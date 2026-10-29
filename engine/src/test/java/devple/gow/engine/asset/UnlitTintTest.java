package devple.gow.engine.asset;

import devple.gow.tools.model.GowModel;
import devple.gow.tools.model.TriMesh;
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
 * 조명 없는 모델의 레이어 색 = 정점 색 × 텍스처 (43 §47, 47 §3.2): 메시 VU1 주 프로그램 0x090–0x098 은 VU 플래그 4 일 때만 qw76 (헤더 × 레이어 rgb) 을
 * 쓰고, 내보내기 도구가 레이어 rgba 를 정점 색에 구워 넣었다 — 엔진이 rgba 를 또 곱하면 제곱이 된다.
 */
class UnlitTintTest {

	private static WadFile wad(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom")).resolve(name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	private static MaterialResource mat(WadFile w, String name) {
		for (WadTag t : w.tags()) {
			if (t.tag() == WadTagType.RESOURCE && t.hasData() && t.magic() == 0x08 && name.equals(t.name())) {
				return MaterialResource.parse(t);
			}
		}
		return null;
	}

	/** 그 머티리얼 서브메시의 정점 색 rgb 최댓값. */
	private static int[] maxColor(TriMesh t, String material) {
		int[] mx = {0, 0, 0};
		for (TriMesh.Sub s : t.subs) {
			if (s.material.equals(material)) {
				for (int[] c : s.color) {
					for (int k = 0; k < 3; k++) {
						mx[k] = Math.max(mx[k], c[k]);
					}
				}
			}
		}
		return mx;
	}

	@Test
	void unlitModelsUseVertexColourOnly() {
		float[] rgba = {0.1157f, 0.112238f, 0.0934856f, 1f};
		assertArrayEquals(new float[]{1, 1, 1, 1}, WadAssets.unlitVertexColorOnly(rgba.clone(), 0, false), 1e-6f, "렌더 플래그 0 → 정점 색만");
		assertArrayEquals(rgba, WadAssets.unlitVertexColorOnly(rgba.clone(), 0x1800, false), 1e-6f, "조명 모델 (0x1000) 은 qw76 그대로");
		assertArrayEquals(rgba, WadAssets.unlitVertexColorOnly(rgba.clone(), 0, true), 1e-6f, "색 ANM 머티리얼은 예전 규칙 [근사]");
		float[] a = {0.5f, 0.5f, 0.5f, 0.25f};
		assertEquals(0.25f, WadAssets.unlitVertexColorOnly(a, 0, false)[3], 1e-6f, "알파는 건드리지 않음");
	}

	@Test
	void exporterBakedLayerColourIntoVertexColours() throws Exception {
		WadFile w = wad("ATHN04A");
		GowModel crate = GowModel.load(w).stream().filter(g -> g.name.equals("MDL_breakCrate02")).findFirst().orElseThrow();
		assertEquals(0, crate.renderFlags & WadAssets.VU_MATERIAL_ALPHA, "조명 없는 모델");
		TriMesh t = TriMesh.of(crate, 0);
		String[] names = {"MAT_woodFloor_wood", "MAT_rel_woodFloor_wood6", "MAT_rel_woodFloor_wood7", "MAT_rel_woodFloor_wood9"};
		float base = mat(w, names[0]).layers().getFirst().rgba()[0];
		int baseC = maxColor(t, names[0])[0];
		assertEquals(0.8595f, base, 1e-3f);
		for (String n : names) {
			float r = mat(w, n).layers().getFirst().rgba()[0];
			int c = maxColor(t, n)[0];
			// 정점 색 최댓값의 비 = 레이어 rgba 의 비 (wood 64 · wood6 54 · wood7 89 · wood9 0 ↔ 0.8595 · 0.719 · 1.2 · 0)
			assertEquals(r / base, c / (float) baseC, 0.03f, n);
		}
	}

	@Test
	void hadesShaftLightBeamVertexColourIsTheLayerColour() throws Exception {
		WadFile w = wad("PAND03E");
		GowModel fake = GowModel.load(w).stream().filter(g -> g.name.equals("MDL_FakeStatue")).findFirst().orElseThrow();
		assertEquals(0, fake.renderFlags & WadAssets.VU_MATERIAL_ALPHA);
		MaterialResource beam = mat(w, "MAT_M01SFXlightBeam5");
		assertEquals(0x48, beam.layers().getFirst().gsAlpha(), "가산");
		float[] rgba = beam.layers().getFirst().rgba();
		int[] c = maxColor(TriMesh.of(fake, 0), "MAT_M01SFXlightBeam5");
		for (int k = 0; k < 3; k++) {
			assertEquals(rgba[k] * 256f, c[k], 1.5f, "정점 색 = 레이어 rgba × 256 (채널 " + k + ")");
		}
	}
}
