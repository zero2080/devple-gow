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
	/** -Dgow.debug.task: 과제 진행 1023 기록 (크랭크·레버·CSM) [엔진 전용]. */
	private static final boolean DEBUG_TASK = System.getProperty("gow.debug.task") != null;
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

		/** Internal 0xD / 0xE 지금 / 바로 앞 카메라 이름 — 카메라 관리자는 하나라 이웃 스크립트도 같은 값 ({@link ScriptListener#cameraName}). */
		default String cameraName(boolean previous) {
			return "";
		}

		/** Internal 0x13 FailedScreen (0x1B0B58 → 0x29D958 = 1) — GameMgr 는 슬롯 구분 없음. */
		default void failedScreen() {
		}

		/**
		 * 이웃 레벨 스크립트의 CheckPoint (Internal 0x0) — GameMgr_OnMessage 0x1B0234 는 보낸 엔티티의 슬롯을 보지 않는다 [확실: 코드]
		 * (예 ATHN05D 가 현재인 채 ATHN05C CheckpointGroup 볼륨에 들어감).
		 */
		default void neighbourCheckpoint(ScriptEntity source) {
		}

		/**
		 * 이웃 레벨 스크립트의 Vis Visible (슬롯 0: 1 보임 / 2 숨김) — 원작은 두 슬롯 GO 가 같은 렌더 서버에 있어 이웃 스크립트의 숨김도 그대로 화면에 걸린다
		 * (예 PAND01A AxeAlcWall-CamSensor1: 이벤트 1037 카메라 AltCam43 등이면 goaxealcwall 숨김, 43 §13.8).
		 */
		default void neighbourVisible(List<String> objects, boolean show) {
		}

		/** 이웃 레벨 스크립트 Animator 가 이웃 GO 를 틀었다 — 그 GO act 의 채널 이미터 (먼지·불꽃) 도 돌린다 (원작은 슬롯과 무관하게 GO 애니가 이미터를 몬다). */
		default void neighbourAnimated(List<String> objects) {
		}

		/**
		 * 이웃 레벨 스크립트의 SpawnEnemy — 원작은 두 슬롯 스크립트가 같은 세계에서 돌아 이웃 레벨의 적도 나온다 (예 ATHN02C 복도 사다리 위 ATHN02B
		 * ClimbWallEnemies1 해골병, 지금 레벨은 ATHN02C 그대로 — 43 문서 §14).
		 */
		default void neighbourSpawn(ScriptListener.SpawnRequest r) {
		}

		/** 이웃 레벨 스크립트의 FadeOut/FadeIn — 원작 페이드 상태 0x303D50 은 전역 하나라 슬롯 구분이 없다 (43 §16). */
		default void neighbourFade(float r, float g, float b, float seconds, boolean out) {
		}

		/**
		 * 이웃 레벨 스크립트의 동영상 (Entity:0xD PlayMovie / 0x10 PreloadMovie, {@link ScriptListener#movie}) — 원작 동영상 재생기와 끝 이벤트 501 은
		 * 슬롯 구분이 없다: PostEvent 큐 0x303DB8 하나에 모든 슬롯 엔티티가 걸린다 ({@link ScriptWorld#linkEvents}). PAND00E 는 PAND00B 의 LoadWad 로만
		 * 올라오는 이웃이라 (LoadCheck 없음) 그 Mpeg-p00MP02play (죽어 가는 병사 동영상, 영상 3194–3262) 는 늘 이웃 슬롯에서 불린다 (43 §32).
		 */
		default void neighbourMovie(String name, int op, int arg) {
		}

		/**
		 * 이웃 레벨 스크립트의 Idle(bool) (Internal 0xC) — 원작 0x1BF080 → 전역 정지 0x29DB58 하나라 이웃 슬롯 스크립트도 영웅을 세운다
		 * (예 ATHN04A LedgeJumpCams-TriggerVol: ATHN04B 가 현재인 채 난간에 들어서면 Idle + SuicideCam 10.5 초, 43 §18).
		 */
		default void neighbourIdle(boolean idle) {
		}

		/**
		 * 이웃 레벨 스크립트의 구역 제목 (Internal 0x10) — 원작 GameMgr_OnMessage 0x1B0A28 → 0x1B60D0 → 0x1B8BA0 (HUD PS2_ZoneReport_Title/Event) 는
		 * 메시지를 보낸 슬롯을 보지 않는다 [확실: 코드]. 예: ATHN04C 계단을 올라 ATHN05A 마당에 들어서는 순간 05A 는 아직 이웃 슬롯인데 ZoneReportGroup
		 * (GlobalData ZoneArea ≠ 5 → ZoneReport(5)) 이 "아테네의 지붕" 을 띄운다 (영상 6539, 43 문서 §19). 예전에는 버려져 제목이 없었다.
		 */
		default void neighbourZoneReport(int zone) {
		}

		/**
		 * 이웃 레벨 스크립트의 Creator (타입 11, name = 슬롯 0 Name0) — 원작은 두 슬롯 GO 가 모두 살아 있어 이웃 레벨이 만든 세이브 포인트도 R2 로
		 * 저장된다: ATHN04A 의 SaveGameZone (1249, −81, 1442) 은 해치로 떨어진 ATHN04C 아래층 (현재 = 04C) 에서 쓰인다 (영상 6450–6456, 43 §18).
		 */
		default void neighbourCreate(ScriptEntity source, Object name) {
		}

		/** 다른 슬롯에 레벨을 새로 읽는다: 레벨 기억 (없으면 null = 처음, GameSession.enterLevel). */
		default ScriptWorld.Memory levelMemory(String level) {
			return null;
		}

		/** 다른 슬롯의 레벨이 내려간다 (다른 레벨로 바뀜 · LoadForWarp 로 비움): 그 세계를 레벨 기억에 (GameSession.rememberLevel). */
		default void levelLeft(String level, ScriptWorld w) {
		}
	}

	/** 현재 슬롯 레벨 (이음 {@link #swapSlots} 에서 이웃과 바뀐다). */
	private LevelData level;
	private ScriptWorld world;
	private final Host host;
	public final NavWorld navWorld;
	public Interactables items;
	public final HeroNavigator navigator;
	private Tweaks tweaks;
	private NavWorld.Floor lastFloor;
	/** 이웃 레벨 (다른 슬롯) 스크립트 — 원작은 두 레벨 엔티티가 같이 돈다 (36 문서 §6 한계 → 38 문서 §4). */
	private String neighbourName;
	private ScriptWorld neighbour;
	/** 이웃 레벨 데이터 (볼륨 자세 {@link #poseBody}). */
	private LevelData neighbourData;
	/**
	 * 이웃 레벨 Animator 오브젝트 (문·발판) — 원작은 두 슬롯이 같이 살아 있어 이웃 스크립트의 Animator 도 그 레벨 오브젝트를 움직이고, 이음으로 떠나온
	 * 레벨의 열린 문도 그대로 남는다 (43 §13.8). 렌더 자세만 (충돌은 이웃 정적 충돌 그대로 [근사]).
	 */
	private Platforms neighbourPlatforms = new Platforms();
	/**
	 * 이웃 레벨의 R1/R2 오브젝트 (레버·크랭크·TAndF 문·CSM·밀기 상자). 원작 잡기 질의 0x1C3F18 은 슬롯 구분 없는 충돌 세계 구 질의라 이웃 레벨
	 * 오브젝트도 잡힌다 — ATHN04C (해치 아래층, LoadCheck4CEntity 로 현재 = 04C) 에서 ATHN04A 의 창살 LiftDoor13 을 들어 올린다 (영상 6460–6462).
	 * 예전엔 이웃 레벨 행동 GO 가 몸 (Platforms) 에도 빠져 ({@link Platforms#staticOwner}) 닫힌 창살을 걸어서 지나갔다 (43 §18).
	 * 이벤트 (1023·1048·1049) 는 공유 큐 ({@link ScriptWorld#linkEvents}) 로 이웃 센서에 간다.
	 */
	private Interactables neighbourItems = new Interactables();
	/** 이웃 슬롯 레벨의 TWK (CSM 상자 템플릿 · OrbEmitter — 레벨마다 다르다). */
	private Tweaks neighbourTweaks;
	/** 시작 중인 이웃 세계 (setOther 안 nw.start 동안 — 생성 통지 Creator 의 슬롯을 읽을 세계). */
	private ScriptWorld neighbourStarting;
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
		navWorld.setPlatforms(Platforms.of(level, animatorOwners(world), bodyOwners(items)));
		registerMaterialAnimators(world, navWorld.platforms());
		navigator = new HeroNavigator(hero.ctl, navWorld, new NavAnims(hero.tweaks));
		navigator.setItems(items);
		navigator.setListener(new HeroNavigator.Listener() {
			@Override
			public void taskProgress(Interactables.Item it, devple.gow.engine.nav.TaskProgress.Event e) {
				// 원작 0x215928 / 0x2118E8: 1023 (%, 과제 id, 재생 속도 × 100)
				if (DEBUG_TASK) {
					System.out.printf("1023 %s: %d %% id %d 속도 %d%n", it.owner, e.percent(), e.taskId(), e.speed100());
				}
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
			public void ropeGrabbed(devple.gow.engine.nav.NavRope r) {
				// 원작 0x1C4EF8 → 0x1C4FD0: sub_001A8C78(0x41B, 0, 0, 0, 영웅) — ATHN03C RopeAttachGroup-attachRope (모드 1 = 거르기만:
				// 인자 0 == 0, 한 번) → ShowMessage(591) "줄을 앞뒤로 흔들려면 ◎을 누르고 있으십시오" (영상 5612)
				world.postEvent(HeroNavigator.EVENT_ROPE_GRAB, 0, 0, 0, world.player());
			}

			@Override
			public void pushMoved(Interactables.Item it, Vector3f d) {
				boolean other = neighbour != null && neighbourItems.items().contains(it);
				(other ? neighbour : world).moveOwnerBy(it.owner, d.x, d.y, d.z);
				Platforms ps = other ? neighbourPlatforms : navWorld.platforms();
				if (ps.shift(it.owner, d.x, d.y, d.z)) { // 상자 몸·자식 GO 도 (렌더·충돌)
					Platforms.Body b = ps.body(it.owner);
					Interactables.refreshHandles(it, b.skeleton(), b.joints()); // 손잡이 (handle1/2) 도 같이 — 다시 잡을 수 있게
				}
			}

			@Override
			public void interactDone(Interactables.Item it) {
				System.out.println("상황 동작 완료: " + it);
				if (it.kind == Interactables.Kind.CHEST) {
					boolean other = neighbour != null && neighbourItems.items().contains(it);
					(other ? neighbour : world).markOpened(it.owner); // 이음·레벨 기억으로 다시 만들 때 열린 채 (ScriptWorld.opened)
					host.orbs(chestEmitter(it, other ? neighbourTweaks() : tweaks), it.pos);
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
	 * 소유 GO 에 거는 (모드 1) "$재질" Animator 의 재질 시계를 생성 상태로 등록한다 ({@link Platforms#registerMaterial}): 0x42 재질 (스크립트용 한 번)
	 * 은 스크립트가 틀 때까지 시간 0 (ATHN02D 남쪽 문 감산 막 p28 0.5), 0x41 은 생성 때부터 반복.
	 */
	public static void registerMaterialAnimators(ScriptWorld w, Platforms ps) {
		for (ScriptEntity e : w.entities()) {
			if ((e.type() == 5 || e.type() == 6) && e.mode() == 1 && e.ownerName() != null && w.slot(e, 0) instanceof String s && s.startsWith("$")) {
				ps.registerMaterial(e.ownerName(), s);
			}
		}
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
		setOther(name, data, otherCol, neighbourListener, null);
	}

	/** kept = 그 레벨이 슬롯에 그대로 있던 세계 (넘겨 씀, 없으면 null = 새로). */
	public void setOther(String name, LevelData data, LevelCollision otherCol, ScriptListener neighbourListener, ScriptWorld kept) {
		setOther(name, data, otherCol, neighbourListener, kept, null);
	}

	/** otherTweaks = 그 레벨 TWK (영구 + 레벨) — 이웃 R1/R2 오브젝트 템플릿용, null 이면 이 레벨 것. */
	public void setOther(String name, LevelData data, LevelCollision otherCol, ScriptListener neighbourListener, ScriptWorld kept, Tweaks otherTweaks) {
		navWorld.setOther(otherCol);
		if (name == null || name.equals(neighbourName)) {
			return;
		}
		if (neighbour != null && neighbourName != null) {
			host.levelLeft(neighbourName, neighbour); // 바뀌는 다른 슬롯 레벨 → 레벨 기억 (0x1BC110 → 0x1B3CD0)
		}
		neighbourName = name;
		neighbour = null;
		neighbourData = data;
		neighbourTweaks = otherTweaks;
		neighbourPlatforms = new Platforms();
		navWorld.setNeighbourPlatforms(neighbourPlatforms);
		setNeighbourItems(new Interactables());
		world.linkEvents(null);
		if (data == null) {
			return;
		}
		try {
			// 두 슬롯에 그대로 있던 레벨 (이음으로 떠나온 레벨) 은 그 세계를 넘겨 쓴다 — 시작 큐를 다시 돌리지 않는다 (GameSession.residentWorlds, 43 §18)
			ScriptWorld nw = kept != null ? kept : new ScriptWorld(data, ScriptHost.neighbour(mainHost), neighbourListener);
			ScriptWorld.Memory mem = null;
			neighbourMemory = null;
			if (kept != null) {
				nw.rebind(neighbourListener);
			} else {
				mem = host.levelMemory(name); // 레벨 기억 (0x1B38A8 → 0x1B4618)
				neighbourMemory = mem;
				if (mem != null) {
					nw.restore(mem);
					System.out.println("이웃 레벨 기억으로 되살림: " + name + " (발동 기록 " + mem.fired().size() + ", 적 " + mem.enemies().size() + ", 몸 "
							+ mem.anims().size() + ")");
				}
			}
			Interactables ni = Interactables.of(data, otherTweaks != null ? otherTweaks : tweaks);
			Set<String> owners = animatorOwners(nw);
			Set<String> posed = new HashSet<>(owners);
			posed.addAll(bodyOwners(ni)); // 이웃 R1/R2 오브젝트 (밀기 상자 포함) 도 자세·충돌을 갖는 몸으로 (닫힌 창살이 막는다)
			neighbourPlatforms = Platforms.of(data, owners, posed);
			registerMaterialAnimators(nw, neighbourPlatforms);
			navWorld.setNeighbourPlatforms(neighbourPlatforms); // 이웃 문·발판 충돌도 자세대로 (43 §13.8 [근사] 해소)
			setNeighbourItems(ni);
			if (kept == null) {
				neighbourStarting = nw;
				nw.start();
				neighbourStarting = null;
				Vector3f p = world.player() != null ? new Vector3f(world.player().pos[0], world.player().pos[1], world.player().pos[2]) : new Vector3f();
				nw.placePlayer(p.x, p.y, p.z, true); // 이미 서 있는 볼륨은 "안" 으로 (들어올 때 발동하지 않음)
				if (mem != null && !mem.anims().isEmpty()) {
					restoreNeighbourPlatforms(mem.anims()); // 레벨 기억의 Animator 몸 (원작 덩어리 GO 상태)
				}
			}
			neighbour = nw;
			world.linkEvents(nw); // 전역 이벤트 큐 공유 (원작 0x303DB8)
			System.out.println("이웃 레벨 스크립트: " + name + " (엔티티 " + nw.entities().size() + (kept != null ? ", 넘겨 씀" : "") + ")");
		} catch (RuntimeException ex) {
			System.out.println("이웃 레벨 스크립트 실패 " + name + ": " + ex);
		}
	}

	/** 다른 슬롯 비움 (LoadForWarp 가 꺼진 레벨을 올림): 충돌·발판·스크립트·이벤트 큐 연결 없음. */
	public void clearOther() {
		navWorld.setOther(null);
		if (neighbour != null && neighbourName != null) {
			host.levelLeft(neighbourName, neighbour);
		}
		neighbourName = null;
		neighbour = null;
		neighbourData = null;
		neighbourTweaks = null;
		neighbourPlatforms = new Platforms();
		navWorld.setNeighbourPlatforms(neighbourPlatforms);
		setNeighbourItems(new Interactables());
		world.linkEvents(null);
	}

	/**
	 * 이음 (LoadCheck · 걷기 — 43 §27): 현재 ↔ 다른 슬롯 역할만 바꾼다. 원작 0x1BCA10 은 현재 레벨 이름 (0x29DAE8) 만 바꾸고 두 슬롯의 스크립트·GO·
	 * 충돌은 그대로 산다 [확실]. 여기서도 객체는 그대로 두고 자리만 바꾼다: LevelData · 스크립트 세계 · R1/R2 오브젝트 (내비게이터의 잡은 오브젝트도
	 * 그대로) · TWK · 발판 (자세·애니 진행 그대로) · 정적 충돌 순서 (현재 먼저). 줄은 엔진이 현재 레벨 것만 시뮬레이션하므로 새 현재 레벨 줄로 바꾸되
	 * 지금 매달린 줄은 남긴다 [근사: 엔진 줄 구조]. 스크립트 리스너·이벤트 큐 연결은 호출자 (GameScene) 가 다시 건다.
	 *
	 * @param leaving 지금 현재 레벨 이름 (이웃이 된다)
	 */
	public void swapSlots(String leaving) {
		if (neighbour == null || neighbourData == null) {
			throw new IllegalStateException("이웃 슬롯 없음");
		}
		LevelData l = level;
		level = neighbourData;
		neighbourData = l;
		ScriptWorld w = world;
		world = neighbour;
		neighbour = w;
		neighbourName = leaving;
		Tweaks t = tweaks;
		tweaks = neighbourTweaks != null ? neighbourTweaks : tweaks;
		neighbourTweaks = t;
		Platforms p = navWorld.platforms();
		navWorld.setPlatforms(neighbourPlatforms);
		neighbourPlatforms = p;
		navWorld.setNeighbourPlatforms(p);
		navWorld.swapStatics();
		NavRope held = navigator.rope();
		navWorld.ropes().clear();
		navWorld.ropes().addAll(NavRope.of(level));
		if (held != null && !navWorld.ropes().contains(held)) {
			navWorld.ropes().add(held); // 매달린 채 넘어온 줄 (떠나는 레벨 것) — 놓을 때까지
		}
		Interactables it = items;
		items = neighbourItems;
		navigator.setItems(items);
		setNeighbourItems(it);
	}

	/** 이음 뒤 현재 슬롯 TWK 를 바꾼다 (GameScene 이 새로 만든 오브 튜닝 = 영구 + 레벨 + 적 종류, enter 와 같은 구성). */
	public void setTweaks(Tweaks t) {
		if (t != null) {
			tweaks = t;
		}
	}

	/** 현재 슬롯 레벨 데이터 (스크립트 세계·R1 오브젝트·발판이 쓰는 것). */
	public LevelData levelData() {
		return level;
	}

	/** 다른 슬롯 레벨 데이터 (없으면 null). */
	public LevelData neighbourData() {
		return neighbourData;
	}

	/** 이웃 레벨 R1/R2 오브젝트 (없으면 빈 것). */
	public Interactables neighbourItems() {
		return neighbourItems;
	}

	private void setNeighbourItems(Interactables ni) {
		neighbourItems = ni;
		navigator.setNeighbourItems(ni);
		grabbed = null; // 붙잡는 오브젝트 몸 제외 목록을 다시
	}

	public ScriptWorld neighbour() {
		return neighbour;
	}

	/** 이웃 레벨 스크립트 엔티티의 슬롯 값 (시작 중인 이웃 세계 먼저 — {@link Host#neighbourCreate} 안에서), 이웃이 없으면 null. */
	public Object neighbourSlot(ScriptEntity e, String name) {
		ScriptWorld w = neighbourStarting != null ? neighbourStarting : neighbour;
		return w != null ? w.slot(e, name) : null;
	}

	/** 다른 슬롯 레벨을 되살린 레벨 기억 (부순 물체를 씬이 건다, null = 처음 · 넘겨 씀). */
	private ScriptWorld.Memory neighbourMemory;

	public ScriptWorld.Memory neighbourMemory() {
		return neighbourMemory;
	}

	/**
	 * 이웃 스크립트가 지금 적을 낸 세계: 시작 큐를 도는 중이면 (SpawnAtStart, {@link #setOther} 안 — 아직 {@link #neighbour()} 가 비었다) 그 세계.
	 * 예전에는 이 순간의 null 을 전투 다리 세계로 잡아, 시작 스폰 적의 제거·Respawn 이 현재 레벨 세계로 가서 현재 레벨 리스너가 이웃 템플릿을
	 * 대역 (현재 레벨 첫 적 종류) 으로 다시 냈다 (ATHN05D 에서 ATHN05C 하피 · 미노타우로스가 R_SKD 로 3 초마다, 43 §21).
	 */
	public ScriptWorld spawningNeighbour() {
		return neighbourStarting != null ? neighbourStarting : neighbour;
	}

	public String neighbourName() {
		return neighbourName;
	}

	/**
	 * 이웃 레벨 스크립트 리스너: 메시지·사망 존·LoadCheck·Goto 만 받는다. 이웃의 LoadWad/Warp 는 원작에서 "호출한 레벨이 아닌 슬롯" = 현재 레벨 자리에
	 * 로드하므로 (36 문서 §2) 여기서는 무시한다 [근사]. 적은 이웃 스폰 규칙으로 ({@code Host.neighbourSpawn}), Creator 상자·세이브 포인트는
	 * {@code Host.neighbourCreate} ({@link #createNeighbour}).
	 */
	public ScriptListener neighbourListener() {
		return new ScriptListener() {
			@Override
			public void message(int id, float seconds) {
				host.message(id, seconds);
			}

			@Override
			public void checkpoint(ScriptEntity source) {
				host.neighbourCheckpoint(source); // GameMgr 메시지 0 은 슬롯 구분 없음 (0x1B0234)
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
			public void fade(float r, float g, float b, float seconds, boolean out) {
				host.neighbourFade(r, g, b, seconds, out);
			}

			@Override
			public void camera(String name) {
				host.neighbourCamera(name, null, null, false);
			}

			@Override
			public String cameraName(boolean previous) {
				return host.cameraName(previous);
			}

			@Override
			public void animate(ScriptEntity source, List<String> objects, String anim) {
				applyAnimator(neighbour, neighbourPlatforms, source, objects, anim);
				host.neighbourAnimated(objects);
			}

			@Override
			public void spawnEnemy(SpawnRequest r) {
				host.neighbourSpawn(r);
			}

			@Override
			public void movie(String name, int op, int arg) {
				host.neighbourMovie(name, op, arg);
			}

			@Override
			public void internal(int func, List<Object> args) {
				neighbourInternal(host, func, args);
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
				for (String o : objects) { // 이웃 R1/R2 오브젝트: 숨김 (Visible 2) · 충돌 끔 (Attr11_0 2) 이면 잡히지 않는다
					Interactables.Item it = neighbourItems.byOwner(o);
					if (it != null) {
						if (values.length > 0 && (values[0] == 1 || values[0] == 2)) {
							it.hidden = values[0] == 2;
						}
						if (values.length > 3 && values[3] != 0) {
							it.noCollision = values[3] == 2;
						}
						if (values.length > 9 && values[9] != 0) {
							it.behaviourOff = values[9] == 2; // 슬롯 16: 비헤이비어 (BhvrServer 0x17) 끔 (43 §33)
						}
					}
				}
				if (values.length > 4 && values[4] != 0) {
					if (System.getProperty("gow.trace.vis") != null) {
						System.out.println("VIS(이웃) " + source.name() + " CMV " + (values[4] == 1 ? "ON " : "OFF ") + objects);
					}
					host.neighbourCamera(null, null, objects, values[4] == 1);
				}
			}

			@Override
			public void create(ScriptEntity source) {
				ScriptWorld w = neighbourStarting != null ? neighbourStarting : neighbour;
				if (w != null) {
					host.neighbourCreate(source, w.slot(source, "Name0"));
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
	 * Creator "chest" → R2 상자 (38 §21). 원작 Creator 생성 0x1ACCA8: 슬롯 0 Name0 앞에 "go" 를 붙여 (없으면, 0x1A4960) 그 이름의 오브젝트 리소스
	 * (0x1AD280 → 0x17A7B8, 형 3) 를 {@link #creatorMatrix 엔티티 행렬} 에 새 GO 로 만든다 (0x1A5130, 슬롯 3..6 = unk3..unk6 을 넘김: unk5 = CSM 템플릿,
	 * unk6 = 과제 id). "chest" = R_PERMK 오브젝트 chest (MDL_chest·ANM_chest·CDV_chest, GO gochest = IO_CSM) — 소유 GO (gochest2) 는 메시 없는 자리표.
	 * 이름은 대소문자를 가리지 않는다 ({@link #creatorIs}: "Chest" 40 개). 묶음 GO 하나에 상자가 여럿이면 (ATHN05A ChestGroup 둘) 상자마다 따로 키를 준다.
	 * 모델·충돌·손잡이는 {@link ChestProps}.
	 *
	 * @return 상자 (Creator 가 상자가 아니면 null)
	 */
	public Interactables.Item create(ScriptEntity source) {
		return addChest(items, world, source, tweaks);
	}

	/**
	 * 이웃 슬롯 레벨의 Creator "chest" → 이웃 R2 상자. 원작은 두 슬롯이 모두 살아 있어 Creator 0x1ACCA8 이 슬롯마다 자기 GO 를 만들고, 잡기 질의 0x1C3F18
	 * 은 슬롯 구분 없는 충돌 세계 구 질의다 ({@link #neighbourItems}) [확실: 구조]. 예전 엔진은 이웃 상자를 만들지 않아 ATHN05C 항아리 방 (바닥이 05C 에만
	 * 있다) 의 ATHN05D 상자 둘 (Chest3 CSM 010 고르곤의 눈 · Chest4 CSM 011 불사조 깃털, 영상 8090–8100) 이 보이지도 열리지도 않았다 (43 §21).
	 * CSM 템플릿은 레벨 TWK 마다 다르므로 이웃 레벨 것으로 ({@link #neighbourTweaks()}).
	 *
	 * @return 상자 (이웃이 없거나 상자가 아니면 null)
	 */
	public Interactables.Item createNeighbour(ScriptEntity source) {
		ScriptWorld w = neighbourStarting != null ? neighbourStarting : neighbour;
		return w == null ? null : addChest(neighbourItems, w, source, neighbourTweaks());
	}

	/** Creator 슬롯 → 상자 (Name0 "chest", unk5 = CSM 템플릿, unk6 = 과제 id). 그 세계가 이미 연 상자면 끝난 채 ({@link ScriptWorld#opened}). */
	static Interactables.Item addChest(Interactables into, ScriptWorld w, ScriptEntity source, Tweaks tw) {
		if (!creatorIs(w.slot(source, "Name0"), "chest") || source.owner == null) {
			return null;
		}
		float[] m = creatorMatrix(source);
		Interactables.Item it = into.addChest(creatorKey(source), new Vector3f(m[12], m[13], m[14]), num(w.slot(source, "unk5")), num(w.slot(source, "unk6")), tw);
		it.done = w.opened(it.owner);
		return it;
	}

	/** 이웃 슬롯 레벨의 TWK (없으면 이 레벨 것). */
	public Tweaks neighbourTweaks() {
		return neighbourTweaks != null ? neighbourTweaks : tweaks;
	}

	/**
	 * Creator Name0 이 그 오브젝트인가: 원작 오브젝트 레지스트리는 이름을 대문자로 바꿔 해시한다 (0x17A7B8 → 0x175C10 → 0x1759F0 → 0x175938 이 a–z 를
	 * 대문자로 복사한 뒤 Hash127) → 대소문자 무시 [확실]. 데이터: Name0 "Chest" 40 개 (DEST01 8 · ATHN06B 6 · HDES01B 8 · ATHN05B RespawnChest1 …) 는
	 * 예전 엔진 ("chest".equals) 에서 상자가 아예 생기지 않았다 (43 문서 §19).
	 */
	public static boolean creatorIs(Object name0, String object) {
		if (!(name0 instanceof String s)) {
			return false;
		}
		String n = s.startsWith("go") ? s.substring(2) : s; // 0x1A4960: "go" 로 시작하지 않으면 붙인다
		return n.equalsIgnoreCase(object);
	}

	/**
	 * Creator 가 만드는 GO 의 행렬 = 원작 0x1A9CD0: 엔티티 레코드 행렬 (+0x00) · 소유 GO 관절 (+0x40) 월드 ({@link ScriptEntity#world}), 회전 세 행은
	 * 길이 1 로 정규화, 이동은 그대로 [확실]. 0x1ACCA8 은 그 앞에 슬롯 2 (unk2, +0xC0) 배율 대각 행렬을 곱한다 — 기록에 처리기가 없는 Creator 뿐이라
	 * 1 로 둔다 [추정: 엔티티 생성 기본값]. 소유 GO 인스턴스 행렬과 다를 수 있다: 묶음 GO (ATHN05A ChestGroup 인스턴스 (0, 0, 0) · 관절 0 (608, 640, 3008))
	 * 는 상자마다 엔티티 행렬이 자리를 준다 — 전 레벨 상자 24 개 · PAND02C 세이브 포인트 하나가 예전엔 소유 GO 자리 (대개 원점) 에 생겼다 (43 문서 §19).
	 */
	public static float[] creatorMatrix(ScriptEntity s) {
		float[] m = s.world.clone();
		for (int r = 0; r < 3; r++) {
			float l = (float) Math.sqrt(m[r * 4] * m[r * 4] + m[r * 4 + 1] * m[r * 4 + 1] + m[r * 4 + 2] * m[r * 4 + 2]);
			if (l > 1e-6f) {
				m[r * 4] /= l;
				m[r * 4 + 1] /= l;
				m[r * 4 + 2] /= l;
			}
		}
		return m;
	}

	/**
	 * Creator 가 만든 GO 의 이름 (상호작용 키): 원작은 새 GO 라 소유 GO 와 따로다. 엔진 키는 만든 자리가 소유 GO 자리면 소유 GO 이름 (Vis·이음 상태가
	 * 그 이름으로 찾는다), 아니면 소유 GO 이름 + "/" + 엔티티 이름 (묶음 GO 의 상자들이 한 키를 나눠 쓰지 않게).
	 */
	public static String creatorKey(ScriptEntity s) {
		String owner = s.owner.instance().name();
		float[] m = s.world, p = s.owner.instance().pos();
		float dx = m[12] - p[0], dy = m[13] - p[1], dz = m[14] - p[2];
		return dx * dx + dy * dy + dz * dz <= 1f ? owner : owner + "/" + s.name().toLowerCase(java.util.Locale.ROOT);
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

	/**
	 * 발판 몸 ({@link Platforms}) 으로 만들 R1/R2 오브젝트: {@link #posedOwners} + 밀기 상자 (PushPull Type 0) — 밀린 상자는 몸을 옮겨
	 * ({@link Platforms#shift}) 렌더·CDV·자식 GO 가 따라가게 한다 (원작 0x211A10 은 GO 위치 자체를 옮긴다).
	 */
	public static java.util.Set<String> bodyOwners(Interactables items) {
		java.util.Set<String> out = posedOwners(items);
		for (Interactables.Item it : items.items()) {
			if (it.kind == Interactables.Kind.PUSH) {
				out.add(it.owner);
			}
		}
		return out;
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
			if (it.kind == Interactables.Kind.LIFT && b != null && physicalLift(it, ps)) {
				// 문 GO 를 직접 움직이는 TAndF (ATHN04A 창살 · ATHN04C 나무 게이트 LiftDoor13): 원작 0x214058 은 잡은 동안 문 Y = 처음 Y + 영웅 synchJoint
				// 모델 공간 높이, 0x214180 은 끝나면 중력 50×16 으로 처음 Y + 높이 (템플릿 ×16) 까지 오르고, 다 들기 전에 놓거나 1015 리셋이면 같은 중력으로
				// 처음 Y 까지 떨어진다 [확실] — 오프셋은 HeroNavigator 가 정한다 (Item.slideY, 43 문서 §19)
				if (ps.lift(it.owner, it.slideY)) {
					world.poseOwner(b.owner, b.joints());
				}
				continue;
			}
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

	/**
	 * 이웃 슬롯 스크립트의 Internal 호출 중 전역 상태를 바꾸는 것 — 원작 GameMgr / 카메라 관리자 / 전역 정지는 슬롯 구분이 없다:
	 * 0x9 CMV 질의 on/off, 0xC Idle (0x1BF080 → 0x29DB58), 0x10 구역 제목 (0x1B0A28 → 0x1B8BA0, 43 문서 §19), 0x13 FailedScreen (0x1B0B58,
	 * 43 §23) [확실: 코드].
	 */
	static void neighbourInternal(Host host, int func, List<Object> args) {
		if (func == 0x13) {
			host.failedScreen(); // FailedScreen (인자 없음)
			return;
		}
		if (args.isEmpty()) {
			return;
		}
		Object a = args.get(0);
		if (func == 0x9) {
			host.neighbourCamera(null, a instanceof Number n ? n.floatValue() != 0 : !(a instanceof Boolean b) || b, null, false);
		}
		if (func == 0xC) {
			host.neighbourIdle(a instanceof Number n ? n.floatValue() != 0 : !(a instanceof Boolean b) || b);
		}
		if (func == devple.gow.engine.script.GameScriptHost.ZONE_REPORT && a instanceof Number n) {
			host.neighbourZoneReport(n.intValue()); // 구역 제목은 슬롯 구분 없이 (0x1B0A28)
		}
	}

	/**
	 * 문 GO 를 직접 움직이는 TAndF ({@link Platforms#lift}) — 템플릿 +0x158 플래그 비트 0 (#a6e81c1e, 0x214470 · 0x2145B8 · 0x21491C 가 이것으로만
	 * 가른다) [확실]. 데이터의 리프트 게이트 12 템플릿이 비트 0 이고 그 문 ANM 에는 템플릿 act (연타 #d7f2bea8 · 완료 #c5b0169f) 가 없다; 해치·석상 등
	 * 오브젝트 애니 TAndF 는 비트 0 이 없다. ps 는 예전 판정 (act 유무) 과 같은 꼴을 위해 남긴다.
	 */
	static boolean physicalLift(Interactables.Item it, Platforms ps) {
		return it.kind == Interactables.Kind.LIFT && it.slide && it.liftHeight > 0;
	}

	/** 한 틱: 발판 애니 → Marker 위치, 이웃 스크립트 (영웅 볼륨 접촉점 = 발). */
	public void update(float dt, Vector3f hero) {
		update(dt, hero, hero);
	}

	/**
	 * 한 틱: 발판 애니 → Marker 위치, 이웃 스크립트.
	 * @param contact 스크립트 볼륨 접촉점 (영웅 pelvis — {@code HeroRig.contactPoint}, 원작 0x1ADD74 / 0x1A6308)
	 */
	public void update(float dt, Vector3f hero, Vector3f contact) {
		navWorld.platforms().update(dt);
		neighbourPlatforms.update(dt);
		rideBoxes(items, navWorld.platforms(), world);
		if (neighbour != null) {
			rideBoxes(neighbourItems, neighbourPlatforms, neighbour);
		}
		poseItems(items, navWorld.platforms(), world, dt);
		if (neighbour != null) {
			poseItems(neighbourItems, neighbourPlatforms, neighbour, dt); // 이웃 TAndF 창살·해치 자세 = 진행 (렌더는 다른 슬롯 자세로)
		}
		for (Platforms.Body b : navWorld.platforms().bodies()) {
			poseBody(world, level, b, volumePosed);
			if (b.followMoved()) {
				world.followOwner(b.owner, b.joints());
				Interactables.Item it = items.byOwner(b.owner);
				if (it != null && it.kind != Interactables.Kind.CHEST) {
					Interactables.refreshHandles(it, b.skeleton(), b.joints()); // 회전판 위 발리스타 크랭크 손잡이
				}
			}
		}
		if (neighbourData != null) {
			for (Platforms.Body b : neighbourPlatforms.bodies()) {
				poseBody(null, neighbourData, b, volumePosed);
			}
		}
		if (neighbour != null) {
			try {
				neighbour.tick(dt, contact.x, contact.y, contact.z);
			} catch (RuntimeException ex) {
				System.out.println("이웃 레벨 스크립트 오류: " + ex);
				neighbour = null;
				world.linkEvents(null);
			}
		}
	}

	/**
	 * 발판 관절 위의 밀기 상자를 그 관절과 같이 돌리고 옮긴다 — 원작 상자 갱신 0x212398 (Type 0/7/8) 이 매 틱 0x1E6248 로 지면 GO 관절 (바닥 탐침
	 * 0x1E3298 이 +0x24C / +0x250 에 적음) 의 움직임을 yaw 만 남겨 몸에 곱한다 [확실: 코드]. ATHN05B 홀의 둥근 판 = goladder 관절 15 group2920
	 * (레버 lever4Ladder 602 → ladder-AnimDown/AnimUp, act "ladder" 3.67 초에 yaw 0 → 90°) 위에 발리스타 BallistaPlatfor 를 올리고 레버를 당기면
	 * 90° 돌아간다 (영상 7836–7840, 두 번 돌려 처음 남쪽 → 북쪽 문). 예전 엔진은 상자가 발판을 따라가지 않아 볼트가 남쪽 벽에만 날아가
	 * BrkbDoor 를 깰 수 없었다 (43 §22).
	 */
	public static void rideBoxes(Interactables its, Platforms ps, ScriptWorld w) {
		if (its == null || ps == null) {
			return;
		}
		for (Interactables.Item it : its.items()) {
			if (it.kind != Interactables.Kind.PUSH) {
				continue;
			}
			Set<String> self = ps.family(it.owner);
			Platforms.Support s = ps.floor(it.pos.x, it.pos.y + 16, it.pos.z, 32, self);
			float[] d = Platforms.ride(s, it.pos);
			if (d == null || !ps.ride(it.owner, d)) {
				continue;
			}
			float[] to = new float[3];
			devple.gow.tools.anim.Mat4.transformPoint(d, it.pos.x, it.pos.y, it.pos.z, to);
			if (w != null) {
				w.moveOwnerBy(it.owner, to[0] - it.pos.x, to[1] - it.pos.y, to[2] - it.pos.z);
			}
			it.pos.set(to[0], to[1], to[2]);
		}
	}


	/** 몸마다 마지막으로 볼륨·Marker 에 반영한 관절 배열 (Body.pose 는 틱마다 새 배열 — 같은 배열이면 자세 그대로). */
	private final java.util.Map<Platforms.Body, float[][]> volumePosed = new java.util.IdentityHashMap<>();

	/**
	 * 몸 자세 (Animator 애니 · 부모 링크 follow · 도착 자세 맞춤 · 이음 상태) → 그 GO 의 Marker (world, null = 이웃 슬롯은 볼륨만) 와 볼륨
	 * (스크립트 진입 센서·CMV·소리) 조각 ({@link LevelData.PlacedVolume#pose}).
	 * <ul>
	 *   <li>볼륨: 원작 충돌 월드는 조각마다 GO 관절 행렬로 판정한다 (0x1A7FFC → 0x11E190, BallHull off[6]) [확실: 38 §4 · 데이터]. 예전에는 부모 링크
	 *       이동만 관절 0 으로 따랐다 (ATHN02A 정박한 배 ENV_shipGroup) — ATHN05D 엘리베이터의 발판 구 PlayerVolume0 (관절 2 pressPlate) · 눌림 상자
	 *       MarkerVolume1 (관절 1 elevatorRT) 은 제자리 관절 0 에 남아 발판에 서도 내려가지 않았다 (43 §21).</li>
	 *   <li>Marker: 자세가 바뀐 틱 (관절 배열이 새것) 마다 — 한 번 재생 애니의 마지막 틱은 {@link Platforms#update} 가 playing 을 먼저 거짓으로 두고 끝
	 *       자세를 매기므로 playing 만 보면 Marker 가 끝 바로 앞 틱 자리에 남는다: ATHN05D 엘리베이터 Marker599 는 끝 (act 7 초) 에서 y 676.2 로
	 *       DownVol2 (668.6–676.6) 안인데 한 틱 앞 676.9 에 남아 Warp 볼륨에 영영 들지 않았다 (43 §21).</li>
	 * </ul>
	 *
	 * @return 이번 틱 자세가 바뀌었는지 (처음 본 몸은 거짓)
	 */
	static boolean poseBody(ScriptWorld world, LevelData data, Platforms.Body b, java.util.Map<Platforms.Body, float[][]> posed) {
		float[][] j = b.joints();
		float[][] last = posed.get(b);
		boolean moved = last != null && last != j;
		if (world != null && (b.playing() || b.followMoved() || moved)) { // 부모 링크로 따라 움직인 몸 (ATHN02A 정박한 배) 의 Marker 도
			world.poseOwner(b.owner, j);
		}
		if (j != null && last != j) {
			posed.put(b, j);
			for (LevelData.PlacedVolume v : data.volumes) {
				if (v.owner().equalsIgnoreCase(b.owner)) {
					v.pose(j);
				}
			}
		}
		return moved;
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
		restoreItems(items, states);
	}

	/** 이음으로 넘겨받은 다른 슬롯 레벨 오브젝트 상태 (GameScene.Resident) — 몸 자세 + 이웃 R1/R2 오브젝트. */
	public void restoreNeighbourPlatforms(List<Platforms.State> states) {
		neighbourPlatforms.restore(states);
		restoreItems(neighbourItems, states);
	}

	/**
	 * 넘겨받은 몸 상태 → R1/R2 오브젝트: 충돌이 꺼진 몸은 잡히지 않고 (넘겨 쓴 세계는 생성 스크립트 해치 CDsOff 가 다시 돌지 않는다), 열린 자세
	 * (TAndF 완료 애니 trapOpen 등) 로 남은 TAndF 는 끝난 것으로 — 씬마다 새로 만든 오브젝트가 열린 해치·창살을 다시 잡아 닫던 것을 막는다 [근사: 엔진 구조].
	 */
	static void restoreItems(Interactables items, List<Platforms.State> states) {
		for (Platforms.State s : states) {
			Interactables.Item it = items.byOwner(s.owner());
			if (it == null) {
				continue;
			}
			if (!s.solid()) {
				it.noCollision = true;
			}
			if (it.kind == Interactables.Kind.LIFT && it.slide) {
				it.slideY = s.lift(); // 넘겨받은 문 높이에서 이어서 (1015 리셋이면 거기서 떨어진다, 0x214180)
			}
			if (it.kind == Interactables.Kind.LIFT && (it.openObjectAnim != null && it.openObjectAnim.equalsIgnoreCase(s.anim())
					|| it.liftHeight > 0 && s.lift() >= it.liftHeight - 0.5f)) {
				it.done = true;
				it.progress = 1f;
				it.openTime = s.time();
			}
		}
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
			for (Interactables x : List.of(items, neighbourItems)) {
				for (Interactables.Item it : x.items()) {
					if (it.kind == Interactables.Kind.LEVER || it.kind == Interactables.Kind.PUSH) {
						grabbed.add(it.owner);
					}
				}
			}
		}
		return navWorld.collideBodies(c, r, grabbed, exclude); // 이 레벨 + 다른 슬롯 레벨 문·발판 (이웃 Animator 자세)
	}

	/**
	 * 스크립트 Vis Attr11_0 → 충돌 켜고 끄기. 꺼진 오브젝트는 R2 잡기 대상에서도 빠진다 ({@link Interactables.Item#noCollision}: 원작 잡기
	 * 질의 0x1C3F18 은 충돌 세계 질의).
	 */
	public void setSolid(List<String> owners, boolean on) {
		for (String o : owners) {
			navWorld.platforms().setSolid(o, on);
			Interactables.Item it = items.byOwner(o);
			if (it != null) {
				it.noCollision = !on;
			}
		}
	}

	/** 리스폰·Goto 뒤 내비게이터 상태 초기화. */
	public void reset() {
		navigator.reset();
	}
}
