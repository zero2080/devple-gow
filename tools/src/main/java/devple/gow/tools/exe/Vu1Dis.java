package devple.gow.tools.exe;

import java.io.PrintStream;
import java.util.Map;
import java.util.TreeMap;

/**
 * 최소 VU1 마이크로코드 디스어셈블러 (상위 FMAC + 하위 명령). 실행 파일 .data 안의 VIF MPG 체인(예 파티클 0x2A7700..0x2AA338,
 * 메시 0x2ABB00..0x2AE000, 0x2AE000..0x2B1690)을 따라 마이크로 메모리 이미지를 만든 뒤 출력한다 (16 문서 §2.4).
 * <pre>./gradlew :tools:run --args="exe:vu1 work/SCKA_300.02 0x2a7700 0x2aa338"</pre>
 */
public final class Vu1Dis {

	private Vu1Dis() {
	}

	/** VIF 코드 열(NOP / MPG)을 따라 마이크로 메모리 (주소 = 8바이트 단위) → 64비트 명령. */
	public static Map<Integer, Long> load(Elf elf, int start, int end) {
		Map<Integer, Long> mem = new TreeMap<>();
		int p = start;
		while (p + 4 <= end) {
			int w = elf.u32(p);
			int cmd = (w >>> 24) & 0x7F;
			p += 4;
			if (cmd == 0x4A) {
				int num = (w >>> 16) & 0xFF;
				if (num == 0) {
					num = 256;
				}
				int addr = w & 0xFFFF;
				// MPG 데이터는 8바이트 정렬
				if ((p & 7) != 0) {
					p += 4;
				}
				for (int i = 0; i < num && p + 8 <= end; i++, p += 8) {
					long lo = elf.u32(p) & 0xFFFFFFFFL, hi = elf.u32(p + 4) & 0xFFFFFFFFL;
					mem.put(addr + i, lo | hi << 32);
				}
			}
		}
		return mem;
	}

	public static void print(Map<Integer, Long> mem, PrintStream o) {
		for (Map.Entry<Integer, Long> e : mem.entrySet()) {
			int pc = e.getKey();
			int lo = (int) (long) e.getValue(), up = (int) (e.getValue() >>> 32);
			String l = (up >>> 31 & 1) != 0 ? String.format("loi %s", Float.intBitsToFloat(lo)) : lower(lo, pc);
			o.printf("%03x  %-44s %s%n", pc, upper(up), l);
		}
	}

	private static final String BC = "xyzw";

	private static String dest(int i) {
		StringBuilder s = new StringBuilder();
		for (int k = 0; k < 4; k++) {
			if ((i >> (24 - k) & 1) != 0) {
				s.append(BC.charAt(k));
			}
		}
		return s.toString();
	}

	private static final String[] U1 = new String[0x30];
	private static final String[] U2 = new String[0x30];
	private static final Map<Integer, String> L2 = new TreeMap<>();

	static {
		String[] u1 = {"mulq", "maxi", "muli", "minii", "addq", "maddq", "addi", "maddi", "subq", "msubq", "subi", "msubi", "add", "madd",
				"mul", "max", "sub", "msub", "opmsub", "mini"};
		System.arraycopy(u1, 0, U1, 0x1C, u1.length);
		String[][] u2 = {{"10", "itof0"}, {"11", "itof4"}, {"12", "itof12"}, {"13", "itof15"}, {"14", "ftoi0"}, {"15", "ftoi4"}, {"16", "ftoi12"},
				{"17", "ftoi15"}, {"1c", "mulaq"}, {"1d", "abs"}, {"1e", "mulai"}, {"1f", "clipw"}, {"20", "addaq"}, {"21", "maddaq"},
				{"22", "addai"}, {"23", "maddai"}, {"24", "subaq"}, {"25", "msubaq"}, {"26", "subai"}, {"27", "msubai"}, {"28", "adda"},
				{"29", "madda"}, {"2a", "mula"}, {"2c", "suba"}, {"2d", "msuba"}, {"2e", "opmula"}, {"2f", "nop"}};
		for (String[] x : u2) {
			U2[Integer.parseInt(x[0], 16)] = x[1];
		}
		String[][] l2 = {{"30", "move"}, {"31", "mr32"}, {"34", "lqi"}, {"35", "sqi"}, {"36", "lqd"}, {"37", "sqd"}, {"38", "div"}, {"39", "sqrt"},
				{"3a", "rsqrt"}, {"3b", "waitq"}, {"3c", "mtir"}, {"3d", "mfir"}, {"3e", "ilwr"}, {"3f", "iswr"}, {"40", "rnext"}, {"41", "rget"},
				{"42", "rinit"}, {"43", "rxor"}, {"64", "mfp"}, {"68", "xtop"}, {"69", "xitop"}, {"6c", "xgkick"}, {"70", "esadd"},
				{"71", "ersadd"}, {"72", "eleng"}, {"73", "erleng"}, {"74", "eatanxy"}, {"75", "eatanxz"}, {"76", "esum"}, {"78", "esqrt"},
				{"79", "ersqrt"}, {"7a", "ercpr"}, {"7b", "waitp"}, {"7c", "esin"}, {"7d", "eatan"}, {"7e", "eexp"}};
		for (String[] x : l2) {
			L2.put(Integer.parseInt(x[0], 16), x[1]);
		}
	}

	static String upper(int i) {
		int ft = (i >> 16) & 31, fs = (i >> 11) & 31, fd = (i >> 6) & 31, f = i & 0x3F;
		String d = dest(i);
		StringBuilder fl = new StringBuilder();
		String[] fn = {"I", "E", "M", "D", "T"};
		for (int k = 0; k < 5; k++) {
			if ((i >>> (31 - k) & 1) != 0) {
				fl.append(fn[k]);
			}
		}
		String flags = fl.isEmpty() ? "" : "[" + fl + "]";
		if (f < 0x1C) {
			String op = new String[]{"add", "sub", "madd", "msub", "max", "mini", "mul"}[f >> 2] + BC.charAt(f & 3);
			return String.format("%s.%s vf%d, vf%d, vf%d%c%s", op, d, fd, fs, ft, BC.charAt(f & 3), flags);
		}
		if (f < 0x30) {
			String n = U1[f];
			if (n == null) {
				return "?u" + Integer.toHexString(f) + flags;
			}
			if (n.endsWith("q")) {
				return String.format("%s.%s vf%d, vf%d, Q%s", n, d, fd, fs, flags);
			}
			if (n.endsWith("i") && !n.equals("mini")) {
				return String.format("%s.%s vf%d, vf%d, I%s", n, d, fd, fs, flags);
			}
			return String.format("%s.%s vf%d, vf%d, vf%d%s", n, d, fd, fs, ft, flags);
		}
		if (f >= 0x3C) {
			int o = (((i >> 6) & 31) << 2) | (f & 3);
			if (o < 0x10) {
				String op = new String[]{"adda", "suba", "madda", "msuba"}[o >> 2] + BC.charAt(o & 3);
				return String.format("%s.%s ACC, vf%d, vf%d%c%s", op, d, fs, ft, BC.charAt(o & 3), flags);
			}
			if (o >= 0x18 && o < 0x1C) {
				return String.format("mula%c.%s ACC, vf%d, vf%d%c%s", BC.charAt(o & 3), d, fs, ft, BC.charAt(o & 3), flags);
			}
			String n = o < U2.length ? U2[o] : null;
			if (n == null) {
				return "?u2_" + Integer.toHexString(o) + flags;
			}
			if (n.equals("nop")) {
				return "nop" + flags;
			}
			if (n.equals("abs") || n.startsWith("itof") || n.startsWith("ftoi")) {
				return String.format("%s.%s vf%d, vf%d%s", n, d, ft, fs, flags);
			}
			if (n.equals("clipw")) {
				return String.format("clipw.xyz vf%d, vf%dw%s", fs, ft, flags);
			}
			if (n.endsWith("q")) {
				return String.format("%s.%s ACC, vf%d, Q%s", n, d, fs, flags);
			}
			if (n.endsWith("i")) {
				return String.format("%s.%s ACC, vf%d, I%s", n, d, fs, flags);
			}
			return String.format("%s.%s ACC, vf%d, vf%d%s", n, d, fs, ft, flags);
		}
		return "?u" + Integer.toHexString(f) + flags;
	}

	private static int s11(int i) {
		int v = i & 0x7FF;
		return (v & 0x400) != 0 ? v - 0x800 : v;
	}

	static String lower(int i, int pc) {
		int op = i >>> 25, ft = (i >> 16) & 31, fs = (i >> 11) & 31, fd = (i >> 6) & 31;
		String d = dest(i);
		if (op == 0x40) {
			int f = i & 0x3F;
			switch (f) {
				case 0x30:
					return String.format("iadd vi%d, vi%d, vi%d", fd, fs, ft);
				case 0x31:
					return String.format("isub vi%d, vi%d, vi%d", fd, fs, ft);
				case 0x32:
					return String.format("iaddi vi%d, vi%d, %d", ft, fs, (fd & 16) != 0 ? fd - 32 : fd);
				case 0x34:
					return String.format("iand vi%d, vi%d, vi%d", fd, fs, ft);
				case 0x35:
					return String.format("ior vi%d, vi%d, vi%d", fd, fs, ft);
				default:
					break;
			}
			if (f >= 0x3C) {
				int o = (((i >> 6) & 31) << 2) | (f & 3);
				String n = L2.getOrDefault(o, "?l2_" + Integer.toHexString(o));
				char fsf = BC.charAt((i >> 21) & 3), ftf = BC.charAt((i >> 23) & 3);
				return switch (n) {
					case "move", "mr32" -> String.format("%s.%s vf%d, vf%d", n, d, ft, fs);
					case "lqi" -> String.format("lqi.%s vf%d, (vi%d++)", d, ft, fs);
					case "sqi" -> String.format("sqi.%s vf%d, (vi%d++)", d, fs, ft);
					case "lqd" -> String.format("lqd.%s vf%d, (--vi%d)", d, ft, fs);
					case "sqd" -> String.format("sqd.%s vf%d, (--vi%d)", d, fs, ft);
					case "div", "rsqrt" -> String.format("%s Q, vf%d%c, vf%d%c", n, fs, fsf, ft, ftf);
					case "sqrt" -> String.format("sqrt Q, vf%d%c", ft, ftf);
					case "mtir" -> String.format("mtir vi%d, vf%d%c", ft, fs, fsf);
					case "mfir" -> String.format("mfir.%s vf%d, vi%d", d, ft, fs);
					case "ilwr", "iswr" -> String.format("%s.%s vi%d, (vi%d)", n, d, ft, fs);
					case "xtop", "xitop" -> String.format("%s vi%d", n, ft);
					case "xgkick" -> String.format("xgkick vi%d", fs);
					case "mfp" -> String.format("mfp.%s vf%d, P", d, ft);
					case "esin", "eatan", "eexp", "esqrt", "ersqrt", "ercpr" -> String.format("%s P, vf%d%c", n, fs, fsf);
					case "eleng", "erleng", "esadd", "ersadd", "esum", "eatanxy", "eatanxz" -> String.format("%s P, vf%d", n, fs);
					default -> n;
				};
			}
			return "?l40_" + Integer.toHexString(f);
		}
		String tgt = String.format("0x%03x", pc + 1 + s11(i));
		int imm15 = ((i >> 10) & 0x7800) | (i & 0x7FF), imm12 = ((i >> 10) & 0x800) | (i & 0x7FF);
		return switch (op) {
			case 0x00 -> String.format("lq.%s vf%d, %d(vi%d)", d, ft, s11(i), fs);
			case 0x01 -> String.format("sq.%s vf%d, %d(vi%d)", d, fs, s11(i), ft);
			case 0x04 -> String.format("ilw.%s vi%d, %d(vi%d)", d, ft, s11(i), fs);
			case 0x05 -> String.format("isw.%s vi%d, %d(vi%d)", d, ft, s11(i), fs);
			case 0x08 -> String.format("iaddiu vi%d, vi%d, 0x%x", ft, fs, imm15);
			case 0x09 -> String.format("isubiu vi%d, vi%d, 0x%x", ft, fs, imm15);
			case 0x10 -> String.format("fceq vi1, 0x%x", i & 0xFFFFFF);
			case 0x11 -> String.format("fcset 0x%x", i & 0xFFFFFF);
			case 0x12 -> String.format("fcand vi1, 0x%x", i & 0xFFFFFF);
			case 0x13 -> String.format("fcor vi1, 0x%x", i & 0xFFFFFF);
			case 0x14 -> String.format("fseq vi%d, 0x%x", ft, imm12);
			case 0x15 -> String.format("fsset 0x%x", imm12);
			case 0x16 -> String.format("fsand vi%d, 0x%x", ft, imm12);
			case 0x17 -> String.format("fsor vi%d, 0x%x", ft, imm12);
			case 0x18 -> String.format("fmeq vi%d, vi%d", ft, fs);
			case 0x1A -> String.format("fmand vi%d, vi%d", ft, fs);
			case 0x1B -> String.format("fmor vi%d, vi%d", ft, fs);
			case 0x1C -> String.format("fcget vi%d", ft);
			case 0x20 -> "b " + tgt;
			case 0x21 -> String.format("bal vi%d, %s", ft, tgt);
			case 0x24 -> String.format("jr vi%d", fs);
			case 0x25 -> String.format("jalr vi%d, vi%d", ft, fs);
			case 0x28 -> String.format("ibeq vi%d, vi%d, %s", ft, fs, tgt);
			case 0x29 -> String.format("ibne vi%d, vi%d, %s", ft, fs, tgt);
			case 0x2C -> String.format("ibltz vi%d, %s", fs, tgt);
			case 0x2D -> String.format("ibgtz vi%d, %s", fs, tgt);
			case 0x2E -> String.format("iblez vi%d, %s", fs, tgt);
			case 0x2F -> String.format("ibgez vi%d, %s", fs, tgt);
			default -> "?l" + Integer.toHexString(op);
		};
	}
}
