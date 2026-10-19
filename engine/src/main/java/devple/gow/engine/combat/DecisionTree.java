package devple.gow.engine.combat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 적 결정 트리 ({@code TWK_DTre_NNN} 의 "DecisionTree" 블록, 17 문서 §3).
 * <pre>
 * ['#']  선택: 있으면 공격 항목에 재사용 대기(cool) u16 이 붙는 신형                (파서 0x1A1CF8)
 * u32 노드 수
 * 노드: u8 종류
 *   0 판단: u16 조건, u8 비교, u8 인자, u32 값(조건 형에 따라 float/int), u16 참 → 노드, u16 거짓 → 노드
 *   1 행동: cstr 이름(≤16), u8 n × 공격{s16 기술, f32 가중치, u16 최소거리, u16 최대거리, u16 비용A, u16 비용B,
 *           u16 공격 후 대기 ms, [u16 재사용 대기 ms]}, u8 n × 방어{s16 기술, f32 %}, u8 n × 보조{s16, f32}   (0x2296E0)
 * </pre>
 * 평가(0x1A2250): 루트에서 판단 노드를 따라가 행동 노드를 돌려준다. 조건 함수 표 0x2FFC68(16바이트 × 28)은 {@link Sense}.
 */
public final class DecisionTree {

	/** 조건 값 형 (조건 표 +0x0C). 0 float: < <= > >=, 1·2 정수 ==/!=, 3 정수 대소 비교. */
	static final int[] COND_TYPE = {0, 0, 0, 1, 2, 2, 1, 1, 1, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1, 0, 0, 1, 1, 3, 1, 1, 3, 3};

	/** 조건 번호 (0x2FFC68 표 순서). 값의 의미는 17 문서 §3.2. */
	public static final int C_ELEVATION = 0, C_YAW = 1, C_DIST = 2, C_SELF_STATE = 4, C_TARGET_STATE = 5, C_IN_FRONT = 7,
			C_ON_SIDE = 8, C_SELF_HP = 9, C_TARGET_HP = 10, C_TARGET_GUARD = 11, C_ABOVE = 12, C_LATERAL = 13, C_FORWARD = 14,
			C_HEIGHT = 15, C_DIRECTLY_ABOVE = 16, C_TARGET_STILL = 17, C_TARGET_IN_MOVE = 18, C_FLAT_DIST = 19,
			C_YAW_FROM_TARGET = 20, C_SELF_AIR = 21, C_COLLISION = 22, C_TARGET_MOVE = 23, C_HAS_TOKEN = 24, C_SCRIPT = 25;

	/** 조건 값 공급자 (원작 조건 함수들). float 형은 그대로, 나머지는 정수 값. */
	public interface Sense {
		float value(int cond, int arg);
	}

	/**
	 * 공격 항목. 거리 = 값/100 × 16 월드 단위. 비용 A/B 는 대상의 공격 슬롯(Sold +0x248/+0x24A)을 차지한다.
	 *
	 * @param pauseMs    끝난 뒤 다음 공격까지 대기(ms) × 난이도 "AI Recovery Time Mult" (0x1CCCD8, 0x1D307C)
	 * @param cooldownMs 고른 뒤 같은 행동의 재사용 금지(ms) × 같은 배율 (0x2294D4)
	 */
	public record Attack(int move, float weight, int minDist, int maxDist, int costA, int costB, int pauseMs, int cooldownMs) {

		public float minUnits() {
			return minDist / 100f * 16f;
		}

		public float maxUnits() {
			return maxDist / 100f * 16f;
		}
	}

	/** 방어/보조 항목: 기술과 확률(%). */
	public record Weighted(int move, float weight) {
	}

	/** 행동 노드. attacks=공격 목록(+0x10), blocks=방어 목록(+0x18), extra=보조 목록(+0x20, 사용처 미상). */
	public record Leaf(String name, List<Attack> attacks, List<Weighted> blocks, List<Weighted> extra) {
	}

	private record Node(int cond, int op, int arg, int value, int ifTrue, int ifFalse, Leaf leaf) {
	}

	private final List<Node> nodes;

	private DecisionTree(List<Node> nodes) {
		this.nodes = nodes;
	}

	public int size() {
		return nodes.size();
	}

	/** 모든 행동 노드 (순서대로). */
	public List<Leaf> leaves() {
		List<Leaf> l = new ArrayList<>();
		for (Node n : nodes) {
			if (n.leaf != null) {
				l.add(n.leaf);
			}
		}
		return l;
	}

	public static DecisionTree parse(byte[] blob) {
		ByteBuffer b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
		boolean v2 = blob.length > 0 && blob[0] == '#';
		b.position(v2 ? 1 : 0);
		int n = b.getInt();
		List<Node> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int kind = b.get() & 0xFF;
			if (kind == 0) {
				int cond = b.getShort() & 0xFFFF;
				int op = b.get() & 0xFF;
				int arg = b.get() & 0xFF;
				int value = b.getInt();
				int t = b.getShort() & 0xFFFF;
				int f = b.getShort() & 0xFFFF;
				out.add(new Node(cond, op, arg, value, t, f, null));
			} else if (kind == 1) {
				StringBuilder name = new StringBuilder();
				for (int k = 0; k < 16; k++) {
					byte c = b.get();
					if (c == 0) {
						break;
					}
					name.append((char) (c & 0xFF));
				}
				List<Attack> atk = new ArrayList<>();
				int na = b.get() & 0xFF;
				for (int k = 0; k < na; k++) {
					int move = b.getShort();
					float w = b.getFloat();
					int mn = b.getShort() & 0xFFFF, mx = b.getShort() & 0xFFFF;
					int ca = b.getShort() & 0xFFFF, cb = b.getShort() & 0xFFFF, pause = b.getShort() & 0xFFFF;
					int cool = v2 ? b.getShort() & 0xFFFF : 0;
					atk.add(new Attack(move, w, mn, mx, ca, cb, pause, cool));
				}
				out.add(new Node(-1, 0, 0, 0, 0, 0, new Leaf(name.toString(), atk, weighted(b), weighted(b))));
			} else {
				throw new IllegalArgumentException("DTre 노드 종류 " + kind + " @" + b.position());
			}
		}
		return new DecisionTree(out);
	}

	private static List<Weighted> weighted(ByteBuffer b) {
		List<Weighted> l = new ArrayList<>();
		int n = b.get() & 0xFF;
		for (int k = 0; k < n; k++) {
			int m = b.getShort();
			l.add(new Weighted(m, b.getFloat()));
		}
		return l;
	}

	/** 평가 (0x1A2250). 행동 노드까지 내려간다. 순환/범위 밖이면 null. */
	public Leaf evaluate(Sense s) {
		int i = 0;
		for (int guard = 0; guard < 256 && i >= 0 && i < nodes.size(); guard++) {
			Node n = nodes.get(i);
			if (n.leaf != null) {
				return n.leaf;
			}
			i = test(n, s) ? n.ifTrue : n.ifFalse;
		}
		return null;
	}

	static boolean test(Node n, Sense s) {
		int type = n.cond < COND_TYPE.length ? COND_TYPE[n.cond] : 3;
		float v = s.value(n.cond, n.arg);
		if (type == 0) {
			float c = Float.intBitsToFloat(n.value);
			return switch (n.op) {
				case 2 -> v < c;
				case 3 -> v <= c;
				case 4 -> c < v;
				case 5 -> c <= v;
				default -> true;
			};
		}
		int a = (int) v;
		if (type == 3) {
			// 점프표 0x2B5730 (op 0..5)
			return switch (n.op) {
				case 0 -> a == n.value;
				case 1 -> a != n.value;
				case 2 -> a < n.value;
				case 3 -> a <= n.value;
				case 4 -> a > n.value;
				case 5 -> a >= n.value;
				default -> true;
			};
		}
		return switch (n.op) {
			case 0 -> a == n.value;
			case 1 -> a != n.value;
			default -> true;
		};
	}

	/** 방어 목록 고르기 (0x229660): r = rand×100, 순서대로 r ≤ w 이면 선택, 아니면 r −= w. 남으면 없음. */
	public static Weighted pick(List<Weighted> list, Random rnd) {
		float r = rnd.nextFloat() * 100f;
		for (Weighted w : list) {
			if (r <= w.weight()) {
				return w;
			}
			r -= w.weight();
		}
		return null;
	}

	/**
	 * 공격 고르기 (0x229258). 자격: 최소거리 &lt; dist &lt; 최대거리, 비용A ≤ freeA, 비용B ≤ freeB,
	 * 재사용 대기가 있는 항목은 행동별 대기 시각이 지났을 때만. 자격 항목 가중치 합 × rand 로 가중 선택.
	 *
	 * @param coolReady 이 행동 노드의 재사용 대기가 끝났는지 (원작: 노드 상태 +8 프레임 시각)
	 */
	public static Attack pickAttack(Leaf leaf, float dist, int freeA, int freeB, boolean coolReady, Random rnd) {
		float sum = 0;
		List<Attack> ok = new ArrayList<>();
		for (Attack a : leaf.attacks()) {
			if (eligible(a, dist, freeA, freeB, coolReady)) {
				ok.add(a);
				sum += a.weight();
			}
		}
		float r = rnd.nextFloat() * sum;
		for (Attack a : ok) {
			if (r < a.weight()) {
				return a;
			}
			r -= a.weight();
		}
		return null;
	}

	static boolean eligible(Attack a, float dist, int freeA, int freeB, boolean coolReady) {
		return a.minUnits() < dist && dist < a.maxUnits() && freeA >= a.costA() && freeB >= a.costB()
				&& (a.cooldownMs() == 0 || coolReady);
	}

	/** 이름 문자열 디버그용. */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < nodes.size(); i++) {
			Node n = nodes.get(i);
			if (n.leaf != null) {
				sb.append(i).append(": [").append(n.leaf.name()).append("] ").append(n.leaf.attacks()).append('\n');
			} else {
				sb.append(i).append(": c").append(n.cond).append(" op").append(n.op).append(' ').append(n.value)
						.append(" ? ").append(n.ifTrue).append(" : ").append(n.ifFalse).append('\n');
			}
		}
		return sb.toString();
	}
}
