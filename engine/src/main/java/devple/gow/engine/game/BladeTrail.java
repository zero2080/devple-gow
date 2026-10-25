package devple.gow.engine.game;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 혼돈의 블레이드 휘두르기 궤적 — 손 쪽 사슬에서 칼끝까지 넓은 불꽃 띠 (45 문서 §3.4, 43 §35). 원작 = Blades_Init (0x1F9C38) 이 블레이드마다
 * 만드는 EffectsServer (0x1E) 서브타입 0xB "칼 궤적" (16 문서 §2 의 FXC 0xB001E 와 같은 형):
 * <ul>
 *   <li>리소스 +0xB0 = 0x12DD60(0x70 바이트, 스택 템플릿 0x000B001E · 단위 행렬 · 이름 "" , 점 3) → 0x12DAC8: 수명 +0x14 = [0x29CA70] = <b>0.32 초</b>
 *       (템플릿 +0x6C 를 덮어씀), 점 수 +0x18 = 3, 점 배열 +0x20. 블레이드 갱신 0x1FB460 이 매 프레임 리소스 행렬 (+0x30..+0x60) = 블레이드 행렬
 *       (0x1FAFC8 뒤, 배율 없음, 0x1FBE04) 로, 점 = 블레이드 행렬⁻¹ (0x185490) × {사슬 베지어 t 0.2 (0x1FBDF8 번스타인 .512/.384/.096/.008),
 *       t 0.7 (0x1FBEB4 .027/.189/.441/.343), 칼끝 P3 − 블레이드 Z × Weapon Length × 16 (0x1FBF4C)} × 16 으로 쓴다.</li>
 *   <li>인스턴스 +0xB4 = 0x12DE70 (0x190 바이트, 서버 +0x2C): 머티리얼 MAT_swordtrail (없으면 MAT_trail1), 정점 = 위치 + ST (+0x154 bit 1, 색 없음),
 *       띠 = [옛0, 새0, 옛1, 새1, 옛2, 새2] (GS 삼각형 띠, 점 0 만 ADC) — 한 띠 = 시간 한 칸, 링 {@link #CAPACITY} 칸.
 *       켜짐 +0x158 = 부착물 종류 0 (Blades_Lights 0x1FAE74 가 0x201F28(슬롯, 0, +0xB4) 로 넣음) → 기술 이벤트 17/18 (0x204B38 → 0x201C78 / 0x201DD8 → 0x12E4C8).</li>
 *   <li>매 프레임 0x240A88 (← EffectsServer 갱신 0x25D2C0): 시계 +0x150 += 1/60, 행렬 이력 (0x12EEB0 / 0x12EDA8) · 점 이력 (0x12F5A8) 은 켜짐과 관계없이,
 *       켜져 있으면 하위 표본 n = min(10, int(4·|ΔR|_F + 4·|Δt|)) (0x12F288, 가중 0x2C9080/84 = 4, 4 — 0x235744) 개를 이차 보간 (0x12EEC0 / 0x12F080,
 *       점은 0x12F658 / 0x12F718) 으로 끼우고 (0x12E8F0) 지금 표본을 넣는다. 지난 프레임에 꺼져 있었으면 (+0x15C) 이번 첫 띠의 옛 정점 = 새 정점 (면적 0).
 *       수명이 지난 띠는 꼬리부터 지운다 (0x12E518). 끈 뒤에도 남은 띠는 수명까지 그려진다.</li>
 *   <li>텍스처 좌표: S = 점 번호 / 2 (0, 0.5, 1 — 0x12E8F0 의 4096·i/(n−1)), T = 1 − 나이 / 0.32 (0x240FB0 – 0x2410AC, 꼬리 띠의 옛 정점은 0).
 *       텍스처 TXR_swordtrail 64×32 (무기 단계마다 다름) 는 아래 줄 (T = 1, 새 표본) 이 밝고 위로 어두워지는 불꽃이라 오래된 쪽이 사라진다.</li>
 *   <li>색 = Trail Tint (god 이면 God Mode Trail Tint) — 0x1FB5D0 → 0x12E4D0 이 띠 인스턴스 색 +0xB0 에 쓴다 (부채꼴 +0xB8 과 같은 값).</li>
 * </ul>
 * 0x1FC070 의 부채꼴 ({@link BladeChain#trail}) 은 이 띠의 맨 앞 (T = 1) 을 지금 사슬의 굽은 모양까지 메우는 덮개다 (같은 S 배치 · V = 1, 이 띠가 켜졌을 때만 그림).
 * 좌표는 엔진 월드 (원작은 ×16 고정소수, vftoi0). GL 없음 — 그리기는 {@link devple.gow.engine.render.BladeChainRenderer}.
 */
public final class BladeTrail {

	/** 수명 (초): [0x29CA70] = 0.32 — 0x12DAC8 이 템플릿 +0x6C (블레이드 2.0, 데이터 궤적 1.0) 를 덮어쓴다, 다른 쓰기 없음 [확실]. */
	public static final float LIFETIME = 0.32f;
	/** 점 수 (Blades_Init 0x1F9DF4: 0x12DD60(…, 3)). */
	public static final int POINTS = 3;
	/** 점 0·1 의 사슬 베지어 t (0x1FBDF8 / 0x1FBEB4 번스타인 계수), 점 2 = 칼끝. */
	public static final float T_NEAR = 0.2f, T_MID = 0.7f;
	/** 프레임당 하위 표본 상한 (0x240CB0 `li 0xa` · `slt` · `movz`). */
	public static final int MAX_STEPS = 10;
	/** 하위 표본 수 가중 (0x2C9080 회전 / 0x2C9084 이동 = 4.0, 4.0 — 초기화 0x235744, 쓰는 곳은 거기뿐) [확실]. */
	public static final float ROT_WEIGHT = 4f, MOVE_WEIGHT = 4f;
	/** 위치 + ST 두 qword (+0x154 = 2: MAT_swordtrail 레이어에 텍스처가 있어 0x12DF74 가 bit 1 을 켬). */
	public static final int QWORDS_PER_VERTEX = 2;
	/** 띠 링 칸 수 +0x144 (0x12DFC4 – 0x12E040): 0.32·60 → 19 프레임 × 10, 패킷당 (0xF5 − 2) / (6·2) = 20 띠 → 10 패킷 → 200. */
	public static final int CAPACITY = capacity(LIFETIME, POINTS, QWORDS_PER_VERTEX);
	/** 점별 S (0x12E8F0: a1·4096/(n−1) → itof12). */
	public static final float[] S = {0f, 0.5f, 1f};

	/** 0x12DE70 의 링 크기 계산 그대로. */
	public static int capacity(float lifetime, int points, int qwordsPerVertex) {
		int verts = points * 2; // +0x13C
		int perPacket = (0xF5 - qwordsPerVertex) / (verts * qwordsPerVertex); // +0x148
		int frames = (int) (lifetime * 60f); // cvt.w.s (버림)
		int packets = (frames * 10 + perPacket - 1) / perPacket; // +0x140
		return packets * perPacket; // +0x144
	}

	/** 띠 한 칸 (원작: 정점 [옛0, 새0, 옛1, 새1, 옛2, 새2] + +0x180 시각). */
	public static final class Strip {
		/** 표본 시각 (+0x180, 트레일 시계 기준). */
		public float time;
		/** 이전 띠의 새 정점 복사 (0x12E8F0) / 이 표본. */
		public final Vector3f[] prev = new Vector3f[POINTS], next = new Vector3f[POINTS];
		/** 옛 / 새 정점 T (0x240FB0: 매 프레임 다시 씀). */
		public float tPrev, tNext;

		Strip() {
			for (int i = 0; i < POINTS; i++) {
				prev[i] = new Vector3f();
				next[i] = new Vector3f();
			}
		}
	}

	/** 행렬 이력 (인스턴스 +0x80, 0x94 바이트: 0x12ED00 / 0x12EDA8 / 0x12EEB0 / 0x12EEC0 / 0x12F080 / 0x12F288). 원소별 이차 곡선. */
	static final class MatrixHistory {
		/** C = 전역 스크래치 [0x29CA68] (+0x00) — 두 프레임 전, A = +0x00 지난 프레임, B = +0x40 이번. */
		final float[] c = new float[16], a = new float[16], b = new float[16];
		/** 곡선 계수: 전역 +0x40 (1 차), +0x80 (2 차). P(s) = C + lin·s + quad·s². */
		final float[] lin = new float[16], quad = new float[16];
		/** +0x84 (C→A), +0x88 (A→B), +0x8C (누적). */
		float t1, t2, acc;
		/** +0x80 = 1: 다음 push 가 셋 다 채움. */
		boolean first = true;

		void reset() {
			first = true;
			t1 = t2 = acc = 0;
		}

		/** 0x12EEB0: +0x8C += 1/60. */
		void tick(float dt) {
			acc += dt;
		}

		/** 0x12EDA8. 첫 번째: C = A = B = M, +0x84 = +0x88 = +0x8C (+0x8C 는 0 으로 돌리지 않음). 그 뒤: C ← A, A ← B, B ← M, +0x84 ← +0x88 ← +0x8C ← 0. */
		void push(float[] m) {
			if (first) {
				System.arraycopy(m, 0, c, 0, 16);
				System.arraycopy(m, 0, a, 0, 16);
				System.arraycopy(m, 0, b, 0, 16);
				t1 = acc;
				t2 = acc;
				first = false;
				return;
			}
			System.arraycopy(a, 0, c, 0, 16);
			System.arraycopy(b, 0, a, 0, 16);
			System.arraycopy(m, 0, b, 0, 16);
			t1 = t2;
			t2 = acc;
			acc = 0;
		}

		/**
		 * 0x12F288: int(√(Σ 3×3 (B − A)²) · 회전 가중 + |B 이동 − A 이동| · 이동 가중) (cvt.w.s 버림). 원작 행 0–2 의 xyz 와 행 3 의 xyz —
		 * JOML 열 우선 배열에서도 같은 원소 (0,1,2,4,5,6,8,9,10 / 12,13,14).
		 */
		int steps(float wr, float wt) {
			float r = 0;
			for (int col = 0; col < 3; col++) {
				for (int row = 0; row < 3; row++) {
					float d = b[col * 4 + row] - a[col * 4 + row];
					r += d * d;
				}
			}
			float tx = b[12] - a[12], ty = b[13] - a[13], tz = b[14] - a[14];
			float f = (float) Math.sqrt(r) * wr + (float) Math.sqrt(tx * tx + ty * ty + tz * tz) * wt;
			return (int) f;
		}

		/**
		 * 0x12EEC0: 세 행렬 (C 시각 0, A 시각 t1, B 시각 t1 + t2) 을 지나는 원소별 이차식.
		 * quad = ((C − B)·t1 − (C − A)·T) / (t1·T·(t1 − T)), lin = −((C − A) + quad·t1²) / t1, T = t1 + t2.
		 */
		void fit() {
			float bigT = t1 + t2;
			if (t1 <= 0f || t2 <= 0f) { // 엔진 보호: dt 0 틱 (원작은 늘 1/60)
				for (int i = 0; i < 16; i++) {
					quad[i] = 0;
					lin[i] = bigT > 0 ? (b[i] - c[i]) / bigT : 0;
				}
				return;
			}
			float inv = 1f / (t1 * bigT * (t1 - bigT));
			for (int i = 0; i < 16; i++) {
				float d1 = c[i] - a[i], d2 = c[i] - b[i];
				quad[i] = (d2 * t1 - d1 * bigT) * inv;
				lin[i] = (d1 + quad[i] * t1 * t1) * (-1f / t1);
			}
		}

		/** 0x12F080: s = u·t2 + t1, out = C + lin·s + quad·s² (u = 0 → A, 1 → B). */
		void eval(float u, float[] out) {
			float s = u * t2 + t1;
			float s2 = s * s;
			for (int i = 0; i < 16; i++) {
				out[i] = c[i] + lin[i] * s + quad[i] * s2;
			}
		}
	}

	/** 점 이력 (인스턴스 +0x120, 0x1C 바이트: 0x12F398 / 0x12F348 / 0x12F5A8 / 0x12F658 / 0x12F718). 블레이드 로컬 점, 간격 1 (프레임) 의 이차 곡선. */
	static final class PointHistory {
		/** +0x0 / +0x4 / +0x8 = 두 프레임 전 · 지난 · 이번. */
		Vector3f[] p0 = vecs(), p1 = vecs(), p2 = vecs();
		/** +0xC 2 차 · +0x10 1 차 계수. */
		final Vector3f[] quad = vecs(), lin = vecs();
		/** +0x18 = 1 (0x12F348). */
		boolean first = true;

		private static Vector3f[] vecs() {
			Vector3f[] v = new Vector3f[POINTS];
			for (int i = 0; i < POINTS; i++) {
				v[i] = new Vector3f();
			}
			return v;
		}

		void reset() {
			first = true;
		}

		/** 0x12F5A8: 첫 번째면 셋 다, 아니면 배열을 돌리고 (p0 ← p1 ← p2 ← 옛 p0) 새 p2 에 복사. */
		void push(Vector3f[] pts) {
			if (first) {
				for (int i = 0; i < POINTS; i++) {
					p0[i].set(pts[i]);
					p1[i].set(pts[i]);
					p2[i].set(pts[i]);
				}
				first = false;
				return;
			}
			Vector3f[] old = p0;
			p0 = p1;
			p1 = p2;
			p2 = old;
			for (int i = 0; i < POINTS; i++) {
				p2[i].set(pts[i]);
			}
		}

		/** 0x12F658: quad = (P0 − 2·P1 + P2) / 2, lin = P1 − P0 − quad (P(0) = P0, P(1) = P1, P(2) = P2). */
		void fit() {
			for (int i = 0; i < POINTS; i++) {
				quad[i].set(p0[i]).sub(p1[i]).sub(p1[i]).add(p2[i]).mul(0.5f);
				lin[i].set(p1[i]).sub(p0[i]).sub(quad[i]);
			}
		}

		/** 0x12F718: s = u + 1, out = P0 + lin·s + quad·s² (u = 0 → 지난 프레임, 1 → 이번). */
		void eval(float u, Vector3f[] out) {
			float s = u + 1f;
			float s2 = s * s;
			for (int i = 0; i < POINTS; i++) {
				out[i].set(p0[i]).fma(s, lin[i]).fma(s2, quad[i]);
			}
		}
	}

	/** 블레이드 하나의 궤적 인스턴스 (원작 +0xB4). */
	public final class Side {
		public final int index;
		final Strip[] ring = new Strip[CAPACITY];
		/** 쓰기 칸 +0x178 · 꼬리 +0x170: 살아 있는 띠 = [tail, head). */
		int head, tail;
		/** +0x150 트레일 시계 (초). */
		float clock;
		/** +0x15C: 지난 프레임에 켜져 있었음. */
		boolean wasOn;
		final MatrixHistory mh = new MatrixHistory();
		final PointHistory ph = new PointHistory();
		/** 이번 틱에 넣은 하위 표본 수 (확인용, 지금 표본 제외). */
		public int lastSubSamples;

		Side(int index) {
			this.index = index;
			for (int i = 0; i < CAPACITY; i++) {
				ring[i] = new Strip();
			}
		}

		/** 살아 있는 띠 수. */
		public int size() {
			return (head - tail + CAPACITY) % CAPACITY;
		}

		/** k 번째 살아 있는 띠 (0 = 가장 오래된 것). */
		public Strip strip(int k) {
			return ring[(tail + k) % CAPACITY];
		}

		/** 0x12E768: 이력·띠 전부 지움 (+0x15C = 0). 링 칸 번호는 그대로 (지운 띠는 수명이 지나 꼬리에서 빠진다 — 엔진은 바로 비움). */
		public void reset() {
			mh.reset();
			ph.reset();
			wasOn = false;
			tail = head;
		}

		/**
		 * 한 프레임 (0x240A88).
		 *
		 * @param blade 블레이드 행렬 (배율 없음, 0x1FBE04 의 리소스 행렬)
		 * @param world 점 3 개 월드 (베지어 t 0.2 · t 0.7 · 칼끝)
		 * @param on    +0x158 (기술 이벤트 17/18)
		 */
		public void update(Matrix4f blade, Vector3f[] world, boolean on, float dt) {
			float before = clock;
			clock += dt; // 0x240B88: +0x150 += 1/60
			float[] m = blade.get(new float[16]);
			mh.tick(dt);
			mh.push(m);
			Matrix4f inv = new Matrix4f(blade).invertAffine(); // 0x1FBDEC: 0x185490
			Vector3f[] local = new Vector3f[POINTS];
			for (int i = 0; i < POINTS; i++) {
				local[i] = inv.transformPosition(world[i], new Vector3f());
			}
			ph.push(local);
			lastSubSamples = 0;
			if (on) {
				int first = head; // s6 = +0x178[s5]
				int n = Math.min(MAX_STEPS, mh.steps(ROT_WEIGHT, MOVE_WEIGHT));
				if (n != 0) {
					mh.fit();
					ph.fit();
					float du = 1f / n, dtime = (clock - before) / n;
					float u = 0, time = before;
					float[] mi = new float[16];
					Matrix4f mm = new Matrix4f();
					Vector3f[] li = {new Vector3f(), new Vector3f(), new Vector3f()};
					Vector3f[] wi = {new Vector3f(), new Vector3f(), new Vector3f()};
					for (int k = 1; k < n; k++) {
						u += du; // f21 += 1/n (실수 누적 그대로)
						time += dtime; // f20 += (지금 − 전)/n
						mh.eval(u, mi);
						ph.eval(u, li);
						mm.set(mi);
						for (int i = 0; i < POINTS; i++) {
							mm.transformPosition(li[i], wi[i]); // 0x12E8F0: 행렬 × (16·점, 16) → vftoi0
						}
						add(wi, time);
						lastSubSamples++;
					}
				}
				add(world, clock); // 0x240D70: 지금 행렬 · 지금 점
				if (!wasOn) {
					Strip s = ring[first]; // 0x240D84 – 0x240DF0: 옛 정점 = 새 정점
					for (int i = 0; i < POINTS; i++) {
						s.prev[i].set(s.next[i]);
					}
				}
			}
			wasOn = on; // 0x240E34: +0x15C = +0x158
			// 0x240DF4 – 0x240E9C: 꼬리부터 (시각 < 지금 − 수명) 인 띠를 지움
			float limit = clock - LIFETIME;
			while (tail != head && ring[tail].time < limit) {
				tail = (tail + 1) % CAPACITY;
			}
			// 0x240FB0 – 0x2410AC: T = 1 − 나이 / 수명, 꼬리 띠의 옛 정점 0
			float prevT = 0;
			for (int k = tail; k != head; k = (k + 1) % CAPACITY) {
				Strip s = ring[k];
				float t = 1f - (clock - s.time) / LIFETIME;
				s.tPrev = prevT;
				s.tNext = t;
				prevT = t;
			}
		}

		/** 0x12E8F0: 쓰기 칸에 표본, 옛 정점 = 이전 칸의 새 정점. 링이 차면 꼬리를 지운다 (0x12E518). */
		private void add(Vector3f[] pts, float time) {
			int idx = head;
			int prev = (head - 1 + CAPACITY) % CAPACITY;
			head = (head + 1) % CAPACITY;
			if (head == tail) {
				tail = (tail + 1) % CAPACITY;
			}
			Strip s = ring[idx];
			Strip p = ring[prev];
			s.time = time;
			for (int i = 0; i < POINTS; i++) {
				s.prev[i].set(p.next[i]);
				s.next[i].set(pts[i]);
			}
		}
	}

	public final Side[] sides = {new Side(0), new Side(1)};

	/** 0x12E768 (블레이드 순간 이동 0x1FDB18 ← 영웅 SetMatrix 0x1E0A18 → 0x201140 → 0x202080). */
	public void reset() {
		for (Side s : sides) {
			s.reset();
		}
	}

	/**
	 * 블레이드 갱신 0x1FB460 끝 (0x1FBDF8 – 0x1FBFE0) 이 리소스에 쓰는 세 점 (월드): 베지어 t 0.2, t 0.7, 칼끝 P3 − Z · 블레이드 길이.
	 *
	 * @param bladeLen Weapon Length × 16 (god 이면 God Mode Weapon Length, 0x1FBF70)
	 */
	public static Vector3f[] points(BladeChain.Side s, float bladeLen) {
		return new Vector3f[]{
				BladeChain.bezier(s.ctrl, T_NEAR, new Vector3f()),
				BladeChain.bezier(s.ctrl, T_MID, new Vector3f()),
				new Vector3f(s.bladeZ).mul(-bladeLen).add(s.ctrl[3])};
	}

	/**
	 * 사슬 한 틱 뒤 (HeroRig.tickBlades): 블레이드마다 0x1FB460 이 쓴 행렬·점으로 0x240A88.
	 *
	 * @param on       블레이드별 +0x158 (기술 이벤트 17/18)
	 * @param bladeLen Weapon Length × 16
	 */
	public void update(BladeChain chain, boolean[] on, float bladeLen, float dt) {
		for (Side t : sides) {
			BladeChain.Side s = chain.sides[t.index];
			if (!s.valid()) {
				continue;
			}
			t.update(s.blade, points(s, bladeLen), on[t.index], dt);
		}
	}
}
