package devple.gow.tools.anim;

import devple.gow.tools.level.BallHull;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.model.TriMesh;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;

import java.util.List;

/**
 * 블레이드(혼돈의 블레이드) = 별도 오브젝트. R_WPN&lt;costume&gt;_&lt;level&gt;.WAD 의 {@code maiblade}
 * (CXT 3관절: MAIBlade / BDepoly6 / BDepoly3), {@code MDL_MAIBlade}, {@code CDV_MAIBlade}.
 * 히어로 로케이터 관절 {@code lWeapOH}(107) / {@code rWeapOH}(109) 의 월드 행렬에 블레이드 오브젝트 루트를 둔다
 * (위치 = ANM 타입 0 디스크립터 3 — 이동 배열에 이어 쓰는 로케이터 트랙, 회전 = 타입 0 디스크립터 0 — 05 문서 2.8.1). 블레이드 메시·CDV 는 블레이드 관절 로컬이다.
 */
public final class WeaponAttach {

	public final Skeleton skeleton;
	public final SkinnedModel model;
	public final BallHull cdv;

	public WeaponAttach(Skeleton skeleton, SkinnedModel model, BallHull cdv) {
		this.skeleton = skeleton;
		this.model = model;
		this.cdv = cdv;
	}

	/** R_WPN WAD 에서 블레이드 한 벌을 읽는다 (CDV/모델이 없으면 해당 필드 null). */
	public static WeaponAttach blades(WadFile wpn) {
		Skeleton sk = Skeleton.loadAll(wpn).stream().filter(s -> s.name.equalsIgnoreCase("maiblade")).findFirst().orElse(null);
		if (sk == null) {
			return null;
		}
		SkinnedModel sm = null;
		for (GowModel m : GowModel.load(wpn)) {
			if (m.name.equalsIgnoreCase("MDL_MAIBlade")) {
				sm = new SkinnedModel(m, 0, 0);
			}
		}
		BallHull hull = null;
		for (WadTag t : wpn.resources()) {
			if (t.name().equalsIgnoreCase("CDV_MAIBlade") && t.data() != null && BallHull.is(t.data())) {
				hull = new BallHull(t.name(), t.data());
			}
		}
		return new WeaponAttach(sk, sm, hull);
	}

	/** 블레이드 관절 월드 행렬 (부착 관절 행렬 기준, 아이들 포즈). */
	public float[][] world(float[] attach) {
		float[][] w = Pose.idle(skeleton).world(skeleton);
		float[][] out = new float[w.length][];
		for (int j = 0; j < w.length; j++) {
			out[j] = Mat4.mul(w[j], attach);
		}
		return out;
	}

	/** 부착 행렬에 놓인 블레이드 메시. */
	public TriMesh posed(float[] attach) {
		float[][] w = world(attach);
		return model.posed(skeleton, w, skeleton.skin(w));
	}

	/**
	 * 구 목록 (x, y, z, r) 을 3개의 대원(ring) 리본으로 — 미리보기 렌더러에서 몸을 가리지 않는 와이어 구.
	 *
	 * @param rgb 서브메시 색 (PreviewRenderer "#RRGGBB" 규칙)
	 */
	public static TriMesh.Sub rings(List<float[]> spheres, int rgb) {
		TriMesh.Sub s = new TriMesh.Sub(String.format("#%06X", rgb & 0xFFFFFF), null);
		int seg = 32;
		for (float[] sp : spheres) {
			float w = Math.max(0.35f, sp[3] * 0.05f);
			for (int axis = 0; axis < 3; axis++) {
				int base = s.pos.size();
				for (int i = 0; i < seg; i++) {
					double a = 2 * Math.PI * i / seg;
					float c = (float) Math.cos(a), n = (float) Math.sin(a);
					for (float rr : new float[]{sp[3] - w, sp[3] + w}) {
						float[] p = switch (axis) {
							case 0 -> new float[]{sp[0] + c * rr, sp[1] + n * rr, sp[2]};
							case 1 -> new float[]{sp[0] + c * rr, sp[1], sp[2] + n * rr};
							default -> new float[]{sp[0], sp[1] + c * rr, sp[2] + n * rr};
						};
						s.pos.add(p);
						s.uv.add(new float[2]);
						s.normal.add(new float[3]);
						s.color.add(new int[]{128, 128, 128, 128});
					}
				}
				for (int i = 0; i < seg; i++) {
					int a0 = base + i * 2, a1 = base + ((i + 1) % seg) * 2;
					s.tris.add(new int[]{a0, a0 + 1, a1 + 1});
					s.tris.add(new int[]{a0, a1 + 1, a1});
				}
			}
		}
		return s;
	}

	/** 관절 월드 행렬로 BallHull 구를 포즈 (반지름은 관절 스케일 = 첫 행 길이 배). */
	public static float[] posedBall(BallHull h, int ball, float[] jointWorld) {
		float[] b = h.balls.get(ball);
		float[] o = new float[3];
		Mat4.transformPoint(jointWorld, b[0], b[1], b[2], o);
		float sc = (float) Math.sqrt(jointWorld[0] * jointWorld[0] + jointWorld[1] * jointWorld[1] + jointWorld[2] * jointWorld[2]);
		return new float[]{o[0], o[1], o[2], b[3] * sc};
	}
}
