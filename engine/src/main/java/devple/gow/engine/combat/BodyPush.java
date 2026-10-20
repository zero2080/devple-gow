package devple.gow.engine.combat;

import devple.gow.tools.combat.CombatFile;
import org.joml.Vector3f;

import java.util.function.BooleanSupplier;

/**
 * 생물끼리 밀어내기 — 원작 공용 내비게이션의 이동 단계 0x1EA200 / 0x1EA5F0 가 벽 충돌(0x1E2100) <b>앞에</b> 부르는 0x1E2DD8 과
 * 그 질의 콜백 0x1E28B0 (32 문서 §생물끼리 밀어내기) [확실: 흐름·수식].
 * <ul>
 *   <li>몸 = 수직 캡슐. 상자 owner+0x120 = (지름, 키, 지름) · +0x130 = (0, 바닥, 0) · +0x140 = 키 − 바닥 (0x1E6D58: Sold +0x234 반폭 ×2,
 *       +0x238 키, +0x23C 바닥, × 16 × 개체 스케일 +0x168).</li>
 *   <li>자기 쪽 기준점 b = 위치 − 위 × 바닥 (0x1E2E44), 선분 [b + R, b + 키 − R] (0x1E2A64–0x1E2A88, 0x306808 = +0x120.y 키).
 *       상대 쪽 기준점 = 상대 행렬 × +0x130 = 위치 + 위 × 바닥 (+ 아래 관절 상자의 수평 이동), 선분 [+ r, + 키 − r] (0x1E29B4–0x1E2AB4, 끝점 순서 그대로).
 *       두 선분 최근접 거리 (0x12F7F8) &lt; R + r 이면 겹침, 깊이 = R + r − 거리, 방향 n = 자기 최근접점 − 상대 최근접점.</li>
 *   <li><b>상자는 매 프레임 바뀐다</b> (0x1EBD78 → 0x1E6DF0, {@link JointBox}): Sold +0xA4 관절 (크라토스 head, BMI·히드라 머리·목; 일반 적은 없음 = 루트)
 *       의 수평 변위 j 로 — 동기 기술 (Move +0xE &amp; 0x60) 이면 r = max(Sold r, min(max(|j.x|, |j.z|) + 6.4, 키/2)), 기준 이동 없음;
 *       지상 (+0x174 1|2, 붙잡기 0x4000 아님, +0x2D8 0x800000 아님) 또는 전투 기술 중 공중이면 j/2 로 같은 식 + 기준점을 j/2 만큼 (+0x130.xz);
 *       그 밖 (수영·물건 붙잡기 등) Sold 상자 그대로. 반지름은 Sold 값보다 작아지지 않는다.</li>
 *   <li>질의 필터 = 생물 범주 1|2 (0x1E2E68, 영웅·적 모두), 자신과 붙잡은 대상(+0x188/+0x18C) 제외. 상대의 <b>현재 기술 +0x12 비트 2</b>
 *       (CombatFile Move.stateMask — 기술 없으면 CombatFileSet #9C811767 +0x38, 데이터 전부 0x1F) 가 있어야 단단함 (0x1E2968–0x1E29AC).
 *       stateMask 1 (비트 2 없음) = 목 베기 사망·잡기 처형(mai*)·벽 잡기·스폰·하피 swipe 등 → 통과.</li>
 *   <li>자신이 공중 (+0x174 &amp; 0x4C) 이면 n × 깊이 전부. 지상이면 공중인 상대는 무시, 그 밖은 n × 깊이 (자유),
 *       벽 오르기 (0x20) 는 n 을 몸 위·오른쪽 축에 투영 (벽 평면 안에서만), 턱 매달림 (0x10) 은 오른쪽 축만, 사다리 (0x400000) 는 상대보다 아래일 때 위 축만.</li>
 *   <li><b>자기만 민다</b> (상대는 움직이지 않음) — 상대도 자기 갱신에서 같은 계산을 하므로 순서대로 갈라진다. 결과는 누적 (콜백마다 갱신된 기준점).
 *       owner+0x2D8 비트 31 이면 건너뜀 (0x1EA670), 줄 (0x400) 이동 0x1C5848 은 부르지 않음.</li>
 * </ul>
 */
public final class BodyPush {

	private BodyPush() {
	}

	/** 밀어내기에 참여하는 생물 몸 (월드 단위). */
	public interface Body {
		/** 발 위치. */
		Vector3f feet();

		/** 캡슐 반지름 = Sold +0x234 × 16 × 스케일. */
		float bodyRadius();

		/** 키 = Sold +0x238 × 16 × 스케일. */
		float bodyHeight();

		/** 바닥 높이 = Sold +0x23C × 16 × 스케일. */
		float bodyBase();

		/** 공중 (+0x174 &amp; 0x4C: 점프·낙하·날기). */
		boolean airborne();

		/** 단단함 = 현재 기술 stateMask 비트 2 (없으면 CombatFileSet 기본값). false 면 다른 생물이 이 몸을 통과한다. */
		boolean solid();

		/** 상대로 볼 때 캡슐 기준점의 수평 이동 x (월드, +0x130.x 를 행렬로 — {@link JointBox}). */
		default float refX() {
			return 0;
		}

		/** 상대로 볼 때 캡슐 기준점의 수평 이동 z (월드). */
		default float refZ() {
			return 0;
		}
	}

	/**
	 * 관절 상자 0x1E6DF0 (매 프레임): Sold +0xA4 관절의 오브젝트 로컬 수평 변위 (jx, jz) 로 반지름·기준 이동을 정한다 [확실: 코드].
	 * 관절 이름이 없으면 (index −1 → 0x136BD8 이 루트 행렬) 변위 0 → Sold 상자 그대로.
	 */
	public static final class JointBox {
		/** 반지름 (월드). */
		public float radius;
		/** 기준점 수평 이동 (월드). */
		public float offX, offZ;

		/**
		 * @param soldR  Sold 반지름 (+0x234 × 16 × 스케일)
		 * @param height Sold 키 (+0x238 × 16 × 스케일)
		 * @param jx     관절 − 위치 의 로컬 x (+0x50 행)
		 * @param jz     관절 − 위치 의 로컬 z (+0x70 행)
		 * @param yaw    오브젝트 요 (로컬 → 월드)
		 * @param sync   동기 기술 (Move +0xE &amp; 0x60, 0x1E6E68) — 반지름만 (변위 전체), 기준 이동 없음
		 * @param joint  관절 상자 조건 (지상 등, 0x1E6FD0–0x1E7020) — false 면 Sold 상자
		 */
		public void update(float soldR, float height, float jx, float jz, float yaw, boolean sync, boolean joint) {
			radius = soldR;
			offX = offZ = 0;
			if (!sync && !joint) {
				return;
			}
			float hx = sync ? jx : jx * 0.5f, hz = sync ? jz : jz * 0.5f; // 0x1E70A4: × 0.5 (동기 쪽은 없음)
			float f = Math.min(Math.max(Math.abs(hx), Math.abs(hz)) + 6.4f, height * 0.5f); // 0x40CCCCCD
			radius = Math.max(soldR, f);
			if (!sync) {
				// +0x130 = (hx, 바닥, hz) — 상대 콜백이 행렬로 옮김 (0x1E2A08)
				float c = (float) Math.cos(yaw), s = (float) Math.sin(yaw);
				offX = c * hx + s * hz;
				offZ = -s * hx + c * hz;
			}
		}
	}

	/** 기술 stateMask 비트 2 (0x1E29A8) — 기술이 없으면 CombatFileSet 기본 0x1F (데이터 전부) → 단단함. */
	public static boolean solid(Combatant c) {
		if (c == null || !c.active()) {
			return true;
		}
		CombatFile.Move m = c.move();
		return m == null || (m.stateMask() & 2) != 0;
	}

	/** 영웅 몸 (크라토스 Sold_000, 스케일 1). */
	public static Body hero(Vector3f feet, BooleanSupplier airborne, Combatant combat) {
		AiData.Body b = AiData.Body.HERO;
		return new Body() {
			public Vector3f feet() {
				return feet;
			}

			public float bodyRadius() {
				return b.half() * 16f;
			}

			public float bodyHeight() {
				return b.height() * 16f;
			}

			public float bodyBase() {
				return b.base() * 16f;
			}

			public boolean airborne() {
				return airborne.getAsBoolean();
			}

			public boolean solid() {
				return BodyPush.solid(combat);
			}
		};
	}

	/**
	 * 영웅 몸 + 관절 상자 (Sold_000 +0xA4 = "head", 0x1E7318 → +0x352): {@link #refresh} 를 틱마다 한 번 (밀기 전에) 부른다.
	 * 머리가 루트보다 앞으로 나간 자세 (GRAB 달려듦 등) 에서 다른 생물이 보는 영웅 캡슐이 앞으로 옮겨지고 커진다.
	 */
	public static final class HeroBody implements Body {
		private final devple.gow.engine.anim.HeroController ctl;
		private final BooleanSupplier airborne;
		private final Combatant combat;
		private final JointBox box = new JointBox();
		private final int head, zero;

		public HeroBody(devple.gow.engine.anim.HeroController ctl, BooleanSupplier airborne, Combatant combat) {
			this.ctl = ctl;
			this.airborne = airborne;
			this.combat = combat;
			var sk = ctl.animator().skeleton();
			head = sk.indexOf("head");
			zero = sk.indexOf("zeroJoint");
			box.radius = AiData.Body.HERO.half() * 16f;
		}

		/** 0x1E6DF0: 이번 자세의 머리 관절로 상자를 다시 만든다. */
		public void refresh() {
			float r = AiData.Body.HERO.half() * 16f, h = AiData.Body.HERO.height() * 16f;
			var an = ctl.animator();
			if (head < 0 || an.current() == null) {
				box.update(r, h, 0, 0, ctl.yaw, false, false);
				return;
			}
			float[][] w = an.pose().world(an.skeleton());
			org.joml.Matrix4f m = new org.joml.Matrix4f().set(w[head]);
			if (zero >= 0) {
				m = new org.joml.Matrix4f().set(w[zero]).invert().mul(m);
			}
			Vector3f j = m.getTranslation(new Vector3f());
			CombatFile.Move mv = combat != null && combat.active() ? combat.move() : null;
			boolean sync = mv != null && (mv.flags() & 0x60) != 0;
			boolean air = airborne.getAsBoolean();
			box.update(r, h, j.x, j.z, ctl.yaw, sync, !air || mv != null);
		}

		/**
		 * 자기 이동에서 미는지: 자유 이동 기술 (stateMask &amp; 0x1C == 0 — 처형 MAI*·띄우기 등, 0x1ECD60) 이면 자기 stateMask &amp; 2 일 때만 (0x1ECDEC) [확실].
		 */
		public boolean pushesSelf() {
			if (combat == null || !combat.active() || combat.move() == null) {
				return true;
			}
			int sm = combat.move().stateMask();
			return (sm & 0x1C) != 0 || (sm & 2) != 0;
		}

		public Vector3f feet() {
			return ctl.position;
		}

		public float bodyRadius() {
			return box.radius;
		}

		public float bodyHeight() {
			return AiData.Body.HERO.height() * 16f;
		}

		public float bodyBase() {
			return AiData.Body.HERO.base() * 16f;
		}

		public boolean airborne() {
			return airborne.getAsBoolean();
		}

		public boolean solid() {
			return BodyPush.solid(combat);
		}

		public float refX() {
			return box.offX;
		}

		public float refZ() {
			return box.offZ;
		}
	}

	/**
	 * self 를 others 밖으로 민다 (0x1E2DD8). feet 는 이번 틱 이동 후 발 위치 (갱신됨).
	 *
	 * @param wallNormal 벽 오르기 중이면 벽 법선 (밀기를 벽 평면 = 위·오른쪽 축에 투영), 아니면 null
	 * @return 밀렸는지 (원작 반환 0x400)
	 */
	public static boolean push(Body self, Vector3f feet, Iterable<? extends Body> others, Vector3f wallNormal) {
		if (others == null) {
			return false;
		}
		float r0 = self.bodyRadius();
		float base = self.bodyBase();
		float h0 = self.bodyHeight(); // +0x120.y (0x306808 — 키 − 바닥이 아니라 키)
		boolean selfAir = self.airborne();
		boolean pushed = false;
		for (Body o : others) {
			if (o == null || o == self || !o.solid()) {
				continue;
			}
			// 자기 선분 (0x1E2E44, 0x1E2A64–0x1E2A88): 기준점 b = 발 − 위 × 바닥, [b + R, b + 키 − R]
			float by = feet.y - base;
			float a0 = by + r0, a1 = by + h0 - r0;
			// 상대 선분 (0x1E29B4–0x1E2AB4): 기준점 = 상대 행렬 × +0x130 = 발 + 위 × 바닥, [+ r, + 키 − r] (끝점 순서 그대로 — 키 < 2r 이면 뒤집힌 선분)
			float r1 = o.bodyRadius();
			Vector3f q = o.feet();
			float qy = q.y + o.bodyBase();
			float qx = q.x + o.refX(), qz = q.z + o.refZ();
			float b0 = qy + r1, b1 = qy + o.bodyHeight() - r1;
			float aLo = Math.min(a0, a1), aHi = Math.max(a0, a1), bLo = Math.min(b0, b1), bHi = Math.max(b0, b1);
			// 두 수직 선분의 최근접점 (0x12F7F8)
			float pa, pb;
			if (aHi < bLo) {
				pa = aHi;
				pb = bLo;
			} else if (bHi < aLo) {
				pa = aLo;
				pb = bHi;
			} else {
				pa = pb = 0;
			}
			float dx = feet.x - qx, dy = pa - pb, dz = feet.z - qz;
			float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			float sum = r0 + r1;
			if (dist >= sum) {
				continue;
			}
			if (!selfAir && o.airborne()) {
				continue; // 0x1E2B80: 자신 지상 + 상대 공중 → 밀지 않음
			}
			Vector3f n;
			if (dist < 1e-4f) {
				n = new Vector3f(1, 0, 0); // 같은 점: 원작은 0 벡터 정규화 (정의 안 됨) [근사: +x]
			} else {
				n = new Vector3f(dx, dy, dz).div(dist);
			}
			float depth = sum - dist;
			Vector3f d;
			if (!selfAir && wallNormal != null) {
				// 0x1E2CA8: 벽 오르기 — 몸 위축(+0x60)·오른쪽축(+0x50) 성분만
				Vector3f up = new Vector3f(0, 1, 0);
				Vector3f right = new Vector3f(wallNormal).negate().cross(up);
				if (right.lengthSquared() < 1e-6f) {
					continue;
				}
				right.normalize();
				d = new Vector3f(up).mul(n.dot(up) * depth).fma(n.dot(right) * depth, right);
			} else {
				d = n.mul(depth);
			}
			feet.add(d);
			pushed = true; // 콜백 반환 0 = 맞음·계속 (0x11EA48: 0xC 만 끝냄, 0xB = 안 맞음) → 다음 상대는 밀린 기준점으로
		}
		return pushed;
	}
}
