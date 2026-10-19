package devple.gow.engine.fx;

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
			Path permk = rom.resolve("R_PERMK.WAD");
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
		Path p = rom.resolve(prefix + Math.max(0, Math.min(2, st)) + ".WAD");
		if (!Files.exists(p)) {
			p = rom.resolve(prefix + "0.WAD");
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
			}
		});
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
				concussion(e.index(), joints, null);
			}
		});
		c.addContactListener((a, victim, r) -> {
			if (r == Hittable.Result.HIT && victim != null) {
				hitBlood(a, c.owner().position(), victim, !hero);
			}
		});
	}

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
	public void concussion(int template, Joints joints, Vector3f at) {
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
	private void hitBlood(CombatFile.Attack a, Vector3f from, Hittable victim, boolean victimHero) {
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
		if (go == null || fx.play(go, EffectSystem.Anchor.at(p, yaw)) == null) {
			if (mfx >= 500) {
				burst("blood", p);
			}
		}
		lastHitMfx = mfx;
		lastHitGo = go;
		if (EffectSystem.LOG) {
			System.out.printf("적중 MFX %d (%s) → %s%n", mfx, hv != null && hv.lastHitFx() > 0 ? "피격 구" : "기본", go);
		}
		if (flag(node, "Drop Blood")) {
			bloodDrop(p, 1f);
		}
		if (a != null && a.mfx() > 0 && (a.flags() & 2) != 0) {
			String g2 = tw.sHash(String.format("/TweakTemplates/MFX/%03d/", a.mfx()), MFX_BLOOD, null);
			if (g2 != null && !g2.equalsIgnoreCase(go)) {
				fx.play(g2, EffectSystem.Anchor.at(p, yaw));
			}
		}
	}

	/** 마지막 적중의 MFX 번호·GO (테스트). */
	public int lastHitMfx = -1;
	public String lastHitGo;

	/** 피격 재질 MFX: 맞은 구의 SheetFX → 몸 구 SheetFX → 500/501. */
	public static int mfxOf(Hittable victim, boolean victimHero) {
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
		String n = "/GlobGame/BloodSpots/";
		String go = tw.s(n, "Particle System 1 Light", "goBloodSpot");
		float count = Math.min(tw.f(n, "Nominal Particles", 36f) * scale, 200);
		Vector3f p = new Vector3f(at.x, fl[0] + tw.f(n, "Offset", 0.1f) * Magic.UNIT, at.z);
		EffectSystem.Effect e = fx.play(go, EffectSystem.Anchor.of(new Matrix4f().translation(p)), 0.1f);
		if (e != null) {
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
	 * 부서지는 물체 (41 문서): 물체 GO 자체를 이펙트로 재생 — 오브젝트 ANM 첫 act (파편이 흩어지는 애니) + 메시 + FXC 먼지 + SEM 소리
	 * (pot: SND_POTTERYSMASH). 원작 0x1DDF70 이 GO 애니 컴포넌트를 0초부터 재생(sub_00100578)하고 이벤트 1018 을 보낸다.
	 * act 가 끝나면 Brkb "Fade Time" 동안 마지막 포즈를 흐리게 한다 [추정: Fade Time 사용처 미확인].
	 *
	 * @param world 인스턴스 월드 행렬 (열벡터, {@code GoInstance.matrix()} 전치)
	 */
	public EffectSystem.Effect breakObject(String goName, Matrix4f world, float fadeTime) {
		EffectSystem.Effect e = fx.play(goName, EffectSystem.Anchor.of(world), -1);
		if (e == null) {
			return null;
		}
		float dur = e.def.anim() != null ? Math.max(e.def.anim().duration, 0.1f) : 1f;
		float fade = Math.max(fadeTime, 0);
		e.setLife(dur + fade);
		e.scale = 1f / Math.max(e.def.scale(), 1e-3f); // 인스턴스 스케일은 world 에 이미 들어 있다
		perFrame.add(new Runnable() {
			@Override
			public void run() {
				e.alpha = e.time() > dur && fade > 0 ? Math.max(0, 1 - (e.time() - dur) / fade) : 1;
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
		recentSounds.replaceAll((k, v) -> v - dt);
		recentSounds.values().removeIf(v -> v <= 0);
		for (Hittable h : enemies) {
			boolean st = h.petrified();
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
	 * 포세이돈 (SCR_Lightning 0x1948F0): 시작 때 goLightningAttackMain·goLightningRadius 를 만들어 매 프레임 영웅 행렬에 둔다.
	 * 타이머(0x3368A8)가 (1/60)·연타율·"Stage n Flash Freq" 씩 줄어 0 이 되면 goStrikeGrp 를 반경
	 * (1 − rnd²) · Reach · (1 + 연타율·(Mash Reach Scale − 1)) · 16 의 임의 방향에 1회 (슬롯 4개 순환). 연타율 = 매시 미터/최대 —
	 * 엔진은 매시 미터가 없어 0.5 [근사].
	 */
	private void lightning(Magic m, Vector3f heroPos, float heroYaw, float dt) {
		if (m.lightningOn()) {
			if (lightningFx == null) {
				stage("R_LGHTN", stages[3]);
				EffectSystem.Anchor a = out -> {
					out.translation(this.heroPos).rotateY(this.heroYaw);
					return true;
				};
				lightningFx = fx.play("goLightningAttackMain", a);
				radiusFx = fx.play("goLightningRadius", a);
				strikeTimer = 1;
			}
			String n = "/Player/Lightning/";
			int st = stages[3];
			float mash = 0.5f;
			float freq = Math.max(1, Math.round(tw.f(n, "Stage " + st + " Flash Freq", 1f)));
			strikeTimer -= dt * mash * freq;
			if (strikeTimer <= 0) {
				strikeTimer = 1;
				float reach = tw.f(n, "Stage " + st + " Reach", 5f) * (1 + mash * (tw.f(n, "Stage " + st + " Mash Reach Scale", 1f) - 1));
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
				if (fx.play("goStrikeGrp", EffectSystem.Anchor.at(p, ang)) != null) {
					strikes++;
				}
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

	@Override
	public void close() {
		fx.close();
		ps.close();
	}
}
