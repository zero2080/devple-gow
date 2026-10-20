package devple.gow.engine.light;

import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.wad.WadFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** SHG_ 해석 (0x162338) 과 그림자 볼륨 (0x162FB8) — 닫힘·방향·안팎 (47 문서 §7). */
class ShadowVolumeTest {

	static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_HERO0.WAD")), "gow-rom 없음");
		return rom;
	}

	/** 정육면체 조각 (관절 0, 반변 1): 면 고리 = 법선 기준 반시계, 모서리 참조 방향 포함. */
	static ShadowGeometry.Piece cube() {
		float[][] v = new float[8][];
		for (int i = 0; i < 8; i++) {
			v[i] = new float[]{(i & 1) != 0 ? 1 : -1, (i & 2) != 0 ? 1 : -1, (i & 4) != 0 ? 1 : -1};
		}
		int[][] loops = {{0, 2, 3, 1}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 4, 6, 2}, {1, 3, 7, 5}};
		float[][] n = {{0, 0, -1}, {0, 0, 1}, {0, -1, 0}, {0, 1, 0}, {-1, 0, 0}, {1, 0, 0}};
		Map<Long, Integer> idx = new HashMap<>();
		List<int[]> edges = new java.util.ArrayList<>();
		int[] corners = new int[24];
		int[][] refs = new int[24][];
		int[][] faces = new int[6][];
		for (int f = 0; f < 6; f++) {
			faces[f] = new int[]{4, 4 * f};
			int[] l = loops[f];
			// 띠 순서: 고리 0,1,2,3 → 띠 0,1,3,2
			corners[4 * f] = l[0];
			corners[4 * f + 1] = l[1];
			corners[4 * f + 2] = l[3];
			corners[4 * f + 3] = l[2];
			for (int k = 0; k < 4; k++) {
				int a = l[k], b = l[(k + 1) % 4];
				long key = (long) Math.min(a, b) << 32 | Math.max(a, b);
				Integer e = idx.get(key);
				if (e == null) {
					e = edges.size();
					idx.put(key, e);
					edges.add(new int[]{a, b});
				}
				refs[4 * f + k] = new int[]{e, edges.get(e)[0] == a ? 0 : 1};
			}
		}
		return new ShadowGeometry.Piece(new float[]{0, 0, 0, 1.8f}, 0, 0, v, n, edges.toArray(int[][]::new), corners, refs, faces);
	}

	/** 삼각형 목록이 닫혀 있고 방향이 일관 (방향 모서리마다 반대 모서리가 같은 수) 하며 부피가 양수인지. */
	static double checkClosed(ShadowVolume.Out out) {
		Map<String, Integer> dir = new HashMap<>();
		double vol = 0;
		float[] d = out.data;
		for (int t = 0; t < out.triangles(); t++) {
			String[] k = new String[3];
			for (int i = 0; i < 3; i++) {
				int o = t * 9 + i * 3;
				k[i] = d[o] + "," + d[o + 1] + "," + d[o + 2];
			}
			for (int i = 0; i < 3; i++) {
				dir.merge(k[i] + ">" + k[(i + 1) % 3], 1, Integer::sum);
			}
			Vector3f a = new Vector3f(d[t * 9], d[t * 9 + 1], d[t * 9 + 2]);
			Vector3f b = new Vector3f(d[t * 9 + 3], d[t * 9 + 4], d[t * 9 + 5]);
			Vector3f c = new Vector3f(d[t * 9 + 6], d[t * 9 + 7], d[t * 9 + 8]);
			vol += a.dot(new Vector3f(b).cross(c)) / 6.0;
		}
		for (var e : dir.entrySet()) {
			String[] ab = e.getKey().split(">");
			if (ab[0].equals(ab[1])) {
				continue;
			}
			assertEquals(e.getValue(), dir.getOrDefault(ab[1] + ">" + ab[0], 0), "반대 방향 모서리 짝 " + e.getKey());
		}
		return vol;
	}

	/** 반직선 (o, +y) 이 삼각형 목록을 몇 번 지나는지 (안팎 판정). */
	static int crossings(ShadowVolume.Out out, Vector3f o, Vector3f dir) {
		int n = 0;
		float[] d = out.data;
		for (int t = 0; t < out.triangles(); t++) {
			Vector3f a = new Vector3f(d[t * 9], d[t * 9 + 1], d[t * 9 + 2]);
			Vector3f e1 = new Vector3f(d[t * 9 + 3], d[t * 9 + 4], d[t * 9 + 5]).sub(a);
			Vector3f e2 = new Vector3f(d[t * 9 + 6], d[t * 9 + 7], d[t * 9 + 8]).sub(a);
			Vector3f p = new Vector3f(dir).cross(e2);
			float det = e1.dot(p);
			if (Math.abs(det) < 1e-9f) {
				continue;
			}
			Vector3f s = new Vector3f(o).sub(a);
			float u = s.dot(p) / det;
			Vector3f q = new Vector3f(s).cross(e1);
			float v = dir.dot(q) / det;
			float tt = e2.dot(q) / det;
			if (u >= 0 && v >= 0 && u + v <= 1 && tt > 0) {
				n++;
			}
		}
		return n;
	}

	@Test
	void cubeExtrudedVolumeIsClosedAndCoversRegionBehind() {
		ShadowGeometry.Piece c = cube();
		ShadowVolume.Out out = new ShadowVolume.Out();
		Vector3f light = new Vector3f(0.3f, 64 * 1.8f, 0.2f); // 위쪽 먼 점 (중심 + 방향 × 64·반경)
		int n = ShadowVolume.build(c, new Matrix4f(), light, ShadowVolume.MODE_EXTRUDE, null, ShadowVolume.extrudeFactor(1.8f), out);
		assertTrue(n > 0);
		assertTrue(checkClosed(out) > 0, "바깥 반시계 → 부피 양수");
		Vector3f up = new Vector3f(0, 1, 0);
		assertEquals(1, crossings(out, new Vector3f(0.13f, -5, 0.27f), up) % 2, "물체 아래 = 그림자 안");
		assertEquals(0, crossings(out, new Vector3f(0.13f, 5, 0.27f), up) % 2, "물체 위 = 밖");
		assertEquals(0, crossings(out, new Vector3f(4, -5, 0), up) % 2, "옆 = 밖");
		// 밀어낸 길이 ≈ 16·REACH = 256 (|v − L| ≈ 64·반경, f = 4 / 반경)
		assertEquals(1, crossings(out, new Vector3f(0.13f, -240, 0.27f), up) % 2);
		assertEquals(0, crossings(out, new Vector3f(0.13f, -300, 0.27f), up) % 2);
	}

	@Test
	void slabAndGroundModesClipToReceiverPlane() {
		ShadowGeometry.Piece c = cube();
		Vector4f floor = new Vector4f(0, 1, 0, 10); // y = −10
		Vector3f light = new Vector3f(0, 115, 0);
		Vector3f up = new Vector3f(0, 1, 0);
		ShadowVolume.Out slab = new ShadowVolume.Out();
		assertTrue(ShadowVolume.build(c, new Matrix4f(), light, ShadowVolume.MODE_SLAB, floor, 0, slab) > 0);
		assertTrue(checkClosed(slab) > 0);
		assertEquals(1, crossings(slab, new Vector3f(0.13f, -10.5f, 0.27f), up) % 2, "바닥 바로 위 (+2.4 안)");
		assertEquals(0, crossings(slab, new Vector3f(0.13f, -5, 0.27f), up) % 2, "물체와 바닥 사이는 밖 (판만)");
		assertEquals(0, crossings(slab, new Vector3f(0.13f, -12, 0.27f), up) % 2, "바닥 1.6 아래는 밖");
		ShadowVolume.Out ground = new ShadowVolume.Out();
		assertTrue(ShadowVolume.build(c, new Matrix4f(), light, ShadowVolume.MODE_GROUND, floor, 0, ground) > 0);
		assertTrue(checkClosed(ground) > 0);
		assertEquals(1, crossings(ground, new Vector3f(0.13f, -5, 0.27f), up) % 2, "물체에서 바닥까지 채움");
		assertEquals(0, crossings(ground, new Vector3f(0.13f, -12, 0.27f), up) % 2, "바닥 − 1.6 아래는 밖");
		// L 이 평면 아래면 그리지 않는다
		assertEquals(0, ShadowVolume.build(c, new Matrix4f(), new Vector3f(0, -50, 0), ShadowVolume.MODE_SLAB, floor, 0, new ShadowVolume.Out()));
	}

	@Test
	void chainSlabIsClosed() {
		ShadowVolume.Out out = new ShadowVolume.Out();
		Vector3f[] pts = {new Vector3f(0, 20, 0), new Vector3f(5, 22, 1), new Vector3f(10, 21, 3)};
		int n = ShadowVolume.chain(pts, 1.04f, new Vector3f(0, 500, 0), new Vector4f(0, 1, 0, 0), out);
		assertEquals(3 * 0 + 2 * 12, n);
		assertTrue(checkClosed(out) > 0);
	}

	/** 디스크의 모든 SHG_: 인덱스 범위, 닫힌 껍질 (모서리마다 참조 2 번, 방향 0/1 한 번씩), 면 고리 = 법선 기준 반시계. */
	@Test
	void allDiskShadowGeometryIsClosedAndCounterClockwise() throws IOException {
		Path rom = rom();
		int shg = 0, pieces = 0, open = 0, faces = 0, cw = 0;
		try (Stream<Path> s = Files.list(rom)) {
			for (Path p : s.filter(x -> x.toString().endsWith(".WAD")).sorted().toList()) {
				for (ShadowGeometry g : ShadowGeometry.load(WadFile.load(p))) {
					shg++;
					for (ShadowGeometry.Piece pc : g.pieces) {
						pieces++;
						int[] uses = new int[pc.edges().length], sides = new int[pc.edges().length];
						for (int fi = 0; fi < pc.faces().length; fi++) {
							int[] f = pc.faces()[fi];
							Vector3f nrm = new Vector3f();
							for (int k = 0; k < f[0]; k++) {
								int[] r = pc.refs()[f[1] + k];
								uses[r[0]]++;
								sides[r[0]] += r[1];
								int a = pc.edges()[r[0]][r[1]], b = pc.edges()[r[0]][1 - r[1]];
								assertTrue(pc.corners()[f[1] + k] < pc.verts().length);
								float[] va = pc.verts()[a], vb = pc.verts()[b];
								nrm.add(va[1] * vb[2] - va[2] * vb[1], va[2] * vb[0] - va[0] * vb[2], va[0] * vb[1] - va[1] * vb[0]);
							}
							float[] fn = pc.normals()[fi];
							faces++;
							if (nrm.dot(fn[0], fn[1], fn[2]) <= 0) {
								cw++;
							}
						}
						boolean closed = true;
						for (int e = 0; e < uses.length; e++) {
							assertTrue(uses[e] == 1 || uses[e] == 2, g.name + " 모서리 " + e);
							if (uses[e] == 2) {
								assertEquals(1, sides[e], g.name + " 모서리 " + e + " 방향 0 과 1 (일관된 방향)");
							} else {
								closed = false;
							}
						}
						if (!closed) {
							open++;
						}
					}
				}
			}
		}
		System.out.printf("SHG %d, 조각 %d, 열린 조각 %d, 면 %d (법선과 반대 고리 %d)%n", shg, pieces, open, faces, cw);
		assertTrue(cw * 200 < faces, "면 고리 = 법선 기준 반시계 (나머지는 거의 퇴화한 면)");
		assertEquals(139, shg, "SHG 수");
		assertTrue(pieces > shg);
		assertTrue(open * 20 < pieces, "대부분 닫힌 껍질");
	}

	/** SHG_hero: 19 조각, 관절이 영웅 스켈레톤 안, 실제 영웅 자세로 만든 볼륨 (세 모드) 이 모두 닫혀 있다. */
	@Test
	void heroVolumesClosedInAllModes() throws IOException {
		WadFile w = WadFile.load(rom().resolve("R_HERO0.WAD"));
		ShadowGeometry g = ShadowGeometry.forModel(w, "MDL_hero");
		assertNotNull(g);
		assertEquals(19, g.pieces.size());
		Skeleton sk = Skeleton.loadAll(w).stream().filter(x -> x.name.equals("hero")).findFirst().orElseThrow();
		float[][] joints = devple.gow.tools.anim.Pose.idle(sk).world(sk);
		Vector3f light = new Vector3f(40, 300, 25);
		Vector4f floor = new Vector4f(0, 1, 0, 5);
		for (int mode = 0; mode < 3; mode++) {
			ShadowVolume.Out out = new ShadowVolume.Out();
			int tris = 0;
			for (ShadowGeometry.Piece pc : g.pieces) {
				assertTrue(pc.joint() < sk.size(), "관절 " + pc.joint());
				ShadowVolume.Out one = new ShadowVolume.Out();
				int t = ShadowVolume.build(pc, new Matrix4f().set(joints[pc.joint()]), light, mode, floor, ShadowVolume.extrudeFactor(30), one);
				if (t > 0) {
					assertTrue(checkClosed(one) > 0, "모드 " + mode);
				}
				tris += t;
			}
			assertTrue(tris > 100, "모드 " + mode + " 삼각형 " + tris);
		}
	}
}
