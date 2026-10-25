package devple.gow.engine.fx;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.engine.asset.RenderModel;
import devple.gow.engine.combat.Combatant;
import devple.gow.engine.combat.HadesSouls;
import devple.gow.engine.combat.Hittable;
import devple.gow.engine.combat.Magic;
import devple.gow.engine.combat.ZeusBolt;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Texture;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.wad.WadFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 마법·전투 시각 효과 (35 문서 §7, 40 문서). 원작 이펙트 GO 를 {@link EffectSystem} 으로 붙인다:
 * <ul>
 *   <li>메두사: 머리 goMewH (SCR_ShowMedusaHead, rWeapIH 부착, act On/Off), 광선 goEyeBeam (반복, 같은 관절, 조준 방향), 섬광 goMedHeadFlash (1회),
 *       Nuke = Concussion 템플릿의 goMedHeadNuke (반경 확대)</li>
 *   <li>제우스 차지: SCR_ShowThunderbolt → goBolt + goBoltChargeUp 을 L/RWEAPIH 에</li>
 *   <li>제우스: 투사체 goBolt + 꼬리 goStdBolt (Arrow 템플릿 GO Name / Trail Name), 명중 goBoltHit</li>
 *   <li>하데스: 영혼 goDedRed (속도 방향), 명중 goDeadHit, 소멸 goDeadDie</li>
 *   <li>포세이돈: 루프 동안 goLightningAttackMain + goLightningRadius, 주기적으로 주변 goStrikeGrp, 피해 틱마다 대상에 goLightningSingleBolt</li>
 *   <li>전투: 영웅/적 기술의 SCR_PlayEffect(PlayFX 템플릿) / SCR_ConcussionBurst(Concussion 템플릿) 이벤트, 적중 시 피격자 MFX 피 (goSklBlood),
 *       발소리 MFX Run Dust Name (goRunDust), 착지 Jump Land Effect, 무기-월드 Slice Hit, 바닥 피 자국, 부서지는 물체 GO (41 문서)</li>
 * </ul>
 * 원작 데이터가 없는 것(석상 먼지·석상 파괴 *stonedbrk)이나 WAD 가 없을 때는 예전 근사 파티클을 쓴다.
 */
public final class MagicFx implements AutoCloseable {

	/** Hash127Upper("SCR_PlayEffect") / ("SCR_ConcussionBurst") — 영웅·적 CombatFile SCRIPT 이벤트 (idx = 템플릿). */
	public static final int SCR_PLAY_EFFECT = 0x097AE822, SCR_CONCUSSION_BURST = 0x242EF94F;
	/** MFX 템플릿의 피 이펙트 GO (+0x28, 키 이름 미복원 #405ce7fc): 500 Flesh / 501 Hero Flesh = goSklBlood. */
	static final int MFX_BLOOD = 0x405CE7FC;
	/** MFX +0x2C Jump Land Effect (#35159154), +0x30 Slice Hit Name (#e54a19a6), +0x3C Slice Hit Sound (#0f01c134). */
	static final int MFX_LAND = 0x35159154, MFX_SLICE = 0xE54A19A6, MFX_SLICE_SOUND = 0x0F01C134;
	/** Hash127Upper: SCR_ShowMedusaHead (0x197280), SCR_ShowThunderbolt (0x19FF60), SCR_ThrowThunderbolt (0x1A0B00). */
	public static final int SCR_SHOW_MEDUSA_HEAD = 0xE5D96CF1, SCR_SHOW_THUNDERBOLT = 0x8D7EAE7F, SCR_THROW_THUNDERBOLT = 0x96EDE590;
	/** Hash127Upper("SCR_BerserkBladeFX") — BERSERKENTER [0, 1] 의 SCRIPT 이벤트 (Items_RegisterScr 0x19CF28 → 0x19C798). */
	public static final int SCR_BERSERK_BLADE_FX = 0x5968598D;

	/**
	 * 레벨 충돌 질의 (무기-월드 베기, 바닥 피 자국, 착지). GameScene 이 LevelCollision 으로 제공.
	 */
	public interface Surface {
		/** 선분 from→to 의 첫 충돌: {x, y, z, nx, ny, nz, sheetFx} 또는 null. */
		float[] cast(Vector3f from, Vector3f to);

		/** p 아래 maxDrop 안의 바닥: {y, sheetFx} 또는 null. */
		float[] floor(Vector3f p, float maxDrop);
	}

	/** 관절 월드 조회 (관절 이름 대소문자 무시). */
	@FunctionalInterface
	public interface Joints {
		boolean world(String joint, Matrix4f out);
	}

	private final ParticleSystem ps;
	private final EffectSystem fx;
	private final Tweaks tw = new Tweaks();
	private final Path rom;
	private final Map<String, Integer> stageLoaded = new HashMap<>();
	private final Map<Object, ParticleSystem.Emitter> trails = new IdentityHashMap<>();
	private final Map<Object, List<EffectSystem.Effect>> attached = new IdentityHashMap<>();
	private final Map<String, ParticleDef> defs = new HashMap<>();
	private final List<float[]> bursts = new ArrayList<>(); // [time left] 이미터와 짝
	private final List<ParticleSystem.Emitter> burstEmitters = new ArrayList<>();
	private final List<Runnable> perFrame = new ArrayList<>();
	private ParticleSystem.Emitter beam;
	private EffectSystem.Effect beamFx, lightningFx;
	private final Map<Hittable, ParticleSystem.Emitter> stones = new IdentityHashMap<>();
	/** 석상 GO (Sold +0x20, goMINStonedBRK) — 석화된 적 대신 보이는 돌 조각 모델 (35 문서 §2.2). */
	private final Map<Hittable, EffectSystem.Effect> statues = new IdentityHashMap<>();
	/** 띄운 석상 수 / 석화 순간 GO 수 / 부서진 석상 수 (테스트·로그). */
	public int statuesShown, freezeFxShown, statuesBroken;
	private Joints heroJoints = (j, out) -> false;
	private Combatant heroCombat;
	private Surface surface;
	private final java.util.Random rnd = new java.util.Random(7);
	/** 메두사 머리 GO (goMewH, 0x1970D0): 영웅 rWeapIH 에 부착, 광선 중 act "On", 아니면 "Off" (0x196B18). */
	private EffectSystem.Effect head;
	private int headSerial = -1;
	/** 제우스 손 번개 (SCR_ShowThunderbolt: goBolt + goBoltChargeUp 을 L/RWEAPIH 에, 0x19FE10). */
	private final List<EffectSystem.Effect> charge = new ArrayList<>();
	private int chargeSerial = -1;
	/** 포세이돈 goLightningRadius (0x1948F0 이 AttackMain 과 함께 생성) 와 goStrikeGrp 타이머 (0x3368A8). */
	private EffectSystem.Effect radiusFx;
	private float strikeTimer = 1;
	/** Sink 로 직접 낸 소리 (이름 → 남은 초): 같은 소리를 이펙트 SEM 이 다시 내지 않게. */
	private final Map<String, Float> recentSounds = new HashMap<>();
	/** 무기-월드 베기 기록 (기술 serial << 16 | 공격 번호). */
	private final java.util.Set<Long> sliced = new java.util.HashSet<>();
	/** PlayFX 로 켠 이펙트 (전투자별, Remove 플래그가 끈다). */
	private final Map<Combatant, List<EffectSystem.Effect>> playFxLive = new IdentityHashMap<>();
	private Vector3f heroPos = new Vector3f();
	private float heroYaw;
	private float aimYaw;

	private static ParticleDef def(String name, float rate, float life, float box, float spreadV, float up, float size0, float size1,
								   float[] c0, float[] c1, float stretch, float gravity) {
		return new ParticleDef(name, rate, life, 0.3f, new float[]{box, box, box}, new float[]{0, up, 0},
				new float[]{spreadV, spreadV, spreadV}, new float[]{0, -gravity, 0}, 1.5f, size0, size1, c0, c1, 0, stretch, 0,
				null, 1, RenderModel.Blend.ADD);
	}

	/** 근사 파티클만 (원작 WAD 없음). */
	public MagicFx() {
		this(null);
	}

	/**
	 * @param rom gow-rom 경로. R_PERMK (전투 이펙트·템플릿) 를 바로, 마법 WAD(R_MEDHD/R_THBLT/R_RDEAD/R_LGHTN + 단계)는 처음 쓸 때 읽는다.
	 */
	public MagicFx(Path rom) {
		this.rom = rom;
		this.fx = new EffectSystem(8192);
		this.ps = new ParticleSystem(4096);
		if (rom != null) {
			Path permk = Content.file(rom, Region.of(rom).perm());
			if (fx.add(permk)) {
				try {
					tw.load(WadFile.load(permk));
				} catch (IOException ignored) {
					// 템플릿 없음 → 이벤트 이펙트 생략
				}
			}
		}
		defs.put("bolt", def("bolt", 240, 0.25f, 4, 30, 0, 9, 2, new float[]{1, 1, 0.7f, 1}, new float[]{0.4f, 0.6f, 1, 0}, 0, 0));
		defs.put("boltHit", def("boltHit", 900, 0.35f, 6, 260, 40, 12, 2, new float[]{1, 1, 0.8f, 1}, new float[]{0.3f, 0.5f, 1, 0}, 0.04f, 0));
		defs.put("soul", def("soul", 120, 0.5f, 5, 15, 10, 14, 4, new float[]{1, 0.35f, 0.1f, 0.9f}, new float[]{0.4f, 0, 0, 0}, 0, -20));
		defs.put("soulHit", def("soulHit", 500, 0.4f, 6, 150, 30, 12, 3, new float[]{1, 0.4f, 0.1f, 1}, new float[]{0.3f, 0, 0, 0}, 0, 0));
		// 광선: 원뿔 모드 (반각 ≈ 10.4°, 15m 를 1초에) — goEyeBeam 이 없을 때의 근사
		defs.put("beam", new ParticleDef("beam", 220, 1f, 0.1f, new float[]{2, 2, 2}, new float[3], new float[3], new float[3], 0,
				12, 30, new float[]{0.45f, 1, 0.35f, 0.3f}, new float[]{0.1f, 0.5f, 0.1f, 0}, 0.05f, 0, 0, null, 1,
				RenderModel.Blend.ADD, 0.115f, 15 * Magic.UNIT, 40, 0, 0, 0));
		defs.put("flash", def("flash", 1500, 0.3f, 20, 400, 0, 20, 40, new float[]{0.7f, 1, 0.6f, 1}, new float[]{0.2f, 0.5f, 0.2f, 0}, 0, 0));
		defs.put("stone", def("stone", 25, 0.8f, 18, 8, 15, 5, 3, new float[]{0.6f, 0.6f, 0.55f, 0.6f}, new float[]{0.3f, 0.3f, 0.3f, 0}, 0, 0));
		defs.put("shatter", new ParticleDef("shatter", 1200, 1.1f, 0.3f, new float[]{14, 25, 14}, new float[]{0, 120, 0},
				new float[]{180, 120, 180}, new float[]{0, -600, 0}, 0.5f, 7, 5, new float[]{0.65f, 0.63f, 0.58f, 1},
				new float[]{0.45f, 0.43f, 0.4f, 0.8f}, 0, 0, 0, null, 1, RenderModel.Blend.ALPHA));
		defs.put("blood", def("blood", 600, 0.5f, 3, 60, 50, 3, 2, new float[]{0.55f, 0.02f, 0.02f, 1}, new float[]{0.3f, 0, 0, 0.8f}, 0, 300));
	}

	public EffectSystem effects() {
		return fx;
	}

	/** 추가 튜닝 (적 WAD 의 PlayFX / Concussion / MFX 템플릿). */
	public void addTweaks(WadFile wad) {
		tw.load(wad);
	}

	/**
	 * 레벨·적 WAD: 그 WAD 의 이펙트 GO (goBHABloodBB, 부서지는 물체 GO …) 와 튜닝 템플릿 (MFX 0xx 표면 재질, 적 PlayFX/MFX 5xx) 등록.
	 * 원작은 WAD 를 적재하면 GO·템플릿이 전역 표에 들어간다. 같은 WAD 를 두 번 넣으면 무시.
	 */
	public void addWad(devple.gow.engine.asset.WadAssets a) {
		if (fx.add(a)) {
			tw.load(a.wad());
		}
	}

	/** {@link #addWad} 또는 이미 있으면 맨 뒤로 (이음으로 현재가 된 레벨·그 적 종류 — 새 씬 enter 와 같은 우선순위, 43 §27). */
	public void addOrPromoteWad(devple.gow.engine.asset.WadAssets a) {
		if (fx.addOrPromote(a)) {
			tw.load(a.wad());
		}
	}

	/** WAD 파일 (다른 슬롯 레벨) 의 이펙트 GO 를 등록 — 이미 올린 경로면 아무것도 안 한다 (자산은 이펙트 시스템이 소유하고 닫는다). */
	public void addWad(java.nio.file.Path wad) {
		if (wad != null && addedPaths.add(wad.toAbsolutePath().normalize())) {
			fx.add(wad);
		}
	}

	private final java.util.Set<java.nio.file.Path> addedPaths = new java.util.HashSet<>();

	/**
	 * 레벨 WAD 의 이펙트 GO 등록을 뺀다 (그 WAD 로 도는 이펙트·파티클 포함, {@link EffectSystem#remove}) — 이음으로 씬이 이어지는 동안 두 슬롯에서 모두
	 * 내려간 레벨 장면을 닫기 전에 (엔진 전용, 43 §27). wad = 그 장면 자산의 WAD, path = 같은 레벨을 경로로 더했으면 그것도 (null 가능).
	 */
	public void removeWad(devple.gow.tools.wad.WadFile wad, java.nio.file.Path path) {
		fx.remove(wad);
		if (path != null && addedPaths.remove(path.toAbsolutePath().normalize())) {
			for (devple.gow.tools.wad.WadFile w : fx.takeWadsAt(path)) {
				fx.remove(w);
			}
		}
	}

	/** 이펙트 SEM 소리 출력 (SoundManager). Sink 로 막 낸 같은 이름은 건너뛴다 (MedusaGaze 의 SND_MEDUSA_RAY_SHOT 등). */
	public void setSounds(EffectSystem.Sounds out) {
		fx.sounds = out == null ? null : new EffectSystem.Sounds() {
			@Override
			public int play(String snd, Vector3f at, float near, float far) {
				if (recentSounds.containsKey(snd)) {
					return 0;
				}
				recentSounds.put(snd, 0.3f);
				return out.play(snd, at, near, far);
			}

			@Override
			public void move(int handle, Vector3f at) {
				out.move(handle, at);
			}

			@Override
			public void stop(int handle) {
				out.stop(handle);
			}
		};
	}

	/** 레벨 충돌 (무기-월드 베기·바닥 피·착지). */
	public void setSurface(Surface s) {
		surface = s;
	}

	public Tweaks tweaks() {
		return tw;
	}

	/** 관절 이름 → 번호 (대소문자 무시, 없으면 −1). 튜닝은 "head"/"synchJoint", 이벤트 해시는 대문자 이름. */
	public static int joint(Skeleton sk, String name) {
		if (sk == null || name == null) {
			return -1;
		}
		for (Skeleton.Joint j : sk.joints) {
			if (j.name().equalsIgnoreCase(name)) {
				return j.index();
			}
		}
		return -1;
	}

	/** 적 관절 조회 (적 월드 × 포즈 관절). */
	public static Joints joints(devple.gow.engine.combat.EnemyFighter f) {
		return (j, out) -> {
			f.world(out, 1f);
			Skeleton sk = f.animator().skeleton();
			int i = joint(sk, j);
			if (i < 0) {
				return false;
			}
			out.mul(new Matrix4f().set(f.animator().pose().world(sk)[i]));
			return true;
		};
	}

	/** 마법 WAD 단계 맞추기 (R_MEDHD0..2 등). 단계가 바뀌면 다시 등록해 같은 이름을 덮는다. */
	private void stage(String prefix, int st) {
		if (rom == null) {
			return;
		}
		Integer cur = stageLoaded.get(prefix);
		if (cur != null && cur == st) {
			return;
		}
		stageLoaded.put(prefix, st);
		Path p = Content.file(rom, prefix + Math.max(0, Math.min(2, st)) + ".WAD");
		if (!Files.exists(p)) {
			p = Content.file(rom, prefix + "0.WAD");
		}
		fx.add(p);
	}

	/** 영웅 관절 (마법 이펙트 부착: 메두사 머리 손 rWeapIH). */
	public void bindHero(Combatant c, Joints joints) {
		heroJoints = joints != null ? joints : (j, out) -> false;
		heroCombat = c;
		bind(c, heroJoints, true);
		c.addEventListener(e -> {
			if (e.type() != 8) {
				return;
			}
			if (e.hash() == SCR_SHOW_MEDUSA_HEAD) {
				showHead(c);
			} else if (e.hash() == SCR_SHOW_THUNDERBOLT) {
				showThunderbolt(c, e.index());
			} else if (e.hash() == SCR_THROW_THUNDERBOLT) {
				hideThunderbolt();
			} else if (e.hash() == SCR_BERSERK_BLADE_FX) {
				berserkBladeFx();
			}
		});
	}

	/**
	 * SCR_BerserkBladeFX (0x19C798, BERSERKENTER 시작): 아르테미스의 검 WAD 맥락 (0x19C1F0 "WAD_R_Brsrk") 에서 영웅 "RWRIST" 관절 월드 행렬로
	 * "goMAIBSActive" 를 만들어 그 관절에 붙인다 (0x2283A8(…, 행렬, 영웅, 관절)) [확실: 이름·관절 / 추정: 부착]. 오브젝트 maibsactive 는 관절 배율 1/32,
	 * act MAIBSactive 0.93 초가 0.2 초 무렵 표면 이미터 BBglowemit (BBEFXgeomShape, 313/초, PTC_BBglowpart 파랑-보라 (83, 75, 204) 가산 0.12 초) 와
	 * 불티 둘을 잠깐 켠다 — 검을 뽑을 때 손목 옆 보라 빛 (영상 4014).
	 */
	private void berserkBladeFx() {
		stage("R_BRSRK", Math.max(0, artemisStage.getAsInt()));
		fx.play("goMAIBSActive", out -> {
			if (!heroJoints.world("rWrist", out)) {
				out.translation(heroPos).translate(0, 40, 0).rotateY(heroYaw);
			}
			return true;
		});
	}

	/** 아르테미스의 검 단계 (0..2, 들지 않았으면 −1) — GameScene 이 영웅 상태로 둔다. */
	private java.util.function.IntSupplier artemisStage = () -> -1;
	private EffectSystem.Effect artemisFx;
	private int artemisFxStage = -1;

	public void setArtemisBlade(java.util.function.IntSupplier stage) {
		artemisStage = stage != null ? stage : () -> -1;
	}

	/**
	 * 아르테미스의 검 GO (0x19C240): 장착 때 /Player/BerserkBlade "Stage n GO Name" (= goBerserkBlade, WAD_R_Brsrk) 을 만들어 영웅 RWEAPIH 관절에
	 * 붙이고 (0x131460), 종류 &lt; 0xB 부품 (이미터) 의 생성률 배율 +0xB4 를 1 로 (0x19C3C4 – 0x19C414) — 표면 이미터 Bglowemit1 (700/초) ·
	 * Bfireemit1 (1000/초) 가 칼날 모양 MSH_berzergeomShape1 위에 파랑-보라 (25, 29, 78) 가산 파티클 (0.15 / 0.10 초, PTC 0x30 = 검을 따라감) 을 낸다
	 * [확실: 코드·데이터]. 메시는 {@code ArtemisBladeModel} 이 그린다 (hideMesh). 해제 (0x19C488) 는 GO 를 지운다 (0x132768).
	 */
	private void artemisBlade() {
		int st = artemisStage.getAsInt();
		if (st >= 0 && (artemisFx == null || artemisFx.done() || st != artemisFxStage)) {
			if (artemisFx != null) {
				artemisFx.stop();
			}
			stage("R_BRSRK", st);
			String go = tw.s("/Player/BerserkBlade/", "Stage " + st + " GO Name", "goBerserkBlade");
			artemisFx = fx.play(go, out -> {
				if (!heroJoints.world("rWeapIH", out)) {
					out.translation(heroPos).translate(0, 40, 0).rotateY(heroYaw);
				}
				return true;
			}, Float.MAX_VALUE);
			if (artemisFx != null) {
				artemisFx.hideMesh = true;
			}
			artemisFxStage = st;
		} else if (st < 0 && artemisFx != null) {
			artemisFx.stop();
			artemisFx = null;
			artemisFxStage = -1;
		}
	}

	/** 아르테미스의 검 GO (테스트). */
	public EffectSystem.Effect artemisBladeFx() {
		return artemisFx;
	}

	/**
	 * SCR_ShowMedusaHead (0x197280 → 0x1970D0): /Player/MedusaHead "HeadName" (goMewH) GO 를 만들어 영웅의 "JointName" (rWeapIH) 관절에
	 * 부착 (sub_00131460) 하고 act "Off" 로 시작. 스크립트가 끝나면(참조 수 0, 0x197248) 지운다 → 엔진: 기술이 바뀔 때 [추정: 기술 = 스크립트 수명].
	 */
	private void showHead(Combatant c) {
		headSerial = c.moveSerial();
		if (head != null && !head.done()) {
			return;
		}
		stage("R_MEDHD", stages[4]);
		String n = "/Player/MedusaHead/";
		String name = tw.s(n, "HeadName", "goMewH");
		String jn = tw.s(n, "JointName", "rWeapIH");
		head = fx.play(name, out -> {
			if (!heroJoints.world(jn, out)) {
				out.translation(heroPos).translate(0, 50, 0).rotateY(heroYaw);
			}
			return true;
		}, Float.MAX_VALUE); // 첫 act "main" (0.03초) 은 수명이 아니다 — 머리는 스크립트가 지울 때까지
		if (head != null) {
			head.setAct("Off");
		}
	}

	/** 메두사 머리: 광선 중 "On" (MHBemit 채널·눈 회전), 아니면 "Off". 기술이 바뀌고 광선도 꺼지면 제거 (0x1971B0). */
	private void updateHead(Magic m) {
		if (head == null) {
			return;
		}
		boolean beam = m.medusa.beamOn();
		if (head.done() || (!beam && heroCombat != null && (heroCombat.moveSerial() != headSerial || !heroCombat.active()))) {
			head.stop();
			head = null;
			return;
		}
		head.setAct(beam ? "On" : "Off");
	}

	/** 머리 GO (테스트·디버그). */
	public EffectSystem.Effect head() {
		return head;
	}

	/**
	 * SCR_ShowThunderbolt (0x19FF60): 이벤트 인자 1 이면 LWEAPIH, 아니면 RWEAPIH 에 goBolt + goBoltChargeUp (0x19FE10) 을 부착.
	 * SCR_ThrowThunderbolt 또는 기술 변경에서 뗀다 [추정: 던질 때 화살 객체로 넘어감].
	 */
	private void showThunderbolt(Combatant c, int arg) {
		chargeSerial = c.moveSerial();
		if (!charge.isEmpty()) {
			return;
		}
		stage("R_THBLT", stages[5]);
		String jn = arg == 1 ? "lWeapIH" : "rWeapIH";
		EffectSystem.Anchor a = out -> {
			if (!heroJoints.world(jn, out)) {
				out.translation(heroPos).translate(0, 50, 0);
			}
			return true;
		};
		addIf(charge, fx.play("goBolt", a));
		addIf(charge, fx.play("goBoltChargeUp", a));
	}

	private void hideThunderbolt() {
		charge.forEach(EffectSystem.Effect::stop);
		charge.clear();
	}

	public List<EffectSystem.Effect> charge() {
		return charge;
	}

	/**
	 * 전투자 연결: 기술 SCRIPT 이벤트 SCR_PlayEffect / SCR_ConcussionBurst → 템플릿 이펙트, 적중 → 피격자 MFX 피.
	 *
	 * @param hero 영웅이면 피격 MFX 501 (Hero Flesh), 적이면 500 (Flesh) [근사: 원작은 피격 부위 재질별 표 0x3376FC]
	 */
	public void bind(Combatant c, Joints joints, boolean hero) {
		c.addEventListener(e -> {
			if (e.type() != 8) {
				return;
			}
			if (e.hash() == SCR_PLAY_EFFECT) {
				playFx(e.index(), c, joints);
			} else if (e.hash() == SCR_CONCUSSION_BURST) {
				concussion(e.index(), joints, null, c);
			}
		});
		c.addContactListener((a, victim, r) -> {
			if (r == Hittable.Result.HIT && victim != null) {
				// 0x2034CC: 공격자 +0x80 핸들 (주 대상 [추정]) 이 피격자면 배율 1, 아니면 0.125 + 바닥 피 자국 없음 [확실: 분기]
				hitBlood(a, c.owner().position(), victim, !hero, c.target() == null || c.target() == victim);
			}
		});
	}

	/**
	 * 사라진 적 (시체 정리) 의 연결 끊기 — 살아 있는 PlayFX 목록·석화 이미터를 정리한다. 예전에는 적마다 키가 남아 Combatant
	 * (애니·전투 데이터) 가 레벨 끝까지 남았고, 석화 중 사라진 적의 돌 이미터는 계속 돌았다 (48 문서 §4).
	 */
	public void unbind(Combatant c, Hittable h) {
		List<EffectSystem.Effect> l = playFxLive.remove(c);
		if (l != null) {
			l.forEach(EffectSystem.Effect::stop);
		}
		ParticleSystem.Emitter e = h != null ? stones.remove(h) : null;
		if (e != null) {
			ps.emitters().remove(e);
		}
		EffectSystem.Effect st = h != null ? statues.get(h) : null;
		if (st != null && st.hold) { // 부서지는 중인 석상은 Fade 까지 둔다
			st.stop();
			statues.remove(h);
		}
	}

	/** 적의 석상 이펙트 (없으면 null). */
	public EffectSystem.Effect statue(Hittable h) {
		return statues.get(h);
	}

	/**
	 * 석화 겉모습 (35 문서 §2.2, {@link devple.gow.engine.combat.EnemyFighter.StoneLook}): 원작은 적 몸 색을 바꾸지 않는다.
	 * SCR_TurnToRock 이 석화 순간 GO (goMINFreeze) 를, 석화 자세 끝 (진행률 &gt; 0.99) 에 0x1E8960 이 석상 GO (goMINStonedBRK:
	 * MDL_MINStonedBRK + 돌 텍스처 TXR_Minotaurfile1 = GFX_minotaurstone) 를 소유자 행렬에 만들고 소유자를 숨긴다 [확실].
	 * 석상은 맞을 때까지 act 0 초 자세 (석화 자세 끝과 같은 포즈) 로 서 있고 [추정: 0x1DDF70 이 파괴 때 애니를 0 초부터 재생],
	 * 부서지면 Brkb 파괴 act (조각) → 지연 #c3441d8c (3초) 뒤 Fade Time (1초) 동안 흐려지고 사라진다 (41 문서 §1.2).
	 */
	public devple.gow.engine.combat.EnemyFighter.StoneLook stoneLook() {
		return new devple.gow.engine.combat.EnemyFighter.StoneLook() {
			@Override
			public void turnToRock(devple.gow.engine.combat.EnemyFighter f, String freezeGo) {
				EffectSystem.Effect e = fx.play(freezeGo, EffectSystem.Anchor.of(f.world(new Matrix4f(), 1)), -1);
				if (e != null) {
					e.scale = 1f / Math.max(e.def.scale(), 1e-3f); // 소유자 행렬 (배율 포함) 을 그대로 쓴다 (0x19FA04)
					freezeFxShown++;
				}
				if (EffectSystem.LOG) {
					System.out.printf("석화 SCR_TurnToRock → %s %s%n", freezeGo, e != null ? "재생" : "없음");
				}
			}

			@Override
			public boolean statue(devple.gow.engine.combat.EnemyFighter f, String statueGo) {
				EffectSystem.Effect e = fx.play(statueGo, out -> {
					f.world(out, 1);
					return true;
				}, Float.MAX_VALUE);
				if (e == null) {
					if (EffectSystem.LOG) {
						System.out.printf("석상 %s 없음 → 적 몸 그대로%n", statueGo);
					}
					return false;
				}
				e.hold = true; // 맞을 때까지 act 0 초 자세
				e.scale = 1f / Math.max(e.def.scale(), 1e-3f); // 소유자 행렬 복사 (0x1E8A8C–0x1E8AB4)
				e.jointVisibility = true;
				f.statueMfx = statueMfx(e.def);
				statues.put(f, e);
				ParticleSystem.Emitter dust = stones.remove(f);
				if (dust != null) {
					ps.emitters().remove(dust);
				}
				statuesShown++;
				if (EffectSystem.LOG) {
					System.out.printf("석상 %s 생성 (Brkb %d, 타수 %d)%n", statueGo, f.statueBrkb(), f.statueHpMax());
				}
				return true;
			}

			@Override
			public void shatter(devple.gow.engine.combat.EnemyFighter f) {
				EffectSystem.Effect e = statues.get(f);
				if (e == null) {
					return;
				}
				String bn = String.format("/TweakTemplates/Brkb/%03d/", Math.max(f.statueBrkb(), 0));
				var dl = tw.rawHash(bn, BRKB_DELAY);
				float delay = dl.isPresent() ? Float.intBitsToFloat(dl.getAsInt()) : 3f;
				float fade = Math.max(tw.f(bn, "Fade Time", 1f), 0);
				var ph = tw.rawHash(bn, BRKB_PHYSICS);
				e.hold = false;
				e.setActTime(0); // 0x1DDF70: GO 애니를 0 초부터 (파편이 흩어지는 act)
				e.setLife(delay + fade);
				if (ph.isPresent() && (ph.getAsInt() & 1) != 0 && e.def.skeleton() != null) {
					e.debris = new BreakDebris(e.def.skeleton()); // Brkb +0x30 bit 0x1 (517 = 1): 조각 물리 (41 문서 §1.4)
					e.debrisSurface = surface;
				}
				e.setAnchor(EffectSystem.Anchor.of(f.world(new Matrix4f(), 1)));
				statuesBroken++;
				perFrame.add(new Runnable() {
					@Override
					public void run() {
						e.alpha = e.time() > delay && fade > 0 ? Math.max(0, 1 - (e.time() - delay) / fade) : 1;
						if (e.done()) {
							perFrame.remove(this);
							statues.remove(f, e);
						}
					}
				});
			}

			@Override
			public void unfreeze(devple.gow.engine.combat.EnemyFighter f) {
				EffectSystem.Effect e = statues.remove(f);
				if (e != null) {
					e.stop(); // 0x1E8AF8: 석상에 0x40C (제거)
				}
			}
		};
	}

	/**
	 * 석상 GO 의 CDV 재질 MFX: CDV_MINStonedBRK 는 공 없이 볼록 덩어리 하나 (재질 MFX 22 = R_MIN "Breakable Rock": goRockhitS,
	 * SND_SWORDHIT_STONE) [확실: 데이터] — 석상을 치면 피 대신 돌 조각·돌 소리.
	 */
	public static int statueMfx(devple.gow.tools.fx.EffectLibrary.EffectDef d) {
		if (d == null || d.skeleton() == null) {
			return 0;
		}
		var cdv = devple.gow.engine.combat.HitVolumes.cdvFor(d.wad(), d.skeleton());
		if (cdv == null) {
			return 0;
		}
		for (int m : cdv.materialFx) {
			if (m > 0) {
				return m;
			}
		}
		return 0;
	}

	/** Brkb #c3441d8c (+0x20, 파괴 뒤 페이드 지연) / #93191d85 (+0x30 bit 0x1 파편 물리) — 41 문서 §1.1. */
	private static final int BRKB_DELAY = 0xc3441d8c, BRKB_PHYSICS = 0x93191d85;

	/**
	 * PlayFX 템플릿 (sub_0019BBE0 / SCR_PlayEffect 0x19A720): Effect Name, Joint Name, 플래그 u16 +0x40
	 * (0x1 Particle Effect, 0x2 Looped, 0x8 Attached, 0x20/0x40 Left/Right Weapon, 0x80 Remove, 0x400 Blood Drop),
	 * Scale X/Y/Z (+0x18), Offset X/Y/Z (+0x24), Rot X/Y/Z (+0x30) [확실: 키·오프셋, 추정: 동작].
	 * Attached 면 매 프레임 관절을 따라가고, 아니면 발동 순간의 관절 위치에 남는다. Looped 는 기술이 바뀌면 끈다.
	 */
	public EffectSystem.Effect playFx(int template, Combatant c, Joints joints) {
		String n = String.format("/TweakTemplates/PlayFX/%03d/", template);
		String name = tw.s(n, "Effect Name", null);
		if (name == null) {
			return null;
		}
		ensureFor(name);
		boolean attachedFlag = flag(n, "Attached");
		boolean looped = flag(n, "Looped");
		boolean left = flag(n, "Left Weapon"), right = flag(n, "Right Weapon");
		// Remove (0x80): 같은 이름의 이펙트를 끈다 [추정: 0x19A98C 의 GO 제거 경로]
		if (flag(n, "Remove")) {
			List<EffectSystem.Effect> l = playFxLive.get(c);
			if (l != null) {
				l.removeIf(e -> {
					if (e.def.go().equalsIgnoreCase(name) || ("go" + e.def.object()).equalsIgnoreCase(name)) {
						e.stop();
						return true;
					}
					return e.done();
				});
			}
			return null;
		}
		// Left/Right Weapon (0x20/0x40): 블레이드 로케이터 lWeapOH / rWeapOH 기준 [추정], 아니면 Joint Name
		String jn = left ? "lWeapOH" : right ? "rWeapOH" : tw.s(n, "Joint Name", null);
		float sx = tw.f(n, "Scale X", 1f), sy = tw.f(n, "Scale Y", sx), sz = tw.f(n, "Scale Z", sx);
		if (!(sx > 0 && sx < 50)) {
			sx = sy = sz = 1;
		}
		Vector3f off = new Vector3f(tw.f(n, "Offset X", 0), tw.f(n, "Offset Y", 0), tw.f(n, "Offset Z", 0)).mul(Magic.UNIT);
		// Rot X/Y/Z (+0x30, 도): 관절 기준 로컬 회전 (FirePath 017/018 = Y 90°) [확실: 키·값, 추정: 순서 X→Y→Z]
		float rx = (float) Math.toRadians(tw.f(n, "Rot X", 0)), ry = (float) Math.toRadians(tw.f(n, "Rot Y", 0)),
				rz = (float) Math.toRadians(tw.f(n, "Rot Z", 0));
		float ux = sx, uy = sy / sx, uz = sz / sx;
		EffectSystem.Anchor live = out -> {
			if (jn == null || !joints.world(jn, out)) {
				if (c == null) {
					return true;
				}
				out.translation(c.owner().position()).rotateY(c.owner().yaw()); // 관절 없음 → 소유자 원점
			}
			out.translate(off).rotateXYZ(rx, ry, rz).scale(1, uy, uz);
			return true;
		};
		EffectSystem.Effect e = fx.play(name, attachedFlag ? live : snapshot(live), -1);
		if (e != null) {
			e.scale = ux;
			if (c != null) {
				List<EffectSystem.Effect> l = playFxLive.computeIfAbsent(c, k -> new ArrayList<>());
				l.removeIf(EffectSystem.Effect::done);
				l.add(e);
			}
			if (looped && c != null) {
				int serial = c.moveSerial();
				perFrame.add(new Runnable() {
					@Override
					public void run() {
						if (c.moveSerial() != serial || !c.active()) {
							e.stop();
							perFrame.remove(this);
						}
					}
				});
			}
		}
		// Blood Drop (0x400): 관절 아래 바닥 피 자국 (0x19B798 → sub_00221A50 → 0x1F9278) [추정]
		if (flag(n, "Blood Drop")) {
			Matrix4f m = new Matrix4f();
			live.world(m);
			bloodDrop(m.getTranslation(new Vector3f()), 1f);
		}
		return e;
	}

	/** PlayFX/Concussion 비트 키 (값의 하위 바이트, 06 문서 §TWK). */
	private boolean flag(String node, String key) {
		return (tw.raw(node, key).orElse(0) & 0xFF) != 0;
	}

	private static EffectSystem.Anchor snapshot(EffectSystem.Anchor a) {
		Matrix4f m = new Matrix4f();
		a.world(m);
		return EffectSystem.Anchor.of(m);
	}

	/**
	 * Concussion 템플릿 이펙트 (sub_00191720): Effect Name 을 Joint Name 위치에, 반경 Start→End (TWK m) 를 Duration 동안.
	 * Scale X/Y/Z 플래그(비트 0x1/0x2/0x4) 면 이펙트를 반경만큼 늘린다 — 메시 기준 반경 27 (medheadNuke) 으로 맞춤 [근사].
	 * 이펙트는 Duration 뒤 0.5초 페이드 [근사: 원작은 Concussion 객체 수명].
	 */
	/** 범위 피해 받는 쪽 (게임): 주인 (null = 영웅 마법), 중심, 반경, 템플릿 피해 (주인 배율 전). */
	public interface ConcussionDamage {
		void apply(Combatant owner, Vector3f center, float radius, int damage);
	}

	public ConcussionDamage concussionDamage;

	public void concussion(int template, Joints joints, Vector3f at) {
		concussion(template, joints, at, null);
	}

	/**
	 * @param owner 이 범위 피해를 낸 전투원 (0x191720: 피해 = 템플릿 "Damage" 하위 바이트 × 주인 AttackerScale — 영웅이면 Damage Mult,
	 *              적이면 AI Damage Mult (난이도)). null = 영웅 (마법·메두사 머리)
	 */
	public void concussion(int template, Joints joints, Vector3f at, Combatant owner) {
		String n = String.format("/TweakTemplates/Concussion/%03d/", template);
		String name = tw.s(n, "Effect Name", null);
		if (name == null) {
			return;
		}
		ensureFor(name);
		String jn = tw.s(n, "Joint Name", null);
		Matrix4f m = new Matrix4f();
		if (joints == null || !joints.world(jn, m)) {
			m.translation(at != null ? at : heroPos).rotateY(heroYaw);
		}
		// 관절 회전은 버리고 위치만 (충격파는 수평) [추정: No Orient 0x8 미설정 템플릿도 수평으로 보임]
		Vector3f p = m.getTranslation(new Vector3f());
		float r0 = tw.f(n, "Start Radius", 1f), r1 = tw.f(n, "End Radius", r0), dur = Math.max(tw.f(n, "Duration", 0.3f), 0.05f);
		boolean scaled = (tw.raw(n, "Scale X").orElse(0) & 0xFF) != 0;
		int dmg = tw.raw(n, "Damage").orElse(0) & 0xFF;
		if (dmg > 0 && concussionDamage != null) {
			concussionDamage.apply(owner, p, Math.max(r0, r1) * Magic.UNIT, dmg); // [근사: 반경 시간 변화·밀어내기 (Ground/Air Impulse) 없음, 터지는 순간 한 번]
		}
		EffectSystem.Effect e = fx.play(name, EffectSystem.Anchor.at(p, heroYaw), dur + 0.5f);
		if (e == null) {
			return;
		}
		float[] t = {0};
		perFrame.add(new Runnable() {
			@Override
			public void run() {
				t[0] += 1 / 60f;
				float u = Math.min(1, e.time() / dur);
				if (scaled) {
					e.scale = Math.max(r0 + (r1 - r0) * u, 0.05f) * Magic.UNIT / 27f;
				}
				e.alpha = e.time() > dur ? Math.max(0, 1 - (e.time() - dur) / 0.5f) : 1;
				if (e.done()) {
					perFrame.remove(this);
				}
			}
		});
	}

	/**
	 * 적중 (0x2032C0 → sub_00221810): ① 공격 MFX (Attack +0x16, flags 0x2) 와 ② 피격 구 재질의 SheetFX (CDV 재질 엔트리 +0x2C,
	 * exe 속성 오프셋 표 0x3376FC; 0x203450) MFX 의 +0x28 GO 를 접촉점에, 공격자→피격자 방향으로 1회. MFX "Drop Blood" (bit 0x2) 면
	 * 바닥 피 자국 (sub_00221A50 → 0x1F9278). 영웅 기술의 520 "Hit Effect" 는 +0x28 이 비어 피는 ② 에서만 나온다.
	 * 피격 구를 모르면 (히트박스 폴백) 피격자 몸 구의 SheetFX, 그것도 없으면 500 Flesh / 501 Hero Flesh [근사].
	 */
	private void hitBlood(CombatFile.Attack a, Vector3f from, Hittable victim, boolean victimHero, boolean primary) {
		int mfx = mfxOf(victim, victimHero);
		if (tw.s(String.format("/TweakTemplates/MFX/%03d/", mfx), "Instance Name", null) == null) {
			mfx = victimHero ? 501 : 500; // 템플릿이 든 WAD (510 Zombie Flesh 는 레벨 WAD) 가 없으면 기본 살 [근사]
		}
		Vector3f p = new Vector3f(victim.position()).add(0, 40, 0);
		var hv = victim.hitVolumes();
		Vector3f cp = hv != null ? hv.lastHitPoint() : null;
		if (cp != null && cp.distance(victim.position()) < 200) {
			p.set(cp);
		}
		Vector3f d = new Vector3f(p).sub(from);
		float yaw = (float) Math.atan2(-d.x, -d.z);
		String node = String.format("/TweakTemplates/MFX/%03d/", mfx);
		String go = tw.sHash(node, MFX_BLOOD, mfx == 500 || mfx == 501 ? "goSklBlood" : null);
		// 재질 MFX 이펙트 생성률 배율 = 0x221810 f12 → GO 파티클 컴포넌트 +0xB4 (sub_00227D80): 주 대상 1, 그 외 0.125 [확실: 값·경로]
		EffectSystem.Effect blood = go == null ? null : fx.play(go, EffectSystem.Anchor.at(p, yaw));
		if (blood != null) {
			blood.rateScale = primary ? 1f : 0.125f;
		} else if (mfx >= 500) {
			burst("blood", p);
		}
		lastHitScale = primary ? 1f : 0.125f;
		lastHitMfx = mfx;
		lastHitGo = go;
		if (EffectSystem.LOG) {
			System.out.printf("적중 MFX %d (%s) → %s ×%.3f%n", mfx, hv != null && hv.lastHitFx() > 0 ? "피격 구" : "기본", go, lastHitScale);
		}
		if (primary && flag(node, "Drop Blood")) {
			bloodDrop(p, 1f);
		}
		// ① 공격 MFX 는 항상 배율 1 (0x203428 f12 = 1.0)
		if (a != null && a.mfx() > 0 && (a.flags() & 2) != 0) {
			String g2 = tw.sHash(String.format("/TweakTemplates/MFX/%03d/", a.mfx()), MFX_BLOOD, null);
			if (g2 != null && !g2.equalsIgnoreCase(go)) {
				fx.play(g2, EffectSystem.Anchor.at(p, yaw));
			}
		}
	}

	/** 마지막 적중의 MFX 번호·GO (테스트). */
	public int lastHitMfx = -1;
	/** 마지막 적중 재질 MFX 의 생성률 배율 (테스트). */
	public float lastHitScale = 1;
	public String lastHitGo;

	/** 피격 재질 MFX: 맞은 구의 SheetFX → 몸 구 SheetFX → 500/501. */
	public static int mfxOf(Hittable victim, boolean victimHero) {
		if (victim instanceof devple.gow.engine.combat.EnemyFighter ef && ef.statueShown() && ef.statueMfx > 0) {
			return ef.statueMfx; // 석상이 맞는다 (원작은 석상 GO 의 CDV 와 접촉)
		}
		var hv = victim.hitVolumes();
		int m = hv != null ? hv.lastHitFx() : -1;
		if (m <= 0 && hv != null) {
			m = hv.bodyFx();
		}
		return m > 0 ? m : victimHero ? 501 : 500;
	}

	/**
	 * 바닥 피 자국 (/GlobGame/BloodSpots, 0x1F9278): "Particle System 1 Light" (goBloodSpot) 의 PTC 로 Nominal Particles 개를
	 * 접촉점 아래 바닥에 뿌린다 (Offset 위, Fall Time 동안 남음). 원작은 Cone/Impulse/Gravity 로 떨어뜨려 바닥에 붙이는 전용 코드 —
	 * 엔진은 바닥 위치에서 0.1초 분출(속력 ¼) [근사].
	 */
	public void bloodDrop(Vector3f at, float scale) {
		if (surface == null) {
			return;
		}
		float[] fl = surface.floor(new Vector3f(at).add(0, 4, 0), 400);
		if (fl == null) {
			return;
		}
		// 원작 0x1F8990: 충돌 레이 (0x11E190) 의 면 법선으로 피 자국 평면 축을 만든다 [확실]
		float[] hit = surface.cast(new Vector3f(at).add(0, 4, 0), new Vector3f(at).add(0, -400, 0));
		Vector3f nrm = hit != null && hit.length >= 6 ? new Vector3f(hit[3], hit[4], hit[5]) : new Vector3f(0, 1, 0);
		if (nrm.lengthSquared() < 1e-6f || nrm.y < 0.2f) {
			nrm.set(0, 1, 0);
		}
		nrm.normalize();
		String n = "/GlobGame/BloodSpots/";
		String go = tw.s(n, "Particle System 1 Light", "goBloodSpot");
		float count = Math.min(tw.f(n, "Nominal Particles", 36f) * scale, 200);
		float off = tw.f(n, "Offset", 0.1f) * Magic.UNIT;
		Vector3f p = hit != null && Math.abs(hit[1] - fl[0]) < 8 ? new Vector3f(hit[0], hit[1], hit[2]).fma(off, nrm)
				: new Vector3f(at.x, fl[0] + off, at.z);
		EffectSystem.Effect e = fx.play(go, EffectSystem.Anchor.of(new Matrix4f().translation(p)), 0.1f);
		if (e != null) {
			e.planeNormal.set(nrm);
			e.rateOverride = count / 0.1f;
			e.speedScale = 0.25f;
			bloodSpots++;
		}
	}

	/** 바닥 피 자국 횟수 (테스트). */
	public int bloodSpots;

	/** 발소리 (GameScene): 발밑 SheetFX → MFX "Run Dust Name" (+0x34, 예 Rock → goRunDust) 를 발 위치에 (sub_00221A90) [달리기만]. */
	public void footstep(int sheetFx, Vector3f at, float yaw) {
		String go = sheetFx > 0 ? tw.s(String.format("/TweakTemplates/MFX/%03d/", sheetFx), "Run Dust Name", null) : null;
		if (go != null && go.startsWith("go")) {
			fx.play(go, EffectSystem.Anchor.at(at, yaw), 0.6f);
		}
	}

	/**
	 * 착지 (sub_00221EA8, 0x1FE490/0x1FE518): 발밑 SheetFX 의 MFX "Jump Land Effect" (+0x2C, 예 Rock → goRockLand) 를 발 위치에 1회.
	 * 소리 (Foot Sound 레지스터 3 LAND) 는 GameScene 이 낸다.
	 */
	public EffectSystem.Effect land(int sheetFx, Vector3f at, float yaw) {
		String go = sheetFx > 0 ? tw.sHash(String.format("/TweakTemplates/MFX/%03d/", sheetFx), MFX_LAND, null) : null;
		if (EffectSystem.LOG) {
			System.out.printf("착지 SheetFX %d → %s%n", sheetFx, go);
		}
		if (go == null || !go.startsWith("go")) {
			return null;
		}
		return fx.play(go, EffectSystem.Anchor.at(at, yaw), -1);
	}

	/**
	 * 무기-월드 베기 (0x202248 → sub_00221918): 공격 창 안의 공격 부위 구가 이전→현재 프레임 사이 월드 면을 지나면,
	 * 그 면 SheetFX 의 MFX "Slice Hit Name" (+0x30) GO 를 충돌점에 면 법선 방향으로, "Slice Hit Sound" (+0x3C) 를 1회.
	 * 같은 (기술, 공격 번호) 는 한 번 [추정: 원작 접촉 기록 +0xAC 와 같은 규칙].
	 *
	 * @return 재생한 GO 이름 (없으면 null)
	 */
	public String weaponWorld(Combatant c, java.util.function.BiConsumer<String, Vector3f> sound) {
		if (surface == null || c == null || !c.active() || c.move() == null || c.hitVolumes() == null) {
			sliced.clear();
			return null;
		}
		float t = c.progress();
		String played = null;
		for (CombatFile.Attack a : c.move().attacks()) {
			if (t < a.start() || t > a.end() || a.damage() <= 0) {
				continue;
			}
			long key = ((long) c.moveSerial() << 16) | a.index();
			if (sliced.contains(key)) {
				continue;
			}
			if (bladeSliceNative && (a.part() == 2 || a.part() == 3 || a.part() == 11)) {
				continue; // 블레이드는 사슬 벽 질의 (0x1FAFC8 → 0x202248, {@link #bladeSpark}) 가 맡는다
			}
			for (float[] sw : c.hitVolumes().sweeps(a.part())) {
				Vector3f p0 = new Vector3f(sw[0], sw[1], sw[2]), p1 = new Vector3f(sw[3], sw[4], sw[5]);
				if (p0.distanceSquared(p1) < 1e-4f) {
					continue;
				}
				// 구 스윕 근사: 이동 방향으로 반지름만큼 늘린 선분 (원작은 구-시트 접촉 0x13C400)
				Vector3f ext = new Vector3f(p1).sub(p0).normalize().mul(sw[6]).add(p1);
				float[] h = surface.cast(p0, ext);
				if (h == null) {
					continue;
				}
				if (EffectSystem.LOG) {
					System.out.printf("무기-월드 공격#%d 부위 %d → 면 SheetFX %d at (%.0f, %.0f, %.0f)%n", a.index(), a.part(), (int) h[6], h[0], h[1], h[2]);
				}
				sliced.add(key);
				int sfx = (int) h[6];
				String node = String.format("/TweakTemplates/MFX/%03d/", sfx);
				String go = sfx > 0 ? tw.sHash(node, MFX_SLICE, null) : null;
				Vector3f hp = new Vector3f(h[0], h[1], h[2]);
				if (go != null && go.startsWith("go")) {
					Vector3f nrm = new Vector3f(h[3], h[4], h[5]);
					Matrix4f m = new Matrix4f().translation(hp);
					if (nrm.lengthSquared() > 1e-6f) {
						m.rotateTowards(nrm.normalize(), Math.abs(nrm.y) > 0.9f ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0));
					}
					if (fx.play(go, EffectSystem.Anchor.of(m)) != null) {
						played = go;
					}
				}
				String snd = sfx > 0 ? tw.sHash(node, MFX_SLICE_SOUND, null) : null;
				if (snd != null && snd.toUpperCase().startsWith("SND_") && sound != null) {
					sound.accept(snd.toUpperCase(), hp);
				}
				break;
			}
		}
		return played;
	}

	/**
	 * 블레이드 부위 (2 / 3 / 11) 의 무기-월드 베기를 {@link #weaponWorld} 근사에서 빼고 {@link #bladeSpark} 로 낸다.
	 * 원작 0x202248 의 호출자는 0x1FAFC8 (혼돈의 블레이드, 손 밖) · 0x1DB788 (아르테미스의 검 부위 10) · 0x1C3100 (적) 뿐이다.
	 */
	public boolean bladeSliceNative;
	private final EffectSystem.Effect[] sparks = new EffectSystem.Effect[2];
	private final int[] sparkMfx = new int[2];
	/** 블레이드 벽 불꽃을 새로 낸 횟수 (테스트). */
	public int bladeSparks;

	/**
	 * 블레이드 벽 충돌 불꽃 (0x1FAFC8 → 0x202248, 블레이드 슬롯 +0x24 이펙트 핸들 / +0x2C MFX 번호) [확실: 분기]:
	 * 슬롯의 이전 이펙트가 아직 살아 있고 같은 MFX 면 그 GO 를 새 충돌점으로 옮기기만 한다 (새 소리 없음).
	 * 아니면 면 SheetFX 의 MFX "Slice Hit Name" (+0x30) GO 를 새로 내고 (0x221918) "Slice Hit Sound" (+0x3C) 를 낸다 (0x221738).
	 * → 블레이드가 벽에 닿아 있는 동안 불꽃이 끝날 때마다 다시 난다.
	 *
	 * @return 새로 낸 GO 이름 (옮기기만 했거나 없으면 null)
	 */
	public String bladeSpark(int side, int sheetFx, Vector3f at, Vector3f normal, java.util.function.BiConsumer<String, Vector3f> sound) {
		Matrix4f m = new Matrix4f().translation(at);
		Vector3f nrm = new Vector3f(normal);
		if (nrm.lengthSquared() > 1e-6f) {
			m.rotateTowards(nrm.normalize(), Math.abs(nrm.y) > 0.9f ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0));
		}
		EffectSystem.Effect prev = sparks[side];
		if (prev != null && !prev.done() && sparkMfx[side] == sheetFx) {
			prev.setAnchor(EffectSystem.Anchor.of(m)); // 0x20232C: GO 행렬만 갱신 (Y = 면 법선)
			return null;
		}
		sparks[side] = null;
		sparkMfx[side] = sheetFx;
		if (sheetFx <= 0) {
			return null;
		}
		String node = String.format("/TweakTemplates/MFX/%03d/", sheetFx);
		String go = tw.sHash(node, MFX_SLICE, null);
		String played = null;
		if (go != null && go.startsWith("go")) {
			sparks[side] = fx.play(go, EffectSystem.Anchor.of(m));
			if (sparks[side] != null) {
				played = go;
				bladeSparks++;
			}
		}
		String snd = tw.sHash(node, MFX_SLICE_SOUND, null);
		if (snd != null && snd.toUpperCase().startsWith("SND_") && sound != null) {
			sound.accept(snd.toUpperCase(), new Vector3f(at));
		}
		if (EffectSystem.LOG) {
			System.out.printf("블레이드 벽 %d SheetFX %d → %s at (%.0f, %.0f, %.0f)%n", side, sheetFx, go, at.x, at.y, at.z);
		}
		return played;
	}

	/** 예전 호출 호환: 지연 = act 길이, 파편 물리 없음. */
	public EffectSystem.Effect breakObject(String goName, Matrix4f world, float fadeTime) {
		return breakObject(goName, world, -1, fadeTime, false);
	}

	/**
	 * 부서지는 물체 (41 문서): 물체 GO 자체를 이펙트로 재생 — 오브젝트 ANM 첫 act (파편이 흩어지는 애니) + 메시 + FXC 먼지 + SEM 소리
	 * (pot: SND_POTTERYSMASH). 원작 0x1DDF70 이 GO 애니 컴포넌트를 0초부터 재생(sub_00100578)하고 이벤트 1018 을 보낸다.
	 * 파괴 뒤 경과 시간 (+0x24, 0x1DE278) 이 Brkb #c3441d8c (지연, +0x20) ~ 지연 + Fade Time 이면 페이드 (0x1DE6B8),
	 * 지연 + Fade Time 을 넘으면 제거 [확실: 시점, 추정: 페이드 = 불투명도].
	 * act 의 u8 채널 = 관절 가시성 (온전한 몸 숨김·조각 보임), Brkb bit 0x1 이면 조각 물리 ({@link BreakDebris}).
	 *
	 * @param world   인스턴스 월드 행렬 (열벡터, {@code GoInstance.matrix()} 전치)
	 * @param delay   페이드 시작 (초), &lt; 0 이면 act 길이
	 * @param physics Brkb +0x30 bit 0x1 (#93191d85)
	 */
	public EffectSystem.Effect breakObject(String goName, Matrix4f world, float delay, float fadeTime, boolean physics) {
		EffectSystem.Effect e = fx.play(goName, EffectSystem.Anchor.of(world), -1);
		if (e == null) {
			return null;
		}
		float dur = e.def.anim() != null ? Math.max(e.def.anim().duration, 0.1f) : 1f;
		float start = delay >= 0 ? delay : dur;
		float fade = Math.max(fadeTime, 0);
		e.setLife(start + fade);
		e.scale = 1f / Math.max(e.def.scale(), 1e-3f); // 인스턴스 스케일은 world 에 이미 들어 있다
		e.jointVisibility = true; // u8 트랙: 온전한 몸 숨기고 조각 보이기 (doorVis, barrelVisDisapear, breakgroup1/2)
		if (physics && e.def.skeleton() != null) {
			e.debris = new BreakDebris(e.def.skeleton());
			e.debrisSurface = surface;
		}
		perFrame.add(new Runnable() {
			@Override
			public void run() {
				e.alpha = e.time() > start && fade > 0 ? Math.max(0, 1 - (e.time() - start) / fade) : 1;
				if (e.done()) {
					perFrame.remove(this);
				}
			}
		});
		return e;
	}

	/** 마법 WAD 가 필요한 이름이면 해당 단계 WAD 를 등록 (원작은 WadPlan 이 마법 WAD 를 상주시킨다). */
	private void ensureFor(String name) {
		String k = name.toLowerCase();
		if (k.contains("medhead") || k.contains("eyebeam") || k.contains("mewh")) {
			stage("R_MEDHD", stages[4]);
		} else if (k.contains("bolt") && !k.contains("singlebolt")) {
			stage("R_THBLT", stages[5]);
		} else if (k.contains("dedred") || k.contains("deadhit") || k.contains("deaddie")) {
			stage("R_RDEAD", stages[6]);
		} else if (k.contains("lightning")) {
			stage("R_LGHTN", stages[3]);
		}
	}

	private final int[] stages = new int[8];

	/**
	 * 마법 효과 출력 (소리는 sound 로, 이펙트·Concussion 은 이 객체로). 게임 씬·연습 씬이 {@link Magic#setSink} 에 넘긴다.
	 */
	public Magic.Sink sink(java.util.function.BiConsumer<String, Vector3f> sound) {
		return new Magic.Sink() {
			@Override
			public void sound(String name, Vector3f at) {
				recentSounds.put(name, 0.3f);
				sound.accept(name, at);
			}

			@Override
			public void effect(String name, Vector3f at) {
				MagicFx.this.effect(name, at);
			}

			@Override
			public void concussion(int template, Vector3f at) {
				MagicFx.this.concussion(template, heroJoints, at);
			}

			@Override
			public void screenFlash(int fogTemplate, int bright, int dark, int flashFreq, float duration) {
				screen.start(fogTemplate, bright, dark, flashFreq, duration);
			}
		};
	}

	/** 원작 GO 이펙트 이름 → 1회 재생. 데이터가 없으면 근사 폭발 (0.1초 생성). */
	public void effect(String name, Vector3f at) {
		ensureFor(name);
		if (name.equalsIgnoreCase("goEyeBeam")) {
			return; // 광선은 update 가 유지 (beamOn)
		}
		if (name.equalsIgnoreCase("goLightningSingleBolt")) {
			// 하늘에서 대상으로: 메시가 원점에서 −Y 로 246 내려온다 → 원점을 대상 위 230 에 [추정]
			if (fx.play(name, EffectSystem.Anchor.at(new Vector3f(at).add(0, 230, 0), heroYaw), 0.25f) != null) {
				return;
			}
		}
		if (name.equalsIgnoreCase("goMedHeadFlash")) {
			flash(at);
			return;
		}
		if (!name.equals("shatter") && fx.play(name, EffectSystem.Anchor.at(at, heroYaw), -1) != null) {
			return;
		}
		String k = switch (name) {
			case "goBoltHit" -> "boltHit";
			case "goDeadHit", "goDeadDie" -> "soulHit";
			case "goMedHeadFlash" -> "flash";
			case "shatter" -> "shatter";
			default -> null;
		};
		if (k != null) {
			burst(k, new Vector3f(at).add(0, k.equals("shatter") ? 30 : 0, 0));
		}
	}

	/**
	 * 메두사 섬광은 손 위치·조준 방향에서. {@link Magic.Sink#effect} 의 위치는 몸 원점이라 손 관절로 바꾼다.
	 */
	public void flash(Vector3f bodyPos) {
		stage("R_MEDHD", stages[4]);
		Matrix4f m = hand(bodyPos);
		if (fx.play("goMedHeadFlash", EffectSystem.Anchor.of(m)) == null) {
			burst("flash", m.getTranslation(new Vector3f()));
		}
	}

	private Matrix4f hand(Vector3f bodyPos) {
		Matrix4f j = new Matrix4f();
		Vector3f p = heroJoints.world("rWeapIH", j) ? j.getTranslation(new Vector3f()) : new Vector3f(bodyPos).add(0, 50, 0);
		return new Matrix4f().translation(p).rotateY(aimYaw);
	}

	private void burst(String k, Vector3f at) {
		ParticleSystem.Emitter e = ps.add(defs.get(k), at.x, at.y, at.z);
		burstEmitters.add(e);
		bursts.add(new float[]{0.1f});
	}

	/** 매 틱: 투사체·영혼 이펙트, 메두사 광선, 포세이돈 루프, 석상 먼지. */
	public void update(Magic m, Vector3f heroPos, float heroYaw, List<? extends Hittable> enemies, float dt, Vector3f camEye) {
		this.heroPos.set(heroPos);
		this.heroYaw = heroYaw;
		for (int id = 3; id <= 6; id++) {
			stages[id] = m.stage(id);
		}
		aimYaw = m.medusa.aimYaw();
		List<Object> live = new ArrayList<>();
		String an = "/TweakTemplates/Arrow/002/";
		for (ZeusBolt.Bolt b : m.zeus.bolts()) {
			live.add(b);
			if (!attached.containsKey(b)) {
				stage("R_THBLT", stages[5]);
				List<EffectSystem.Effect> l = new ArrayList<>();
				EffectSystem.Anchor a = out -> {
					out.translation(b.pos);
					if (b.vel.lengthSquared() > 1e-4f) {
						out.rotateTowards(new Vector3f(b.vel).normalize(), new Vector3f(0, 1, 0));
					}
					return !b.dead;
				};
				addIf(l, fx.play(tw.s(an, "GO Name", "goBolt"), a));
				addIf(l, fx.play(tw.s(an, "Trail Name", "goStdBolt"), a));
				attached.put(b, l);
				if (l.isEmpty()) {
					trail(b, "bolt", b.pos);
				}
			} else if (trails.containsKey(b)) {
				trail(b, "bolt", b.pos);
			}
		}
		for (HadesSouls.Soul s : m.hades.souls()) {
			live.add(s);
			if (!attached.containsKey(s)) {
				stage("R_RDEAD", stages[6]);
				List<EffectSystem.Effect> l = new ArrayList<>();
				addIf(l, fx.play("goDedRed", out -> {
					out.translation(s.pos);
					if (s.vel.lengthSquared() > 1e-4f) {
						out.rotateTowards(new Vector3f(s.vel).normalize(), new Vector3f(0, 1, 0));
					}
					return !s.dead;
				}));
				attached.put(s, l);
				if (l.isEmpty()) {
					trail(s, "soul", s.pos);
				}
			} else if (trails.containsKey(s)) {
				trail(s, "soul", s.pos);
			}
		}
		attached.entrySet().removeIf(en -> {
			if (!live.contains(en.getKey())) {
				en.getValue().forEach(EffectSystem.Effect::stop);
				ParticleSystem.Emitter t = trails.remove(en.getKey());
				if (t != null) {
					ps.emitters().remove(t);
				}
				return true;
			}
			return false;
		});
		medusaBeam(m, heroPos);
		updateHead(m);
		if (!charge.isEmpty() && heroCombat != null && (heroCombat.moveSerial() != chargeSerial || !heroCombat.active())) {
			hideThunderbolt();
		}
		lightning(m, heroPos, heroYaw, dt);
		screen.update(m.lightningScript(), m.stage(3), dt);
		godMode(heroCombat != null && heroCombat.owner() != null && heroCombat.owner().godMode());
		normalBlades();
		artemisBlade();
		recentSounds.replaceAll((k, v) -> v - dt);
		recentSounds.values().removeIf(v -> v <= 0);
		for (Hittable h : enemies) {
			// 회색 먼지는 석상 GO 가 없는 적만 [근사] — 원작에는 없다. 석상 GO 가 있으면 석화 순간 GO + 석상이 대신한다
			boolean st = h.petrified() && !(h instanceof devple.gow.engine.combat.EnemyFighter ef && ef.statueGo() != null
					&& fx.library().resolve(ef.statueGo()) != null);
			ParticleSystem.Emitter e = stones.get(h);
			if (st && e == null) {
				stones.put(h, e = ps.add(defs.get("stone"), 0, 0, 0));
			}
			if (e != null) {
				e.pos.set(h.position()).add(0, 35, 0);
				if (!st) {
					ps.emitters().remove(e);
					stones.remove(h);
				}
			}
		}
		for (int i = bursts.size() - 1; i >= 0; i--) {
			if ((bursts.get(i)[0] -= dt) <= 0) {
				burstEmitters.get(i).active = false;
				ps.emitters().remove(burstEmitters.get(i));
				burstEmitters.remove(i);
				bursts.remove(i);
			}
		}
		for (Runnable r : new ArrayList<>(perFrame)) {
			r.run();
		}
		fx.update(dt, camEye);
		ps.update(dt, camEye);
	}

	private void medusaBeam(Magic m, Vector3f heroPos) {
		if (m.medusa.beamOn()) {
			if (beamFx == null && beam == null) {
				stage("R_MEDHD", stages[4]);
				String name = tw.s("/Player/MedusaHead/", "Stage " + stages[4] + " Effect", "goEyeBeam");
				// 원작: 영웅 관절 0x3368C8 (= rWeapIH, 머리와 같은 관절) 에 부착 (0x1969F0 → sub_00131460) [확실].
				// 엔진은 그 관절 위치 + 조준 방향 [근사: 관절 회전 대신 조준 yaw]
				beamFx = fx.play(name, out -> {
					out.set(hand(this.heroPos));
					return true;
				});
				if (beamFx == null) {
					beam = ps.add(defs.get("beam"), 0, 0, 0);
				}
			}
			if (beam != null) {
				float ay = m.medusa.aimYaw();
				float fx0 = -(float) Math.sin(ay), fz = -(float) Math.cos(ay);
				beam.pos.set(heroPos.x + fx0 * 15, heroPos.y + 50, heroPos.z + fz * 15);
				beam.dir.set(fx0, 0, fz);
			}
		} else {
			if (beamFx != null) {
				beamFx.stop();
				beamFx = null;
			}
			if (beam != null) {
				ps.emitters().remove(beam);
				beam = null;
			}
		}
	}

	/**
	 * 포세이돈 (SCR_Lightning 0x1948F0) [확실: 식]: 시작 때 goLightningAttackMain·goLightningRadius 를 만든다.
	 * 매 프레임 AttackMain = 단위 회전 + 영웅 위치 (0x194C08: 회전 없음), Radius = 같은 행렬의 축을 배율 s 로 (0x195500).
	 * s (0x3368B8, 시작 1) += (f23 − s)·0.05 (프레임당), f23 = 연타로 늘어난 사거리 (TWK 미터, {@link Magic#lightningReach()}).
	 * 타이머(0x3368A8, 시작 1)가 (1/60)·연타율·"From Above Bolt Freq"(전역 short 0x29D780 = 20) 씩 줄어 0 이하면 1 로 되돌리고
	 * goStrikeGrp 를 반경 (1 − rnd²)·f23·16, 각 rnd·2π 에 1회 (슬롯 4개 순환, 재사용 슬롯의 이전 GO 는 지움).
	 * 연타율 0 (연타 없음·1단계) 이면 낙뢰가 없다.
	 */
	private void lightning(Magic m, Vector3f heroPos, float heroYaw, float dt) {
		if (m.lightningOn()) {
			if (lightningFx == null) {
				stage("R_LGHTN", stages[3]);
				radiusScale = 1;
				lightningFx = fx.play("goLightningAttackMain", out -> {
					out.translation(this.heroPos);
					return true;
				});
				radiusFx = fx.play("goLightningRadius", out -> {
					out.translation(this.heroPos).scale(radiusScale);
					return true;
				});
				strikeTimer = 1;
			}
			String n = "/Player/Lightning/";
			float mash = m.mashRate();
			float reach = m.lightningReach();
			radiusScale += (reach - radiusScale) * (1 - (float) Math.pow(1 - 0.05, dt * 60));
			float freq = Math.max(0, tw.i16(n, "From Above Bolt Freq", 20));
			strikeTimer -= dt * mash * freq;
			if (strikeTimer <= 0) {
				strikeTimer = 1;
				float u = rnd.nextFloat();
				float r = (1 - u * u) * reach * Magic.UNIT;
				float ang = rnd.nextFloat() * (float) Math.PI * 2;
				Vector3f p = new Vector3f(heroPos).add((float) Math.sin(ang) * r, 0, (float) Math.cos(ang) * r);
				if (surface != null) {
					float[] fl = surface.floor(new Vector3f(p).add(0, 60, 0), 300);
					if (fl != null) {
						p.y = fl[0];
					}
				}
				EffectSystem.Effect prev = strikeSlots[strikeSlot];
				if (prev != null) {
					prev.stop();
				}
				// 행렬 = 영웅 행렬 (단위 회전) + 오프셋 → 회전 없음
				strikeSlots[strikeSlot] = fx.play("goStrikeGrp", EffectSystem.Anchor.at(p, 0));
				if (strikeSlots[strikeSlot] != null) {
					strikes++;
				}
				strikeSlot = (strikeSlot + 1) & 3;
			}
		} else {
			if (lightningFx != null) {
				lightningFx.stop();
				lightningFx = null;
			}
			if (radiusFx != null) {
				radiusFx.stop();
				radiusFx = null;
			}
		}
	}

	/** 포세이돈 화면 효과 (0x195BA8 / 0x195C90): 안개 대체·카메라 필터 번호. */
	public final LightningScreen screen = new LightningScreen(tw);

	/**
	 * 신들의 분노 GO (0x1DAEC0 생성 / 0x1DAB08 켬 / 0x1DABF8 끔, 0x1DAD18 이 God Mode 진입·해제에 부른다) [확실: 코드].
	 * 영웅 초기화 0x1DAEC0 이 갑옷 GO 8개를 만들어 숨기고 관절 번호를 +0x14.. 에 둔다. 진입 0x1DAB08: 8개를 켜고 (vtable +0x30)
	 * sub_00131460 으로 관절에 부착, goStrikeGrp (+0x44) · goFlashGrp (+0x48) 를 한 번 생성 (둘 다 act 1.07초 1회, 행렬을 쓰는 코드 없음).
	 * 해제 0x1DABF8: 8개 분리·숨김, 남아 있는 Strike/Flash 제거.
	 */
	public static final String[][] GOD_ARMOR = {
			{"goBreastPlate", "vertebrae4"}, {"goHelmet", "head"}, {"goShoulderGuardR", "rHumerus"}, {"goBracerR", "rRadius"},
			{"goGrieveR", "rTibia"}, {"goShoulderGuardL", "lHumerus"}, {"goBracerL", "lRadius"}, {"goGrieveL", "lTibia"}};
	private final List<EffectSystem.Effect> godFx = new ArrayList<>();
	private boolean godOn;

	/**
	 * 블레이드 god 판 (0x1FA9A0, 해제 0x1FA9D8 = 보통 판): 0x1FAA10(블레이드, GO 이름, 궤적·사슬·사슬 빛 머티리얼).
	 * god 판 = "goMAIgodblade" / MAT_godswordtrail / MAT_godchainlink / MAT_godchainglow, 보통 = goMAIblade / MAT_swordtrail /
	 * MAT_chainlink / MAT_chainglow (모두 WAD_R_Wpn = R_WPN&lt;코스튬&gt;_&lt;단계&gt;). 블레이드마다 이전 GO 를 지우고 (0x132768) 새 GO 를
	 * 만들어 (0x132508/0x1327B8/0x132648) 영웅에 붙이고 (0x131460) 이전 행렬을 복사, "LeftBladeLight"/"RightBladeLight" 라이트 연결.
	 * 엔진: 블레이드 루트 = 사슬이 정한 블레이드 행렬 ({@link #bindBlades}, 45 문서 §3; 없으면 무기 로케이터 lWeapOH/rWeapOH),
	 * 보통 블레이드 메시는 숨긴다. 궤적·사슬 머티리얼 교체는 BladeChainRenderer.
	 */
	public static final String GOD_BLADE = "goMAIgodblade";
	static final String[] BLADE_JOINTS = {"lWeapOH", "rWeapOH"};
	private java.util.function.BooleanSupplier godBladeCheck = () -> false;
	private boolean godBladesOn;

	/** 무기 WAD (R_WPN&lt;c&gt;_&lt;n&gt;) 의 goMAIgodblade 등록. active = 블레이드를 들었는지 (아르테미스면 거짓). */
	public void setGodBlades(Path wpnWad, java.util.function.BooleanSupplier active) {
		if (wpnWad != null && Files.exists(wpnWad)) {
			fx.add(wpnWad);
		}
		godBladeCheck = active != null ? active : () -> false;
	}

	/** 사슬이 정한 블레이드 행렬과 궤적 켜짐 (HeroRig, 45 문서 §3). */
	public interface Blades {
		boolean world(int side, Matrix4f out);

		boolean trail(int side);

		/** 부착물 종류 1 blooddrip / 2 bloodspray 의 생성률 배율 (0x1FB508 → 0x201FB0, 블레이드 +0xA4 피 시간). 기본 0 (피 없음). */
		default float blood(int side, int kind) {
			return 0f;
		}
	}

	private Blades blades;
	/** 보통 블레이드 GO (goMAIblade, 메시는 BladeModels 가 그림 → 이미터만). */
	private final EffectSystem.Effect[] normalBlades = new EffectSystem.Effect[2];
	public static final String NORMAL_BLADE = "goMAIblade";

	public void bindBlades(Blades b) {
		blades = b;
	}

	private EffectSystem.Anchor bladeAnchor(int side) {
		String jn = BLADE_JOINTS[side];
		return out -> {
			if (blades != null && blades.world(side, out)) {
				return true;
			}
			if (!heroJoints.world(jn, out)) {
				out.translation(heroPos).rotateY(heroYaw);
			}
			return true;
		};
	}

	/**
	 * 부착물 종류 3 (0x201A08: 파티클 이름이 "flame" 으로 시작) 은 궤적 (+0x1AC 0x8/0x10) 동안만 켠다 (0x204B38 → 0x201C78 / 0x201DD8).
	 * 불티 (sparkemit)·EG 이미터는 어느 목록에도 없어 늘 켜져 있다.
	 */
	private java.util.function.Predicate<devple.gow.tools.fx.EffectLibrary.EmitterDef> flameGate(int side) {
		return ed -> flame(ed) && (blades == null || !blades.trail(side));
	}

	/**
	 * 블레이드 GO 부착물 (0x201A08, GO 를 붙이거나 바꿀 때): 종류 &lt; 0xB 인 파티클 컴포넌트의 이름을 strncmp 로 "blooddrip" (9) → 종류 1,
	 * "bloodspray" (10) → 2, "flame" (5) → 3 순서로 가른다 [확실]. 종류 1·2 는 매 프레임 생성률 배율 (+0xB4) = {@link Blades#blood}
	 * (0x1FB508 → 0x201FB0), 종류 3 은 궤적 중에만 ({@link #flameGate}). 출시 데이터의 goMAIblade / goMAIgodblade (R_WPN 36 개) 에는
	 * blooddrip·bloodspray 파티클이 없어 종류 1·2 목록은 늘 비어 있다 [확실: 데이터 전수].
	 */
	private void bladeAttachments(EffectSystem.Effect e, int side) {
		e.emitterOff = flameGate(side);
		e.emitterRate = ed -> {
			int kind = bladeAttachKind(ed);
			return kind == 1 || kind == 2 ? (blades != null ? blades.blood(side, kind) : 0f) : 1f;
		};
	}

	/** 0x201A08 의 이름 분류: 1 blooddrip, 2 bloodspray, 3 flame, 0 그 밖 (불티·EG — 늘 켜짐). */
	public static int bladeAttachKind(devple.gow.tools.fx.EffectLibrary.EmitterDef ed) {
		String n = ed.ptc() != null ? ed.ptc().shape() : null;
		if (n == null) {
			return 0;
		}
		return n.startsWith("blooddrip") ? 1 : n.startsWith("bloodspray") ? 2 : n.startsWith("flame") ? 3 : 0;
	}

	/** 0x201A08 의 strncmp(파티클 이름, "flame", 5) — PTC 안 이름 (예 PTC_flame6 의 "flame6Shape"). */
	public static boolean flame(devple.gow.tools.fx.EffectLibrary.EmitterDef ed) {
		return ed.ptc() != null && ed.ptc().shape() != null && ed.ptc().shape().startsWith("flame");
	}

	/** 보통 블레이드 GO 유지 (신 모드·아르테미스면 끔). */
	private void normalBlades() {
		boolean want = blades != null && godBladeCheck.getAsBoolean() && !godBladesOn();
		for (int i = 0; i < 2; i++) {
			EffectSystem.Effect e = normalBlades[i];
			if (want && (e == null || e.done())) {
				e = fx.play(NORMAL_BLADE, bladeAnchor(i), Float.MAX_VALUE);
				if (e != null) {
					e.hideMesh = true;
					bladeAttachments(e, i);
				}
				normalBlades[i] = e;
			} else if (!want && e != null) {
				e.stop();
				normalBlades[i] = null;
			}
		}
	}

	/**
	 * 무기 WAD 를 바꾼 뒤 (업그레이드 메뉴 → Upgrade_LoadWad 0x1D7890): 붙어 있던 보통 블레이드 GO 를 지워 다음 {@link #normalBlades} 가
	 * 새 WAD 의 goMAIblade 로 다시 만든다 — 레벨마다 불꽃·EG 이미터가 다르다 (R_WPN0_2 FXC_EGemit1·EGgrav1 등) [추정: 원작은 힙과 함께 GO 를 새로].
	 */
	public void reattachBlades() {
		for (int i = 0; i < 2; i++) {
			if (normalBlades[i] != null) {
				normalBlades[i].stop();
				normalBlades[i] = null;
			}
		}
	}

	/** 보통 블레이드 GO (테스트). */
	public EffectSystem.Effect normalBlade(int side) {
		return normalBlades[side];
	}

	/** god 판 블레이드 GO 가 떠 있는지 (HeroRig 가 보통 블레이드를 숨긴다). */
	public boolean godBladesOn() {
		return godOn && godBladesOn;
	}

	private void godMode(boolean on) {
		if (on == godOn) {
			return;
		}
		godOn = on;
		godFx.forEach(EffectSystem.Effect::stop);
		godFx.clear();
		if (!on) {
			return;
		}
		for (String[] a : GOD_ARMOR) {
			String jn = a[1];
			addIf(godFx, fx.play(a[0], out -> {
				if (!heroJoints.world(jn, out)) {
					out.translation(heroPos).rotateY(heroYaw);
				}
				return true;
			}, Float.MAX_VALUE));
		}
		// 블레이드 god 판 (0x1FA9A0 → 0x1FAA10): 양손 블레이드 GO 를 goMAIgodblade 로 바꿔 무기 관절에 붙인다 (45 문서 §2)
		godBladesOn = false;
		if (godBladeCheck.getAsBoolean()) {
			for (int side = 0; side < 2; side++) {
				EffectSystem.Effect b = fx.play(GOD_BLADE, bladeAnchor(side), Float.MAX_VALUE);
				if (b != null) {
					bladeAttachments(b, side);
					godFx.add(b);
					godBladesOn = true;
				}
			}
		}
		// Strike/Flash: 원작은 위치를 정하지 않는다 (풀 인스턴스의 이전 행렬) → 엔진은 영웅 위치 [추정]
		Vector3f p = new Vector3f(heroPos);
		addIf(godFx, fx.play("goStrikeGrp", EffectSystem.Anchor.at(p, heroYaw)));
		addIf(godFx, fx.play("goFlashGrp", EffectSystem.Anchor.at(p, heroYaw)));
	}

	/** 신들의 분노 GO (테스트). */
	public List<EffectSystem.Effect> godEffects() {
		return godFx;
	}

	/** goLightningRadius 배율 (0x3368B8). */
	private float radiusScale = 1;
	/** goStrikeGrp 슬롯 4개 (0x336890, 인덱스 0x3368AC). */
	private final EffectSystem.Effect[] strikeSlots = new EffectSystem.Effect[4];
	private int strikeSlot;

	/** goStrikeGrp 횟수 (테스트). */
	public int strikes;

	private static void addIf(List<EffectSystem.Effect> l, EffectSystem.Effect e) {
		if (e != null) {
			l.add(e);
		}
	}

	private void trail(Object key, String def, Vector3f pos) {
		ParticleSystem.Emitter e = trails.computeIfAbsent(key, k -> ps.add(defs.get(def), pos.x, pos.y, pos.z));
		e.pos.set(pos);
	}

	public void render(Camera cam, float aspect) {
		render(cam, aspect, null);
	}

	public void render(Camera cam, float aspect, Texture white) {
		if (white != null) {
			fx.render(cam, aspect, white);
		} else {
			fx.particles().render(cam, aspect);
		}
		ps.render(cam, aspect);
	}

	/** 하늘 GO 이펙트의 파티클 (층 1) — 레벨 하늘 단계 ({@code LevelScene.skyEffects}) 에서. */
	public void renderSky(Camera cam, float aspect) {
		fx.renderSky(cam, aspect);
	}

	@Override
	public void close() {
		fx.close();
		ps.close();
	}
}
