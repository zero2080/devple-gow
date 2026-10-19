package devple.gow.engine.fx;

import devple.gow.tools.fx.EnvParams;
import devple.gow.tools.fx.FxResources;
import devple.gow.tools.fx.MaterialAnim;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 환경/이펙트 데이터 해석 (16 문서): 안개·비·물 스크립트, 머티리얼 ANM, 파티클 이미터. */
class EnvFxTest {

	private static WadFile wad(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	@Test
	void athn01aLayeredFogAndRain() throws Exception {
		EnvParams p = new EnvParams(wad("ATHN01A"));
		assertEquals(1, p.layeredFogs.size());
		EnvParams.LayeredFog f = p.layeredFogs.getFirst();
		assertEquals("gofogcube", f.owner());
		assertEquals(30000, f.centreZ());
		assertArrayEquals(new float[]{0.445f, 0.475f, 0.54f}, f.stops(), 1e-4f);
		assertEquals(0.56f, f.colors()[2][3], 1e-4f);
		assertEquals(95, f.backPlane());
		assertEquals("defFogMat", f.material());
		EnvParams.Weather w = p.weathers.getFirst();
		assertEquals(0, w.type());
		assertEquals("gorain", w.typeName());
		assertArrayEquals(new float[]{70, 50, 63}, w.box(), 1e-4f);
		assertEquals("nightSky", p.skies.getFirst().object());
	}

	@Test
	void pand01aFogWaterAndHdesFarClip() throws Exception {
		EnvParams p = new EnvParams(wad("PAND01A"));
		assertEquals(3, p.fogs.size());
		assertEquals(160000, p.fogs.getFirst().centreZ());
		assertEquals(0.8f, p.fogs.getFirst().color()[3], 1e-4f);
		EnvParams.Water w = p.waters.getFirst();
		assertEquals("REFLENVM", w.material());
		assertEquals(23, w.nx());
		assertEquals(31, w.nz());
		assertEquals(30, w.dropRate());
		EnvParams h = new EnvParams(wad("HDES01A"));
		assertEquals(2800f, h.farClips.getFirst().farDistance(), 1e-3f);
	}

	@Test
	void athn07GrassFlipbook() throws Exception {
		Map<String, MaterialAnim.Anim> m = MaterialAnim.load(wad("ATHN07"));
		MaterialAnim.Anim grass = m.get("MAT_lambert250");
		assertNotNull(grass);
		assertEquals(20, grass.frames().length);
		assertEquals(0, grass.frame(0));
		assertEquals(19, grass.frame(19 / 30f + 0.001f));
	}

	@Test
	void athn01aUvScroll() throws Exception {
		MaterialAnim.Anim a = MaterialAnim.load(wad("ATHN01A")).get("MAT_M01sfxWaterShimmer");
		assertNotNull(a);
		float[] uv0 = a.uv(0, 0), uvHalf = a.uv(0, a.duration() / 2);
		assertEquals(1f, uv0[1], 1e-3f);
		assertEquals(1.5f, uvHalf[1], 0.05f);
	}

	@Test
	void pand01aTorchEmitter() throws Exception {
		FxResources r = new FxResources(wad("PAND01A"));
		FxResources.Emitter e = r.emitters.get("FXC_Tfiremit");
		assertEquals(1, e.subtype());
		assertEquals(35f, e.rate(), 1e-3f);
		FxResources.Shape s = r.byShape.get(e.shape());
		assertEquals("PTC_Tfirepart", s.name());
		assertEquals("MAT_fire_M01firepart3", s.material());
		float[] c = s.colorCurve();
		assertNotNull(c);
		assertEquals(84.4f / 128, c[0], 1e-3f);
		assertEquals(2966f, c[6], 1f);
	}
}
