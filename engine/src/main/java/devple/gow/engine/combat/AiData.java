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
	 */
	public record Stone(int resist, int move) {
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

	/** BlockMod 항목 하나 (0x19BEF0): 영웅 기술, 최소 횟수, 최소 방어 %, 한계 횟수. */
	public record BlockEntry(int move, int min, float minBlock, int limit) {
	}

	private final Map<Integer, DecisionTree> trees = new HashMap<>();
	private final Map<Integer, Soldier> soldiers = new HashMap<>();
	private final Map<Integer, Stone> stones = new HashMap<>();
	private final Map<Integer, Motion> motions = new HashMap<>();

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
		return new Stone(b.getShort(base + 0x26C) & 0xFFFF, b.getShort(base + 0x270) & 0xFFFF);
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
