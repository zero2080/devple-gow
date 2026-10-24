package devple.gow.tools.level;

import devple.gow.tools.anim.Mat4;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.anim.SkinnedModel;
import devple.gow.tools.model.GowMesh;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.model.MeshObject;
import devple.gow.tools.model.VertexBatch;
import devple.gow.tools.model.TriMesh;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 배치된 모델 → 월드 좌표 {@link TriMesh} (정적 레벨 지오메트리용 CPU 변환).
 * 정점별 관절은 {@link SkinnedModel} 의 규칙(GIF 태그 관절 쌍, 관절 로컬 파트)을 그대로 쓰고,
 * UV/정점색은 같은 순서로 만든 {@link TriMesh} 에서 가져온다. 체인(chainCount &gt; 1) 은 체인마다 복제되고 정점색은 그 체인의 색 REF 를 쓴다 (16 문서 §3.5).
 */
public final class PlacedMesh {

	private PlacedMesh() {
	}

	/**
	 * @param jointWorld 관절 월드 행렬 (인스턴스 포함, {@link LevelLayout#jointWorld})
	 */
	public static TriMesh build(GowModel gm, Skeleton sk, float[][] jointWorld, int lod) {
		return build(gm, sk, jointWorld, lod, null);
	}

	/**
	 * @param hidden 관절 숨김 (u8 가시성 트랙, {@link devple.gow.tools.anim.Animation#hiddenJoints}) — 파트가 쓰는 관절이 모두 숨김이면
	 *               그 파트를 빼고 굽는다 (엔진 AnimatedModel 과 같은 규칙). null 이면 모두.
	 */
	public static TriMesh build(GowModel gm, Skeleton sk, float[][] jointWorld, int lod, boolean[] hidden) {
		TriMesh src = TriMesh.of(gm, lod);
		Map<String, TriMesh.Sub> byKey = new HashMap<>();
		for (TriMesh.Sub s : src.subs) {
			byKey.put(s.name.replaceFirst("_l\\d+_o", "_o"), s);
		}
		Map<String, List<float[]>> rows = rowOffsets(gm, lod);
		Map<String, MeshObject> objects = meshObjects(gm, lod);
		float[][] skinMats = sk.skin(jointWorld);
		SkinnedModel skm = new SkinnedModel(gm, lod, -1);
		TriMesh out = new TriMesh(gm.name);
		float[] a = new float[3];
		float[] b = new float[3];
		float[] n = new float[3];
		for (SkinnedModel.Part p : skm.parts) {
			String k = p.name.replaceFirst("_i\\d+$", "");
			TriMesh.Sub s = byKey.get(k);
			List<float[]> row = rows.get(k);
			if (s == null || s.pos.size() != p.pos.size()) {
				continue;
			}
			if (hidden != null && allHidden(p, hidden, mats(jointWorld))) {
				continue;
			}
			float[][] mats = p.palette(p.jointLocal(sk) ? jointWorld : skinMats); // + 혼합 노드 (GowMesh.blendJoints)
			// 체인(인스턴스) 번호 → 그 체인의 베이크 정점색 (체인마다 다른 색 REF)
			List<int[]> chainCol = null;
			java.util.regex.Matcher im = INST.matcher(p.name);
			MeshObject mo = objects.get(k);
			if (im.find() && mo != null && mo.chainCount > 1) {
				int inst = Integer.parseInt(im.group(1));
				if (inst > 0) {
					chainCol = mo.chainColors(inst);
					if (chainCol.size() != s.pos.size()) {
						chainCol = null;
					}
				}
			}
			TriMesh.Sub d = new TriMesh.Sub(p.name, s.material);
			d.hasUv = s.hasUv;
			d.hasNormal = s.hasNormal;
			for (int i = 0; i < p.pos.size(); i++) {
				float[] v0 = p.pos.get(i);
				float[] r = row != null && i < row.size() ? row.get(i) : ZERO;
				float[] v = {v0[0] - r[0], v0[1] - r[1], v0[2] - r[2]};
				int[] j = p.joints.get(i);
				float w = p.weights.get(i);
				int ja = j[0] < mats.length ? j[0] : 0;
				int jb = j[1] < mats.length ? j[1] : ja;
				Mat4.transformPoint(mats[ja], v[0], v[1], v[2], a);
				Mat4.transformPoint(mats[jb], v[0], v[1], v[2], b);
				d.pos.add(new float[]{a[0] * w + b[0] * (1 - w), a[1] * w + b[1] * (1 - w), a[2] * w + b[2] * (1 - w)});
				float[] sn = s.normal.get(i);
				Mat4.transformDir(mats[ja], sn[0], sn[1], sn[2], n);
				float l = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
				d.normal.add(l > 0 ? new float[]{n[0] / l, n[1] / l, n[2] / l} : new float[3]);
				d.uv.add(s.uv.get(i));
				int[] cc = chainCol != null ? chainCol.get(i) : null;
				d.color.add(cc != null ? cc : s.color.get(i));
			}
			d.tris.addAll(s.tris);
			out.subs.add(d);
		}
		return out;
	}

	private static int mats(float[][] jointWorld) {
		return jointWorld.length;
	}

	/** 파트의 모든 정점 관절 (가중치 1 이 아니면 두 번째 관절도) 이 숨김인가. */
	static boolean allHidden(SkinnedModel.Part p, boolean[] hidden, int n) {
		if (p.pos.isEmpty()) {
			return false;
		}
		for (int i = 0; i < p.pos.size(); i++) {
			int[] j = p.joints.get(i);
			int ja = j[0] < n ? j[0] : j[1];
			int jb = j[1] < n ? j[1] : ja;
			ja = ja < n ? ja : 0;
			jb = jb < n ? jb : 0;
			if (ja >= hidden.length || !hidden[ja] || p.weights.get(i) < 1f && (jb >= hidden.length || !hidden[jb])) {
				return false;
			}
		}
		return true;
	}

	private static final float[] ZERO = new float[3];
	private static final java.util.regex.Pattern INST = java.util.regex.Pattern.compile("_i(\\d+)$");

	/** 키("메시_p파트_o오브젝트") → 메시 오브젝트 (LOD 선택 규칙은 {@link #rowOffsets} 와 같다). */
	private static Map<String, MeshObject> meshObjects(GowModel m, int lod) {
		Map<String, MeshObject> out = new HashMap<>();
		for (GowMesh mesh : m.meshes) {
			for (int p = 0; p < mesh.parts.size(); p++) {
				GowMesh.Part part = mesh.parts.get(p);
				int pick = Math.min(lod, part.groups().size() - 1);
				while (pick >= 0 && pick + 1 < part.groups().size() && part.groups().get(pick).objects().isEmpty()) {
					pick++;
				}
				if (pick < 0) {
					continue;
				}
				List<MeshObject> objs = part.groups().get(pick).objects();
				for (int k = 0; k < objs.size(); k++) {
					out.put(String.format("%s_p%d_o%d", mesh.name, p, k), objs.get(k));
				}
			}
		}
		return out;
	}

	/**
	 * 정점별 STROW 오프셋 ({@link VertexBatch#posRow}). 정적 메시(type 0x1D)의 위치에는 툴이 넣은 STROW 가 더해져 있는데,
	 * 이 값 ÷16 · 관절 스케일 = 관절 이동 + 인스턴스 위치 이므로 게임은 STROW 를 쓰지 않는다 → 배치 전에 뺀다.
	 * 키 = SkinnedModel 파트 이름에서 체인 번호를 뺀 것 ("메시_p파트_o오브젝트").
	 */
	private static Map<String, List<float[]>> rowOffsets(GowModel m, int lod) {
		Map<String, List<float[]>> out = new HashMap<>();
		for (GowMesh mesh : m.meshes) {
			for (int p = 0; p < mesh.parts.size(); p++) {
				GowMesh.Part part = mesh.parts.get(p);
				int pick = Math.min(lod, part.groups().size() - 1);
				while (pick >= 0 && pick + 1 < part.groups().size() && part.groups().get(pick).objects().isEmpty()) {
					pick++;
				}
				if (pick < 0) {
					continue;
				}
				List<MeshObject> objs = part.groups().get(pick).objects();
				for (int k = 0; k < objs.size(); k++) {
					List<float[]> l = new ArrayList<>();
					for (VertexBatch vb : objs.get(k).batches) {
						for (int i = 0; i < vb.count; i++) {
							l.add(vb.posRow);
						}
					}
					out.put(String.format("%s_p%d_o%d", mesh.name, p, k), l);
				}
			}
		}
		return out;
	}

	/** 모든 정점의 AABB {minX,minY,minZ,maxX,maxY,maxZ}. */
	public static float[] bounds(List<TriMesh> meshes) {
		float[] r = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (TriMesh m : meshes) {
			float[] b = m.bounds();
			for (int k = 0; k < 3; k++) {
				r[k] = Math.min(r[k], b[k]);
				r[k + 3] = Math.max(r[k + 3], b[k + 3]);
			}
		}
		return r;
	}
}
