package devple.gow.engine.level;

import devple.gow.engine.light.ShadowCasters;
import devple.gow.tools.fx.EnvParams;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SCR_Mirror 합성 후속 (43 문서 §12): 0x12A330 앞쪽 검사, 값 [0] 흐림, 거울 뷰 가시 칸 (0x152EC8 공유 마스크), 레벨 GO 그림자 대상 (47 §11).
 */
class MirrorCompositeTest {

	private static WadFile wad(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	@Test
	void frontSideAndDistance() {
		// 0x12A330: 카메라가 모델 Y 축 쪽일 때만 거리 |중심 − 눈|, 아니면 FLT_MAX — 시작값 1e7 (0x4B189680) 이라 뒤쪽 거울은 뽑히지 않는다
		Mirrors.Mirror floor = new Mirrors.Mirror("floor", new Vector3f(0, -1, 0), 0, 0, 0, 0)
				.withAxis(new Vector3f(0, 2, 0), new Vector3f(0, 0, 0));
		Mirrors.Mirror ceiling = new Mirrors.Mirror("ceiling", new Vector3f(0, 1, 0), 100, 0, 0, 0)
				.withAxis(new Vector3f(0, 1, 0), new Vector3f(0, 100, 0));
		assertEquals(1f, floor.axis().length(), 1e-6f);
		Vector3f eye = new Vector3f(0, 60, 0); // 천장 거울 (40) 이 더 가깝지만 그 축 (위) 의 뒤쪽
		assertEquals(Float.MAX_VALUE, ceiling.distance(eye));
		assertEquals(60f, floor.distance(eye), 1e-4f);
		assertSame(floor, Mirrors.nearest(List.of(ceiling, floor), eye));
		assertNull(Mirrors.nearest(List.of(ceiling), eye));
		// 축이 없으면 (기존 생성자) 앞쪽 검사 없음
		Mirrors.Mirror plain = new Mirrors.Mirror("p", new Vector3f(0, 1, 0), 0, 0, 0, 0, new Vector3f(0, 0, 0));
		assertEquals(10f, plain.distance(new Vector3f(0, -10, 0)), 1e-4f);
		assertNull(Mirrors.nearest(List.of(plain), new Vector3f(2e7f, 0, 0)), "1e7 보다 먼 거울은 고르지 않음");
	}

	@Test
	void blurFlagFromValue0() throws Exception {
		// 값 [0] ≠ 0 → 후처리 노드 +0x14 켬 (0x12A930): PAND01BB gomirrorfloor [1 0], ATHN03A gogroup2839 [0 1]
		Map<String, int[]> p = Mirrors.owners(new EnvParams(wad("PAND01BB")));
		int[] v = p.get("gomirrorfloor");
		assertArrayEquals(new int[]{1, 0}, v);
		assertTrue(new Mirrors.Mirror("gomirrorfloor", new Vector3f(0, 1, 0), 0, 0, v[0], v[1]).blur());
		int[] w = Mirrors.owners(new EnvParams(wad("ATHN03A"))).get("gogroup2839");
		assertArrayEquals(new int[]{0, 1}, w);
		assertFalse(new Mirrors.Mirror("g", new Vector3f(0, 1, 0), 0, 0, w[0], w[1]).blur());
	}

	@Test
	void mirrorViewSharesContextMask() throws Exception {
		// 0x152EC8: 뷰마다 칸을 같은 마스크에 누적한 뒤 모든 뷰에 같은 마스크 → 반사 눈이 칸 밖이면 주 뷰 집합 그대로 (ATHN04B 바닥 거울,
		// 주 눈 (2352, −50, 1242) 은 칸 y −96..64 안, 반사 눈 y −111 은 밖)
		LevelData d = new LevelData(wad("ATHN04B"));
		float[] main = {2352f, -50f, 1242f}, refl = {2352f, -111f, 1242f};
		Set<String> mainOnly = d.contextHidden(main[0], main[1], main[2]);
		assertEquals(mainOnly, d.contextHidden(new float[][]{refl, main}));
		assertTrue(d.contextHidden(refl[0], refl[1], refl[2]).size() > mainOnly.size(), "반사 눈 혼자면 더 많이 숨는다 (이전 근사가 아님)");
		// 누적은 뷰 순서대로 (뷰마다 형식 3 끔 → 켬): 뒤 뷰의 칸이 끄는 컨텍스트는 앞 뷰가 켰어도 꺼진다 (PAND01BB 바깥 칸 ↔ 복도 칸)
		LevelData p = new LevelData(wad("PAND01BB"));
		float[] outside = {-1700f, 300f, -500f}, hall = {-2816f, -222.5f, -791f};
		Set<String> oh = p.contextHidden(new float[][]{outside, hall});
		assertTrue(oh.contains("gosandarena"), "복도 칸이 bvoutside 를 끈다");
		assertFalse(oh.contains("gohallsectionb"));
		Set<String> ho = p.contextHidden(new float[][]{hall, outside});
		assertFalse(ho.contains("gosandarena"));
		assertTrue(ho.contains("gohallsectionb"), "바깥 칸이 bvhall 을 끈다");
	}

	@Test
	void levelGoShadowTargets() throws Exception {
		// SCR_Shadow (0x2465B0) 소유 GO: ATHN05A goaosks / 레벨 AI1 템플릿 오브젝트 (원점의 aoxf1 …) 는 그림자 대상에서 뺀다
		assertEquals(Set.of("goaosks"), LevelScene.scriptOwners(new EnvParams(wad("ATHN05A")), "SCR_Shadow"));
		assertTrue(LevelScene.aiTemplateObjects(wad("ATHN01D")).contains("aoxf1"));
	}

	@Test
	void shadowPauseKeepsCollected() {
		ShadowCasters sc = new ShadowCasters();
		sc.begin();
		assertTrue(sc.collecting());
		sc.pause(); // 거울 반사 패스
		assertFalse(sc.collecting());
		sc.resume();
		assertTrue(sc.collecting());
	}
}
