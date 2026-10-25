package devple.gow.engine.nav;

import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 내비게이션 수치 출처: TWK_Sold 구조체 (태그 0x72, 이름 뒤 오프셋) 중 영웅·적 공용 내비게이션 코드 (0x1EBD78 계열) 가 읽는 필드.
 * 원작은 (owner+0x320)+8 → Sold 로 읽고, 단위는 미터(초) 이며 월드 = ×16 (38 문서 §2.2).
 * 크라토스 = R_PERMK {@code TWK_Sold_000}. 값은 {@link #KRATOS} 에 박아 두고 데이터 테스트가 원본과 대조한다.
 * <pre>
 * 오프셋 (Sold_000 값)  읽는 곳            뜻
 * 0xC4  15.25  0x1E9E88 / 0x1E99A0     점프 수직 초속 (현재 수직 속도에 더함)                 [확실]
 * 0xC8  15.25  0x1EA19C                2단 점프: 수직 속도 = 이 값                           [확실]
 * 0xCC  20.009 0x1EA144                2단 점프 허용: 수직 속도 &lt; 이 값                     [확실]
 * 0xD0  -100   0x1EA160                2단 점프 허용: 수직 속도 &gt; 이 값                     [확실]
 * 0xD8  7.5    0x1EB6F4                공중 수평 가속 = 감속 (m/s²)                           [확실]
 * 0xDC  50     0x1EBC4C                최대 낙하 속도                                       [확실]
 * 0xE4  0.05   0x1EB6DC                공중 회전 비율 (60 Hz 한 프레임당, 0x1E60E8)           [추정: 회전 보간]
 * 0xEC  7.5    0x1EB790                공중 목표 수평 속도 (× 스틱)                           [확실]
 * 0x104 1      0x1C8A24 / 0x1C7810     턱 매달리기 (+0x174 0x10) 좌우 속도                     [확실]
 * 0x108 0.5    0x1C8DB0                벽 오르기 최소 속도                                   [확실]
 * 0x10C 1.5    0x1C8D34                벽 오르기 좌우 속도                                   [확실]
 * 0x110 2      0x1C8D4C / 0x1C75E4     벽 오르기 상하 속도 (= 최대)                          [확실]
 * 0x118 17     0x1E9E5C                벽에서 등 뒤 점프 수직 속도                            [확실]
 * 0x11C 10     0x1C8E24                벽에서 떨어지는 속도 (등 뒤 점프 수평 속도)            [확실]
 * 0x124 2      0x1C8C24 / 0x1C7544     사다리 (+0x174 0x400000) 상하 속도                     [확실]
 * 0x128 0.9    0x1C8AD4                사다리 미끄럼 시작 스틱 (아래 &gt; 0.9)                  [확실]
 * 0x12C 10     0x1C8B58                사다리 미끄럼 최대 속도                                [확실]
 * 0x130 20     0x1C8B54                사다리 미끄럼 가속                                    [확실]
 * 0x134 40     0x1C8B48                사다리 미끄럼 멈춤 감속                                [확실]
 * 0x138 1.5    0x1C6578                매단 줄 오르내리기 속도 (× 스틱)                        [확실]
 * 0x13C 0.5    0x1C657C                매단 줄 최소 속도                                    [확실]
 * 0x140 14     0x1C55F4                매단 줄 × 뛰어내리기 위 속도 (지역 +0x260.y)               [확실]
 * 0x144 14     0x1C561C                매단 줄 × 뛰어내리기 앞 속도 (지역 +0x260.z, 줄 축 4 방향 중 스틱 쪽) [확실]
 * 0x148 500    0x1C6BE8                매단 줄 흔들기 힘 (줄 질점 시뮬레이션)                  [추정: 엔진은 진자 근사]
 * 0x14C 7.5    0x1C64D8                줄 미끄럼 최대 속도                                   [확실]
 * 0x150 20     0x1C64D4                줄 미끄럼 가속                                       [확실]
 * 0x154 20     0x1C6518                줄 미끄럼 멈춤 감속                                   [확실]
 * 0x158 2.5    0x1C6370                수평 줄 (+0x174 0x20000) 이동 속도 (× 스틱)              [확실]
 * 0x15C 1.25   0x1C6374                수평 줄 최소 속도                                    [확실]
 * 0x160 20     0x1C6138                짚라인 (+0x174 0x40000) 목표 속도                       [확실]
 * 0x164 10     0x1C6134                짚라인 가속                                         [확실]
 * 0x168 1.25   0x1C8CBC / 0x1C7484     좁은 턱 벽 따라 걷기 (WallFront/Back) 속도              [확실]
 * 0x178 10     0x1EAFF0                지상 목표 속도 (× 스틱)                               [확실: 엔진 영웅은 애니 이동량]
 * 0x17C 0.5 ×3 0x1C4518                붙은 오브젝트 밀기 최소 속도 [무게 0/1/2]               [확실]
 * 0x188 1.9 / 1.5 / 1.2  0x1C453C      붙은 오브젝트 밀기 최대 속도 [무게 0/1/2] (× 스틱)      [확실]
 * 0x194 0 ×3, 0x1A0 24 / 16 / 9  0x1C4A08  상자 차기 속도 lerp(최소, 최대, 충전 / 0x1AC) [무게]  [확실]
 * 0x1AC 2      0x1C49A8                상자 차기 최대 충전 시간                               [확실]
 * 0x1D4 7.5    0x1CA7F8                수영 (+0x174 0x100, ANISwim) 목표 속도                  [확실]
 * 0x1D8 4.5    0x1CA7F0                물 위 떠 있기 (0x80, ANITread) 목표 속도                [확실]
 * 0x1DC 1      0x1CA80C                수영 최소 속도                                       [확실]
 * 0x1E0 7.5    0x1CA7D4                수영 가속                                           [확실]
 * 0x1E4 7.5    0x1CA7D0                수영 감속                                           [확실]
 * 0x1E8 0.15   0x1CA62C                수영 회전 비율 (프레임당)                              [확실: 값 / 추정: 0x183D68 각도 보간]
 * 0x1EC 0.2    0x1C9C00                물 판정 높이 = 키 × (1 − 이 값) − 바닥 (내비 위치 기준 21.76)  [확실: 0x1E57D8 수면 맞춤]
 * 0x1F0 15     0x1CAC68                물 위·가라앉음 (0x80/0x100) 부력 (m/s², 월드 수직 속도에 더함)  [확실]
 * 0x1F8 0.5 / 0x1FC 1   0x1C9F10       잠수 돌진 (R1) 충전 시간 하한 / 상한 (초)                 [확실]
 * 0x200 11 / 0x204 22   0x1C9F24       잠수 돌진 속도 lerp (m/s)                               [확실]
 * 0x208 0.01 / 0x20C 0.1  0x1C9F2C     잠수 돌진 조종 시간 lerp (+0x2B8 = 이 값 + 1, ≥ 1 동안 상하 조종)  [확실]
 * 0x214 18     0x1DE63C                부서지는 물체 (Breakable) 돌진 파괴 속도 — 접촉 0x1DE4D8: 공격 없이 돌진 중 (+0x2D8 0x80000) |속도| / 16 &gt; 이 값이면 1000 피해  [확실]
 * 0x114 10 / 0x120 12   0x1E9CD0       벽 옆 끝 (+0x2D8 0x100/0x200) × 옆 점프: 정면 / 위 속도 (정면을 ±90° 돌림)  [확실]
 * 0x22C 1      0x1C7678 / 0x1C76E4     평균대 매달림 (턱 윗면 Narrow·NarrowNoBB, +0x2D8 0x40) 시간 → +0x2A4 (0 이면 무한) [확실]
 * 0x230 1      0x1EAFB0 / 0x1EB578     +0x2D8 비트 4 (좁은 길) 일 때 지상 속도 배율             [확실: 값 / 추정: 비트 뜻]
 * 0x234 0.6    0x1E6D74                몸 상자 반폭 (상자 폭 = ×2)                           [확실: 0x1E6D58]
 * 0x238 2.2    0x1E6D80                몸 키 (+0x120.y) — 줄 잡기 점·줄 손 높이                [확실]
 * 0x23C 0.4    0x1E6D78                몸 상자 바닥 높이 (+0x130.y)                          [확실: 값 / 추정: 뜻]
 * 그 밖: /GlobGame/ "Gravity" 50 (0x1BF2B0 등록, 0x1EBC0C 공중 중력 · 0x211B34 밀기 상자), 줄 잡기 반경 32 (0x1C5CF0),
 * R1 오브젝트 반경 32 (0x1C3F18), 줄 TWK /TweakTemplates/Rope/n/ "Gravity" 9.82 (줄 시뮬레이션).
 * </pre>
 */
public final class NavTuning {

	/** 1 m = 16 유닛. */
	public static final float UNITS_PER_METER = 16f;
	/** /GlobGame/ "Gravity" (m/s²) [확실: 0x1BF2B0 Tweak_AddFloat → 0x337718, 기본값도 50 (0x1BF344)]. */
	public static final float GLOBAL_GRAVITY = 50f;

	public static final int JUMP = 0xC4, DOUBLE_JUMP = 0xC8, DOUBLE_MAX_VY = 0xCC, DOUBLE_MIN_VY = 0xD0, AIR_ACCEL = 0xD8, FALL_MAX = 0xDC,
			AIR_TURN = 0xE4, AIR_SPEED = 0xEC, HANG_SIDE = 0x104, WALL_MIN = 0x108, WALL_SIDE = 0x10C, WALL_UP = 0x110, BACK_JUMP_UP = 0x118,
			WALL_AWAY = 0x11C, LADDER = 0x124, LADDER_SLIDE_STICK = 0x128, LADDER_SLIDE_MAX = 0x12C, LADDER_SLIDE_ACC = 0x130,
			LADDER_SLIDE_DEC = 0x134, ROPE_CLIMB = 0x138, ROPE_CLIMB_MIN = 0x13C, ROPE_SWING_FORCE = 0x148, ROPE_SLIDE_MAX = 0x14C,
			ROPE_SLIDE_ACC = 0x150, ROPE_SLIDE_DEC = 0x154, TRAVERSE = 0x158, TRAVERSE_MIN = 0x15C, ZIP_SPEED = 0x160, ZIP_ACCEL = 0x164,
			LEDGE_WALK = 0x168, GROUND = 0x178, PUSH_MIN = 0x17C, PUSH_MAX = 0x188, KICK_MIN = 0x194, KICK_MAX = 0x1A0, KICK_CHARGE = 0x1AC, SWIM = 0x1D4, TREAD = 0x1D8, SWIM_MIN = 0x1DC, SWIM_ACCEL = 0x1E0, SWIM_DECEL = 0x1E4,
			SWIM_TURN = 0x1E8, WATER_LINE = 0x1EC, BUOYANCY = 0x1F0, DASH_CHARGE_MIN = 0x1F8, DASH_CHARGE_MAX = 0x1FC,
			DASH_MIN = 0x200, DASH_MAX = 0x204, DASH_STEER_MIN = 0x208, DASH_STEER_MAX = 0x20C, DASH_BREAK = 0x214, EDGE_JUMP_OUT = 0x114, EDGE_JUMP_UP = 0x120, BALANCE_HANG = 0x22C, NARROW_SCALE = 0x230, BODY_HALF = 0x234, BODY_HEIGHT = 0x238, BODY_BASE = 0x23C,
			ROPE_JUMP_UP = 0x140, ROPE_JUMP_OUT = 0x144;

	/** R_PERMK TWK_Sold_000 (크라토스) — 데이터 테스트가 원본과 대조. */
	public static final NavTuning KRATOS = new NavTuning(Map.ofEntries(
			Map.entry(JUMP, 15.25f), Map.entry(DOUBLE_JUMP, 15.25f), Map.entry(DOUBLE_MAX_VY, 20.0088f), Map.entry(DOUBLE_MIN_VY, -100f),
			Map.entry(AIR_ACCEL, 7.5f), Map.entry(FALL_MAX, 50f), Map.entry(AIR_TURN, 0.05f), Map.entry(AIR_SPEED, 7.5f),
			Map.entry(HANG_SIDE, 1f), Map.entry(WALL_MIN, 0.5f), Map.entry(WALL_SIDE, 1.5f), Map.entry(WALL_UP, 2f),
			Map.entry(BACK_JUMP_UP, 17f), Map.entry(WALL_AWAY, 10f), Map.entry(LADDER, 2f), Map.entry(LADDER_SLIDE_STICK, 0.9f),
			Map.entry(LADDER_SLIDE_MAX, 10f), Map.entry(LADDER_SLIDE_ACC, 20f), Map.entry(LADDER_SLIDE_DEC, 40f),
			Map.entry(ROPE_CLIMB, 1.5f), Map.entry(ROPE_CLIMB_MIN, 0.5f), Map.entry(ROPE_SWING_FORCE, 500f),
			Map.entry(ROPE_SLIDE_MAX, 7.5f), Map.entry(ROPE_SLIDE_ACC, 20f), Map.entry(ROPE_SLIDE_DEC, 20f),
			Map.entry(TRAVERSE, 2.5f), Map.entry(TRAVERSE_MIN, 1.25f), Map.entry(ZIP_SPEED, 20f), Map.entry(ZIP_ACCEL, 10f),
			Map.entry(LEDGE_WALK, 1.25f), Map.entry(GROUND, 10f),
			Map.entry(PUSH_MIN, 0.5f), Map.entry(PUSH_MIN + 4, 0.5f), Map.entry(PUSH_MIN + 8, 0.5f),
			Map.entry(PUSH_MAX, 1.9f), Map.entry(PUSH_MAX + 4, 1.5f), Map.entry(PUSH_MAX + 8, 1.2f),
			Map.entry(KICK_MIN, 0f), Map.entry(KICK_MIN + 4, 0f), Map.entry(KICK_MIN + 8, 0f),
			Map.entry(KICK_MAX, 24f), Map.entry(KICK_MAX + 4, 16f), Map.entry(KICK_MAX + 8, 9f), Map.entry(KICK_CHARGE, 2f), Map.entry(SWIM, 7.5f), Map.entry(TREAD, 4.5f), Map.entry(SWIM_MIN, 1f),
			Map.entry(SWIM_ACCEL, 7.5f), Map.entry(SWIM_DECEL, 7.5f), Map.entry(SWIM_TURN, 0.15f), Map.entry(WATER_LINE, 0.2f),
			Map.entry(BUOYANCY, 15f), Map.entry(DASH_CHARGE_MIN, 0.5f), Map.entry(DASH_CHARGE_MAX, 1f), Map.entry(DASH_MIN, 11f), Map.entry(DASH_MAX, 22f),
			Map.entry(DASH_STEER_MIN, 0.01f), Map.entry(DASH_STEER_MAX, 0.1f), Map.entry(DASH_BREAK, 18f), Map.entry(EDGE_JUMP_OUT, 10f), Map.entry(EDGE_JUMP_UP, 12f),
			Map.entry(BALANCE_HANG, 1f), Map.entry(NARROW_SCALE, 1f), Map.entry(BODY_HALF, 0.6f), Map.entry(BODY_HEIGHT, 2.2f), Map.entry(BODY_BASE, 0.4f),
			Map.entry(ROPE_JUMP_UP, 14f), Map.entry(ROPE_JUMP_OUT, 14f)));

	private final Map<Integer, Float> values;

	private NavTuning(Map<Integer, Float> values) {
		this.values = new LinkedHashMap<>(values);
	}

	/** 필드 값 (미터 단위 그대로). */
	public float get(int offset) {
		Float v = values.get(offset);
		if (v == null) {
			throw new IllegalArgumentException(String.format("Sold +0x%X 미등록", offset));
		}
		return v;
	}

	/** 필드 × 16 (월드 유닛). */
	public float units(int offset) {
		return get(offset) * UNITS_PER_METER;
	}

	/** 등록된 오프셋 (KRATOS 와 같은 집합). */
	public java.util.Set<Integer> offsets() {
		return values.keySet();
	}

	/** TWK_Sold 태그 데이터 (u32 1, 경로 문자열 NUL, 구조체) 에서 {@link #KRATOS} 와 같은 필드를 읽는다. */
	public static NavTuning fromSold(ByteBuffer src) {
		ByteBuffer b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int p = 4;
		while (p < b.limit() && b.get(p) != 0) {
			p++;
		}
		int base = p + 1;
		Map<Integer, Float> m = new LinkedHashMap<>();
		for (int off : KRATOS.offsets()) {
			m.put(off, b.getFloat(base + off));
		}
		return new NavTuning(m);
	}

	/** WAD 의 TWK_Sold_NNN (없으면 null). */
	public static NavTuning fromWad(WadFile w, int soldier) {
		String name = String.format("TWK_Sold_%03d", soldier);
		for (WadTag t : w.tags()) {
			if (t.hasData() && t.tag() == WadTagType.TWEAK_REF && name.equals(t.name())) {
				return fromSold(t.data());
			}
		}
		return null;
	}
}
