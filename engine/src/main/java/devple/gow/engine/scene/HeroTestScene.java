package devple.gow.engine.scene;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.asset.AnimatedModel;
import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.combat.CombatData;
import devple.gow.engine.combat.CombatOwner;
import devple.gow.engine.combat.Combatant;
import devple.gow.engine.combat.DamageRules;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.combat.Hittable;
import devple.gow.engine.game.GameRules;
import devple.gow.engine.game.PlayerState;
import devple.gow.engine.ui.HudBinding;
import devple.gow.engine.ui.UiLayer;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.ui.MessageTable;
import devple.gow.engine.combat.TrainingDummy;
import devple.gow.engine.core.Engine;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Mesh;
import devple.gow.engine.render.MeshBuilder;
import devple.gow.engine.render.Shader;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.wad.WadFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL41C.GL_LINES;
import static org.lwjgl.opengl.GL41C.GL_TRIANGLES;

/**
 * 평지에서 크레토스 조작 + 전투 (연습용 표적). 레벨 연결 전 테스트용.
 * 조작: WASD 이동, J □ / I △ / L ○ / K × / Q L1(방어) / E R1, 방향키 = 오른쪽 스틱(회피) — 좌우 방향키는 카메라도 회전.
 */
public final class HeroTestScene implements Scene {

	public enum Auto { NONE, RUN, COMBO }

	private final Path rom;
	private final Auto auto;
	private final int bladeLevel;
	private final Camera cam = new Camera();
	private final Matrix4f world = new Matrix4f();
	private WadAssets hero;
	private Shader skinned;
	private AnimatedModel model;
	private HeroController ctl;
	private Combatant combat;
	private TrainingDummy dummy;
	private WadAssets minAssets;
	private Shader skinnedMin;
	private AnimatedModel minModel;
	private EnemyFighter enemy;
	private PlayerState player;
	private UiLayer hud;
	private final HudBinding hudBinding = new HudBinding();
	private Hittable heroHittable;
	private devple.gow.engine.combat.GodMode godMode;
	private devple.gow.engine.game.HitCounter hitCounter;
	private Tweaks orbTweaks;
	private boolean dropped;
	private int lastHits;
	private Mesh grid;
	private Mesh box;
	private float camYaw;
	private float autoTime;
	private ScriptedInput scripted;

	private String enemyWad = "R_MIN";

	public HeroTestScene(Path rom, Auto auto, int bladeLevel, String enemyWad) {
		this(rom, auto, bladeLevel);
		if (enemyWad != null) {
			this.enemyWad = enemyWad.toUpperCase();
		}
	}

	public HeroTestScene(Path rom, Auto auto, int bladeLevel) {
		this.rom = rom;
		this.auto = auto;
		this.bladeLevel = bladeLevel;
	}

	@Override
	public void enter(Engine e) {
		WadFile perm;
		try {
			hero = new WadAssets(rom.resolve("R_HERO0.WAD"));
			perm = WadFile.load(rom.resolve("R_PERMK.WAD"));
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(hero.wad());
		Skeleton sk = Skeleton.loadAll(hero.wad()).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(hero.wad(), "ANM_hero");
		skinned = new Shader("skinned.vert", "basic.frag");
		model = new AnimatedModel(hero, hero.requireModel("MDL_hero"), sk, skinned);
		Animator animator = new Animator(sk, set, tw, "/Animation/goHero/");
		ctl = new HeroController(animator);
		CombatData data = new CombatData(perm, CombatData.heroFiles(), set);
		player = new PlayerState(GameRules.load(perm));
		godMode = new devple.gow.engine.combat.GodMode(tw);
		hitCounter = new devple.gow.engine.game.HitCounter(tw);
		player.level.put(devple.gow.engine.game.Weapon.BLADES, bladeLevel);
		combat = new Combatant(data, animator, owner());
		DamageRules rules = new DamageRules(tw);
		combat.setDamageScale(() -> rules.playerScale(1, 0, bladeLevel, godMode.active()));
		heroHittable = new Hittable() {
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
				Result r = combat.takeHit(a, attacker, dmg);
				System.out.printf("  크레토스 피격 %.1f → %s (HP %.0f)%n", dmg, r, player.hp);
				return r;
			}
		};
		try {
			var kind = devple.gow.engine.combat.EnemyFactory.load(rom.resolve(enemyWad + ".WAD"), perm, -1);
			minAssets = kind.assets();
			skinnedMin = new Shader("skinned.vert", "basic.frag");
			minModel = kind.buildModel(skinnedMin);
			enemy = kind.spawn();
			System.out.printf("적: %s AI1 %03d HP %.0f 전투 파일 %s%n", enemyWad, kind.ai1(), enemy.maxHp(), kind.combatFiles());
			enemy.pos.set(0, 0, -120);
			enemy.setFoe(heroHittable);
			enemy.setIncomingScale(() -> rules.incomingScale(1, 0, bladeLevel, godMode.active()));
			orbTweaks = new Tweaks();
			orbTweaks.load(perm);
			orbTweaks.load(minAssets.wad());
			java.nio.file.Path lvl = rom.resolve("PAND03A.WAD"); // Orb 템플릿 정의가 있는 레벨
			if (java.nio.file.Files.exists(lvl)) {
				orbTweaks.load(WadFile.load(lvl));
			}
			enemy.combat().onLog(s -> System.out.println("[" + enemyWad + "] " + s));
			hud = new UiLayer(new WadAssets(rom.resolve("R_PERMK.WAD")), true, "FLP_HUDK", MessageTable.fromWad(perm).orElse(null), new devple.gow.engine.ui.UiVariables());
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		combat.onLog(System.out::println);
		combat.debug = auto == Auto.COMBO;
		System.out.printf("전투 데이터: 기술 %d개, 루트 액션 %d개, 블레이드 레벨 %d%n", data.moveCount(),
				data.rootActions().size(), bladeLevel);
		dummy = new TrainingDummy(new Vector3f(0, 0, -50), 90, 10);
		combat.setTarget(enemy != null ? enemy : dummy);
		MeshBuilder g = new MeshBuilder();
		for (int i = -40; i <= 40; i++) {
			float c = i == 0 ? 0.6f : 0.25f;
			g.line(i * 20, 0, -800, i * 20, 0, 800, c, c, c);
			g.line(-800, 0, i * 20, 800, 0, i * 20, c, c, c);
		}
		grid = g.build(GL_LINES);
		box = new MeshBuilder().box(0, 20, 0, 8, 20, 8, 1, 1, 1).build(GL_TRIANGLES);
		if (auto == Auto.COMBO) {
			// □ 다섯 번 (COMBO3A→3E 기대), 이후 △ (COMBO6A)
			List<ScriptedInput.Step> st = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				st.add(new ScriptedInput.Step(0.2f + i * 0.3f, 0.05f, PadButton.SQUARE));
			}
			st.add(new ScriptedInput.Step(4.0f, 0.05f, PadButton.TRIANGLE));
			scripted = new ScriptedInput(e.window().handle(), st);
		}
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
				return true;
			}

			public int bladeLevel() {
				return bladeLevel;
			}

			public boolean godMode() {
				return godMode != null && godMode.active();
			}

			public boolean artemisEquipped() {
				return false;
			}

			public int selectedMagic() {
				return 0;
			}

			public int classId() {
				return -1;
			}

			public Vector3f position() {
				return ctl.position;
			}

			public float yaw() {
				return ctl.yaw;
			}
		};
	}

	@Override
	public void update(Engine e, float dt) {
		Input in = e.input();
		if (scripted != null) {
			scripted.advance(dt);
			in = scripted;
		}
		camYaw -= in.rightX() * dt * 2;
		combat.update(in, dt);
		boolean godPress = (in.pressed(PadButton.L3) && in.held(PadButton.R3)) || (in.pressed(PadButton.R3) && in.held(PadButton.L3));
		if (godMode.update(combat, godPress, bladeLevel, player.hp, dt)) {
			System.out.println("신들의 분노 발동");
		}
		player.rage = combat.godMeter;
		while (lastHits < combat.hitCounter) {
			lastHits++;
			var r = hitCounter.hit();
			if (r != null) {
				player.addRedOrbs(r.redOrbs());
				System.out.printf("%d 히트 보너스: 붉은 오브 %d (메시지 %d)%n", r.hits(), r.redOrbs(), r.messageId());
			}
		}
		hitCounter.update(dt);
		if (combat.active()) {
			Vector3f aim = enemy != null ? (enemy.dead() ? null : enemy.pos) : (dummy.hp > 0 ? dummy.pos : null);
			ctl.updateCombat(combat, in, camYaw, dt, aim);
		} else if (auto == Auto.RUN && (autoTime += dt) < 0.4f) {
			ctl.update(new Input(e.window().handle()) {
				@Override
				public float leftX() {
					return 0.7f;
				}

				@Override
				public float leftY() {
					return 0.7f;
				}
			}, camYaw, dt);
		} else {
			ctl.update(in, camYaw, dt);
		}
		if (enemy != null) {
			enemy.update(dt);
			if (enemy.dead() && !dropped) {
				dropped = true;
				String emitter = enemyWad.substring(2) + " Death";
				for (var o : devple.gow.engine.game.OrbDrops.roll(orbTweaks, emitter, new java.util.Random(), player.hp, player.mp)) {
					player.pickupOrb(o.type(), o.points());
				}
				System.out.println(enemyWad + " 처치 → 붉은 오브 " + player.redOrbs);
			}
			ctl.separate(enemy.pos, 22);
		} else {
			dummy.update(dt);
			ctl.separate(dummy.pos, 10);
		}
		if (hud != null) {
			hudBinding.update(player, hud.variables());
			hud.update(e);
		}
	}

	@Override
	public void render(Engine e, float alpha) {
		ctl.world(world, alpha);
		Vector3f p = world.getTranslation(new Vector3f());
		cam.near = 1;
		cam.far = 8000;
		cam.target.set(p.x, p.y + 25, p.z - 20);
		cam.eye.set(p.x + 90, p.y + 70, p.z + 90);
		e.renderer().begin3d(cam);
		e.renderer().draw(grid, new Matrix4f(), false);
		if (enemy == null) {
			float k = dummy.hp / dummy.maxHp;
			e.renderer().basic().set("uTint", dummy.flash > 0 ? new Vector4f(1, 1, 1, 1) : new Vector4f(0.8f, 0.25f + 0.5f * k, 0.2f, 1));
			e.renderer().draw(box, new Matrix4f().translation(dummy.pos), true);
			e.renderer().basic().set("uTint", new Vector4f(1, 1, 1, 1));
		} else if (enemy.deadTime() < 3f) {
			minModel.draw(cam, e.window().aspect(), enemy.world(new Matrix4f(), alpha), enemy.animator().pose(), e.renderer().white());
		}
		model.draw(cam, e.window().aspect(), world, ctl.animator().pose(), e.renderer().white());
		if (hud != null) {
			hud.render(e);
		}
	}

	@Override
	public void exit(Engine e) {
		model.close();
		skinned.close();
		grid.close();
		box.close();
		hero.close();
		if (minModel != null) {
			minModel.close();
			skinnedMin.close();
			minAssets.close();
		}
		if (hud != null) {
			hud.close();
		}
	}
}
