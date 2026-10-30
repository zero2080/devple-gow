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
 *   <li>피격 볼륨 = 재질 bitfield1 비트 0 TakeDamage 구 (OnContact 0x2040A8) [확실] — 몸·머리·다리, 일부 적 무기</li>
 *   <li>판정 = 이전 프레임 → 현재 프레임 위치를 잇는 스윕 구 대 스윕 구 (빠른 블레이드가 빠져나가지 않게)</li>
 * </ul>
 * 오브젝트 행렬 = translation(pos) · rotateY(yaw): 모델 -Z(05 문서 정면, 루트 모션 방향)가 월드 (-sin yaw, -cos yaw).
 */
public final class HitVolumes {

	public static final int PART_BODY = 1, PART_LEFT = 2, PART_RIGHT = 3, PART_TWIN = 11;
	/** 아르테미스의 검 (CDV_berserkBlade 의 PartType, BER* 공격 레코드 부위). */
	public static final int PART_ARTEMIS = 10;

	/** 구 하나의 원본 (관절 또는 무기 부착). */
	private record Src(int joint, float[] local, float radius, int part, float[] attach, int fx, boolean hurt) {
	}

	/** 재질 bitfield1 (+0x1C) 비트 0 "TakeDamage" — OnContact 0x2040A8 이 피격 쪽 구에 요구 ([0x3376F0] = bitfield1, 0x1BECE4). */
	public static final int TAKE_DAMAGE = 0x1;

	private static boolean hurt(BallHull h, int b) {
		int m = h.ballMaterial[b];
		return m >= 0 && m < h.materialRaw.length && (h.materialRaw[m][1] & TAKE_DAMAGE) != 0;
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
				srcs.add(new Src(h.ballJoint[b], new float[]{ball[0], ball[1], ball[2]}, ball[3], h.ballPart(b), null, h.ballFx(b), hurt(h, b)));
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
			srcs.add(new Src(locator, new float[]{ball[0], ball[1], ball[2]}, ball[3], part, at, w.cdv.ballFx(b), hurt(w.cdv, b)));
		}
		resize();
		return this;
	}

	private void resize() {
		filters();
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
		update(world, pos, yaw, 1f);
	}

	/** @param scale 개체 배율 (적 ScaleCenter — 렌더 {@code EnemyFighter.world} 와 같은 행렬) */
	public void update(float[][] world, Vector3f pos, float yaw, float scale) {
		Matrix4f obj = new Matrix4f().translation(pos).rotateY(yaw).scale(scale);
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
			cur[i][3] = s.radius * sc * scale;
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
	 * 자기 CDV 의 현재 월드 구 (x, y, z, r) — 부착 무기 오브젝트 (별도 GO, 범주 +0xF4 = 0 → 0x80000000) 의 구는 뺀다.
	 * 원작 충돌 세계 0x29C978 에 오른 생물 엔트리 = 이 구 하나씩 (경계 0x13A998 = 관절 행렬 × 로컬 중심, 반지름 × 관절 배율) [확실].
	 * 첫 {@link #update} 전이면 null. 반환 배열은 내부 버퍼 (읽기만).
	 */
	public List<float[]> ownSpheres() {
		if (!primed) {
			return null; // 아직 포즈 없음 (첫 갱신 전) — 알 수 없음
		}
		List<float[]> out = new ArrayList<>();
		for (int i = 0; i < srcs.size(); i++) {
			if (srcs.get(i).attach == null) {
				out.add(cur[i]);
			}
		}
		return out;
	}

	/** 피격 구 k 인가: 재질 TakeDamage (0x2040A8). 그런 구가 하나도 없는 볼륨 (재질 정보 없음) 은 몸 (PartType 1), 그것도 없으면 전부. */
	private boolean victimSphere(int k) {
		if (anyHurt) {
			return srcs.get(k).hurt;
		}
		return !anyBody || srcs.get(k).part == PART_BODY;
	}

	private boolean anyHurt, anyBody;

	/** 피격 구 (TakeDamage) 의 PartType 집합 — 검증용. */
	public java.util.Set<Integer> hurtParts() {
		java.util.Set<Integer> s = new java.util.HashSet<>();
		for (int k = 0; k < srcs.size(); k++) {
			if (victimSphere(k)) {
				s.add(srcs.get(k).part);
			}
		}
		return s;
	}

	private void filters() {
		anyHurt = srcs.stream().anyMatch(Src::hurt);
		anyBody = srcs.stream().anyMatch(x -> x.part == PART_BODY);
	}

	/**
	 * 공격 부위 part 의 구들이 이번 프레임 이동 중 상대의 피격 구 (재질 TakeDamage — 몸뿐 아니라 키클롭스·켄타우로스·하피 다리, 머리,
	 * 고르곤·사티로스 무기 등) 와 닿았는지 (스윕 판정). 원작 0x203150 은 두 개체의 모든 구 쌍을 접촉시키고, 피격 쪽 구 재질 bitfield1 &amp; 1 이
	 * 없으면 OnContact 0x2040A8 이 기록 없이 끝낸다 [확실]. 예전 엔진은 PartType 1 (몸) 구만 봐 다리·머리에 닿은 베기를 놓쳤다.
	 */
	public boolean sweptHit(int part, HitVolumes victim) {
		for (int i = 0; i < srcs.size(); i++) {
			if (srcs.get(i).part != part) {
				continue;
			}
			for (int k = 0; k < victim.srcs.size(); k++) {
				if (!victim.victimSphere(k)) {
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

	/**
	 * 선분 a → b 가 이 볼륨의 피격 구 (재질 TakeDamage, {@link #sweptHit} 와 같은 거름) 를 지나는지 — 블레이드 사슬 적 스윕 (0x1FAFC8 → 0x11E190, 질의 0x100003).
	 * 맞으면 접촉점 (구 중심에서 선분까지 가장 가까운 점) · SheetFX 를 피격 기록에 남긴다. 콜백 0x1FAF40 도 접촉 레코드 +0x2C (맞은 구 재질) 로
	 * OnContact 를 불러 같은 0x2040A8 검사를 받는다 [확실].
	 */
	public boolean segmentHit(Vector3f a, Vector3f b) {
		float dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
		float dd = dx * dx + dy * dy + dz * dz;
		for (int k = 0; k < srcs.size(); k++) {
			if (!victimSphere(k)) {
				continue;
			}
			float[] c = cur[k];
			float s = dd > 1e-9f ? Math.max(0, Math.min(1, ((c[0] - a.x) * dx + (c[1] - a.y) * dy + (c[2] - a.z) * dz) / dd)) : 0;
			float px = a.x + dx * s, py = a.y + dy * s, pz = a.z + dz * s;
			float ex = px - c[0], ey = py - c[1], ez = pz - c[2];
			if (ex * ex + ey * ey + ez * ez <= c[3] * c[3]) {
				lastPoint.set(px, py, pz);
				lastFx = srcs.get(k).fx;
				lastValid = true;
				return true;
			}
		}
		return false;
	}

	/**
	 * 선분 a → b 가 처음 들어가는 피격 구 (몸 PartType 1, 없으면 전부 — {@link #segmentHit} 와 같은 거름) — 블레이드 벽 질의 (0x1FB2CC, 종류 0xFF9CE00F 에
	 * 생물 범주 1|2 가 든다). 시작점이 구 안이면 그 구는 건너뜀 [추정].
	 *
	 * @return {t, x, y, z, nx, ny, nz, sheetFx} (t = 선분 비율, 법선 = 구 중심 → 접촉점) 또는 null
	 */
	public float[] segmentCast(Vector3f a, Vector3f b) {
		boolean anyBody = hasPart(PART_BODY);
		float dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
		float dd = dx * dx + dy * dy + dz * dz;
		if (dd < 1e-12f) {
			return null;
		}
		float[] best = null;
		for (int k = 0; k < srcs.size(); k++) {
			if (anyBody && srcs.get(k).part != PART_BODY) {
				continue;
			}
			float[] c = cur[k];
			float ox = a.x - c[0], oy = a.y - c[1], oz = a.z - c[2];
			float bq = ox * dx + oy * dy + oz * dz, cq = ox * ox + oy * oy + oz * oz - c[3] * c[3];
			float disc = bq * bq - dd * cq;
			if (cq <= 0 || disc < 0) {
				continue;
			}
			float t = (-bq - (float) Math.sqrt(disc)) / dd;
			if (t < 0 || t > 1 || best != null && t >= best[0]) {
				continue;
			}
			float px = a.x + dx * t, py = a.y + dy * t, pz = a.z + dz * t;
			Vector3f n = new Vector3f(px - c[0], py - c[1], pz - c[2]);
			if (n.lengthSquared() > 1e-12f) {
				n.normalize();
			}
			best = new float[]{t, px, py, pz, n.x, n.y, n.z, srcs.get(k).fx};
		}
		return best;
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
		return forHero(heroWad, sk, wpn, null);
	}

	/**
	 * 크레토스 + 아르테미스의 검 (brsrk = R_BRSRK&lt;단계&gt;.WAD, null 이면 없음): CDV_berserkBlade 구 2 개 (PartType 10) 를 히어로 관절
	 * {@code rWeapIH} 에 — 원작 장착 0x19C240 이 검 GO 를 Hash127Upper("RWEAPIH") 관절에 붙인다 (0x19C388 → 0x138360 → 0x131460) [확실].
	 * 검 관절 0 의 idle 배율 0.25 가 구에도 걸려 월드 반지름 ≈ 8.9, 손잡이에서 ≈ 31 까지 (0x13AAA8: 중심 · 관절 행렬, 반지름 × 행 길이).
	 * 예전 엔진은 부위 10 구가 없어 BER* 공격을 정면 근사 구 (앞 55, 반지름 55 — 영웅 앞 110 까지) 로 판정했다 (43 §34).
	 * 검을 들지 않았을 때는 부위 10 공격 레코드가 없어 (BER* 만 10) 판정에 쓰이지 않는다.
	 */
	public static HitVolumes forHero(WadFile heroWad, Skeleton sk, WadFile wpn, WadFile brsrk) {
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
		int ih = sk.indexOf("rWeapIH");
		if (brsrk != null && ih >= 0) {
			v.addWeapon(WeaponAttach.berserkBlade(brsrk), ih, true); // GO +0xF4 |= 0x200 (0x19C3BC) — 부위 10 은 2 → 3 바꿈 대상 아님
		}
		return v;
	}
}
