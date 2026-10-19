package devple.gow.tools.exe;

import devple.gow.tools.exe.R5900.Insn;
import devple.gow.tools.exe.R5900.Kind;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 심볼 없는 EE 실행 파일의 정적 분석.
 * <ul>
 *   <li>함수 경계: jal 대상 + 데이터 영역의 코드 포인터 + "jr ra" 뒤 스택 프롤로그</li>
 *   <li>상수 전파: 함수 내 선형 스캔으로 lui/addiu/ori/gp 상대 주소를 복원해 참조(xref)를 수집</li>
 *   <li>이름: 리소스 {@code names.txt} (주소 이름 [설명]) 로 수동 부여한 이름을 덮어씀</li>
 * </ul>
 */
public final class ExeAnalysis {

	/** 참조 종류. */
	public enum RefKind { CALL, ADDR, LOAD, STORE, DATA_PTR }

	/** from(명령 주소 또는 데이터 주소) → to. */
	public record Ref(int from, int to, RefKind kind) {
	}

	/** 함수. end 는 배타적. */
	public record Func(int start, int end) {
	}

	private final Elf elf;
	private final int textStart, textEnd;
	private final Insn[] insns;
	private final int gp;
	private final Elf.Section rodata;
	private final TreeMap<Integer, Func> funcs = new TreeMap<>();
	private final List<Ref> refs = new ArrayList<>();
	private final Map<Integer, List<Ref>> refsTo = new HashMap<>();
	private final Map<Integer, List<Ref>> refsFrom = new HashMap<>();
	private final Map<Integer, List<Ref>> refsAt = new HashMap<>();
	private final Map<Integer, int[]> callArgs = new HashMap<>();
	private final Map<Integer, String> names = new HashMap<>();
	private final Map<Integer, String> notes = new HashMap<>();

	public ExeAnalysis(Elf elf) {
		this.elf = elf;
		Elf.Section text = elf.section(".text");
		textStart = text.addr();
		textEnd = text.addr() + text.size();
		rodata = elf.section(".rodata");
		insns = new Insn[(textEnd - textStart) / 4];
		for (int i = 0; i < insns.length; i++) {
			int pc = textStart + i * 4;
			insns[i] = R5900.decode(elf.u32(pc), pc);
		}
		gp = findGp();
		findFunctions(Set.of());
		propagate();
		// 2차: lui/addiu 로 주소만 취해지는 코드(콜백 등록 등)도 함수 시작으로 추가 후 재분석
		Set<Integer> extra = new HashSet<>();
		for (Ref r : refs) {
			if (r.kind() == RefKind.ADDR && isText(r.to()) && !funcs.containsKey(r.to()) && looksLikeStart(r.to())) {
				extra.add(r.to());
			}
		}
		if (!extra.isEmpty()) {
			funcs.clear();
			refs.clear();
			refsTo.clear();
			refsFrom.clear();
			refsAt.clear();
			callArgs.clear();
			findFunctions(extra);
			propagate();
		}
		scanDataPointers();
		loadNames();
	}

	public Elf elf() {
		return elf;
	}

	public int gp() {
		return gp;
	}

	public boolean isText(int va) {
		return Integer.compareUnsigned(va - textStart, textEnd - textStart) < 0 && (va & 3) == 0;
	}

	public Insn insn(int pc) {
		return insns[(pc - textStart) / 4];
	}

	public TreeMap<Integer, Func> funcs() {
		return funcs;
	}

	public List<Ref> refs() {
		return refs;
	}

	public List<Ref> refsTo(int a) {
		return refsTo.getOrDefault(a, List.of());
	}

	public List<Ref> refsFrom(int funcStart) {
		return refsFrom.getOrDefault(funcStart, List.of());
	}

	/**
	 * 호출 지점의 인자 a0..a3 (상수 전파 결과). [0..3] = 값, [4] = 알려진 레지스터 비트마스크 (bit0 = a0).
	 */
	public int[] callArgs(int callPc) {
		return callArgs.get(callPc);
	}

	/** 해당 명령/데이터 주소에서 나가는 참조. */
	public List<Ref> refsAt(int from) {
		return refsAt.getOrDefault(from, List.of());
	}

	public Func funcAt(int pc) {
		var e = funcs.floorEntry(pc);
		return e != null && pc < e.getValue().end() ? e.getValue() : null;
	}

	/** 사람이 붙인 이름이 있으면 그것, 없으면 sub_XXXXXXXX. */
	public String name(int a) {
		String n = names.get(a);
		if (n != null) {
			return n;
		}
		return isText(a) ? String.format("sub_%08x", a) : String.format("dat_%08x", a);
	}

	public boolean named(int a) {
		return names.containsKey(a);
	}

	public String note(int a) {
		return notes.get(a);
	}

	/** _start 의 "lui gp / addiu gp" 로 gp 값을 얻는다. */
	private int findGp() {
		int[] v = new int[32];
		for (int pc = elf.entry(); pc < elf.entry() + 0x200; pc += 4) {
			Insn i = insn(pc);
			switch (i.kind()) {
				case LUI -> v[i.rd()] = i.imm();
				case ADDIU -> v[i.rd()] = v[i.rs()] + i.imm();
				case ORI -> v[i.rd()] = v[i.rs()] | i.imm();
				case MOVE -> {
					v[i.rd()] = v[i.rs()];
					if (i.rd() == R5900.GP) {
						return v[i.rd()];
					}
				}
				default -> {
				}
			}
			if (i.rd() == R5900.GP && i.kind() != Kind.LUI) {
				return v[R5900.GP];
			}
		}
		return 0;
	}

	private static boolean isPrologue(Insn i) {
		return i.kind() == Kind.ADDIU && i.rd() == R5900.SP && i.rs() == R5900.SP && i.imm() < 0;
	}

	private boolean looksLikeStart(int v) {
		int k = (v - textStart) / 4;
		boolean afterRet = k >= 2 && insns[k - 2].kind() == Kind.JR && insns[k - 2].rs() == R5900.RA;
		return isPrologue(insns[k]) || afterRet;
	}

	private void findFunctions(Set<Integer> extra) {
		TreeSet<Integer> starts = new TreeSet<>(extra);
		starts.add(textStart);
		starts.add(elf.entry());
		for (Insn i : insns) {
			if (i.kind() == Kind.CALL && i.mnem().equals("jal") && isText(i.target())) {
				starts.add(i.target());
			}
		}
		// jr ra + 지연 슬롯 뒤의 프롤로그
		for (int k = 0; k + 2 < insns.length; k++) {
			Insn i = insns[k];
			if (i.kind() == Kind.JR && i.rs() == R5900.RA) {
				int j = k + 2;
				while (j < insns.length && insns[j].word() == 0) {
					j++;
				}
				if (j < insns.length && isPrologue(insns[j])) {
					starts.add(insns[j].pc());
				}
			}
		}
		// 데이터 영역의 코드 포인터 (vtable, 콜백 테이블)
		for (int a = textEnd; a + 4 <= elf.fileEnd(); a += 4) {
			int v = elf.u32(a);
			if (isText(v) && v != textStart) {
				Insn t = insn(v);
				int prev = (v - textStart) / 4 - 2;
				boolean afterRet = prev >= 0 && insns[prev].kind() == Kind.JR && insns[prev].rs() == R5900.RA;
				if (isPrologue(t) || afterRet) {
					starts.add(v);
				}
			}
		}
		Integer prev = null;
		for (int s : starts) {
			if (prev != null) {
				funcs.put(prev, new Func(prev, s));
			}
			prev = s;
		}
		funcs.put(prev, new Func(prev, textEnd));
	}

	private void addRef(int fn, int from, int to, RefKind k) {
		Ref r = new Ref(from, to, k);
		refs.add(r);
		refsTo.computeIfAbsent(to, x -> new ArrayList<>()).add(r);
		refsFrom.computeIfAbsent(fn, x -> new ArrayList<>()).add(r);
		refsAt.computeIfAbsent(from, x -> new ArrayList<>()).add(r);
	}

	private static final int CLOBBER = 0b0000_0011_0000_0000_1111_1111_1111_1110; // at,v0-v1,a0-a3,t0-t7,t8,t9

	/** 함수별 선형 상수 전파. 주소 상수와 메모리 참조를 Ref 로 기록한다. */
	private void propagate() {
		int[] val = new int[32];
		long known;
		for (Func f : funcs.values()) {
			known = 1L | (1L << R5900.GP);
			val[0] = 0;
			val[R5900.GP] = gp;
			int clobberAt = -1;
			for (int pc = f.start(); pc < f.end(); pc += 4) {
				Insn i = insn(pc);
				if (pc == clobberAt) {
					// 지연 슬롯까지 반영된 인자 레지스터 a0..a3 스냅숏
					int[] args = new int[5];
					for (int r = 0; r < 4; r++) {
						args[r] = val[4 + r];
					}
					args[4] = (int) (known >> 4) & 0xF;
					callArgs.put(pc - 8, args);
					known &= ~(CLOBBER & 0xFFFFFFFFL);
					clobberAt = -1;
				}
				switch (i.kind()) {
					case LUI -> {
						val[i.rd()] = i.imm();
						known |= 1L << i.rd();
					}
					case ADDIU, ORI -> {
						if ((known >> i.rs() & 1) != 0) {
							int v = i.kind() == Kind.ORI ? val[i.rs()] | i.imm() : val[i.rs()] + i.imm();
							if (i.rs() != 0 && elf.inImage(v)) {
								addRef(f.start(), pc, v, RefKind.ADDR);
							}
							if (i.rd() != 0) {
								val[i.rd()] = v;
								known |= 1L << i.rd();
							}
						} else if (i.rd() > 0) {
							known &= ~(1L << i.rd());
						}
					}
					case MOVE -> {
						if ((known >> i.rs() & 1) != 0) {
							val[i.rd()] = val[i.rs()];
							known |= 1L << i.rd();
						} else {
							known &= ~(1L << i.rd());
						}
					}
					case LOAD, STORE, FLOAD, FSTORE -> {
						if ((known >> i.rs() & 1) != 0 && i.rs() != R5900.SP) {
							int a = val[i.rs()] + i.imm();
							if (elf.inImage(a)) {
								boolean st = i.kind() == Kind.STORE || i.kind() == Kind.FSTORE;
								addRef(f.start(), pc, a, st ? RefKind.STORE : RefKind.LOAD);
							}
						}
						if (i.kind() == Kind.LOAD && i.rd() > 0) {
							known &= ~(1L << i.rd());
							// .rodata 의 상수 워드(lw) 는 값으로 취급 (함수 포인터 테이블 등)
							if (i.mnem().equals("lw") && (known >> i.rs() & 1) != 0 || i.mnem().equals("lw") && i.rs() == 0) {
								int a = val[i.rs()] + i.imm();
								if (rodata.contains(a) && (a & 3) == 0) {
									val[i.rd()] = elf.u32(a);
									known |= 1L << i.rd();
								}
							}
						}
					}
					case CALL -> {
						if (isText(i.target())) {
							addRef(f.start(), pc, i.target(), RefKind.CALL);
						}
						clobberAt = pc + 8;
					}
					case JALR -> {
						if ((known >> i.rs() & 1) != 0 && isText(val[i.rs()])) {
							addRef(f.start(), pc, val[i.rs()], RefKind.CALL);
						}
						clobberAt = pc + 8;
					}
					case JUMP -> {
						if (isText(i.target()) && funcs.containsKey(i.target())) {
							addRef(f.start(), pc, i.target(), RefKind.CALL); // 꼬리 호출
						}
					}
					default -> {
						if (i.rd() > 0) {
							known &= ~(1L << i.rd());
						}
					}
				}
			}
		}
	}

	/** .data/.rodata 의 워드 중 코드/문자열을 가리키는 포인터. */
	private void scanDataPointers() {
		for (int a = textEnd; a + 4 <= elf.fileEnd(); a += 4) {
			int v = elf.u32(a);
			if (funcs.containsKey(v) || (elf.mapped(v) && !isText(v) && v > textEnd && elf.cstring(v, 3) != null)) {
				addRef(-1, a, v, RefKind.DATA_PTR);
			}
		}
	}

	private void loadNames() {
		try (InputStream in = ExeAnalysis.class.getResourceAsStream("names.txt")) {
			if (in == null) {
				return;
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			for (String line; (line = r.readLine()) != null; ) {
				line = line.strip();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] p = line.split("\\s+", 3);
				int a = Integer.parseUnsignedInt(p[0].replaceFirst("^0x", ""), 16);
				names.put(a, p[1]);
				if (p.length > 2) {
					notes.put(a, p[2]);
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 주소 값에 대한 짧은 설명: 함수 이름 / 문자열 / 데이터 이름. */
	public String describe(int a) {
		if (funcs.containsKey(a) || names.containsKey(a)) {
			return name(a);
		}
		if (!isText(a)) {
			String s = elf.cstring(a, 2);
			if (s != null) {
				return "\"" + s.replace("\n", "\\n") + "\"";
			}
		}
		return null;
	}
}
