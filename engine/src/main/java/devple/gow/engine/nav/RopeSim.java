package devple.gow.engine.nav;

import org.joml.Vector3f;

/**
 * 매단 줄 질점-스프링 시뮬레이션 (원작 SCR_RopeSkinned 갱신 0x217A40 + 한 단계 0x217478) [확실: 디스어셈블].
 * 단위는 미터 (원작도 관절 위치 × 1/16 로 시뮬레이션하고 ×16 으로 되돌린다, 0x217AC8 / 0x217EF0).
 * <pre>
 * 템플릿 /TweakTemplates/Rope/n/ (0x2168C0, 전 레벨 Rope/000·ZipLine/003 같은 값):
 *   +0x18 Segment Mass 0.2, +0x1C #51fee0b7 10000 (이웃 스프링), +0x20 Spring Damping 10,
 *   +0x24 #ad717afb 500 / +0x28 #480eb083 5 (한 칸 건너 굽힘 스프링), +0x2C #1f59f04a 5000 / +0x30 #893d4e1c 5 (매단 점 – 매달린 영웅 스프링),
 *   +0x34 Global Damping 0.003, +0x38 Gravity 9.82, +0x3C #9a6419f6 8 (60 Hz 한 프레임 하위 단계 수).
 * 한 단계 (0x217478, h = dt / 단계 수):
 *   i 와 i+1: F = (k (|d| − L) + c (d̂ · Δv)) d̂ 를 i 에 +, i+1 에 −. i 와 i+2: 같은 식 (500, 5, 쉼 길이 2L).
 *   노드 0 = 매단 점. 나머지: v += F · h / m, 힘 = (0, −g m, 0) 으로 초기화, p += v h, v ×= (1 − 감쇠).
 *   매달림 (슬롯 16, +0x70 + 0x30 i): 힘을 거리 비율로 j, j+1 에 나눔. 양끝 고정이 아니면 무게 비율 w/최대 × (1 − frac) 로
 *   매단 점 – j (쉼 길이 1.1 j L) 스프링 5000 / 5 (j+1 도 frac 로).
 * 프레임 (0x217A40): 0.2 초 넘게 움직임 없으면 (모든 |v|² ≤ 0.0004) 멈춤 (감쇠 = Global Damping). 노드 질량 = Segment Mass +
 *   매달린 무게 (j, j+1 에 거리 비율로). 무게는 붙으면 최대 × 0.5 /초로 늘고 떨어지면 줄어듦 (최대 = 매단 줄 100, 양끝 고정 10, 0x216D84).
 *   하위 단계 수 = int(8 · dt · 60 + 0.99). 감쇠 = clamp(감쇠 − 0.05 · dt, Global Damping, 1), 처음 0.2 (0x216D54).
 * </pre>
 * 노드 수는 원작에선 줄 모델의 사슬 관절 수 (0x216FC8), 엔진은 줄 오브젝트 관절 수 [추정].
 */
public final class RopeSim {

	public static final float SEG_MASS = 0.2f, K = 10000f, C = 10f, K_BEND = 500f, C_BEND = 5f, K_ANCHOR = 5000f, C_ANCHOR = 5f,
			GLOBAL_DAMPING = 0.003f, GRAVITY = 9.82f, START_DAMPING = 0.2f, DAMPING_DECAY = 0.05f, SLEEP_TIME = 0.2f, WAKE_V2 = 0.0004f;
	public static final int SUBSTEPS = 8;
	/** 매달림 최대 무게 (+0x14): 매단 줄 100, 양끝 고정 10 (0x216D84) [확실]. */
	public static final float WEIGHT_HANG = 100f;

	private final int n;
	private final Vector3f anchor = new Vector3f();
	private final Vector3f[] p, v, f;
	private final float[] m;
	private final float seg, length;
	/** 감쇠 (+0x37C, 만들 때 0.2) 와 정지 시간 (+0x378). 엔진은 레벨 로드 뒤 0.2 초 만에 잠든 상태 (감쇠 = Global Damping) 로 만든다 [추정]. */
	private float damping = GLOBAL_DAMPING, idle = SLEEP_TIME;
	/** 영웅 매달림 하나 (원작 슬롯 16 개 중 영웅 몫). */
	private boolean attached;
	private float dist, weight;
	private final float maxWeight = WEIGHT_HANG;
	private final Vector3f force = new Vector3f();

	/**
	 * @param top    매단 점 (월드 유닛)
	 * @param length 줄 길이 (월드 유닛)
	 * @param nodes  노드 수 (≥ 2)
	 */
	public RopeSim(Vector3f top, float length, int nodes) {
		n = Math.max(3, nodes);
		this.length = length / NavTuning.UNITS_PER_METER;
		seg = this.length / (n - 1);
		anchor.set(top).div(NavTuning.UNITS_PER_METER);
		p = new Vector3f[n];
		v = new Vector3f[n];
		f = new Vector3f[n];
		m = new float[n];
		for (int i = 0; i < n; i++) {
			// 매단 줄 초기 자세 = 매단 점에서 곧게 아래로 (0x217200)
			p[i] = new Vector3f(anchor).sub(0, seg * i, 0);
			v[i] = new Vector3f();
			f[i] = new Vector3f();
			m[i] = SEG_MASS;
		}
	}

	/** 줄 길이 (m). */
	public float length() {
		return length;
	}

	public float damping() {
		return damping;
	}

	public float weight() {
		return weight;
	}

	/** 영웅이 dist (m) 에 붙음 (0x218200 + 0x2183B0: [0.25, 길이 − 0.25] 로 자름). */
	public void attach(float dist) {
		attached = true;
		idle = 0;
		moveAttach(dist);
	}

	public void detach() {
		attached = false;
		idle = 0;
	}

	public boolean attached() {
		return attached;
	}

	/** 붙은 위치 옮김 (0x2183B0) — 돌려준 값이 실제 위치. */
	public float moveAttach(float d) {
		dist = Math.max(0.25f, Math.min(length - 0.25f, d));
		idle = 0;
		return dist;
	}

	public float dist() {
		return dist;
	}

	/** 이번 프레임 매달림 힘 (N, 0x218470 — 영웅이 매달린 동안 매 프레임 부름, 0x1C71A8) — 프레임 끝에 0 으로 (0x217DA4). */
	public void setForce(Vector3f fw) {
		force.set(fw);
		idle = 0;
	}

	/** 매단 점을 옮김 (월드 유닛; 움직이면 깨어남). */
	public void setAnchor(Vector3f top) {
		Vector3f a = new Vector3f(top).div(NavTuning.UNITS_PER_METER);
		if (a.distanceSquared(anchor) > 0) {
			anchor.set(a);
			idle = 0;
		}
	}

	/** 줄 위 거리 d (m) 의 점 (월드 유닛, 0x2184E8). */
	public Vector3f pointAt(float d, Vector3f out) {
		int j = (int) (d / seg);
		if (j >= n - 1) {
			return out.set(p[n - 1]).mul(NavTuning.UNITS_PER_METER);
		}
		j = Math.max(0, j);
		float t = (d - j * seg) / seg;
		return out.set(p[j]).lerp(p[j + 1], t).mul(NavTuning.UNITS_PER_METER);
	}

	/** 거리 d 의 속도 (월드 유닛/초). */
	public Vector3f velocityAt(float d, Vector3f out) {
		int j = Math.max(0, Math.min(n - 2, (int) (d / seg)));
		float t = Math.max(0, Math.min(1, (d - j * seg) / seg));
		return out.set(v[j]).lerp(v[j + 1], t).mul(NavTuning.UNITS_PER_METER);
	}

	/** 한 프레임 (0x217A40). */
	public void update(float dt) {
		idle += dt;
		if (idle > SLEEP_TIME) {
			damping = GLOBAL_DAMPING;
			force.zero();
			return;
		}
		for (int i = 0; i < n; i++) {
			if (v[i].lengthSquared() > WAKE_V2) {
				idle = 0;
				break;
			}
		}
		// 무게: 붙어 있으면 늘고 떨어지면 줄어든다 (0x217C48)
		float dw = maxWeight * 0.5f * dt;
		weight = Math.max(0, Math.min(maxWeight, weight + (attached ? dw : -dw)));
		for (int i = 0; i < n; i++) {
			m[i] = SEG_MASS;
		}
		if (weight > 0) {
			int j = Math.min(n - 1, (int) (dist / seg));
			float frac = (dist - j * seg) / seg;
			m[j] += (1 - frac) * weight;
			if (j + 1 < n) {
				m[j + 1] += frac * weight;
			}
		}
		int steps = Math.max(1, (int) (SUBSTEPS * dt * 60 + 0.99f));
		float h = dt / steps;
		for (int s = 0; s < steps; s++) {
			step(h);
		}
		force.zero();
		damping = Math.max(GLOBAL_DAMPING, Math.min(1, damping - DAMPING_DECAY * dt));
	}

	/** 한 단계 (0x217478). */
	private void step(float h) {
		for (int i = 0; i + 1 < n; i++) {
			spring(i, i + 1, seg, K, C);
			if (i + 2 < n) {
				spring(i, i + 2, 2 * seg, K_BEND, C_BEND);
			}
		}
		p[0].set(anchor);
		v[0].zero();
		for (int i = 1; i < n; i++) {
			v[i].fma(h / m[i], f[i]);
			f[i].set(0, -GRAVITY * m[i], 0);
			p[i].fma(h, v[i]);
			v[i].mul(1 - damping);
		}
		// 매달림 (0x217790): 힘 분배 + 매단 점 스프링 (무게 비율)
		if (weight > 0 || force.lengthSquared() > 0) {
			int j = Math.min(n - 1, (int) (dist / seg));
			float frac = (dist - j * seg) / seg;
			f[j].fma(1 - frac, force);
			float wf = weight / maxWeight;
			if (j > 0) {
				spring(0, j, 1.1f * j * seg, wf * (1 - frac) * K_ANCHOR, wf * (1 - frac) * C_ANCHOR);
			}
			if (j + 1 < n) {
				f[j + 1].fma(frac, force);
				spring(0, j + 1, 1.1f * (j + 1) * seg, wf * frac * K_ANCHOR, wf * frac * C_ANCHOR);
			}
		}
	}

	private void spring(int a, int b, float rest, float k, float c) {
		Vector3f d = new Vector3f(p[b]).sub(p[a]);
		float len = d.length();
		if (len < 1e-6f) {
			return;
		}
		d.div(len);
		float rel = d.dot(v[b].x - v[a].x, v[b].y - v[a].y, v[b].z - v[a].z);
		d.mul(k * (len - rest) + c * rel);
		f[a].add(d);
		f[b].sub(d);
	}
}
