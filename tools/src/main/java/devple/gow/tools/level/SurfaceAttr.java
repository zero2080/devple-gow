package devple.gow.tools.level;

/**
 * 충돌 시트 표면 속성 (SheetHdr 섹션 [4] 비트필드 스키마의 이름, 12 문서 §2.1.1).
 * 스키마 엔트리 0x4C 바이트: {@code char[32] 이름, u32 타입(2 = 비트, 0 = 정수, 1 = 실수), u32 기본값, …,
 * +0x3C u32 바이트 오프셋, +0x40 u32 ?, +0x44 u32 비트 번호(레코드 전체 기준), +0x48 u32 폭}.
 * 이름 → 비트 위치는 시트마다 스키마로 읽는다 (전 레벨 102 시트 동일 배치) — 여기 순서는 엔진 쪽 비트마스크 번호일 뿐이다.
 */
public enum SurfaceAttr {
	GROUND("Ground"), WATER("Water"), NARROW("Narrow"), CLIMBABLE("Climbable"), LADDER("Ladder"), SLIDE("Slide"), DEATH("Death"),
	AI_BLOCK("AIBlock"), CLIMB_GUIDE("ClimbGuide"), PUSH_PULL_SLIDE("PushPullSlide"), BACK_PRESS("BackPress"),
	NO_PLAYER_COLLISION("NoPlayerCollision"), NO_AI_COLLISION("NoAICollision"), NO_PLAYER_USE("NoPlayerUse"), NO_AI_USE("NoAIUse"),
	GENERAL_GUIDE("GeneralGuide"), NO_DIVING("NoDiving"), NO_PUSH_PULL_COLLISION("NoPushPullCollision"), COMBAT_GUIDE("CombatGuide"),
	NARROW_NO_BB("NarrowNoBB"), NO_CSM_COLLISION("NoCSMCollision"), TAKE_DAMAGE("TakeDamage"), NO_IK("NoIK"), NO_DECALS("NoDecals"),
	DARK_SURFACE("DarkSurface"), NO_WALL_HANG("NoWallHang"), CAM_REL_CONTROL("CamRelControl"), NO_LADDER_SLIDING("NoLadderSliding"),
	NO_BACK_JUMPS("NoBackJumps"), NO_SHADOWS("NoShadows"), DOUBLE_SIDED("doubleSided");

	/** 스키마 이름 (데이터 문자열 그대로). */
	public final String schemaName;

	SurfaceAttr(String schemaName) {
		this.schemaName = schemaName;
	}

	public long bit() {
		return 1L << ordinal();
	}

	public static SurfaceAttr bySchemaName(String n) {
		for (SurfaceAttr a : values()) {
			if (a.schemaName.equals(n)) {
				return a;
			}
		}
		return null;
	}

	/** 마스크 → "Ground,Climbable" 형식. */
	public static String names(long mask) {
		StringBuilder sb = new StringBuilder();
		for (SurfaceAttr a : values()) {
			if ((mask & a.bit()) != 0) {
				if (!sb.isEmpty()) {
					sb.append(',');
				}
				sb.append(a.schemaName);
			}
		}
		return sb.toString();
	}
}
