package devple.gow.tools.tweak;

import devple.gow.tools.tweak.TweakFile.Entry;
import devple.gow.tools.tweak.TweakFile.Kind;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * TWK 튜닝 테이블 명령.
 * <pre>
 *   tweak:dump  gow-rom/R_HERO0.WAD [out.txt]        이름 복원 덤프
 *   tweak:dump-all gow-rom work/exe/twk              모든 WAD → twk_<WAD>.txt
 *   tweak:stats gow-rom                              전체 WAD 의 TWK 통계 / 미해결 해시 수 (빈도순)
 *   tweak:dict  work/SCKA_300.02 gow-rom out.txt     후보 문자열 → 실제 해시와 일치하는 이름 사전 생성
 * </pre>
 * 사전은 리소스 {@code twk-names.txt} 에 내장한다 (tweak:dict 결과).
 * 이름을 못 찾았지만 코드에 상수로 박혀 있는 해시는 {@code twk-const.txt} 로 사용 위치를 표시한다 ("#해시(@코드주소)").
 */
public final class Cli {

	public static void main(String[] args) throws Exception {
		switch (args[0]) {
			case "dump" -> {
				Map<Integer, String> names = loadNames();
				WadFile wad = WadFile.load(Path.of(args[1]));
				if (args.length > 2) {
					try (PrintStream o = new PrintStream(Files.newOutputStream(Path.of(args[2])), false, "UTF-8")) {
						dump(wad, names, o);
					}
				} else {
					dump(wad, names, System.out);
				}
			}
			case "stats" -> stats(Path.of(args[1]));
			case "dict" -> dict(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
			case "dump-all" -> {
				Map<Integer, String> names = loadNames();
				Path outDir = Path.of(args[2]);
				Files.createDirectories(outDir);
				for (Path p : wads(Path.of(args[1]))) {
					WadFile w = WadFile.load(p);
					if (tweakTags(w).isEmpty()) {
						continue;
					}
					String n = p.getFileName().toString().replace(".WAD", "");
					try (PrintStream o = new PrintStream(Files.newOutputStream(outDir.resolve("twk_" + n + ".txt")), false,
							"UTF-8")) {
						dump(w, names, o);
					}
				}
			}
			default -> throw new IllegalArgumentException("unknown: " + args[0]);
		}
	}

	/** 내장 사전 (이름 → 해시 계산). */
	public static Map<Integer, String> loadNames() throws IOException {
		Map<Integer, String> m = new HashMap<>();
		try (InputStream in = Cli.class.getResourceAsStream("twk-names.txt")) {
			if (in == null) {
				return m;
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
			for (String s; (s = r.readLine()) != null; ) {
				if (!s.isEmpty() && !s.startsWith("#")) {
					m.putIfAbsent(TweakFile.hash(s), s);
				}
			}
		}
		return m;
	}

	static List<WadTag> tweakTags(WadFile wad) {
		return wad.tags().stream().filter(t -> t.tag() == WadTagType.TWEAK && t.hasData()).toList();
	}

	private static void dump(WadFile wad, Map<Integer, String> names, PrintStream o) {
		for (WadTag t : tweakTags(wad)) {
			TweakFile tf = TweakFile.parse(t.data());
			o.printf("# %s @%08x size=%x count=%d%n", t.name(), t.offset(), t.size(), tf.count());
			for (Entry e : tf.entries()) {
				String ind = "\t".repeat(Math.max(0, e.depth()));
				switch (e.kind()) {
					case PATH -> o.printf("%n%s%n", e.text());
					case GROUP -> o.printf("%s%s {%n", ind, e.text());
					case END -> o.printf("%s}%n", ind);
					case NUMBER -> o.printf("%s%s = %s%n", ind, key(names, e.hash()), number(e.raw()));
					case STRING -> o.printf("%s%s = \"%s\"%n", ind, key(names, e.hash()), e.text());
					case UNKNOWN -> o.printf("%s?? type 0x%02x @%x%n", ind, e.raw(), e.offset());
				}
			}
			o.println();
		}
	}

	private static String key(Map<Integer, String> names, int h) {
		String n = names.get(h);
		if (n != null) {
			return "\"" + n + "\"";
		}
		String at = CONSTS.get(h);
		return at != null ? String.format("#%08x(@%s)", h, at) : String.format("#%08x", h);
	}

	/** 이름 미상이지만 코드에 상수로 등장하는 해시 → 코드 주소. */
	private static final Map<Integer, String> CONSTS = loadConsts();

	private static Map<Integer, String> loadConsts() {
		Map<Integer, String> m = new HashMap<>();
		try (InputStream in = Cli.class.getResourceAsStream("twk-const.txt")) {
			if (in != null) {
				BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
				for (String s; (s = r.readLine()) != null; ) {
					if (!s.startsWith("#")) {
						String[] p = s.split("\\s+");
						m.merge(Integer.parseUnsignedInt(p[0], 16), p[1], (x, y) -> x + "," + y);
					}
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return m;
	}

	/** float 로 보이면 float, 아니면 정수. */
	static String number(int raw) {
		if (raw == 0) {
			return "0";
		}
		int exp = (raw >>> 23) & 0xFF;
		if (exp >= 0x60 && exp <= 0x9F) {
			float f = Float.intBitsToFloat(raw);
			return f == Math.rint(f) && Math.abs(f) < 1e7 ? String.format("%.1f", f) : Float.toString(f);
		}
		return Integer.toString(raw);
	}

	private static List<Path> wads(Path dir) throws IOException {
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> p.toString().endsWith(".WAD")).sorted().toList();
		}
	}

	private static void stats(Path dir) throws IOException {
		Map<Integer, String> names = loadNames();
		Set<Integer> all = new HashSet<>(), unresolved = new HashSet<>();
		Map<Integer, Integer> freq = new HashMap<>();
		Map<Integer, String> where = new HashMap<>();
		Map<String, Integer> unknownTypes = new TreeMap<>();
		int tables = 0;
		for (Path p : wads(dir)) {
			WadFile w = WadFile.load(p);
			for (WadTag t : tweakTags(w)) {
				tables++;
				for (Entry e : TweakFile.parse(t.data()).entries()) {
					if (e.kind() == Kind.NUMBER || e.kind() == Kind.STRING) {
						all.add(e.hash());
						if (!names.containsKey(e.hash())) {
							unresolved.add(e.hash());
							freq.merge(e.hash(), 1, Integer::sum);
							where.putIfAbsent(e.hash(), p.getFileName() + ":" + t.name());
						}
					} else if (e.kind() == Kind.UNKNOWN) {
						unknownTypes.merge(p.getFileName() + ":" + t.name() + String.format(":0x%02x", e.raw()), 1,
								Integer::sum);
					}
				}
			}
		}
		System.out.printf("tables=%d uniqueKeys=%d resolved=%d unresolved=%d%n", tables, all.size(),
				all.size() - unresolved.size(), unresolved.size());
		unknownTypes.forEach((k, v) -> System.out.println("unknown record " + k));
		freq.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(60)
				.forEach(e -> System.out.printf("  #%08x x%d  %s%n", e.getKey(), e.getValue(), where.get(e.getKey())));
	}

	/**
	 * 이름 사전 생성. 해시가 h = h*127 + c 이므로 접미사를 "벗겨낼" 수 있다:
	 * hash(p + s) = hash(p) * 127^|s| + tail(s)  →  hash(p) = (K - tail(s)) * inv(127^|s|)  (127 은 홀수라 mod 2^32 역원 존재).
	 * <ol>
	 *   <li>직접 후보: 실행 파일 문자열(+접미사·숫자 변형), WAD 내 모든 인쇄 가능 문자열, TWK 그룹/값 문자열</li>
	 *   <li>합성 후보: 실행 파일의 '_'/' ' 로 시작하는 문자열을 접미사로, 직접 후보 집합을 접두사로.
	 *       우연 일치를 거르기 위해 3개 이상의 키를 해결한 접미사만 채택</li>
	 * </ol>
	 */
	private static final java.util.regex.Pattern IDENT = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_ .\\-]*");

	private static void dict(Path exe, Path romDir, Path out) throws IOException {
		Set<Integer> target = new HashSet<>();
		Set<String> base = new HashSet<>();
		Set<String> wadStrs = new HashSet<>();
		for (Path p : wads(romDir)) {
			byte[] raw = Files.readAllBytes(p);
			printable(raw, 3, 48, x -> {
				if (IDENT.matcher(x).matches()) {
					wadStrs.add(x);
				}
			});
			WadFile w = WadFile.load(p);
			for (WadTag t : tweakTags(w)) {
				for (Entry e : TweakFile.parse(t.data()).entries()) {
					if (e.kind() == Kind.NUMBER || e.kind() == Kind.STRING) {
						target.add(e.hash());
					}
					if (e.text() != null) {
						base.add(e.text());
						base.addAll(List.of(e.text().split("/")));
					}
				}
			}
		}
		Set<String> exeStrs = new HashSet<>();
		printable(Files.readAllBytes(exe), 2, 256, exeStrs::add);
		Set<String> suffixes = new TreeSet<>();
		for (String s : exeStrs) {
			for (int k = 0; k + 2 <= s.length(); k++) {
				base.add(s.substring(k));
			}
			if ((s.startsWith("_") || s.startsWith(" ")) && s.length() >= 3 && s.length() <= 24
					&& IDENT.matcher(s.substring(1)).matches()) {
				suffixes.add(s);
			}
		}
		Set<String> found = new TreeSet<>();
		Map<Integer, String> prefix = new HashMap<>();
		for (String s : wadStrs) {
			prefix.putIfAbsent(TweakFile.hash(s), s);
			tryAdd(s, target, found);
			// 일부 테이블(WadInfo 그룹 등)은 대문자 해시(sub_001758e0)로 GO 이름을 키로 쓴다
			tryAdd(s.toUpperCase(java.util.Locale.ROOT), target, found);
		}
		// printf 형식 문자열: %d/%2d/%02d/%03d/%i 를 0..63 으로 치환
		java.util.regex.Pattern fmt = java.util.regex.Pattern.compile("%(0?\\d?)[di]");
		for (String s : exeStrs) {
			java.util.regex.Matcher m = fmt.matcher(s);
			if (!m.find() || s.contains("%s") || s.indexOf('%', m.end()) >= 0) {
				continue;
			}
			for (int n = 0; n < 64; n++) {
				String v = s.substring(0, m.start()) + String.format("%" + m.group(1) + "d", n) + s.substring(m.end());
				tryAdd(v, target, found);
				prefix.putIfAbsent(TweakFile.hash(v), v);
			}
		}
		for (String s : base) {
			prefix.putIfAbsent(TweakFile.hash(s), s);
			tryAdd(s, target, found);
			for (String sep : new String[]{"", "_", " "}) {
				for (int n = 0; n < 100; n++) {
					tryAdd(s + sep + n, target, found);
					if (n < 10) {
						tryAdd(s + sep + "0" + n, target, found);
					}
				}
			}
		}
		Set<Integer> hit = new HashSet<>();
		found.forEach(s -> hit.add(TweakFile.hash(s)));
		for (String suf : suffixes) {
			int tail = TweakFile.hash(suf);
			int inv = inverse(pow127(suf.length()));
			List<String> got = new ArrayList<>();
			for (int k : target) {
				if (hit.contains(k)) {
					continue;
				}
				String p = prefix.get((k - tail) * inv);
				if (p != null) {
					got.add(p + suf);
				}
			}
			if (got.size() >= 10) {
				System.out.printf("suffix \"%s\" -> %d%n", suf, got.size());
				found.addAll(got);
			}
		}
		hit.clear();
		found.forEach(s -> hit.add(TweakFile.hash(s)));
		// 단어 조합: 실행 파일 문자열을 단어로 쪼갠 뒤 2단어 조합 (""/" " 구분자). 대조군(해시 XOR)으로 우연 일치 수를 함께 출력
		Set<String> words = new TreeSet<>();
		for (String s : exeStrs) {
			for (String w : s.split("[^A-Za-z0-9]+|(?<=[a-z])(?=[A-Z])")) {
				if (w.length() >= 2 && w.length() <= 16 && Character.isLetter(w.charAt(0))) {
					words.add(w);
					words.add(Character.toUpperCase(w.charAt(0)) + w.substring(1));
				}
			}
		}
		Set<Integer> rest = new HashSet<>(target);
		rest.removeAll(hit);
		Set<Integer> control = new HashSet<>();
		rest.forEach(k -> control.add(k ^ 0x5A5A5A5A));
		int ctl = 0;
		List<String> wl = new ArrayList<>(words);
		List<String> combo = new ArrayList<>();
		for (String a : wl) {
			for (String sep : new String[]{"", " ", "_"}) {
				int ha = TweakFile.hash(a + sep);
				for (String b : wl) {
					int h = ha;
					for (int i = 0; i < b.length(); i++) {
						h = h * 127 + b.charAt(i);
					}
					if (rest.contains(h)) {
						combo.add(a + sep + b);
					}
					if (control.contains(h)) {
						ctl++;
					}
				}
			}
		}
		System.out.printf("words=%d combo hits=%d control=%d%n", wl.size(), combo.size(), ctl);
		hit.clear();
		found.forEach(s -> hit.add(TweakFile.hash(s)));
		combo.forEach(s -> hit.add(TweakFile.hash(s)));
		System.out.printf("targets=%d candidates=%d suffixes=%d resolved=%d%n", target.size(), base.size() + wadStrs.size(),
				suffixes.size(), hit.size());
		// 이름을 못 찾은 해시 중 실행 파일에 즉치 상수(lui+ori/addiu)로 등장하는 것 → 사용 위치 기록
		Set<Integer> unresolved = new HashSet<>(target);
		unresolved.removeAll(hit);
		List<String> constLines = new ArrayList<>();
		constLines.add("# 이름 미상 TWK 키 해시가 실행 파일 코드에 상수로 등장하는 위치 (tweak:dict 생성): hash addr");
		devple.gow.tools.exe.Elf elf = devple.gow.tools.exe.Elf.load(exe);
		devple.gow.tools.exe.Elf.Section text = elf.section(".text");
		for (int pc = text.addr(); pc < text.addr() + text.size(); pc += 4) {
			var a = devple.gow.tools.exe.R5900.decode(elf.u32(pc), pc);
			if (a.kind() != devple.gow.tools.exe.R5900.Kind.LUI) {
				continue;
			}
			for (int q = pc + 4; q < pc + 40 && q < text.addr() + text.size(); q += 4) {
				var b = devple.gow.tools.exe.R5900.decode(elf.u32(q), q);
				if (b.rs() == a.rd() && (b.kind() == devple.gow.tools.exe.R5900.Kind.ORI
						|| b.kind() == devple.gow.tools.exe.R5900.Kind.ADDIU)) {
					int v = b.kind() == devple.gow.tools.exe.R5900.Kind.ORI ? a.imm() | b.imm() : a.imm() + b.imm();
					if (unresolved.contains(v)) {
						constLines.add(String.format("%08x %08x", v, q));
					}
					break;
				}
			}
		}
		Files.write(out.resolveSibling("twk-const.txt"), constLines, StandardCharsets.UTF_8);
		System.out.printf("code-constant keys=%d%n", constLines.size() - 1);
		List<String> lines = new ArrayList<>();
		lines.add("# TWK 키 이름 사전 (tweak:dict 생성). 해시 h = h*127 + c (실행 파일 sub_001758a0)");
		lines.add("# 직접 일치 + 접미사(_Tween/_Cycle) 역산");
		lines.addAll(found);
		lines.add("# 2단어 조합 (추정: 대조군 기준 우연 일치 약 " + ctl + "/" + combo.size() + ")");
		lines.addAll(new TreeSet<>(combo));
		Files.write(out, lines, StandardCharsets.UTF_8);
	}

	static int pow127(int n) {
		int r = 1;
		for (int i = 0; i < n; i++) {
			r *= 127;
		}
		return r;
	}

	/** 홀수의 mod 2^32 곱셈 역원 (뉴턴 반복). */
	static int inverse(int a) {
		int x = a;
		for (int i = 0; i < 5; i++) {
			x *= 2 - a * x;
		}
		return x;
	}

	/** 인쇄 가능한 ASCII 연속 구간을 수집. */
	static void printable(byte[] b, int min, int max, java.util.function.Consumer<String> sink) {
		for (int i = 0; i < b.length; i++) {
			int j = i;
			while (j < b.length && b[j] >= 0x20 && b[j] < 0x7F) {
				j++;
			}
			if (j - i >= min && j - i <= max) {
				sink.accept(new String(b, i, j - i, StandardCharsets.ISO_8859_1));
			}
			i = j;
		}
	}

	private static void tryAdd(String s, Set<Integer> target, Set<String> found) {
		if (target.contains(TweakFile.hash(s))) {
			found.add(s);
		}
	}
}
