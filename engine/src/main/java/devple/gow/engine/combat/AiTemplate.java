package devple.gow.engine.combat;

import devple.gow.engine.game.Tweaks;

import java.util.OptionalInt;

/**
 * AI1 템플릿 ({@code /TweakTemplates/AI1/NNN/}). 등록 함수 AI_RegisterTweaks(0x18B650) 가 정한 형/오프셋 그대로 읽는다
 * (17 문서 §2). 0x20 TWK 레코드는 항상 4바이트지만 short/byte 필드는 하위 비트만 유효하다.
 * <p>
 * 거리 단위: 템플릿·결정 트리의 거리는 "×16" 단위(예 R_wait 10 → 160 월드 단위)이고, 결정 트리 공격 거리는 그 1/100.
 */
public final class AiTemplate {

	/** +0x84 플래그 비트 (해시 cb247958): 공격 토큰 사용. 0x1CEF04 에서 시작 시 "토큰 없음" 비트를 세운다. */
	public static final int FLAG_USE_TOKEN = 0x100;
	/** 0x84 &amp; 0x800 (ff0daf8f): 인지 시 시선 가림 검사 생략 (0x1D2564). */
	public static final int FLAG_IGNORE_LOS = 0x800;

	public final int id;
	public final String node;
	/** +0x28 SoldierTemplate (TWK_Sold_NNN). */
	public final int soldier;
	/** +0x3C 결정 트리 번호 (TWK_DTre_NNN, -1 없음). */
	public final int decisionTree;
	/** +0x26 BlockMod 번호 (-1 없음). */
	public final int blockMod;
	public final int hitPoints, maxHitPoints;
	public final float damageMult;
	/** +0x44 AI Mode (0 보병, 1 직접 공격, 2 궁수, 3/4 변형, 5 무반응). 0/3/4 만 토큰·포위 이동 사용 (0x1D1438). */
	public final int mode;
	/** +0x94 / +0x96 (×16): 토큰 보유 시 포위 반경 / 대기 반경 (0x1CE4B0). +0x96 은 토큰 획득 거리(×1.1)·반납 거리(×1.5)도 겸함. */
	public final int tokenRadius, waitRadius;
	/** +0x84 플래그 비트들. */
	public final int flags;
	/** +0x70 "Block" 방어 확률 % (0x1CC570), +0x6C 보조 방어 확률 % (0x1CC674, 호출 경로 미상). */
	public final float block, block2;
	/** +0x20 칸 대기(ms) 후 공격 시도, +0x22 칸 선택 후 경과(ms) 공격 시도·토큰 보유 재배치, +0x24 토큰 없을 때 재배치 (0x1CDA68). */
	public final int dwellMs, repickTokenMs, repickWaitMs;
	/** +0x74 (×16) 이 거리 안이면 대기 시간과 무관하게 공격 시도 (-1 = 없음). */
	public final float anyAttackDist;
	/** +0x78 (×16) 대상과 이 거리보다 멀어야 재배치 (0x1D3CC0). */
	public final float minRepickDist;
	/** +0x4C 시야 거리(×16) / +0x50 시야각(도) (0x1D22B0). */
	public final float sightRange, sightFov;
	/** +0x68 / +0x64 이동 속도 % (0x1D0044: 평상 / 특수 상태). */
	public final float speedPct, speedPctAlt;
	/** +0x58 (×16) 대상 추적 한계 거리 (0x1D0948) [추정]. */
	public final float leashRange;
	/** +0x60 ScaleCenter / +0x5C ScaleRange (%): 모델 크기 = C/100 + R/100 × (0.5 − rand) (0x1CEE00). */
	public final float scaleCenter, scaleRange;
	/** Circle Health Threshold (65535 = 없음). */
	public final int circleHp;

	public AiTemplate(Tweaks tw, int id) {
		this.id = id;
		this.node = String.format("/TweakTemplates/AI1/%03d/", id);
		soldier = i32(tw, 0x68503EDC, id);
		decisionTree = i32(tw, 0xC086E0B4, -1);
		blockMod = s16(tw, 0xE9B40E3D, -1);
		hitPoints = i32(tw, 0xEC2E4776, 100);
		maxHitPoints = i32(tw, 0x5938A592, hitPoints);
		damageMult = f(tw, 0x4AD50B52, 1f);
		mode = s16(tw, 0xD2AF2A3B, 0);
		tokenRadius = u16(tw, 0xB9FFCBE2, 4);
		waitRadius = u16(tw, 0x758CABEE, 10);
		flags = bit(tw, 0x7EA1EC2E, 0x1) | bit(tw, 0xC1C01F59, 0x4) | bit(tw, 0xBBBC439A, 0x8) | bit(tw, 0xDBB472C3, 0x2)
				| bit(tw, 0xFC037268, 0x40) | bit(tw, 0xCB247958, 0x100) | bit(tw, 0x55637E92, 0x80)
				| bit(tw, 0xF17492AE, 0x200) | bit(tw, 0x500A656C, 0x400) | bit(tw, 0x8EB6AF1B, 0x10)
				| bit(tw, 0xFF0DAF8F, 0x800) | bit(tw, 0x918DE416, 0x1000);
		block = f(tw, 0x0CADA0CD, 0f);
		block2 = f(tw, 0xA7082DB6, 0f);
		dwellMs = u16(tw, 0xA3C5272C, 2000);
		repickTokenMs = u16(tw, 0xB36FF1C2, 2000);
		repickWaitMs = u16(tw, 0x41E08A53, 5000);
		anyAttackDist = f(tw, 0xC3A12B87, -1f);
		minRepickDist = f(tw, 0x2C2B1E13, 4f);
		sightRange = f(tw, 0x4672BFB2, 80f);
		sightFov = f(tw, 0xD37B884C, 180f);
		speedPct = f(tw, 0x72949419, 100f);
		speedPctAlt = f(tw, 0xCD7DA53A, 50f);
		leashRange = f(tw, 0x4949C244, 80f);
		scaleCenter = f(tw, 0x884F26FF, 100f);
		scaleRange = f(tw, 0xCCF3A893, 0f);
		circleHp = tw.i(node, "Circle Health Threshold", 65535) & 0xFFFF;
	}

	public boolean usesToken() {
		return (flags & FLAG_USE_TOKEN) != 0;
	}

	/** 토큰·포위 이동을 쓰는 모드 (0x1D1438: 0, 3, 4). */
	public boolean groupMode() {
		return mode == 0 || mode == 3 || mode == 4;
	}

	private OptionalInt raw(Tweaks tw, int hash) {
		return tw.rawHash(node, hash);
	}

	private int i32(Tweaks tw, int hash, int def) {
		OptionalInt r = raw(tw, hash);
		return r.isPresent() ? r.getAsInt() : def;
	}

	private int s16(Tweaks tw, int hash, int def) {
		OptionalInt r = raw(tw, hash);
		return r.isPresent() ? (short) r.getAsInt() : def;
	}

	private int u16(Tweaks tw, int hash, int def) {
		OptionalInt r = raw(tw, hash);
		return r.isPresent() ? r.getAsInt() & 0xFFFF : def;
	}

	private float f(Tweaks tw, int hash, float def) {
		OptionalInt r = raw(tw, hash);
		return r.isPresent() ? Float.intBitsToFloat(r.getAsInt()) : def;
	}

	/** bool 은 하위 바이트만 유효 (0x1820C0 → 0x17E1B8). */
	private int bit(Tweaks tw, int hash, int mask) {
		OptionalInt r = raw(tw, hash);
		return r.isPresent() && (r.getAsInt() & 0xFF) != 0 ? mask : 0;
	}
}
