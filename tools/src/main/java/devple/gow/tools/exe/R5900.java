package devple.gow.tools.exe;

/**
 * MIPS R5900(EE) 디스어셈블러. MIPS III 정수 + COP1(단정도) + EE 확장(lq/sq, MMI, mult 3피연산자) 을 해석한다.
 * VU0 매크로(COP2) 명령은 니모닉 없이 원시 값으로만 표시한다.
 */
public final class R5900 {

	public static final String[] REG = {
			"zero", "at", "v0", "v1", "a0", "a1", "a2", "a3",
			"t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7",
			"s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7",
			"t8", "t9", "k0", "k1", "gp", "sp", "fp", "ra"};

	public static final int RA = 31, SP = 29, GP = 28, ZERO = 0;

	/** 명령 종류 (분석용). */
	public enum Kind { OTHER, LOAD, STORE, BRANCH, JUMP, CALL, JR, JALR, LUI, ADDIU, ORI, MOVE, FLOAD, FSTORE, INVALID }

	/**
	 * 해석된 명령.
	 *
	 * @param rd   결과를 쓰는 GPR (-1 = 없음)
	 * @param rs   소스/베이스 레지스터
	 * @param rt   두번째 소스 (store 의 값 레지스터 등)
	 * @param imm  부호 확장 즉치값 (lui 는 상위 16비트 그대로)
	 * @param target 분기/점프 대상 (없으면 0)
	 */
	public record Insn(int pc, int word, String mnem, String ops, Kind kind, int rd, int rs, int rt, int imm,
					   int target, boolean likely) {
		public String text() {
			return ops.isEmpty() ? mnem : String.format("%-8s %s", mnem, ops);
		}

		/** 지연 슬롯을 가지는 제어 이동인가. */
		public boolean hasDelaySlot() {
			return kind == Kind.BRANCH || kind == Kind.JUMP || kind == Kind.CALL || kind == Kind.JR || kind == Kind.JALR;
		}
	}

	private R5900() {
	}

	private static String r(int i) {
		return REG[i];
	}

	private static String f(int i) {
		return "$f" + i;
	}

	private static String hex(int v) {
		return v < 0 ? "-0x" + Integer.toHexString(-v) : "0x" + Integer.toHexString(v);
	}

	public static Insn decode(int w, int pc) {
		int op = w >>> 26;
		int rs = (w >>> 21) & 31, rt = (w >>> 16) & 31, rd = (w >>> 11) & 31, sa = (w >>> 6) & 31, fn = w & 63;
		int simm = (short) w, uimm = w & 0xFFFF;
		int btarget = pc + 4 + (simm << 2);
		String mem = hex(simm) + "(" + r(rs) + ")";
		switch (op) {
			case 0:
				return special(w, pc, rs, rt, rd, sa, fn);
			case 1: {
				String[] n = {"bltz", "bgez", "bltzl", "bgezl", null, null, null, null,
						"tgei", "tgeiu", "tlti", "tltiu", "teqi", null, "tnei", null,
						"bltzal", "bgezal", "bltzall", "bgezall", null, null, null, null,
						"mtsab", "mtsah"};
				String m = rt < n.length ? n[rt] : null;
				if (m == null) {
					return inv(w, pc);
				}
				if (rt >= 24) {
					return new Insn(pc, w, m, r(rs) + ", " + hex(simm), Kind.OTHER, -1, rs, -1, simm, 0, false);
				}
				if (rt >= 8 && rt < 16) {
					return new Insn(pc, w, m, r(rs) + ", " + hex(simm), Kind.OTHER, -1, rs, -1, simm, 0, false);
				}
				boolean link = rt >= 16;
				return new Insn(pc, w, m, r(rs) + ", " + hex(btarget), link ? Kind.CALL : Kind.BRANCH,
						link ? RA : -1, rs, -1, simm, btarget, (rt & 2) != 0);
			}
			case 2:
			case 3: {
				int t = (pc & 0xF0000000) | ((w & 0x3FFFFFF) << 2);
				return new Insn(pc, w, op == 2 ? "j" : "jal", hex(t), op == 2 ? Kind.JUMP : Kind.CALL,
						op == 3 ? RA : -1, -1, -1, 0, t, false);
			}
			case 4, 5, 20, 21: {
				String m = switch (op) {
					case 4 -> "beq";
					case 5 -> "bne";
					case 20 -> "beql";
					default -> "bnel";
				};
				if (op == 4 && rs == 0 && rt == 0) {
					return new Insn(pc, w, "b", hex(btarget), Kind.BRANCH, -1, rs, rt, simm, btarget, false);
				}
				return new Insn(pc, w, m, r(rs) + ", " + r(rt) + ", " + hex(btarget), Kind.BRANCH, -1, rs, rt, simm,
						btarget, op >= 20);
			}
			case 6, 7, 22, 23: {
				String m = switch (op) {
					case 6 -> "blez";
					case 7 -> "bgtz";
					case 22 -> "blezl";
					default -> "bgtzl";
				};
				return new Insn(pc, w, m, r(rs) + ", " + hex(btarget), Kind.BRANCH, -1, rs, -1, simm, btarget, op >= 20);
			}
			case 8, 9, 24, 25: {
				String m = switch (op) {
					case 8 -> "addi";
					case 9 -> "addiu";
					case 24 -> "daddi";
					default -> "daddiu";
				};
				if (rs == 0) {
					return new Insn(pc, w, "li", r(rt) + ", " + hex(simm), Kind.ADDIU, rt, rs, -1, simm, 0, false);
				}
				return new Insn(pc, w, m, r(rt) + ", " + r(rs) + ", " + hex(simm), Kind.ADDIU, rt, rs, -1, simm, 0, false);
			}
			case 10, 11:
				return new Insn(pc, w, op == 10 ? "slti" : "sltiu", r(rt) + ", " + r(rs) + ", " + hex(simm), Kind.OTHER,
						rt, rs, -1, simm, 0, false);
			case 12, 13, 14: {
				String m = op == 12 ? "andi" : op == 13 ? "ori" : "xori";
				return new Insn(pc, w, m, r(rt) + ", " + r(rs) + ", 0x" + Integer.toHexString(uimm),
						op == 13 ? Kind.ORI : Kind.OTHER, rt, rs, -1, uimm, 0, false);
			}
			case 15:
				return new Insn(pc, w, "lui", r(rt) + ", 0x" + Integer.toHexString(uimm), Kind.LUI, rt, -1, -1, uimm << 16,
						0, false);
			case 16:
				return cop0(w, pc, rs, rt, rd, fn);
			case 17:
				return cop1(w, pc, rs, rt, rd, sa, fn, simm);
			case 18:
				return cop2(w, pc, rs, rt, rd);
			case 28:
				return mmi(w, pc, rs, rt, rd, sa, fn);
			case 26, 27, 30, 32, 33, 34, 35, 36, 37, 38, 39, 55: {
				String m = switch (op) {
					case 26 -> "ldl";
					case 27 -> "ldr";
					case 30 -> "lq";
					case 32 -> "lb";
					case 33 -> "lh";
					case 34 -> "lwl";
					case 35 -> "lw";
					case 36 -> "lbu";
					case 37 -> "lhu";
					case 38 -> "lwr";
					case 39 -> "lwu";
					default -> "ld";
				};
				return new Insn(pc, w, m, r(rt) + ", " + mem, Kind.LOAD, rt, rs, -1, simm, 0, false);
			}
			case 31, 40, 41, 42, 43, 44, 45, 46, 63: {
				String m = switch (op) {
					case 31 -> "sq";
					case 40 -> "sb";
					case 41 -> "sh";
					case 42 -> "swl";
					case 43 -> "sw";
					case 44 -> "sdl";
					case 45 -> "sdr";
					case 46 -> "swr";
					default -> "sd";
				};
				return new Insn(pc, w, m, r(rt) + ", " + mem, Kind.STORE, -1, rs, rt, simm, 0, false);
			}
			case 47:
				return new Insn(pc, w, "cache", "0x" + Integer.toHexString(rt) + ", " + mem, Kind.OTHER, -1, rs, -1, simm, 0,
						false);
			case 51:
				return new Insn(pc, w, "pref", rt + ", " + mem, Kind.OTHER, -1, rs, -1, simm, 0, false);
			case 49:
				return new Insn(pc, w, "lwc1", f(rt) + ", " + mem, Kind.FLOAD, -1, rs, rt, simm, 0, false);
			case 57:
				return new Insn(pc, w, "swc1", f(rt) + ", " + mem, Kind.FSTORE, -1, rs, rt, simm, 0, false);
			case 54:
				return new Insn(pc, w, "lqc2", "vf" + rt + ", " + mem, Kind.FLOAD, -1, rs, rt, simm, 0, false);
			case 62:
				return new Insn(pc, w, "sqc2", "vf" + rt + ", " + mem, Kind.FSTORE, -1, rs, rt, simm, 0, false);
			default:
				return inv(w, pc);
		}
	}

	private static Insn inv(int w, int pc) {
		return new Insn(pc, w, ".word", String.format("0x%08x", w), Kind.INVALID, -1, -1, -1, 0, 0, false);
	}

	private static Insn rtype(int w, int pc, String m, int rd, int rs, int rt) {
		return new Insn(pc, w, m, r(rd) + ", " + r(rs) + ", " + r(rt), Kind.OTHER, rd, rs, rt, 0, 0, false);
	}

	private static Insn special(int w, int pc, int rs, int rt, int rd, int sa, int fn) {
		switch (fn) {
			case 0:
				if (w == 0) {
					return new Insn(pc, w, "nop", "", Kind.OTHER, -1, -1, -1, 0, 0, false);
				}
			case 2, 3, 56, 58, 59, 60, 62, 63: {
				String m = switch (fn) {
					case 0 -> "sll";
					case 2 -> "srl";
					case 3 -> "sra";
					case 56 -> "dsll";
					case 58 -> "dsrl";
					case 59 -> "dsra";
					case 60 -> "dsll32";
					case 62 -> "dsrl32";
					default -> "dsra32";
				};
				return new Insn(pc, w, m, r(rd) + ", " + r(rt) + ", " + sa, Kind.OTHER, rd, rt, -1, sa, 0, false);
			}
			case 4, 6, 7, 20, 22, 23: {
				String m = switch (fn) {
					case 4 -> "sllv";
					case 6 -> "srlv";
					case 7 -> "srav";
					case 20 -> "dsllv";
					case 22 -> "dsrlv";
					default -> "dsrav";
				};
				return new Insn(pc, w, m, r(rd) + ", " + r(rt) + ", " + r(rs), Kind.OTHER, rd, rt, rs, 0, 0, false);
			}
			case 8:
				return new Insn(pc, w, "jr", r(rs), Kind.JR, -1, rs, -1, 0, 0, false);
			case 9:
				return new Insn(pc, w, "jalr", rd == RA ? r(rs) : r(rd) + ", " + r(rs), Kind.JALR, rd, rs, -1, 0, 0, false);
			case 10:
				return rtype(w, pc, "movz", rd, rs, rt);
			case 11:
				return rtype(w, pc, "movn", rd, rs, rt);
			case 12:
				return new Insn(pc, w, "syscall", "", Kind.OTHER, -1, -1, -1, 0, 0, false);
			case 13:
				return new Insn(pc, w, "break", "", Kind.OTHER, -1, -1, -1, 0, 0, false);
			case 15:
				return new Insn(pc, w, "sync", "", Kind.OTHER, -1, -1, -1, 0, 0, false);
			case 16, 18, 40:
				return new Insn(pc, w, fn == 16 ? "mfhi" : fn == 18 ? "mflo" : "mfsa", r(rd), Kind.OTHER, rd, -1, -1, 0, 0,
						false);
			case 17, 19, 41:
				return new Insn(pc, w, fn == 17 ? "mthi" : fn == 19 ? "mtlo" : "mtsa", r(rs), Kind.OTHER, -1, rs, -1, 0, 0,
						false);
			case 24, 25:
				// EE: mult rd, rs, rt (rd 에 하위 결과도 기록)
				return rd == 0 ? new Insn(pc, w, fn == 24 ? "mult" : "multu", r(rs) + ", " + r(rt), Kind.OTHER, -1, rs, rt,
						0, 0, false) : rtype(w, pc, fn == 24 ? "mult" : "multu", rd, rs, rt);
			case 26, 27:
				return new Insn(pc, w, fn == 26 ? "div" : "divu", r(rs) + ", " + r(rt), Kind.OTHER, -1, rs, rt, 0, 0, false);
			case 32, 33, 44, 45, 37: {
				String m = switch (fn) {
					case 32 -> "add";
					case 33 -> "addu";
					case 44 -> "dadd";
					case 45 -> "daddu";
					default -> "or";
				};
				if (rt == 0 || (rs == 0 && fn != 37)) {
					int src = rt == 0 ? rs : rt;
					return new Insn(pc, w, "move", r(rd) + ", " + r(src), Kind.MOVE, rd, src, -1, 0, 0, false);
				}
				return rtype(w, pc, m, rd, rs, rt);
			}
			case 34, 35, 36, 38, 39, 42, 43, 46, 47: {
				String m = switch (fn) {
					case 34 -> "sub";
					case 35 -> "subu";
					case 36 -> "and";
					case 38 -> "xor";
					case 39 -> "nor";
					case 42 -> "slt";
					case 43 -> "sltu";
					case 46 -> "dsub";
					default -> "dsubu";
				};
				if ((fn == 35 || fn == 47) && rs == 0) {
					return new Insn(pc, w, "neg", r(rd) + ", " + r(rt), Kind.OTHER, rd, rt, -1, 0, 0, false);
				}
				return rtype(w, pc, m, rd, rs, rt);
			}
			case 48, 49, 50, 51, 52, 54:
				return new Insn(pc, w, "trap" + fn, r(rs) + ", " + r(rt), Kind.OTHER, -1, rs, rt, 0, 0, false);
			default:
				return inv(w, pc);
		}
	}

	private static Insn cop0(int w, int pc, int rs, int rt, int rd, int fn) {
		return switch (rs) {
			case 0 -> new Insn(pc, w, "mfc0", r(rt) + ", $" + rd, Kind.OTHER, rt, -1, -1, 0, 0, false);
			case 4 -> new Insn(pc, w, "mtc0", r(rt) + ", $" + rd, Kind.OTHER, -1, rt, -1, 0, 0, false);
			case 16 -> new Insn(pc, w, switch (fn) {
				case 2 -> "tlbwi";
				case 24 -> "eret";
				case 56 -> "ei";
				case 57 -> "di";
				default -> "c0." + fn;
			}, "", Kind.OTHER, -1, -1, -1, 0, 0, false);
			default -> new Insn(pc, w, "cop0", String.format("0x%07x", w & 0x3FFFFFF), Kind.OTHER, -1, -1, -1, 0, 0, false);
		};
	}

	private static Insn cop1(int w, int pc, int rs, int rt, int rd, int sa, int fn, int simm) {
		int fd = sa, fs = rd, ft = rt;
		switch (rs) {
			case 0:
				return new Insn(pc, w, "mfc1", r(rt) + ", " + f(fs), Kind.OTHER, rt, -1, -1, 0, 0, false);
			case 2:
				return new Insn(pc, w, "cfc1", r(rt) + ", $" + fs, Kind.OTHER, rt, -1, -1, 0, 0, false);
			case 4:
				return new Insn(pc, w, "mtc1", r(rt) + ", " + f(fs), Kind.OTHER, -1, rt, -1, 0, 0, false);
			case 6:
				return new Insn(pc, w, "ctc1", r(rt) + ", $" + fs, Kind.OTHER, -1, rt, -1, 0, 0, false);
			case 8: {
				String[] n = {"bc1f", "bc1t", "bc1fl", "bc1tl"};
				int t = pc + 4 + (simm << 2);
				return new Insn(pc, w, n[rt & 3], hex(t), Kind.BRANCH, -1, -1, -1, simm, t, (rt & 2) != 0);
			}
			case 16: {
				String m = switch (fn) {
					case 0 -> "add.s";
					case 1 -> "sub.s";
					case 2 -> "mul.s";
					case 3 -> "div.s";
					case 4 -> "sqrt.s";
					case 5 -> "abs.s";
					case 6 -> "mov.s";
					case 7 -> "neg.s";
					case 22 -> "rsqrt.s";
					case 24 -> "adda.s";
					case 25 -> "suba.s";
					case 26 -> "mula.s";
					case 28 -> "madd.s";
					case 29 -> "msub.s";
					case 30 -> "madda.s";
					case 31 -> "msuba.s";
					case 36 -> "cvt.w.s";
					case 40 -> "max.s";
					case 41 -> "min.s";
					case 48 -> "c.f.s";
					case 50 -> "c.eq.s";
					case 52 -> "c.lt.s";
					case 54 -> "c.le.s";
					default -> null;
				};
				if (m == null) {
					return inv(w, pc);
				}
				String ops = switch (fn) {
					case 4, 22 -> fn == 4 ? f(fd) + ", " + f(ft) : f(fd) + ", " + f(fs) + ", " + f(ft);
					case 5, 6, 7, 36 -> f(fd) + ", " + f(fs);
					case 24, 25, 26, 30, 31, 48, 50, 52, 54 -> f(fs) + ", " + f(ft);
					default -> f(fd) + ", " + f(fs) + ", " + f(ft);
				};
				return new Insn(pc, w, m, ops, Kind.OTHER, -1, -1, -1, 0, 0, false);
			}
			case 20:
				if (fn == 32) {
					return new Insn(pc, w, "cvt.s.w", f(fd) + ", " + f(fs), Kind.OTHER, -1, -1, -1, 0, 0, false);
				}
				return inv(w, pc);
			default:
				return inv(w, pc);
		}
	}

	private static Insn cop2(int w, int pc, int rs, int rt, int rd) {
		return switch (rs) {
			case 1 -> new Insn(pc, w, "qmfc2", r(rt) + ", vf" + rd, Kind.OTHER, rt, -1, -1, 0, 0, false);
			case 2 -> new Insn(pc, w, "cfc2", r(rt) + ", vi" + rd, Kind.OTHER, rt, -1, -1, 0, 0, false);
			case 5 -> new Insn(pc, w, "qmtc2", r(rt) + ", vf" + rd, Kind.OTHER, -1, rt, -1, 0, 0, false);
			case 6 -> new Insn(pc, w, "ctc2", r(rt) + ", vi" + rd, Kind.OTHER, -1, rt, -1, 0, 0, false);
			case 8 -> {
				int t = pc + 4 + (((short) w) << 2);
				yield new Insn(pc, w, new String[]{"bc2f", "bc2t", "bc2fl", "bc2tl"}[rt & 3], hex(t), Kind.BRANCH, -1, -1,
						-1, 0, t, (rt & 2) != 0);
			}
			default -> new Insn(pc, w, "vu0", String.format("0x%07x", w & 0x1FFFFFF), Kind.OTHER, -1, -1, -1, 0, 0,
					false);
		};
	}

	private static Insn mmi(int w, int pc, int rs, int rt, int rd, int sa, int fn) {
		String m = switch (fn) {
			case 0 -> "madd";
			case 1 -> "maddu";
			case 4 -> "plzcw";
			case 16 -> "mfhi1";
			case 17 -> "mthi1";
			case 18 -> "mflo1";
			case 19 -> "mtlo1";
			case 24 -> "mult1";
			case 25 -> "multu1";
			case 26 -> "div1";
			case 27 -> "divu1";
			case 32 -> "madd1";
			case 33 -> "maddu1";
			case 48 -> "pmfhl";
			case 49 -> "pmthl";
			case 8 -> mmiSub(0, sa);
			case 9 -> mmiSub(2, sa);
			case 40 -> mmiSub(1, sa);
			case 41 -> mmiSub(3, sa);
			default -> "mmi." + fn + "." + sa;
		};
		if (m.equals("por") && rt == 0) {
			return new Insn(pc, w, "move", r(rd) + ", " + r(rs), Kind.MOVE, rd, rs, -1, 0, 0, false);
		}
		return new Insn(pc, w, m, r(rd) + ", " + r(rs) + ", " + r(rt), Kind.OTHER, rd, rs, rt, 0, 0, false);
	}

	private static String mmiSub(int grp, int sa) {
		String[] g0 = {"paddw", "psubw", "pcgtw", "pmaxw", "paddh", "psubh", "pcgth", "pmaxh", "paddb", "psubb", "pcgtb",
				null, null, null, null, null, "paddsw", "psubsw", "pextlw", "ppacw", "paddsh", "psubsh", "pextlh", "ppach",
				"paddsb", "psubsb", "pextlb", "ppacb", null, null, "pext5", "ppac5"};
		String[] g1 = {null, "pabsw", "pceqw", "pminw", "padsbh", "pabsh", "pceqh", "pminh", null, null, "pceqb", null,
				null, null, null, null, "padduw", "psubuw", "pextuw", null, "padduh", "psubuh", "pextuh", null, "paddub",
				"psubub", "pextub", "qfsrv"};
		String[] g2 = {"pmaddw", null, "psllvw", "psrlvw", "pmsubw", null, null, null, "pmfhi", "pmflo", "pinth", null,
				"pmultw", "pdivw", "pcpyld", null, "pmaddh", "phmadh", "pand", "pxor", "pmsubh", "phmsbh", null, null,
				null, null, "pexeh", "prevh", "pmulth", "pdivbw", "pexew", "prot3w"};
		String[] g3 = {"pmadduw", null, null, "psravw", null, null, null, null, "pmthi", "pmtlo", "pinteh", null,
				"pmultuw", "pdivuw", "pcpyud", null, null, null, "por", "pnor", null, null, null, null, null, null,
				"pexch", "pcpyh", null, null, "pexcw", null};
		String[][] all = {g0, g1, g2, g3};
		String[] t = all[grp];
		String m = sa < t.length ? t[sa] : null;
		return m != null ? m : "mmi" + grp + "." + sa;
	}
}
