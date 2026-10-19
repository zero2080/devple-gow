package devple.gow.tools.level;

import java.nio.ByteBuffer;

/**
 * 줄 오브젝트 설정 {@code SCR_RopeSkinned} (GO 그룹 안 72 바이트 SCR 리소스, 실행 파일 등록 0x21D328). 줄의 관절 사슬(최대 163 관절)은
 * 오브젝트 스켈레톤, 물리 상수는 TWK {@code /TweakTemplates/Rope/<template>/}.
 * <pre>
 * 0x00 u32 0x10004  0x04 "SCR_RopeSkinned"   0x1C u32 0x13880000
 * 0x20 u32 kind (18, PAND04G 0x10012)
 * 0x24 u32 template  0 Rope · 1 Chain · 2 RopeEastWest · 3 ZipLine · 4 RopeNorthSouth   (전 레벨 TWK Instance Name 일치)
 * 0x28 f32 줄 길이 (미터, 4..90) — 실행 파일 0x2170C0 이 시뮬레이션 길이 +0x60 으로 읽음 (매단 줄은 관절 사슬 길이 / 16 과 같음) [확실]. 전엔 "각도" [추정] 이었음
 * 0x2C u32[4] 플래그  f1 = 끝점 고정 (끝점이 의미 있음), f2 = 짚라인(미끄럼), f3 = 짧은 사슬 (관절 3..5)
 * 0x3C vec3 끝점 (미터, ×16 = 월드 유닛). 예 ATHN01D gorope3: 돛대 꼭대기 (-994, 1201, -1762) → (-2656, 788, -593) = ATHN01E 갑판 (시작 지점 옆)
 * </pre>
 * 분류 (전 레벨 27 개): f2 → 짚라인 (ATHN01D/01E, PAND03E, PAND04E), f1 만 → 양끝 고정 수평 줄 (ATHN02B, PAND01D, PAND03D, PAND04C),
 * 그 밖 → 매단 줄 (오르기/흔들기).
 */
public record RopeDef(String owner, int kind, int template, float lengthM, int[] flags, float[] end) {

	public enum Type { HANG, TRAVERSE, ZIPLINE }

	public static final float UNITS_PER_METER = 16f;

	public static boolean is(String name, ByteBuffer d) {
		return name.trim().equals("SCR_RopeSkinned") && d.limit() == 72 && d.getInt(0) == 0x10004;
	}

	public static RopeDef parse(String owner, ByteBuffer d) {
		int[] f = {d.getInt(0x2C), d.getInt(0x30), d.getInt(0x34), d.getInt(0x38)};
		float[] e = {d.getFloat(0x3C) * UNITS_PER_METER, d.getFloat(0x40) * UNITS_PER_METER, d.getFloat(0x44) * UNITS_PER_METER};
		return new RopeDef(owner, d.getInt(0x20), d.getInt(0x24), d.getFloat(0x28), f, e);
	}

	public Type type() {
		if (flags[2] != 0) {
			return Type.ZIPLINE;
		}
		return flags[1] != 0 ? Type.TRAVERSE : Type.HANG;
	}
}
