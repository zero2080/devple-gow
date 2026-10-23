package devple.gow.engine.game;

import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.nav.HeroNavigator;
import devple.gow.engine.nav.Interactables;
import devple.gow.engine.nav.NavAnims;
import devple.gow.engine.nav.NavRope;
import devple.gow.engine.nav.NavWorld;
import devple.gow.engine.nav.Platforms;
import devple.gow.engine.script.ScriptActor;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.level.LevelData;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 게임 씬의 이동 보조 (38 문서): 내비게이션 세계(정적 충돌 2 슬롯 + 발판 + 줄), R1 오브젝트, 내비게이터 → 스크립트 이벤트,
 * 이웃 레벨 스크립트. GameScene 은 이 클래스에 위임만 한다.
 * <pre>
 * 이벤트 (원작 값):  1023 과제 진행 (인자 0 = %, 인자 1 = 과제 id)   1048 붙잡기 / 1049 놓기 (인자 0 = PushPull id)
 * </pre>
 */
public final class SceneNav {

	/** 원작 과제 진행 이벤트 (CSM 0x215928: a0 = 0x3FF). */
	public static final int EVENT_TASK = 1023;
	/** PushPull 붙잡기 / 놓기 (PAND05B pBoxPand05B-EventAttach/Detach). */
	public static final int EVENT_ATTACH = 1048, EVENT_DETACH = 1049;
	/**
	 * 버튼 안내 센서용 누름 (원작 0x1DC3F8: 영웅이 잡기·다른 동작을 하지 않을 때 입력 +0x30 비트 7 → PostEvent(0x400, 0, 0, 0, 영웅)).
	 * 받는 쪽은 EventSensor mode 2 (보낸 쪽 = 볼륨 안 영웅) + PromptOnEnter — ATHN01E 선장 `XFCcaptainCSM-main-r2` → a01MP04 → LoadWad(Athn01D)
	 * [확실: 이벤트 번호·보낸 쪽 / 추정: 버튼 = R1].
	 */
	public static final int EVENT_USE = 1024;

	/** 게임 씬 쪽 동작. */
	public interface Host {
		void heroDied();

		/** 이웃 레벨 스크립트의 레벨 요청 (LoadCheck → 이음 전환). */
		void neighbourLevel(ScriptListener.LevelRequest r);

		/** 이웃 레벨 엔티티로 순간 이동 (Goto). */
		void teleport(float[] pos, float yaw);

		void message(int id, float seconds);

		void orbs(String emitter, Vector3f at);

		/**
		 * 이웃 레벨 스크립트의 카메라 조작 — 원작 카메라 관리자는 슬롯 구분 없이 하나라 이웃 스크립트의 SwitchCamera(Internal 0x8), CMV 질의
		 * on/off(Internal 0x9), Vis Attr11_1 (CMV 볼륨 on/off) 도 그대로 먹는다 (30 문서 §7). name != null = SwitchCamera, query != null = 0x9,
		 * owners != null = Attr11_1 (on).
		 */
		default void neighbourCamera(String name, Boolean query, List<String> owners, boolean on) {
		}

		/**
		 * 이웃 레벨 스크립트의 Vis Visible (슬롯 0: 1 보임 / 2 숨김) — 원작은 두 슬롯 GO 가 같은 렌더 서버에 있어 이웃 스크립트의 숨김도 그대로 화면에 걸린다
		 * (예 PAND01A AxeAlcWall-CamSensor1: 이벤트 1037 카메라 AltCam43 등이면 goaxealcwall 숨김, 43 §13.8).
		 */
		default void neighbourVisible(List<String> objects, boolean show) {
		}
	}

	private final LevelData level;
	private final ScriptWorld world;
	private final Host host;
	public final NavWorld navWorld;
	public final Interactables items;
	public final HeroNavigator navigator;
	private final Tweaks tweaks;
	private NavWorld.Floor lastFloor;
	/** 이웃 레벨 (다른 슬롯) 스크립트 — 원작은 두 레벨 엔티티가 같이 돈다 (36 문서 §6 한계 → 38 문서 §4). */
	private String neighbourName;
	private ScriptWorld neighbour;
	/**
	 * 이웃 레벨 Animator 오브젝트 (문·발판) — 원작은 두 슬롯이 같이 살아 있어 이웃 스크립트의 Animator 도 그 레벨 오브젝트를 움직이고, 이음으로 떠나온
	 * 레벨의 열린 문도 그대로 남는다 (43 §13.8). 렌더 자세만 (충돌은 이웃 정적 충돌 그대로 [근사]).
	 */
	private Platforms neighbourPlatforms = new Platforms();
	private final ScriptHost mainHost;

	public SceneNav(LevelData level, LevelCollision col, HeroRig hero, ScriptWorld world, ScriptHost mainHost, Tweaks levelTweaks, Host host) {
		this.level = level;
		this.world = world;
		this.host = host;
		this.mainHost = mainHost;
		this.tweaks = levelTweaks;
		navWorld = new NavWorld(col);
		navWorld.ropes().addAll(NavRope.of(level));
		items = Interactables.of(level, levelTweaks);
		navWorld.setPlatforms(Platforms.of(level, animatorOwners(world), posedOwners(items)));
		navigator = new HeroNavigator(hero.ctl, navWorld, new NavAnims(hero.tweaks));
		navigator.setItems(items);
		navigator.setListener(new HeroNavigator.Listener() {
			@Override
			public void taskProgress(Interactables.Item it, devple.gow.engine.nav.TaskProgress.Event e) {
				// 원작 0x215928 / 0x2118E8: 1023 (%, 과제 id, 재생 속도 × 100)
				world.postEvent(EVENT_TASK, e.percent(), e.taskId(), e.speed100(), world.player());
			}

			@Override
			public void pushAttach(Interactables.Item it) {
				world.postEvent(EVENT_ATTACH, it.id, 0, 0, world.player());
			}

			@Override
			public void pushDetach(Interactables.Item it) {
				world.postEvent(EVENT_DETACH, it.id, 0, 0, world.player());
			}

			@Override
			public void boxKicked(Interactables.Item it) {
				// 원작 0x1C4980: sub_001A8C78(0x41D, 0, 0, 0, 영웅)
				world.postEvent(HeroNavigator.EVENT_KICK, 0, 0, 0, world.player());
			}

			@Override
			public void pushMoved(Interactables.Item it, Vector3f d) {
				world.moveOwnerBy(it.owner, d.x, d.y, d.z);
			}

			@Override
			public void interactDone(Interactables.Item it) {
				System.out.println("상황 동작 완료: " + it);
				if (it.kind == Interactables.Kind.CHEST) {
					host.orbs(chestEmitter(it, tweaks), it.pos);
				}
			}

			@Override
			public void deathSurface() {
				host.heroDied();
			}
		});
		hero.nav = navigator;
		System.out.printf("내비게이션: 줄 %d, 발판 %d, R1 오브젝트 %d%n", navWorld.ropes().size(), navWorld.platforms().bodies().size(), items.items().size());
	}

	/** Animator 엔티티(타입 5/6) 가 가리키는 소유 GO 이름들 = 발판 후보. */
	public static Set<String> animatorOwners(ScriptWorld w) {
		Set<String> out = new HashSet<>();
		for (ScriptEntity e : w.entities()) {
			if ((e.type() == 5 || e.type() == 6) && e.ownerName() != null) {
				out.add(e.ownerName());
			}
		}
		return out;
	}

	/**
	 * 상자 → 오브 이미터 (TWK OrbEmitter Instance Name): CSM 종류별 OrbEmitter 템플릿 (+0x90.., {@link Interactables.Item#chestOrb()}, 0x2161A8)
	 * 의 이름. 템플릿이 없으면 CSM 이름으로 고른다 [근사].
	 */
	static String chestEmitter(Interactables.Item it, Tweaks tw) {
		int orb = it.chestOrb();
		if (orb >= 0 && tw != null) {
			String n = tw.s(String.format(java.util.Locale.ROOT, "/TweakTemplates/OrbEmitter/%03d/", orb), "Instance Name", null);
			if (n != null) {
				return n;
			}
		}
		return chestEmitter(it.label);
	}

	/** 상자 종류 → 오브 이미터 (TWK OrbEmitter Instance Name) [근사: CSM 템플릿 이름으로 고름]. */
	static String chestEmitter(String csmLabel) {
		String l = csmLabel == null ? "" : csmLabel.toLowerCase(java.util.Locale.ROOT);
		if (l.contains("health")) {
			return "HealthChest";
		}
		if (l.contains("magic")) {
			return "MagicChest";
		}
		return "RaidenChest1";
	}

	/** 다른 슬롯 레벨이 바뀜: 충돌 + 스크립트. */
	public void setOther(String name, LevelData data, LevelCollision otherCol, ScriptListener neighbourListener) {
		navWorld.setOther(otherCol);
		if (name == null || name.equals(neighbourName)) {
			return;
		}
		neighbourName = name;
		neighbour = null;
		neighbourPlatforms = new Platforms();
		navWorld.setNeighbourPlatforms(neighbourPlatforms);
		world.linkEvents(null);
		if (data == null) {
			return;
		}
		try {
			ScriptWorld nw = new ScriptWorld(data, ScriptHost.neighbour(mainHost), neighbourListener);
			Set<String> owners = animatorOwners(nw);
			neighbourPlatforms = Platforms.of(data, owners, owners);
			navWorld.setNeighbourPlatforms(neighbourPlatforms); // 이웃 문·발판 충돌도 자세대로 (43 §13.8 [근사] 해소)
			nw.start();
			Vector3f p = world.player() != null ? new Vector3f(world.player().pos[0], world.player().pos[1], world.player().pos[2]) : new Vector3f();
			nw.placePlayer(p.x, p.y, p.z, true); // 이미 서 있는 볼륨은 "안" 으로 (들어올 때 발동하지 않음)
			neighbour = nw;
			world.linkEvents(nw); // 전역 이벤트 큐 공유 (원작 0x303DB8)
			System.out.println("이웃 레벨 스크립트: " + name + " (엔티티 " + nw.entities().size() + ")");
		} catch (RuntimeException ex) {
			System.out.println("이웃 레벨 스크립트 실패 " + name + ": " + ex);
		}
	}

	/** 다른 슬롯 비움 (LoadForWarp 가 꺼진 레벨을 올림): 충돌·발판·스크립트·이벤트 큐 연결 없음. */
	public void clearOther() {
		navWorld.setOther(null);
		neighbourName = null;
		neighbour = null;
		neighbourPlatforms = new Platforms();
		navWorld.setNeighbourPlatforms(neighbourPlatforms);
		world.linkEvents(null);
	}

	public ScriptWorld neighbour() {
		return neighbour;
	}

	public String neighbourName() {
		return neighbourName;
	}

	/**
	 * 이웃 레벨 스크립트 리스너: 메시지·사망 존·LoadCheck·Goto 만 받는다. 이웃의 LoadWad/Warp 는 원작에서 "호출한 레벨이 아닌 슬롯" = 현재 레벨 자리에
	 * 로드하므로 (36 문서 §2) 여기서는 무시한다 [근사]. 적 생성·상자 생성도 그 레벨로 넘어간 뒤 그 레벨 스크립트가 한다.
	 */
	public ScriptListener neighbourListener() {
		return new ScriptListener() {
			@Override
			public void message(int id, float seconds) {
				host.message(id, seconds);
			}

			@Override
			public void level(LevelRequest r) {
				if (r.op() == LevelOp.LOAD_CHECK) {
					host.neighbourLevel(r);
				} else if (r.op() == LevelOp.GOTO && neighbour != null) {
					ScriptEntity t = neighbour.find(r.entry());
					if (t != null) {
						host.teleport(t.position(), (float) Math.atan2(t.world[8], t.world[10]));
					}
				}
			}

			@Override
			public void camera(String name) {
				host.neighbourCamera(name, null, null, false);
			}

			@Override
			public void animate(ScriptEntity source, List<String> objects, String anim) {
				applyAnimator(neighbour, neighbourPlatforms, source, objects, anim);
			}

			@Override
			public void internal(int func, List<Object> args) {
				if (func == 0x9 && !args.isEmpty()) {
					Object a = args.get(0);
					host.neighbourCamera(null, a instanceof Number n ? n.floatValue() != 0 : !(a instanceof Boolean b) || b, null, false);
				}
			}

			@Override
			public void vis(ScriptEntity source, List<String> objects, int[] values) {
				if (values.length > 0 && (values[0] == 1 || values[0] == 2)) {
					if (System.getProperty("gow.trace.vis") != null) {
						System.out.println("VIS(이웃) " + source.name() + " " + (values[0] == 1 ? "ON " : "OFF ") + objects);
					}
					host.neighbourVisible(objects, values[0] == 1);
				}
				applyCollision(neighbourPlatforms, objects, values); // 이웃 문 "EndDoor-DoorCDOff" 등
				if (values.length > 4 && values[4] != 0) {
					if (System.getProperty("gow.trace.vis") != null) {
						System.out.println("VIS(이웃) " + source.name() + " CMV " + (values[4] == 1 ? "ON " : "OFF ") + objects);
					}
					host.neighbourCamera(null, null, objects, values[4] == 1);
				}
			}

			@Override
			public boolean loadCheck(String wad) {
				return wad != null && (wad.equalsIgnoreCase(neighbourName) || wad.equalsIgnoreCase(level.wad.name().replace(".WAD", "")));
			}

			@Override
			public void deathZone(ScriptActor actor, ScriptEntity source, int event, int arg) {
				if (neighbour != null && actor == neighbour.player()) {
					host.heroDied();
				}
			}
		};
	}

	/** 바닥 (정적 2 슬롯 + 발판). 마지막 결과를 기억해 발판 운반에 쓴다. */
	public float ground(float x, float y, float z, float drop) {
		lastFloor = navWorld.floor(x, y, z, drop);
		return lastFloor == null ? Float.NaN : lastFloor.y();
	}

	/** 발판 위에 서 있으면 이번 틱 발판 이동만큼 영웅을 옮긴다. */
	public void carry(Vector3f p, boolean grounded) {
		if (!grounded) {
			return;
		}
		NavWorld.Floor f = navWorld.floor(p.x, p.y + 4, p.z, 8);
		if (f != null && f.platform() != null) {
			Platforms.carry(p, f.platform());
		}
	}

	/** Animator 엔티티 → 발판 애니 (모드·속도·시작 시간 = 슬롯 1..3, {@link Platforms#play(String, String, int, float, float)}). */
	public void animate(ScriptEntity source, List<String> objects, String anim) {
		int[] mode = new int[1];
		float[] speed = {1}, start = new float[1];
		if (source != null) {
			animatorArgs(world, source, mode, speed, start);
		}
		for (String o : objects) {
			if (navWorld.platforms().play(o, anim, mode[0], speed[0], start[0])) {
				System.out.println("오브젝트 애니: " + o + " " + anim + " 모드 0x" + Integer.toHexString(mode[0]) + " 속도 " + speed[0]);
			}
		}
	}

	/**
	 * 스크립트 Animator → 발판 애니 (다른 슬롯 레벨: 이웃 스크립트가 이웃 오브젝트를, 43 §13.8). 렌더 자세와 CDV hull 충돌이 같이 움직인다.
	 */
	public static void applyAnimator(ScriptWorld w, Platforms ps, ScriptEntity source, List<String> objects, String anim) {
		int[] mode = new int[1];
		float[] speed = {1}, start = new float[1];
		if (source != null && w != null) {
			animatorArgs(w, source, mode, speed, start);
		}
		for (String o : objects) {
			ps.play(o, anim, mode[0], speed[0], start[0]);
		}
	}

	/** 스크립트 Vis 슬롯 3 (Attr11_0, 1 켬 / 2 끔 — "DoorCollOff", "DoorCDOff") → 소유 GO 의 CDV 충돌 (이 레벨은 GameScene 이 같은 규칙으로). */
	public static void applyCollision(Platforms ps, List<String> objects, int[] values) {
		if (values.length > 3 && values[3] != 0) {
			for (String o : objects) {
				ps.setSolid(o, values[3] == 1);
			}
		}
	}

	/** Animator 슬롯 1 모드 (int), 2 속도 (없으면 1), 3 시작 시간. */
	public static void animatorArgs(ScriptWorld w, ScriptEntity e, int[] mode, float[] speed, float[] start) {
		Object m = w.slot(e, 1), sp = w.slot(e, 2), st = w.slot(e, 3);
		mode[0] = m instanceof Number n ? n.intValue() : 0;
		speed[0] = sp instanceof Number n ? n.floatValue() : 1f;
		start[0] = st instanceof Number n ? n.floatValue() : 0f;
	}

	/**
	 * Creator "chest" → R2 상자 (38 §21). 원작 Creator 생성 0x1ACCA8: 슬롯 0 Name0 에서 "go" 를 떼고 (0x1A4960) 그 이름의 오브젝트 리소스
	 * (0x1AD280 → 0x17A7B8, 형 3) 를 소유 GO 행렬 (0x1A9CD0) 에 새 GO 로 만든다 (0x1A5130, 슬롯 3..6 = unk3..unk6 을 넘김: unk5 = CSM 템플릿,
	 * unk6 = 과제 id). "chest" = R_PERMK 오브젝트 chest (MDL_chest·ANM_chest·CDV_chest, GO gochest = IO_CSM) — 소유 GO (gochest2) 는 메시 없는 자리표.
	 * 모델·충돌·손잡이는 {@link ChestProps}.
	 *
	 * @return 상자 (Creator 가 상자가 아니면 null)
	 */
	public Interactables.Item create(ScriptEntity source) {
		Object name = world.slot(source, "Name0");
		if ("chest".equals(name) && source.owner != null) {
			float[] gp = source.owner.instance().pos();
			int tpl = num(world.slot(source, "unk5"));
			int task = num(world.slot(source, "unk6"));
			return items.addChest(source.owner.instance().name(), new Vector3f(gp[0], gp[1], gp[2]), tpl, task, tweaks);
		}
		return null;
	}

	private static int num(Object o) {
		return o instanceof Number n ? n.intValue() : 0;
	}

	/** 도착점 아래 정적 바닥이 없으면 발판 자세를 맞춘다 (ATHN05D WarpFrom4A 엘리베이터). */
	public boolean settleArrival(Vector3f spawn) {
		Platforms.Body b = navWorld.platforms().settleUnder(spawn, 24);
		if (b != null) {
			world.poseOwner(b.owner, b.joints());
			System.out.printf("도착점 아래 발판 %s (애니 %.1f 초 자세) [근사]%n", b.owner, b.time());
		}
		return b != null;
	}

	/** 진행 시간으로 자세를 갖는 R1 오브젝트 (레버·크랭크·CSM 문·TAndF): 오브젝트 애니 = 과제 진행 시간. 밀기 상자는 위치로 움직인다. */
	public static java.util.Set<String> posedOwners(Interactables items) {
		java.util.Set<String> out = new java.util.HashSet<>();
		for (Interactables.Item it : items.items()) {
			if (it.kind != Interactables.Kind.PUSH && it.kind != Interactables.Kind.CHEST) {
				out.add(it.owner);
			}
		}
		return out;
	}

	/**
	 * R1 오브젝트 자세 = 진행 시간 (링 크랭크의 고리 관절 Marker 101 → R1SouthVol → LevelData 3, PAND00C 고리 퍼즐).
	 * LIFT 는 진행 0..1 → 오브젝트 애니 길이 비율 [추정: 문 ANM 은 길이 0 인 경우가 많다].
	 */
	public static void poseItems(Interactables items, Platforms ps, ScriptWorld world) {
		poseItems(items, ps, world, 0);
	}

	public static void poseItems(Interactables items, Platforms ps, ScriptWorld world, float dt) {
		for (Interactables.Item it : items.items()) {
			if (it.kind == Interactables.Kind.PUSH || it.kind == Interactables.Kind.CHEST || !it.taskStarted()) {
				continue;
			}
			Platforms.Body b = ps.body(it.owner);
			float t = it.task().time;
			if (it.kind == Interactables.Kind.LIFT && it.openObjectAnim != null && b != null) {
				// 0x213B28 / 0x214BC0: 연타 중 = TandF 애니 (길이 × 위치 — HydraHatchDoor trapDoorTandF 는 길이 0 → 뚜껑 정지),
				// 완료 = trapOpen 을 속도 1 로 (영웅 trapDoorOpen 0.6 초와 함께)
				boolean posed;
				if (it.done) {
					it.openTime += dt;
					posed = ps.poseAt(it.owner, it.openObjectAnim, it.openTime);
				} else {
					posed = ps.poseAt(it.owner, it.liftObjectAnim, 0);
				}
				if (posed) {
					world.poseOwner(b.owner, b.joints());
				}
				continue;
			}
			if (b != null && ps.poseAt(it.owner, it.objectAnim, it.kind == Interactables.Kind.LIFT && b.anim() != null ? t * b.anim().duration : t)) {
				world.poseOwner(b.owner, b.joints());
			}
		}
	}

	/** 한 틱: 발판 애니 → Marker 위치, 이웃 스크립트. */
	public void update(float dt, Vector3f hero) {
		navWorld.platforms().update(dt);
		neighbourPlatforms.update(dt);
		poseItems(items, navWorld.platforms(), world, dt);
		for (Platforms.Body b : navWorld.platforms().bodies()) {
			if (b.playing()) {
				world.poseOwner(b.owner, b.joints());
			}
		}
		if (neighbour != null) {
			try {
				neighbour.tick(dt, hero.x, hero.y, hero.z);
			} catch (RuntimeException ex) {
				System.out.println("이웃 레벨 스크립트 오류: " + ex);
				neighbour = null;
				world.linkEvents(null);
			}
		}
	}

	/** 스크립트 Animator 가 움직이는 발판·문 (렌더 자세용). */
	public List<Platforms.Body> platformBodies() {
		return navWorld.platforms().bodies();
	}

	/** 이웃 레벨 Animator 오브젝트 (렌더 자세용). */
	public Platforms neighbourPlatforms() {
		return neighbourPlatforms;
	}

	/** 이음으로 넘겨받은 이 레벨 Animator 오브젝트 상태 (GameScene.Resident). */
	public void restorePlatforms(List<Platforms.State> states) {
		navWorld.platforms().restore(states);
	}

	/** 검증용: 이 레벨 오브젝트 애니 재생 ({@link Platforms#play(String, String, int, float, float)}). */
	public boolean playPlatform(String owner, String anim, int mode, float speed, float start) {
		return navWorld.platforms().play(owner, anim, mode, speed, start);
	}

	/** 이 레벨 Animator 오브젝트 상태 (이음으로 떠날 때 넘긴다). */
	public List<Platforms.State> platformStates() {
		return navWorld.platforms().states();
	}

	/** 붙잡는 오브젝트 (레버·크랭크·밀기 상자) — 영웅이 손잡이에 닿아야 하므로 벽 판정에서 뺀다. */
	private java.util.Set<String> grabbed;

	/** 움직이는 오브젝트 CDV (문·창살·회전방) 로 영웅 구 밀어내기. */
	public boolean collideBodies(Vector3f c, float r) {
		return collideBodies(c, r, devple.gow.engine.nav.NavWorld.HERO_BODY_EXCLUDE);
	}

	/** {@link #collideBodies(Vector3f, float)} 를 몸 제외 마스크로 ({@link devple.gow.engine.nav.NavWorld#bodyExclude}). */
	public boolean collideBodies(Vector3f c, float r, long exclude) {
		if (grabbed == null) {
			grabbed = new java.util.HashSet<>();
			for (Interactables.Item it : items.items()) {
				if (it.kind == Interactables.Kind.LEVER || it.kind == Interactables.Kind.PUSH) {
					grabbed.add(it.owner);
				}
			}
		}
		return navWorld.collideBodies(c, r, grabbed, exclude); // 이 레벨 + 다른 슬롯 레벨 문·발판 (이웃 Animator 자세)
	}

	/** 스크립트 Vis Attr11_0 → 충돌 켜고 끄기. */
	public void setSolid(List<String> owners, boolean on) {
		for (String o : owners) {
			navWorld.platforms().setSolid(o, on);
		}
	}

	/** 리스폰·Goto 뒤 내비게이터 상태 초기화. */
	public void reset() {
		navigator.reset();
	}
}
