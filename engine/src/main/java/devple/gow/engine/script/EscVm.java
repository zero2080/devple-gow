package devple.gow.engine.script;

import devple.gow.tools.script.EscScript;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 레벨 스크립트(ESC, SCR_Entities) 바이트코드 인터프리터. opcode 표는 07 문서 §5.3.
 * 핸들러 하나를 끝(exit)까지 실행하고 return 으로 지정된 값을 돌려준다.
 */
public final class EscVm {

	/** 무한 루프 방지 (원작 스크립트는 핸들러당 수십 명령). */
	public static final int MAX_STEPS = 100_000;

	private final EscHost host;

	public EscVm(EscHost host) {
		this.host = host;
	}

	public Object run(EscContext ctx, EscScript.Handler h) {
		return run(ctx, ctx.entity().bytecode(), h.start());
	}

	public Object run(EscContext ctx, byte[] bytecode, int start) {
		ByteBuffer c = ByteBuffer.wrap(bytecode).order(ByteOrder.LITTLE_ENDIAN);
		Deque<Object> st = new ArrayDeque<>();
		Object result = null;
		int pc = start;
		for (int steps = 0; steps < MAX_STEPS && pc < c.limit(); steps++) {
			int op = c.get(pc++) & 0xFF;
			if (op >= 0x3A) {
				return result;
			}
			switch (op) {
				case 0x00 -> {
					st.push(c.getFloat(pc));
					pc += 4;
				}
				case 0x01 -> {
					st.push(c.getInt(pc));
					pc += 4;
				}
				case 0x02, 0x03, 0x04, 0x05 -> {
					int ref = u16(c, pc);
					pc += 2;
					st.push(host.getVar(ctx, ref >>> 12, ref & 0xFFF, op - 0x02));
				}
				case 0x06, 0x07, 0x08, 0x09 -> {
					int ref = u16(c, pc);
					pc += 2;
					host.setVar(ctx, ref >>> 12, ref & 0xFFF, op - 0x06, st.pop());
				}
				case 0x0A, 0x0B, 0x0C, 0x0D -> {
					int ref = u16(c, pc);
					pc += 2;
					Object r = host.call(ctx, ref >>> 12, ref & 0xFFF, op - 0x0A, st);
					st.push(r != null ? r : defaultOf(op - 0x0A));
				}
				case 0x0E -> {
					st.push(cstr(c, u16(c, pc)));
					pc += 2;
				}
				case 0x0F -> {
					int off = c.getShort(pc);
					pc += 2;
					if (bool(st.pop())) {
						pc += off;
					}
				}
				case 0x10 -> {
					int off = c.getShort(pc);
					pc += 2;
					pc += off;
				}
				case 0x11 -> st.push(true);
				case 0x12 -> st.push(false);
				case 0x13 -> st.push(-i(st.pop()));
				case 0x14 -> st.push(-f(st.pop()));
				case 0x15, 0x17, 0x19, 0x1B, 0x1D -> {
					int b = i(st.pop()), a = i(st.pop());
					st.push(switch (op) {
						case 0x15 -> a + b;
						case 0x17 -> a - b;
						case 0x19 -> a * b;
						case 0x1B -> b == 0 ? 0 : a / b;
						default -> b == 0 ? 0 : a % b;
					});
				}
				case 0x16, 0x18, 0x1A, 0x1C -> {
					float b = f(st.pop()), a = f(st.pop());
					st.push(switch (op) {
						case 0x16 -> a + b;
						case 0x18 -> a - b;
						case 0x1A -> a * b;
						default -> b == 0 ? 0f : a / b;
					});
				}
				case 0x1E -> st.push(bool(st.pop()) ? 1f : 0f);
				case 0x1F -> st.push(bool(st.pop()) ? 1 : 0);
				case 0x20 -> st.push(f(st.pop()) != 0f);
				case 0x21 -> st.push((int) f(st.pop()));
				case 0x22 -> st.push(i(st.pop()) != 0);
				case 0x23 -> st.push((float) i(st.pop()));
				case 0x24 -> st.push(!bool(st.pop()));
				case 0x25, 0x26, 0x27 -> {
					boolean b = bool(st.pop()), a = bool(st.pop());
					st.push(op == 0x25 ? a && b : op == 0x26 ? a || b : a ^ b);
				}
				case 0x38 -> result = st.isEmpty() ? null : st.pop();
				default -> {
					if (op >= 0x28 && op <= 0x37) {
						compare(op, st);
					} else {
						throw new IllegalStateException(String.format("%s: 알 수 없는 opcode %02X @%04x", ctx.script(), op, pc - 1));
					}
				}
			}
		}
		return result;
	}

	/** 0x28–0x33: lt/le/gt/ge × (int, float, string), 0x34–0x37: eq × (int, float, bool, string). */
	private static void compare(int op, Deque<Object> st) {
		Object b = st.pop(), a = st.pop();
		if (op >= 0x34) {
			st.push(switch (op) {
				case 0x34 -> i(a) == i(b);
				case 0x35 -> f(a) == f(b);
				case 0x36 -> bool(a) == bool(b);
				default -> String.valueOf(a).equals(String.valueOf(b));
			});
			return;
		}
		int kind = (op - 0x28) / 3;
		int type = (op - 0x28) % 3;
		int cmp = switch (type) {
			case 0 -> Integer.compare(i(a), i(b));
			case 1 -> Float.compare(f(a), f(b));
			default -> String.valueOf(a).compareTo(String.valueOf(b));
		};
		st.push(switch (kind) {
			case 0 -> cmp < 0;
			case 1 -> cmp <= 0;
			case 2 -> cmp > 0;
			default -> cmp >= 0;
		});
	}

	private static Object defaultOf(int type) {
		return switch (type) {
			case 0 -> 0f;
			case 1 -> 0;
			case 2 -> false;
			default -> "";
		};
	}

	static int i(Object o) {
		return o instanceof Number n ? n.intValue() : o instanceof Boolean b && b ? 1 : 0;
	}

	static float f(Object o) {
		return o instanceof Number n ? n.floatValue() : o instanceof Boolean b && b ? 1f : 0f;
	}

	static boolean bool(Object o) {
		return o instanceof Boolean b ? b : o instanceof Number n ? n.floatValue() != 0 : o != null && !o.toString().isEmpty();
	}

	private static int u16(ByteBuffer b, int p) {
		return Short.toUnsignedInt(b.getShort(p));
	}

	private static String cstr(ByteBuffer b, int off) {
		int n = 0;
		while (off + n < b.limit() && b.get(off + n) != 0) {
			n++;
		}
		return new String(b.array(), off, n, StandardCharsets.ISO_8859_1);
	}
}
