package devple.gow.engine.game;

import devple.gow.engine.nav.Platforms;
import devple.gow.engine.script.ScriptWorld;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * 세이브 파일 (자체 형식, 11 문서 §8.3 "원본 호환이 목표가 아니면 블록 순서를 따른 자체 포맷").
 * 원작 직렬화 스트림 순서 그대로의 항목: 레벨/세이브 위치 → PlayerProgress → 난이도·코스튬 → 스크립트 GlobalData.
 * 형식은 사람이 읽을 수 있는 properties (UTF-8). 위치: {@code ~/Library/Application Support/devple-gow/slot<N>.save}.
 * <p>
 * 체크포인트 스냅숏도 이 객체다 ({@link GameSession#checkpoint}) — 원작은 세이브와 같은 스트림 (Save_Serialize 0x22D490) 을 메모리 버퍼 0x3387B8 에 쓰고
 * (0x22D418), 사망 메뉴의 "마지막 체크포인트에서 다시 시작" (0x21E3D0 → 0x22D440) 은 그것을 되읽는다 ({@link #applyCheckpoint}) [확실].
 */
public final class SaveGame {

	public String level;
	/**
	 * 다른 슬롯 레벨 (null = 없음) — 원작 스트림 3 (0x1BBB88: 두 슬롯 WAD 이름 + 켬 바이트) 을 되읽을 때 0x1B4528 → 0x1BC110 · 0x1B2AA8 이 둘 다 다시 올리고
	 * 켬 바이트가 선 슬롯만 켠다 [확실].
	 */
	public String other;
	/** 다른 슬롯이 켜져 있음 (LoadWad · 이음). 거짓 = LoadForWarp 로 꺼진 채 올라 Warp 준비 (0x29D918). */
	public boolean otherActive = true;
	public float[] pos = new float[3];
	public float yaw;
	/**
	 * 영웅 이동 상태 ({@code NavState} 이름, null = 지상) — 원작 PlayerProgress +0x40 = 영웅 +0x174 &amp; 0xFFFF3BFF 를 담고 Load_PlayerBlock 이 되돌린다 (벽 0x20 이면
	 * 벽에 붙은 채, 예 ATHN06C 덩굴 위 a06Mp03Done 체크포인트).
	 */
	public String heroState;
	/** 음악 (SND_M_* 이름, "" = 없음) — 원작 스트림 6 음악 그룹 블록 (0xEC, 0x22BD58) 의 근사. */
	public String music = "";
	public int difficulty;
	public int costume;
	/** 옵션 블록 [10] HUD EndGame (GameSession#endGame). */
	public int endGame;
	public float hp, mp;
	/** 마법 재생 MP/초 (PlayerProgress +0x64 = 0x3044A4, 스크립트 Internal 0x12). */
	public float mpRegen;
	/** 신들의 분노 게이지 (PlayerProgress +0x60). */
	public float rage;
	public int redOrbs, hpLevel, mpLevel, gorgonEyes, phoenixFeathers, deaths, itemFlags;
	/** 첫 안내 비트·누적 (PlayerProgress +0x50 / +0x54 / +0x55, {@link PlayerState#firstTimeHint}). */
	public int hintMask, hintCount1, hintCount19;
	public String selectedMagic;
	/** 누적 플레이 시간 (초, 원작 슬롯 디렉터리 f32 playTime — 11 문서 §5.1). */
	public float playTime;
	/**
	 * 세이브 포인트 번호 (원작 슬롯 디렉터리 u16 +0xC ← 0x2A2704 ← 세이브 포인트 Creator 슬롯 6, 0x2312B0) — 슬롯 이름 메시지 600 + 번호
	 * (617 "아틀라스의 시험" = PAND01A), 0 이하 = "* Not A Save Point *" (Save_SlotText 0x22E810) [확실]. 예전 세이브엔 없어 0.
	 */
	public int savePoint;
	public final Map<Weapon, int[]> weapons = new TreeMap<>();
	/** ESC GlobalData 변수 (id → 값 문자열 "타입:값"). */
	public final Map<Integer, String> globalData = new TreeMap<>();
	/** 초기화한 GlobalData 엔티티 (원작 전역 표 0x30063C 칸별 초기화됨 +0x19 의 근사, {@code ScriptHost.initedGlobals}). */
	public final List<String> initedGlobals = new ArrayList<>();
	/** GameMgr 타이머 표 {남은[4], 길이[4], 플래그[4]} (스트림 7 GameMgr 블록 0x3368F8 +0x00..+0x2F, {@code ScriptTimers.save}). null = 빈 표. */
	public float[] timers;
	/**
	 * 레벨 기억 표 (원작 World_Serialize 0x1B3E48 끝: 표 칸마다 이름 10 바이트 + 덩어리) — 칸 순서, 레벨별 LevelData ("타입:값") · 만들어 둔 Creator
	 * ({@link GameSession#levelMemory}, 43 §21).
	 */
	public final java.util.List<String> levelTable = new java.util.ArrayList<>();
	public final Map<String, Map<Integer, String>> levelData = new TreeMap<>();
	public final Map<String, java.util.List<String>> levelCreated = new TreeMap<>();
	/** 레벨별 오브젝트 → "Vis 엔티티|값,값,…" ({@link devple.gow.engine.script.ScriptWorld.Memory#vis}). */
	public final Map<String, Map<String, String>> levelVis = new TreeMap<>();
	/** 레벨별 연 상자의 Creator 키 ({@link devple.gow.engine.script.ScriptWorld.Memory#opened}). */
	public final Map<String, java.util.List<String>> levelOpened = new TreeMap<>();
	/** 레벨별 엔티티 발동 기록: 번호 → "몇 초 전,만든 수,죽은 수" (세이브 시각 기준, {@code Memory.fired}). */
	public final Map<String, Map<Integer, String>> levelFired = new TreeMap<>();
	/** 레벨별 살아 있던 SpawnEnemy 적 "엔티티 번호,x,y,z" ({@code Memory.enemies}). */
	public final Map<String, List<String>> levelEnemies = new TreeMap<>();
	/** 레벨별 미뤄 둔 발동 "엔티티 번호,남은 초,영웅 1/0" ({@code Memory.pending}). */
	public final Map<String, List<String>> levelPending = new TreeMap<>();
	/** 레벨별 Animator 몸 상태 "주인|애니|시각|속도|재생|반복|왕복|충돌|들림" ({@code Memory.anims}). */
	public final Map<String, List<String>> levelAnims = new TreeMap<>();
	/** 레벨별 부순 물체의 소유 GO ({@code Memory.broken}). */
	public final Map<String, List<String>> levelBroken = new TreeMap<>();

	public static Path dir() {
		return Path.of(System.getProperty("user.home"), "Library", "Application Support", "devple-gow");
	}

	public static Path slot(int n) {
		return dir().resolve("slot" + n + ".save");
	}

	public static SaveGame capture(GameSession s, String level, float[] pos, float yaw) {
		return capture(s, level, null, true, pos, yaw);
	}

	/**
	 * 지금 세션을 스트림 순서대로 담는다 (세이브 · 체크포인트 공통). 레벨 기억 표는 지금 시계로 늙혀 담는다 — 원작 덩어리는 전역 시계의 절대 시각이라
	 * 되읽은 뒤에도 같은 간격 ({@code Memory.aged}). 부르는 쪽이 먼저 올라 있는 두 슬롯을 표에 써야 한다 (0x1B3E48 → 0x1B4080(…, 0)).
	 */
	public static SaveGame capture(GameSession s, String level, String other, boolean otherActive, float[] pos, float yaw) {
		SaveGame g = new SaveGame();
		PlayerState p = s.player;
		g.level = level;
		g.other = other;
		g.otherActive = otherActive;
		g.pos = pos.clone();
		g.yaw = yaw;
		g.difficulty = s.difficulty;
		g.costume = p.costume;
		g.endGame = s.endGame;
		g.hp = p.hp;
		g.mp = p.mp;
		g.mpRegen = p.mpRegen;
		g.rage = p.rage;
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
		g.savePoint = s.savePoint;
		String mus = s.sound != null ? s.sound.currentMusic() : null;
		g.music = mus != null ? mus : "";
		for (Weapon w : Weapon.values()) {
			g.weapons.put(w, new int[]{p.level.get(w), p.invested.get(w)});
		}
		s.scriptHost.globalData().forEach((k, v) -> g.globalData.put(k, encode(v)));
		g.initedGlobals.addAll(new java.util.TreeSet<>(s.scriptHost.initedGlobals()));
		g.timers = s.scriptHost.timers.save();
		g.levelTable.addAll(s.levelTable());
		s.levelMemory.forEach((lv, raw) -> {
			ScriptWorld.Memory m = raw.aged(s.playTime);
			Map<Integer, String> d = new TreeMap<>();
			m.levelData().forEach((k, v) -> d.put(k, encode(v)));
			g.levelData.put(lv, d);
			g.levelCreated.put(lv, new java.util.ArrayList<>(m.created()));
			g.levelOpened.put(lv, new java.util.ArrayList<>(m.opened()));
			Map<String, String> vis = new TreeMap<>();
			m.vis().forEach((o, v) -> {
				StringBuilder sb = new StringBuilder(m.visBy().getOrDefault(o, "")).append('|');
				for (int i = 0; i < v.length; i++) {
					sb.append(i > 0 ? "," : "").append(v[i]);
				}
				vis.put(o, sb.toString());
			});
			g.levelVis.put(lv, vis);
			Map<Integer, String> fired = new TreeMap<>();
			m.fired().forEach((id, v) -> fired.put(id, floats(v)));
			g.levelFired.put(lv, fired);
			g.levelEnemies.put(lv, m.enemies().stream().map(SaveGame::floats).toList());
			g.levelPending.put(lv, m.pending().stream().map(SaveGame::floats).toList());
			g.levelAnims.put(lv, m.anims().stream().map(SaveGame::encodeAnim).toList());
			g.levelBroken.put(lv, new ArrayList<>(m.broken()));
		});
		return g;
	}

	/** 불러오기: 진행도를 세션에 적용 (원작: hp = max(저장, 2), 사망 수 0 — 11 문서 §8.2 loadFromCard). */
	public void applyTo(GameSession s) {
		PlayerState p = player(s);
		p.hp = Math.max(hp, 2);
		p.deaths = 0;
		s.player = p;
		s.difficulty = difficulty;
		s.costume = costume;
		s.easyRestart = false; // 0x22DA88: 메모리카드 불러오기만 0x29F484 를 지운다
		s.checkpoint = this; // 0x21FB70: 읽은 카드 버퍼 0x3387B8 이 곧 스냅숏 — 상태 9 는 찍지 않는다 (0x21FC28 a0 = 0x29F444 == 0 → 0)
		restoreWorld(s);
	}

	/**
	 * 체크포인트 재시작 (원작 0x21E3D0 → 0x22D440 → 명령 0xF/0x10 → 0x22D660 → "Restarted or loaded" 0x1A7C80 → 0x22DA88) [확실: 코드]:
	 * <ul>
	 *   <li>PlayerProgress 를 스냅숏으로 (Load_PlayerBlock 0x1DC130) — 단 사망 수 (+0x48) 와 첫 안내 비트 (+0x50) 는 지금 것, 체력은
	 *       {@link PlayerState#onCheckpointRestart} (스냅숏 체력 기준). 플레이 시간 (+0x4C) 도 스냅숏으로 되돌아간다.</li>
	 *   <li>난이도 = 스냅숏 옵션 [9], "쉬움으로" 를 고른 뒤면 (0x29F484) 0.</li>
	 *   <li>세계 = 스냅숏: GlobalData · LevelData · 레벨 상태 표 (두 슬롯 덩어리 포함, 0x1B4450) · GameMgr 타이머 (0x1B15E0 — 본 동영상 목록은 지금 것)
	 *       — 그 뒤 두 슬롯 레벨을 다시 올린다 (0x1B4528).</li>
	 * </ul>
	 * 영웅 자리·방향 (행렬) 은 {@link #pos} · {@link #yaw} — 씬이 둔다.
	 */
	public void applyCheckpoint(GameSession s, boolean easy) {
		PlayerState cur = s.player;
		PlayerState p = player(s);
		p.deaths = cur != null ? cur.deaths : 0;
		p.hintMask = cur != null ? cur.hintMask : hintMask;
		p.hp = hp;
		p.onCheckpointRestart(hp);
		s.player = p;
		if (easy) {
			s.easyRestart = true;
		}
		s.difficulty = s.easyRestart ? 0 : difficulty;
		restoreWorld(s);
	}

	private PlayerState player(GameSession s) {
		PlayerState p = new PlayerState(GameRules.load(s.permk));
		p.costume = costume;
		p.hpLevel = hpLevel;
		p.mpLevel = mpLevel;
		p.hp = hp;
		p.mp = mp;
		p.mpRegen = mpRegen;
		p.rage = rage;
		p.redOrbs = redOrbs;
		p.gorgonEyes = gorgonEyes;
		p.phoenixFeathers = phoenixFeathers;
		p.deaths = deaths;
		p.itemFlags = itemFlags;
		p.hintMask = hintMask;
		p.hintCount1 = hintCount1;
		p.hintCount19 = hintCount19;
		p.selectedMagic = selectedMagic == null || selectedMagic.isEmpty() ? null : Weapon.valueOf(selectedMagic);
		weapons.forEach((w, v) -> {
			p.level.put(w, v[0]);
			p.invested.put(w, v[1]);
		});
		return p;
	}

	/** 세계 블록: 플레이 시간 · GlobalData · 초기화 기록 · 타이머 · 레벨 상태 표 (0x1B4450 · 0x1B1588 · 0x1B15E0). 두 슬롯 세계는 버린다. */
	private void restoreWorld(GameSession s) {
		s.costume = costume;
		s.endGame = endGame; // 0x22DB64: 옵션 [10] → HUD +0x1B8
		s.playTime = playTime;
		s.music = music != null ? music : ""; // 0x22BD80: 음악 그룹 블록 — 새 씬이 튼다 (GameScene.resumeMusic)
		s.scriptHost.globalData().clear();
		globalData.forEach((k, v) -> s.scriptHost.globalData().put(k, decode(v)));
		if (!initedGlobals.isEmpty()) {
			s.scriptHost.initedGlobals().clear();
			s.scriptHost.initedGlobals().addAll(initedGlobals);
		}
		s.scriptHost.timers.restore(timers);
		s.checkpointPending = null; // 0x1B15E0 → 0x1B33F0: GameMgr 플래그 (0x29D960 …) 지움
		s.residentWorlds.clear();
		s.warpLeft = null;
		Map<String, ScriptWorld.Memory> mem = new LinkedHashMap<>();
		for (String lv : levelTable) {
			Map<Integer, String> d = levelData.get(lv);
			if (d == null) {
				continue;
			}
			Map<Integer, Object> vals = new HashMap<>();
			d.forEach((k, v) -> vals.put(k, decode(v)));
			Map<String, int[]> vis = new LinkedHashMap<>();
			Map<String, String> by = new HashMap<>();
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
			Map<Integer, float[]> fired = new LinkedHashMap<>();
			levelFired.getOrDefault(lv, Map.of()).forEach((id, v) -> fired.put(id, parseFloats(v)));
			List<float[]> enemies = levelEnemies.getOrDefault(lv, List.of()).stream().map(SaveGame::parseFloats).toList();
			List<float[]> pending = levelPending.getOrDefault(lv, List.of()).stream().map(SaveGame::parseFloats).toList();
			List<Platforms.State> anims = levelAnims.getOrDefault(lv, List.of()).stream().map(SaveGame::decodeAnim).toList();
			mem.put(lv, new ScriptWorld.Memory(vals, new LinkedHashSet<>(levelCreated.getOrDefault(lv, List.of())), vis, by,
					new LinkedHashSet<>(levelOpened.getOrDefault(lv, List.of())), fired, enemies, pending, anims,
					new LinkedHashSet<>(levelBroken.getOrDefault(lv, List.of())), playTime));
		}
		s.restoreLevelTable(levelTable, mem);
	}

	public void write(Path file) throws IOException {
		Properties pr = new Properties();
		pr.setProperty("level", level);
		if (other != null) {
			pr.setProperty("other", other);
			pr.setProperty("otherActive", Boolean.toString(otherActive));
		}
		pr.setProperty("pos", pos[0] + "," + pos[1] + "," + pos[2]);
		pr.setProperty("yaw", Float.toString(yaw));
		if (heroState != null) {
			pr.setProperty("heroState", heroState);
		}
		pr.setProperty("difficulty", Integer.toString(difficulty));
		pr.setProperty("costume", Integer.toString(costume));
		pr.setProperty("endGame", Integer.toString(endGame));
		pr.setProperty("hp", Float.toString(hp));
		pr.setProperty("mp", Float.toString(mp));
		pr.setProperty("mpRegen", Float.toString(mpRegen));
		pr.setProperty("rage", Float.toString(rage));
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
		pr.setProperty("savePoint", Integer.toString(savePoint));
		pr.setProperty("music", music != null ? music : "");
		weapons.forEach((w, v) -> pr.setProperty("weapon." + w.name(), v[0] + "," + v[1]));
		globalData.forEach((k, v) -> pr.setProperty("global." + k, v));
		pr.setProperty("initedGlobals", String.join(";", initedGlobals));
		if (timers != null) {
			pr.setProperty("timers", floats(timers));
		}
		pr.setProperty("levelTable", String.join(",", levelTable));
		levelData.forEach((lv, d) -> d.forEach((k, v) -> pr.setProperty("level." + lv + "." + k, v)));
		levelCreated.forEach((lv, c) -> pr.setProperty("levelCreated." + lv, String.join(",", c)));
		levelOpened.forEach((lv, c) -> pr.setProperty("levelOpened." + lv, String.join(",", c)));
		levelVis.forEach((lv, m) -> m.forEach((o, v) -> pr.setProperty("levelVis." + lv + "." + o, v)));
		levelFired.forEach((lv, m) -> m.forEach((id, v) -> pr.setProperty("levelFired." + lv + "." + id, v)));
		levelEnemies.forEach((lv, l) -> pr.setProperty("levelEnemies." + lv, String.join(";", l)));
		levelPending.forEach((lv, l) -> pr.setProperty("levelPending." + lv, String.join(";", l)));
		levelAnims.forEach((lv, l) -> pr.setProperty("levelAnims." + lv, String.join(";", l)));
		levelBroken.forEach((lv, l) -> pr.setProperty("levelBroken." + lv, String.join(",", l)));
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
		g.other = pr.getProperty("other");
		g.otherActive = Boolean.parseBoolean(pr.getProperty("otherActive", "true"));
		String[] p = pr.getProperty("pos").split(",");
		for (int i = 0; i < 3; i++) {
			g.pos[i] = Float.parseFloat(p[i]);
		}
		g.yaw = Float.parseFloat(pr.getProperty("yaw", "0"));
		g.heroState = pr.getProperty("heroState");
		g.difficulty = Integer.parseInt(pr.getProperty("difficulty", "1"));
		g.costume = Integer.parseInt(pr.getProperty("costume", "0"));
		g.endGame = Integer.parseInt(pr.getProperty("endGame", "0"));
		g.hp = Float.parseFloat(pr.getProperty("hp"));
		g.mp = Float.parseFloat(pr.getProperty("mp"));
		g.mpRegen = Float.parseFloat(pr.getProperty("mpRegen", "0"));
		g.rage = Float.parseFloat(pr.getProperty("rage", "0"));
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
		g.savePoint = Integer.parseInt(pr.getProperty("savePoint", "0"));
		g.music = pr.getProperty("music", "");
		for (Weapon w : Weapon.values()) {
			String v = pr.getProperty("weapon." + w.name());
			if (v != null) {
				String[] a = v.split(",");
				g.weapons.put(w, new int[]{Integer.parseInt(a[0]), Integer.parseInt(a[1])});
			}
		}
		String ig = pr.getProperty("initedGlobals", "");
		if (!ig.isEmpty()) {
			g.initedGlobals.addAll(List.of(ig.split(";")));
		}
		String tm = pr.getProperty("timers");
		g.timers = tm != null ? parseFloats(tm) : null;
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
			} else if (k.startsWith("levelFired.")) {
				String rest = k.substring(11);
				int dot = rest.lastIndexOf('.');
				g.levelFired.computeIfAbsent(rest.substring(0, dot), x -> new TreeMap<>()).put(Integer.parseInt(rest.substring(dot + 1)), pr.getProperty(k));
			} else if (k.startsWith("levelCreated.")) {
				g.levelCreated.put(k.substring(13), list(pr.getProperty(k), ","));
			} else if (k.startsWith("levelOpened.")) {
				g.levelOpened.put(k.substring(12), list(pr.getProperty(k), ","));
			} else if (k.startsWith("levelEnemies.")) {
				g.levelEnemies.put(k.substring(13), list(pr.getProperty(k), ";"));
			} else if (k.startsWith("levelPending.")) {
				g.levelPending.put(k.substring(13), list(pr.getProperty(k), ";"));
			} else if (k.startsWith("levelAnims.")) {
				g.levelAnims.put(k.substring(11), list(pr.getProperty(k), ";"));
			} else if (k.startsWith("levelBroken.")) {
				g.levelBroken.put(k.substring(12), list(pr.getProperty(k), ","));
			}
		}
		String lt = pr.getProperty("levelTable", "");
		if (!lt.isEmpty()) {
			g.levelTable.addAll(java.util.List.of(lt.split(",")));
		}
		return g;
	}

	private static List<String> list(String v, String sep) {
		return v.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(v.split(sep)));
	}

	private static String floats(float[] v) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < v.length; i++) {
			sb.append(i > 0 ? "," : "").append(v[i]);
		}
		return sb.toString();
	}

	private static float[] parseFloats(String s) {
		String[] q = s.split(",");
		float[] v = new float[q.length];
		for (int i = 0; i < q.length; i++) {
			v[i] = Float.parseFloat(q[i]);
		}
		return v;
	}

	private static String encodeAnim(Platforms.State s) {
		return s.owner() + "|" + (s.anim() != null ? s.anim() : "") + "|" + s.time() + "|" + s.rate() + "|" + s.playing() + "|" + s.loop() + "|"
				+ s.pingPong() + "|" + s.solid() + "|" + s.lift();
	}

	private static Platforms.State decodeAnim(String e) {
		String[] f = e.split("\\|", -1);
		return new Platforms.State(f[0], f[1].isEmpty() ? null : f[1], Float.parseFloat(f[2]), Float.parseFloat(f[3]), Boolean.parseBoolean(f[4]),
				Boolean.parseBoolean(f[5]), Boolean.parseBoolean(f[6]), Boolean.parseBoolean(f[7]), Float.parseFloat(f[8]));
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
