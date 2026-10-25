package devple.gow.engine.asset;

import devple.gow.engine.fx.MaterialFx;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.fx.MaterialAnim;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.level.StartPose;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.texture.MaterialResource;
import devple.gow.tools.texture.TextureLibrary;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 자세 GO (AnimatedModel) 의 첫 레이어 플립북 (43 §30, 영상 54–56): ATHN07 둥근 문 옆 풀 goentrance MAT_lambert254 는 GFX 20 프레임
 * (64×640 세로 스트립 → 프레임 64×32) 에 반복 머티리얼 ANM 이 붙은 "바람에 흔들리는 풀" 이고, goentrance 는 스스로 도는 반복 act 가 있어
 * 구운 메시가 아니라 AnimatedModel 로 매 프레임 그린다. 예전 AnimatedModel 은 첫 레이어에 스트립 전체를 붙여 (프레임 선택은 구운 메시
 * Renderer 에만 있었다) 풀 다발마다 프레임 띠가 동심 줄무늬로 늘어선 어두운 우리처럼 보였다.
 */
class AnimatedFlipbookTest {

	private static WadFile wad(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom")).resolve(name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	@Test
	void entranceGrassIsAFlipbookOnASelfAnimatingPlacement() throws Exception {
		WadFile w = wad("ATHN07");
		LevelData d = new LevelData(w);
		LevelLayout.Placement p = d.layout.placements().stream().filter(q -> q.instance().name().equalsIgnoreCase("goentrance")).findFirst().orElseThrow();
		// LevelScene 의 Ambient 조건 (스스로 도는 반복 act + 관절 데이터) → AnimatedModel 경로
		assertTrue(d.selfAnimates(p), "인스턴스 플래그 bit 0 · Animator 없음");
		AnimationSet set = AnimationSet.load(w, p.object().refs("ANM_").getFirst());
		Animation act = StartPose.startAct(set, p.instance().startAct());
		assertEquals("entrance", act.name);
		assertTrue(act.looping() && act.duration > 0 && act.hasSkeletalData());
		GowModel gm = GowModel.load(w).stream().filter(g -> g.name.equals(p.model())).findFirst().orElseThrow();
		assertTrue(gm.materials.contains("MAT_lambert254"));

		TextureLibrary lib = new TextureLibrary(w);
		MaterialResource m = lib.materials().stream().filter(x -> x.name().equals("MAT_lambert254")).findFirst().orElseThrow();
		String txr = m.layers().getFirst().texture();
		assertEquals("TXR_visuals07_file178", txr);
		TextureLibrary.Entry e = lib.textures().stream().filter(x -> x.txr().name().equals(txr)).findFirst().orElseThrow();
		assertEquals(20, e.gfx().frames());
		assertEquals(64, e.gfx().width());
		assertEquals(640, e.gfx().height(), "세로 스트립 — 프레임 한 장은 64×32");

		MaterialAnim.Anim an = MaterialAnim.load(w).get("MAT_lambert254");
		assertNotNull(an, "ANM_lambert254 (플립북)");
		assertTrue(an.loop());
		assertEquals(40, an.frames().length, "키 40 개 (1/30 초) 에 프레임 0..19");
		String[] frames = IntStream.range(0, 20).mapToObj(i -> "f" + i).toArray(String[]::new);
		for (float t = 0; t < 3f; t += 0.05f) {
			String got = MaterialFx.pickFrame(frames, "strip", an, t);
			assertNotEquals("strip", got, "스트립 전체를 붙이지 않는다");
			assertEquals("f" + an.frame(t), got);
		}
		assertEquals("strip", MaterialFx.pickFrame(null, "strip", an, 1f), "프레임 없는 텍스처는 그대로");
	}
}
