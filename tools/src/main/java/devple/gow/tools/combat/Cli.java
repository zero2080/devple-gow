package devple.gow.tools.combat;

import devple.gow.tools.combat.CombatFile.Attack;
import devple.gow.tools.combat.CombatFile.Branch;
import devple.gow.tools.combat.CombatFile.Event;
import devple.gow.tools.combat.CombatFile.Move;
import devple.gow.tools.tweak.TweakFile;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 전투 테이블 명령.
 * <pre>
 *   combat:dump    gow-rom/R_PERMK.WAD [out.txt]          기술/분기/공격/이벤트 전체 (애니 이름 복원)
 *   combat:tree    gow-rom/R_PERMK.WAD [ROOT|기술ID|이름] [깊이] [normal|god|berserk|air|all]
 *                  입력 분기만 따라간 콤보 트리 (기본 ROOT=대기 상태, 깊이 6, 지상 기본 문맥)
 *   combat:enemies gow-rom [out.md]                      AI1 템플릿(HP 등) + CombatFileSet + 공격 피해 요약 표
 * </pre>
 * 애니 이름은 TWK 사전(_Tween/_Cycle 제거)과 WAD 안의 식별자 문자열을 Hash127Upper 로 대조해 복원한다.
 */
public final class Cli {

	public static void main(String[] args) throws Exception {
		switch (args[0]) {
			case "dump" -> {
				Path wad = Path.of(args[1]);
				Names nm = Names.build(wad);
				try (PrintStream o = out(args.length > 2 ? args[2] : null)) {
					for (CombatFile cf : CombatFile.fromWad(WadFile.load(wad))) {
						dump(cf, nm, o);
					}
				}
			}
			case "tree" -> {
				Path wad = Path.of(args[1]);
				Names nm = Names.build(wad);
				List<CombatFile> files = CombatFile.fromWad(WadFile.load(wad));
				String root = args.length > 2 ? args[2] : "ROOT";
				int depth = args.length > 3 ? Integer.parseInt(args[3]) : 6;
				Ctx ctx = args.length > 4 ? Ctx.valueOf(args[4].toUpperCase(Locale.ROOT)) : Ctx.NORMAL;
				tree(files, nm, root, depth, ctx, System.out);
			}
			case "enemies" -> {
				try (PrintStream o = out(args.length > 2 ? args[2] : null)) {
					enemies(Path.of(args[1]), o);
				}
			}
			default -> throw new IllegalArgumentException("unknown: " + args[0]);
		}
	}

	private static PrintStream out(String file) throws IOException {
		if (file == null) {
			return new PrintStream(System.out, true, StandardCharsets.UTF_8) {
				@Override
				public void close() {
					flush();
				}
			};
		}
		return new PrintStream(Files.newOutputStream(Path.of(file)), false, StandardCharsets.UTF_8);
	}

	// ---------------------------------------------------------------- dump

	static void dump(CombatFile cf, Names nm, PrintStream o) {
		Map<Integer, Move> byId = new HashMap<>();
		cf.moves().forEach(m -> byId.put(m.id(), m));
		o.printf("# %s  %s  moves=%d actions=%d%n", cf.name(), cf.path(), cf.moves().size(), cf.actions().size());
		o.println("## 루트 액션 (ID 로 요청 / 대기 중 전역 분기)");
		for (Branch b : cf.actions()) {
			o.printf("  A%-4d %s%n", b.index(), branch(b, nm, byId));
		}
		o.println("## 기술");
		for (Move m : cf.moves()) {
			o.printf("M%-4d %-22s speed=%.2f flags=%04x poise=%d state=%d guard=%d%n", m.id(), nm.anim(m.animHash()),
					m.speed(), m.flags(), m.poise(), m.stateMask(), m.guard());
			for (Branch b : m.branches()) {
				o.printf("    > %s%n", branch(b, nm, byId));
			}
			for (Attack a : m.attacks()) {
				o.printf("    ! %s%n", attack(a));
			}
			for (Event e : m.events()) {
				o.printf("    * %s%n", event(e, nm));
			}
		}
		o.println();
	}

	static String branch(Branch b, Names nm, Map<Integer, Move> local) {
		StringBuilder s = new StringBuilder();
		s.append(String.format("[%.2f-%.2f]%s ", b.winStart(), b.winEnd(), b.immediate() ? "!" : " "));
		s.append(CombatFile.inputName(b.input()));
		if (CombatFile.padBit(b.input()) >= 0 || (b.input() == 17 && b.mode() == 4)) {
			s.append('/').append(CombatFile.modeName(b.mode()));
		}
		if (b.dir() != 0) {
			s.append(' ').append(CombatFile.dirName(b.dir()));
		}
		if (b.magic() != 0) {
			s.append(' ').append(CombatFile.magicName(b.magic()));
		}
		s.append(" -> ").append(target(b.target(), nm, local));
		if (b.startPhase() != 0) {
			s.append(String.format(" @%.2f", b.startPhase()));
		}
		s.append(String.format("  f=%06x", b.flags()));
		if (b.level() != 0) {
			s.append(" lv>=").append(b.level());
		}
		if (b.hpMin() != 0 || b.hpMax() != 0x7FFF) {
			s.append(" myHP=").append(b.hpMin()).append("..").append(b.hpMax());
		}
		if (b.argMin() != 0 || b.argMax() != 0x7FFF) {
			s.append(" tgtHP=").append(b.argMin()).append("..").append(b.argMax());
		}
		if (b.reaction() != -1) {
			s.append(" react=").append(b.reaction());
		}
		if (b.classId() != -1) {
			s.append(" self=").append(b.classId());
		}
		if (b.matchA() != -1 || b.matchB() != -1 || b.matchC() != -1) {
			s.append(" tgt=").append(b.matchA()).append(" tag=").append(b.matchB());
		}
		return s.toString();
	}

	static String target(int id, Names nm, Map<Integer, Move> local) {
		if (id == -1) {
			return "-";
		}
		if (id == -2) {
			return "(block)";
		}
		Move m = local.get(id);
		String n = m != null ? nm.anim(m.animHash()) : nm.moveName(id);
		return "M" + id + (n != null ? " " + n : "");
	}

	static String attack(Attack a) {
		float[] v = a.impulse();
		return String.format(Locale.ROOT, "[%.2f-%.2f] dmg=%d react=%d/%d/%d part=%d fl=%02x mfx=%d imp=(%.0f,%.0f,%.0f)/(%.0f,%.0f,%.0f)/(%.0f,%.0f,%.0f)",
				a.start(), a.end(), a.damage(), a.reaction(), a.blockReaction(), a.death(), a.part(), a.flags(), a.mfx(), v[0], v[1], v[2], v[3], v[4],
				v[5], v[6], v[7], v[8]);
	}

	static String event(Event e, Names nm) {
		String h = nm.any(e.hash());
		return String.format(Locale.ROOT, "[%.2f-%.2f] %-14s %s a=%.3g b=%.3g idx=%d%s%s%s", e.start(), e.end(),
				CombatFile.eventName(e.type()), h, e.a(), e.b(), e.index(),
				e.cond() != 0 ? " " + CombatFile.condName(e.cond()) : "", e.prob() != 100 ? " p=" + e.prob() + "%" : "",
				e.level() != 0 ? " lv>=" + e.level() : "");
	}

	// ---------------------------------------------------------------- tree

	/** 트리 필터 문맥: 지상 기본 / 신들의 분노 / 아르테미스의 검 / 공중 / 전부. */
	enum Ctx { NORMAL, GOD, BERSERK, AIR, ALL }

	static void tree(List<CombatFile> files, Names nm, String root, int depth, Ctx ctx, PrintStream o) {
		Map<Integer, Move> byId = new TreeMap<>();
		files.forEach(f -> f.moves().forEach(m -> byId.putIfAbsent(m.id(), m)));
		if (root.equalsIgnoreCase("ROOT")) {
			// 대기(기술 없음) 상태에서 평가되는 루트 액션들에서 출발
			List<Branch> all = new ArrayList<>();
			files.forEach(f -> all.addAll(f.actions()));
			o.println("ROOT (대기 상태 전역 분기)");
			walk(new Move(0, 0, -1, 1, 0, 0, 0, 0, all, List.of(), List.of()), byId, nm, 1, depth, ctx, new HashSet<>(), o);
			return;
		}
		for (Move r : byId.values()) {
			if (root.equals(Integer.toString(r.id())) || root.equalsIgnoreCase(nm.anim(r.animHash()))) {
				o.printf("M%d %s%n", r.id(), nm.anim(r.animHash()));
				walk(r, byId, nm, 1, depth, ctx, new HashSet<>(Set.of(r.id())), o);
			}
		}
	}

	/** 분기가 문맥에서 성립 가능한지 (flags 의 상태/신모드/블레이드 조건, sub_00208578). */
	static boolean allowed(Branch b, Ctx ctx) {
		int f = b.flags();
		boolean god = (f & 0x200) != 0 || (f & 0x100) != 0, needGod = (f & 0x300) == 0x100;
		boolean ber = (f & 0x8000) != 0 || (f & 0x2000) != 0, needBer = (f & 0xA000) == 0x2000;
		return switch (ctx) {
			case ALL -> true;
			case NORMAL -> (f & CombatFile.FLAG_GROUND) != 0 && !needGod && !needBer;
			case AIR -> (f & CombatFile.FLAG_AIR) != 0 && !needGod && !needBer;
			case GOD -> (f & CombatFile.FLAG_GROUND) != 0 && god && !needBer;
			case BERSERK -> (f & (CombatFile.FLAG_GROUND | CombatFile.FLAG_AIR)) != 0 && ber && !needGod;
		};
	}

	private static void walk(Move m, Map<Integer, Move> byId, Names nm, int d, int max, Ctx ctx, Set<Integer> path,
							 PrintStream o) {
		if (d > max) {
			return;
		}
		// 같은 입력·대상의 "버퍼 구간 + 즉시 구간" 쌍을 하나로 합친다
		Map<String, float[]> merged = new LinkedHashMap<>();
		Map<String, Branch> first = new HashMap<>();
		for (Branch b : m.branches()) {
			if (CombatFile.padBit(b.input()) < 0 || b.target() < 0 || b.mode() == 5 || b.mode() == 2 || !allowed(b, ctx)) {
				continue;
			}
			String k = b.input() + ":" + b.mode() + ":" + b.dir() + ":" + b.magic() + ":" + b.target() + ":" + b.level();
			float[] w = merged.computeIfAbsent(k, x -> new float[]{2, -1, -1});
			w[0] = Math.min(w[0], b.winStart());
			w[1] = Math.max(w[1], b.winEnd());
			if (!b.immediate()) {
				w[2] = Math.max(w[2], b.winEnd());
			}
			first.putIfAbsent(k, b);
		}
		for (var e : merged.entrySet()) {
			Branch b = first.get(e.getKey());
			float[] w = e.getValue();
			Move t = byId.get(b.target());
			String label = t != null ? nm.anim(t.animHash()) : "?";
			String dmg = t == null || t.attacks().isEmpty() ? ""
					: " dmg=" + t.attacks().stream().map(a -> Integer.toString(a.damage())).toList();
			o.printf(Locale.ROOT, "%s%s%s%s%s [%.2f-%.2f%s] -> M%d %s%s%s%n", "  ".repeat(d), CombatFile.inputName(b.input()),
					b.mode() == 1 ? "" : "(" + CombatFile.modeName(b.mode()) + ")",
					b.dir() != 0 ? " " + CombatFile.dirName(b.dir()) : "",
					b.magic() != 0 ? " " + CombatFile.magicName(b.magic()) : "",
					w[0], w[1], w[2] >= 0 && w[2] < w[1] ? String.format(Locale.ROOT, " link@%.2f", w[2]) : (w[2] < 0 ? " now" : ""),
					b.target(), label, dmg, b.level() != 0 ? " lv>=" + b.level() : "");
			if (t != null && !path.contains(t.id())) {
				path.add(t.id());
				walk(t, byId, nm, d + 1, max, ctx, path, o);
				path.remove(t.id());
			}
		}
	}

	// ---------------------------------------------------------------- enemies

	/** 사전에 없는 TWK 키 (해시 역산으로 확인한 이름). */
	private static final String[] EXTRA_KEYS = {"MaxHitPoints", "DecisionTreeTemplate", "Normal Health Prob", "Normal Health Min",
			"Normal Health Max", "Normal Magic Prob", "Normal Magic Min", "Normal Magic Max", "Normal Weapon Prob", "Normal Weapon Min",
			"Normal Weapon Max", "Low Health Prob", "Low Health Min", "Low Health Max", "Low Magic Prob", "Low Magic Min",
			"Low Magic Max", "Invulnerable", "Target Radius"};

	static void enemies(Path dir, PrintStream o) throws IOException {
		Map<Integer, String> keys = devple.gow.tools.tweak.Cli.loadNames();
		for (String k : EXTRA_KEYS) {
			keys.putIfAbsent(TweakFile.hash(k), k);
		}
		Map<String, Map<String, String>> ai = new TreeMap<>(), sets = new TreeMap<>();
		Map<String, Set<String>> where = new HashMap<>();
		Map<String, CombatFile> cfs = new HashMap<>();
		Map<String, String> wadSet = new HashMap<>();
		List<Path> wads;
		try (Stream<Path> s = Files.list(dir)) {
			wads = s.filter(p -> p.toString().endsWith(".WAD")).sorted().toList();
		}
		for (Path p : wads) {
			WadFile w = WadFile.load(p);
			String wn = p.getFileName().toString().replace(".WAD", "");
			for (CombatFile cf : CombatFile.fromWad(w)) {
				cfs.putIfAbsent(cf.name(), cf);
			}
			for (WadTag t : w.tags()) {
				if (t.tag() != WadTagType.TWEAK || !t.hasData()) {
					continue;
				}
				Map<String, String> cur = null;
				for (TweakFile.Entry e : TweakFile.parse(t.data()).entries()) {
					switch (e.kind()) {
						case PATH -> {
							cur = null;
							String[] seg = e.text().split("/");
							if (seg.length >= 4 && seg[1].equals("TweakTemplates") && (seg[2].equals("AI1") || seg[2].equals("CombatFileSet"))) {
								Map<String, Map<String, String>> tgt = seg[2].equals("AI1") ? ai : sets;
								cur = tgt.computeIfAbsent(seg[3], k -> new LinkedHashMap<>());
								where.computeIfAbsent(seg[2] + seg[3], k -> new java.util.TreeSet<>()).add(wn);
								if (seg[2].equals("CombatFileSet")) {
									wadSet.putIfAbsent(wn, seg[3]);
								}
							}
						}
						case NUMBER -> {
							if (cur != null) {
								cur.putIfAbsent(keys.getOrDefault(e.hash(), String.format("#%08x", e.hash())), Integer.toString(e.raw()));
							}
						}
						case STRING -> {
							if (cur != null) {
								cur.putIfAbsent(keys.getOrDefault(e.hash(), String.format("#%08x", e.hash())), e.text());
							}
						}
						default -> {
						}
					}
				}
			}
		}
		o.println("| AI1 | 이름 | HitPoints | MaxHitPoints | Damage Mult | Block | Circle HP | CombatFileSet → 기술 파일 | 기술 수 | 공격 피해(최소-최대) | WAD |");
		o.println("|---|---|---|---|---|---|---|---|---|---|---|");
		for (var en : ai.entrySet()) {
			Map<String, String> r = en.getValue();
			// AI1 과 같은 WAD 에 실린 CombatFileSet 을 쓴다 (번호는 일치하지 않는 경우가 많음: 예 skelly Lvl 2 → 020)
			Set<String> ws = where.getOrDefault("AI1" + en.getKey(), Set.of());
			String setId = ws.stream().map(wadSet::get).filter(x -> x != null).findFirst().orElse(en.getKey());
			Map<String, String> set = sets.get(setId);
			List<String> files = new ArrayList<>();
			int nMoves = 0, dMin = Integer.MAX_VALUE, dMax = 0;
			if (set != null) {
				for (int i = 0; i < 8; i++) {
					String v = set.get("File " + i);
					if (v == null) {
						continue;
					}
					int f = (short) Integer.parseInt(v);
					if (f < 0) {
						continue;
					}
					files.add(String.format("%03d", f));
					CombatFile cf = cfs.get(String.format("TWK_CombatFile_%03d", f));
					if (cf == null) {
						continue;
					}
					for (Move m : cf.moves()) {
						nMoves++;
						for (Attack a : m.attacks()) {
							if (a.damage() > 0) {
								dMin = Math.min(dMin, a.damage());
								dMax = Math.max(dMax, a.damage());
							}
						}
					}
				}
			}
			o.printf("| %s | %s | %s | %s | %s | %s | %s | %s | %d | %s | %s |%n", en.getKey(), r.getOrDefault("Instance Name", ""),
					num(r.get("HitPoints")), num(r.get("MaxHitPoints")), flt(r.get("Damage Multiplier")), flt(r.get("Block")),
					num(r.get("Circle Health Threshold")), set == null ? "" : setId + "→" + String.join(",", files), nMoves,
					dMax == 0 ? "-" : dMin + "-" + dMax, String.join(",", where.getOrDefault("AI1" + en.getKey(), Set.of())));
		}
	}

	/** short/int 형 값 (하위 16비트). */
	private static String num(String raw) {
		if (raw == null) {
			return "";
		}
		int v = Integer.parseInt(raw);
		return Integer.toString(v & 0xFFFF);
	}

	private static String flt(String raw) {
		if (raw == null) {
			return "";
		}
		float f = Float.intBitsToFloat(Integer.parseInt(raw));
		return Float.isFinite(f) && Math.abs(f) < 1e6 && Math.abs(f) > 1e-6 ? String.format(Locale.ROOT, "%.2f", f) : "0";
	}

	// ---------------------------------------------------------------- names

	/** 해시 → 이름 사전. */
	static final class Names {
		final Map<Integer, String> upper = new HashMap<>();
		final Map<Integer, String> exact = new HashMap<>();
		final Map<Integer, String> moveNames = new HashMap<>();

		static Names build(Path wad) throws IOException {
			Names n = new Names();
			for (String s : devple.gow.tools.tweak.Cli.loadNames().values()) {
				n.add(s);
				for (String suf : new String[]{"_Tween", "_Cycle"}) {
					if (s.endsWith(suf)) {
						n.add(s.substring(0, s.length() - suf.length()));
					}
				}
			}
			Set<Path> scan = new HashSet<>();
			scan.add(wad);
			Path dir = wad.toAbsolutePath().getParent();
			for (String x : new String[]{"R_HERO0.WAD", "R_PERMK.WAD"}) {
				Path p = dir.resolve(x);
				if (Files.exists(p)) {
					scan.add(p);
				}
			}
			for (Path p : scan) {
				byte[] d = Files.readAllBytes(p);
				int st = -1;
				for (int i = 0; i <= d.length; i++) {
					int c = i < d.length ? d[i] & 0xFF : 0;
					boolean id = c == '_' || (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
					if (id && st < 0) {
						st = i;
					} else if (!id && st >= 0) {
						if (i - st >= 3 && i - st <= 40 && !Character.isDigit(d[st])) {
							n.add(new String(d, st, i - st, StandardCharsets.ISO_8859_1));
						}
						st = -1;
					}
				}
			}
			for (CombatFile cf : CombatFile.fromWad(WadFile.load(wad))) {
				cf.moves().forEach(m -> n.moveNames.putIfAbsent(m.id(), n.anim(m.animHash())));
			}
			return n;
		}

		void add(String s) {
			upper.putIfAbsent(TweakFile.hashUpper(s), s.toUpperCase(Locale.ROOT));
			exact.putIfAbsent(TweakFile.hash(s), s);
		}

		String anim(int h) {
			String s = upper.get(h);
			return s != null ? s : String.format("#%08x", h);
		}

		String any(int h) {
			String s = upper.get(h);
			if (s == null) {
				s = exact.get(h);
			}
			return s != null ? s : String.format("#%08x", h);
		}

		String moveName(int id) {
			return moveNames.get(id);
		}
	}

	private Cli() {
	}
}
