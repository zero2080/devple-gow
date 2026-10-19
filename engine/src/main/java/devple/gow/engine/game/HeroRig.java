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
import devple.gow.engine.combat.HitVolumes;
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
	/** 기어오르기·줄·밀기 등 (38 문서). null 이면 기존 지상/공중 이동만. */
	public devple.gow.engine.nav.HeroNavigator nav;
	private final AnimatedModel model;
	/** 혼돈의 블레이드 (lWeapOH/rWeapOH 부착, 34 문서). 없으면 null. */
	private final devple.gow.engine.asset.BladeModels blades;
	private devple.gow.engine.asset.ArtemisBladeModel artemis;
	private int artemisStageLoaded = -1;
	private final Path rom;
	/** BERSERKENTER 0.22 의 SCRIPT 이벤트 (SCR_EnableBerserkBlade) / BERSERKEXIT 의 SCR_DisableBerserkBlade 해시 (Hash127Upper). */
	private static final int SCR_ENABLE_BERSERK = 0xe9283e7e, SCR_DISABLE_BERSERK = 0x33681849;
	private final Shader shader;
	private int lastHits;
	private final AnimationSet animSet;
	private final CombatData combatData;
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
		animSet = set;
		combatData = new CombatData(permk, CombatData.heroFiles(), set);
		combat = new Combatant(combatData, animator, owner());
		combat.setDamageScale(() -> rules.playerScale(difficulty, player.costume, bladeLevel(), godMode.active(),
				player.artemisEquipped ? Math.min(2, player.level.get(Weapon.ARTEMIS)) : -1));
		// 원작 CDV 히트 볼륨: CDV_hero + 블레이드(R_WPN<costume>_<level> 의 CDV_MAIBlade, 레벨 무관하게 같은 구) — 34 문서
		Path wpn = rom.resolve("R_WPN" + costume + "_" + Math.max(0, Math.min(5, bladeLevel())) + ".WAD");
		combat.setHitVolumes(HitVolumes.forHero(assets.wad(), sk, java.nio.file.Files.exists(wpn) ? WadFile.load(wpn) : null));
		blades = devple.gow.engine.asset.BladeModels.load(rom, costume, bladeLevel(), shader);
		this.rom = rom;
		hittable = new Hittable() {
			public Vector3f position() {
				return ctl.position;
			}

			public int locomotionState() {
				var s = nav != null ? nav.state() : null;
				if (s != null) {
					switch (s) {
						case WALL_CLIMB, LEDGE_HANG, LADDER -> {
							return 1;
						}
						case ROPE, ROPE_TRAVERSE, ZIPLINE -> {
							return 3;
						}
						case AIR -> {
							return 2;
						}
						default -> {
						}
					}
				}
				return ctl.grounded ? 0 : 2;
			}

			public float hp() {
				return player.hp;
			}

			public int classId() {
				return 0;
			}

			public int stanceTag() {
				return combat.stanceTag();
			}

			public boolean invulnerable() {
				return player.hp <= 0 || combat.invulnerable();
			}

			/** 적 AI 결정 트리가 영웅의 방어 자세·현재 기술을 본다 (17 문서 §3.2). */
			public Combatant combatant() {
				return combat;
			}

			public HitVolumes hitVolumes() {
				return combat.hitVolumes();
			}

			public Result receive(CombatFile.Attack a, Combatant attacker, float dmg, Vector3f knock) {
				return combat.takeHit(a, attacker, dmg);
			}
		};
	}

	/**
	 * 적/레벨 WAD 의 외부 애니 그룹(0x10003, 예 R_MIN 의 ANM_MAIMIN = 크레토스의 미노타우로스 처형 동작)을 영웅 세트에 연결.
	 * 원작도 적 리소스가 로드될 때 영웅 애니 세트의 해당 그룹이 채워진다 (05 문서 §외부 그룹).
	 */
	public void attachExternalAnims(WadFile wad) {
		animSet.attachAll(wad);
		combatData.reindexAnimations();
	}

	public int bladeLevel() {
		return player.level.get(Weapon.BLADES);
	}

	/** 적이 영웅을 때릴 때 곱할 배율 (난이도·코스튬 AI 배율, 신 모드 감소). */
	public float incomingScale() {
		return rules.incomingScale(difficulty, player.costume, bladeLevel(), godMode.active());
	}

	/** 스키닝 셰이더 (레벨 안개 유니폼 적용용). */
	public Shader shader() {
		return shader;
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

			/**
	 * 적/레벨 WAD 의 외부 애니 그룹(0x10003, 예 R_MIN 의 ANM_MAIMIN = 크레토스의 미노타우로스 처형 동작)을 영웅 세트에 연결.
	 * 원작도 적 리소스가 로드될 때 영웅 애니 세트의 해당 그룹이 채워진다 (05 문서 §외부 그룹).
	 */
	public void attachExternalAnims(WadFile wad) {
		animSet.attachAll(wad);
		combatData.reindexAnimations();
	}

	public int bladeLevel() {
				return HeroRig.this.bladeLevel();
			}

			public boolean godMode() {
				return godMode.active();
			}

			public boolean artemisEquipped() {
				return player.artemisEquipped;
			}

			public boolean artemisOwned() {
				return player.owned(Weapon.ARTEMIS);
			}

			public int selectedMagic() {
				return player.selectedMagic != null ? player.selectedMagic.itemId : 0;
			}

			/** 영웅의 종류 ID = 0 (적 분기의 "tgt=0" 이 영웅을 가리킴, 예 미노타우로스 A400 MAIMIN01). */
			public int classId() {
				return 0;
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

	/** 한 틱: 전투 → (내비게이션 상태면 내비게이터, 기술 중이면 루트 모션, 아니면 이동) → 신 모드 → 히트 카운터. */
	public void update(Input in, float camYaw, float dt, Vector3f aimTarget) {
		boolean navActive = nav != null && nav.active();
		if (!navActive) {
			combat.update(in, dt);
		}
		if (nav != null && !combat.active()) {
			ctl.beginTick();
			if (nav.update(in, camYaw, dt)) {
				ctl.navTick(dt);
				navActive = true;
			}
		}
		if (navActive) {
			// 매달림·줄·밀기 중에는 전투·점프 입력 없음 [근사: 원작 줄 공격 기술은 미구현]
		} else {
			if (!combat.active() && in.pressed(PadButton.CROSS)) {
				ctl.jump(Math.hypot(in.leftX(), in.leftY()) > 0.3);
			}
			if (combat.active()) {
				ctl.updateCombat(combat, in, camYaw, dt, aimTarget);
			} else {
				ctl.update(in, camYaw, dt);
			}
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
		// 무기 전환 (원작 player.flags 0x20): 분기 데이터가 고른 BERSERKENTER/EXIT 기술의 스크립트 이벤트를 따른다
		if (combat.activeScript(SCR_ENABLE_BERSERK) != null && !player.artemisEquipped) {
			player.artemisEquipped = true;
		} else if (combat.activeScript(SCR_DISABLE_BERSERK) != null && player.artemisEquipped) {
			player.artemisEquipped = false;
		}
		magic.select(in, ctl.position); // 방향키 마법 선택 (원작 sub_001D7E10)
		magic.update(combat, in, dt);
	}

	/** 관절 월드 행렬 (영웅 월드 × 관절, 이펙트 부착용). 없는 관절이면 영웅 원점 + false. */
	public boolean jointWorld(String joint, Matrix4f out) {
		ctl.world(out, 1f);
		var sk = ctl.animator().skeleton();
		int i = devple.gow.engine.fx.MagicFx.joint(sk, joint);
		if (i < 0) {
			return false;
		}
		out.mul(new Matrix4f().set(ctl.animator().pose().world(sk)[i]));
		return true;
	}

	public void draw(Camera cam, float aspect, float alpha, Texture white) {
		Matrix4f w = ctl.world(new Matrix4f(), alpha);
		var pose = ctl.animator().pose();
		model.draw(cam, aspect, w, pose, white);
		var sk = ctl.animator().skeleton();
		if (player.artemisEquipped) {
			int stage = Math.min(2, player.level.get(Weapon.ARTEMIS));
			if (stage != artemisStageLoaded) {
				if (artemis != null) {
					artemis.close();
				}
				try {
					artemis = devple.gow.engine.asset.ArtemisBladeModel.load(rom, stage, shader);
				} catch (IOException ex) {
					artemis = null;
				}
				artemisStageLoaded = stage;
			}
			if (artemis != null) {
				artemis.draw(cam, aspect, w, sk, pose.world(sk), white);
			}
		} else if (blades != null) {
			blades.draw(cam, aspect, w, sk, pose.world(sk), white);
		}
	}

	@Override
	public void close() {
		model.close();
		if (blades != null) {
			blades.close();
		}
		if (artemis != null) {
			artemis.close();
		}
		shader.close();
		assets.close();
	}
}
