package devple.gow.engine.boss;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.combat.CombatData;
import devple.gow.engine.combat.CombatOwner;
import devple.gow.engine.combat.Combatant;
import devple.gow.engine.combat.EnemyFactory;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.combat.GrabSync;
import devple.gow.engine.combat.Hittable;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * GL 없는 보스전 (순회 검증, 38 문서 §10.4): 원본 레벨 스크립트(주어진 {@link ScriptWorld}) + 크레토스 전투(Combatant) + {@link BossFight}
 * 를 GameScene.update 와 같은 순서로 돌린다. 미니게임·마무리는 보스별 데모(autoPlay, --boss-demo 와 같은 경로)가 입력한다.
 * <pre>
 * tick: 영웅 전투·이동 → 적 → 일반 잡기 → 보스 컨트롤러(동기·QTE·데모) → (호출 쪽이 스크립트 tick)
 * 스크립트 → 게임: spawn (보스 리소스 템플릿만 맡음), objectEvent (EvtForce·SCR 등)
 * 게임 → 스크립트: CombatScriptBridge (1035 기술 시작 / 1047 기술 끝 / 1039 피해 …)
 * </pre>
 * 데모가 하는 일 중 "보스 HP 를 마무리 임계 밑으로" 는 플레이어가 싸워서 깎는 것을 대신한다 [근사]. 그 뒤의 잡기 → 연타 → 타이밍 버튼
 * → 마무리 기술 → 1047/1035 → 컷신 → Warp 는 원본 데이터(CombatFile, DTre, ESC) 그대로다.
 */
public final class HeadlessBoss {

	public final BossFight fight;
	public final HeroController ctl;
	public final Combatant hero;
	public final CombatScriptBridge bridge;
	public final List<EnemyFighter> enemies = new ArrayList<>();
	private final Hittable heroHit;
	private final BossArena arena;
	private final GrabSync grab;
	/** 아무것도 누르지 않는 패드 (미니게임은 fight.autoPlay 가 따로 입력). */
	private final AutoPad idle = new AutoPad();
	private float heroHp = 400;

	private HeadlessBoss(BossFight fight, Path rom, WadFile permk, WadFile levelWad, ScriptWorld world) throws IOException {
		this.fight = fight;
		WadFile heroWad = WadFile.load(rom.resolve("R_HERO0.WAD"));
		Tweaks tw = new Tweaks();
		tw.load(permk);
		tw.load(heroWad);
		Skeleton sk = Skeleton.loadAll(heroWad).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(heroWad, "ANM_hero");
		set.attachAll(levelWad);
		Animator an = new Animator(sk, set, tw, "/Animation/goHero/");
		ctl = new HeroController(an);
		CombatOwner owner = new CombatOwner() {
			public float hp() { return heroHp; }
			public boolean applyDamage(float d) { heroHp = Math.max(1, heroHp - d); return false; }
			public boolean onGround() { return true; }
			public int bladeLevel() { return 1; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public int selectedMagic() { return 0; }
			public int classId() { return 0; }
			public Vector3f position() { return ctl.position; }
			public float yaw() { return ctl.yaw; }
		};
		CombatData cd = new CombatData(permk, CombatData.heroFiles(), set);
		hero = new Combatant(cd, an, owner);
		heroHit = new Hittable() {
			public Vector3f position() { return ctl.position; }
			public float hp() { return heroHp; }
			public int classId() { return 0; }
			public int stanceTag() { return hero.stanceTag(); }
			public boolean invulnerable() { return hero.invulnerable(); } // HP 바닥 1: 검증에서 영웅은 죽지 않는다
			public Combatant combatant() { return hero; }
			public Result receive(CombatFile.Attack a, Combatant at, float d, Vector3f kn) { return hero.takeHit(a, at, d); }
		};
		bridge = new CombatScriptBridge(world);
		arena = new BossArena() {
			public HeroController heroCtl() { return ctl; }
			public Combatant heroCombat() { return hero; }
			public Hittable hero() { return heroHit; }
			public void damageHero(float a) { heroHp = Math.max(1, Math.min(400, heroHp - a)); }
			public ScriptWorld world() { return world; }
			public CombatScriptBridge bridge() { return bridge; }
		};
		grab = new GrabSync(ctl, hero);
		for (EnemyFactory.Kind k : fight.kinds().values()) {
			set.attachAll(k.assets().wad());
		}
		for (Path p : fight.heroAnimWads()) {
			set.attachAll(WadFile.load(p));
		}
		cd.reindexAnimations();
		fight.autoPlay = true;
		fight.attach(arena);
	}

	/**
	 * 레벨의 보스전 (전용 연출이 있는 보스만: 히드라·메두사 여왕·아레스 1/3). 없거나 로드 실패면 null.
	 */
	public static HeadlessBoss create(String level, Path rom, WadFile permk, WadFile levelWad, ScriptWorld world) {
		BossFight f = BossDirector.create(level, rom, permk);
		if (f == null || f instanceof BossDirector.ResourceOnlyFight || !f.headless()) {
			return null;
		}
		try {
			return new HeadlessBoss(f, rom, permk, levelWad, world);
		} catch (IOException | RuntimeException ex) {
			System.out.println("헤드리스 보스전 실패 " + level + ": " + ex);
			return null;
		}
	}

	/** 스크립트 SpawnEnemy: 보스 리소스 템플릿이면 만들고 true, 소리 전용 템플릿도 true (만들지 않음). 그 밖은 false (호출 쪽 처리). */
	public boolean spawn(ScriptListener.SpawnRequest r) {
		int tpl = r.templateId();
		if (fight.silent(tpl)) {
			return true;
		}
		EnemyFactory.Kind k = fight.kindFor(tpl);
		if (k == null) {
			return false;
		}
		EnemyFighter f = k.spawn(tpl);
		float[] w = r.world();
		f.pos.set(w[12], w[13], w[14]);
		f.prevPos.set(f.pos);
		f.yaw = (float) Math.atan2(w[8], w[10]);
		f.setFoe(heroHit);
		enemies.add(f);
		fight.onSpawn(f, r.actor(), tpl, arena);
		return true;
	}

	public void objectEvent(ScriptListener.ObjectEvent e) {
		fight.objectEvent(e);
	}

	/** 스크립트 Goto 등으로 영웅이 옮겨짐. */
	public void moveHero(float x, float y, float z) {
		ctl.position.set(x, y, z);
		ctl.prevPosition.set(ctl.position);
	}

	/** 한 틱 (스크립트 tick 은 호출 쪽이 영웅 위치로). */
	public void tick(float dt) {
		EnemyFighter b = fight.boss();
		if (!grab.active()) {
			hero.setTarget(b);
			hero.update(idle, dt);
			if (hero.active()) {
				ctl.updateCombat(hero, idle, 0, dt, b != null ? b.pos : null);
			} else {
				ctl.update(idle, 0, dt);
			}
		}
		for (EnemyFighter f : enemies) {
			float y = f.pos.y;
			f.update(dt);
			f.pos.y = y; // 충돌 없음: 높이 고정
		}
		if (b != null && !fight.claimsGrab(b)) {
			boolean was = grab.active();
			grab.update(fight.minigameInput(idle, grab.timed(), grab.qte()), b, fight.kindFor(b.ai1()).tweaks(), dt);
			if (was && !grab.active() && grab.executionSucceeded()) {
				b.hp = 0;
			}
		}
		fight.update(idle, dt);
	}
}
