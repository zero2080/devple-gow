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

	/** 게임 씬 쪽 동작. */
	public interface Host {
		void heroDied();

		/** 이웃 레벨 스크립트의 레벨 요청 (LoadCheck → 이음 전환). */
		void neighbourLevel(ScriptListener.LevelRequest r);

		/** 이웃 레벨 엔티티로 순간 이동 (Goto). */
		void teleport(float[] pos, float yaw);

		void message(int id, float seconds);

		void orbs(String emitter, Vector3f at);
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
	private final ScriptHost mainHost;

	public SceneNav(LevelData level, LevelCollision col, HeroRig hero, ScriptWorld world, ScriptHost mainHost, Tweaks levelTweaks, Host host) {
		this.level = level;
		this.world = world;
		this.host = host;
		this.mainHost = mainHost;
		this.tweaks = levelTweaks;
		navWorld = new NavWorld(col);
		navWorld.ropes().addAll(NavRope.of(level));
		navWorld.setPlatforms(Platforms.of(level, animatorOwners(world)));
		items = Interactables.of(level, levelTweaks);
		navigator = new HeroNavigator(hero.ctl, navWorld, new NavAnims(hero.tweaks));
		navigator.setItems(items);
		navigator.setListener(new HeroNavigator.Listener() {
			@Override
			public void taskProgress(Interactables.Item it, int percent) {
				world.postEvent(EVENT_TASK, percent, it.id, 0, world.player());
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
			public void pushMoved(Interactables.Item it, Vector3f d) {
				world.moveOwnerBy(it.owner, d.x, d.y, d.z);
			}

			@Override
			public void interactDone(Interactables.Item it) {
				System.out.println("상황 동작 완료: " + it);
				if (it.kind == Interactables.Kind.CHEST) {
					host.orbs(chestEmitter(it.label), it.pos);
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
		if (data == null) {
			return;
		}
		try {
			ScriptWorld nw = new ScriptWorld(data, ScriptHost.neighbour(mainHost), neighbourListener);
			nw.start();
			Vector3f p = world.player() != null ? new Vector3f(world.player().pos[0], world.player().pos[1], world.player().pos[2]) : new Vector3f();
			nw.placePlayer(p.x, p.y, p.z, true); // 이미 서 있는 볼륨은 "안" 으로 (들어올 때 발동하지 않음)
			neighbour = nw;
			System.out.println("이웃 레벨 스크립트: " + name + " (엔티티 " + nw.entities().size() + ")");
		} catch (RuntimeException ex) {
			System.out.println("이웃 레벨 스크립트 실패 " + name + ": " + ex);
		}
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

	/** Animator 엔티티 → 발판 애니. */
	public void animate(List<String> objects, String anim) {
		for (String o : objects) {
			if (navWorld.platforms().play(o, anim)) {
				System.out.println("오브젝트 애니: " + o + " " + anim);
			}
		}
	}

	/** Creator "chest" → R1 상자. */
	public void create(ScriptEntity source) {
		Object name = world.slot(source, "Name0");
		if ("chest".equals(name) && source.owner != null) {
			float[] gp = source.owner.instance().pos();
			int tpl = num(world.slot(source, "unk5"));
			int task = num(world.slot(source, "unk6"));
			items.addChest(source.owner.instance().name(), new Vector3f(gp[0], gp[1], gp[2]), tpl, task, tweaks);
		}
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

	/** 한 틱: 발판 애니 → Marker 위치, 이웃 스크립트. */
	public void update(float dt, Vector3f hero) {
		navWorld.platforms().update(dt);
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
			}
		}
	}

	/** 리스폰·Goto 뒤 내비게이터 상태 초기화. */
	public void reset() {
		navigator.reset();
	}
}
