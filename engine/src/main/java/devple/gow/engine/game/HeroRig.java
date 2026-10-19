package devple.gow.engine.game;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.asset.AnimatedModel;
import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.combat.CombatData;
import devple.gow.engine.combat.CombatOwner;
import devple.gow.engine.combat.Combatant;
import devple.gow.engine.combat.DamageRules;
import devple.gow.engine.combat.GodMode;
import devple.gow.engine.combat.Hittable;
import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Shader;
import devple.gow.engine.render.Texture;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.wad.WadFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 크레토스 한 벌: R_HERO&lt;costume&gt; 모델·스켈레톤·애니 + 이동 컨트롤러 + 전투(Combatant) + 성장 상태 + 신들의 분노 + 히트 카운터.
 * 게임 씬과 테스트 씬이 공유한다.
 */
public final class HeroRig implements AutoCloseable {

	public final WadAssets assets;
	public final Tweaks tweaks = new Tweaks();
	public final PlayerState player;
	public final HeroController ctl;
	public final Combatant combat;
	public final GodMode godMode;
	public final HitCounter hitCounter;
	public final DamageRules rules;
	public final devple.gow.engine.combat.Magic magic;
	public int difficulty = 1;
	private final AnimatedModel model;
	private final Shader shader;
	private int lastHits;
	private final Hittable hittable;

	public HeroRig(Path rom, WadFile permk, int costume) throws IOException {
		this(rom, permk, costume, null);
	}

	/** @param existing 레벨 전환 시 이어받을 진행 상태 (null 이면 새 게임) */
	public HeroRig(Path rom, WadFile permk, int costume, PlayerState existing) throws IOException {
		assets = new WadAssets(rom.resolve("R_HERO" + costume + ".WAD"));
		tweaks.load(permk);
		tweaks.load(assets.wad());
		player = existing != null ? existing : new PlayerState(GameRules.load(permk));
		player.costume = costume;
		Skeleton sk = Skeleton.loadAll(assets.wad()).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(assets.wad(), "ANM_hero");
		shader = new Shader("skinned.vert", "basic.frag");
		model = new AnimatedModel(assets, assets.requireModel("MDL_hero"), sk, shader);
		Animator animator = new Animator(sk, set, tweaks, "/Animation/goHero/");
		ctl = new HeroController(animator);
		godMode = new GodMode(tweaks);
		hitCounter = new HitCounter(tweaks);
		rules = new DamageRules(tweaks);
		magic = new devple.gow.engine.combat.Magic(tweaks, player);
		combat = new Combatant(new CombatData(permk, CombatData.heroFiles(), set), animator, owner());
		combat.setDamageScale(() -> rules.playerScale(difficulty, player.costume, bladeLevel(), godMode.active()));
		hittable = new Hittable() {
			public Vector3f position() {
				return ctl.position;
			}

			public float hp() {
				return player.hp;
			}

			public int classId() {
				return -1;
			}

			public int stanceTag() {
				return combat.stanceTag();
			}

			public boolean invulnerable() {
				return player.hp <= 0 || combat.invulnerable();
			}

			public Result receive(CombatFile.Attack a, Combatant attacker, float dmg, Vector3f knock) {
				return combat.takeHit(a, attacker, dmg);
			}
		};
	}

	public int bladeLevel() {
		return player.level.get(Weapon.BLADES);
	}

	/** 적이 영웅을 때릴 때 곱할 배율 (난이도·코스튬 AI 배율, 신 모드 감소). */
	public float incomingScale() {
		return rules.incomingScale(difficulty, player.costume, bladeLevel(), godMode.active());
	}

	public Hittable hittable() {
		return hittable;
	}

	private CombatOwner owner() {
		return new CombatOwner() {
			public float hp() {
				return player.hp;
			}

			public boolean applyDamage(float d) {
				player.hp = Math.max(0, player.hp - d);
				return false;
			}

			public boolean onGround() {
				return ctl.grounded;
			}

			public int bladeLevel() {
				return HeroRig.this.bladeLevel();
			}

			public boolean godMode() {
				return godMode.active();
			}

			public boolean artemisEquipped() {
				return false;
			}

			public int selectedMagic() {
				return player.selectedMagic != null ? player.selectedMagic.itemId : 0;
			}

			public int classId() {
				return -1;
			}

			public boolean hasMagic(int id) {
				return magic.has(id);
			}

			public void useMagic(int id) {
				magic.use(id);
			}

			public Vector3f position() {
				return ctl.position;
			}

			public float yaw() {
				return ctl.yaw;
			}
		};
	}

	/** 한 틱: 전투 → (기술 중이면 루트 모션, 아니면 이동) → 신 모드 → 히트 카운터. */
	public void update(Input in, float camYaw, float dt, Vector3f aimTarget) {
		combat.update(in, dt);
		if (!combat.active() && in.pressed(PadButton.CROSS)) {
			ctl.jump(Math.hypot(in.leftX(), in.leftY()) > 0.3);
		}
		if (combat.active()) {
			ctl.updateCombat(combat, in, camYaw, dt, aimTarget);
		} else {
			ctl.update(in, camYaw, dt);
		}
		boolean godPress = (in.pressed(PadButton.L3) && in.held(PadButton.R3)) || (in.pressed(PadButton.R3) && in.held(PadButton.L3));
		godMode.update(combat, godPress, bladeLevel(), player.hp, dt);
		player.rage = combat.godMeter;
		while (lastHits < combat.hitCounter) {
			lastHits++;
			HitCounter.Reward r = hitCounter.hit();
			if (r != null) {
				player.addRedOrbs(r.redOrbs());
			}
		}
		hitCounter.update(dt);
		magic.update(combat, dt);
	}

	public void draw(Camera cam, float aspect, float alpha, Texture white) {
		model.draw(cam, aspect, ctl.world(new Matrix4f(), alpha), ctl.animator().pose(), white);
	}

	@Override
	public void close() {
		model.close();
		shader.close();
		assets.close();
	}
}
