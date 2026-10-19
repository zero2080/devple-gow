package devple.gow.tools.level;

/**
 * 충돌 시트 표면(머티리얼) 레코드 64B (SheetHdr 섹션 [3]): {@code char[24] Maya 머티리얼 이름 + 데이터 40B}.
 * 데이터 배치는 스키마 (섹션 [4]) 가 정한다 — 전 시트 공통:
 * <pre>
 * +0x00 u32 bitfield0  Ground 0, Water 1, Narrow 2, Climbable 3, Ladder 4, Slide 5, Death 6, AIBlock 16, ClimbGuide 17, PushPullSlide 18,
 *                      BackPress 19, NoPlayerCollision 20, NoAICollision 21, NoPlayerUse 22, NoAIUse 23, GeneralGuide 24, NoDiving 25,
 *                      NoPushPullCollision 26, CombatGuide 27, NarrowNoBB 28, NoCSMCollision 29
 * +0x04 u32 bitfield1  TakeDamage 0 (기본 1), NoIK 1, NoDecals 2, DarkSurface 3, NoWallHang 4, CamRelControl 5, NoLadderSliding 6, NoBackJumps 7, NoShadows 8
 * +0x08 / +0x0C u32 bitfield2 / bitfield3 (빈 칸)
 * +0x10 u32 doubleSided
 * +0x14 s32 SheetFX   (최대 999) → TWK /TweakTemplates/MFX/&lt;번호&gt;/ (Foot Sound 등: 20 Rock, 30 Wood, 35 Splash, 50 Metal, 92 Flesh …)
 * +0x18 s32 PartType  (기본 8, 최대 12)
 * +0x1C f32 WallHangTimer (기본 60)
 * +0x20 s32 Damage / +0x24 s32 AIDamage (최대 1024)
 * </pre>
 * 예: ATHN01D {@code visuals01D_Climbable} = Climbable|TakeDamage|NoBackJumps, SheetFX 73.
 *
 * @param attrs {@link SurfaceAttr} 비트마스크
 */
public record SheetSurface(String name, long attrs, int sheetFx, int partType, float wallHangTimer, int damage, int aiDamage) {

	public boolean has(SurfaceAttr a) {
		return (attrs & a.bit()) != 0;
	}

	@Override
	public String toString() {
		return name + "[" + SurfaceAttr.names(attrs) + (sheetFx != 0 ? " fx" + sheetFx : "") + "]";
	}
}
