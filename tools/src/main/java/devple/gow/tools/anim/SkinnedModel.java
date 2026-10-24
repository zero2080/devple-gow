package devple.gow.tools.anim;

import devple.gow.tools.model.GowMesh;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.model.MeshObject;
import devple.gow.tools.model.TriMesh;
import devple.gow.tools.model.VertexBatch;

import java.util.ArrayList;
import java.util.List;

/**
 * CPU 스키닝 검증용 메시: 정점마다 (관절 A, 관절 B, 가중치 w) 를 붙인다.
 * <pre>
 * 배치 앞의 GIF 태그 블록(배치 V4-32 의 최소 VU 주소부터 연속, 더블 버퍼라 0/341/683 등) 하나 = NLOOP 개 정점.
 *   REGS 상위 32비트 = (A&lt;&lt;12) | (B&lt;&lt;2)  → A, B = 오브젝트 jointMap 인덱스
 *   정점 w 하위 15비트 = 가중치 (4096 = 1.0) → 관절 A 에 w, 관절 B 에 1-w  (강체 블록은 A==B, w=0)
 *   태그 bit45(0x20 of byte5) = "스티치" 블록: bit44 가 켜진 블록에서는 홀수 번째 정점이 다음 블록의 관절 쌍을,
 *   꺼진 블록에서는 이전 블록의 관절 쌍을 쓴다 (블록 경계의 이음매 정점).
 * 포즈 위치 = w · (p · M[A]) + (1-w) · (p · M[B])
 *   M = invBind · world (파트 jointRefs[0] == 0, 정점이 모델 공간 바인드 포즈)
 *   M = world          (파트 jointRefs[0] != 0, 정점이 관절 로컬 — 미노타우로스 다리, 도끼, 부서지는 통 조각)
 * </pre>
 */
public final class SkinnedModel {

	public static final class Part {
		public final String name;
		public final String material;
		public final List<float[]> pos = new ArrayList<>();
		public final List<float[]> normal = new ArrayList<>();
		public final List<float[]> uv = new ArrayList<>();
		/** RGBA, PS2 0x80 = 1.0 */
		public final List<int[]> color = new ArrayList<>();
		public boolean hasUv;
		public boolean hasNormal;
		/** {jointA, jointB} 스켈레톤 인덱스. */
		public final List<int[]> joints = new ArrayList<>();
		public final List<Float> weights = new ArrayList<>();
		public final List<int[]> tris = new ArrayList<>();
		/** 디버그: GIF 태그 상위 워드 (q1, q2) 종류. */
		public final java.util.Set<String> tagKinds = new java.util.TreeSet<>();
		/** 메시 파트의 jointRefs[0] (부착 관절). 0 이 아니면 정점이 관절 로컬 공간. */
		public final int attachJoint;

		Part(String name, String material, int attachJoint) {
			this.name = name;
			this.material = material;
			this.attachJoint = attachJoint;
		}

		/**
		 * 스켈레톤을 모를 때의 예전 규칙 (부착 관절 ≠ 0 이면 관절 로컬). 스켈레톤이 있으면 {@link #jointLocal(Skeleton)} 을 쓴다.
		 */
		public boolean jointLocal() {
			return attachJoint != 0;
		}

		/**
		 * true 면 inverse bind 없이 관절 월드 행렬을 그대로 쓴다 (정점이 관절 로컬 공간).
		 * <p>
		 * 부착 관절 (파트 jointRefs[0]) 이 0 이 아니고 <b>그 관절에 inverse bind 가 있을 때만</b> 관절 로컬이다 — 미노타우로스 다리 (STB),
		 * R_SKD 몸 (턱), 히어로 블레이드 등: 그 파트들의 inverse bind 는 보통 모델 공간 (invBind·bindWorld = I) 이고 정점은 관절마다 강체 [확실: 데이터].
		 * 부착 관절에 inverse bind 가 없으면 (Ares {@code group9} 배율 2.91 노드, TIT {@code scale}, DMCdog {@code dogHead}, 키클롭스 피 관절,
		 * 메두사 광선 {@code trail1} …) 파트 정점은 <b>부착 관절 공간의 바인드 메시</b>이고 inverse bind 가 그 공간 기준이다
		 * (invBind·bindWorld = bindWorld[부착], 오차 0.000 — 43 §16) → 모델 공간 파트처럼 skin (invBind·world) 을 쓴다.
		 * 이전 규칙 (부착 ≠ 0 이면 무조건 world) 은 그런 파트를 관절 위치만큼 한 번 더 옮겨 몸이 늘어난 삼각형으로 찢어졌다
		 * (도시 위 거대한 아레스, 키클롭스 R_CY2/CY3/CYW 의 노란·분홍 큰 삼각형) [추정: 원작 VU1 팔레트 미해독, 데이터 일치].
		 */
		public boolean jointLocal(Skeleton sk) {
			if (attachJoint == 0) {
				return false;
			}
			return sk == null || attachJoint >= sk.size() || sk.joints.get(attachJoint).hasInverseBind();
		}
	}

	public final String name;
	public final List<Part> parts = new ArrayList<>();
	public final List<String> warnings = new ArrayList<>();
	public int stitchBlocks;
	public int blocks;

	/**
	 * @param instance chainCount &gt; 1 인 오브젝트에서 쓸 체인(인스턴스) 번호. -1 이면 모든 인스턴스를 각각 추가.
	 */
	public SkinnedModel(GowModel m, int lod, int instance) {
		this(m, lod, instance, false);
	}

	/**
	 * @param variant true 면 LOD 번호를 <b>모델 변형</b>으로 쓴다 (원작 모델 컴포넌트 +0x120 강제 LOD, 판도라 수호자 BMI 단계 0/1/2+ → 2/1/0,
	 *                37 문서 §10): 파트마다 그룹[min(lod, n−1)], 비어 있으면 그룹 0, 그것도 비면 그리지 않음. false = 거리 LOD (비면 다음 그룹)
	 */
	public SkinnedModel(GowModel m, int lod, int instance, boolean variant) {
		this.name = m.name;
		for (GowMesh mesh : m.meshes) {
			for (int p = 0; p < mesh.parts.size(); p++) {
				GowMesh.Part part = mesh.parts.get(p);
				int pick = Math.min(lod, part.groups().size() - 1);
				if (variant) {
					if (pick >= 0 && part.groups().get(pick).objects().isEmpty()) {
						pick = 0;
					}
				} else {
					while (pick >= 0 && pick + 1 < part.groups().size() && part.groups().get(pick).objects().isEmpty()) {
						pick++;
					}
				}
				if (pick < 0) {
					continue;
				}
				List<MeshObject> objs = part.groups().get(pick).objects();
				for (int k = 0; k < objs.size(); k++) {
					MeshObject o = objs.get(k);
					String mat = o.materialIndex < m.materials.size() ? m.materials.get(o.materialIndex) : "MAT_unknown" + o.materialIndex;
					int nInst = o.jointMaps.length;
					for (int inst = 0; inst < nInst; inst++) {
						if (instance >= 0 && inst != Math.min(instance, nInst - 1)) {
							continue;
						}
						int attach = part.jointRefs().length > 0 ? part.jointRefs()[0] : 0;
						Part sp = new Part(String.format("%s_p%d_o%d_i%d", mesh.name, p, k, inst), mat, attach);
						for (VertexBatch vb : o.batches) {
							addBatch(sp, vb, o.jointMaps[inst], o.offset);
						}
						if (!sp.tris.isEmpty()) {
							parts.add(sp);
						}
					}
				}
			}
		}
	}

	private void addBatch(Part sp, VertexBatch vb, int[] jointMap, int objOff) {
		// GIF 태그 블록 (VU 주소 0,1,2...)
		List<int[]> tags = new ArrayList<>();
		int sum = 0;
		int first = vb.qwordAddr.stream().mapToInt(Integer::intValue).min().orElse(0); // 더블 버퍼 기준 주소
		for (int addr = first; sum < vb.count; addr++) {
			int idx = vb.qwordAddr.indexOf(addr);
			if (idx < 0) {
				break;
			}
			int[] q = vb.qwords.get(idx);
			tags.add(q);
			sum += q[0] & 0x7FFF;
		}
		if (sum != vb.count) {
			warnings.add(String.format("obj@%x GIF 블록 NLOOP 합 %d != 정점 %d (VU 주소 %s)", objOff, sum, vb.count, vb.qwordAddr));
		}
		int[] ja = new int[vb.count];
		int[] jb = new int[vb.count];
		int v = 0;
		int stitch = 0;
		for (int t = 0; t < tags.size(); t++) {
			int[] q = tags.get(t);
			int n = q[0] & 0x7FFF;
			boolean st = (q[1] & 0x2000) != 0;
			boolean push = (q[1] & 0x1000) != 0;
			blocks++;
			sp.tagKinds.add(String.format("%08x/%08x", q[1], q[2]));
			if (st) {
				stitchBlocks++;
			}
			for (int i = 0; i < n && v < vb.count; i++, v++) {
				int[] use = q;
				if (st) {
					if (push) {
						if (stitch % 2 != 0 && t + 1 < tags.size()) {
							use = tags.get(t + 1);
						}
						stitch++;
					} else {
						stitch--;
						if (stitch % 2 != 0 && t > 0) {
							use = tags.get(t - 1);
						}
					}
				}
				ja[v] = map(jointMap, (use[3] >>> 12) & 0xF);
				jb[v] = map(jointMap, (use[3] >>> 2) & 0x3F);
			}
		}
		int base = sp.pos.size();
		sp.hasUv |= vb.uv != null;
		sp.hasNormal |= vb.normal != null;
		for (int i = 0; i < vb.count; i++) {
			sp.pos.add(new float[]{vb.x(i), vb.y(i), vb.z(i)});
			sp.normal.add(vb.normal != null ? vb.normal[i] : new float[3]);
			sp.uv.add(vb.uv != null ? vb.uv[i] : new float[2]);
			sp.color.add(vb.color != null ? vb.color[i] : new int[]{128, 128, 128, 128});
			sp.joints.add(new int[]{ja[i], jb[i]});
			sp.weights.add(vb.weight(i) / 4096f);
		}
		int[] tr = vb.triangles();
		for (int i = 0; i < tr.length; i += 3) {
			sp.tris.add(new int[]{base + tr[i], base + tr[i + 1], base + tr[i + 2]});
		}
	}

	private int map(int[] jointMap, int slot) {
		if (slot < jointMap.length) {
			return jointMap[slot];
		}
		warnings.add("jointMap 범위 밖 슬롯 " + slot);
		return 0;
	}

	/** 포즈 적용 TriMesh (미리보기 렌더러 입력). pose 가 null 이면 원본 정점. */
	public TriMesh posed(Skeleton sk, Pose pose) {
		if (pose == null) {
			return posed(sk, null, null);
		}
		float[][] world = pose.world(sk);
		return posed(sk, world, sk.skin(world));
	}

	/** {@link #posed(Skeleton, float[][], float[][])} 를 스켈레톤 없이 (예전 규칙 {@link Part#jointLocal()}). */
	public TriMesh posed(float[][] world, float[][] skinMats) {
		return posed(null, world, skinMats);
	}

	/**
	 * 정점 변환. 모델 공간 파트는 skin(= invBind·world), 관절 로컬 파트({@link Part#jointLocal(Skeleton)})는 world 를 쓴다.
	 * 둘 다 null 이면 원본 정점. sk 가 null 이면 예전 규칙 (부착 관절 ≠ 0 → 관절 로컬).
	 */
	public TriMesh posed(Skeleton sk, float[][] world, float[][] skinMats) {
		TriMesh t = new TriMesh(name);
		float[] a = new float[3];
		float[] b = new float[3];
		for (Part p : parts) {
			TriMesh.Sub s = new TriMesh.Sub(p.name, p.material);
			float[][] skin = (sk != null ? p.jointLocal(sk) : p.jointLocal()) ? world : skinMats;
			s.hasNormal = false;
			for (int i = 0; i < p.pos.size(); i++) {
				float[] v = p.pos.get(i);
				if (skin == null) {
					s.pos.add(v.clone());
				} else {
					int[] j = p.joints.get(i);
					float w = p.weights.get(i);
					int ja = j[0] < skin.length ? j[0] : j[1];
					int jb = j[1] < skin.length ? j[1] : j[0];
					if (ja >= skin.length) {
						ja = jb = 0;
					}
					Mat4.transformPoint(skin[ja], v[0], v[1], v[2], a);
					Mat4.transformPoint(skin[jb], v[0], v[1], v[2], b);
					s.pos.add(new float[]{a[0] * w + b[0] * (1 - w), a[1] * w + b[1] * (1 - w), a[2] * w + b[2] * (1 - w)});
				}
				s.uv.add(new float[2]);
				s.normal.add(new float[3]);
				s.color.add(new int[]{128, 128, 128, 128});
			}
			s.tris.addAll(p.tris);
			t.subs.add(s);
		}
		return t;
	}

	/** 스켈레톤 범위 밖 관절을 참조하는 정점 수 {가중치&gt;0 으로 쓰는 수, 전체 참조 수}. */
	public int[] outOfRange(int joints) {
		int used = 0;
		int any = 0;
		for (Part p : parts) {
			for (int i = 0; i < p.pos.size(); i++) {
				int[] j = p.joints.get(i);
				float w = p.weights.get(i);
				boolean oa = j[0] >= joints, ob = j[1] >= joints;
				if (oa || ob) {
					any++;
					if ((oa && w > 0) || (ob && w < 1)) {
						used++;
					}
				}
			}
		}
		return new int[]{used, any};
	}

	public int vertexCount() {
		return parts.stream().mapToInt(p -> p.pos.size()).sum();
	}
}
