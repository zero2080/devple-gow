package devple.gow.engine.script;

import devple.gow.tools.anim.Mat4;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.script.EntitySlots;
import devple.gow.tools.script.EscScript;
import devple.gow.tools.wad.WadTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 레벨 하나의 ESC 엔티티 런타임 (원작 SCR_Entities, 13 문서). 엔티티 생성 → 시작 큐(우선순위) → 매 틱
 * [볼륨 진입/이탈 → 전역 이벤트 → 지연 발동] 순서로 처리한다 (원작 프레임 갱신 0x1A7A78).
 * <pre>
 * Trigger(e, sender)  0x1A9750: Condition 거짓 / 재발동 대기 중이면 무시 → Delay 뒤 발동 예약
 * Fire(e, sender)     0x1A98C0: DelayedEnable 거짓이면 취소 → Action 실행 → 타입별 효과
 *                     센서(0..4) = 대상 엔티티 전부 Trigger (0x1A9C18), 이펙터 = 모드별 오브젝트에 효과 (0x1A9958)
 * </pre>
 * 사용: {@code new ScriptWorld(level, host, listener).start(); world.tick(dt, x, y, z)}
 */
public final class ScriptWorld {

	/** 원작 "제거" 이벤트 (DeathZone 송신기가 적에게 보냄) [추정]. */
	public static final int EVENT_KILL = 1029;
	/** 동영상 끝 이벤트: 인자 0 = 동영상 이름 (예 ATHN01D TwinKickDoor-main-movieDone: EventArg(0) == "Semo02") [확실: 데이터]. */
	public static final int EVENT_MOVIE_DONE = 501;
	/** 원작 "EvtForce" 이벤트: arg1 = 강제 상태 번호 (예: 영웅 DeathZone 504) [추정]. */
	public static final int EVENT_FORCE = 1019;
	/** 스폰 재생성 최소 지연 (원작 0x29D93C 플래그 → max(delay, 0.25)). */
	static final float RESPAWN_MIN_DELAY = 0.25f;

	private record Pending(float time, long seq, ScriptEntity entity, ScriptActor sender) {
	}

	/** 볼륨의 한 영역 (region -1 = 볼륨 전체). */
	record VolumeRegion(LevelData.PlacedVolume volume, int region) {
	}

	private record Event(int id, Object a0, Object a1, Object a2, ScriptActor sender) {
	}

	public final LevelData level;
	private final ScriptHost host;
	private final ScriptListener listener;
	private final EscVm vm;
	private final List<ScriptEntity> entities = new ArrayList<>();
	private final Map<Integer, ScriptEntity> byId = new HashMap<>();
	private final Map<LevelData.PlacedVolume, List<ScriptEntity>> byVolume = new LinkedHashMap<>();
	private final List<ScriptActor> actors = new ArrayList<>();
	private final PriorityQueue<Pending> pending = new PriorityQueue<>(Comparator.comparingDouble(Pending::time).thenComparingLong(Pending::seq));
	private List<Event> events = new ArrayList<>();
	private long seq;
	private float time;
	private boolean started;
	private boolean zeroDelay, minDelay;
	private ScriptActor player;
	/** Marker 엔티티 → 오브젝트 (그룹 = Marker Id, 소유 GO 관절에 붙어 움직임). */
	private final Map<ScriptEntity, ScriptActor> markers = new LinkedHashMap<>();

	public ScriptWorld(LevelData level, ScriptHost host, ScriptListener listener) {
		this.level = level;
		this.host = host;
		this.listener = listener != null ? listener : new ScriptListener() {
		};
		host.listener = this.listener;
		host.world = this;
		host.resetLevelData();
		this.vm = new EscVm(host);
		List<LevelLayout.Placement> placements = level.layout.placements();
		for (WadTag t : level.wad.resources()) {
			if (t.magic() != EscScript.MAGIC || !t.name().startsWith("ESC_")) {
				continue;
			}
			EscScript s = new EscScript(t.data());
			if (!s.target.equals("SCR_Entities")) {
				continue;
			}
			LevelLayout.Placement owner = placements.stream().filter(p -> p.object().refs().contains(t.name())).findFirst().orElse(null);
			LevelData.PlacedVolume vol = null;
			if (owner != null) {
				for (LevelData.PlacedVolume v : level.volumes) {
					if (v.hull().kind == 2 && v.owner().equals(owner.instance().name())) {
						vol = v;
						break;
					}
				}
			}
			for (EscScript.Entity e : s.entities) {
				float[] world = e.matrix().clone();
				if (owner != null) {
					float[][] jw = owner.jointWorld();
					world = Mat4.mul(e.matrix(), jw[Math.min(e.unk40(), jw.length - 1)]);
				}
				ScriptEntity se = new ScriptEntity(t.name(), e, owner, world, vol);
				entities.add(se);
				byId.putIfAbsent(e.id(), se);
				if (vol != null) {
					byVolume.computeIfAbsent(vol, k -> new ArrayList<>()).add(se);
				}
			}
		}
	}

	public List<ScriptEntity> entities() {
		return entities;
	}

	public ScriptEntity entity(int id) {
		return byId.get(id);
	}

	/** 이름으로 찾기 (정확히, 없으면 그룹 접두사 일치). */
	public ScriptEntity find(String name) {
		for (ScriptEntity e : entities) {
			if (e.name().equalsIgnoreCase(name)) {
				return e;
			}
		}
		for (ScriptEntity e : entities) {
			if (e.group().equalsIgnoreCase(name)) {
				return e;
			}
		}
		return null;
	}

	public List<ScriptActor> actors() {
		return actors;
	}

	public ScriptActor player() {
		return player;
	}

	public float time() {
		return time;
	}

	public ScriptHost host() {
		return host;
	}

	/** 첫 Start 엔티티 (플레이어 이름 / 그룹 / 시작 위치). */
	public ScriptEntity startEntity() {
		return entities.stream().filter(e -> e.type() == 9).findFirst().orElse(null);
	}

	/** Start 모드: 1 기본 시작, 2 워프 도착점(슬롯 0 = 떠나온 레벨 이름), 3 Goto 지점 (36 문서 §2). */
	public static final int START_DEFAULT = 1, START_WARP = 2, START_GOTO = 3;

	/** 기본 시작 지점 = 모드 1 인 첫 Start (ATHN01E 처럼 Goto 지점(모드 3)이 먼저 나오는 레벨이 있다). 없으면 null. */
	public ScriptEntity defaultStart() {
		return entities.stream().filter(e -> e.type() == 9 && e.mode() == START_DEFAULT).findFirst().orElse(null);
	}

	/**
	 * Warp 도착점 (원작 0x1AE710): 모드 2 Start 중 슬롯 0(떠나온 레벨 이름)이 fromLevel 과 같은 것, 없으면 첫 모드 1 Start.
	 * 원작 비교 함수 0x28DF50 은 대소문자 무시로 본다 (데이터에 "athn08c", "sewr02" 처럼 소문자 이름이 섞여 있음) [추정].
	 */
	public ScriptEntity warpTarget(String fromLevel) {
		if (fromLevel != null) {
			for (ScriptEntity e : entities) {
				if (e.type() == 9 && e.mode() == START_WARP && fromLevel.equalsIgnoreCase(String.valueOf(slot(e, 0)))) {
					return e;
				}
			}
		}
		return defaultStart();
	}

	/** 검증용: 핸들러 return 뒤 스택이 남으면 통지 (36 문서). */
	public void onUnbalanced(java.util.function.Consumer<String> l) {
		vm.onUnbalanced(l);
	}

	/**
	 * 플레이어를 옮기고, seamless 이면 그 위치를 감싸는 볼륨을 "이미 안" 으로 표시한다 (진입 센서 발동 없음).
	 * 원작은 이웃 레벨 두 개가 동시에 상주해 경계를 넘을 때 새 레벨의 센서가 계속 살아 있었으므로,
	 * LoadCheck 이음 전환 직후 도착 레벨의 LoadCheck 볼륨이 즉시 되돌아가는 일이 없다. {@link #start()} 뒤, 첫 틱 전에 부른다.
	 */
	public void placePlayer(float x, float y, float z, boolean seamless) {
		if (player == null) {
			return;
		}
		player.moveTo(x, y, z);
		player.inside.clear();
		if (seamless) {
			for (Map.Entry<LevelData.PlacedVolume, List<ScriptEntity>> m : byVolume.entrySet()) {
				LevelData.PlacedVolume v = m.getKey();
				Set<Integer> now = v.regions(x, y, z);
				if (now.isEmpty()) {
					continue;
				}
				player.inside.add(new VolumeRegion(v, -1));
				now.forEach(r -> player.inside.add(new VolumeRegion(v, r)));
			}
		}
	}

	/**
	 * 엔티티가 반응하는 볼륨 영역: 엔티티 영역 ID(원본 0x4A)가 볼륨의 ball/hull 영역 번호에 있으면 그 영역, 없으면 볼륨 전체 [근사].
	 * 전 레벨 1617 개 ESC/ENV 쌍 중 1610 개가 일치, 99 개 볼륨이 여러 영역 (예 PAND00B Warpto00A: LoadVol 0 / WarpVol 1).
	 */
	public static int regionOf(ScriptEntity e) {
		int r = e.data.baseId();
		if (e.volume == null || e.volume.hull().hasRegion(r)) {
			return e.volume != null ? r : -1;
		}
		// 영역 ID 가 있는데 볼륨에 그 영역이 없고, 볼륨이 여러 영역으로 나뉘어 있으면 밖 (ATHN01E ENV_bossEnt 51–54 의 "boss zone 5/6" 55/56, 37 문서)
		return r != 0 && e.volume.hull().regionCount() >= 2 ? NO_REGION : -1;
	}

	/** 어떤 위치에서도 안이 아닌 영역. */
	static final int NO_REGION = Integer.MIN_VALUE;

	// ---- 슬롯 평가 ----

	/** 슬롯 값 (핸들러가 있으면 VM 실행, 없으면 표 기본값) 을 슬롯 타입으로 변환. */
	public Object slot(ScriptEntity e, int slot) {
		List<EntitySlots.Slot> table = EntitySlots.of(e.type());
		int type = slot < table.size() ? table.get(slot).type() : EntitySlots.INT;
		Object def = slot < table.size() ? table.get(slot).def() : 0;
		EscScript.Handler h = e.handler(slot);
		if (h == null) {
			return def;
		}
		ScriptEntity prev = host.current;
		host.current = e;
		Object v;
		try {
			v = vm.run(new EscContext(e.script, e.data), h);
		} finally {
			host.current = prev;
		}
		return switch (type) {
			case EntitySlots.FLOAT -> EscVm.f(v);
			case EntitySlots.INT -> v == null ? 0 : EscVm.i(v);
			case EntitySlots.BOOL -> v != null && EscVm.bool(v);
			case EntitySlots.STRING -> v == null ? "" : String.valueOf(v);
			default -> v;
		};
	}

	public Object slot(ScriptEntity e, String name) {
		int i = EntitySlots.index(e.type(), name);
		return i < 0 ? null : slot(e, i);
	}

	private int intSlot(ScriptEntity e, String name) {
		Object v = slot(e, name);
		return v == null ? 0 : EscVm.i(v);
	}

	private float floatSlot(ScriptEntity e, String name) {
		Object v = slot(e, name);
		return v == null ? 0 : EscVm.f(v);
	}

	private boolean boolSlot(ScriptEntity e, String name, boolean def) {
		Object v = slot(e, name);
		return v == null ? def : EscVm.bool(v);
	}

	private boolean passes(ScriptEntity e, ScriptActor a) {
		int f = intSlot(e, EntitySlots.FILTER);
		return f == 0 || a != null && a.group == f;
	}

	// ---- 시작 ----

	/** 엔티티 등록(메시지 0) 후 시작 큐(메시지 1)를 우선순위 오름차순으로 처리 (원작 0x1A95E8 / 0x303DA0). */
	public void start() {
		if (started) {
			return;
		}
		started = true;
		ScriptEntity st = startEntity();
		if (st != null) {
			String name = String.valueOf(slot(st, "PlayerName"));
			int g = intSlot(st, EntitySlots.FILTER);
			player = new ScriptActor(name.isEmpty() ? "Hero" : name, g == 0 ? 1 : g, st.position(), null);
			actors.add(player);
		}
		List<ScriptEntity> queue = new ArrayList<>();
		Map<ScriptEntity, Integer> prio = new HashMap<>();
		for (ScriptEntity e : entities) {
			int p = switch (e.type()) {
				case 12 -> intSlot(e, "Priority");
				case 13 -> 1000 + intSlot(e, "Priority");
				case 2 -> 10000;
				case 9, 10, 11 -> Integer.MAX_VALUE;
				default -> -1;
			};
			if (p >= 0 || e.type() == 12) {
				prio.put(e, p);
				queue.add(e);
			}
		}
		queue.sort(Comparator.comparingInt(prio::get));
		for (ScriptEntity e : queue) {
			startMessage(e);
		}
		createMarkers();
		runPending();
	}

	/**
	 * Marker (타입 14): 소유 GO 관절에 붙은 표지 오브젝트, 그룹 = Id 슬롯. 진입 센서 Filter 로 "그 오브젝트가 볼륨에 들어옴" 을 잡는다
	 * (PAND05B 판도라 상자 pBoxPand05B-Marker511 → semoMovieHaveBox Filter 511, ATHN05D 엘리베이터 Marker599 → MarkerVolume Filter 599) [확실: 데이터].
	 * 생성 통지(CreationSensor)는 보내지 않는다 (레벨 배치물) [추정].
	 */
	private void createMarkers() {
		for (ScriptEntity e : entities) {
			if (e.type() != 14 || markers.containsKey(e)) {
				continue;
			}
			int id = intSlot(e, "Id");
			if (id <= 0) {
				continue;
			}
			ScriptActor a = new ScriptActor(e.name(), id, e.position(), null);
			actors.add(a);
			markers.put(e, a);
		}
	}

	/** 소유 GO 의 Marker 오브젝트들. */
	public List<ScriptActor> markers(String owner) {
		List<ScriptActor> out = new ArrayList<>();
		markers.forEach((e, a) -> {
			if (owner.equalsIgnoreCase(e.ownerName())) {
				out.add(a);
			}
		});
		return out;
	}

	/** 소유 GO 가 (dx, dy, dz) 만큼 움직임 (밀린 상자) → 그 Marker 들도. */
	public void moveOwnerBy(String owner, float dx, float dy, float dz) {
		for (ScriptActor a : markers(owner)) {
			a.moveTo(a.pos[0] + dx, a.pos[1] + dy, a.pos[2] + dz);
		}
	}

	/** 소유 GO 관절 월드 행렬이 바뀜 (애니 재생 중인 발판) → Marker 위치 = 엔티티 로컬 × 관절. */
	public void poseOwner(String owner, float[][] jointWorld) {
		markers.forEach((e, a) -> {
			if (owner.equalsIgnoreCase(e.ownerName()) && jointWorld.length > 0) {
				float[] w = Mat4.mul(e.data.matrix(), jointWorld[Math.min(e.data.unk40(), jointWorld.length - 1)]);
				a.moveTo(w[12], w[13], w[14]);
			}
		});
	}

	private void startMessage(ScriptEntity e) {
		switch (e.type()) {
			case 12 -> {
				if (host.initedGlobals.add(e.script + "/" + e.name())) {
					runInits(e);
				}
			}
			case 13 -> runInits(e);
			case 2 -> {
				// 모드 1: 소유 GO 가 있으면, 모드 5: 무조건 (보낸 쪽 없음)
				if (e.mode() == 1 && e.owner != null || e.mode() == 5) {
					trigger(e, null);
				}
			}
			case 10 -> {
				if (e.owner != null && boolSlot(e, "SpawnAtStart", false)) {
					zeroDelay = true;
					trigger(e, null);
					zeroDelay = false;
				}
			}
			case 11 -> {
				// Creator 슬롯 8 = 시작 시 생성 [강한 추정]: 상자 335 개·세이브 포인트 42 개 대부분이 이 값만 true 이고 다른 발동 경로가 없다 (36 문서 §5)
				if (e.owner != null && boolSlot(e, "CreateAtStart", false)) {
					zeroDelay = true;
					trigger(e, null);
					zeroDelay = false;
				}
			}
			default -> {
			}
		}
	}

	/** Data 엔티티: Init1..N 액션 실행 (변수 초기값). */
	private void runInits(ScriptEntity e) {
		for (int i = 1; i <= 16; i++) {
			if (e.handler(i) != null) {
				slot(e, i);
			}
		}
	}

	// ---- 매 틱 ----

	/** 플레이어 위치 갱신 후 한 틱. */
	public void tick(float dt, float x, float y, float z) {
		if (player != null) {
			player.moveTo(x, y, z);
		}
		update(dt);
	}

	public void update(float dt) {
		if (!started) {
			start();
		}
		time += dt;
		host.addPlayTime(dt);
		for (ScriptActor a : List.copyOf(actors)) {
			contacts(a);
		}
		List<Event> ev = events;
		events = new ArrayList<>();
		for (Event e : ev) {
			deliverEvent(e);
		}
		runPending();
	}

	/** 볼륨 진입(메시지 2) / 이탈(메시지 4) 판정 (원작 0x1A8410 / 0x1A7EF0, Filter = 오브젝트 그룹). */
	private void contacts(ScriptActor a) {
		if (a.destroyed) {
			return;
		}
		for (Map.Entry<LevelData.PlacedVolume, List<ScriptEntity>> m : byVolume.entrySet()) {
			LevelData.PlacedVolume v = m.getKey();
			Set<Integer> now = v.regions(a.pos[0], a.pos[1], a.pos[2]);
			// 영역별 진입/이탈 판정 (원작 0x1A88F0: (소유 GO, 영역 ID) 로 엔티티를 찾음)
			Map<Integer, Boolean> changed = new HashMap<>();
			for (ScriptEntity e : m.getValue()) {
				int r = regionOf(e);
				if (changed.containsKey(r)) {
					continue;
				}
				boolean in = r == NO_REGION ? false : r < 0 ? !now.isEmpty() : now.contains(r);
				VolumeRegion key = new VolumeRegion(v, r);
				boolean was = a.inside.contains(key);
				if (in != was) {
					if (in) {
						a.inside.add(key);
					} else {
						a.inside.remove(key);
					}
					changed.put(r, in);
				} else {
					changed.put(r, null);
				}
			}
			// 이탈(1) 먼저, 진입(0) 나중: 영역 사이를 옮길 때 "옛 영역 이탈 → 새 영역 진입" 순서여야 스크립트 상태가 새 영역으로 남는다
			// (히드라 구역 1→2: Exit1 이 Entry2 뒤에 오면 구역이 0 으로 지워짐, 37 문서)
			for (int pass = 1; pass >= 0; pass--) {
				for (ScriptEntity e : m.getValue()) {
					Boolean in = changed.get(regionOf(e));
					if (in != null && e.type() == pass && (in ? pass == 0 : pass == 1) && passes(e, a)) {
						trigger(e, a);
					}
				}
			}
		}
	}

	/** 전역 이벤트 → EventId 가 같은 EventSensor (원작 0x1AAB00, 인자는 Entity:0x9..0xC 로 읽힘). */
	private void deliverEvent(Event ev) {
		for (ScriptEntity e : entities) {
			if (e.type() != 4 || intSlot(e, "EventId") != ev.id) {
				continue;
			}
			e.eventArgs[0] = ev.a0;
			e.eventArgs[1] = ev.a1;
			e.eventArgs[2] = ev.a2;
			ScriptActor s = ev.sender;
			boolean ok = switch (e.mode()) {
				case 1 -> passes(e, s);
				case 2 -> s != null && passes(e, s) && inVolume(e, s);
				case 3 -> s != null && s.spawner != null && s.spawner.owner == e.owner;
				default -> false;
			};
			if (ok) {
				trigger(e, s);
			}
		}
	}

	/** 게임 → 스크립트 이벤트 (예: 501 동영상 끝 (인자 0 = 이름), 1048 붙잡기). 다음 틱에 처리. 이벤트 짝 월드가 있으면 거기에도. */
	public void postEvent(int id, Object a0, Object a1, Object a2, ScriptActor sender) {
		Event e = new Event(id, a0 == null ? 0 : a0, a1 == null ? 0 : a1, a2 == null ? 0 : a2, sender);
		events.add(e);
		if (eventPeer != null) {
			eventPeer.events.add(e);
		}
	}

	/** 같은 전역 이벤트 큐를 쓰는 다른 슬롯 레벨 (null = 혼자). */
	private ScriptWorld eventPeer;

	/**
	 * 두 레벨 슬롯의 이벤트를 잇는다: 원작 PostEvent(0x1A8C78) 는 큐 하나(0x303DB8)에 넣고 전달(0x1AAB00)은 모든 엔티티를 돈다 —
	 * 두 슬롯 엔티티가 같은 목록에 있으므로 한 레벨의 EventTransmitter(모드 5)·게임 이벤트를 다른 레벨 센서도 받는다 [확실: 큐 하나 / 추정: 목록 공유].
	 * 예 PAND00C EyeDoor 204 → PAND00D FakeEyeDoor 동영상, PAND00D 205 → PAND00C 엘리베이터 동상. other = null 이면 끊음.
	 */
	public void linkEvents(ScriptWorld other) {
		if (eventPeer != null && eventPeer.eventPeer == this) {
			eventPeer.eventPeer = null;
		}
		eventPeer = other;
		if (other != null) {
			if (other.eventPeer != null && other.eventPeer != this) {
				other.eventPeer.eventPeer = null;
			}
			other.eventPeer = this;
		}
	}

	private static boolean inVolume(ScriptEntity e, ScriptActor a) {
		if (e.volume == null) {
			return false;
		}
		int r = regionOf(e);
		Set<Integer> now = e.volume.regions(a.pos[0], a.pos[1], a.pos[2]);
		return r == NO_REGION ? false : r < 0 ? !now.isEmpty() : now.contains(r);
	}

	// ---- 발동 ----

	/** 원작 0x1A9750. */
	public void trigger(ScriptEntity e, ScriptActor sender) {
		int base = EntitySlots.baseIndex(e.type());
		if (base < 0) {
			return;
		}
		if (!boolSlot(e, EntitySlots.CONDITION, true)) {
			return;
		}
		float refract = floatSlot(e, EntitySlots.REFRACT);
		float until = refract < 0 ? Float.MAX_VALUE : e.lastTrigger + refract;
		if (e.lastTrigger > -Float.MAX_VALUE && time < until) {
			return;
		}
		e.lastTrigger = time;
		float delay = zeroDelay ? 0 : floatSlot(e, EntitySlots.DELAY);
		if (minDelay) {
			delay = Math.max(delay, RESPAWN_MIN_DELAY);
		}
		pending.add(new Pending(time + delay, seq++, e, sender));
	}

	private void runPending() {
		for (int guard = 0; guard < 100_000 && !pending.isEmpty() && pending.peek().time() <= time; guard++) {
			Pending p = pending.poll();
			fire(p.entity(), p.sender());
		}
	}

	/** 원작 0x1A98C0 + vt+0x28. */
	private void fire(ScriptEntity e, ScriptActor sender) {
		if (!boolSlot(e, EntitySlots.DELAYED_ENABLE, true)) {
			return;
		}
		slot(e, EntitySlots.ACTION_SLOT);
		String dbg = String.valueOf(slot(e, EntitySlots.DEBUG));
		if (!dbg.isEmpty()) {
			listener.debug(e, dbg);
		}
		switch (e.type()) {
			case 0, 1, 2, 3, 4 -> {
				for (int t : e.targets()) {
					ScriptEntity te = byId.get(t);
					if (te != null) {
						trigger(te, sender);
					}
				}
			}
			case 5, 6 -> listener.animate(e, objects(e, sender), String.valueOf(slot(e, "AnimName")));
			case 7 -> {
				int[] v = new int[11];
				for (int i = 0; i < 8; i++) {
					v[i] = EscVm.i(slot(e, i));
				}
				v[8] = Math.round(EscVm.f(slot(e, 8)) * 1000);
				v[9] = intSlot(e, "Attr17");
				v[10] = intSlot(e, "JointVisibility");
				listener.vis(e, objects(e, sender), v);
			}
			case 8 -> transmit(e, sender);
			case 10 -> spawn(e);
			case 11 -> listener.create(e);
			case 15 -> listener.sound(e, e.group(), intSlot(e, "Op"));
			default -> {
			}
		}
	}

	/** 이펙터 대상 (원작 0x1A9958): 1 소유 GO, 2 보낸 쪽, 3 그룹이 맞는 전체, 4 볼륨 안. */
	private List<ScriptActor> targetActors(ScriptEntity e, ScriptActor sender) {
		List<ScriptActor> out = new ArrayList<>();
		switch (e.mode()) {
			case 2 -> {
				if (sender != null && passes(e, sender)) {
					out.add(sender);
				}
			}
			case 3, 4 -> {
				for (ScriptActor a : actors) {
					if (!a.destroyed && passes(e, a) && (e.mode() == 3 || inVolume(e, a))) {
						out.add(a);
					}
				}
			}
			default -> {
			}
		}
		return out;
	}

	private List<String> objects(ScriptEntity e, ScriptActor sender) {
		if (e.mode() == 1) {
			return e.ownerName() != null ? List.of(e.ownerName()) : List.of();
		}
		return targetActors(e, sender).stream().map(a -> a.name).toList();
	}

	/** EventTransmitter (원작 0x1ABF20 / 0x1AC020). */
	private void transmit(ScriptEntity e, ScriptActor sender) {
		int id = intSlot(e, "EventId"), a1 = intSlot(e, "Arg1"), a2 = intSlot(e, "Arg2");
		if (e.mode() == 5) {
			postEvent(id, a1, a2, 0, sender);
			return;
		}
		if (e.mode() == 1) {
			listener.objectEvent(new ScriptListener.ObjectEvent(e, e.ownerName(), null, id, a1, a2));
			return;
		}
		boolean deathZone = e.group().regionMatches(true, 0, "DeathZone", 0, 9);
		for (ScriptActor a : targetActors(e, sender)) {
			listener.objectEvent(new ScriptListener.ObjectEvent(e, a.name, a, id, a1, a2));
			if (deathZone || id == EVENT_KILL) {
				listener.deathZone(a, e, id, a1);
			}
		}
	}

	/**
	 * SpawnEnemy (원작 0x1AC850): TemplateId &gt; 0 일 때 엔티티 월드 행렬 위치에 그룹 = Filter 인 액터.
	 * 슬롯 0 = <b>MaximumCount</b> (원작 0x1AC580 "MaximumCount %d exceeded (%d)"): 0 이 아니면 이 엔티티가 만든 수(+0x100)가 그 값에 닿은 뒤로는
	 * 만들지 않는다 [확실: 코드]. 데이터: ATHN08B 분신 4 파도 (2 파 7/8 회 → 3 파 4/5 → 4 파 합 25 = LevelData31), DEST03 세이렌 3×3 = LevelData1 9.
	 * Respawn 이 이 값으로 끝나지 않으면 카운터 조건(LevelData == 최대)이 지나쳐 다음 파도가 영영 안 나온다.
	 */
	private void spawn(ScriptEntity e) {
		int tpl = intSlot(e, "TemplateId");
		if (tpl <= 0) {
			return;
		}
		int max = intSlot(e, "MaximumCount");
		if (max > 0 && e.spawnCount >= max) {
			return;
		}
		e.spawnCount++;
		ScriptActor a = new ScriptActor(e.group() + "#" + e.id(), intSlot(e, EntitySlots.FILTER), e.position(), e);
		actors.add(a);
		listener.spawnEnemy(new ScriptListener.SpawnRequest(e, a, tpl, String.valueOf(slot(e, "Patrol")), e.world.clone()));
		created(a);
	}

	// ---- 오브젝트 생성 / 제거 ----

	/** 게임이 만든 오브젝트 등록 (CreationSensor 모드 2..4 통지). */
	public ScriptActor addActor(String name, int group, float[] pos) {
		ScriptActor a = new ScriptActor(name, group, pos, null);
		actors.add(a);
		created(a);
		return a;
	}

	private void created(ScriptActor a) {
		for (ScriptEntity e : entities) {
			if (e.type() != 2) {
				continue;
			}
			boolean ok = switch (e.mode()) {
				case 2 -> a.spawner != null && a.spawner.owner == e.owner;
				case 3 -> passes(e, a);
				case 4 -> inVolume(e, a);
				default -> false;
			};
			if (ok) {
				trigger(e, a);
			}
		}
	}

	/** 오브젝트 제거 (메시지 6): DestructionSensor + 만든 SpawnEnemy 의 Respawn. */
	public void destroy(ScriptActor a) {
		if (a.destroyed) {
			return;
		}
		a.destroyed = true;
		actors.remove(a);
		for (ScriptEntity e : entities) {
			if (e.type() != 3) {
				continue;
			}
			boolean ok = switch (e.mode()) {
				case 1 -> a.name.equals(e.ownerName());
				case 2 -> a.spawner != null && a.spawner.owner == e.owner;
				case 3 -> passes(e, a);
				case 4 -> inVolume(e, a);
				default -> false;
			};
			if (ok) {
				trigger(e, a);
			}
		}
		ScriptEntity sp = a.spawner;
		if (sp != null) {
			sp.spawnedDeaths++;
			if (boolSlot(sp, "Respawn", false)) {
				minDelay = true;
				trigger(sp, a);
				minDelay = false;
			}
		}
	}

	/** Internal:0x7 Goto(name): 이름이 같은 엔티티 위치로 플레이어 이동 + 그 엔티티의 대상 발동 (원작 Warp_Jump 0x1B2E08). */
	void gotoEntity(String name) {
		ScriptEntity e = find(name);
		float[] p = e != null ? e.position() : null;
		listener.level(new ScriptListener.LevelRequest(ScriptListener.LevelOp.GOTO, null, name, p));
		if (e == null) {
			return;
		}
		if (player != null) {
			player.moveTo(p[0], p[1], p[2]);
		}
		for (int t : e.targets()) {
			ScriptEntity te = byId.get(t);
			if (te != null) {
				trigger(te, player);
			}
		}
	}
}
