package devple.gow.engine.level;

import devple.gow.tools.level.NcvCurve;
import org.joml.Vector3f;

/**
 * AresCamera1 전용 갱신 (실행 파일 0x1F67C0, 비용 0x1F5C58 / 0x1F6430, 30 문서 §6.3). 아레스 클래스 생성 0x1C2CF8 → 0x1F59A0 이
 * 카메라 대상 슬롯(0x304790, 0x50 바이트 × 10) 4 개를 등록하고 영웅 기본 슬롯(0x29DC24)을 끈다:
 * <pre>
 *   영웅 관절 "vertebrae4"     플래그 2 (화면 맞춤용)      영웅 몸 (−4 모드, 오프셋 (0, 14.92, 0))  플래그 1 (거리·가림용)
 *   아레스 관절 "BAR01JOCVEC1" 플래그 2                    아레스 몸 (−4, 같은 오프셋)              플래그 1
 *   오프셋 = ("bowl base" − "bay floor")·16, 반지름 +0x44 = "Camera Target Radius"·16 (0x1F5850)
 * </pre>
 * 프레임마다 (0x1F67C0):
 * <ol>
 *   <li>주시점 aim = 이전 눈 + 이전 정면 · (이전 대상 깊이). 대상 T(상태 +0x70) = 플래그 1 슬롯 가중 평균 (CAM 0x10000, 0x1F26E8) = 영웅·아레스 몸 중점.</li>
 *   <li>레일 t: 처음엔 매듭 수 ×4 표본 최소 (0x1F20F0), 이후 이전 t 에서 지역 탐색 (0x1F1AA0, 보폭 0.05·−½) 뒤
 *       t += 0.1 · softclamp(t* − t, ±"max rail speed"/60) (0x1F5398).</li>
 *   <li>레일 비용 (0x1F5C58, 후보 P = 레일 점): 이전 화면 회전으로 본 두 몸의 수평 화면각 a_i·폭 w_i = r·100/거리 —
 *       분리 (["seperating min","max"] 밖 × weight), 가림 (두 폭 구간 겹침 × "obscured weight"),
 *       최소 거리 (8·16 − 가까운 몸 거리) × 100, 바깥 테두리 (aim → P 수평 반직선이 그릇 원을 나가는 거리 &lt; 25 m) × 10,
 *       f line (P − aim 과 몸 연결선 각, 가중 0) / dolly rot (가중 0).</li>
 *   <li>붐 f (0x1F6CB4): 눈 E(f) = T − f·normalize(aim − P) (수평), 높이 = 그릇 면 (0x1F62E8: 바닥 "bowl base", 반지름 ± 4 m 사이
 *       Hermite, 밖은 기울기 "bowl slope"). f ∈ [0, |aim − P|수평] 를 이전 수평 거리에서 보폭 −1·−½ 로 탐색,
 *       비용 (0x1F6430) = 먼 몸 &gt; "maximum dist" ×10, 가까운 몸 &lt; "minimum dist" ×100, 두 관절 화면 수평 각차 &gt; 35° ×500,
 *       수직 &gt; 25° ×1000, f·10/16. 변화량은 softclamp("boom speed in"·16/60, "boom speed out"·16/60).</li>
 *   <li>방향: 요 = (aim − P) 의 요, 피치 0 에서 두 관절 화면각 평균만큼 돌린다 → 두 관절의 각 중점을 본다.</li>
 * </ol>
 * 수치는 R_PERMK TWK /GlobGame/BAR (실행 파일 0x1F5400 이 같은 이름으로 0x29F198.. 에 등록) [확실: 데이터]. bool 은 하위 바이트
 * ("f line symetrical" 0x3FB30000 → 거짓, "obscured symetrical" 0x41200001 → 참). "bowl h delta" 는 TWK 에 없어 실행 파일 기본 4.
 */
public final class AresCamera {

	// ---- /GlobGame/BAR (R_PERMK) ----
	static final float TARGET_RADIUS = 1.3999994f * 16;
	static final boolean LINE_SYMMETRIC = false;
	static final float LINE_ANGLE = 60f, LINE_WEIGHT = 0f;
	static final float SEP_MIN = 0f, SEP_MAX = 40f, SEP_WEIGHT = 10f;
	static final boolean OBSCURED_SYMMETRIC = true;
	static final float OBSCURED_WEIGHT = 80.60005f;
	static final float DIST_MIN = 8f * 16, DIST_MIN_WEIGHT = 100f;
	static final float RIM_MIN = 25f, RIM_WEIGHT = 10f;
	static final float HORIZ_MAX = 35.00026f, HORIZ_WEIGHT = 500f, VERT_MAX = 24.99997f, VERT_WEIGHT = 1000f;
	static final float MIN_DIST = 6.899997f * 16, MIN_DIST_WEIGHT = 100f, MAX_DIST = 7.9999995f * 16, MAX_DIST_WEIGHT = 10f;
	static final float SLOPE_WEIGHT = 10f;
	static final float BAY_FLOOR = -0.4125f, BOWL_BASE = 0.5199994f, BOWL_X = 0f, BOWL_Z = -3.1199434f, BOWL_RADIUS = 8.999869f,
			BOWL_SLOPE = 1.2499998f, BOWL_H_DELTA = 4f;
	static final float MAX_RAIL_SPEED = 0.3600006f, BOOM_IN = -3.9999037f, BOOM_OUT = 27.000465f;
	/** 실행 파일 고정값: soft_clamp_width 0x29F244, 레일 감쇠 0x29F240, 지역 탐색 보폭 0x29F188 / 종료 0x29F184, 붐 종료 0.001. */
	static final float SOFT_WIDTH = 0.1f, RAIL_DAMP = 0.9f, RAIL_STEP = 0.05f, RAIL_EPS = 0.0015f, BOOM_EPS = 0.001f;
	/** 몸 슬롯 높이 오프셋 (0x1F5850). */
	static final float BODY_OFFSET = (BOWL_BASE - BAY_FLOOR) * 16;

	private static final float FRAME = 1 / 60f;

	/** 이번 프레임 입력. */
	static final class Targets {
		final Vector3f heroBody = new Vector3f(), bossBody = new Vector3f(), heroJoint = new Vector3f(), bossJoint = new Vector3f();
	}

	/** 이전 화면 (원작 전역 카메라 0x29F16C 의 시야 행렬 + 이 카메라 상태 +0x20/+0x30). */
	static final class View {
		final Vector3f eye = new Vector3f(), fwd = new Vector3f(0, 0, -1);
		float depth;
	}

	/** 결과: 눈, 요/피치(도), 대상 T. */
	public final Vector3f eye = new Vector3f(), target = new Vector3f();
	public float yaw, pitch;
	/** 레일 매개 (필터 후, 상태 +0x48). 음수 = 처음. */
	public float t = -1;
	/** 디버그/검증: 마지막 레일 점과 붐. */
	public final Vector3f railPoint = new Vector3f();
	public float boom;

	private final NcvCurve rail;
	private final Targets in = new Targets();
	private final Vector3f t0 = new Vector3f(), t1 = new Vector3f(), aim = new Vector3f();
	/** 레일 비용용 이전 화면 축 (오른쪽 / 정면). */
	private final Vector3f viewRight = new Vector3f(1, 0, 0), viewFwd = new Vector3f(0, 0, -1), viewUp = new Vector3f(0, 1, 0);

	AresCamera(NcvCurve rail) {
		this.rail = rail;
	}

	/**
	 * 한 틱. prev = 이 카메라의 이전 결과 (처음이면 직전 카메라), screen = 직전 프레임 화면 (전역 카메라).
	 */
	void update(Targets tg, View prev, View screen) {
		in.heroBody.set(tg.heroBody);
		in.bossBody.set(tg.bossBody);
		in.heroJoint.set(tg.heroJoint);
		in.bossJoint.set(tg.bossJoint);
		t0.set(tg.heroBody).add(0, BODY_OFFSET, 0);
		t1.set(tg.bossBody).add(0, BODY_OFFSET, 0);
		target.set(t0).add(t1).mul(0.5f);
		aim.set(prev.fwd).mul(prev.depth).add(prev.eye);
		viewAxes(screen.fwd);
		// 레일 t (0x1F20F0 / 0x1F1AA0 + 0x1F5398)
		if (t < 0) {
			t = globalSearch();
		} else {
			float best = localSearch(t);
			float v = MAX_RAIL_SPEED * FRAME;
			t += (1 - RAIL_DAMP) * softClamp(-v, v, best - t);
			t = rail.wrap(t);
		}
		float[] r = new float[3];
		rail.eval(t, r);
		railPoint.set(r[0], r[1], r[2]);
		Vector3f toAim = new Vector3f(aim).sub(railPoint);
		float baseYaw = CameraRig.yawOf(toAim);
		Vector3f dir = new Vector3f(toAim).normalize();
		// 붐 (0x1F6CB4)
		float fMax = horiz(toAim);
		float f0 = Math.min(Math.max(horiz(new Vector3f(prev.eye).sub(target)), 0), fMax);
		float cur = f0, step = -1;
		float cCur = boomCost(cur, dir, baseYaw, null);
		Vector3f eCur = boomEye(cur, dir), eNew;
		for (int guard = 0; guard < 2000; guard++) {
			float nf = Math.max(0, Math.min(fMax, cur + step));
			eNew = boomEye(nf, dir);
			float cNew = boomCost(nf, dir, baseYaw, null);
			float d2 = eCur.distanceSquared(eNew);
			if (accept(cCur, cNew, cur - f0, step)) {
				cur = nf;
				cCur = cNew;
				eCur = eNew;
			} else {
				step *= -0.5f;
			}
			if (!(d2 > BOOM_EPS)) {
				break;
			}
		}
		boom = f0 + softClamp(BOOM_IN * 16 * FRAME, BOOM_OUT * 16 * FRAME, cur - f0);
		eye.set(boomEye(boom, dir));
		float[] ang = new float[2];
		boomCost(boom, dir, baseYaw, ang);
		yaw = baseYaw + ang[0];
		pitch = ang[1];
		// 다음 프레임 aim 깊이 (0x1F11A0: 대상 T 를 새 시야로 본 깊이)
		Vector3f f = CameraRig.dir(yaw, pitch, new Vector3f());
		prev.eye.set(eye);
		prev.fwd.set(f);
		prev.depth = new Vector3f(target).sub(eye).dot(f);
	}

	private void viewAxes(Vector3f fwd) {
		viewFwd.set(fwd).normalize();
		viewRight.set(viewFwd).cross(0, 1, 0);
		if (viewRight.lengthSquared() < 1e-8f) {
			viewRight.set(1, 0, 0);
		}
		viewRight.normalize();
		viewUp.set(viewRight).cross(viewFwd).normalize();
	}

	/** 0x1F1AA0 의 수락 규칙: 더 작으면 수락, 같으면 (진행 부호 ≠ 보폭 부호 이고 진행 ≠ 0) 일 때만. */
	private static boolean accept(float cCur, float cNew, float moved, float step) {
		if (cCur < cNew) {
			return false;
		}
		if (cNew != cCur) {
			return true;
		}
		int a = (int) Math.signum(moved), b = (int) Math.signum(step);
		return a != b && a != 0;
	}

	private float globalSearch() {
		int n = rail.knots.length * 4;
		float end = rail.tMax(), best = 0, bc = Float.MAX_VALUE;
		for (int i = 0; i <= n; i++) {
			float u = end * i / n;
			float c = railCost(u);
			if (c < bc) {
				bc = c;
				best = u;
			}
		}
		return best;
	}

	private float localSearch(float start) {
		float end = rail.tMax();
		float step = RAIL_STEP;
		if (end < start + step && !rail.closed) {
			step = -step;
		}
		float u = start, c = railCost(u);
		float[] p = new float[3], q = new float[3];
		rail.eval(u, p);
		for (int guard = 0; guard < 1000; guard++) {
			float n = rail.closed ? rail.wrap(u + step) : Math.max(0, Math.min(end, u + step));
			rail.eval(n, q);
			float nc = railCost(n);
			float dx = p[0] - q[0], dy = p[1] - q[1], dz = p[2] - q[2];
			float d2 = dx * dx + dy * dy + dz * dz;
			if (accept(c, nc, u - start, step)) {
				u = n;
				c = nc;
				p[0] = q[0];
				p[1] = q[1];
				p[2] = q[2];
			} else {
				step *= -0.5f;
			}
			if (!(d2 > RAIL_EPS && step != 0)) {
				break;
			}
		}
		return u;
	}

	/** 0x1F5C58: 레일 점 P(u) 의 비용. */
	float railCost(float u) {
		float[] r = new float[3];
		rail.eval(u, r);
		Vector3f p = new Vector3f(r[0], r[1], r[2]);
		float cost = 0;
		// f line: (P − aim) 과 (T1 − T0) 사이 각
		if (LINE_WEIGHT != 0) {
			Vector3f a = new Vector3f(p).sub(aim).normalize(), b = new Vector3f(t1).sub(t0).normalize();
			float ang = (float) Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a.dot(b)))));
			if (LINE_SYMMETRIC) {
				ang = Math.abs(ang > 90 ? 180 - ang : ang);
			}
			cost += Math.max(LINE_ANGLE - ang, 0) * LINE_WEIGHT;
		}
		// 두 몸의 화면 수평 각 (이전 화면 회전, 후보 P 기준)
		float[] s0 = screen(new Vector3f(t0).sub(p)), s1 = screen(new Vector3f(t1).sub(p));
		float a0 = s0[0], a1 = s1[0], d0 = s0[1], d1 = s1[1];
		float w0 = TARGET_RADIUS * 100 / d0, w1 = TARGET_RADIUS * 100 / d1;
		float lo, hiA, hiB, loB;
		if (a1 < a0) {
			lo = a1 - w1;
			hiB = a1 + w1;
			hiA = a0 + w0;
			loB = a0 - w0;
		} else {
			lo = a0 - w0;
			hiB = a0 + w0;
			hiA = a1 + w1;
			loB = a1 - w1;
		}
		float extent = hiA - lo;
		float overlap = Math.max(hiB - loB, 0);
		if (!OBSCURED_SYMMETRIC && d0 < d1) {
			overlap = 0;
		}
		cost += OBSCURED_WEIGHT * overlap;
		cost += SEP_WEIGHT * Math.max(Math.max(SEP_MIN - extent, extent - SEP_MAX), 0);
		cost += Math.max(0, DIST_MIN - Math.min(d0, d1)) * DIST_MIN_WEIGHT;
		// 바깥 테두리: aim 에서 P 쪽 수평 반직선이 그릇 원(반지름 bowl radius)을 나가는 거리
		Vector3f dir = new Vector3f(aim).sub(p).normalize().negate();
		float ox = aim.x, oz = aim.z, dx = dir.x, dz = dir.z;
		float cx = BOWL_X * 16, cz = BOWL_Z * 16, rr = BOWL_RADIUS * 16;
		float qa = dx * dx + dz * dz;
		float qb = 2 * (dx * (ox - cx) + dz * (oz - cz));
		float qc = ox * ox + oz * oz + cx * cx + cz * cz - 2 * (ox * cx + oz * cz) - rr * rr;
		float disc = qb * qb - 4 * qa * qc;
		if (disc >= 0 && qa > 0) {
			float sq = (float) Math.sqrt(disc);
			float exit = Math.max((-qb + sq) / (2 * qa), (-qb - sq) / (2 * qa)) / 16f;
			if (exit >= 0) {
				cost += Math.max(0, RIM_MIN - exit) * RIM_WEIGHT;
			}
		}
		return cost;
	}

	/** 이전 화면 회전으로 본 벡터: [수평 화면각(도, 오른쪽 +), 수평 거리]. */
	private float[] screen(Vector3f v) {
		float x = v.dot(viewRight), z = -v.dot(viewFwd);
		return new float[]{(float) -Math.toDegrees(Math.atan2(-x, -z)), (float) Math.sqrt(x * x + z * z)};
	}

	/** 0x1F62E8: 붐 f 의 눈 — 수평은 T − f·dir, 높이는 그릇 면. */
	Vector3f boomEye(float f, Vector3f dir) {
		Vector3f e = new Vector3f(dir).mul(-f).add(target);
		e.y = bowlHeight(e.x, e.z);
		return e;
	}

	static float bowlHeight(float x, float z) {
		float dx = x - BOWL_X * 16, dz = z - BOWL_Z * 16;
		float d = (float) Math.sqrt(dx * dx + dz * dz);
		float in = (BOWL_RADIUS - BOWL_H_DELTA) * 16, out = (BOWL_RADIUS + BOWL_H_DELTA) * 16;
		float h;
		if (d < in) {
			h = 0;
		} else if (d < out) {
			h = hermite(0, 0, BOWL_H_DELTA * BOWL_SLOPE, BOWL_SLOPE, (d - in) / (out - in)) * 16;
		} else {
			h = BOWL_SLOPE * (d - BOWL_RADIUS * 16);
		}
		return BOWL_BASE * 16 + h;
	}

	/**
	 * 0x1F6430: 붐 비용. ang != null 이면 두 관절의 평균 화면각 (상대 요, 피치) 을 돌려준다 (마지막 호출의 방향 맞춤).
	 */
	private float boomCost(float f, Vector3f dir, float baseYaw, float[] ang) {
		Vector3f e = boomEye(f, dir);
		float d0 = horiz(new Vector3f(t0).sub(e)), d1 = horiz(new Vector3f(t1).sub(e));
		float cost = Math.max(0, Math.max(d0, d1) - MAX_DIST) * MAX_DIST_WEIGHT;
		cost += Math.max(0, MIN_DIST - Math.min(d0, d1)) * MIN_DIST_WEIGHT;
		Vector3f j0 = new Vector3f(in.heroJoint).sub(e), j1 = new Vector3f(in.bossJoint).sub(e);
		float h0 = CameraRig.wrap180(CameraRig.yawOf(j0) - baseYaw), h1 = CameraRig.wrap180(CameraRig.yawOf(j1) - baseYaw);
		float v0 = CameraRig.pitchOf(j0), v1 = CameraRig.pitchOf(j1);
		cost += outside(Math.abs(h0 - h1), 0, HORIZ_MAX) * HORIZ_WEIGHT;
		cost += outside(Math.abs(v0 - v1), 0, VERT_MAX) * VERT_WEIGHT;
		cost += f * SLOPE_WEIGHT / 16f;
		if (ang != null) {
			ang[0] = (h0 + h1) / 2;
			ang[1] = (v0 + v1) / 2;
		}
		return cost;
	}

	/** 0x1F0D00: 각 x 가 [lo, hi] 밖으로 벗어난 정도 (도, 360 감김). */
	static float outside(float x, float lo, float hi) {
		if (hi - lo >= 360) {
			return 0;
		}
		float mid = (lo + hi) / 2;
		float d = CameraRig.wrap180(x - mid);
		return d < 0 ? Math.max(0, lo - mid - d) : Math.max(0, d - (hi - mid));
	}

	/** 0x1F5258: 부드러운 자르기 (폭 = 반범위 × soft_clamp_width, 경계는 Hermite — 원작 그대로 경계 밖 값과 이어지지 않는다). */
	static float softClamp(float lo, float hi, float x) {
		float w = (hi - lo) * 0.5f * SOFT_WIDTH;
		if (x < lo - w) {
			return lo;
		}
		if (x < lo + w) {
			return hermite(lo - w, 0, lo + w, 1, (x - (lo - w)) / (2 * w));
		}
		if (x < hi - w) {
			return x;
		}
		if (x < hi + w) {
			return hermite(hi - w, 1, hi + w, 0, (x - (hi - w)) / (2 * w));
		}
		return hi;
	}

	/** 0x1F51F8: 3차 Hermite (p0, m0, p1, m1, s). */
	static float hermite(float p0, float m0, float p1, float m1, float s) {
		float s2 = s * s, s3 = s2 * s;
		return (2 * s3 - 3 * s2 + 1) * p0 + (s3 - 2 * s2 + s) * m0 + (-2 * s3 + 3 * s2) * p1 + (s3 - s2) * m1;
	}

	private static float horiz(Vector3f v) {
		return (float) Math.sqrt(v.x * v.x + v.z * v.z);
	}
}
