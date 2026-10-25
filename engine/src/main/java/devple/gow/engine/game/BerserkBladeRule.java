package devple.gow.engine.game;

import devple.gow.engine.nav.NavState;

/**
 * 아르테미스의 검 숨김 · 해제 (원작 BerserkBlade_Update 0x1DB788 — 플레이어 갱신 0x1D6CF0 이 매 프레임 0x1DB608 뒤에 부른다) [확실: 디스어셈블].
 * <pre>
 * 장착 (플레이어 0x29DC1C +0xC &amp; 0x20) 이 아니면 아무것도 하지 않는다. 검 GO (0x29D818) 가 없으면 장착 0x19C240(1) 로 다시 만든다
 * (레벨 적재 · 업그레이드 메뉴 0x19C520 뒤). 그 뒤 영웅 +0x174 가
 *   (0x400 줄 이고 0x20000 수평 줄) 또는 (0x20 벽 이고 0x400000 사다리도 0x300000 좁은 턱 (벽 보고 0x100000 · 등 대고 0x200000) 도 아님 = 오르는 벽)
 *     → <b>해제</b> 0x19C240(0): +0xC &amp;= ~0x20, 검 GO 지움 (0x132768), 내비 애니 뱅크 (영웅 vt+0x5C = +0x328, +0xA8) = 0,
 *       혼돈의 블레이드 보임 (0x201908 왼 · 오). 저절로 돌아오지 않는다 — 0x19C240(1) 을 부르는 곳은 SCR_EnableBerserkBlade (0x19C578,
 *       BERSERKENTER = L1+R1) 와 위의 GO 재생성 (장착 비트가 서 있을 때만) 뿐이다.
 *   그 밖에 SCR_HideBerserkBlade 가 살아 있거나 (0x29D814: 영웅 기술의 스크립트 이벤트 0x19C6C0 이 1, 끝 콜백 0x19C608 이 같은 이벤트가
 *   더 없을 때 0 — 잡기 GRAB · 처치 MAI* · 마법 · 메두사 머리 · 석화 등 87 기술) 또는 +0x174 &amp; (0x4000 물체 붙잡기 | 0x400 줄 | 0x10 턱 매달림 |
 *   0x380 물 | 0x80000 좁은 바닥 | 0x20 벽) 또는 +0x2D8 &amp; 0x800000 (애니 구동 내비 전환) 이면
 *     → <b>숨김</b>: 검 GO 끔 (vt+0x44), 블레이드 보임 (0x201908). 장착 비트 · 애니 뱅크 · 피해 배율 (0x1DBD18 은 +0xC &amp; 0x20 만 봄) 은 그대로.
 *   아니면 → <b>보임</b>: 검 GO 다시 켬 (vt+0x34), 블레이드 숨김 (0x201930) — 숨김 상태가 끝나면 저절로 돌아온다.
 *   (보일 때 부위 10 공격 중이면 (0x209D00(…, 10) ≠ 0) 검 선분이 벽에 닿는지 보고 불꽃 0x202248 — 엔진은 {@code MagicFx.weaponWorld} 근사.)
 * </pre>
 * 영상 (2화): 5104 · 5108 검을 어깨에 걸치고 뜀 → 5110 수평 줄을 잡자 등에 혼돈의 블레이드 → 5118 · 5150 · 5166 줄 · 세로 줄 · 달리기 내내 블레이드
 * (다시 들지 않음). 5100 초록 상자를 여는 동안 등에 블레이드 → 5102 상자가 끝나자 손에 검 (L1+R1 없이).
 */
public final class BerserkBladeRule {

	private BerserkBladeRule() {
	}

	public enum Action {
		/** 장착하지 않음 — 할 일 없음. */
		NONE,
		/** 해제 (0x19C240(0)). */
		UNEQUIP,
		/** 장착한 채 검 GO 를 숨기고 블레이드를 보임. */
		HIDE,
		/** 장착한 채 검 GO 를 보이고 블레이드를 숨김. */
		SHOW
	}

	/** 영웅 +0x174 비트 (이 규칙이 보는 것, 32 문서 표 · 38 문서). */
	public static final int GROUND = 0x1, JUMP = 0x4, FALL = 0x8, HANG = 0x10, WALL = 0x20, WATER = 0x80, ROPE = 0x400, GRAB_OBJECT = 0x4000,
			HORIZONTAL_ROPE = 0x20000, ZIPLINE = 0x40000, NARROW_FLOOR = 0x80000, WALL_FRONT = 0x100000, WALL_BACK = 0x200000, LADDER = 0x400000;
	/** 숨김 비트 (0x1DB840 – 0x1DB888). */
	public static final int HIDE_BITS = GRAB_OBJECT | ROPE | HANG | 0x380 | NARROW_FLOOR | WALL;
	/** SCR_HideBerserkBlade 의 Hash127Upper (Items_RegisterScr 0x19CFB0 → 0x19C6C0). */
	public static final int SCR_HIDE_BERSERK_BLADE = 0x8CACF07D;

	/**
	 * 0x1DB788 의 판정.
	 *
	 * @param equipped   플레이어 +0xC &amp; 0x20
	 * @param heroState  영웅 +0x174 ({@link #stateBits})
	 * @param hideScript SCR_HideBerserkBlade 가 살아 있음 (0x29D814)
	 * @param navAnim    +0x2D8 &amp; 0x800000 (애니 구동 내비 전환)
	 */
	public static Action decide(boolean equipped, int heroState, boolean hideScript, boolean navAnim) {
		if (!equipped) {
			return Action.NONE;
		}
		boolean horizontalRope = (heroState & ROPE) != 0 && (heroState & HORIZONTAL_ROPE) != 0;
		boolean climbWall = (heroState & WALL) != 0 && (heroState & LADDER) == 0 && (heroState & (WALL_FRONT | WALL_BACK)) == 0;
		if (horizontalRope || climbWall) {
			return Action.UNEQUIP;
		}
		return hideScript || (heroState & HIDE_BITS) != 0 || navAnim ? Action.HIDE : Action.SHOW;
	}

	/**
	 * 엔진 내비 상태 → 원작 영웅 +0x174 (이 규칙이 보는 비트만): 지상 0x1 (Narrow 바닥이면 |0x80000 — 0x1BFA70, NarrowNoBB 는 0x2000 뿐),
	 * 공중 0x4 (뛰어서) / 0x8 (떨어짐), 턱 매달림 · 평균대 매달림 0x10 (0x1C75F8), 벽 오르기 0x20 (0x1C7280), 좁은 턱 0x20 | 0x100000
	 * (등 대고 0x200000), 사다리 0x20 | 0x400000, 매단 줄 0x400, 수평 줄 0x400 | 0x20000, 짚라인 0x400 | 0x40000 (줄 +0x374, 0x1C4EF8),
	 * 물 0x80 (0x380 중 하나), 밀기 상자 · 상황 동작 (CSM 상자 · 문 · 크랭크 · 레버) 0x4000 (물체 붙잡기 0x1E17F0).
	 */
	public static int stateBits(NavState s, boolean grounded, boolean jumped, boolean wallBack, boolean balancing) {
		if (s == null) {
			s = grounded ? NavState.GROUND : NavState.AIR;
		}
		return switch (s) {
			case GROUND -> GROUND | (balancing ? NARROW_FLOOR : 0);
			case AIR -> jumped ? JUMP : FALL;
			case LEDGE_HANG -> HANG;
			case WALL_CLIMB -> WALL;
			case WALL_WALK -> WALL | (wallBack ? WALL_BACK : WALL_FRONT);
			case LADDER -> WALL | LADDER;
			case ROPE -> ROPE;
			case ROPE_TRAVERSE -> ROPE | HORIZONTAL_ROPE;
			case ZIPLINE -> ROPE | ZIPLINE;
			case SWIM -> WATER;
			case PUSH, INTERACT -> GRAB_OBJECT;
		};
	}
}
