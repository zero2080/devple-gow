package devple.gow.engine.nav;

/**
 * 영웅 이동 상태 (원작 Navigation 표의 동작 묶음, 38 문서 §2).
 * GROUND / AIR 는 기존 {@code HeroController} + 게임 씬 물리가 처리하고, 나머지는 {@link HeroNavigator} 가 위치를 직접 정한다.
 */
public enum NavState {
	/** 지상 (걷기·균형 걷기 — 균형은 {@link HeroNavigator#balancing()}) */
	GROUND,
	/** 공중 (점프·낙하, 줄에서 뛰어내린 뒤) */
	AIR,
	/** 턱 매달리기 ANIWallHang / 좌우 이동 ANIWallHangL/R / 올라서기 ANIWallPullup */
	LEDGE_HANG,
	/** 벽 오르기 (Climbable 면) ANIClimb 블렌드 bClimb / ANIClimbIdle */
	WALL_CLIMB,
	/** 사다리 (Ladder 면) ANILadderU/D, 꼭대기 ANILDis1 */
	LADDER,
	/** 매단 줄 오르기·흔들기 ANIRope / ANIRopeU/D / ANIRopeSW* */
	ROPE,
	/** 양끝 고정 수평 줄 손 이동 ANIBRope / ANIBRopeF */
	ROPE_TRAVERSE,
	/** 짚라인 미끄럼 ANIZipLine */
	ZIPLINE,
	/** 수영 (Water 면) ANISwim / ANISwimIdle */
	SWIM,
	/** 상자 밀기/당기기 ANIPushI / ANIPushF / ANIPushB */
	PUSH,
	/** 상황 동작 (CSM: 문 차기·상자 열기, 크랭크·레버) */
	INTERACT
}
