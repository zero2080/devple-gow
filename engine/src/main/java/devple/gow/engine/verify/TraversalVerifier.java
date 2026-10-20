package devple.gow.engine.verify;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.engine.combat.EnemyFactory;
import devple.gow.engine.game.LevelEntry;
import devple.gow.engine.game.LevelLink;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.nav.Interactables;
import devple.gow.engine.nav.NavRope;
import devple.gow.engine.nav.Platforms;
import devple.gow.engine.nav.TaskProgress;
import devple.gow.engine.script.ScriptActor;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.anim.Mat4;
import devple.gow.tools.level.GoInstance;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.texture.TextureLibrary;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.video.MovieNames;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 전 레벨 순회 검증 (36 문서). GL 없이 데이터 클래스만 쓴다.
 * <pre>
 * 레벨마다: LevelData + 충돌 → 시작 지점(Start 모드 1 / start GO) 바닥 확인 → ScriptWorld(헤드리스 호스트) 시작
 *          → 스크립트 볼륨을 가까운 순서로 방문(볼륨 안 바닥 위 한 점에 1초씩) · 스폰된 적은 0.5초 뒤 제거 · 동영상은 다음 틱에 종료 이벤트
 *          → 마지막에 30초 더 진행 (지연 발동 처리)
 * 기록: 레벨 요청 → {@link LevelLink} 로 실제 전환(SEAMLESS / WARP / LOAD), 적 스폰 템플릿 해석, 동영상·메시지 존재,
 *       세이브 포인트, 예외 / 스택 불균형, 배치 인스턴스의 누락 리소스(MDL/MAT/TXR/ANM)
 * 전체: 도착 검사(이음 전환 위치에 대상 레벨 바닥, Warp 도착 Start) + ATHN01A 에서의 도달 그래프
 * </pre>
 */
public final class TraversalVerifier {

	/** 현재 레벨에서 스크립트 Vis 로 숨겨진 오브젝트 (소문자). */
	private final Set<String> hiddenObjects = new HashSet<>();

	public static final String START = "ATHN01A", END = "OLYMP02";
	/** 보너스 '신들의 도전' 첫/끝 아레나 (11 문서 §6.4, 메뉴에서 시작). */
	public static final String BONUS_START = "ARENA10", BONUS_END = "ARENA27";
	static final float DT = 1 / 30f, DWELL = 1f, FLUSH = 30f, KILL_AFTER = 0.5f;
	/** 시작 지점 위/아래 바닥 탐색 범위 (유닛, 1m ≈ 16). */
	static final float UP = 32, DROP = 600;
	/** LoadCheck 를 위치 이음으로 볼 대상 바닥 거리 (이보다 멀면 "현재 레벨 표지" 로만 본다). */
	static final float SEAMLESS_REACH = 256;
	/** 이음 도착 검사 허용 거리: 떠나온 레벨 충돌이 남아 있으므로 그 바닥으로 걸어가 WALK 전환 (36 문서 §3). */
	static final float WALK_REACH = 512;
	/** 이웃 레벨 바닥이 맞닿는지 볼 때의 칸 크기 (유닛). */
	static final float FLOOR_CELL = 48;

	/** 실제로 일어나는 전환 하나. pos = 요청 시 플레이어 위치, source = 그때 방문 중이던 볼륨, forced = 게임 이벤트 강제 뒤에야 나온 전환. */
	public record Edge(LevelLink.Kind kind, String target, boolean targetExists, float[] pos, String source, boolean forced) {
	}

	/** 원시 레벨 요청 (첫 발생). */
	public record Req(float[] pos, String source, boolean forced) {
	}

	/** 적 스폰: 템플릿 번호, 해석 결과(R_ WAD 이름 / "level" / null), 모델 출처. */
	public record Spawn(String entity, int templateId, String resolvedBy, String model) {
	}

	/** 도착 검사 결과 (2단계). */
	public record Arrival(String from, LevelLink.Kind kind, String detail, boolean ok) {
	}

	/** 레벨 하나의 결과. */
	public static final class LevelResult {
		public final String level;
		public int entities, volumes, visited, airborne;
		public float[] spawn;
		public String spawnSource;
		public boolean spawnGround;
		/** 시작 지점에서 가장 가까운 바닥 칸까지 거리 (바닥이 없을 때 보고용). */
		public float spawnFloorDistance;
		public final List<Edge> edges = new ArrayList<>();
		public final Set<String> requests = new LinkedHashSet<>();
		public final Set<String> badWads = new LinkedHashSet<>();
		public final Map<String, Spawn> spawns = new LinkedHashMap<>();
		public final Map<String, Boolean> movies = new TreeMap<>();
		public final Map<Integer, Boolean> messages = new TreeMap<>();
		public final Set<String> savePoints = new LinkedHashSet<>();
		/** 레벨의 세이브 포인트 Creator 수 (Name0 "savepoint", 발동 여부 무관). */
		public int savePointCreators;
		public final Map<String, Boolean> gotos = new TreeMap<>();
		/** Goto 를 일으킨 위치 (볼륨 안 보행 점) → Goto 이름. 이웃 레벨에서 이 볼륨을 밟아 들어오는 연결 판정용. */
		public final Map<String, Req> gotoFrom = new LinkedHashMap<>();
		public int checkpoints, heroDeathZones;
		public final List<String> exceptions = new ArrayList<>();
		public final Set<String> unbalanced = new LinkedHashSet<>();
		public final Set<String> missing = new LinkedHashSet<>();
		/** 오브젝트 정의(0x40001)가 없는 GO 인스턴스 (locator 같은 표지, 그리지 않음). */
		public int markerGos;
		public final List<Arrival> arrivals = new ArrayList<>();
		/** LoadWad / LoadForWarp 대상 (슬롯에 올라간 적이 있는 레벨). */
		public final Map<String, Req> loads = new LinkedHashMap<>();
		public final Set<String> warpLoads = new LinkedHashSet<>();
		public final Map<String, Req> loadChecks = new LinkedHashMap<>();
		/** LoadCheck 볼륨(영역) 안의 점들 (월드) — 도착 검사: 대상 레벨 바닥이 볼륨 안에 있는지. */
		final Map<String, List<float[]>> loadCheckSamples = new LinkedHashMap<>();
		public Req warp;
		/** 강제한 게임 이벤트 (EventId → 시도한 인자 수). */
		public final Map<Integer, Integer> forcedEvents = new TreeMap<>();
		/** 바닥 삼각형 표본 칸 (이웃 레벨 걸어 들어가기 판정용, {@link #FLOOR_CELL} 유닛). */
		Set<Long> floorCells = Set.of();
		/** 강제로 볼륨에 넣은 오브젝트 그룹 (밀어 넣는 상자 등, 진입 센서 Filter). */
		public final Set<Integer> forcedGroups = new java.util.TreeSet<>();
		/** 내부 점을 못 찾은 볼륨 (방문 불가). */
		public final List<String> unreachableVolumes = new ArrayList<>();
		/** 레벨의 줄 (짚라인·수평 줄 끝 → 다른 레벨 바닥 연결, 38 문서 §6). */
		public final List<NavRope> ropes = new ArrayList<>();
		/** R1 오브젝트 상호작용 (CSM 과제 진행 · 밀기 상자 Marker) — 강제 아님, 실제 게임 동작. */
		public final List<String> interactions = new ArrayList<>();
		/** 헤드리스 보스전 단계 기록 / 격파 여부 (보스 레벨만). */
		public final List<String> bossPhases = new ArrayList<>();
		public boolean bossDefeated;
		/** 끝났을 때 GlobalData (게임 전체 공유 — 이웃 레벨 재실행에 넘김). */
		public final Map<Integer, Object> globals = new TreeMap<>();
		/** 스크립트가 보낸 전역 이벤트 {id, 인자 1, 인자 2} (EventTransmitter 모드 5, 중복 제거). */
		public final List<Object[]> globalEvents = new ArrayList<>();
		/** 이 레벨 EventSensor 가 듣는 이벤트 번호 / 이웃에게서 받아 다시 보낸 전역 이벤트 번호. */
		final Set<Integer> heard = new HashSet<>(), replayed = new HashSet<>();
		/** 이웃 레벨 상태로 다시 돌렸으면 그 레벨들 (2 단계 실행). */
		public final Set<String> withNeighbours = new java.util.TreeSet<>();

		LevelResult(String level) {
			this.level = level;
		}

		public boolean hasErrors() {
			return !exceptions.isEmpty() || !unbalanced.isEmpty() || movies.containsValue(false) || messages.containsValue(false)
					|| edges.stream().anyMatch(e -> !e.targetExists()) || arrivals.stream().anyMatch(a -> !a.ok());
		}
	}

	public final Path rom;
	/** 디버그: 이 레벨을 돌릴 때 요청·동영상·스폰·상호작용을 시간순으로 출력 (시스템 속성 gow.trace = 레벨 이름). */
	public String trace = System.getProperty("gow.trace");
	public final Map<String, LevelResult> results = new TreeMap<>();
	private final WadFile permk;
	private final MessageTable messages;
	private final Set<String> permNames = new HashSet<>();
	/** 적 WAD 요약 (WAD 전체를 들고 있지 않도록 템플릿 번호와 모델 이름만). null = 적이 아닌 리소스 WAD. */
	record EnemyInfo(String wad, Set<Integer> ai1, String model) {
	}

	private final Map<String, EnemyInfo> kinds = new HashMap<>();
	private final Map<String, Set<String>> wadNames = new HashMap<>();

	public TraversalVerifier(Path rom) throws IOException {
		this.rom = rom;
		this.permk = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		this.messages = MessageTable.fromWad(permk).orElseThrow();
		permNames.addAll(names(permk));
	}

	/** 레벨 WAD 이름 (R_ 리소스 제외, 대문자). */
	public List<String> levels() throws IOException {
		try (Stream<Path> s = Files.list(rom)) {
			return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".WAD") && !n.startsWith("R_"))
					.map(n -> n.substring(0, n.length() - 4)).sorted().toList();
		}
	}

	public boolean wadExists(String name) {
		return Files.isRegularFile(Content.file(rom, name.toUpperCase(Locale.ROOT) + ".WAD"));
	}

	private boolean movieExists(String name) {
		String b = MovieNames.fileBase(name);
		return Files.isRegularFile(Content.file(rom, b + ".PSS")) || Files.isRegularFile(Content.file(rom, b + ".PSW"));
	}

	private static Set<String> names(WadFile w) {
		Set<String> s = new HashSet<>();
		for (WadTag t : w.resources()) {
			s.add(t.name());
		}
		return s;
	}

	/** 전 레벨 실행 후 도착 검사. */
	public TraversalVerifier runAll() throws IOException {
		for (String l : levels()) {
			results.put(l, run(l));
		}
		rerunWithNeighbours();
		resolveEdges();
		checkArrivals();
		return this;
	}

	/** 이웃 레벨에서 넘어오는 상태: 끝난 GlobalData 합집합, 보낸 전역 이벤트. */
	record Neighbours(Set<String> levels, Map<Integer, Object> globals, List<Object[]> events) {
	}

	/**
	 * 2 단계: 숨긴 오브젝트를 숨긴 채로만 쓴 레벨을, 2 걸음 안 이웃 레벨(전환·LoadWad·LoadCheck)이 끝났을 때의 GlobalData 와 그 레벨들이
	 * 보낸 전역 이벤트를 받아 다시 돌린다. 원작은 GlobalData 가 게임 전체에서 이어지고 두 슬롯 스크립트가 이벤트 큐 하나를 쓴다
	 * (ATHN01E 선장 열쇠 GlobalData:0 → ATHN01D 선장 문, PAND00C 손잡이 GlobalData:3 → PAND01BB 석상 크랭크). 숨김 상호작용이 줄면 결과를 바꾼다.
	 * 남는 것: PAND00C 엘리베이터 레버 — 고리 5 개 위치(LevelData 3/4/7/8/9) 정답 회전 + PAND00D 와 203/204/202/205 왕복 (퍼즐 풀이 없음).
	 */
	void rerunWithNeighbours() throws IOException {
		// 앞 레벨의 개선이 뒤 레벨 상태가 된다 (PAND01BB 손잡이 GlobalData:4 → PAND00C 고리 퍼즐 204) → 바뀌지 않을 때까지 (최대 3 회)
		for (int round = 0; round < 3 && rerunOnce(); round++) {
		}
	}

	private boolean rerunOnce() throws IOException {
		boolean changed = false;
		for (String l : List.copyOf(results.keySet())) {
			LevelResult r = results.get(l);
			long hidden = r.interactions.stream().filter(x -> x.startsWith(HIDDEN_USE)).count();
			Neighbours nb = neighbours(l);
			// 이웃이 새로 보낸 (이 레벨이 듣고, 스스로 보내지 않고, 아직 받아 본 적 없는) 전역 이벤트 — 두 레벨 왕복 (PAND00C 204 → PAND00D → 205)
			Set<Integer> own = new HashSet<>();
			r.globalEvents.forEach(e -> own.add((Integer) e[0]));
			boolean fresh = nb.events().stream().map(e -> (Integer) e[0])
					.anyMatch(id -> r.heard.contains(id) && !own.contains(id) && !r.replayed.contains(id));
			if (hidden == 0 && !fresh) {
				continue;
			}
			LevelResult again = run(l, nb);
			Set<Integer> before = new HashSet<>(own), after = new HashSet<>();
			again.globalEvents.forEach(e -> after.add((Integer) e[0]));
			boolean moreEvents = after.containsAll(before) && after.size() > before.size();
			if (again.interactions.stream().filter(x -> x.startsWith(HIDDEN_USE)).count() < hidden || moreEvents) {
				merge(r, again); // 퍼즐 전·후 상태 둘 다 게임에서 가능한 경로 (PAND00C: 전 = LoadWad 03J, 후 = 03F)
				again.withNeighbours.addAll(nb.levels());
				results.put(l, again);
				changed = true;
			}
		}
		return changed;
	}

	/** 원래 실행의 요청(적재·LoadCheck·Goto·Warp·Load 전환)을 다시 돌린 결과에 합친다: 없는 것은 그대로, 둘 다 있으면 강제가 덜한 쪽. */
	private static void merge(LevelResult a, LevelResult b) {
		for (var m : List.of(java.util.Map.entry(a.loads, b.loads), java.util.Map.entry(a.loadChecks, b.loadChecks),
				java.util.Map.entry(a.gotoFrom, b.gotoFrom))) {
			for (var e : m.getKey().entrySet()) {
				m.getValue().merge(e.getKey(), e.getValue(), (nw, old) -> nw.forced() && !old.forced() ? old : nw);
			}
		}
		b.warpLoads.addAll(a.warpLoads);
		if (b.warp == null || b.warp.forced() && a.warp != null && !a.warp.forced()) {
			b.warp = a.warp;
		}
		for (Edge e : a.edges) {
			if (b.edges.stream().noneMatch(x -> x.kind() == e.kind() && x.target().equals(e.target()) && (!x.forced() || e.forced()))) {
				b.edges.add(e);
			}
		}
		a.loadCheckSamples.forEach(b.loadCheckSamples::putIfAbsent);
	}

	/** 2 걸음 안 이웃의 끝난 GlobalData 합집합과 보낸 전역 이벤트. */
	private Neighbours neighbours(String l) {
		Set<String> near = new java.util.TreeSet<>();
		for (String n : linked(l)) {
			near.add(n);
			near.addAll(linked(n));
		}
		near.remove(l);
		Map<Integer, Object> g = new TreeMap<>();
		List<Object[]> ev = new ArrayList<>();
		for (String n : near) {
			LevelResult x = results.get(n);
			if (x != null) {
				x.globals.forEach((k, v) -> g.merge(k, v, (a, b) -> truthy(a) ? a : b));
				ev.addAll(x.globalEvents);
			}
		}
		return new Neighbours(near, g, ev);
	}

	private static boolean truthy(Object v) {
		return v instanceof Boolean b ? b : v instanceof Number n && n.doubleValue() != 0;
	}

	/** 레벨이 직접 잇는 레벨 (요청한 LoadWad·LoadForWarp·LoadCheck·Load, 또는 그 레벨을 요청한 레벨). */
	private Set<String> linked(String l) {
		Set<String> out = new HashSet<>();
		LevelResult r = results.get(l);
		if (r == null) {
			return out;
		}
		out.addAll(r.loads.keySet());
		out.addAll(r.loadChecks.keySet());
		r.edges.forEach(e -> out.add(e.target()));
		for (LevelResult x : results.values()) {
			if (x.loads.containsKey(l) || x.loadChecks.containsKey(l) || x.edges.stream().anyMatch(e -> e.target().equals(l))) {
				out.add(x.level);
			}
		}
		out.retainAll(results.keySet());
		return out;
	}

	// ---- 1단계: 레벨 하나 ----

	public LevelResult run(String levelName) throws IOException {
		return run(levelName, null);
	}

	LevelResult run(String levelName, Neighbours nb) throws IOException {
		// 적 AI 난수 씨앗 = 생성 순번 (EnemyFighter.spawnCount, JVM 전역) — 레벨마다 0 부터: 앞선 레벨·같은 JVM 의 다른 테스트가 만든 적 수에
		// 결과가 좌우되지 않게 (전체 :engine:test 와 단독 실행의 ATHN02D 메두사 단계가 달랐다, 48 문서 §5)
		devple.gow.engine.combat.EnemyFighter.resetSpawnCount();
		devple.gow.engine.combat.AiTargets.clear(); // 앞 레벨 대상 슬롯 (보스 헤드리스 영웅) 정리
		LevelResult r = new LevelResult(levelName);
		LevelData lv = new LevelData(WadFile.load(Content.file(rom, levelName + ".WAD")));
		LevelCollision col = new LevelCollision(lv.sheets);
		r.floorCells = floorCells(col);
		checkResources(lv, r);
		Tweaks levelTweaks = new Tweaks();
		levelTweaks.load(lv.wad);
		List<EnemyInfo> levelKinds = enemyKinds(lv, r);
		float[] player = new float[3];
		boolean[] forced = {false};
		String[] currentSource = {null};
		List<ScriptActor> spawned = new ArrayList<>();
		Map<ScriptActor, Float> spawnTime = new HashMap<>();
		List<String> movieDone = new ArrayList<>();
		ScriptWorld[] wref = new ScriptWorld[1];
		Map<String, Target> targetsByName = new HashMap<>();
		devple.gow.engine.boss.HeadlessBoss[] bossRef = new devple.gow.engine.boss.HeadlessBoss[1];
		devple.gow.engine.nav.Platforms[] platforms = new devple.gow.engine.nav.Platforms[1];
		boolean tr = levelName.equalsIgnoreCase(trace);
		java.util.function.Consumer<String> log = m -> {
			if (tr) {
				System.out.printf(Locale.ROOT, "[%s %.1f] %s @ %s%n", levelName, wref[0] == null ? 0 : wref[0].time(), m, currentSource[0]);
			}
		};

		// 메시지가 닫히면(표시 시간 뒤) 플레이어가 1040(번호) — GameScene 과 같음 (원작 0x1B8ECC, 아레나 "완료" 메시지 → 워프)
		Map<Integer, Float> msgDue = new java.util.LinkedHashMap<>();
		float[] clock = {0};
		hiddenObjects.clear();
		usedVisible.clear();
		ScriptListener listener = new ScriptListener() {
			@Override
			public void vis(ScriptEntity source, List<String> objects, int[] values) {
				// Visible 1/2: 숨긴 오브젝트는 R1 상호작용 안 함 (GameScene 과 같음)
				if (values.length > 0 && (values[0] == 1 || values[0] == 2)) {
					for (String o : objects) {
						if (values[0] == 2) {
							hiddenObjects.add(o.toLowerCase(Locale.ROOT));
						} else {
							hiddenObjects.remove(o.toLowerCase(Locale.ROOT));
						}
					}
				}
			}

			@Override
			public void message(int id, float sec) {
				r.messages.put(id, messages.get(id) != null);
				msgDue.put(id, clock[0] + (sec > 0 ? sec : 4f));
			}

			@Override
			public void checkpoint(ScriptEntity s) {
				r.checkpoints++;
			}

			@Override
			public void internal(int func, List<Object> args) {
				// 질문 (Internal 0xB) → 예 (1021 인자 0 = 1, 인자 1 = 메시지 번호) — GameScene × 와 같음. PAND01C 손잡이 1019 → 메시지 258 → LoadWad Pand01B
				if (func == 0xB && !args.isEmpty() && args.get(0) instanceof Number n) {
					r.messages.put(n.intValue(), messages.get(n.intValue()) != null);
					wref[0].postEvent(1021, 1, n.intValue(), 0, wref[0].player());
				}
			}

			@Override
			public void level(LevelRequest q) {
				log.accept("level " + q.op() + " " + q.wad() + " " + q.entry());
				r.requests.add(q.op() + (q.wad() != null ? " " + q.wad() : "") + (q.entry() != null ? " " + q.entry() : ""));
				if (q.op() == LevelOp.GOTO) {
					r.gotos.put(q.entry(), q.pos() != null);
					r.gotoFrom.putIfAbsent(q.entry(), new Req(player.clone(), currentSource[0], forced[0]));
					if (q.pos() != null) {
						System.arraycopy(q.pos(), 0, player, 0, 3);
						if (bossRef[0] != null) {
							bossRef[0].moveHero(q.pos()[0], q.pos()[1], q.pos()[2]);
						}
					}
					return;
				}
				if (q.wad() != null && !wadExists(q.wad())) {
					r.badWads.add(q.op() + " " + q.wad());
					return;
				}
				Req req = new Req(player.clone(), currentSource[0], forced[0]);
				String wad = q.wad();
				switch (q.op()) {
					case LOAD_WAD, LOAD_FOR_WARP -> {
						if (!wad.equals(levelName)) {
							r.loads.putIfAbsent(wad, req);
							if (q.op() == LevelOp.LOAD_FOR_WARP) {
								r.warpLoads.add(wad);
							}
						}
					}
					case LOAD_CHECK -> {
						if (!wad.equals(levelName)) {
							r.loadChecks.putIfAbsent(wad, req);
							Target t = targetsByName.get(currentSource[0]);
							if (t != null) {
								r.loadCheckSamples.putIfAbsent(wad, volumeSamples(t.volume(), t.region()));
							}
						}
					}
					case WARP -> {
						if (r.warp == null) {
							r.warp = req;
						}
					}
					case LOAD -> r.edges.add(new Edge(LevelLink.Kind.LOAD, wad, true, req.pos(), req.source(), req.forced()));
					default -> {
					}
				}
			}

			@Override
			public boolean loadCheck(String wad) {
				return r.loads.containsKey(wad);
			}

			@Override
			public void deathZone(ScriptActor a, ScriptEntity s, int ev, int arg) {
				if (a == wref[0].player()) {
					r.heroDeathZones++;
				}
			}

			@Override
			public void spawnEnemy(SpawnRequest q) {
				log.accept("spawn " + q.templateId() + " " + q.source().name());
				String key = q.source().name() + "/" + q.templateId();
				if (bossRef[0] != null && bossRef[0].spawn(q)) {
					// 보스 리소스 템플릿 (레벨 WAD 안, 37 문서) — 보스전이 맡는다 (죽이지 않음)
					var k = bossRef[0].fight.kindFor(q.templateId());
					r.spawns.computeIfAbsent(key, x -> new Spawn(q.source().name(), q.templateId(), "boss",
							k != null ? bossRef[0].fight.getClass().getSimpleName() + ":" + k.model() : null));
					return;
				}
				if (killMatters(wref[0], q.actor())) {
					spawned.add(q.actor());
					spawnTime.put(q.actor(), wref[0].time());
				}
				r.spawns.computeIfAbsent(key, k -> resolveSpawn(q, levelKinds, levelTweaks, lv));
			}

			@Override
			public void animate(ScriptEntity source, List<String> objects, String anim) {
				// Animator → 관절 애니 (GameScene SceneNav 와 같음): 회전방 벽 관절의 Marker 가 진입 센서 볼륨을 지난다 (PAND02AA 201/202)
				if (platforms[0] == null) {
					return;
				}
				int[] mode = new int[1];
				float[] speed = {1}, start = new float[1];
				devple.gow.engine.game.SceneNav.animatorArgs(wref[0], source, mode, speed, start);
				for (String o : objects) {
					if (platforms[0].play(o, anim, mode[0], speed[0], start[0])) {
						log.accept("animate " + o + " " + anim + " 0x" + Integer.toHexString(mode[0]));
					}
				}
			}

			@Override
			public void objectEvent(ObjectEvent e) {
				if (bossRef[0] != null) {
					bossRef[0].objectEvent(e);
				}
			}

			@Override
			public void movie(String name, int op, int arg) {
				log.accept("movie " + name + " op " + op);
				r.movies.put(name, movieExists(name));
				if (op == 0) {
					movieDone.add(name);
				}
			}

			@Override
			public void create(ScriptEntity s) {
				Object n = wref[0].slot(s, "Name0");
				if ("savepoint".equals(n)) {
					r.savePoints.add(s.name());
				}
			}
		};
		ScriptWorld w = new ScriptWorld(lv, new ScriptHost(), listener);
		wref[0] = w;
		levelItems = Interactables.of(lv, levelTweaks);
		platforms[0] = devple.gow.engine.nav.Platforms.of(lv, devple.gow.engine.game.SceneNav.animatorOwners(w),
				devple.gow.engine.game.SceneNav.posedOwners(levelItems));
		levelPlatforms = platforms[0];
		if (devple.gow.engine.boss.BossDirector.hostsBoss(levelName)) {
			bossRef[0] = devple.gow.engine.boss.HeadlessBoss.create(levelName, rom, permk, lv.wad, w);
		}
		w.onUnbalanced(r.unbalanced::add);
		r.entities = w.entities().size();
		for (ScriptEntity e : w.entities()) {
			if (e.type() == 4) {
				r.heard.add(intSlot(w, e, "EventId"));
			}
		}
		r.savePointCreators = (int) w.entities().stream().filter(e -> e.type() == 11 && "savepoint".equals(w.slot(e, "Name0"))).count();

		// 시작 지점 (GameScene 과 같은 규칙)
		LevelEntry.Spot spot = LevelEntry.resolve(lv, w, null, null);
		r.spawn = spot.pos();
		r.spawnSource = spot.source();
		r.spawnGround = !Float.isNaN(col.groundHeight(r.spawn[0], r.spawn[1] + UP, r.spawn[2], DROP));
		float[] snap = LevelEntry.snapToFloor(col, r.spawn, 256);
		r.spawnFloorDistance = r.spawnGround ? 0 : snap != null ? (float) Math.hypot(snap[0] - r.spawn[0], snap[2] - r.spawn[2]) : Float.MAX_VALUE;
		System.arraycopy(r.spawn, 0, player, 0, 3);

		w.tapGlobalEvents(e -> {
			if (r.globalEvents.stream().noneMatch(x -> java.util.Arrays.equals(x, e))) {
				r.globalEvents.add(e);
			}
		});
		try {
			w.start();
			if (nb != null) {
				// GlobalData 는 게임 전체 공유: 이웃 레벨이 끝났을 때 값 (시작 초기화 뒤에 덮어씀)
				w.host().globalData().putAll(nb.globals());
			}
			w.placePlayer(player[0], player[1], player[2], false);
		} catch (RuntimeException ex) {
			r.exceptions.add("start: " + ex);
			return r;
		}

		// 방문할 (볼륨, 영역): 엔티티가 붙은 것 전부, 가까운 순서
		List<Target> todo = new ArrayList<>(new LinkedHashSet<>(
				w.entities().stream().filter(e -> e.volume != null).map(e -> new Target(e.volume, ScriptWorld.regionOf(e))).toList()));
		r.volumes = todo.size();
		for (Target t : todo) {
			targetsByName.putIfAbsent(t.volume().hull().name + (t.region() >= 0 ? "#" + t.region() : ""), t);
		}
		Map<Target, float[]> points = new HashMap<>();
		for (Target v : todo) {
			float[] p = interior(v.volume(), col, v.region());
			if (p != null) {
				points.put(v, p);
				if (p[3] != 0) {
					r.airborne++;
				}
			}
		}
		todo.stream().filter(v -> !points.containsKey(v)).forEach(v -> r.unreachableVolumes.add(v.volume().hull().name + "#" + v.region()
				+ "@" + v.volume().owner()));
		todo.removeIf(v -> !points.containsKey(v));
		Runnable step = () -> {
			try {
				for (String m : movieDone) {
					// GameScene 과 같이 보낸 쪽 = 플레이어 (Filter 1 인 …MpegDone 센서, 37 문서 §3.1). 전엔 null 이라 DEST03 d01Mp01 끝이 안 들렸다
					w.postEvent(ScriptWorld.EVENT_MOVIE_DONE, m, 0, 0, w.player());
				}
				movieDone.clear();
				platforms[0].update(DT);
				devple.gow.engine.game.SceneNav.poseItems(levelItems, platforms[0], w); // R1 오브젝트 자세 = 진행 시간 (링 크랭크 Marker)
				for (devple.gow.engine.nav.Platforms.Body b : platforms[0].bodies()) {
					if (b.playing()) {
						w.poseOwner(b.owner, b.joints());
					}
				}
				w.tick(DT, player[0], player[1], player[2]);
				clock[0] = w.time();
				for (var it = msgDue.entrySet().iterator(); it.hasNext(); ) {
					var m = it.next();
					if (m.getValue() <= clock[0]) {
						it.remove();
						w.postEvent(1040, m.getKey(), 0, 0, w.player());
					}
				}
				for (ScriptActor a : List.copyOf(spawned)) {
					if (!a.destroyed() && w.time() - spawnTime.get(a) > KILL_AFTER) {
						// 볼륨 안 파괴만 세는 센서(DestructionSensor mode 4, 예 ARENA10 FallenZone "단상 밖으로 떨어뜨리기")가 이 그룹을 보면
						// 그 볼륨 안에서 처치 (플레이어가 밀어 떨어뜨린 것 대신)
						float[] in = destructionZone(w, a);
						if (in != null) {
							a.moveTo(in[0], in[1], in[2]);
						}
						w.destroy(a);
					}
				}
				spawned.removeIf(ScriptActor::destroyed);
			} catch (RuntimeException ex) {
				if (r.exceptions.size() < 20) {
					r.exceptions.add(String.format(Locale.ROOT, "t=%.1f: %s", w.time(), ex));
				}
			}
		};
		walk(todo, points, player, currentSource, step, r);
		r.ropes.addAll(NavRope.of(lv));
		// R1 상호작용 ↔ 보행 2회: 과제가 앞 단계 진행(적 처치·동영상 끝)을 조건으로 하는 레벨이 있다 (DEST03 두 번째 뿔피리 = LevelData3)
		// 부서지는 물체(Breakable) 전부 파괴 — 플레이어가 때려 부순 것 대신 (41 문서, ARENA06 "all broken" 36 개 → 2008 → 1040 → Warp)
		currentSource[0] = "(물체 파괴)";
		for (var b : lv.behaviors.values()) {
			if (b.type().equals("Breakable")) {
				for (ScriptActor m : List.copyOf(w.markers(b.owner()))) {
					w.destroy(m);
				}
			}
		}
		for (float t = 0; t < 6f; t += DT) {
			step.run(); // 성공 메시지 Delay·표시 시간 → 1040
		}
		for (int round = 0; round < 2; round++) {
			interact(lv, w, levelTweaks, col, points, player, currentSource, step, r);
			walk(new ArrayList<>(points.keySet()), points, player, currentSource, step, null);
		}
		if (nb != null) {
			// 이웃 레벨 스크립트가 보낸 전역 이벤트 (원작은 두 슬롯이 큐 하나 — PAND00D 고리 205 → PAND00C). 이 레벨이 듣고 스스로는 안 보내는 것만
			Set<Integer> own = new HashSet<>(), heard = r.heard;
			r.globalEvents.forEach(e -> own.add((Integer) e[0]));
			for (ScriptEntity e : w.entities()) {
				if (e.type() == 4) {
					heard.add(intSlot(w, e, "EventId"));
				}
			}
			for (Object[] e : nb.events()) {
				int id = (Integer) e[0];
				if (!heard.contains(id) || own.contains(id)) {
					continue;
				}
				r.replayed.add(id);
				currentSource[0] = "이웃 레벨 이벤트 " + id;
				w.postEvent(id, e[1], e[2], 0, w.player());
				for (float t = 0; t < DWELL / 2; t += DT) {
					step.run();
				}
			}
			interact(lv, w, levelTweaks, col, points, player, currentSource, step, r);
			walk(new ArrayList<>(points.keySet()), points, player, currentSource, step, null);
		}
		// 전투 마무리: 남은 적(죽이면 스크립트가 반응하는 것)이 다 죽고 재생성(MaximumCount)이 끝날 때까지 — 파도 전투 (ATHN08B 분신 25)
		currentSource[0] = "(전투)";
		for (float t = 0, quiet = 0; !spawnTime.isEmpty() && t < COMBAT_MAX && quiet < COMBAT_QUIET; t += DT) {
			step.run();
			quiet = spawned.isEmpty() ? quiet + DT : 0; // 다음 파도는 지연(Delay·재생성 0.25 초) 뒤에 나오므로 잠깐 비어도 계속
		}
		if (bossRef[0] != null) {
			bossPhase(bossRef[0], w, player, movieDone, currentSource, step, r);
		}
		// 보스·전투 뒤 다시 상호작용: 보스를 쓰러뜨린 뒤에야 열리는 것 (ATHN01E 히드라 입 속 선장 1024 → a01MP04 → LoadWad Athn01D)
		interact(lv, w, levelTweaks, col, points, player, currentSource, step, r);
		walk(new ArrayList<>(points.keySet()), points, player, currentSource, step, null);
		// 게임 이벤트 강제 2회: 스크립트가 보내지 않는 이벤트(동영상 끝·보스 사망·과제 완료 …)를 조건 상수 조합으로 → 다시 보행
		Set<Integer> scriptEvents = new HashSet<>();
		for (ScriptEntity e : w.entities()) {
			if (e.type() == 8 && e.mode() == 5) {
				scriptEvents.add(intSlot(w, e, "EventId"));
			}
		}
		forced[0] = true;
		for (int round = 0; round < 2; round++) {
			for (ScriptEntity e : w.entities()) {
				if (e.type() != 4) {
					continue;
				}
				int id = intSlot(w, e, "EventId");
				if (scriptEvents.contains(id) || id == ScriptWorld.EVENT_MOVIE_DONE && round > 0) {
					continue;
				}
				Target et = e.volume != null ? new Target(e.volume, ScriptWorld.regionOf(e)) : null;
				if (e.mode() == 2 && et != null && points.containsKey(et)) {
					System.arraycopy(points.get(et), 0, player, 0, 3);
					step.run();
				}
				ScriptActor sender = fuzzSender(w, e, player);
				List<Object> vals = conditionConstants(e);
				for (Object a0 : vals) {
					for (Object a1 : vals) {
						w.postEvent(id, a0, a1, 0, sender);
					}
				}
				r.forcedEvents.merge(id, vals.size() * vals.size(), Integer::sum);
				currentSource[0] = "이벤트 " + id + " (" + e.name() + ")";
				for (float t = 0; t < DWELL / 2; t += DT) {
					step.run();
				}
			}
			// 영웅이 아닌 오브젝트(그룹 = 진입 센서 Filter, 예 PAND05B 판도라 상자 511)를 그 볼륨에 넣는다
			for (ScriptEntity e : w.entities()) {
				int f = e.type() == 0 ? intSlot(w, e, "Filter") : 0;
				Target et = e.volume != null ? new Target(e.volume, ScriptWorld.regionOf(e)) : null;
				if (f <= 1 || et == null || !points.containsKey(et)) {
					continue;
				}
				ScriptActor obj = w.addActor("강제그룹" + f, f, new float[]{1e6f, 1e6f, 1e6f});
				step.run();
				float[] p = points.get(et);
				obj.moveTo(p[0], p[1], p[2]);
				r.forcedGroups.add(f);
				currentSource[0] = "그룹 " + f + " (" + e.name() + ")";
				for (float t = 0; t < DWELL / 2; t += DT) {
					step.run();
				}
				obj.moveTo(1e6f, 1e6f, 1e6f);
				step.run();
			}
			walk(new ArrayList<>(points.keySet()), points, player, currentSource, step, null);
		}
		currentSource[0] = "(대기)";
		for (float t = 0; t < FLUSH; t += DT) {
			step.run();
		}
		r.globals.putAll(w.host().globalData());
		// 끝난 뒤 숨는 것(열린 문·처치한 선장)은 앞 회차에서 보일 때 이미 썼다 → 표시 안 함
		r.interactions.removeIf(x -> x.startsWith(HIDDEN_USE) && usedVisible.contains(x.substring(HIDDEN_USE.length())));
		return r;
	}

	/**
	 * R1 오브젝트 상호작용 (38 문서 §3, 강제 아님): 영웅을 오브젝트 옆에 두고 원작과 같은 이벤트를 보낸다.
	 * <ul>
	 *   <li>CSM / 상자: 과제 진행 1023 (인자 0 = 10, 20 … 100 %, 인자 1 = 과제 id) — ATHN01D 쌍발차기 문 (과제 206, ≥ 30 %)</li>
	 *   <li>PushPull: 붙잡기 1048 (id) → 상자 Marker 를 그 Marker 그룹을 기다리는 진입 센서 볼륨으로 옮김 → 놓기 1049
	 *       (PAND05B 판도라 상자 Marker 511 → semoMovieHaveBox) [근사: 경로 탐색 없이 순간 이동]</li>
	 * </ul>
	 */
	private void interact(LevelData lv, ScriptWorld w, Tweaks tw, LevelCollision col, Map<Target, float[]> points, float[] player,
			String[] currentSource, Runnable step, LevelResult r) {
		Interactables items = levelItems; // 레벨당 하나 (진행 상태·자세 유지)
		for (ScriptEntity e : w.entities()) {
			if (e.type() == 11 && "chest".equals(w.slot(e, "Name0")) && e.owner != null
					&& items.items().stream().noneMatch(x -> x.owner.equalsIgnoreCase(e.owner.instance().name()))) {
				float[] gp = e.owner.instance().pos();
				items.addChest(e.owner.instance().name(), new org.joml.Vector3f(gp[0], gp[1], gp[2]), intSlot(w, e, "unk5"), intSlot(w, e, "unk6"), tw);
			}
		}
		// 보이는 것 먼저 → 버튼 안내 센서(1024) → 그 사이 드러난 것 → 끝까지 숨은 것 (PAND05A 지구본 손잡이는 석상 R2 1024 뒤에 보인다)
		List<Interactables.Item> pending = new ArrayList<>(items.items());
		boolean buttons = false, last = false;
		while (!pending.isEmpty() || !buttons) {
			Interactables.Item it = null;
			for (Interactables.Item c : pending) {
				if (last || !hiddenObjects.contains(c.owner.toLowerCase(Locale.ROOT))) {
					it = c;
					break;
				}
			}
			if (it == null) {
				if (!buttons) {
					pressButtons(w, points, player, currentSource, step, r);
					buttons = true;
				} else {
					last = true;
				}
				continue;
			}
			pending.remove(it);
			if (!hiddenObjects.contains(it.owner.toLowerCase(Locale.ROOT))) {
				usedVisible.add(it.owner);
			} else if (!usedVisible.contains(it.owner)) {
				// 게임에서는 숨겨진 오브젝트를 잡을 수 없다. 하네스는 레벨을 하나씩 돌려 두 레벨에 걸친 퍼즐(PAND00C/00D 고리 → 204/205 → LeverOn)을
				// 재현하지 못하므로 상호작용은 하되 표시만 남긴다 [하네스 근사]
				addOnce(r.interactions, HIDDEN_USE + it.owner);
			}
			float g = col.groundHeight(it.pos.x, it.pos.y + UP, it.pos.z, DROP);
			player[0] = it.pos.x;
			player[1] = Float.isNaN(g) ? it.pos.y : g + 4;
			player[2] = it.pos.z;
			currentSource[0] = "R1 " + it.owner;
			switch (it.kind) {
				case CSM, CHEST, LIFT -> {
					runTask(w, it, step);
					addOnce(r.interactions, it.kind + " " + it.owner + " 과제 " + it.id);
				}
				case LEVER -> {
					// 레버·크랭크 (PushPull): 붙잡기 1048 → 애니 재생 동안 1023 (원작 0x2118E8, 인자 1 = PushPull id) → 놓기 1049
					w.postEvent(devple.gow.engine.game.SceneNav.EVENT_ATTACH, it.id, 0, 0, w.player());
					step.run();
					if (it.pushType == 4) {
						int d = solveRing(w, it, step);
						addOnce(r.interactions, "RING " + it.owner + " id " + it.id + " → " + d + "/4");
						w.postEvent(devple.gow.engine.game.SceneNav.EVENT_DETACH, it.id, 0, 0, w.player());
						break;
					}
					runTask(w, it, step);
					w.postEvent(devple.gow.engine.game.SceneNav.EVENT_DETACH, it.id, 0, 0, w.player());
					addOnce(r.interactions, "LEVER " + it.owner + " id " + it.id);
				}
				case PUSH -> {
					w.postEvent(devple.gow.engine.game.SceneNav.EVENT_ATTACH, it.id, 0, 0, w.player());
					step.run();
					for (ScriptActor m : w.markers(it.owner)) {
						for (ScriptEntity e : w.entities()) {
							Target et = e.volume != null ? new Target(e.volume, ScriptWorld.regionOf(e)) : null;
							if (e.type() != 0 || et == null || !points.containsKey(et) || intSlot(w, e, "Filter") != m.group) {
								continue;
							}
							float[] p = points.get(et);
							float[] was = m.pos.clone();
							currentSource[0] = "밀기 " + it.owner + " → " + e.volume.hull().name;
							m.moveTo(p[0], p[1], p[2]);
							for (float t = 0; t < DWELL / 2; t += DT) {
								step.run();
							}
							m.moveTo(was[0], was[1], was[2]);
							step.run();
							addOnce(r.interactions, "PUSH " + it.owner + " Marker " + m.group + " → " + e.volume.hull().name);
						}
					}
					w.postEvent(devple.gow.engine.game.SceneNav.EVENT_DETACH, it.id, 0, 0, w.player());
				}
				default -> {
				}
			}
			// 오브젝트 애니가 끝날 때까지 (PAND02AA 회전방 51.7 초 ÷ 속도 0.9 — 끝에서야 Marker 202 가 spinRmEnd 에 든다)
			for (float t = 0; t < DWELL / 2 || animating() && t < ANIM_MAX; t += DT) {
				step.run();
			}
		}
	}

	/** 버튼 안내 센서 (1024, mode 2 = 볼륨 안 영웅): 그 볼륨 안에서 누름 — GameScene R1 과 같음. */
	private void pressButtons(ScriptWorld w, Map<Target, float[]> points, float[] player, String[] currentSource, Runnable step, LevelResult r) {
		for (ScriptEntity e : w.entities()) {
			Target et = e.volume != null ? new Target(e.volume, ScriptWorld.regionOf(e)) : null;
			if (e.type() != 4 || e.mode() != 2 || intSlot(w, e, "EventId") != devple.gow.engine.game.SceneNav.EVENT_USE
					|| et == null || !points.containsKey(et)) {
				continue;
			}
			System.arraycopy(points.get(et), 0, player, 0, 3);
			currentSource[0] = "버튼 " + e.name();
			step.run();
			w.postEvent(devple.gow.engine.game.SceneNav.EVENT_USE, 0, 0, 0, w.player());
			for (float t = 0; t < DWELL / 2; t += DT) {
				step.run();
			}
			addOnce(r.interactions, "USE 1024 " + e.name());
		}
	}

	/** 보고서: 숨긴 상태로만 상호작용한 오브젝트 (게임에서는 못 함 → 앞 조건을 하네스가 재현 못 함). */
	static final String HIDDEN_USE = "숨김 상태 상호작용(두 레벨 퍼즐 미재현) ";
	/** 이 레벨에서 보이는 상태로 상호작용한 오브젝트. */
	private final Set<String> usedVisible = new HashSet<>();

	/** 지금 돌리는 레벨의 R1 오브젝트 (진행 상태를 상호작용 회차 사이에 유지). */
	private Interactables levelItems;

	/** 지금 돌리는 레벨의 Animator 관절 애니 (발판·회전방). */
	private devple.gow.engine.nav.Platforms levelPlatforms;
	/** 상호작용 뒤 오브젝트 애니를 기다리는 최대 시간 (초). */
	static final float ANIM_MAX = 90, WALK_ANIM_MAX = 20;

	private boolean animating() {
		return levelPlatforms != null && levelPlatforms.bodies().stream().anyMatch(devple.gow.engine.nav.Platforms.Body::playingOnce);
	}

	/** 전투 마무리 최대 시간 / 적이 없는 채로 기다리는 시간 (마지막 처치 뒤 Delay 6 초 동영상 — ATHN08B winEnt) (초). */
	static final float COMBAT_MAX = 180, COMBAT_QUIET = 8, BOSS_MAX = 240, BOSS_AFTER = 20;

	/**
	 * 죽였을 때 스크립트가 반응하는 적인지: DestructionSensor (모드 2 만든 SpawnEnemy 의 GO / 3 Filter 그룹 / 4 볼륨) 또는 만든 SpawnEnemy 의
	 * Respawn. 반응이 없는 것은 죽이지 않는다 — ATHN08B 가족(660, Filter 60)은 지켜야 하는 대상이라 죽으면 실패 (1035 150 → FailedScreen).
	 */
	static boolean killMatters(ScriptWorld w, ScriptActor a) {
		if (a.spawner != null && Boolean.TRUE.equals(w.slot(a.spawner, "Respawn"))) {
			return true;
		}
		for (ScriptEntity e : w.entities()) {
			if (e.type() != 3) {
				continue;
			}
			boolean ok = switch (e.mode()) {
				case 2 -> a.spawner != null && a.spawner.owner == e.owner;
				case 3 -> intSlot(w, e, "Filter") == 0 || intSlot(w, e, "Filter") == a.group;
				case 4 -> true;
				default -> false;
			};
			if (ok) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 보스전 (38 문서 §10.4, 강제 아님): {@link devple.gow.engine.boss.HeadlessBoss} 로 크레토스·보스 전투, 동기 잡기·QTE·마무리 기술을 원본 데이터대로
	 * 60 Hz 로 돌리고 같은 틱에 스크립트를 영웅 위치로 tick 한다. 보스가 쓰러진 뒤 BOSS_AFTER 초 (컷신 → Warp) 까지.
	 */
	private void bossPhase(devple.gow.engine.boss.HeadlessBoss b, ScriptWorld w, float[] player, List<String> movieDone, String[] currentSource,
			Runnable step, LevelResult r) {
		float dt = 1 / 60f, after = 0;
		currentSource[0] = "보스 " + b.fight.getClass().getSimpleName();
		b.moveHero(player[0], player[1], player[2]);
		for (float t = 0; t < BOSS_MAX && after < BOSS_AFTER; t += dt) {
			try {
				for (String m : movieDone) {
					w.postEvent(ScriptWorld.EVENT_MOVIE_DONE, m, 0, 0, w.player());
				}
				movieDone.clear();
				b.tick(dt);
				var p = b.ctl.position;
				player[0] = p.x;
				player[1] = p.y;
				player[2] = p.z;
				w.tick(dt, p.x, p.y, p.z);
			} catch (RuntimeException ex) {
				if (r.exceptions.size() < 20) {
					r.exceptions.add(String.format(Locale.ROOT, "보스 t=%.1f: %s", w.time(), ex));
				}
				break;
			}
			if (b.fight.defeated()) {
				after += dt;
			}
		}
		r.bossPhases.addAll(b.fight.phases);
		r.bossDefeated = b.fight.defeated();
	}

	/** 붙어 있는 동안 오브젝트 애니를 끝까지 재생하며 진행률 1023 을 원작 규칙대로 보낸다 ({@link TaskProgress}). */
	/** 센서가 (직접 또는 대상 엔티티로) 실패 화면 Internal 0x13 을 부르는지 — 엔티티 코드 문자열 검사. */
	private static boolean failsLevel(ScriptWorld w, ScriptEntity e) {
		java.util.function.Predicate<ScriptEntity> calls = x -> x.toString().contains("FailedScreen") || x.name().contains("Fail");
		if (calls.test(e)) {
			return true;
		}
		for (ScriptEntity t : w.entities()) {
			if (e.targets().contains(t.data.id()) && calls.test(t)) {
				return true;
			}
		}
		return false;
	}

	/** 그룹을 Filter 로 보는 mode 4 DestructionSensor 의 볼륨 안 한 점 (없으면 null). */
	private static float[] destructionZone(ScriptWorld w, ScriptActor a) {
		for (ScriptEntity e : w.entities()) {
			if (e.type() != 3 || e.mode() != 4 || e.volume == null) { // 3 = DestructionSensor
				continue;
			}
			if (failsLevel(w, e)) {
				continue; // 실패 구역 (ARENA10 FailZone → FailTransmit → FailedScreen) 은 피한다
			}
			Object f = w.slot(e, "Filter");
			if (!(f instanceof Number n) || n.intValue() != a.group) {
				continue;
			}
			float[] c = e.position();
			for (int r = 0; r <= 640; r += 32) {
				for (int dx = -r; dx <= r; dx += 32) {
					for (int dz = -r; dz <= r; dz += 32) {
						for (int dy = -256; dy <= 256; dy += 64) {
							if (e.volume.contains(c[0] + dx, c[1] + dy, c[2] + dz)) {
								return new float[]{c[0] + dx, c[1] + dy, c[2] + dz};
							}
						}
					}
				}
			}
		}
		return null;
	}

	private static void runTask(ScriptWorld w, Interactables.Item it, Runnable step) {
		TaskProgress tp = new TaskProgress(it.id, it.length, it.engagedSpeed, it.rewindSpeed);
		for (int guard = 0; guard < 3000 && !tp.done; guard++) {
			TaskProgress.Event e = tp.update(true, DT);
			if (e != null) {
				w.postEvent(devple.gow.engine.game.SceneNav.EVENT_TASK, e.percent(), e.taskId(), e.speed100(), w.player());
			}
			step.run();
		}
	}

	/**
	 * 링 크랭크 (Push Type 4, 42 문서 §5): 멈춤 자리 0·¼·½·¾ 를 차례로 돌려 보고 (붙잡고 정회전 → 놓아 가장 가까운 자리로, 원작 식 그대로)
	 * 참인 LevelData bool 이 가장 많은 자리에 둔다 — 고리 관절 Marker 가 정렬 볼륨(R1SouthVol 등)에 들어가 켜는 플래그 [하네스 탐색].
	 *
	 * @return 고른 자리 (0..3)
	 */
	private int solveRing(ScriptWorld w, Interactables.Item it, Runnable step) {
		TaskProgress t = it.task();
		TaskProgress.RingParams p = it.ringParams();
		int best = -1, bestScore = -1;
		for (int pass = 0; pass < 2; pass++) {
			if (pass == 1 && Math.round(t.time / t.length * 4) % 4 == best) {
				return best;
			}
			for (int k = 0; k < 4; k++) {
				// 한 칸 (¼ 바퀴): 다음 자리 허용 범위에 들어갈 때까지 정회전, 놓고 멈출 때까지
				float start = t.time / t.length;
				for (int g = 0; g < 20000; g++) {
					send(w, t.ring(true, 1, p, DT));
					step.run();
					float f = t.time / t.length;
					float moved = ((f - start) % 1 + 1) % 1;
					if (moved > 0.15f && TaskProgress.detent(f, p.tol()) >= 0) {
						break;
					}
				}
				for (int g = 0; g < 20000 && !(g > 0 && t.speed == 0); g++) {
					send(w, t.ring(false, 0, p, DT));
					step.run();
				}
				for (float s = 0; s < 1; s += DT) {
					step.run();
				}
				int pos = Math.round(t.time / t.length * 4) % 4;
				if (pass == 0) {
					int score = (int) w.host().levelData().values().stream().filter(v -> Boolean.TRUE.equals(v)).count();
					if (score > bestScore) {
						bestScore = score;
						best = pos;
					}
				} else if (pos == best) {
					return best;
				}
			}
		}
		return best;
	}

	private static void send(ScriptWorld w, TaskProgress.Event e) {
		if (e != null) {
			w.postEvent(devple.gow.engine.game.SceneNav.EVENT_TASK, e.percent(), e.taskId(), e.speed100(), w.player());
		}
	}

	private static void addOnce(List<String> l, String s) {
		if (!l.contains(s)) {
			l.add(s);
		}
	}

	/** 방문 대상: 볼륨의 한 영역 (-1 = 볼륨 전체, {@link ScriptWorld#regionOf}). */
	record Target(LevelData.PlacedVolume volume, int region) {
	}

	/** 남은 볼륨을 가까운 순서로 방문 (각 DWELL 초). r 이 있으면 방문 수를 센다. */
	private void walk(List<Target> todo, Map<Target, float[]> points, float[] player,
			String[] currentSource, Runnable step, LevelResult r) {
		float[] at = player.clone();
		while (!todo.isEmpty()) {
			Target next = null;
			float bd = Float.MAX_VALUE;
			for (Target v : todo) {
				float d = dist2(points.get(v), at);
				if (d < bd) {
					bd = d;
					next = v;
				}
			}
			todo.remove(next);
			at = points.get(next);
			System.arraycopy(at, 0, player, 0, 3);
			currentSource[0] = next.volume().hull().name + (next.region() >= 0 ? "#" + next.region() : "");
			// 밟아서 움직인 발판·문 애니가 끝날 때까지 서 있는다 (PAND03A 누름판 → Marker 104 가 아래 볼륨에 든다)
			for (float t = 0; t < DWELL || animating() && t < WALK_ANIM_MAX; t += DT) {
				step.run();
			}
			if (r != null) {
				r.visited++;
			}
		}
	}

	private static int intSlot(ScriptWorld w, ScriptEntity e, String name) {
		Object v = w.slot(e, name);
		return v instanceof Number n ? n.intValue() : 0;
	}

	/** 강제 이벤트의 보낸 쪽: 모드 3 = 소유 GO 의 SpawnEnemy 가 만든 것처럼, 그 밖에는 Filter 그룹의 액터 (보통 영웅). */
	private static ScriptActor fuzzSender(ScriptWorld w, ScriptEntity e, float[] pos) {
		int filter = intSlot(w, e, "Filter");
		if (e.mode() == 3) {
			for (ScriptEntity s : w.entities()) {
				if (s.type() == 10 && s.owner == e.owner) {
					return new ScriptActor("강제#" + s.id(), filter, pos, s);
				}
			}
		}
		return filter == 0 || filter == 1 ? w.player() : new ScriptActor("강제", filter, pos, null);
	}

	/** EventSensor Condition 핸들러의 상수 (push_int / push_float / push_string) + 0. 최대 8개. */
	static List<Object> conditionConstants(ScriptEntity e) {
		List<Object> out = new ArrayList<>();
		out.add(0);
		int slot = devple.gow.tools.script.EntitySlots.index(e.type(), devple.gow.tools.script.EntitySlots.CONDITION);
		for (devple.gow.tools.script.EscScript.Handler h : e.data.handlers()) {
			if (h.id() != slot) {
				continue;
			}
			for (devple.gow.tools.script.EscOp op : h.code()) {
				String t = op.text();
				Object v = null;
				try {
					if (t.startsWith("push_int ")) {
						v = Integer.parseInt(t.substring(9).trim());
					} else if (t.startsWith("push_float ")) {
						v = Float.parseFloat(t.substring(11).trim());
					} else if (t.startsWith("push_string ")) {
						v = t.substring(12).trim().replace("\"", "");
					}
				} catch (NumberFormatException ignored) {
					// 주석이 붙은 피연산자
				}
				if (v != null && !out.contains(v) && out.size() < 8) {
					out.add(v);
				}
			}
		}
		return out;
	}

	private static float dist2(float[] a, float[] b) {
		float x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
		return x * x + y * y + z * z;
	}

	/**
	 * 볼륨 안의 한 점 (월드): 볼륨 안에 바닥이 있으면 바닥 위 4 유닛(걷는 위치), 없으면 아무 내부 점(공중, [3] = 1). 못 찾으면 null.
	 * 반환 = {x, y, z, 공중 여부}.
	 */
	static float[] interior(LevelData.PlacedVolume v, LevelCollision col) {
		return interior(v, col, -1);
	}

	/** region &gt;= 0 이면 그 영역의 ball/hull 안의 점만. */
	static float[] interior(LevelData.PlacedVolume v, LevelCollision col, int region) {
		// 후보 (로컬): hull 중심, ball 중심, 경계구 격자 (얇은 hull 은 격자 사이로 빠지므로 중심을 먼저)
		List<float[]> cand = new ArrayList<>();
		var h = v.hull();
		for (int i = 0; i < h.hulls.size(); i++) {
			if (region < 0 || h.hullRegion[i] == region) {
				cand.add(h.hulls.get(i).center());
			}
		}
		for (int i = 0; i < h.balls.size(); i++) {
			if (region < 0 || h.ballRegion[i] == region) {
				cand.add(h.balls.get(i));
			}
		}
		float[] s = h.sphere;
		int n = 12;
		for (int i = 0; i <= n; i++) {
			for (int k = 0; k <= n; k++) {
				for (int j = n; j >= 0; j--) {
					cand.add(new float[]{s[0] + s[3] * (2f * i / n - 1), s[1] + s[3] * (2f * j / n - 1), s[2] + s[3] * (2f * k / n - 1)});
				}
			}
		}
		float[] w = new float[3];
		float[] air = null;
		float reach = 2 * s[3] * scale(v) + 64;
		for (float[] c : cand) {
			Mat4.transformPoint(v.world(), c[0], c[1], c[2], w);
			if (!in(v, region, w[0], w[1], w[2])) {
				continue;
			}
			float g = col.groundHeight(w[0], w[1], w[2], reach);
			if (!Float.isNaN(g) && in(v, region, w[0], g + 4, w[2])) {
				return new float[]{w[0], g + 4, w[2], 0};
			}
			if (air == null) {
				air = new float[]{w[0], w[1], w[2], 1};
			}
		}
		return air;
	}

	private static boolean in(LevelData.PlacedVolume v, int region, float x, float y, float z) {
		return region < 0 ? v.contains(x, y, z) : v.regions(x, y, z).contains(region);
	}

	private static float scale(LevelData.PlacedVolume v) {
		float[] m = v.world();
		return (float) Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
	}

	// ---- 적 ----

	private List<EnemyInfo> enemyKinds(LevelData lv, LevelResult r) {
		List<EnemyInfo> out = new ArrayList<>();
		for (String name : EnemyFactory.resourceWads(lv.wad)) {
			Path p = Content.file(rom, name + ".WAD");
			if (!Files.exists(p)) {
				r.missing.add("RSRCS " + name + ".WAD");
				continue;
			}
			EnemyInfo k = kinds.computeIfAbsent(name, n -> {
				Set<Integer> ai1 = new HashSet<>();
				String model = null;
				try {
					Tweaks own = new Tweaks();
					own.load(WadFile.load(p));
					for (String node : own.nodes("/TweakTemplates/AI1/")) {
						try {
							ai1.add(Integer.parseInt(node.split("/")[3]));
						} catch (RuntimeException ignored) {
							// 숫자가 아닌 노드
						}
					}
					model = EnemyFactory.load(p, permk, -1).model();
				} catch (IOException | RuntimeException ex) {
					// 스켈레톤 없는 리소스 WAD (FkSnd 사운드 뱅크 로더 등): model = null
				}
				return new EnemyInfo(n, ai1, model);
			});
			out.add(k);
		}
		return out;
	}

	/** TemplateId → GameScene.spawn 과 같은 규칙: RSRCS 적 WAD 의 AI1 → 레벨 자체 AI1(Instance Name 오브젝트) → 모델 없는 템플릿 / 미해결. */
	private Spawn resolveSpawn(ScriptListener.SpawnRequest q, List<EnemyInfo> levelKinds, Tweaks levelTweaks, LevelData lv) {
		int tpl = q.templateId();
		for (EnemyInfo k : levelKinds) {
			if (k.model() != null && k.ai1().contains(tpl)) {
				return new Spawn(q.source().name(), tpl, k.wad(), k.model());
			}
		}
		String obj = EnemyFactory.levelTemplateObject(levelTweaks, tpl);
		LevelLayout.LevelObject o = obj == null ? null : lv.layout.objects.get(obj);
		String mdl = o == null ? null : o.refs("MDL_").stream().findFirst().orElse(null);
		if (mdl != null) {
			return new Spawn(q.source().name(), tpl, "level", obj + ":" + mdl);
		}
		for (EnemyInfo k : levelKinds) {
			if (k.ai1().contains(tpl)) {
				return new Spawn(q.source().name(), tpl, k.wad(), null); // 사운드 뱅크 로더 등 (전투 개체 없음)
			}
		}
		// 보스 등 오브젝트 없는 템플릿 → GameScene 은 첫 적 종류를 대역으로
		EnemyInfo stand = levelKinds.stream().filter(k -> k.model() != null).findFirst().orElse(null);
		String name = levelTweaks.findNode(String.format("/TweakTemplates/AI1/%03d/", tpl)) != null ? "보스/특수" : null;
		return new Spawn(q.source().name(), tpl, name == null ? null : "stand-in",
				stand == null ? null : "대역 " + stand.wad() + (obj != null ? " (" + obj + ")" : ""));
	}

	// ---- 리소스 ----

	/** 배치 인스턴스가 참조하는 MDL / ANM / 모델 머티리얼 / 머티리얼 텍스처가 레벨 WAD 또는 R_PERMK 에 있는지. */
	private void checkResources(LevelData lv, LevelResult r) {
		Set<String> own = names(lv.wad);
		Set<String> extra = new HashSet<>();
		for (String n : EnemyFactory.resourceWads(lv.wad)) {
			extra.addAll(wadNames.computeIfAbsent(n, k -> {
				try {
					Path p = Content.file(rom, k + ".WAD");
					return Files.exists(p) ? names(WadFile.load(p)) : Set.of();
				} catch (IOException ex) {
					return Set.of();
				}
			}));
		}
		Map<String, GowModel> models = new HashMap<>();
		for (GowModel m : GowModel.load(lv.wad)) {
			models.putIfAbsent(m.name, m);
		}
		TextureLibrary lib = new TextureLibrary(lv.wad);
		Set<String> txr = new HashSet<>();
		lib.textures().forEach(e -> txr.add(e.txr().name()));
		Set<String> checkedMdl = new HashSet<>();
		for (LevelLayout.Placement p : lv.layout.placements()) {
			for (String ref : p.object().refs()) {
				boolean have = own.contains(ref) || permNames.contains(ref) || extra.contains(ref);
				if ((ref.startsWith("MDL_") || ref.startsWith("ANM_")) && !have) {
					r.missing.add(ref + " (" + p.instance().name() + ")");
				}
			}
			String mdl = p.model();
			if (mdl == null || !checkedMdl.add(mdl)) {
				continue;
			}
			GowModel gm = models.get(mdl);
			if (gm == null) {
				if (own.contains(mdl)) {
					r.missing.add(mdl + " 파싱 실패");
				}
				continue;
			}
			for (String mat : gm.materials) {
				if (mat == null || mat.isEmpty()) {
					continue;
				}
				if (!own.contains(mat) && !permNames.contains(mat)) {
					r.missing.add(mat + " (" + mdl + ")");
					continue;
				}
				for (String t : GowModel.textureNames(lv.wad, mat)) {
					if (!txr.contains(t) && !permNames.contains(t)) {
						r.missing.add(t + " (" + mat + ")");
					}
				}
			}
		}
		for (LevelLayout.Placement p : lv.layout.placements()) {
			if (p.object().refs().isEmpty() && p.instance().object().isEmpty()) {
				r.missing.add("빈 오브젝트 " + p.instance().name());
			}
		}
		for (GoInstance g : lv.layout.instances) {
			if (!lv.layout.objects.containsKey(g.object())) {
				r.markerGos++;
			}
		}
	}

	// ---- 2단계: 전환 결정 ----

	/**
	 * 원시 요청 → 전환 (순서 무관, 고정점). 보행 순서가 임의라서 GameScene 의 {@link LevelLink} 처럼 순서를 따지지 않는다.
	 * <pre>
	 * SEAMLESS L→X : L 에 LoadCheck(X), 그리고 X 가 L 에서 LoadWad/LoadForWarp 됐거나 X→L 전환이 있음(떠나온 레벨은 다른 슬롯에 남는다)
 * WALK     L→X : L 이 X 를 LoadWad/LoadForWarp 했고 두 레벨 바닥이 맞닿음 (LoadCheck 없이 걸어 들어가는 연결, SEWR01 → SEWR02)
	 * WARP     L→X : L 에 Warp, X ∈ L 의 LoadForWarp 대상
	 * </pre>
	 */
	void resolveEdges() {
		for (boolean changed = true; changed; ) {
			changed = false;
			for (LevelResult r : results.values()) {
				for (Map.Entry<String, Req> c : r.loadChecks.entrySet()) {
					String x = c.getKey();
					if (hasEdge(r, LevelLink.Kind.SEAMLESS, x)) {
						continue;
					}
					Req load = r.loads.get(x);
					boolean back = results.containsKey(x) && results.get(x).edges.stream().anyMatch(e -> e.target().equals(r.level));
					if ((load != null || back) && results.containsKey(x) && nearFloor(results.get(x).floorCells, c.getValue().pos())) {
						boolean f = c.getValue().forced() || (load != null ? load.forced() : results.get(x).edges.stream()
								.filter(e -> e.target().equals(r.level)).allMatch(Edge::forced));
						r.edges.add(new Edge(LevelLink.Kind.SEAMLESS, x, true, c.getValue().pos(), c.getValue().source(), f));
						changed = true;
					}
				}
				for (Map.Entry<String, Req> l : r.loads.entrySet()) {
					LevelResult x = results.get(l.getKey());
					// LoadForWarp 짝(아레나처럼 같은 원점에 겹친 다른 좌표계)은 바닥이 겹쳐도 걸어 넘어가는 연결이 아니다
					boolean warpOnly = r.warpLoads.contains(l.getKey()) && x != null && x.edges.stream()
							.noneMatch(e -> e.target().equals(r.level) && e.kind() != LevelLink.Kind.WARP);
					if (x != null && !warpOnly && !hasEdge(r, LevelLink.Kind.WALK, x.level) && !hasEdge(r, LevelLink.Kind.SEAMLESS, x.level)
							&& floorsTouch(r.floorCells, x.floorCells)) {
						r.edges.add(new Edge(LevelLink.Kind.WALK, x.level, true, l.getValue().pos(), l.getValue().source(), l.getValue().forced()));
						changed = true;
					}
				}
				// 이웃 레벨 X 의 Goto 볼륨이 L 의 바닥 위에 있음 (원작은 두 레벨 스크립트가 같이 돈다, ATHN01D → ATHN01E 갑판)
				for (LevelResult x : results.values()) {
					if (x == r || hasEdge(r, LevelLink.Kind.WALK, x.level) || !(r.loads.containsKey(x.level)
							|| x.edges.stream().anyMatch(e -> e.target().equals(r.level)))) {
						continue;
					}
					for (Map.Entry<String, Req> g : x.gotoFrom.entrySet()) {
						Req q = g.getValue();
						if (q.source() != null && q.source().startsWith("ENV_") && nearFloor(r.floorCells, q.pos(), 1, 1)) {
							r.edges.add(new Edge(LevelLink.Kind.WALK, x.level, true, q.pos(), x.level + " Goto " + g.getKey(), q.forced()));
							changed = true;
							break;
						}
					}
				}
				// 짚라인·수평 줄 끝이 이웃 레벨 바닥 위 (ATHN01D 돛대 꼭대기 짚라인 gorope3 → ATHN01E 갑판) → 줄 타고 넘어가 WALK
				for (NavRope rope : r.ropes) {
					if (rope.type() == devple.gow.tools.level.RopeDef.Type.HANG) {
						continue;
					}
					float[] top = {rope.top().x, rope.top().y, rope.top().z};
					float[] end = {rope.end().x, rope.end().y, rope.end().z};
					if (!nearFloor(r.floorCells, top, 6, 6)) {
						continue; // 줄 시작점 근처에 L 의 바닥이 없음 (닿을 수 없음)
					}
					for (LevelResult x : results.values()) {
						if (x == r || hasEdge(r, LevelLink.Kind.WALK, x.level) || hasEdge(r, LevelLink.Kind.SEAMLESS, x.level)
								|| !(r.loads.containsKey(x.level) || x.edges.stream().anyMatch(e -> e.target().equals(r.level)))) {
							continue;
						}
						if (nearFloor(x.floorCells, end, 2, 3)) {
							Req l = r.loads.get(x.level);
							r.edges.add(new Edge(LevelLink.Kind.WALK, x.level, true, end, "줄 " + rope.owner() + " (" + rope.type() + ")",
									l != null && l.forced()));
							changed = true;
						}
					}
				}
				if (r.warp != null) {
					for (String x : r.warpLoads) {
						if (!hasEdge(r, LevelLink.Kind.WARP, x)) {
							r.edges.add(new Edge(LevelLink.Kind.WARP, x, true, r.warp.pos(), r.warp.source(), r.warp.forced() || r.loads.get(x).forced()));
							changed = true;
						}
					}
				}
			}
		}
	}

	/** 영웅이 밟는 바닥 삼각형의 꼭짓점·변 중점·무게중심이 든 칸. */
	static Set<Long> floorCells(LevelCollision col) {
		Set<Long> out = new HashSet<>();
		for (LevelCollision.Tri t : col.triangles()) {
			if (!t.floor() || !t.solidForPlayer()) {
				continue; // AI 전용 바닥 (NoPlayerCollision, 예 HDES01C entities_AIDeath) 은 영웅이 못 밟는다
			}
			var a = t.a();
			var b = t.b();
			var c = t.c();
			float[][] pts = {{a.x, a.y, a.z}, {b.x, b.y, b.z}, {c.x, c.y, c.z}, {(a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2},
					{(b.x + c.x) / 2, (b.y + c.y) / 2, (b.z + c.z) / 2}, {(a.x + c.x) / 2, (a.y + c.y) / 2, (a.z + c.z) / 2},
					{(a.x + b.x + c.x) / 3, (a.y + b.y + c.y) / 3, (a.z + b.z + c.z) / 3}};
			for (float[] p : pts) {
				out.add(cell((int) Math.floor(p[0] / FLOOR_CELL), (int) Math.floor(p[1] / FLOOR_CELL), (int) Math.floor(p[2] / FLOOR_CELL)));
			}
		}
		return out;
	}

	/** 점에서 수평 {@link #SEAMLESS_REACH}, 수직 ±256 안에 바닥 칸이 있는지 (LoadCheck 가 실제 위치 이음인지 — Warp 짝 레벨은 좌표계가 달라 없다). */
	static boolean nearFloor(Set<Long> cells, float[] p) {
		return nearFloor(cells, p, (int) Math.ceil(SEAMLESS_REACH / FLOOR_CELL), (int) Math.ceil(256 / FLOOR_CELL));
	}

	static boolean nearFloor(Set<Long> cells, float[] p, int r, int v) {
		int cx = (int) Math.floor(p[0] / FLOOR_CELL), cy = (int) Math.floor(p[1] / FLOOR_CELL), cz = (int) Math.floor(p[2] / FLOOR_CELL);
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = -v; dy <= v; dy++) {
					if (cells.contains(cell(cx + dx, cy + dy, cz + dz))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** 점에서 가장 가까운 바닥 칸 중심까지 거리 (보고서용, 없으면 MAX). */
	public static float nearestFloor(Set<Long> cells, float[] p) {
		float best = Float.MAX_VALUE;
		for (long k : cells) {
			int x = (int) (k >> 42) << 11 >> 11, y = (int) (k >> 21 & 0x1FFFFF) << 11 >> 11, z = (int) (k & 0x1FFFFF) << 11 >> 11;
			float dx = (x + 0.5f) * FLOOR_CELL - p[0], dy = (y + 0.5f) * FLOOR_CELL - p[1], dz = (z + 0.5f) * FLOOR_CELL - p[2];
			best = Math.min(best, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		return best;
	}

	/** 레벨 결과의 바닥 칸 (보고서용). */
	public Set<Long> floorCellsOf(String level) {
		LevelResult r = results.get(level);
		return r == null ? Set.of() : r.floorCells;
	}

	private static long cell(int x, int y, int z) {
		return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
	}

	/** 두 레벨 바닥이 한 칸 이내로 맞닿는지 (걸어서 넘어갈 수 있는지). */
	static boolean floorsTouch(Set<Long> a, Set<Long> b) {
		Set<Long> small = a.size() < b.size() ? a : b, big = small == a ? b : a;
		for (long k : small) {
			int x = (int) (k >> 42) << 11 >> 11, y = (int) (k >> 21 & 0x1FFFFF) << 11 >> 11, z = (int) (k & 0x1FFFFF) << 11 >> 11;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (big.contains(cell(x + dx, y + dy, z + dz))) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	private static boolean hasEdge(LevelResult r, LevelLink.Kind k, String target) {
		return r.edges.stream().anyMatch(e -> e.kind() == k && e.target().equals(target));
	}

	// ---- 3단계: 도착 검사 ----

	/** 이음 전환 위치 아래 대상 레벨 바닥, Warp 의 도착 Start(모드 2, 떠나온 레벨 이름) 존재. */
	void checkArrivals() throws IOException {
		Map<String, List<Object[]>> incoming = new TreeMap<>();
		for (LevelResult src : results.values()) {
			for (Edge e : src.edges) {
				if (e.targetExists() && results.containsKey(e.target())) {
					incoming.computeIfAbsent(e.target(), k -> new ArrayList<>()).add(new Object[]{src.level, e});
				}
			}
		}
		for (Map.Entry<String, List<Object[]>> in : incoming.entrySet()) {
			LevelResult dst = results.get(in.getKey());
			LevelData lv = new LevelData(WadFile.load(Content.file(rom, in.getKey() + ".WAD")));
			LevelCollision col = new LevelCollision(lv.sheets);
			ScriptWorld w = new ScriptWorld(lv, new ScriptHost(), null);
			for (Object[] o : in.getValue()) {
				String from = (String) o[0];
				Edge e = (Edge) o[1];
				switch (e.kind()) {
					case SEAMLESS -> {
						float[] p = e.pos();
						float d = floorDistance(col, p);
						float[] inVol = d <= WALK_REACH ? null : fallInto(col, new LevelCollision(new LevelData(WadFile.load(Content.file(rom, from + ".WAD"))).sheets),
								results.get(from).loadCheckSamples.get(dst.level));
						dst.arrivals.add(new Arrival(from, e.kind(), String.format(Locale.ROOT, "(%.0f, %.0f, %.0f) %s", p[0], p[1], p[2],
								d == 0 ? "바닥 있음" : d < Float.MAX_VALUE ? "대상 바닥까지 " + (int) d
										: inVol != null ? String.format(Locale.ROOT, "LoadCheck 볼륨 (%.0f, %.0f, %.0f) 에서 떠나온 레벨 바닥 구멍으로 %.0f 낙하 → 대상 바닥 %.0f",
										inVol[0], inVol[1], inVol[2], inVol[3], inVol[1] - inVol[3])
										: "대상 바닥 없음"), d <= WALK_REACH || inVol != null));
					}
					case WARP -> {
						LevelEntry.Spot t = LevelEntry.resolve(lv, w, null, from);
						boolean exact = t.source().startsWith("Warp 도착");
						float[] p = t.pos();
						boolean ground = !Float.isNaN(col.groundHeight(p[0], p[1] + UP, p[2], DROP));
						float[] snap = LevelEntry.snapToFloor(col, p, 256);
						if (!ground && snap == null) {
							// 움직이는 발판 위 도착점 (ATHN05D WarpFrom4A = 엘리베이터 애니 끝 자세, 38 문서 §5)
							Platforms.Body b = Platforms.of(lv, devple.gow.engine.game.SceneNav.animatorOwners(w))
									.settleUnder(new org.joml.Vector3f(p[0], p[1], p[2]), 24);
							if (b != null) {
								dst.arrivals.add(new Arrival(from, e.kind(), (exact ? t.source() : "모드 2 Start 없음 → " + t.source())
										+ String.format(Locale.ROOT, " → 발판 %s (애니 %.1f 초)", b.owner, b.time()), true));
								continue;
							}
						}
						String d = (exact ? t.source() : "모드 2 Start 없음 → " + t.source()) + (ground ? ""
								: String.format(Locale.ROOT, " (%.0f, %.0f, %.0f) 바닥 없음 → %s", p[0], p[1], p[2],
								snap != null ? String.format(Locale.ROOT, "%.0f 옆 바닥으로 보정", Math.hypot(snap[0] - p[0], snap[2] - p[2])) : "보정 실패"));
						dst.arrivals.add(new Arrival(from, e.kind(), d, ground || snap != null));
					}
					default -> dst.arrivals.add(new Arrival(from, e.kind(), e.kind() == LevelLink.Kind.WALK ? "바닥 맞닿음" : "Load " + e.target(), true));
				}
			}
		}
	}

	/**
	 * LoadCheck 볼륨 안의 점 중 떠나온 레벨 바닥에 막히지 않고 대상 레벨 바닥으로 떨어지는 곳 (없으면 null). 반환 {x, y, z, 낙하 거리}.
	 * 볼륨은 보행 점(떠나온 레벨 바닥) 하나가 아니라 경계 전체를 감싼다: PAND02F ENV_LoadCheckH (y −1610..−1413) 는 PAND02F 바닥(−1528)의
	 * 구멍 (x 700–720, z 5117–5199) 을 덮고, 그 아래 PAND02H 구덩이 바닥 −1904 로 떨어진다 — 발판이 아니라 낙하가 도착이다.
	 * GameScene 의 SEAMLESS 판정(다른 슬롯 바닥 600 아래까지)도 같은 낙하를 받는다.
	 */
	static float[] fallInto(LevelCollision dst, LevelCollision src, List<float[]> samples) {
		if (samples == null) {
			return null;
		}
		for (float[] s : samples) {
			float g = dst.groundHeight(s[0], s[1], s[2], DROP);
			if (Float.isNaN(g)) {
				continue;
			}
			float f = src.groundHeight(s[0], s[1], s[2], s[1] - g);
			if (Float.isNaN(f) || f <= g) {
				return new float[]{s[0], s[1], s[2], s[1] - g};
			}
		}
		return null;
	}

	/** 볼륨(영역) 안의 격자 점 (월드, 최대 600). */
	static List<float[]> volumeSamples(LevelData.PlacedVolume v, int region) {
		List<float[]> out = new ArrayList<>();
		float[] s = v.hull().sphere, w = new float[3];
		int n = 14;
		for (int i = 0; i <= n && out.size() < 600; i++) {
			for (int j = 0; j <= n; j++) {
				for (int k = 0; k <= n; k++) {
					Mat4.transformPoint(v.world(), s[0] + s[3] * (2f * i / n - 1), s[1] + s[3] * (2f * j / n - 1), s[2] + s[3] * (2f * k / n - 1), w);
					if (in(v, region, w[0], w[1], w[2])) {
						out.add(w.clone());
					}
				}
			}
		}
		return out;
	}

	/** 이음 전환 지점에서 대상 레벨 바닥까지의 수평 거리 (0 = 바로 아래, MAX = 반경 512 안에 없음). 떠나온 레벨 충돌은 남아 있으므로 가까우면 걸어서 닿는다. */
	static float floorDistance(LevelCollision col, float[] p) {
		for (int r = 0; r <= 512; r += 16) {
			int n = r == 0 ? 1 : 16;
			for (int k = 0; k < n; k++) {
				double a = 2 * Math.PI * k / n;
				float x = p[0] + (float) (r * Math.cos(a)), z = p[2] + (float) (r * Math.sin(a));
				if (!Float.isNaN(col.groundHeight(x, p[1] + 256, z, 512))) {
					return r;
				}
			}
		}
		return Float.MAX_VALUE;
	}

	// ---- 그래프 ----

	/** START 에서 실제 전환으로 갈 수 있는 레벨 → 직전 레벨 (BFS). */
	public Map<String, String> reach(String from) {
		return reach(from, true);
	}

	/** withForced = false 면 강제 이벤트 없이 걷기만으로 나온 전환만 쓴다. */
	public Map<String, String> reach(String from, boolean withForced) {
		Map<String, String> prev = new LinkedHashMap<>();
		Deque<String> q = new ArrayDeque<>();
		prev.put(from, null);
		q.add(from);
		while (!q.isEmpty()) {
			LevelResult r = results.get(q.poll());
			if (r == null) {
				continue;
			}
			for (Edge e : r.edges) {
				if (e.targetExists() && (withForced || !e.forced()) && !prev.containsKey(e.target())) {
					prev.put(e.target(), r.level);
					q.add(e.target());
				}
			}
		}
		return prev;
	}

	/** from → to 최단 경로 (없으면 빈 목록). */
	public List<String> path(String from, String to) {
		Map<String, String> prev = reach(from);
		List<String> out = new ArrayList<>();
		if (!prev.containsKey(to)) {
			return out;
		}
		for (String c = to; c != null; c = prev.get(c)) {
			out.addFirst(c);
		}
		return out;
	}

	/** 실행: {@code TraversalVerifier <gow-rom> <report.md>}. */
	public static void main(String[] args) throws IOException {
		Path rom = Path.of(args.length > 0 ? args[0] : "gow-rom");
		Path out = Path.of(args.length > 1 ? args[1] : "work/verify/report.md");
		TraversalVerifier v = new TraversalVerifier(rom).runAll();
		Files.createDirectories(out.toAbsolutePath().getParent());
		Files.writeString(out, TraversalReport.markdown(v));
		System.out.println("보고서: " + out.toAbsolutePath());
	}
}
