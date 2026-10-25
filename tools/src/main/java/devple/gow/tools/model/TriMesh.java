package devple.gow.tools.model;

import java.util.ArrayList;
import java.util.List;

/** 내보내기/미리보기용 단순 삼각형 메시 (서브메시 = 머티리얼 하나). */
public final class TriMesh {

	public static final class Sub {
		public final String name;
		public final String material;
		public final List<float[]> pos = new ArrayList<>();
		public final List<float[]> uv = new ArrayList<>();
		public final List<float[]> normal = new ArrayList<>();
		public final List<int[]> color = new ArrayList<>();
		public final List<int[]> tris = new ArrayList<>();
		public boolean hasUv;
		public boolean hasNormal;

		public Sub(String name, String material) {
			this.name = name;
			this.material = material;
		}
	}

	public final String name;
	public final List<Sub> subs = new ArrayList<>();
	/**
	 * 원본 모델 MDL +0x28 렌더 플래그 ({@link GowModel#renderFlags}, 모르면 0). 0x1000 이면 원작 VU 플래그 4 — 레이어 알파가 p28·p38 를 받는다
	 * (엔진 {@code WadAssets.materialFx}, 43 §30).
	 */
	public long renderFlags;

	public TriMesh(String name) {
		this.name = name;
	}

	/**
	 * 모델의 모든 메시/파트에서 lod 번째 그룹(없으면 마지막)을 모은다. lod 가 -1 이면 모든 LOD.
	 * 그룹 0 = 가까울 때(거리 &lt; |lodDistance|) 쓰는 고해상도. 비어 있으면(원거리 전용 대역 모델) 다음 그룹을 쓴다.
	 */
	public static TriMesh of(GowModel m, int lod) {
		TriMesh t = new TriMesh(m.name);
		t.renderFlags = m.renderFlags;
		for (GowMesh mesh : m.meshes) {
			for (int p = 0; p < mesh.parts.size(); p++) {
				GowMesh.Part part = mesh.parts.get(p);
				int pick = Math.min(lod, part.groups().size() - 1);
				while (pick >= 0 && pick + 1 < part.groups().size() && part.groups().get(pick).objects().isEmpty()) {
					pick++;
				}
				for (int g = 0; g < part.groups().size(); g++) {
					if (lod >= 0 && g != pick) {
						continue;
					}
					List<MeshObject> objs = part.groups().get(g).objects();
					for (int k = 0; k < objs.size(); k++) {
						MeshObject o = objs.get(k);
						String mat = o.materialIndex < m.materials.size() ? m.materials.get(o.materialIndex) : "MAT_unknown" + o.materialIndex;
						Sub s = new Sub(String.format("%s_p%d_l%d_o%d", mesh.name, p, g, k), mat);
						for (VertexBatch vb : o.batches) {
							t.addBatch(s, vb);
						}
						if (!s.tris.isEmpty()) {
							t.subs.add(s);
						}
					}
				}
			}
		}
		return t;
	}

	public static TriMesh of(MshMesh m) {
		TriMesh t = new TriMesh(m.name);
		Sub s = new Sub(m.name, null);
		s.hasNormal = true;
		for (int i = 0; i < m.pos.length; i++) {
			s.pos.add(m.pos[i]);
			s.normal.add(m.normal[i]);
		}
		for (int i = 0; i < m.tris.length; i += 3) {
			s.tris.add(new int[]{m.tris[i], m.tris[i + 1], m.tris[i + 2]});
		}
		t.subs.add(s);
		return t;
	}

	private void addBatch(Sub s, VertexBatch vb) {
		int base = s.pos.size();
		s.hasUv |= vb.uv != null;
		s.hasNormal |= vb.normal != null;
		for (int i = 0; i < vb.count; i++) {
			s.pos.add(new float[]{vb.x(i), vb.y(i), vb.z(i)});
			s.uv.add(vb.uv != null ? vb.uv[i] : new float[2]);
			s.normal.add(vb.normal != null ? vb.normal[i] : new float[3]);
			s.color.add(vb.color != null ? vb.color[i] : new int[]{128, 128, 128, 128});
		}
		int[] tr = vb.triangles();
		for (int i = 0; i < tr.length; i += 3) {
			int[] t = {base + tr[i], base + tr[i + 1], base + tr[i + 2]};
			if (vb.normal != null && facing(s, t) < 0) {
				int tmp = t[0];
				t[0] = t[1];
				t[1] = tmp;
			}
			s.tris.add(t);
		}
	}

	/** 면 법선 · (정점 법선 합). 양수면 정점 법선과 같은 방향. */
	private static double facing(Sub s, int[] t) {
		float[] a = s.pos.get(t[0]);
		float[] b = s.pos.get(t[1]);
		float[] c = s.pos.get(t[2]);
		double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
		double vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
		double fx = uy * vz - uz * vy, fy = uz * vx - ux * vz, fz = ux * vy - uy * vx;
		double nx = 0, ny = 0, nz = 0;
		for (int i : t) {
			float[] nn = s.normal.get(i);
			nx += nn[0];
			ny += nn[1];
			nz += nn[2];
		}
		return fx * nx + fy * ny + fz * nz;
	}

	public int vertexCount() {
		return subs.stream().mapToInt(s -> s.pos.size()).sum();
	}

	public int triangleCount() {
		return subs.stream().mapToInt(s -> s.tris.size()).sum();
	}

	/** {minX,minY,minZ,maxX,maxY,maxZ} */
	public float[] bounds() {
		float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (Sub s : subs) {
			for (float[] p : s.pos) {
				for (int k = 0; k < 3; k++) {
					b[k] = Math.min(b[k], p[k]);
					b[k + 3] = Math.max(b[k + 3], p[k]);
				}
			}
		}
		return b;
	}

	/**
	 * 감김 방향 검증: 면 법선과 정점 법선 합의 내적이 양수인 삼각형 비율 (법선 없는 메시는 NaN).
	 * 법선 기준 보정 후이므로 1.0 이어야 정상 (OBJ 는 반시계 = 앞면).
	 */
	public double windingAgreement() {
		long ok = 0;
		long n = 0;
		for (Sub s : subs) {
			if (!s.hasNormal) {
				continue;
			}
			for (int[] t : s.tris) {
				double d = facing(s, t);
				if (d != 0) {
					n++;
					if (d > 0) {
						ok++;
					}
				}
			}
		}
		return n == 0 ? Double.NaN : ok / (double) n;
	}

}
