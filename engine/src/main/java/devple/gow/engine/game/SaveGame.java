package devple.gow.engine.game;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * 세이브 파일 (자체 형식, 11 문서 §8.3 "원본 호환이 목표가 아니면 블록 순서를 따른 자체 포맷").
 * 원작 직렬화 스트림 순서 그대로의 항목: 레벨/세이브 위치 → PlayerProgress → 난이도·코스튬 → 스크립트 GlobalData.
 * 형식은 사람이 읽을 수 있는 properties (UTF-8). 위치: {@code ~/Library/Application Support/devple-gow/slot<N>.save}.
 */
public final class SaveGame {

	public String level;
	public float[] pos = new float[3];
	public float yaw;
	public int difficulty;
	public int costume;
	public float hp, mp;
	/** 마법 재생 MP/초 (PlayerProgress +0x64 = 0x3044A4, 스크립트 Internal 0x12). */
	public float mpRegen;
	public int redOrbs, hpLevel, mpLevel, gorgonEyes, phoenixFeathers, deaths, itemFlags;
	/** 첫 안내 비트·누적 (PlayerProgress +0x50 / +0x54 / +0x55, {@link PlayerState#firstTimeHint}). */
	public int hintMask, hintCount1, hintCount19;
	public String selectedMagic;
	/** 누적 플레이 시간 (초, 원작 슬롯 디렉터리 f32 playTime — 11 문서 §5.1). */
	public float playTime;
	public final Map<Weapon, int[]> weapons = new TreeMap<>();
	/** ESC GlobalData 변수 (id → 값 문자열 "타입:값"). */
	public final Map<Integer, String> globalData = new TreeMap<>();
	/**
	 * 레벨 기억 표 (원작 World_Serialize 0x1B3E48 끝: 표 칸마다 이름 10 바이트 + 덩어리) — 칸 순서, 레벨별 LevelData ("타입:값") · 만들어 둔 Creator
	 * ({@link GameSession#levelMemory}, 43 §21).
	 */
	public final java.util.List<String> levelTable = new java.util.ArrayList<>();
	public final Map<String, Map<Integer, String>> levelData = new TreeMap<>();
	public final Map<String, java.util.List<String>> levelCreated = new TreeMap<>();
	/** 레벨별 오브젝트 → "Vis 엔티티|값,값,…" ({@link devple.gow.engine.script.ScriptWorld.Memory#vis}). */
	public final Map<String, Map<String, String>> levelVis = new TreeMap<>();

	public static Path dir() {
		return Path.of(System.getProperty("user.home"), "Library", "Application Support", "devple-gow");
	}

	public static Path slot(int n) {
		return dir().resolve("slot" + n + ".save");
	}

	public static SaveGame capture(GameSession s, String level, float[] pos, float yaw) {
		SaveGame g = new SaveGame();
		PlayerState p = s.player;
		g.level = level;
		g.pos = pos.clone();
		g.yaw = yaw;
		g.difficulty = s.difficulty;
		g.costume = p.costume;
		g.hp = p.hp;
		g.mp = p.mp;
		g.mpRegen = p.mpRegen;
		g.redOrbs = p.redOrbs;
		g.hpLevel = p.hpLevel;
		g.mpLevel = p.mpLevel;
		g.gorgonEyes = p.gorgonEyes;
		g.phoenixFeathers = p.phoenixFeathers;
		g.deaths = p.deaths;
		g.itemFlags = p.itemFlags;
		g.hintMask = p.hintMask;
		g.hintCount1 = p.hintCount1;
		g.hintCount19 = p.hintCount19;
		g.selectedMagic = p.selectedMagic != null ? p.selectedMagic.name() : "";
		g.playTime = s.playTime;
		for (Weapon w : Weapon.values()) {
			g.weapons.put(w, new int[]{p.level.get(w), p.invested.get(w)});
		}
		s.scriptHost.globalData().forEach((k, v) -> g.globalData.put(k, encode(v)));
		g.levelTable.addAll(s.levelTable());
		s.levelMemory.forEach((lv, m) -> {
			Map<Integer, String> d = new TreeMap<>();
			m.levelData().forEach((k, v) -> d.put(k, encode(v)));
			g.levelData.put(lv, d);
			g.levelCreated.put(lv, new java.util.ArrayList<>(m.created()));
			Map<String, String> vis = new TreeMap<>();
			m.vis().forEach((o, v) -> {
				StringBuilder sb = new StringBuilder(m.visBy().getOrDefault(o, "")).append('|');
				for (int i = 0; i < v.length; i++) {
					sb.append(i > 0 ? "," : "").append(v[i]);
				}
				vis.put(o, sb.toString());
			});
			g.levelVis.put(lv, vis);
		});
		return g;
	}

	/** 불러오기: 진행도를 세션에 적용 (원작: hp = max(저장, 2), 사망 수 0 — 11 문서 §8.2 loadFromCard). */
	public void applyTo(GameSession s) {
		PlayerState p = new PlayerState(GameRules.load(s.permk));
		p.costume = costume;
		p.hpLevel = hpLevel;
		p.mpLevel = mpLevel;
		p.hp = Math.max(hp, 2);
		p.mp = mp;
		p.mpRegen = mpRegen;
		p.redOrbs = redOrbs;
		p.gorgonEyes = gorgonEyes;
		p.phoenixFeathers = phoenixFeathers;
		p.deaths = 0;
		p.itemFlags = itemFlags;
		p.hintMask = hintMask;
		p.hintCount1 = hintCount1;
		p.hintCount19 = hintCount19;
		p.selectedMagic = selectedMagic == null || selectedMagic.isEmpty() ? null : Weapon.valueOf(selectedMagic);
		weapons.forEach((w, v) -> {
			p.level.put(w, v[0]);
			p.invested.put(w, v[1]);
		});
		s.player = p;
		s.difficulty = difficulty;
		s.costume = costume;
		s.playTime = playTime;
		globalData.forEach((k, v) -> s.scriptHost.globalData().put(k, decode(v)));
		Map<String, devple.gow.engine.script.ScriptWorld.Memory> mem = new java.util.LinkedHashMap<>();
		for (String lv : levelTable) {
			Map<Integer, String> d = levelData.get(lv);
			if (d == null) {
				continue;
			}
			Map<Integer, Object> vals = new java.util.HashMap<>();
			d.forEach((k, v) -> vals.put(k, decode(v)));
			Map<String, int[]> vis = new java.util.LinkedHashMap<>();
			Map<String, String> by = new java.util.HashMap<>();
			levelVis.getOrDefault(lv, Map.of()).forEach((o, enc) -> {
				int bar = enc.indexOf('|');
				String[] q = enc.substring(bar + 1).split(",");
				int[] v = new int[q.length];
				for (int i = 0; i < q.length; i++) {
					v[i] = Integer.parseInt(q[i]);
				}
				vis.put(o, v);
				if (bar > 0) {
					by.put(o, enc.substring(0, bar));
				}
			});
			mem.put(lv, new devple.gow.engine.script.ScriptWorld.Memory(vals,
					new java.util.LinkedHashSet<>(levelCreated.getOrDefault(lv, java.util.List.of())), vis, by));
		}
		s.restoreLevelTable(levelTable, mem);
	}

	public void write(Path file) throws IOException {
		Properties pr = new Properties();
		pr.setProperty("level", level);
		pr.setProperty("pos", pos[0] + "," + pos[1] + "," + pos[2]);
		pr.setProperty("yaw", Float.toString(yaw));
		pr.setProperty("difficulty", Integer.toString(difficulty));
		pr.setProperty("costume", Integer.toString(costume));
		pr.setProperty("hp", Float.toString(hp));
		pr.setProperty("mp", Float.toString(mp));
		pr.setProperty("mpRegen", Float.toString(mpRegen));
		pr.setProperty("redOrbs", Integer.toString(redOrbs));
		pr.setProperty("hpLevel", Integer.toString(hpLevel));
		pr.setProperty("mpLevel", Integer.toString(mpLevel));
		pr.setProperty("gorgonEyes", Integer.toString(gorgonEyes));
		pr.setProperty("phoenixFeathers", Integer.toString(phoenixFeathers));
		pr.setProperty("deaths", Integer.toString(deaths));
		pr.setProperty("itemFlags", Integer.toString(itemFlags));
		pr.setProperty("hints", hintMask + "," + hintCount1 + "," + hintCount19);
		pr.setProperty("selectedMagic", selectedMagic);
		pr.setProperty("playTime", Float.toString(playTime));
		weapons.forEach((w, v) -> pr.setProperty("weapon." + w.name(), v[0] + "," + v[1]));
		globalData.forEach((k, v) -> pr.setProperty("global." + k, v));
		pr.setProperty("levelTable", String.join(",", levelTable));
		levelData.forEach((lv, d) -> d.forEach((k, v) -> pr.setProperty("level." + lv + "." + k, v)));
		levelCreated.forEach((lv, c) -> pr.setProperty("levelCreated." + lv, String.join(",", c)));
		levelVis.forEach((lv, m) -> m.forEach((o, v) -> pr.setProperty("levelVis." + lv + "." + o, v)));
		Files.createDirectories(file.getParent());
		try (Writer wr = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			pr.store(wr, "devple-gow save");
		}
	}

	public static SaveGame read(Path file) throws IOException {
		Properties pr = new Properties();
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			pr.load(r);
		}
		SaveGame g = new SaveGame();
		g.level = pr.getProperty("level");
		String[] p = pr.getProperty("pos").split(",");
		for (int i = 0; i < 3; i++) {
			g.pos[i] = Float.parseFloat(p[i]);
		}
		g.yaw = Float.parseFloat(pr.getProperty("yaw", "0"));
		g.difficulty = Integer.parseInt(pr.getProperty("difficulty", "1"));
		g.costume = Integer.parseInt(pr.getProperty("costume", "0"));
		g.hp = Float.parseFloat(pr.getProperty("hp"));
		g.mp = Float.parseFloat(pr.getProperty("mp"));
		g.mpRegen = Float.parseFloat(pr.getProperty("mpRegen", "0"));
		g.redOrbs = Integer.parseInt(pr.getProperty("redOrbs"));
		g.hpLevel = Integer.parseInt(pr.getProperty("hpLevel"));
		g.mpLevel = Integer.parseInt(pr.getProperty("mpLevel"));
		g.gorgonEyes = Integer.parseInt(pr.getProperty("gorgonEyes"));
		g.phoenixFeathers = Integer.parseInt(pr.getProperty("phoenixFeathers"));
		g.deaths = Integer.parseInt(pr.getProperty("deaths"));
		g.itemFlags = Integer.parseInt(pr.getProperty("itemFlags"));
		String[] hs = pr.getProperty("hints", "0,0,0").split(",");
		g.hintMask = Integer.parseInt(hs[0]);
		g.hintCount1 = hs.length > 1 ? Integer.parseInt(hs[1]) : 0;
		g.hintCount19 = hs.length > 2 ? Integer.parseInt(hs[2]) : 0;
		g.selectedMagic = pr.getProperty("selectedMagic", "");
		g.playTime = Float.parseFloat(pr.getProperty("playTime", "0"));
		for (Weapon w : Weapon.values()) {
			String v = pr.getProperty("weapon." + w.name());
			if (v != null) {
				String[] a = v.split(",");
				g.weapons.put(w, new int[]{Integer.parseInt(a[0]), Integer.parseInt(a[1])});
			}
		}
		for (String k : pr.stringPropertyNames()) {
			if (k.startsWith("global.")) {
				g.globalData.put(Integer.parseInt(k.substring(7)), pr.getProperty(k));
			} else if (k.startsWith("level.")) {
				int dot = k.lastIndexOf('.');
				g.levelData.computeIfAbsent(k.substring(6, dot), x -> new TreeMap<>()).put(Integer.parseInt(k.substring(dot + 1)), pr.getProperty(k));
			} else if (k.startsWith("levelVis.")) {
				String rest = k.substring(9);
				int dot = rest.indexOf('.');
				g.levelVis.computeIfAbsent(rest.substring(0, dot), x -> new TreeMap<>()).put(rest.substring(dot + 1), pr.getProperty(k));
			} else if (k.startsWith("levelCreated.")) {
				String v = pr.getProperty(k);
				g.levelCreated.put(k.substring(13), v.isEmpty() ? new java.util.ArrayList<>() : new java.util.ArrayList<>(java.util.List.of(v.split(","))));
			}
		}
		String lt = pr.getProperty("levelTable", "");
		if (!lt.isEmpty()) {
			g.levelTable.addAll(java.util.List.of(lt.split(",")));
		}
		return g;
	}

	private static String encode(Object v) {
		if (v instanceof Boolean b) {
			return "b:" + b;
		} else if (v instanceof Integer i) {
			return "i:" + i;
		} else if (v instanceof Float f) {
			return "f:" + f;
		}
		return "s:" + v;
	}

	private static Object decode(String s) {
		String v = s.substring(2);
		return switch (s.charAt(0)) {
			case 'b' -> Boolean.parseBoolean(v);
			case 'i' -> Integer.parseInt(v);
			case 'f' -> Float.parseFloat(v);
			default -> v;
		};
	}
}
