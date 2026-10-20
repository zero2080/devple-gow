package devple.gow.engine.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 공격 대상별 공유 상태 = 원작 "대상 슬롯" 구조체 (엔티티 vtable+0x78, 17 문서 §4). 같은 대상을 노리는 적들이 공유한다.
 * <pre>
 * +0x0C 토큰 예산 사용량   — 토큰을 쥔 적들의 Sold 비용 합. 예산 + 비용 ≤ 대상 Sold +0x246 일 때만 새로 쥘 수 있다 (0x200130)
 * +0x0E / +0x10 공격 슬롯 A/B 사용량 — 결정 트리 공격 항목의 비용 A/B. 용량 = 대상 Sold +0x248/+0x24A (0x1CCB90)
 * +0x16 bit 2 — (적 자신의 슬롯에서) "토큰 없음"
 * </pre>
 * 크라토스(용량 12/10/10)에 대해: 해골(비용 4) 토큰 3명, 공격(비용 A 3) 동시 3회 / 미노타우르스(비용 2) 토큰 6명,
 * 도끼 공격(비용 A 5) 동시 2회.
 * <p>
 * 영웅 기술 사용 기록(0x3044D0, BlockMod 판단용)도 여기에 둔다.
 */
public final class AiTargets {

	/** 대상 하나의 슬롯. */
	public static final class Slot {
		public final AiData.Soldier sold;
		int tokenBudget, usedA, usedB;
		/** 포위 칸 점유 (칸 번호 → 점유자). 11×11 칸 × 32 단위 (0x1CD83C). */
		final Map<Integer, Object> cells = new HashMap<>();
		public final MoveHistory history = new MoveHistory();
		/** 기록 갱신 담당 (같은 프레임에 여러 적이 중복 갱신하지 않도록 한 명만). */
		private AiBrain ticker;
		/** 이 대상을 노리는 AI (경보 전파용). */
		final java.util.Set<AiBrain> brains = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

		Slot(AiData.Soldier sold) {
			this.sold = sold;
		}

		/** 이 적이 이번 프레임 기록 갱신 담당인지 (담당이 죽었으면 넘겨받음). */
		boolean claimTick(AiBrain b) {
			if (ticker == null || ticker.dead()) {
				ticker = b;
			}
			return ticker == b;
		}

		public int tokenBudget() {
			return tokenBudget;
		}

		public int usedA() {
			return usedA;
		}

		public int usedB() {
			return usedB;
		}

		/** 0x200130: 예산 + 비용 ≤ 용량. */
		boolean canTake(int cost) {
			return tokenBudget + cost <= sold.capacity();
		}

		void take(int cost) {
			tokenBudget += cost;
		}

		void give(int cost) {
			tokenBudget = Math.max(0, tokenBudget - cost);
		}

		int freeA() {
			return Math.max(0, sold.capA() - usedA);
		}

		int freeB() {
			return Math.max(0, sold.capB() - usedB);
		}
	}

	/**
	 * 영웅 기술 사용 기록 (0x3044D0 +0x38 ID[6], +0x44 횟수[6], +0x20 타이머[6]; 0x1DC780 / 0x1DC9A8).
	 * 기술 시작마다 같은 ID 횟수 +1 · 타이머 40000(ms) 갱신, 새 ID 는 타이머가 가장 작은 칸을 덮고 횟수 0 부터.
	 */
	public static final class MoveHistory {
		static final int SLOTS = 6, WINDOW_MS = 40000;
		final int[] ids = {-1, -1, -1, -1, -1, -1};
		final int[] counts = new int[SLOTS];
		final int[] timers = new int[SLOTS];
		private Object lastMove;
		private float lastProgress;

		public void onMoveStart(int id) {
			int slot = -1, oldest = Integer.MAX_VALUE;
			for (int i = 0; i < SLOTS; i++) {
				if (ids[i] == id) {
					slot = i;
					break;
				}
				if (timers[i] < oldest) {
					oldest = timers[i];
					slot = i;
				}
			}
			if (ids[slot] != id) {
				counts[slot] = 0;
			}
			ids[slot] = id;
			timers[slot] = WINDOW_MS;
			counts[slot]++;
		}

		public void tick(int ms) {
			for (int i = 0; i < SLOTS; i++) {
				if (timers[i] > 0 && (timers[i] -= ms) <= 0) {
					timers[i] = 0;
					counts[i] = 0;
					ids[i] = -1;
				}
			}
		}

		public int count(int id) {
			for (int i = 0; i < SLOTS; i++) {
				if (ids[i] == id) {
					return counts[i];
				}
			}
			return 0;
		}

		/**
		 * 영웅 Combatant 관찰로 기술 시작을 감지 (원작은 Combat_StartBranch 0x209740 에서 직접 기록) [근사].
		 * 같은 기술 객체라도 진행률이 되감기면 새 시작으로 본다.
		 */
		public void observe(Combatant c) {
			if (c == null) {
				return;
			}
			Object m = c.active() ? c.move() : null;
			float p = c.active() ? c.progress() : 0;
			if (m != null && (m != lastMove || p + 0.05f < lastProgress)) {
				onMoveStart(c.move().id());
			}
			lastMove = m;
			lastProgress = p;
		}
	}

	private static final Map<Object, Slot> SLOTS = new WeakHashMap<>();
	private static AiData.Soldier heroSold = AiData.Soldier.HERO;

	private AiTargets() {
	}

	/** 영웅 Sold 구조체 지정 (R_PERMK TWK_Sold_000). 이후 새로 만드는 슬롯에 적용. */
	public static synchronized void setHeroSoldier(AiData.Soldier s) {
		if (s != null) {
			heroSold = s;
		}
	}

	/** 대상의 공유 슬롯 (없으면 영웅 Sold 로 생성). */
	public static synchronized Slot slot(Object target) {
		return SLOTS.computeIfAbsent(target, k -> new Slot(heroSold));
	}

	/** 대상 슬롯 강제 지정 (테스트/특수 대상). */
	public static synchronized Slot slot(Object target, AiData.Soldier sold) {
		Slot s = new Slot(sold);
		SLOTS.put(target, s);
		return s;
	}

	/** 모든 대상 슬롯 버리기 (레벨을 떠날 때 — 슬롯 값이 키를 잡아 WeakHashMap 이 풀지 못한다, 48 문서 §4). */
	public static synchronized void clear() {
		SLOTS.clear();
	}
}
