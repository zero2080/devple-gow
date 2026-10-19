package devple.gow.engine.boss;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.combat.CombatData;
import devple.gow.engine.combat.CombatOwner;
import devple.gow.engine.combat.Combatant;
import devple.gow.engine.combat.EnemyFactory;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.combat.Hittable;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.script.ScriptActor;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** 헤드리스 보스전: 원본 레벨 스크립트(ScriptWorld) + 크레토스(Combatant) + BossFight. GL 없음. */
final class BossRig {

	static final float DT = 1 / 60f;
	static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	/** 테스트가 직접 누르는 패드. */
	static final class Pad extends Input {
		final Set<PadButton> down = EnumSet.noneOf(PadButton.class), prev = EnumSet.noneOf(PadButton.class);
		float lx, ly;

		Pad() {
			super(0);
		}

		void next() {
			prev.clear();
			prev.addAll(down);
			down.clear();
		}

		@Override
		public void poll() {
		}

		@Override
		public boolean held(PadButton b) {
			return down.contains(b);
		}

		@Override
		public boolean pressed(PadButton b) {
			return down.contains(b) && !prev.contains(b);
		}

		@Override
		public boolean released(PadButton b) {
			return !down.contains(b) && prev.contains(b);
		}

		@Override
		public float leftX() {
			return lx;
		}

		@Override
		public float leftY() {
			return ly;
		}
	}

	final WadFile perm;
	final WadFile levelWad;
	final AnimationSet set;
	final CombatData cd;
	final HeroController ctl;
	final Combatant hero;
	float heroHp = 200;
	final Hittable heroHit;
	final ScriptHost host = new ScriptHost();
	final ScriptWorld world;
	/** 레벨 카메라 (GameScene 과 같은 연결: SwitchCamera / Internal 0x9 / Vis Attr11_1 / 보스 요청). */
	final devple.gow.engine.level.CameraRig cam;
	final List<String> cams = new ArrayList<>();
	final CombatScriptBridge bridge;
	final BossFight fight;
	final List<EnemyFighter> enemies = new ArrayList<>();
	final List<String> log = new ArrayList<>();
	final List<Integer> heroMoves = new ArrayList<>(), bossMoves = new ArrayList<>();
	final Pad pad = new Pad();
	final BossArena arena;
	/** 일반 잡기 (메두사 여왕 MAIMEWGRAB 등, 보스 동기가 맡지 않는 적) — GameScene 과 같은 순서. */
	final devple.gow.engine.combat.GrabSync grab;
	/** 미니게임 자동 입력 (true 면 프롬프트 버튼/회전·연타를 스스로 입력). */
	boolean autoMinigame = true;
	private Object autoPrompt;
	private int autoStep, frame;
	String movie;
	String warp;
	float time;

	BossRig(String level) throws Exception {
		Assumptions.assumeTrue(Files.exists(ROM.resolve(level + ".WAD")), "gow-rom 없음");
		devple.gow.engine.combat.AiTargets.clear(); // 정적 슬롯이 이전 테스트의 영웅/적을 붙잡지 않게
		perm = WadFile.load(ROM.resolve("R_PERMK.WAD"));
		WadFile heroWad = WadFile.load(ROM.resolve("R_HERO0.WAD"));
		levelWad = WadFile.load(ROM.resolve(level + ".WAD"));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(heroWad);
		Skeleton sk = Skeleton.loadAll(heroWad).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		set = AnimationSet.load(heroWad, "ANM_hero");
		set.attachAll(levelWad); // 레벨별 영웅 애니 (ANM_hero_Athn01E: BHAboss* 영웅 쪽)
		Animator an = new Animator(sk, set, tw, "/Animation/goHero/");
		ctl = new HeroController(an);
		CombatOwner owner = new CombatOwner() {
			public float hp() { return heroHp; }
			public boolean applyDamage(float d) { heroHp = Math.max(0, heroHp - d); return false; }
			public boolean onGround() { return true; }
			public int bladeLevel() { return 1; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public int selectedMagic() { return 0; }
			public int classId() { return 0; }
			public Vector3f position() { return ctl.position; }
			public float yaw() { return ctl.yaw; }
		};
		cd = new CombatData(perm, CombatData.heroFiles(), set);
		hero = new Combatant(cd, an, owner);
		hero.onLog(s -> {
			if (s.startsWith("기술 ")) {
				heroMoves.add(Integer.parseInt(s.split(" ")[1]));
			}
		});
		heroHit = new Hittable() {
			public Vector3f position() { return ctl.position; }
			public float hp() { return heroHp; }
			public int classId() { return 0; }
			public int stanceTag() { return hero.stanceTag(); }
			public boolean invulnerable() { return heroHp <= 0 || hero.invulnerable(); }
			public Combatant combatant() { return hero; }
			public Result receive(CombatFile.Attack a, Combatant at, float d, Vector3f kn) { return hero.takeHit(a, at, d); }
		};
		fight = BossDirector.create(level, ROM, perm);
		LevelData data = new LevelData(levelWad);
		cam = new devple.gow.engine.level.CameraRig(data);
		world = new ScriptWorld(data, host, listener());
		cam.onSwitch(n -> {
			cams.add(n);
			world.postEvent(1037, n, 0, 0, null);
		});
		bridge = new CombatScriptBridge(world);
		arena = new BossArena() {
			public HeroController heroCtl() { return ctl; }
			public Combatant heroCombat() { return hero; }
			public Hittable hero() { return heroHit; }
			public void damageHero(float a) { heroHp = Math.max(0, heroHp - a); }
			public ScriptWorld world() { return world; }
			public CombatScriptBridge bridge() { return bridge; }
			public void requestCamera(String name, Vector3f... targets) { cam.request(name, targets); }
		};
		grab = new devple.gow.engine.combat.GrabSync(ctl, hero);
		for (EnemyFactory.Kind k : fight.kinds().values()) {
			set.attachAll(k.assets().wad());
		}
		for (Path p : fight.heroAnimWads()) {
			set.attachAll(WadFile.load(p));
		}
		cd.reindexAnimations();
	}

	private ScriptListener listener() {
		return new ScriptListener() {
			@Override
			public void spawnEnemy(SpawnRequest r) {
				EnemyFactory.Kind k = fight.kindFor(r.templateId());
				log.add("spawn " + r.templateId() + " " + (k != null));
				if (k == null) {
					return;
				}
				EnemyFighter f = k.spawn(r.templateId());
				float[] w = r.world();
				f.pos.set(w[12], w[13], w[14]);
				f.prevPos.set(f.pos);
				f.yaw = (float) Math.atan2(w[8], w[10]);
				f.setFoe(heroHit);
				enemies.add(f);
				fight.onSpawn(f, r.actor(), r.templateId(), arena);
				if (f == fight.boss()) {
					f.combat().onLog(s -> {
						if (s.startsWith("기술 ")) {
							bossMoves.add(Integer.parseInt(s.split(" ")[1]));
						}
					});
				}
			}

			@Override
			public void objectEvent(ObjectEvent e) {
				fight.objectEvent(e);
			}

			@Override
			public void message(int msgId, float seconds) {
				log.add("message " + msgId);
			}

			@Override
			public void movie(String name, int op, int arg) {
				if (op == 0) {
					movie = name;
					log.add("movie " + name);
				}
			}

			@Override
			public void level(LevelRequest r) {
				log.add("level " + r.op() + " " + r.wad() + " " + r.entry());
				if (r.op() == LevelOp.GOTO && r.pos() != null) {
					ctl.position.set(r.pos()[0], r.pos()[1], r.pos()[2]);
					ctl.prevPosition.set(ctl.position);
				}
				if (r.op() == LevelOp.WARP || r.op() == LevelOp.LOAD_FOR_WARP) {
					warp = r.op() + " " + r.wad();
				}
			}

			@Override
			public void camera(String name) {
				log.add(String.format("cam %s @%.2f", name, time));
				cam.request(name);
			}

			@Override
			public void internal(int func, List<Object> args) {
				if (func != 0x9) {
					log.add(String.format("internal 0x%x %s @%.2f", func, args, time));
				}
				if (func == 0x9 && !args.isEmpty()) {
					Object a = args.get(0);
					log.add(String.format("cam query %s @%.2f", a, time));
					cam.setVolumeQuery(a instanceof Number n ? n.floatValue() != 0 : !(a instanceof Boolean b) || b);
				}
			}

			@Override
			public void vis(ScriptEntity source, List<String> objects, int[] values) {
				if (values.length > 4 && values[4] != 0) {
					objects.forEach(o -> cam.setOwnerVolumes(o, values[4] == 1));
				}
			}

			@Override
			public void debug(ScriptEntity source, String text) {
				log.add("dbg " + text);
			}
		};
	}

	/** 컷신 끝 (GameScene 은 MovieScene 종료 시 501, 인자0 = 이름). */
	void movieDone() {
		world.postEvent(501, movie, 0, 0, world.player());
		movie = null;
	}

	/** 미니게임 자동 입력: 타이밍 버튼 = 그 버튼, 회전 = 사분면 순서대로 4프레임마다, 연타 = ○ 0.1초마다. */
	private void auto() {
		var t = fight.sync() != null && fight.sync().timed() != null ? fight.sync().timed() : grab.timed();
		if (t != null) {
			if (t != autoPrompt) {
				autoPrompt = t;
				autoStep = 0;
			}
			if (t.button() != null) {
				pad.down.add(t.button());
			} else {
				int[] seq = t.sequence();
				int q = seq[Math.min(seq.length - 1, autoStep / 4)];
				autoStep++;
				pad.lx = q == 2 ? 1 : q == 4 ? -1 : 0;
				pad.ly = q == 1 ? 1 : q == 3 ? -1 : 0;
			}
			return;
		}
		pad.lx = pad.ly = 0;
		var q = fight.sync() != null && fight.sync().qte() != null ? fight.sync().qte() : grab.qte();
		if (q != null && q.state() == devple.gow.engine.combat.RepeatPressQte.State.RUNNING && frame % 6 == 0) {
			pad.down.add(q.button());
		}
	}

	/** 한 틱: 영웅 전투·이동 → 적 → 잡기 → 보스 컨트롤러 → 스크립트 (GameScene.update 순서). */
	void tick() {
		time += DT;
		frame++;
		if (autoMinigame) {
			auto();
		}
		EnemyFighter b = fight.boss();
		if (!grab.active()) {
			hero.setTarget(b);
			hero.update(pad, DT);
			if (hero.active()) {
				ctl.updateCombat(hero, pad, 0, DT, b != null ? b.pos : null);
			} else {
				ctl.update(pad, 0, DT);
			}
		}
		for (EnemyFighter f : enemies) {
			float y = f.pos.y;
			f.update(DT);
			f.pos.y = y;
		}
		if (b != null && !fight.claimsGrab(b)) {
			boolean was = grab.active();
			grab.update(pad, b, fight.kindFor(b.ai1()).tweaks(), DT);
			if (was && !grab.active() && grab.executionSucceeded()) {
				b.hp = 0;
			}
		}
		fight.update(pad, DT);
		cam.update(DT, ctl.position, ctl.yaw, false);
		world.tick(DT, ctl.position.x, ctl.position.y, ctl.position.z);
		pad.next();
	}

	void run(float seconds) {
		for (float t = 0; t < seconds; t += DT) {
			tick();
		}
	}

	/** 엔티티 볼륨 안의 한 점 (월드). 없으면 null. */
	float[] inside(String entity) {
		ScriptEntity e = world.find(entity);
		if (e == null || e.volume == null) {
			return null;
		}
		LevelData.PlacedVolume v = e.volume;
		float[] w = new float[3];
		float[] s = v.hull().sphere;
		for (int i = 0; i <= 20; i++) {
			for (int j = 0; j <= 20; j++) {
				for (int k = 0; k <= 20; k++) {
					float x = s[0] + s[3] * (i / 10f - 1), y = s[1] + s[3] * (j / 10f - 1), z = s[2] + s[3] * (k / 10f - 1);
					devple.gow.tools.anim.Mat4.transformPoint(v.world(), x, y, z, w);
					if (v.contains(w[0], w[1], w[2], e.data.baseId())) {
						return w;
					}
				}
			}
		}
		return null;
	}

	/** 엔티티 볼륨(영역) 안이면서 다른 엔티티들 영역 밖인 점 중 높이가 preferY 에 가장 가까운 점. */
	float[] exclusive(String entity, float preferY, String... others) {
		ScriptEntity e = world.find(entity);
		if (e == null || e.volume == null) {
			return null;
		}
		List<ScriptEntity> os = new ArrayList<>();
		for (String o : others) {
			ScriptEntity x = world.find(o);
			if (x != null && x.volume != null) {
				os.add(x);
			}
		}
		LevelData.PlacedVolume v = e.volume;
		float[] s = v.hull().sphere, w = new float[3], best = null;
		for (int i = 0; i <= 30; i++) {
			for (int j = 0; j <= 30; j++) {
				for (int k = 0; k <= 30; k++) {
					float x = s[0] + s[3] * (i / 15f - 1), y = s[1] + s[3] * (j / 15f - 1), z = s[2] + s[3] * (k / 15f - 1);
					devple.gow.tools.anim.Mat4.transformPoint(v.world(), x, y, z, w);
					if (!v.contains(w[0], w[1], w[2], e.data.baseId())) {
						continue;
					}
					boolean clash = false;
					for (ScriptEntity o : os) {
						clash |= o.volume.contains(w[0], w[1], w[2], o.data.baseId());
					}
					if (!clash && (best == null || Math.abs(w[1] - preferY) < Math.abs(best[1] - preferY))) {
						best = w.clone();
					}
				}
			}
		}
		return best;
	}

	/** 히드라 갑판 구역 n (1..4) 으로 영웅 이동 (바깥 구역 5/6 과 겹치지 않는 갑판 높이 점). */
	boolean hydraZone(int n) {
		List<String> others = new ArrayList<>();
		for (int z = 1; z <= 4; z++) {
			if (z != n) {
				others.add("bossEnt-bossZoneEntry" + z);
			}
		}
		others.add("bossEnt-bossOuterZoneEntry1");
		others.add("bossEnt-bossOuterZoneEntry2");
		float[] p = exclusive("bossEnt-bossZoneEntry" + n, 832, others.toArray(String[]::new));
		if (p == null) {
			return false;
		}
		ctl.position.set(p[0], p[1], p[2]);
		ctl.prevPosition.set(ctl.position);
		tick();
		return true;
	}

	/** 영웅을 엔티티 볼륨 안으로 옮기고 한 틱 (진입 센서). */
	boolean enter(String entity) {
		float[] p = inside(entity);
		if (p == null) {
			return false;
		}
		ctl.position.set(p[0], p[1], p[2]);
		ctl.prevPosition.set(ctl.position);
		tick();
		return true;
	}

	Object levelData(int id) {
		return host.levelData().get(id);
	}
}
