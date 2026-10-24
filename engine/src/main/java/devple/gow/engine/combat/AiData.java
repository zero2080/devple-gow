package devple.gow.engine.combat;

import devple.gow.engine.game.Tweaks;
import devple.gow.tools.tweak.TweakFile;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * 적 AI 데이터 묶음: 결정 트리(TWK_DTre), 병사 구조체(TWK_Sold), BlockMod 템플릿. 17 문서 §2–§5.
 */
public final class AiData {

	/** "DecisionTree" 키 해시. */
	static final int DECISION_TREE = TweakFile.hash("DecisionTree");

	/**
	 * TWK_Sold_NNN (태그 0x72 고정 구조체) 중 공격 토큰 관련 필드 (0x1CE078, 0x200130, 0x2000C0, 0x2000F8).
	 *
	 * @param cost     +0x244 이 병사가 토큰을 쥘 때 대상 예산에 더하는 값 (해골 4, 미노타우르스 2, 사이클롭스 0)
	 * @param capacity +0x246 이 병사가 "대상"일 때 동시에 허용하는 토큰 예산 (크라토스 12)
	 * @param capA     +0x248 대상일 때 공격 슬롯 A 용량 (크라토스 10) — 결정 트리 공격 비용 A 의 합
	 * @param capB     +0x24A 공격 슬롯 B 용량 (크라토스 10)
	 */
	public record Soldier(int cost, int capacity, int capA, int capB) {
		/** R_PERMK TWK_Sold_000 (크라토스) 값. */
		public static final Soldier HERO = new Soldier(4, 12, 10, 10);
	}

	/**
	 * 석화 관련 Sold 필드 (0x1E8C40, 35 문서 §2).
	 *
	 * @param resist +0x26C u16 동결 게이지 최대치 (미노타우로스 6000, 사이클롭스 20000, ≥ 60000 = 면역)
	 * @param move   +0x270 u16 석화 기술 ID (적 160 freezePose, 영웅 120)
	 * @param statue +0x20 char[24] 석상 GO (goMINStonedBRK) — 석화 기술 진행률 &gt; 0.99 에 소유자 대신 생성 (0x19F798 → 0x1E8960) [확실]
	 * @param freeze +0x38 char[24] 석화 순간 GO (goMINFreeze) — SCR_TurnToRock 인자 진행률에 소유자 행렬로 1회 생성 (0x19F8E0) [확실]
	 */
	public record Stone(int resist, int move, String statue, String freeze) {
		public Stone(int resist, int move) {
			this(resist, move, null, null);
		}
	}

	/**
	 * 이동 속도 Sold 필드 (단위 m/s, 월드 = ×16). 32 문서 §이동 속도. 원작은 (owner+0x320)+8 → Sold 구조체 (sub_00243700) 로 읽는다.
	 *
	 * @param ground    +0x178 지상 목표 속도 = 이 값 × |스틱| (0x1EAFF0, 0x1EAEC8 지상 이동) [확실]. 해골 8, 미노 3, 크라토스 10
	 * @param accel     +0x16C 가속 / {@code decel} +0x170 감속 (m/s², 0x1EAFF8 → sub_001E5FE0 접근) [확실]
	 * @param maxSpeed  +0xEC AI 도착 감속 기준 (0x1D0088: 10 프레임 안에 닿을 거리면 스틱 축소) [확실]
	 * @param minSpeed  +0xF0 막힘 판정 (0x1CBED8: 프레임 이동 &lt; ×0.25 가 AI1 0x9A 프레임 이어지면 막힘) [확실]
	 * @param wallSide  +0x10C 벽 좌우 / {@code wallUp} +0x110 벽 상하 속도, {@code wallMin} +0x108 최소 (0x1C8D34 벽 오르기, 0x1CF5D0 AI) [확실: 필드 / 추정: 축]
	 * @param rope      +0x158 줄 이동 속도 (0x1C6370 줄 갱신) [추정: 의미]
	 */
	public record Motion(float ground, float accel, float decel, float maxSpeed, float minSpeed, float wallMin, float wallSide, float wallUp,
						 float rope) {
		/** Sold 가 없을 때 (해골 Sold_020 값). */
		public static final Motion DEFAULT = new Motion(8, 80, 80, 8, 3, 0.5f, 2.17f, 3.7f, 2);
	}

	/**
	 * 몸 상자 Sold 필드 (단위 m, 월드 = ×16 × 개체 스케일 owner+0x168). 0x1E6D58 이 owner+0x120 상자 (지름, 키, 지름) / +0x130 (0, 바닥, 0) /
	 * +0x140 키 − 바닥 을 만들고, 생물끼리 밀어내기 0x1E2DD8 / 0x1E28B0 이 이 상자로 캡슐을 만든다 (32 문서 §생물끼리 밀어내기) [확실].
	 *
	 * @param half   +0x234 반폭 = 캡슐 반지름 (크라토스 0.6)
	 * @param height +0x238 키 (크라토스 2.2)
	 * @param base   +0x23C 바닥 높이 (크라토스 0.4)
	 * @param joint  +0xA4 관절 이름 (0x1E74F0 → owner+0x352, 상자 관절 0x1E6DF0 — {@link BodyPush.JointBox}). 크라토스 head, BMI·히드라 머리/목,
	 *               일반 적은 빈 문자열 = −1 (루트) [확실]
	 * @param flags  +0x1C 플래그 — 내비게이션 초기화 0x1E75F0 (0x1E77B0–0x1E782C) 가 owner+0x2D8 로 옮긴다: 비트 0 → 0x04000000,
	 *               <b>비트 1 → 0x80000000 = 밀기 끔</b> (이동 단계 0x1EA670 / 0x1ECCD8 이 0x1E2DD8 을 부르지 않음), 비트 4 → 0x40000000 [확실].
	 *               데이터: 일반 적·크라토스 4 (밀기), 히드라 머리 250–253·아기 히드라 252·님프 100·침대 305·악령 크라토스 660 = 비트 1 (32 문서)
	 */
	public record Body(float half, float height, float base, int flags, String joint) {
		/** 크라토스 Sold_000 (데이터 테스트가 대조). */
		public static final Body HERO = new Body(0.6f, 2.2f, 0.4f, 4, "head");

		public Body(float half, float height, float base) {
			this(half, height, base, 4, null);
		}

		/** Sold +0x1C 비트 1 → owner+0x2D8 비트 31: 자기 이동에서 밀어내기를 하지 않는다 (상대에게는 여전히 단단함 — 콜백은 이 비트를 보지 않음). */
		public boolean noPush() {
			return (flags & 2) != 0;
		}
	}

	/**
	 * 줄·짚라인·점프 Sold 필드 (m/s, 38 문서 §2.2 — 영웅과 같은 오프셋).
	 *
	 * @param jump     +0xC4 점프 수직 초속 (0x1E9E88)
	 * @param zipSpeed +0x160 짚라인 목표 속도 / {@code zipAccel} +0x164 가속 (0x1C60F8 → 0x1E5FE0, 스틱 무관 · 낮은 끝으로)
	 * @param climb    +0x138 매단 줄 오르내리기 (0x1C6578, × 스틱 — AI 스틱은 0 이라 쓰지 않음)
	 * @param ropeJumpUp  +0x140 매단 줄 × 뛰어내리기 위 (0x1C55F4, 영웅과 같은 코드 — 전 적 Sold 53 개 모두 14) [확실]
	 * @param ropeJumpOut +0x144 매단 줄 × 뛰어내리기 앞 (0x1C561C) [확실]
	 */
	public record Ride(float jump, float zipSpeed, float zipAccel, float climb, float ropeJumpUp, float ropeJumpOut) {
		/** Sold 가 없을 때 (크라토스 값). */
		public static final Ride DEFAULT = new Ride(15.25f, 20f, 10f, 1.5f, 14f, 14f);

		public Ride(float jump, float zipSpeed, float zipAccel, float climb) {
			this(jump, zipSpeed, zipAccel, climb, 14f, 14f);
		}
	}

	/**
	 * 밀어내기 Sold 필드 ({@link Combatant#setImpulsePhysics}) [확실].
	 *
	 * @param mass      +0x84 질량 (영웅 vslot 0xB4 = 0x2437F8): 크라토스·해골·미노타우로스 100, 사이클롭스·하피·히드라 1000, 200·251 10000
	 * @param friction  +0x88 지상 마찰 계수 (0x1E8F80 → 0x1E0910: 감속 = 계수 × 9.82 × 16): 76 개 3, 3 개 5, 1 개 10
	 * @param immovable +0x1C 비트 3 (0x2438E8 이 속도를 쓰지 않음): 해골 궁수 031/033/035, 님프 100, 히드라 250/252/253, 660
	 */
	public record Physics(float mass, float friction, boolean immovable) {
		/** 크라토스 Sold_000. */
		public static final Physics HERO = new Physics(100f, 3f, false);
	}

	/** BlockMod 항목 하나 (0x19BEF0): 영웅 기술, 최소 횟수, 최소 방어 %, 한계 횟수. */
	public record BlockEntry(int move, int min, float minBlock, int limit) {
	}

	private final Map<Integer, DecisionTree> trees = new HashMap<>();
	private final Map<Integer, Soldier> soldiers = new HashMap<>();
	private final Map<Integer, Stone> stones = new HashMap<>();
	private final Map<Integer, Motion> motions = new HashMap<>();
	private final Map<Integer, Body> bodies = new HashMap<>();
	private final Map<Integer, Physics> physics = new HashMap<>();
	private final Map<Integer, Ride> rides = new HashMap<>();

	public static AiData load(WadFile... wads) {
		AiData d = new AiData();
		for (WadFile w : wads) {
			if (w != null) {
				d.add(w);
			}
		}
		return d;
	}

	public void add(WadFile w) {
		for (WadTag t : w.tags()) {
			if (!t.hasData() || t.name() == null) {
				continue;
			}
			try {
				if (t.tag() == WadTagType.TWEAK && t.name().startsWith("TWK_DTre_")) {
					byte[] blob = stringValue(t.data(), DECISION_TREE);
					if (blob != null && blob.length >= 5) {
						trees.put(Integer.parseInt(t.name().substring(9)), DecisionTree.parse(blob));
					}
				} else if (t.tag() == WadTagType.TWEAK_REF && t.name().startsWith("TWK_Sold_")) {
					int sid = Integer.parseInt(t.name().substring(9));
					soldiers.put(sid, soldier(t.data()));
					Stone st = stone(t.data());
					if (st != null) {
						stones.put(sid, st);
					}
					Motion mo = motion(t.data());
					if (mo != null) {
						motions.put(sid, mo);
					}
					ByteBuffer sb = soldStruct(t.data(), 0x240);
					if (sb != null) {
						physics.put(sid, new Physics(sb.getFloat(0x84), sb.getFloat(0x88), (sb.getInt(0x1C) & 8) != 0));
						bodies.put(sid, new Body(sb.getFloat(0x234), sb.getFloat(0x238), sb.getFloat(0x23C), sb.getInt(0x1C), cstr(sb, 0xA4)));
						rides.put(sid, new Ride(sb.getFloat(0xC4), sb.getFloat(0x160), sb.getFloat(0x164), sb.getFloat(0x138),
								sb.getFloat(0x140), sb.getFloat(0x144)));
					}
				}
			} catch (RuntimeException e) {
				// 이름이 숫자가 아니거나 손상된 블록은 건너뜀
			}
		}
	}

	public DecisionTree tree(int id) {
		return trees.get(id);
	}

	public Map<Integer, DecisionTree> trees() {
		return trees;
	}

	public Soldier soldier(int id) {
		return soldiers.get(id);
	}

	/** 석화 필드 (없으면 null). */
	public Stone stone(int id) {
		return stones.get(id);
	}

	/** 이동 속도 필드 (없으면 null). */
	public Motion motion(int id) {
		return motions.get(id);
	}

	/** 몸 상자 필드 (없으면 null). */
	/** 밀어내기 Sold 필드 (없으면 null). */
	public Physics physics(int id) {
		return physics.get(id);
	}

	public Body body(int id) {
		return bodies.get(id);
	}

	/** 줄·짚라인·점프 필드 (없으면 null). */
	public Ride ride(int id) {
		return rides.get(id);
	}

	private static String cstr(ByteBuffer b, int o) {
		StringBuilder sb = new StringBuilder();
		for (int i = o; i < b.limit() && i < o + 24 && b.get(i) != 0; i++) {
			sb.append((char) b.get(i));
		}
		return sb.isEmpty() ? null : sb.toString();
	}

	/** Sold 구조체 시작 (경로 문자열 NUL 다음) 을 0 으로 둔 버퍼, 크기가 need 보다 작으면 null. */
	private static ByteBuffer soldStruct(ByteBuffer src, int need) {
		ByteBuffer b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int p = 4;
		while (p < b.limit() && b.get(p) != 0) {
			p++;
		}
		int base = p + 1;
		if (base + need > b.limit()) {
			return null;
		}
		return b.position(base).slice().order(ByteOrder.LITTLE_ENDIAN);
	}

	/** BlockMod 템플릿 (/TweakTemplates/BlockMod/NNN/ "Move i", "Move i Min", "Move i Min Block", "Move i Limit"). */
	public static List<BlockEntry> blockMod(Tweaks tw, int id) {
		List<BlockEntry> l = new ArrayList<>();
		if (id < 0) {
			return l;
		}
		String node = String.format("/TweakTemplates/BlockMod/%03d/", id);
		for (int i = 0; i < 10; i++) {
			OptionalInt mv = tw.raw(node, "Move " + i);
			if (mv.isEmpty()) {
				continue;
			}
			int min = tw.i(node, "Move " + i + " Min", 0) & 0xFF;
			float mb = tw.f(node, "Move " + i + " Min Block", 0f);
			int lim = tw.i(node, "Move " + i + " Limit", 0) & 0xFF;
			l.add(new BlockEntry(mv.getAsInt() & 0xFFFF, min, mb, lim));
		}
		return l;
	}

	private static Soldier soldier(ByteBuffer src) {
		ByteBuffer b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int p = 4;
		while (p < b.limit() && b.get(p) != 0) {
			p++;
		}
		int base = p + 1;
		return new Soldier(b.getShort(base + 0x244) & 0xFFFF, b.getShort(base + 0x246) & 0xFFFF,
				b.getShort(base + 0x248) & 0xFFFF, b.getShort(base + 0x24A) & 0xFFFF);
	}

	private static Motion motion(ByteBuffer src) {
		ByteBuffer b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int p = 4;
		while (p < b.limit() && b.get(p) != 0) {
			p++;
		}
		int base = p + 1;
		if (base + 0x17C > b.limit()) {
			return null;
		}
		return new Motion(b.getFloat(base + 0x178), b.getFloat(base + 0x16C), b.getFloat(base + 0x170), b.getFloat(base + 0xEC),
				b.getFloat(base + 0xF0), b.getFloat(base + 0x108), b.getFloat(base + 0x10C), b.getFloat(base + 0x110), b.getFloat(base + 0x158));
	}

	private static Stone stone(ByteBuffer src) {
		ByteBuffer b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int p = 4;
		while (p < b.limit() && b.get(p) != 0) {
			p++;
		}
		int base = p + 1;
		if (base + 0x272 > b.limit()) {
			return null;
		}
		ByteBuffer sb = b.duplicate().position(base).slice().order(ByteOrder.LITTLE_ENDIAN);
		return new Stone(b.getShort(base + 0x26C) & 0xFFFF, b.getShort(base + 0x270) & 0xFFFF, cstr(sb, 0x20), cstr(sb, 0x38));
	}

	/** TWK 바이너리에서 hash 키의 블록 값 원시 바이트 (NUL 이후 포함). TweakFile 레코드 규칙 그대로. */
	static byte[] stringValue(ByteBuffer src, int hash) {
		ByteBuffer b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int p = 0;
		if (b.limit() >= 8 && b.getInt(0) == TweakFile.MAGIC) {
			p = 8;
		}
		int[] size = {4, 0x20, 0x40, 0x100, 0x200, 0x400, 0x800, 0x1000};
		while (p < b.limit()) {
			int t = b.get(p) & 0xFF;
			if (t == 0x80 || t == 0x10) {
				p++;
				while (p < b.limit() && b.get(p) != 0) {
					p++;
				}
				p++;
			} else if (t == 0x40 || t == 0) {
				p++;
			} else if (t == 0x20) {
				p += 9;
			} else if (t > 0x20 && t < 0x28) {
				int len = Math.min(size[t & 7], b.limit() - p - 5);
				if (b.getInt(p + 1) == hash) {
					byte[] out = new byte[len];
					b.get(p + 5, out);
					return out;
				}
				p += 5 + size[t & 7];
			} else {
				return null;
			}
		}
		return null;
	}
}
