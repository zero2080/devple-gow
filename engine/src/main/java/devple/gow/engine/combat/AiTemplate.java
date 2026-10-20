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

	/**
	 * +0x38 이동 능력 비트 (0x18BC30.. 등록, 0x1D08B0 이 owner+0x2DC 로 복사 — 영웅·적 공용 내비게이션이 본다) [확실].
	 * 이름은 TWK 해시 대조: Climb 1c2da9b5, Balance fad969dc, Fly 00117053, Swim 0a3fba28, Dive 08677814, DiveDash abfacea6,
	 * Rope 0a1e8412, Pole 09dfff18, Zipline 087bc8d5, LadderSlide 974039af / RopeSlide 82c42e3f, WallPullup 7aa8082a, Jump 0925f0ae.
	 */
	public static final int CAP_CLIMB = 0x1, CAP_BALANCE = 0x2, CAP_FLY = 0x8, CAP_SWIM = 0x10, CAP_DIVE = 0x20, CAP_40 = 0x40,
			CAP_ROPE = 0x100, CAP_POLE = 0x200, CAP_ZIPLINE = 0x400, CAP_800 = 0x800, CAP_SLIDE = 0x4000, CAP_USE = 0x10000,
			CAP_WALL_PULLUP = 0x20000, CAP_JUMP = 0x100000;
	/** 줄 잡기 시도 조건 (0x1EC54C: 0x100|0x200|0x400|0x800 중 하나 → 0x1C5C20). */
	public static final int CAP_ANY_ROPE = CAP_ROPE | CAP_POLE | CAP_ZIPLINE | CAP_800;

	public final int id;
	public final String node;
	/** +0x38 이동 능력 ({@link #CAP_CLIMB} 등). 해골 -C (021/023/026/028/030) = Climb·Rope·Pole·Zipline·0x800·0x40·RopeSlide·Jump (021 은 WallPullup 도). */
	public final int moveCaps;
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
	/** 0x48 (400232ce): 경보 반경 (×16) — 인지한 적이 이 안의 개체에 메시지 1000(대상) 을 보냄 (0x1D1AD8 → 0x1D1A70). */
	public final float alertRadius;
	/** +0x68 / +0x64 이동 속도 % (0x1D0044: 평상 / 특수 상태). */
	public final float speedPct, speedPctAlt;
	/** +0x58 (×16) 대상 추적 한계 거리 (0x1D0948) [추정]. */
	public final float leashRange;
	/** +0x60 ScaleCenter / +0x5C ScaleRange (%): 모델 크기 = C/100 + R/100 × (0.5 − rand) (0x1CEE00). */
	public final float scaleCenter, scaleRange;
	/** +0x8A (a2c359e9) ms: 벽에서 더 갈 수 없을 때 점프 버튼을 누르기까지의 시간 (0x1CF894 → sub_001CF1C8) [확실: 값 1000 / 추정: 조건]. */
	public final int pauseMoveMs;
	/** Circle Health Threshold (65535 = 없음). */
	public final int circleHp;

	public AiTemplate(Tweaks tw, int id) {
		this(tw, id, AiOverrides.NONE);
	}

	/** 모드 덮어쓰기 ({@code ai.properties} 의 tmpl.&lt;필드&gt;, 49 문서 §13) 를 얹어 읽는다. */
	public AiTemplate(Tweaks tw, int id, AiOverrides o) {
		this.id = id;
		this.node = String.format("/TweakTemplates/AI1/%03d/", id);
		soldier = o.i("tmpl.soldier", i32(tw, 0x68503EDC, id));
		decisionTree = o.i("tmpl.decisionTree", i32(tw, 0xC086E0B4, -1));
		blockMod = o.i("tmpl.blockMod", s16(tw, 0xE9B40E3D, -1));
		hitPoints = o.i("tmpl.hitPoints", i32(tw, 0xEC2E4776, 100));
		maxHitPoints = o.i("tmpl.maxHitPoints", i32(tw, 0x5938A592, hitPoints));
		damageMult = o.f("tmpl.damageMult", f(tw, 0x4AD50B52, 1f));
		mode = o.i("tmpl.mode", s16(tw, 0xD2AF2A3B, 0));
		tokenRadius = o.i("tmpl.tokenRadius", u16(tw, 0xB9FFCBE2, 4));
		waitRadius = o.i("tmpl.waitRadius", u16(tw, 0x758CABEE, 10));
		flags = o.i("tmpl.flags", bit(tw, 0x7EA1EC2E, 0x1) | bit(tw, 0xC1C01F59, 0x4) | bit(tw, 0xBBBC439A, 0x8) | bit(tw, 0xDBB472C3, 0x2)
				| bit(tw, 0xFC037268, 0x40) | bit(tw, 0xCB247958, 0x100) | bit(tw, 0x55637E92, 0x80)
				| bit(tw, 0xF17492AE, 0x200) | bit(tw, 0x500A656C, 0x400) | bit(tw, 0x8EB6AF1B, 0x10)
				| bit(tw, 0xFF0DAF8F, 0x800) | bit(tw, 0x918DE416, 0x1000));
		moveCaps = o.i("tmpl.moveCaps", bit(tw, 0x1C2DA9B5, CAP_CLIMB) | bit(tw, 0xFAD969DC, CAP_BALANCE) | bit(tw, 0x00117053, CAP_FLY)
				| bit(tw, 0x0A3FBA28, CAP_SWIM) | bit(tw, 0x08677814, CAP_DIVE) | bit(tw, 0xABFACEA6, CAP_DIVE)
				| bit(tw, 0x0925F0AE, CAP_JUMP) | bit(tw, 0x112AF47F, CAP_40) | bit(tw, 0x0A1E8412, CAP_ROPE)
				| bit(tw, 0x09DFFF18, CAP_POLE) | bit(tw, 0x087BC8D5, CAP_ZIPLINE) | bit(tw, 0x27F351D1, CAP_800)
				| bit(tw, 0x974039AF, CAP_SLIDE) | bit(tw, 0x82C42E3F, CAP_SLIDE) | bit(tw, 0xFF971F92, CAP_USE)
				| bit(tw, 0x7AA8082A, CAP_WALL_PULLUP) | bit(tw, 0x4ED25AC3, 0x40000) | bit(tw, 0x8CA8E85C, 0x400000));
		pauseMoveMs = o.i("tmpl.pauseMoveMs", u16(tw, 0xA2C359E9, 1000));
		block = o.f("tmpl.block", f(tw, 0x0CADA0CD, 0f));
		block2 = o.f("tmpl.block2", f(tw, 0xA7082DB6, 0f));
		dwellMs = o.i("tmpl.dwellMs", u16(tw, 0xA3C5272C, 2000));
		repickTokenMs = o.i("tmpl.repickTokenMs", u16(tw, 0xB36FF1C2, 2000));
		repickWaitMs = o.i("tmpl.repickWaitMs", u16(tw, 0x41E08A53, 5000));
		anyAttackDist = o.f("tmpl.anyAttackDist", f(tw, 0xC3A12B87, -1f));
		minRepickDist = o.f("tmpl.minRepickDist", f(tw, 0x2C2B1E13, 4f));
		sightRange = o.f("tmpl.sightRange", f(tw, 0x4672BFB2, 80f));
		alertRadius = o.f("tmpl.alertRadius", f(tw, 0x400232CE, 10f));
		sightFov = o.f("tmpl.sightFov", f(tw, 0xD37B884C, 180f));
		speedPct = o.f("tmpl.speedPct", f(tw, 0x72949419, 100f));
		speedPctAlt = o.f("tmpl.speedPctAlt", f(tw, 0xCD7DA53A, 50f));
		leashRange = o.f("tmpl.leashRange", f(tw, 0x4949C244, 80f));
		scaleCenter = o.f("tmpl.scaleCenter", f(tw, 0x884F26FF, 100f));
		scaleRange = o.f("tmpl.scaleRange", f(tw, 0xCCF3A893, 0f));
		circleHp = o.i("tmpl.circleHp", tw.i(node, "Circle Health Threshold", 65535) & 0xFFFF);
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
