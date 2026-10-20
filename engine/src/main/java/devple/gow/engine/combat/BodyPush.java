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
 *   <li>질의 후보 = 질의 구 (기준점 + 위 × 키/2, 반지름 키/2 + R) 에 닿는 상대 CDV 구 엔트리 — 엔트리마다 콜백 한 번 ({@link #candidates}, 0x175648) [확실].
 *       서 있는 적 (지상·속도·변위 0) 은 이 함수를 부르지 않는다 (0x1EBB38 → 0x1E9470, {@link EnemyNav}); 플레이어는 정지 판정 없음.</li>
 *   <li><b>플레이어 상자는 매 프레임 바뀐다</b> (0x1EBD78 → 0x1E6DF0 — owner+0x174 비트 31 플레이어만, 적·보스는 Sold 상자, {@link JointBox}): Sold +0xA4 관절 (크라토스 head)
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

		/**
		 * 충돌 세계에 오른 자기 CDV 구 (월드 x, y, z, r) — 밀기 질의 후보 (0x11E8D0 → 0x11E090 → 0x175648) [확실].
		 * null = 알 수 없음 (구 없는 시험 몸 등) → 늘 후보 [근사: 원작은 CDV 엔트리가 없으면 후보가 아님].
		 */
		default java.util.List<float[]> pushSpheres() {
			return null;
		}
	}

	/**
	 * 밀기 질의 0x1E2DD8 → 0x11E8D0 의 후보 수: 질의 구 (중심 = 자기 기준점 b + 위 × 키/2, 반지름 = 키/2 + R, 0x1E2E4C–0x1E2ED8) 와
	 * 겹치는 상대 CDV 구 (0x175648: d² &lt; (r1 + r2)², 범주 &amp; 3) 개수 — 콜백 0x1E28B0 은 후보 엔트리마다 한 번씩 불린다 [확실].
	 * 질의 구는 콜백 전에 한 번 정해진다 (후보 목록이 먼저, 밀린 기준점은 다음 콜백의 캡슐에만).
	 */
	static int candidates(float cx, float cy, float cz, float qr, Body o) {
		java.util.List<float[]> sp = o.pushSpheres();
		if (sp == null) {
			return 1;
		}
		int n = 0;
		for (float[] s : sp) {
			float dx = s[0] - cx, dy = s[1] - cy, dz = s[2] - cz, rr = s[3] + qr;
			if (dx * dx + dy * dy + dz * dz < rr * rr) {
				n++;
			}
		}
		return n;
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
		/** 0x29F0C8: 지난 갱신이 관절·동기 상자였는지 (전역 — 부르는 생물이 플레이어 하나뿐이라 영웅 상태). */
		private boolean lastJoint;
		/** 0x29F0CC: 공중에서 관절 상자 조건이 아닌 연속 갱신 수 (5 까지 이전 상자 유지). */
		private int airHold;

		/**
		 * 0x1E6DF0 의 조건 (오너 비트) [확실: 디스어셈블].
		 *
		 * @param hasMove  전투 기술 있음 (combatant +0x4C ≠ 0)
		 * @param sync     기술 flags (+0xE) &amp; 0x60 — 동기 상자
		 * @param ground   +0x174 &amp; (1|2) 지상
		 * @param air      +0x174 &amp; 0x4C 공중 (점프 4·낙하 8·날기 0x40)
		 * @param attached +0x174 0x4000 오브젝트 붙잡기 (0x1E17F0: 밀기·당기기·크랭크·CSM 과제)
		 * @param water    +0x174 &amp; 0x380 물 (공중 쪽 검사는 0x300)
		 * @param landed   +0x2D8 0x800000 — 지난 프레임 착지 (0x1E9F78)·벽 → 지상 (0x1C79B8)·애니 구동 내비 상태 (상태 노드 +0x18 &amp; 0x10, 0x1ECD4C).
		 *                 0x1EBD78 이 0x1E6DF0 <b>뒤</b> (0x1EC2F0) 에 지우므로 세운 다음 프레임 한 번만 보인다
		 * @param diving   +0x2D8 0x100000 물 밖 도약·다이빙 (0x1C9838, 38 §11)
		 */
		public record State(boolean hasMove, boolean sync, boolean ground, boolean air, boolean attached, boolean water, boolean landed, boolean diving) {
		}

		/**
		 * 0x1E6DF0 한 번 (0x1EBD78 첫머리, owner+0x174 비트 31 = 플레이어일 때만 — 0x1EBDAC `bgez`) [확실].
		 * <ul>
		 *   <li>동기 → 변위 전체 (기준 이동 없음). 지상 (붙잡기 아님, 착지 직후 아님) 또는 기술 중 공중 (다이빙·물·착지 직후 아님) → 관절 상자 (변위 반, 기준 이동).</li>
		 *   <li>그 밖: 물 (0x380) 이면 상자 그대로, 공중이면 5 번까지 그대로 (0x1E7200, 카운터 0x29F0CC), 아니면 Sold 상자.
		 *       카운터는 관절·동기 상자이거나 지상·비공중이면 0.</li>
		 * </ul>
		 * 관절·Sold 상자가 바뀌는 갱신에 +0x158 = 0.1 · +0x15C = 1.111 · +0x160 = 0 과 +0x174 0x40000000 을 세운다 (0x1E7198 / 0x1E729C) — 엔진 미사용 [추정: 위치 보간].
		 *
		 * @return 이번에 쓴 종류 (0 = Sold, 1 = 관절, 2 = 동기, 3 = 유지)
		 */
		public int step(float soldR, float height, float jx, float jz, float yaw, State s) {
			if (s.sync && s.hasMove) {
				update(soldR, height, jx, jz, yaw, true, false);
				lastJoint = true;
				airHold = 0;
				return 2;
			}
			boolean joint;
			if (s.ground && !s.attached) {
				joint = !s.landed; // 0x1E7018
			} else if (!s.air) {
				joint = false; // 0x1E6FE8
			} else {
				joint = s.hasMove && !s.diving && !s.water && !s.landed; // 0x1E6FF0–0x1E7020
			}
			if (joint) {
				update(soldR, height, jx, jz, yaw, false, true);
				lastJoint = true;
				airHold = 0;
				return 1;
			}
			int kind;
			if (s.water) {
				kind = 3; // 0x1E71EC: 0x380 → 상자 그대로
			} else if (s.air && airHold < 5) {
				airHold++; // 0x1E7200–0x1E7280
				kind = 3;
			} else {
				update(soldR, height, 0, 0, yaw, false, false);
				kind = 0;
			}
			lastJoint = false;
			if (s.ground || !s.air) {
				airHold = 0; // 0x1E72D0–0x1E72EC
			}
			return kind;
		}

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

		private final java.util.function.Supplier<devple.gow.engine.nav.NavState> navState;
		private devple.gow.engine.nav.NavState lastState;
		private long lastTick = Long.MIN_VALUE;

		public HeroBody(devple.gow.engine.anim.HeroController ctl, BooleanSupplier airborne, Combatant combat) {
			this(ctl, airborne, combat, null);
		}

		/**
		 * @param navState 영웅 내비게이션 상태 (null = 지상/공중만 — {@code airborne} 으로) — 관절 상자 조건 비트 (+0x174 붙잡기·물, 착지 직후) 에 쓴다
		 */
		public HeroBody(devple.gow.engine.anim.HeroController ctl, BooleanSupplier airborne, Combatant combat,
				java.util.function.Supplier<devple.gow.engine.nav.NavState> navState) {
			this.ctl = ctl;
			this.airborne = airborne;
			this.combat = combat;
			this.navState = navState;
			var sk = ctl.animator().skeleton();
			head = sk.indexOf("head");
			zero = sk.indexOf("zeroJoint");
			box.radius = AiData.Body.HERO.half() * 16f;
		}

		/** 같은 틱에 여러 번 불려도 한 번만 갱신한다 (원작 0x1E6DF0 = 0x1EBD78 첫머리 한 번 — 공중 유지 카운터·착지 비트가 프레임 단위). */
		public void refresh(long tick) {
			if (tick == lastTick) {
				return;
			}
			lastTick = tick;
			refresh();
		}

		/**
		 * 0x1E6DF0: 이번 자세의 머리 관절로 상자를 다시 만든다 — 조건 비트는 {@link JointBox#step} [확실: 비트 / 근사: 엔진 상태 대응].
		 * 엔진 대응: 지상·공중 = 내비 GROUND/AIR (또는 {@code airborne}), 붙잡기 0x4000 = PUSH·INTERACT, 물 = SWIM,
		 * 벽·턱·사다리·줄 = 지상도 공중도 아님 (Sold 상자), 착지 직후 0x800000 = 지난 갱신이 지상이 아니다가 지상 [근사: 원작은 세운 다음 프레임 — 엔진 갱신은
		 * 영웅 이동 뒤라 같은 틱], 다이빙 0x100000 = 없음 [근사].
		 */
		public void refresh() {
			float r = AiData.Body.HERO.half() * 16f, h = AiData.Body.HERO.height() * 16f;
			devple.gow.engine.nav.NavState st = navState != null ? navState.get() : null;
			boolean ground, air, attached = false, water = false;
			if (st == null || st == devple.gow.engine.nav.NavState.GROUND || st == devple.gow.engine.nav.NavState.AIR) {
				air = st == devple.gow.engine.nav.NavState.AIR || airborne.getAsBoolean();
				ground = !air;
			} else if (st == devple.gow.engine.nav.NavState.SWIM) {
				ground = air = false;
				water = true;
			} else if (st == devple.gow.engine.nav.NavState.PUSH || st == devple.gow.engine.nav.NavState.INTERACT) {
				ground = attached = true;
				air = false;
			} else {
				ground = air = false; // 턱·벽·사다리·줄·짚라인
			}
			devple.gow.engine.nav.NavState now = ground && !attached ? devple.gow.engine.nav.NavState.GROUND : st != null ? st
					: air ? devple.gow.engine.nav.NavState.AIR : devple.gow.engine.nav.NavState.GROUND;
			boolean landed = lastState != null && now == devple.gow.engine.nav.NavState.GROUND && lastState != devple.gow.engine.nav.NavState.GROUND
					&& lastState != devple.gow.engine.nav.NavState.PUSH && lastState != devple.gow.engine.nav.NavState.INTERACT;
			lastState = now;
			CombatFile.Move mv = combat != null && combat.active() ? combat.move() : null;
			boolean sync = mv != null && (mv.flags() & 0x60) != 0;
			var an = ctl.animator();
			float jx = 0, jz = 0;
			if (head >= 0 && an.current() != null) {
				float[][] w = an.pose().world(an.skeleton());
				org.joml.Matrix4f m = new org.joml.Matrix4f().set(w[head]);
				if (zero >= 0) {
					m = new org.joml.Matrix4f().set(w[zero]).invert().mul(m);
				}
				Vector3f j = m.getTranslation(new Vector3f());
				jx = j.x;
				jz = j.z;
			}
			box.step(r, h, jx, jz, ctl.yaw, new JointBox.State(mv != null, sync, ground, air, attached, water, landed, false));
		}

		/** 지금 상자 (시험용). */
		public JointBox box() {
			return box;
		}

		/** CDV_hero 구 (블레이드는 별도 GO maiblade 라 빠짐). */
		@Override
		public java.util.List<float[]> pushSpheres() {
			HitVolumes hv = combat != null ? combat.hitVolumes() : null;
			return hv != null && !hv.isEmpty() ? hv.ownSpheres() : null;
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
		// 질의 구 (0x1E2E4C–0x1E2ED8): 중심 = 기준점 b + 위 × 키/2, 반지름 = 키/2 + R — 후보는 밀기 전 위치로 한 번에 정해진다
		float qcx = feet.x, qcy = feet.y - base + h0 * 0.5f, qcz = feet.z, qr = h0 * 0.5f + r0;
		java.util.List<Body> cand = new java.util.ArrayList<>();
		java.util.List<Integer> count = new java.util.ArrayList<>();
		for (Body o : others) {
			if (o == null || o == self) {
				continue;
			}
			int k = candidates(qcx, qcy, qcz, qr, o);
			if (k > 0) {
				cand.add(o);
				count.add(k);
			}
		}
		for (int ci = 0; ci < cand.size(); ci++) {
			Body o = cand.get(ci);
			for (int rep = count.get(ci); rep > 0; rep--) { // 콜백은 상대 CDV 구 엔트리마다 한 번 (같은 상대가 여러 번 — 대개 첫 번에 경계까지 밀려 뒤는 0)
				pushed |= pushOnce(self, feet, o, r0, base, h0, selfAir, wallNormal);
			}
		}
		return pushed;
	}

	/** 콜백 0x1E28B0 한 번: 상대 o 캡슐에서 자기를 민다. */
	private static boolean pushOnce(Body self, Vector3f feet, Body o, float r0, float base, float h0, boolean selfAir, Vector3f wallNormal) {
		if (!o.solid()) {
			return false;
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
			return false;
		}
		if (!selfAir && o.airborne()) {
			return false; // 0x1E2B80: 자신 지상 + 상대 공중 → 밀지 않음
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
				return false;
			}
			right.normalize();
			d = new Vector3f(up).mul(n.dot(up) * depth).fma(n.dot(right) * depth, right);
		} else {
			d = n.mul(depth);
		}
		feet.add(d);
		return true; // 콜백 반환 0 = 맞음·계속 (0x11EA48: 0xC 만 끝냄, 0xB = 안 맞음) → 다음 상대는 밀린 기준점으로
	}
}
