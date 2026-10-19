package devple.gow.engine.combat;

import devple.gow.tools.anim.Mat4;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.anim.WeaponAttach;
import devple.gow.tools.level.BallHull;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 원작 동적 충돌 구(BallHull CDV)로 만든 공격/피격 볼륨 (10 문서 §5.4, 34 문서).
 * <ul>
 *   <li>구 = CDV ball (관절 로컬 중심, 반지름). 매 프레임 관절 월드 행렬 × 오브젝트 행렬로 월드 좌표 (0x13AAA8)</li>
 *   <li>부위(part) = ball 재질 엔트리의 PartType (+0x30). 공격 레코드 {@code Attack.part} 와 같은 번호
 *       (0x203150 → OnContact → 0x209AB0 비교). 오른손 무기 오브젝트(flags 0x200)는 2 → 3</li>
 *   <li>블레이드 = 별도 오브젝트 {@code maiblade} 를 {@code lWeapOH}/{@code rWeapOH} 에 부착, CDV_MAIBlade (part 2/3, 쌍 11)</li>
 *   <li>피격 볼륨 = PartType 1 (몸) 구 [추정: 원작은 구끼리 모든 쌍을 검사하지만 공격 부위가 아닌 접촉은 판정이 없다]</li>
 *   <li>판정 = 이전 프레임 → 현재 프레임 위치를 잇는 스윕 구 대 스윕 구 (빠른 블레이드가 빠져나가지 않게)</li>
 * </ul>
 * 오브젝트 행렬 = translation(pos) · rotateY(yaw): 모델 -Z(05 문서 정면, 루트 모션 방향)가 월드 (-sin yaw, -cos yaw).
 */
public final class HitVolumes {

	public static final int PART_BODY = 1, PART_LEFT = 2, PART_RIGHT = 3, PART_TWIN = 11;

	/** 구 하나의 원본 (관절 또는 무기 부착). */
	private record Src(int joint, float[] local, float radius, int part, float[] attach, int fx) {
	}

	/** 마지막 {@link #sweptHit} 적중: 피격 구의 SheetFX(MFX 템플릿, 0 = 없음)와 접촉점 (두 구 중심 사이, 반지름 비). 피격자 쪽에 기록. */
	private int lastFx;
	private final Vector3f lastPoint = new Vector3f();
	private boolean lastValid;

	private final List<Src> srcs = new ArrayList<>();
	private float[][] cur = new float[0][];
	private float[][] prev = new float[0][];
	private boolean primed;

	/** 스켈레톤 관절에 붙은 CDV 구 전부 추가. */
	public HitVolumes addBody(BallHull h, int jointCount) {
		for (int b = 0; b < h.balls.size(); b++) {
			if (h.ballJoint[b] < jointCount) {
				float[] ball = h.balls.get(b);
				srcs.add(new Src(h.ballJoint[b], new float[]{ball[0], ball[1], ball[2]}, ball[3], h.ballPart(b), null, h.ballFx(b)));
			}
		}
		resize();
		return this;
	}

	/**
	 * 무기 오브젝트 CDV 를 로케이터 관절에 부착.
	 *
	 * @param right 오른손 무기 (원작 오브젝트 flags 0x200: PartType 2 → 3)
	 */
	public HitVolumes addWeapon(WeaponAttach w, int locator, boolean right) {
		if (w == null || w.cdv == null) {
			return this;
		}
		float[][] bw = w.world(Mat4.identity());
		for (int b = 0; b < w.cdv.balls.size(); b++) {
			float[] ball = w.cdv.balls.get(b);
			int part = w.cdv.ballPart(b);
			if (right && part == PART_LEFT) {
				part = PART_RIGHT;
			}
			float[] at = bw[Math.min(w.cdv.ballJoint[b], bw.length - 1)];
			srcs.add(new Src(locator, new float[]{ball[0], ball[1], ball[2]}, ball[3], part, at, w.cdv.ballFx(b)));
		}
		resize();
		return this;
	}

	private void resize() {
		cur = new float[srcs.size()][4];
		prev = new float[srcs.size()][4];
		primed = false;
	}

	public boolean isEmpty() {
		return srcs.isEmpty();
	}

	/** 이 부위 번호의 구가 있는지. */
	public boolean hasPart(int part) {
		for (Src s : srcs) {
			if (s.part == part) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 새 프레임 포즈. 현재 위치가 이전 위치가 된다.
	 *
	 * @param world 관절 월드 행렬 (모델 공간, 행벡터)
	 */
	public void update(float[][] world, Vector3f pos, float yaw) {
		Matrix4f obj = new Matrix4f().translation(pos).rotateY(yaw);
		float[] tmp = new float[3];
		Vector3f v = new Vector3f();
		float[][] t = prev;
		prev = cur;
		cur = t;
		for (int i = 0; i < srcs.size(); i++) {
			Src s = srcs.get(i);
			float[] m = s.joint < world.length ? world[s.joint] : Mat4.identity();
			if (s.attach != null) {
				m = Mat4.mul(s.attach, m);
			}
			Mat4.transformPoint(m, s.local[0], s.local[1], s.local[2], tmp);
			float sc = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
			obj.transformPosition(v.set(tmp[0], tmp[1], tmp[2]));
			cur[i][0] = v.x;
			cur[i][1] = v.y;
			cur[i][2] = v.z;
			cur[i][3] = s.radius * sc;
		}
		if (!primed) {
			for (int i = 0; i < cur.length; i++) {
				prev[i] = cur[i].clone();
			}
			primed = true;
		}
	}

	/** 현재 월드 구 (x, y, z, r), 부위 필터 (-1 = 전부). */
	public List<float[]> spheres(int part) {
		List<float[]> out = new ArrayList<>();
		for (int i = 0; i < srcs.size(); i++) {
			if (part < 0 || srcs.get(i).part == part) {
				out.add(cur[i].clone());
			}
		}
		return out;
	}

	/**
	 * 공격 부위 part 의 구들이 이번 프레임 이동 중 상대의 몸(PartType 1) 구와 닿았는지 (스윕 판정).
	 * 상대에 몸 구가 없으면 모든 구를 피격 볼륨으로 쓴다.
	 */
	public boolean sweptHit(int part, HitVolumes victim) {
		boolean anyBody = victim.hasPart(PART_BODY);
		for (int i = 0; i < srcs.size(); i++) {
			if (srcs.get(i).part != part) {
				continue;
			}
			for (int k = 0; k < victim.srcs.size(); k++) {
				if (anyBody && victim.srcs.get(k).part != PART_BODY) {
					continue;
				}
				if (swept(prev[i], cur[i], victim.prev[k], victim.cur[k])) {
					float[] a = cur[i], v = victim.cur[k];
					float t = a[3] + v[3] > 1e-4f ? v[3] / (a[3] + v[3]) : 0.5f;
					victim.lastPoint.set(v[0] + (a[0] - v[0]) * t, v[1] + (a[1] - v[1]) * t, v[2] + (a[2] - v[2]) * t);
					victim.lastFx = victim.srcs.get(k).fx;
					victim.lastValid = true;
					return true;
				}
			}
		}
		return false;
	}

	/** 마지막으로 맞은 구의 SheetFX (MFX 템플릿 번호, 피격 기록이 없으면 −1). */
	public int lastHitFx() {
		return lastValid ? lastFx : -1;
	}

	/** 마지막 접촉점 (월드, 기록이 없으면 null). */
	public Vector3f lastHitPoint() {
		return lastValid ? new Vector3f(lastPoint) : null;
	}

	/** 몸(PartType 1) 구 중 첫 SheetFX (적중 기록 없이 피격 재질을 고를 때, 없으면 0). */
	public int bodyFx() {
		for (Src s : srcs) {
			if (s.part == PART_BODY && s.fx > 0) {
				return s.fx;
			}
		}
		return 0;
	}

	/** 부위 part 구의 이전·현재 중심 쌍 [x0,y0,z0, x1,y1,z1, r] (무기-월드 베기 판정용). */
	public List<float[]> sweeps(int part) {
		List<float[]> out = new ArrayList<>();
		for (int i = 0; i < srcs.size(); i++) {
			if (srcs.get(i).part == part) {
				out.add(new float[]{prev[i][0], prev[i][1], prev[i][2], cur[i][0], cur[i][1], cur[i][2], cur[i][3]});
			}
		}
		return out;
	}

	/** 두 구가 각각 a0→a1, b0→b1 로 선형 이동할 때 [0,1] 구간에서 최소 거리 ≤ 반지름 합인지. */
	static boolean swept(float[] a0, float[] a1, float[] b0, float[] b1) {
		float px = a0[0] - b0[0], py = a0[1] - b0[1], pz = a0[2] - b0[2];
		float vx = (a1[0] - a0[0]) - (b1[0] - b0[0]);
		float vy = (a1[1] - a0[1]) - (b1[1] - b0[1]);
		float vz = (a1[2] - a0[2]) - (b1[2] - b0[2]);
		float vv = vx * vx + vy * vy + vz * vz;
		float s = vv > 1e-9f ? Math.max(0, Math.min(1, -(px * vx + py * vy + pz * vz) / vv)) : 0;
		float dx = px + vx * s, dy = py + vy * s, dz = pz + vz * s;
		float r = Math.max(a0[3], a1[3]) + Math.max(b0[3], b1[3]);
		return dx * dx + dy * dy + dz * dz <= r * r;
	}

	// ---- 로딩 ----

	/** 스켈레톤 그룹이 참조하는 CDV_ (BallHull) — 없으면 null. */
	public static BallHull cdvFor(WadFile wad, Skeleton sk) {
		for (String ref : Skeleton.groupRefs(wad, sk.name)) {
			if (!ref.startsWith("CDV_")) {
				continue;
			}
			for (WadTag t : wad.resources()) {
				if (t.name().equals(ref) && t.data() != null && BallHull.is(t.data())) {
					return new BallHull(t.name(), t.data());
				}
			}
		}
		return null;
	}

	/** 적/소품: 자기 CDV 만. CDV 가 없으면 null. */
	public static HitVolumes forObject(WadFile wad, Skeleton sk) {
		BallHull h = cdvFor(wad, sk);
		return h == null ? null : new HitVolumes().addBody(h, sk.size());
	}

	/** 크레토스: CDV_hero + 블레이드 두 자루 (wpn = R_WPN&lt;costume&gt;_&lt;level&gt;.WAD, null 이면 몸만). */
	public static HitVolumes forHero(WadFile heroWad, Skeleton sk, WadFile wpn) {
		HitVolumes v = new HitVolumes();
		BallHull h = cdvFor(heroWad, sk);
		if (h != null) {
			v.addBody(h, sk.size());
		}
		if (wpn != null) {
			WeaponAttach blades = WeaponAttach.blades(wpn);
			int l = sk.indexOf("lWeapOH"), r = sk.indexOf("rWeapOH");
			if (l >= 0) {
				v.addWeapon(blades, l, false);
			}
			if (r >= 0) {
				v.addWeapon(blades, r, true);
			}
		}
		return v;
	}
}
