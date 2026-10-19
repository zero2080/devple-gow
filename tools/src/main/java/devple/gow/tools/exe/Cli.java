package devple.gow.tools.exe;

import devple.gow.tools.exe.ExeAnalysis.Func;
import devple.gow.tools.exe.ExeAnalysis.Ref;
import devple.gow.tools.exe.ExeAnalysis.RefKind;
import devple.gow.tools.exe.R5900.Insn;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 실행 파일 분석 명령.
 * <pre>
 *   exe:listing work/SCKA_300.02 work/exe/SCKA.asm   전체 주석 디스어셈블리
 *   exe:funcs   work/SCKA_300.02 work/exe/funcs.txt  함수 목록 (크기, 호출자 수, 참조 문자열)
 *   exe:strings work/SCKA_300.02 work/exe/strings.txt 문자열 → 참조 함수
 *   exe:dis     work/SCKA_300.02 0x1234560           주소를 포함하는 함수 디스어셈블
 *   exe:xref    work/SCKA_300.02 0x2b3000            주소로의 참조
 *   exe:calls   work/SCKA_300.02 0x175c98            함수 호출 지점별 상수 인자 a0..a3
 *   exe:tree    work/SCKA_300.02 0x138d40 3          호출 트리 (깊이)
 *   exe:words   work/SCKA_300.02 0x29c880 64         데이터 워드 덤프 (포인터 해석)
 * </pre>
 */
public final class Cli {

	public static void main(String[] args) throws Exception {
		ExeAnalysis an = new ExeAnalysis(Elf.load(Path.of(args[1])));
		switch (args[0]) {
			case "listing" -> withOut(args, 2, o -> listing(an, o));
			case "funcs" -> withOut(args, 2, o -> funcs(an, o));
			case "strings" -> withOut(args, 2, o -> strings(an, o));
			case "dis" -> {
				for (int k = 2; k < args.length; k++) {
					Func f = an.funcAt(parse(args[k]));
					if (f != null) {
						dis(an, f, System.out);
					}
				}
			}
			case "xref" -> {
				for (Ref r : an.refsTo(parse(args[2]))) {
					Func f = an.funcAt(r.from());
					System.out.printf("%08x %-8s %s%n", r.from(), r.kind(), f != null ? an.name(f.start()) : "(data)");
				}
			}
			case "calls" -> {
				// 함수의 모든 호출 지점과 상수 인자
				for (int k = 2; k < args.length; k++) {
					int target = parse(args[k]);
					for (Ref r : an.refsTo(target)) {
						if (r.kind() != RefKind.CALL) {
							continue;
						}
						int[] a = an.callArgs(r.from());
						Func f = an.funcAt(r.from());
						StringBuilder sb = new StringBuilder();
						for (int i = 0; i < 4; i++) {
							sb.append(" a").append(i).append('=');
							if (a != null && (a[4] >> i & 1) != 0) {
								String d = an.describe(a[i]);
								sb.append(String.format("%x", a[i])).append(d != null ? "(" + d + ")" : "");
							} else {
								sb.append('?');
							}
						}
						System.out.printf("%08x %-14s%s%n", r.from(), f != null ? an.name(f.start()) : "?", sb);
					}
				}
			}
			case "tree" -> tree(an, parse(args[2]), args.length > 3 ? Integer.parseInt(args[3]) : 2, 0,
					new java.util.HashSet<>(), System.out);
			case "words" -> words(an, parse(args[2]), args.length > 3 ? Integer.parseInt(args[3]) : 64, System.out);
			default -> throw new IllegalArgumentException("unknown: " + args[0]);
		}
	}

	interface Body {
		void run(PrintStream o) throws Exception;
	}

	private static void withOut(String[] a, int idx, Body b) throws Exception {
		if (a.length > idx) {
			Path p = Path.of(a[idx]);
			if (p.getParent() != null) {
				Files.createDirectories(p.getParent());
			}
			try (PrintStream o = new PrintStream(Files.newOutputStream(p), false, "UTF-8")) {
				b.run(o);
			}
		} else {
			b.run(System.out);
		}
	}

	static int parse(String s) {
		return Integer.parseUnsignedInt(s.replaceFirst("^0x", ""), 16);
	}

	private static void listing(ExeAnalysis an, PrintStream o) {
		for (Func f : an.funcs().values()) {
			dis(an, f, o);
		}
	}

	static void dis(ExeAnalysis an, Func f, PrintStream o) {
		List<Ref> callers = an.refsTo(f.start());
		String callerStr = callers.stream().filter(r -> r.kind() == RefKind.CALL).limit(8)
				.map(r -> an.name(an.funcAt(r.from()) != null ? an.funcAt(r.from()).start() : r.from()))
				.distinct().collect(Collectors.joining(" "));
		long dataPtrs = callers.stream().filter(r -> r.kind() == RefKind.DATA_PTR).count();
		o.printf("%n;---- %s  [%08x-%08x] callers: %s%s%n", an.name(f.start()), f.start(), f.end(), callerStr,
				dataPtrs > 0 ? " dataptr@" + callers.stream().filter(r -> r.kind() == RefKind.DATA_PTR)
						.map(r -> String.format("%08x", r.from())).limit(4).collect(Collectors.joining(",")) : "");
		if (an.note(f.start()) != null) {
			o.printf(";     %s%n", an.note(f.start()));
		}
		for (int pc = f.start(); pc < f.end(); pc += 4) {
			Insn i = an.insn(pc);
			StringBuilder c = new StringBuilder();
			if (i.target() != 0) {
				String d = an.describe(i.target());
				if (d != null && i.kind() != R5900.Kind.BRANCH) {
					c.append(d);
				}
			}
			for (Ref r : an.refsAt(pc)) {
				if (r.kind() == RefKind.CALL && i.target() != 0) {
					continue;
				}
				String d = an.describe(r.to());
				if (c.length() > 0) {
					c.append(' ');
				}
				c.append(String.format("%s=%08x", r.kind() == RefKind.ADDR ? "&" : r.kind() == RefKind.LOAD ? "[" :
						r.kind() == RefKind.STORE ? "]" : ">", r.to()));
				if (d != null) {
					c.append(' ').append(d);
				} else if ((r.kind() == RefKind.LOAD) && an.elf().mapped(r.to()) && i.mnem().equals("lwc1")) {
					c.append(' ').append(an.elf().f32(r.to())).append('f');
				}
			}
			o.printf("%08x  %-36s%s%n", pc, i.text(), c.length() > 0 ? " ; " + c : "");
		}
	}

	/** 호출 트리 (중복 방문 생략). 각 함수의 참조 문자열 일부를 함께 표시. */
	private static void tree(ExeAnalysis an, int fn, int depth, int lvl, Set<Integer> seen, PrintStream o) {
		Func f = an.funcAt(fn);
		if (f == null) {
			return;
		}
		boolean first = seen.add(f.start());
		o.printf("%s%s [%x]%s %s%n", "  ".repeat(lvl), an.name(f.start()), f.end() - f.start(), first ? "" : " (*)",
				first ? strSummary(an, f, 6) : "");
		if (!first || lvl >= depth) {
			return;
		}
		Set<Integer> kids = new LinkedHashSet<>();
		for (Ref r : an.refsFrom(f.start())) {
			if (r.kind() == RefKind.CALL && an.funcAt(r.to()) != null && r.to() != f.start()) {
				kids.add(r.to());
			}
		}
		for (int k : kids) {
			if (!seen.contains(k)) {
				tree(an, k, depth, lvl + 1, seen, o);
			}
		}
	}

	static String strSummary(ExeAnalysis an, Func f, int max) {
		Set<String> strs = new LinkedHashSet<>();
		for (Ref r : an.refsFrom(f.start())) {
			if (r.kind() == RefKind.ADDR && !an.isText(r.to())) {
				String s = an.elf().cstring(r.to(), 3);
				if (s != null) {
					strs.add(s.replace("\n", "\\n"));
				}
			}
		}
		return strs.stream().limit(max).map(s -> "\"" + s + "\"").collect(Collectors.joining(" "));
	}

	private static void funcs(ExeAnalysis an, PrintStream o) {
		for (Func f : an.funcs().values()) {
			List<Ref> to = an.refsTo(f.start());
			long calls = to.stream().filter(r -> r.kind() == RefKind.CALL).count();
			long dptr = to.stream().filter(r -> r.kind() == RefKind.DATA_PTR).count();
			Set<String> strs = new LinkedHashSet<>();
			for (Ref r : an.refsFrom(f.start())) {
				if (r.kind() == RefKind.ADDR && !an.isText(r.to())) {
					String s = an.elf().cstring(r.to(), 3);
					if (s != null) {
						strs.add(s.replace("\n", "\\n"));
					}
				}
			}
			o.printf("%08x %6x c=%-4d p=%-2d %-28s %s%n", f.start(), f.end() - f.start(), calls, dptr, an.name(f.start()),
					strs.stream().limit(12).map(s -> "\"" + s + "\"").collect(Collectors.joining(" ")));
		}
	}

	private static void strings(ExeAnalysis an, PrintStream o) {
		TreeMap<Integer, Set<String>> m = new TreeMap<>();
		for (Ref r : an.refs()) {
			if ((r.kind() == RefKind.ADDR || r.kind() == RefKind.DATA_PTR) && !an.isText(r.to())
					&& an.elf().cstring(r.to(), 2) != null) {
				Func f = r.kind() == RefKind.ADDR ? an.funcAt(r.from()) : null;
				m.computeIfAbsent(r.to(), x -> new LinkedHashSet<>())
						.add(f != null ? an.name(f.start()) : String.format("data@%08x", r.from()));
			}
		}
		m.forEach((a, s) -> o.printf("%08x %-40s %s%n", a, "\"" + an.elf().cstring(a, 2).replace("\n", "\\n") + "\"",
				String.join(" ", s)));
	}

	private static void words(ExeAnalysis an, int a, int n, PrintStream o) {
		for (int k = 0; k < n; k++, a += 4) {
			int v = an.elf().u32(a);
			String d = an.describe(v);
			float fv = Float.intBitsToFloat(v);
			String fs = (Math.abs(fv) > 1e-4 && Math.abs(fv) < 1e6) ? String.format(" %gf", fv) : "";
			o.printf("%08x: %08x %s%s%n", a, v, d != null ? d : "", d == null ? fs : "");
		}
	}
}
