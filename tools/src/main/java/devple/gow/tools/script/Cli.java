package devple.gow.tools.script;

import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 레벨 스크립트(ESC) 엔티티 도구 (13 문서).
 * <pre>
 *   script:entities &lt;wad&gt;      엔티티별 슬롯 이름 + 상수 값, 소유 오브젝트 / 인스턴스 / ENV 볼륨 연결
 *   script:entities &lt;wad&gt; --code   상수가 아닌 핸들러 명령까지 출력
 *   script:survey   &lt;romDir&gt;   전 레벨 집계: ENV 없는 진입/이탈 센서, 다중 배치 소유자, 슬롯 상수 분포
 * </pre>
 */
public final class Cli {

	private Cli() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: script:<entities|survey> <wad|romDir>");
			System.exit(1);
		}
		switch (args[0]) {
			case "entities" -> {
				showCode = java.util.Arrays.asList(args).contains("--code");
				entities(Path.of(args[1]));
			}
			case "survey" -> survey(Path.of(args[1]));
			default -> throw new IllegalArgumentException(args[0]);
		}
	}

	/** ESC 리소스 이름 → 스크립트. */
	static Map<String, EscScript> scripts(WadFile wad) {
		Map<String, EscScript> m = new TreeMap<>();
		for (WadTag t : wad.resources()) {
			if (t.magic() == EscScript.MAGIC && t.name().startsWith("ESC_")) {
				EscScript s = new EscScript(t.data());
				if (s.target.equals("SCR_Entities")) {
					m.putIfAbsent(t.name(), s);
				}
			}
		}
		return m;
	}

	/** --code: 상수가 아닌 핸들러의 명령을 모두 출력. */
	private static boolean showCode;

	/** 상수 슬롯이면 값, 아니면 "code". */
	static String constant(EscScript.Entity e, EscScript.Handler h) {
		List<EscOp> c = h.code();
		if (c.size() == 3 && c.get(1).text().equals("return")) {
			String t = c.getFirst().text();
			int sp = t.indexOf(' ');
			return sp > 0 ? t.substring(sp + 1) : t;
		}
		if (c.size() == 4 && c.get(1).text().equals("int2float") && c.get(2).text().equals("return")) {
			return c.getFirst().text().substring(c.getFirst().text().indexOf(' ') + 1);
		}
		return "code";
	}

	private static List<String> owners(LevelData lv, String esc) {
		List<String> out = new ArrayList<>();
		for (LevelLayout.Placement p : lv.layout.placements()) {
			if (p.object().refs().contains(esc)) {
				out.add(p.instance().name());
			}
		}
		return out;
	}

	private static List<String> envs(LevelData lv, List<String> owners) {
		List<String> out = new ArrayList<>();
		for (LevelData.PlacedVolume v : lv.volumes) {
			if (v.hull().kind == 2 && owners.contains(v.owner())) {
				out.add(v.hull().name);
			}
		}
		return out;
	}

	private static void entities(Path wadPath) throws Exception {
		WadFile wad = WadFile.load(wadPath);
		LevelData lv = new LevelData(wad);
		for (Map.Entry<String, EscScript> s : scripts(wad).entrySet()) {
			List<String> own = owners(lv, s.getKey());
			System.out.printf("=== %s owners=%s env=%s%n", s.getKey(), own, envs(lv, own));
			for (EscScript.Entity e : s.getValue().entities) {
				System.out.printf(Locale.ROOT, "  #%d %-17s '%s' mode=%d region=%d joint=%d pos=(%.1f,%.1f,%.1f) targets=%s%n", e.id(),
						e.typeName(), e.name(), e.param(), e.baseId(), e.unk40(), e.matrix()[12], e.matrix()[13], e.matrix()[14], e.targets());
				for (EscScript.Handler h : e.handlers()) {
					String c = constant(e, h);
					System.out.printf("      %2d %-15s %s%n", h.id(), EntitySlots.name(e.type(), h.id()), c);
					if (showCode && c.equals("code")) {
						h.code().forEach(op -> System.out.println("           | " + op.text()));
					}
				}
			}
		}
	}

	private static void survey(Path rom) throws Exception {
		Map<String, Integer> counts = new TreeMap<>();
		List<String> notes = new ArrayList<>();
		try (var files = Files.list(rom)) {
			for (Path p : files.filter(f -> f.toString().endsWith(".WAD")).sorted().toList()) {
				WadFile wad = WadFile.load(p);
				Map<String, EscScript> sc = scripts(wad);
				if (sc.isEmpty()) {
					continue;
				}
				LevelData lv = new LevelData(wad);
				for (Map.Entry<String, EscScript> s : sc.entrySet()) {
					List<String> own = owners(lv, s.getKey());
					List<String> env = envs(lv, own);
					if (own.size() > 1) {
						counts.merge("multi-owner script", 1, Integer::sum);
					}
					if (own.isEmpty()) {
						counts.merge("unplaced script", 1, Integer::sum);
					}
					for (EscScript.Entity e : s.getValue().entities) {
						counts.merge("type " + e.typeName() + " mode " + e.param(), 1, Integer::sum);
						if ((e.type() == 0 || e.type() == 1) && env.isEmpty()) {
							counts.merge("entry/exit without ENV", 1, Integer::sum);
							if (notes.size() < 40) {
								notes.add(p.getFileName() + " " + e.name() + " owners=" + own);
							}
						}
						if (env.size() > 1 && (e.type() == 0 || e.type() == 1)) {
							counts.merge("entry/exit with >1 ENV", 1, Integer::sum);
						}
						for (EscScript.Handler h : e.handlers()) {
							if (h.id() == EntitySlots.index(e.type(), EntitySlots.FILTER) && e.name().startsWith("DeathZone")) {
								counts.merge("DeathZone " + e.typeName() + " filter " + constant(e, h), 1, Integer::sum);
								if (e.type() == 0 && constant(e, h).equals("1")) {
									notes.add("hero DeathZone: " + p.getFileName() + " " + e.name() + " env=" + env);
								}
							}
							if (h.id() >= EntitySlots.of(e.type()).size()) {
								counts.merge("slot out of table " + e.typeName() + ":" + h.id(), 1, Integer::sum);
							}
						}
						if (e.type() == 8) {
							for (EscScript.Handler h : e.handlers()) {
								if (h.id() == 0) {
									counts.merge("event " + constant(e, h) + " mode " + e.param(), 1, Integer::sum);
								}
							}
						}
					}
				}
			}
		}
		counts.forEach((k, v) -> System.out.printf("%6d  %s%n", v, k));
		notes.forEach(n -> System.out.println("  " + n));
	}
}
