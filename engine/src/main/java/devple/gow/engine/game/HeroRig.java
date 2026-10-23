package devple.gow.engine.game;

import devple.gow.tools.content.Content;
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
	/** 아직 HUD 에 알리지 않은 히트 마일스톤 보상 (없으면 null). */
	public HitCounter.Reward comboReward;
	public final DamageRules rules;
	public final devple.gow.engine.combat.Magic magic;
	public int difficulty = 1;
	/** 기어오르기·줄·밀기 등 (38 문서). null 이면 기존 지상/공중 이동만. */
	public devple.gow.engine.nav.HeroNavigator nav;
	private final AnimatedModel model;
	/** 혼돈의 블레이드 (lWeapOH/rWeapOH 부착, 34 문서). 없으면 null. */
	private final devple.gow.engine.asset.BladeModels blades;
	/** 참이면 보통 블레이드를 그리지 않는다 (신들의 분노 god 판 GO 가 대신, MagicFx.godBladesOn, 45 문서 §2). */
	public java.util.function.BooleanSupplier hideBlades = () -> false;
	/** 블레이드 사슬 (블레이드 행렬 따라가기 + 사슬 베지어, 45 문서 §3). */
	public final BladeChain chain;
	private devple.gow.engine.render.BladeChainRenderer chainRenderer;
	/** 블레이드 행렬을 계산한 틱의 영웅 행렬 (렌더 보간 보정). */
	private final Matrix4f bladeTickWorld = new Matrix4f();
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
		assets = new WadAssets(Content.file(rom, "R_HERO" + costume + ".WAD"));
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
		Path wpn = Content.file(rom, "R_WPN" + costume + "_" + Math.max(0, Math.min(5, bladeLevel())) + ".WAD");
		combat.setHitVolumes(HitVolumes.forHero(assets.wad(), sk, java.nio.file.Files.exists(wpn) ? WadFile.load(wpn) : null));
		blades = devple.gow.engine.asset.BladeModels.load(rom, costume, bladeLevel(), shader);
		chain = BladeChain.fromTweaks(tweaks);
		// 그림자 (47 문서 §7): 영웅 = 모드 2 (+0x174 bit 31 = 플레이어, 0x1BF8F8), 블레이드 GO = 영웅의 자식 → 같은 방향·평면 (0x1BF994)
		model.shadowMode = devple.gow.engine.light.ShadowVolume.MODE_GROUND;
		model.shadowKey = "hero";
		if (blades != null) {
			blades.setShadowParent(model);
		}
		this.rom = rom;
		hittable = new Hittable() {
			@Override
			public void hitPause(float s) {
				combat.pause(s); // 맞은 영웅도 히트스톱 (HeroController.updateCombat 가 hitPause 동안 멈춤)
			}

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
				comboReward = r; // HUD 마일스톤 표시 (0x1DB608 → HitCounter_Event 2)
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
		// 포세이돈 연타 회전 (SCR_Lightning 0x195160: 영웅 행렬에 Y 회전 Mash Max Spin · 연타율)
		ctl.yaw += magic.lightningSpin() * dt;
		updateTint();
	}

	private boolean tintGod, tintLightning;

	/**
	 * 영웅 인스턴스 색 (sub_001E03C0, 사건 때만 쓰고 유지): God Mode 진입 (0x1DAD18) = {@code Weapon Level n/God Mode Tint R/G/B}
	 * (+0x20, lv1–3 (0.5, 0.6, 0.75), lv4–5 (1, 0, 0)), 해제 = (1, 1, 1); SCR_Lightning 시작 (0x194AEC) = {@code Stage n Tint Red/Green/Blue}
	 * (R_PERMK 전부 1), 끝 (0x194858) = (1, 1, 1). 원작처럼 나중 사건이 덮어쓴다 (신 모드 중 포세이돈을 쓰면 틴트가 1 로 돌아감).
	 */
	private void updateTint() {
		boolean god = godMode.active();
		if (god != tintGod) {
			tintGod = god;
			String n = "/Player/Weapon Level " + bladeLevel() + "/";
			setTint(god ? new float[]{tweaks.f(n, "God Mode Tint R", 1), tweaks.f(n, "God Mode Tint G", 1), tweaks.f(n, "God Mode Tint B", 1)}
					: null);
		}
		boolean ls = magic.lightningScript();
		if (ls != tintLightning) {
			tintLightning = ls;
			String n = "/Player/Lightning/";
			String sp = "Stage " + magic.stage(3) + " ";
			setTint(ls ? new float[]{tweaks.f(n, sp + "Tint Red", 1), tweaks.f(n, sp + "Tint Green", 1), tweaks.f(n, sp + "Tint Blue", 1)}
					: null);
		}
	}

	private final float[] tint = {1, 1, 1};

	private void setTint(float[] c) {
		tint[0] = c == null ? 1 : c[0];
		tint[1] = c == null ? 1 : c[1];
		tint[2] = c == null ? 1 : c[2];
		model.setColorScale(tint[0], tint[1], tint[2]);
	}

	/** 현재 영웅 인스턴스 색 (테스트). */
	public float[] tint() {
		return tint.clone();
	}

	/**
	 * 블레이드 사슬 한 틱 (원작 0x1FDB18 → 0x1FB460, 매 프레임). 전투·잡기·내비게이션 어느 상태든 애니 자세가 정해진 뒤 부른다.
	 */
	public void tickBlades(float dt) {
		var m = combat.move();
		boolean blades = !player.artemisEquipped; // 0x1FAFC8: 블레이드 GO +0x4 & 4 (아르테미스의 검이면 블레이드 GO 없음)
		chain.update(this::jointWorld, combat.active(), m != null && (m.flags() & 0x80) != 0, dt, blades ? ctl.position : null, bladeWalls);
		for (BladeChain.Side s : chain.sides) {
			if (blades && !s.sweepTo.isEmpty()) {
				combat.chainSweep(s.index == 0 ? 2 : 3, s.sweepFrom, s.sweepTo); // 0x1FB158: 왼쪽 부위 2, 오른쪽 3
				if (combat.chainTouched) {
					s.bleed(); // 0x1FB2C0: 스윕이 무엇이든 맞으면 +0xA4 = 0
				}
			}
		}
		ctl.world(bladeTickWorld, 1f);
		tickBladeLights(dt);
	}

	/** 블레이드 벽 질의 (0x1FAFC8). null 이면 벽 충돌 없음 (연습 씬 등). */
	private BladeChain.Walls bladeWalls;

	public void setBladeWalls(BladeChain.Walls w) {
		bladeWalls = w;
	}

	/** LeftBladeLight / RightBladeLight (R_WPN 의 LGT, 47 문서 §4). 무기 WAD 가 없거나 라이트가 없으면 null. */
	private final devple.gow.engine.light.Light[] bladeLights = new devple.gow.engine.light.Light[2];

	/**
	 * 무기 WAD 의 블레이드 라이트를 서버에 넣는다 (Blades_Lights 0x1FAA10: "LeftBladeLight" 왼쪽 / "RightBladeLight" 오른쪽, +0xE0 = 0).
	 * 아르테미스의 검이면 블레이드 GO 가 없으니 켜지지 않는다 [추정].
	 */
	public void attachBladeLights(devple.gow.engine.light.LightServer srv) {
		if (blades == null) {
			return;
		}
		java.util.List<devple.gow.engine.light.LightDef> defs = devple.gow.engine.light.LightDef.load(blades.assets().wad());
		String[] names = {"LeftBladeLight", "RightBladeLight"};
		for (int side = 0; side < 2; side++) {
			for (devple.gow.engine.light.LightDef d : defs) {
				if (d.name().equalsIgnoreCase(names[side])) {
					devple.gow.engine.light.Light l = srv.add(d);
					l.dyn = 0f;
					l.active = false;
					bladeLights[side] = l;
				}
			}
		}
	}

	/** 마지막으로 그린 영웅의 라이트 묶음 (디버그). */
	public devple.gow.engine.light.LightRig lastLights() {
		return model.lastLights;
	}

	public devple.gow.engine.light.Light bladeLight(int side) {
		return bladeLights[side];
	}

	/**
	 * 블레이드 라이트 갱신: 위치 = <b>당기기 전</b> 블레이드 점 B = slerp 행렬 위치 − Z × 8 (0x1FB044 – 0x1FB118: (−Z × 0.5) × 16, 벽 당김 0x1FB2CC 앞),
	 * 방향 = 시작점 O (영웅 x, 골반 y, 영웅 z) − B (0x151978) [확실]. 세기 = 궤적 (이벤트 17/18) 이면 +4/초, 아니면 −2/초 (0x201C78 / 0x201DD8, 45 문서 §3.5) [확실].
	 */
	private void tickBladeLights(float dt) {
		Matrix4f bw = new Matrix4f();
		for (int side = 0; side < 2; side++) {
			devple.gow.engine.light.Light l = bladeLights[side];
			if (l == null) {
				continue;
			}
			BladeChain.Side s = chain.sides[side];
			boolean on = s.valid() && !player.artemisEquipped && trail(side);
			if (s.lightValid) {
				l.setPosition(s.lightPoint);
				if ((l.def.type() & devple.gow.engine.light.LightDef.DIRECTIONAL) != 0 && s.lightDir.lengthSquared() > 1e-8f) {
					l.dir.set(s.lightDir).normalize(); // 0x151978: 종류 비트 2 면 정규화 방향
				}
			} else if (s.valid()) {
				s.world(bw);
				l.setPosition(new Vector3f(bw.m30() - 8f * bw.m20(), bw.m31() - 8f * bw.m21(), bw.m32() - 8f * bw.m22()));
			}
			l.ramp(on, dt);
		}
	}

	/** 블레이드 GO 행렬 (사슬이 정한 행렬 × 배율). 아직 계산 전이면 false. */
	public boolean bladeWorld(int side, Matrix4f out) {
		BladeChain.Side s = chain.sides[side];
		if (!s.valid()) {
			return false;
		}
		s.world(out);
		return true;
	}

	/** 블레이드 GO 부착물 종류 1 blooddrip / 2 bloodspray 생성률 배율 (0x1FB508, {@link BladeChain.Side#blood}). */
	public float bladeBlood(int side, int kind) {
		return chain.sides[side].blood(kind);
	}

	/** 궤적 켜짐 (기술 이벤트 17/18). */
	public boolean trail(int side) {
		return combat.trail(side);
	}

	/** 렌더 보간 보정: 이번 프레임 영웅 행렬 × 블레이드 틱 영웅 행렬⁻¹. */
	private Matrix4f renderFix(float alpha) {
		return ctl.world(new Matrix4f(), alpha).mul(new Matrix4f(bladeTickWorld).invert());
	}

	/**
	 * 사슬 고리·사슬 빛·궤적 (45 문서 §3). 반투명이라 불투명 패스 뒤에 부른다. 아르테미스의 검이면 없음.
	 *
	 * @param god 신들의 분노 god 판 머티리얼·Trail Tint
	 */
	public void drawChains(Camera cam, float aspect, float alpha, boolean god) {
		if (blades == null || player.artemisEquipped || System.getProperty("gow.nochain") != null) {
			return;
		}
		if (chainRenderer == null) {
			chainRenderer = new devple.gow.engine.render.BladeChainRenderer(blades.assets());
		}
		String n = "/Player/Weapon Level " + bladeLevel() + "/";
		String p = god ? "God Mode Trail Tint " : "Trail Tint ";
		float[] tint = {tweaks.f(n, p + "R", 1), tweaks.f(n, p + "G", 1), tweaks.f(n, p + "B", 1), tweaks.f(n, p + "A", god ? 1 : 0.8f)};
		String c = "/Player/Costume " + player.costume + "/";
		float wl = tweaks.f(c, god ? "God Mode Weapon Length" : "Weapon Length", 0.7f) * 16f;
		chainRenderer.draw(cam, aspect, renderFix(alpha), chain, god, tint, new boolean[]{trail(0), trail(1)}, wl);
	}

	/**
	 * 사슬 그림자 (Blades_Init 의 MAT_stencil 띠, 0x1FD218 — 블레이드 +0xA8 bit 1 = 영웅 그림자의 받는 평면이 있을 때) 를 넘긴다:
	 * 보이는 사슬마다 베지어를 마디 수로 나눈 점 (월드, 47 문서 §7.5).
	 */
	public void submitChainShadows(devple.gow.engine.light.ShadowCasters sc, float alpha) {
		if (sc == null || blades == null || player.artemisEquipped || System.getProperty("gow.nochain") != null) {
			return;
		}
		Matrix4f fix = renderFix(alpha);
		for (BladeChain.Side s : chain.sides) {
			if (!s.valid() || !s.chainVisible) {
				continue;
			}
			float n = chain.segments(s.ctrl);
			if (n <= 1f) {
				continue;
			}
			int k = Math.min(BladeChain.MAX_SAMPLES - 1, (int) Math.ceil(n));
			Vector3f[] pts = new Vector3f[k + 1];
			for (int i = 0; i <= k; i++) {
				pts[i] = fix.transformPosition(BladeChain.bezier(s.ctrl, (float) i / k, new Vector3f()));
			}
			sc.submitChain(model, pts);
		}
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
		} else if (blades != null && !hideBlades.getAsBoolean()) {
			if (chain.sides[0].valid() && chain.sides[1].valid()) {
				// 블레이드 행렬 = 사슬이 따라간 행렬 (원작 0x1FBB50 → 블레이드 GO), 렌더 보간만 보정
				Matrix4f fix = renderFix(alpha);
				Matrix4f bw = new Matrix4f();
				for (BladeChain.Side s : chain.sides) {
					blades.drawAt(cam, aspect, new Matrix4f(fix).mul(s.world(bw)), white);
				}
			} else {
				blades.draw(cam, aspect, w, sk, pose.world(sk), white);
			}
		}
	}

	@Override
	public void close() {
		model.close();
		if (blades != null) {
			blades.close();
		}
		if (chainRenderer != null) {
			chainRenderer.close();
		}
		if (artemis != null) {
			artemis.close();
		}
		shader.close();
		assets.close();
	}
}
