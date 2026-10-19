package devple.gow.engine.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** CPU 노멀맵 생성기·부드러운 법선 (44 문서, 원작에 없는 선택 기능). */
class NormalMapGenTest {

	private static int gray(int v, int a) {
		return a << 24 | v << 16 | v << 8 | v;
	}

	@Test
	void flatInputGivesUpNormal() {
		int[] img = new int[16 * 16];
		Arrays.fill(img, gray(120, 255));
		for (boolean wrap : new boolean[]{true, false}) {
			int[] n = NormalMapGen.generate(img, 16, 16, wrap, NormalMaps.STRENGTH);
			for (int c : n) {
				assertEquals(NormalMapGen.FLAT, c);
				float[] v = NormalMapGen.unpack(c);
				assertEquals(0, v[0], 0.01);
				assertEquals(0, v[1], 0.01);
				assertEquals(1, v[2], 0.01);
			}
		}
	}

	@Test
	void horizontalRampTiltsAgainstGradient() {
		int w = 16, h = 8;
		int[] img = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				img[y * w + x] = gray(x * 10, 255); // 텍셀당 높이 10/255
			}
		}
		float s = 4f;
		int[] n = NormalMapGen.generate(img, w, h, false, s);
		float g = 10f / 255f;
		float[] expect = {-s * g, 0, 1};
		float l = (float) Math.sqrt(expect[0] * expect[0] + 1);
		float[] v = NormalMapGen.unpack(n[3 * w + 8]); // 안쪽 텍셀
		assertEquals(expect[0] / l, v[0], 0.01);
		assertEquals(0, v[1], 0.01);
		assertEquals(1 / l, v[2], 0.01);
		assertTrue(v[0] < 0, "높이가 +u 로 오르면 법선은 −u 로 기운다");
		// clamp: 가장자리 열은 한쪽 차이만 → 경사 절반, 감싸기면 0..150 이음이 반대 큰 경사
		float[] edgeClamp = NormalMapGen.unpack(n[3 * w + w - 1]);
		assertTrue(edgeClamp[0] < 0 && edgeClamp[0] > v[0]);
		int[] nw = NormalMapGen.generate(img, w, h, true, s);
		assertTrue(NormalMapGen.unpack(nw[3 * w + w - 1])[0] > 0, "감싸기: 마지막 열 다음은 높이 0");
	}

	@Test
	void verticalRampTiltsAlongV() {
		int w = 8, h = 16;
		int[] img = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				img[y * w + x] = gray(y * 10, 255);
			}
		}
		float[] v = NormalMapGen.unpack(NormalMapGen.generate(img, w, h, false, 4f)[8 * w + 4]);
		assertEquals(0, v[0], 0.01);
		assertTrue(v[1] < -0.1, "행(+v)으로 높이가 오르면 Y 는 음수");
	}

	@Test
	void alphaCutPixelsMakeNoEdges() {
		int w = 16, h = 16;
		int[] img = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				// 왼쪽 절반 불투명 회색, 오른쪽 절반 잘림 (색은 흰색이라 그대로면 큰 경사)
				img[y * w + x] = x < 8 ? gray(100, 255) : gray(255, 0);
			}
		}
		int[] n = NormalMapGen.generate(img, w, h, false, 4f);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				assertEquals(NormalMapGen.FLAT, n[y * w + x], "x=" + x + " y=" + y);
			}
		}
	}

	@Test
	void smoothNormalsIgnoreWindingAndUvSeams() {
		// 같은 평면 (y = 0) 의 두 삼각형: 감김 반대, 이음처럼 같은 위치 정점이 따로 있다
		List<float[]> pos = new ArrayList<>(List.of(
				new float[]{0, 0, 0}, new float[]{1, 0, 0}, new float[]{0, 0, 1},
				new float[]{1, 0, 0}, new float[]{0, 0, 1}, new float[]{1, 0, 1}));
		List<int[]> tris = List.of(new int[]{0, 1, 2}, new int[]{3, 5, 4});
		float[][] n = SmoothNormals.compute(pos, tris);
		for (float[] v : n) {
			assertEquals(1, Math.abs(v[1]), 1e-5);
		}
		assertEquals(n[1][1], n[3][1], 1e-6, "같은 위치 정점은 같은 법선");
	}
}
