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
 * 2·3 번째 레이어 알파 (43 §30): 원작 메시 VU 프로그램은 VU 플래그 4 (렌더 플래그 0x1000 모델 · 거리 페이드) 일 때만 정점 α = qw78.w (물체 α × p28 × p38),
 * 아니면 메시 정점 α 그대로 (0x091–0x098) — ATHN07 둥근 문 MAT_phong15 (영상 54–56) 는 조명 없는 모델이라 p38 0.25 가 쓰이지 않는다.
 */
class LayerAlphaTest {

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

	@Test
	void unlitDoorExtraLayersKeepTextureAlpha() throws Exception {
		WadFile w = wad("ATHN07");
		MaterialResource m = mat(w, "MAT_phong15");
		assertNotNull(m);
		assertEquals(3, m.layers().size());
		assertEquals("TXR_entities_file338", m.layers().get(0).texture(), "첫 레이어 = 환경맵 ballenv7");
		assertEquals(0x01, m.layers().get(1).gsAlpha(), "반전 블렌드 (Cd − Cs)·As + Cs");
		assertEquals(0.25f, m.layers().get(1).param38(), 1e-6f);
		assertEquals(0.25f, m.layers().get(2).param38(), 1e-6f);
		GowModel door = GowModel.load(w).stream().filter(g -> g.name.equals("MDL_olympusDoorTrue")).findFirst().orElseThrow();
		assertEquals(0, door.renderFlags & WadAssets.VU_MATERIAL_ALPHA, "조명 없는 모델 → VU 플래그 4 없음");
		assertEquals(door.renderFlags, TriMesh.of(door, 0).renderFlags, "TriMesh 가 렌더 플래그를 나른다");
		for (int i = 1; i < 3; i++) {
			assertEquals(1f, WadAssets.extraLayerAlpha(m, m.layers().get(i), door.renderFlags), "p38 0.25 는 정점 α 에 안 들어간다");
		}
		// 조명 모델 (렌더 플래그 0x1800) 은 qw78.w = 물체 α × p28 × p38
		assertEquals(0.25f, WadAssets.extraLayerAlpha(m, m.layers().get(1), 0x1800), 1e-6f);
	}
}
