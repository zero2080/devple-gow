package devple.gow.tools.ui;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * FLP 액션 스크립트 디스어셈블러. SWF4 ActionScript 바이트코드와 opcode 번호가 같지만
 * 0x80 이상 opcode 에 길이 필드가 없고 문자열은 FLP 문자열 섹션 오프셋(u16)으로 들어간다.
 * <pre>
 * 0x81 GotoFrame  u16 frame          0x83 GetURL(FSCommand) u16 url, u16 target
 * 0x8B SetTarget  u16 str            0x8C GotoLabel u16 str
 * 0x96 Push       u16 str  (값은 항상 문자열. 숫자도 "8" 처럼 문자열 상수)
 * 0x99 Jump       s16 (다음 명령 기준) 0x9D If s16      0x9E Call(pop)     0x9F GotoFrame2 u8 play
 * 0x9A GetURL2    u8 flags (0xC0 = loadVariables(url, target))
 * (mogaika 구현의 "01 → float, 00 → 3바이트" 분기는 이 디스크에서 틀린다: 오프셋 하위 바이트가 01/00 인 경우일 뿐)
 * </pre>
 */
public final class FlpScript {

	private FlpScript() {
	}

	/** 한 줄 = "오프셋: 명령". str 은 문자열 섹션 오프셋 → 문자열. */
	public static List<String> disassemble(byte[] code, IntFunction<String> str) {
		ByteBuffer b = ByteBuffer.wrap(code).order(ByteOrder.LITTLE_ENDIAN);
		List<String> out = new ArrayList<>();
		int p = 0;
		while (p < code.length) {
			int at = p;
			int op = code[p++] & 0xFF;
			String s;
			try {
				if (op < 0x80) {
					s = simple(op);
				} else {
					switch (op) {
						case 0x81 -> {
							s = "GotoFrame " + u16(b, p);
							p += 2;
						}
						case 0x83 -> {
							s = "GetURL " + q(str.apply(u16(b, p))) + " " + q(str.apply(u16(b, p + 2)));
							p += 4;
						}
						case 0x8B -> {
							s = "SetTarget " + q(str.apply(u16(b, p)));
							p += 2;
						}
						case 0x8C -> {
							s = "GotoLabel " + q(str.apply(u16(b, p)));
							p += 2;
						}
						case 0x96 -> {
							// 항상 u16 문자열 오프셋 (숫자도 "8" 같은 문자열로 저장, SWF4 와 동일)
							s = "Push " + q(str.apply(u16(b, p)));
							p += 2;
						}
						case 0x9A -> {
							int fl = code[p] & 0xFF;
							s = String.format("GetURL2 0x%02x%s", fl, (fl & 0x40) != 0 ? " (LoadVariables)" : "");
							p += 1;
						}
						case 0x99, 0x9D -> {
							int off = b.getShort(p);
							p += 2;
							s = (op == 0x99 ? "Jump " : "If ") + String.format("L%04x", p + off);
						}
						case 0x9E -> s = "Call";
						case 0x9F -> {
							s = "GotoFrame2 " + ((code[p] & 0xFF) != 0 ? "play" : "stop");
							p += 1;
						}
						default -> {
							s = String.format("?op%02X (중단) %s", op, hex(code, at, Math.min(code.length, at + 16)));
							p = code.length;
						}
					}
				}
			} catch (IndexOutOfBoundsException e) {
				s = String.format("?op%02X truncated", op);
				p = code.length;
			}
			out.add(String.format("L%04x: %s", at, s));
		}
		return out;
	}

	static String hex(byte[] a, int from, int to) {
		StringBuilder sb = new StringBuilder();
		for (int i = from; i < to; i++) {
			sb.append(String.format("%02x", a[i] & 0xFF));
		}
		return sb.toString();
	}

	private static int u16(ByteBuffer b, int p) {
		return Short.toUnsignedInt(b.getShort(p));
	}

	private static String q(String s) {
		return s == null ? "null" : '"' + s + '"';
	}

	/** 인자 없는 SWF4 액션. */
	static String simple(int op) {
		return switch (op) {
			case 0x00 -> "End";
			case 0x04 -> "NextFrame";
			case 0x05 -> "PrevFrame";
			case 0x06 -> "Play";
			case 0x07 -> "Stop";
			case 0x08 -> "ToggleQuality";
			case 0x09 -> "StopSounds";
			case 0x0A -> "Add";
			case 0x0B -> "Subtract";
			case 0x0C -> "Multiply";
			case 0x0D -> "Divide";
			case 0x0E -> "Equals";
			case 0x0F -> "Less";
			case 0x10 -> "And";
			case 0x11 -> "Or";
			case 0x12 -> "Not";
			case 0x13 -> "StringEquals";
			case 0x14 -> "StringLength";
			case 0x15 -> "StringExtract";
			case 0x17 -> "Pop";
			case 0x18 -> "ToInteger";
			case 0x1C -> "GetVariable";
			case 0x1D -> "SetVariable";
			case 0x20 -> "SetTarget2";
			case 0x21 -> "StringAdd";
			case 0x22 -> "GetProperty";
			case 0x23 -> "SetProperty";
			case 0x24 -> "CloneSprite";
			case 0x25 -> "RemoveSprite";
			case 0x26 -> "Trace";
			case 0x29 -> "StringLess";
			case 0x30 -> "RandomNumber";
			case 0x34 -> "GetTime";
			default -> String.format("?op%02X", op);
		};
	}
}
