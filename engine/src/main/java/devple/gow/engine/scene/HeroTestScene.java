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

	/**
	 * GRAB: 적 HP 를 낮추고 (AI 정지) 처형 설계 관계 자리 ({@link devple.gow.engine.combat.GrabRelation}) 에 둔 뒤 ○ 잡기 → 처형 동기
	 * ({@link devple.gow.engine.combat.GrabSync}). 미니게임 (연타·타이밍·스틱 회전) 은 {@link devple.gow.engine.boss.AutoPad} 가 자동 입력 — 성공 분기 스크린샷용.
	 */
	public enum Auto { NONE, RUN, COMBO, MAGIC, GRAB }

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
	private devple.gow.engine.asset.BladeModels blades;
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
	/** 마법 4종 (35 문서): 연습 씬에서는 전부 보유, 방향키로 선택. */
	private devple.gow.engine.combat.Magic magic;
	private devple.gow.engine.fx.MagicFx magicFx;

	private String enemyWad = "R_MIN";
	private devple.gow.engine.combat.GrabSync grab;
	private final devple.gow.engine.boss.AutoPad autoPad = new devple.gow.engine.boss.AutoPad();
	private Tweaks enemyTweaks;
	private devple.gow.engine.combat.EnemyFactory.Kind enemyKind;
	private AnimationSet heroSet;
	private CombatData heroData;

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
		heroSet = set;
		heroData = data;
		player = new PlayerState(GameRules.load(perm));
		godMode = new devple.gow.engine.combat.GodMode(tw);
		hitCounter = new devple.gow.engine.game.HitCounter(tw);
		player.level.put(devple.gow.engine.game.Weapon.BLADES, bladeLevel);
		for (int id = 3; id <= 6; id++) {
			player.addItem(id, 1);
		}
		magic = new devple.gow.engine.combat.Magic(tw, player);
		hudBinding.castable = magic::has;
		magicFx = new devple.gow.engine.fx.MagicFx(rom);
		magic.setSink(new devple.gow.engine.combat.Magic.Sink() {
			@Override
			public void effect(String name, Vector3f at) {
				magicFx.effect(name, at);
			}

			@Override
			public void concussion(int template, Vector3f at) {
				magicFx.concussion(template, null, at);
			}

			@Override
			public void petrified(Hittable h) {
				System.out.println("석화: " + h);
			}
		});
		combat = new Combatant(data, animator, owner());
		magicFx.bindHero(combat, (j, out) -> {
			ctl.world(out, 1f);
			int i = devple.gow.engine.fx.MagicFx.joint(sk, j);
			if (i < 0) {
				return false;
			}
			out.mul(new org.joml.Matrix4f().set(ctl.animator().pose().world(sk)[i]));
			return true;
		});
		DamageRules rules = new DamageRules(tw);
		combat.setDamageScale(() -> rules.playerScale(1, 0, bladeLevel, godMode.active()));
		try {
			// 원작 CDV 히트박스 + 블레이드 모델 (34 문서)
			Path wpn = rom.resolve("R_WPN0_" + Math.max(0, Math.min(5, bladeLevel)) + ".WAD");
			combat.setHitVolumes(devple.gow.engine.combat.HitVolumes.forHero(hero.wad(), sk,
					java.nio.file.Files.exists(wpn) ? WadFile.load(wpn) : null));
			blades = devple.gow.engine.asset.BladeModels.load(rom, 0, bladeLevel, skinned);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		heroHittable = new Hittable() {
			public Vector3f position() {
				return ctl.position;
			}

			public float hp() {
				return player.hp;
			}

			public int classId() {
				return 0; // 영웅 종류 0 (HeroRig 와 같음 — 적 분기 tgt=0, 예 미노타우로스 A400)
			}

			public int stanceTag() {
				return combat.stanceTag();
			}

			public boolean invulnerable() {
				return player.hp <= 0 || combat.invulnerable();
			}

			public devple.gow.engine.combat.HitVolumes hitVolumes() {
				return combat.hitVolumes();
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
			enemyKind = kind;
			enemyTweaks = kind.tweaks();
			heroSet.attachAll(kind.assets().wad()); // 영웅 처형 애니 외부 그룹 (maiCy2 등, 적 WAD 안)
			heroData.reindexAnimations();
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
			EnemyFighter en0 = enemy;
			enemy.onShatter(() -> {
				System.out.println(enemyWad + " 석상 파괴 (Stone Kill)");
				magicFx.effect("shatter", en0.pos);
			});
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
		// 검증용 (40 문서): -Dgow.magicStage=n 마법 단계, -Dgow.script="버튼:시작초:누름초,..." 패드 입력 재생
		String ms = System.getProperty("gow.magicStage");
		if (ms != null) {
			for (int id = 3; id <= 6; id++) {
				player.level.put(devple.gow.engine.game.Weapon.ofItem(id), Integer.parseInt(ms));
			}
			player.mpLevel = 3; // Nuke Points 100 이상
			player.mp = player.maxMp();
		}
		String sc = System.getProperty("gow.script");
		if (sc != null && !sc.isBlank()) {
			List<ScriptedInput.Step> st = new ArrayList<>();
			for (String part : sc.split(",")) {
				String[] f = part.trim().split(":");
				st.add(new ScriptedInput.Step(Float.parseFloat(f[1]), Float.parseFloat(f[2]), PadButton.valueOf(f[0].toUpperCase())));
			}
			scripted = new ScriptedInput(e.window().handle(), st);
		} else if (auto == Auto.COMBO) {
			// □ 다섯 번 (COMBO3A→3E 기대), 이후 △ (COMBO6A)
			List<ScriptedInput.Step> st = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				st.add(new ScriptedInput.Step(0.2f + i * 0.3f, 0.05f, PadButton.SQUARE));
			}
			st.add(new ScriptedInput.Step(4.0f, 0.05f, PadButton.TRIANGLE));
			scripted = new ScriptedInput(e.window().handle(), st);
		} else if (auto == Auto.GRAB && enemy != null) {
			// 적 HP = 잡기 원 아래, AI 정지. 배치 = 처형 설계 관계가 나오는 자리 (37 문서 §11.5, GrabRelation): 원작은 이벤트 19 처형에서 위치를
			// 맞추지 않고 GRAB 이 처음 닿은 자리 + 루트 모션이 관계를 만든다. 미니게임은 자동 입력 (AutoPad) 으로 성공 분기
			enemy.hp = 5;
			enemy.holdAi = true;
			ctl.yaw = 0;
			var fin = devple.gow.engine.combat.GrabRelation.finisher(heroData, enemy.classId(), enemy.hp);
			// 시작 거리: 같은 데이터로 헤드리스 모의 (새 영웅·적) 를 돌려 결합 0.5 초 뒤 synchJoint 오차가 가장 작은 거리
			Path wpnPath = rom.resolve("R_WPN0_" + Math.max(0, Math.min(5, bladeLevel)) + ".WAD");
			var kindF = enemyKind;
			var res = fin == null ? null : devple.gow.engine.combat.GrabRelation.search(() -> devple.gow.engine.combat.GrabRelation.Trial.create(
					sk, set, tw, data, () -> {
						try {
							return devple.gow.engine.combat.HitVolumes.forHero(hero.wad(), sk,
									java.nio.file.Files.exists(wpnPath) ? WadFile.load(wpnPath) : null);
						} catch (IOException ex) {
							throw new UncheckedIOException(ex);
						}
					}, kindF), 10, 130);
			if (res != null) {
				// 영웅 (원점, 요 0) 기준: 적은 설계 방향의 반대쪽 res.start 거리, 적 요 = −relYaw
				float ey = -res.relYaw();
				var rel = devple.gow.engine.combat.GrabRelation.relation(devple.gow.engine.combat.GrabRelation.Trial.create(sk, set, tw, data, null, kindF));
				Vector3f d = rel.getTranslation(new Vector3f()).setComponent(1, 0).normalize().mul(res.start()); // 적 기준 영웅 위치
				d.rotateY(ey).negate(); // 영웅 기준 적 위치
				enemy.pos.set(d.x, 0, d.z);
				enemy.yaw = ey;
				System.out.printf("처형 %d %s: 설계 관계 %.1f, 시작 거리 %.0f (적중 GRAB %.2f, 결합 거리 %.1f, 오차 %.1f)%n", res.finisher(),
						heroData.animation(fin).name, res.design(), res.start(), res.hitProgress(), res.link(), res.error());
			} else {
				enemy.pos.set(0, 0, -32);
				enemy.yaw = (float) Math.PI;
			}
			enemy.prevPos.set(enemy.pos);
			grab = new devple.gow.engine.combat.GrabSync(ctl, combat);
			List<ScriptedInput.Step> st = new ArrayList<>(List.of(new ScriptedInput.Step(0.3f, 0.05f, PadButton.CIRCLE)));
			if (fin != null && fin.branches().stream().anyMatch(b -> b.input() == 2)) {
				// 처형 중 영웅 입력 분기 (해골병 MAISKB00 [0.4–1] □ → MAISKB02 꿰뚫기): □ 를 누른다
				st.add(new ScriptedInput.Step(1.0f, 0.05f, PadButton.SQUARE));
			}
			scripted = new ScriptedInput(e.window().handle(), st);
		} else if (auto == Auto.MAGIC) {
			// ← 메두사 선택 → R2 홀드(광선, 석화) → □□ (석상 파괴) → ↑ 하데스 → R2 (영혼)
			scripted = new ScriptedInput(e.window().handle(), List.of(new ScriptedInput.Step(0.2f, 0.05f, PadButton.LEFT),
					new ScriptedInput.Step(0.5f, 3f, PadButton.L2), new ScriptedInput.Step(4.0f, 0.05f, PadButton.SQUARE),
					new ScriptedInput.Step(4.4f, 0.05f, PadButton.SQUARE), new ScriptedInput.Step(6.0f, 0.05f, PadButton.UP),
					new ScriptedInput.Step(6.3f, 0.1f, PadButton.L2)));
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
				return player.selectedMagic != null ? player.selectedMagic.itemId : 0;
			}

			public boolean hasMagic(int id) {
				return magic.has(id);
			}

			public void useMagic(int id) {
				magic.use(id);
			}

			public int classId() {
				return 0; // 영웅 종류 0 (HeroRig 와 같음 — 적 분기 tgt=0, 예 미노타우로스 A400)
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
		boolean locked = grab != null && grab.active();
		if (!locked) {
			combat.update(in, dt);
		}
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
		magic.select(in, ctl.position);
		magic.setTargets(() -> enemy != null ? List.of(enemy) : List.of(dummy));
		magic.update(combat, in, dt);
		magicFx.update(magic, ctl.position, ctl.yaw, enemy != null ? List.of(enemy) : List.of(dummy), dt, cam.eye);
		if (locked) {
			// 처형 결합 중: 영웅 애니·정렬은 GrabSync
		} else if (combat.active()) {
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
			if (grab != null) {
				Input gin = in;
				if (grab.active()) {
					// 미니게임 중에는 자동 입력, 그 밖에는 장면 입력 (해골병 □ 같은 영웅 입력 분기)
					autoPad.drive(grab.timed(), grab.qte());
					if (grab.timed() != null || grab.qte() != null) {
						gin = autoPad;
					}
				}
				boolean was = grab.active();
				grab.update(gin, enemy, enemyTweaks, dt);
				if (was && !grab.active()) {
					if (grab.executionSucceeded()) {
						enemy.hp = 0; // GameScene 과 같은 근사 (처형 성공 → 사망)
					}
					boolean mini = grab.lastResult() != null || grab.timedPassed + grab.timedFailed > 0;
					System.out.println("처형 결합 끝: " + (!mini ? "미니게임 없음" : grab.executionSucceeded() ? "성공" : "실패")
							+ " (타이밍 성공 " + grab.timedPassed + " / 실패 " + grab.timedFailed + ", 연타 " + grab.lastResult() + ")");
				}
			}
			if (grab == null || !grab.active()) {
				ctl.separate(enemy.pos, 22);
			}
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
		} else if (enemy.deadTime() < 3f && !enemy.shattered()) {
			minModel.draw(cam, e.window().aspect(), enemy.world(new Matrix4f(), alpha), enemy.animator().pose(), e.renderer().white());
		}
		var pose = ctl.animator().pose();
		model.draw(cam, e.window().aspect(), world, pose, e.renderer().white());
		if (blades != null) {
			blades.draw(cam, e.window().aspect(), world, ctl.animator().skeleton(), pose.world(ctl.animator().skeleton()), e.renderer().white());
		}
		magicFx.render(cam, e.window().aspect(), e.renderer().white());
		if (hud != null) {
			hud.render(e);
		}
	}

	@Override
	public void exit(Engine e) {
		model.close();
		if (blades != null) {
			blades.close();
		}
		skinned.close();
		grid.close();
		magicFx.close();
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
