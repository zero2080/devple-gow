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
 *   <li>메두사: 광선 goEyeBeam (반복, 손 관절 rWeapIH 부착, 조준 방향), 섬광 goMedHeadFlash (1회), Nuke = Concussion 템플릿의 goMedHeadNuke (반경 확대)</li>
 *   <li>제우스: 투사체 goBolt + 꼬리 goStdBolt (Arrow 템플릿 GO Name / Trail Name), 명중 goBoltHit</li>
 *   <li>하데스: 영혼 goDedRed (속도 방향), 명중 goDeadHit, 소멸 goDeadDie</li>
 *   <li>포세이돈: 루프 동안 goLightningAttackMain, 피해 틱마다 대상에 goLightningSingleBolt</li>
 *   <li>전투: 영웅/적 기술의 SCR_PlayEffect(PlayFX 템플릿) / SCR_ConcussionBurst(Concussion 템플릿) 이벤트, 적중 시 피격자 MFX 피 (goSklBlood),
 *       발소리 MFX Run Dust Name (goRunDust)</li>
 * </ul>
 * 원작 데이터가 없는 것(석상 먼지·석상 파괴 *stonedbrk)이나 WAD 가 없을 때는 예전 근사 파티클을 쓴다.
 */
public final class MagicFx implements AutoCloseable {

	/** Hash127Upper("SCR_PlayEffect") / ("SCR_ConcussionBurst") — 영웅·적 CombatFile SCRIPT 이벤트 (idx = 템플릿). */
	public static final int SCR_PLAY_EFFECT = 0x097AE822, SCR_CONCUSSION_BURST = 0x242EF94F;
	/** MFX 템플릿의 피 이펙트 GO (+0x28, 키 이름 미복원 #405ce7fc): 500 Flesh / 501 Hero Flesh = goSklBlood. */
	static final int MFX_BLOOD = 0x405CE7FC;

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
		bind(c, heroJoints, true);
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
		String jn = tw.s(n, "Joint Name", null);
		boolean attachedFlag = (tw.raw(n, "Attached").orElse(0) & 0xFF) != 0;
		boolean looped = (tw.raw(n, "Looped").orElse(0) & 0xFF) != 0;
		float sx = tw.f(n, "Scale X", 1f);
		Vector3f off = new Vector3f(tw.f(n, "Offset X", 0), tw.f(n, "Offset Y", 0), tw.f(n, "Offset Z", 0)).mul(Magic.UNIT);
		EffectSystem.Anchor live = out -> {
			if (!joints.world(jn, out)) {
				return true; // 관절 없음 → 소유자 원점
			}
			out.translate(off);
			return true;
		};
		EffectSystem.Effect e = fx.play(name, attachedFlag ? live : snapshot(live), -1);
		if (e != null) {
			e.scale = sx > 0 && sx < 50 ? sx : 1;
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
		return e;
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
	 * 적중 피 (0x2032C0 → sub_00221810): 피격자 재질 MFX 의 +0x28 GO 를 접촉점에, 공격자→피격자 방향으로.
	 * 공격 자체의 MFX (Attack +0x16, flags 0x2) 도 같은 필드를 쓰지만 영웅 기술의 520 "Hit Effect" 는 비어 있다.
	 */
	private void hitBlood(CombatFile.Attack a, Vector3f from, Hittable victim, boolean victimHero) {
		Vector3f p = new Vector3f(victim.position()).add(0, 40, 0);
		Vector3f d = new Vector3f(p).sub(from);
		float yaw = (float) Math.atan2(-d.x, -d.z);
		String go = tw.sHash(String.format("/TweakTemplates/MFX/%03d/", victimHero ? 501 : 500), MFX_BLOOD, "goSklBlood");
		if (fx.play(go, EffectSystem.Anchor.at(p, yaw)) == null) {
			burst("blood", p);
		}
		if (a != null && a.mfx() > 0 && (a.flags() & 2) != 0) {
			String g2 = tw.sHash(String.format("/TweakTemplates/MFX/%03d/", a.mfx()), MFX_BLOOD, null);
			if (g2 != null && !g2.equalsIgnoreCase(go)) {
				fx.play(g2, EffectSystem.Anchor.at(p, yaw));
			}
		}
	}

	/** 발소리 (GameScene): 발밑 SheetFX → MFX "Run Dust Name" (+0x34, 예 Rock → goRunDust) 를 발 위치에 (sub_00221A90) [달리기만]. */
	public void footstep(int sheetFx, Vector3f at, float yaw) {
		String go = sheetFx > 0 ? tw.s(String.format("/TweakTemplates/MFX/%03d/", sheetFx), "Run Dust Name", null) : null;
		if (go != null && go.startsWith("go")) {
			fx.play(go, EffectSystem.Anchor.at(at, yaw), 0.6f);
		}
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
		lightning(m, heroPos, heroYaw);
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
				// 원작: 메두사 머리 GO 의 관절에 부착 (0x1969F0 → sub_00131460). 엔진은 영웅 손 rWeapIH + 조준 방향 [근사]
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

	private void lightning(Magic m, Vector3f heroPos, float heroYaw) {
		if (m.lightningOn()) {
			if (lightningFx == null) {
				stage("R_LGHTN", stages[3]);
				lightningFx = fx.play("goLightningAttackMain", out -> {
					out.translation(this.heroPos).rotateY(this.heroYaw);
					return true;
				});
			}
		} else if (lightningFx != null) {
			lightningFx.stop();
			lightningFx = null;
		}
	}

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
