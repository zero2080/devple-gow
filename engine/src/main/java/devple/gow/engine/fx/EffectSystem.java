package devple.gow.engine.fx;

import devple.gow.engine.asset.AnimatedModel;
import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Shader;
import devple.gow.engine.render.Texture;
import devple.gow.tools.anim.Pose;
import devple.gow.tools.fx.EffectLibrary;
import devple.gow.tools.fx.FxResources;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.wad.WadFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 원작 이펙트 GO 런타임 (40 문서 §4). 튜닝/스크립트의 이펙트 이름 → {@link EffectLibrary} 정의 → 인스턴스:
 * <ul>
 *   <li>메시 (MDL_) + 오브젝트 애니 (관절 회전) + 머티리얼 ANM (UV 스크롤·불투명도) → {@link AnimatedModel}</li>
 *   <li>FXC_ 이미터 + PTC_ → {@link ParticleSystem} 이미터. 생성률·속력·퍼짐은 ANM 타입 10 채널(스트림 8/4/3) 곡선</li>
 *   <li>FXC_ 필드 → 파티클 가속</li>
 * </ul>
 * 위치는 {@link Anchor} (관절 부착이면 매 프레임 관절 월드, 아니면 고정 행렬). 수명 = 1회성 act 길이, 반복이면 {@link Effect#stop()} 까지
 * (원작 0x228210 / 0x2282D0). 데이터가 없는 이름은 {@link #play} 가 null 을 돌려주고 호출자가 근사 파티클로 대신한다.
 * {@link #headless} 이면 GL 객체를 만들지 않고 수명·생성률만 진행한다 (테스트).
 */
public final class EffectSystem implements AutoCloseable {

	/** 이펙트 기준 행렬 (JOML 열벡터 규약, 월드). false 를 돌려주면 소유자가 사라진 것 → 이펙트 종료. */
	@FunctionalInterface
	public interface Anchor {
		boolean world(Matrix4f out);

		/** 고정 위치 + 방향 (yaw: 정면 −Z 기준, 엔진 규약). */
		static Anchor at(Vector3f p, float yaw) {
			Matrix4f m = new Matrix4f().translation(p).rotateY(yaw);
			return out -> {
				out.set(m);
				return true;
			};
		}

		/** 고정 행렬. */
		static Anchor of(Matrix4f m) {
			Matrix4f c = new Matrix4f(m);
			return out -> {
				out.set(c);
				return true;
			};
		}
	}

	/**
	 * 이펙트 소리 출력 (SEM_ 이미터, 40 문서 §4.4). 핸들 ≤ 0 = 재생 실패.
	 */
	public interface Sounds {
		int play(String snd, Vector3f at, float near, float far);

		void move(int handle, Vector3f at);

		void stop(int handle);
	}

	/** 실행 중 이펙트 하나. */
	public final class Effect {
		public final EffectLibrary.EffectDef def;
		private Anchor anchor;
		private float life;
		private float time;
		private boolean stopped;
		/** 현재 오브젝트 act (기본 = 첫 act, mewh 는 "On"/"off" 로 바꾼다) 와 그 act 의 경과 시간. */
		private devple.gow.tools.anim.Animation act;
		private float actTime;
		/** SEM 소리 끄기 (호출자가 같은 소리를 이미 낸 경우). */
		public boolean silent;
		/** 메시를 그리지 않음 (이미터·소리만). */
		public boolean hideMesh;
		/** act 의 u8 채널을 관절 가시성으로 적용 (부서지는 물체, 41 문서 §2) [추정: 다른 GO 에는 아직 안 씀]. */
		public boolean jointVisibility;
		/** 파편 물리 (Brkb bit 0x1, {@link BreakDebris}) 와 그 충돌면. tick 에서 조각 관절 행렬을 바꾼다. */
		public BreakDebris debris;
		public MagicFx.Surface debrisSurface;
		private float[][] debrisPose;
		private int soundHandle;
		private boolean soundStarted;
		/** 사용자 배율 (Concussion 반경 등), 인스턴스 스케일과 곱한다. */
		public float scale = 1;
		/** 메시 불투명도 배율 (페이드). */
		public float alpha = 1;
		/** 생성률 강제 (초당, &lt; 0 = 데이터대로). 원작이 코드로 직접 뿌리는 이펙트(BloodSpots 0x1F9278)용 [근사]. */
		public float rateOverride = -1;
		/** 이미터 속력 배율. */
		public float speedScale = 1;
		/**
		 * 이미터 생성률 배율 = 원작 GO 파티클 컴포넌트 +0xB4 (sub_00227D80 이 종류 &lt; 0xB 인 컴포넌트에 기록, 0x122B00 이
		 * rate·dt·배율 로 누적) [확실]. MFX 적중 0x221810 의 f12 (주 대상 1 / 그 외 0.125).
		 */
		public float rateScale = 1;
		/** 평면 무늬 (VU 종류 9) 바닥 법선 (월드). 피 자국은 바닥 충돌 법선 (원작 0x1F8990). */
		public final Vector3f planeNormal = new Vector3f(0, 1, 0);
		/**
		 * 참이면 그 이미터 생성률 0 (원작 부착물 종류별 켜기/끄기 0x201C78 / 0x201DD8 — 블레이드 "flame*" 파티클은 궤적 중에만, 45 문서 §3).
		 */
		public java.util.function.Predicate<EffectLibrary.EmitterDef> emitterOff;
		/** 이미터별 생성률 배율 (원작 부착물 종류별 +0xB4 — 블레이드 blooddrip / bloodspray 0x201FB0, 45 문서 §3.8). null = 1. */
		public java.util.function.ToDoubleFunction<EffectLibrary.EmitterDef> emitterRate;
		/** 시간 정지 (act 0 초 포즈 유지 — BMI 갑옷 조각이 깨지기 전, 원작 0x1C1B10 이 애니를 0 초에 둔다). */
		public boolean hold;
		/** {@link #setAct} 로 바꾼 act 를 데이터 반복 플래그와 관계없이 되풀이 (스크립트 Animator 모드 비트 0x2 — Creator 오브젝트, 43 문서 §19). */
		public boolean loopAct;
		/**
		 * 코드가 정한 관절 숨김 (참 = 그 관절 숨김, 파트는 쓰는 관절이 모두 숨김이면 안 그림) — act 의 u8 가시성과 합친다. 원작 GO 관절 가시성 표 (+0x104 → +0x54, 0x215AF0:
		 * 상자 종류별 magic/health/power/plain 관절).
		 */
		public boolean[] jointMask;
		/**
		 * 이미터를 붙은 관절에 둔다 (FXC 노드 헤더 +0x08 = 관절, {@code LevelEmitters} 와 같은 이미터 월드 = FXC 로컬 · 관절 월드 · GO 월드, 관절 자세 = {@code EffectDef.pose}).
		 * Animator 가 튼 레벨 GO (ATHN03A 불덩이) 와 하늘 GO 에 켠다 — 다른 이펙트는 예전처럼 GO 원점 + 로컬 이동. 생성률·속력 채널은 어느 쪽이든 원작 채널 id (05 문서 §2.8.1).
		 */
		public boolean jointEmitters;
		/** 하늘 GO 이펙트: 이미터를 {@link ParticleSystem.Emitter#sky} 로 — 파티클은 하늘 단계에서만 그린다 (세계에 덮인다, 43 §17). */
		public boolean sky;
		private float[][] bindWorld;
		private final List<ParticleSystem.Emitter> emitters = new ArrayList<>();
		private final Matrix4f world = new Matrix4f();
		private final Matrix4f tmp = new Matrix4f();

		Effect(EffectLibrary.EffectDef def, Anchor anchor, float life) {
			this.def = def;
			this.anchor = anchor;
			this.life = life;
			this.act = def.anim();
		}

		/** 오브젝트 act 를 이름으로 바꾼다 (같은 act 면 그대로). 원작 sub_00196B18: GO 애니 컴포넌트에서 act 해시로 재생. */
		public boolean setAct(String name) {
			devple.gow.tools.anim.Animation a = def.act(name);
			if (a == null) {
				return false;
			}
			if (a != act) {
				act = a;
				actTime = 0;
			}
			return true;
		}

		/** 지금 act 의 경과 시간을 맞춘다 ({@link #hold} 와 함께: 진행률로 자세를 정하는 오브젝트 — 상자 chestGrab = 과제 시간). */
		public void setActTime(float t) {
			actTime = t;
			if (act == def.anim()) {
				time = t;
			}
		}

		public String actName() {
			return act != null ? act.name : null;
		}

		/** 지금 act 의 경과 시간 (초). */
		public float actTime() {
			return act == def.anim() ? time : actTime;
		}

		/** 지금 act 의 길이 (초, 없으면 0). */
		public float actDuration() {
			return act != null ? act.duration : 0;
		}

		/** 수명 연장/단축 (Fade Time 등). */
		public void setLife(float l) {
			life = l;
		}

		public float time() {
			return time;
		}

		public float life() {
			return life;
		}

		public boolean done() {
			return stopped;
		}

		public void setAnchor(Anchor a) {
			anchor = a;
		}

		/** 이미터를 끄고 메시를 지운다. 이미 나온 파티클은 수명까지 남는다 (원작 0x227548: GO 제거). */
		public void stop() {
			if (stopped) {
				return;
			}
			stopped = true;
			if (ps != null) {
				ps.emitters().removeAll(emitters);
			}
			emitters.clear();
			// 반복 이펙트의 소리(*_LP)는 GO 와 함께 끈다. 1회성 소리는 끝까지 둔다 [추정]
			if (soundHandle > 0 && sounds != null && !def.oneShot()) {
				sounds.stop(soundHandle);
			}
			soundHandle = 0;
		}

		/** 현재 이미터별 생성률 (테스트). */
		public float[] rates() {
			float[] r = new float[def.emitters().size()];
			for (int i = 0; i < r.length; i++) {
				r[i] = def.rate(act, def.emitters().get(i), act == def.anim() ? time : actTime);
			}
			return r;
		}

		/** 이 프레임의 이펙트 월드 행렬 (기준 × 스케일). */
		public Matrix4f world() {
			return world;
		}

		void tick(float dt) {
			if (stopped) {
				return;
			}
			if (!hold) {
				time += dt;
				actTime += dt;
				if (loopAct && act != null && act != def.anim() && act.duration > 0 && actTime >= act.duration) {
					actTime %= act.duration;
				}
			}
			if (time >= life || anchor == null || !anchor.world(tmp)) {
				stop();
				return;
			}
			float s = def.scale() * scale;
			world.set(tmp).scale(s);
			if (debris != null && act != null && def.skeleton() != null) {
				float at = act == def.anim() ? time : actTime;
				debrisPose = act.sample(def.skeleton(), at, act.looping()).world(def.skeleton());
				debris.step(debrisPose, world, debrisSurface, dt);
			}
			sound();
			if (ps == null) {
				return;
			}
			if (emitters.isEmpty() && !def.emitters().isEmpty()) {
				for (EffectLibrary.EmitterDef ed : def.emitters()) {
					ParticleDef pd = particleDef(def, ed);
					ParticleSystem.Emitter e = ps.add(pd, 0, 0, 0);
					e.active = pd != null;
					emitters.add(e);
				}
			}
			float[][] jointWorld = null;
			if (jointEmitters && def.skeleton() != null) {
				if (act != null) {
					jointWorld = def.pose(act, act == def.anim() ? time : actTime).world(def.skeleton());
				} else {
					if (bindWorld == null) {
						bindWorld = def.skeleton().bindWorld();
					}
					jointWorld = bindWorld; // 애니 없는 GO (하늘 group2): 바인드 관절
				}
			}
			for (int i = 0; i < emitters.size(); i++) {
				EffectLibrary.EmitterDef ed = def.emitters().get(i);
				ParticleSystem.Emitter e = emitters.get(i);
				e.sky = sky;
				FxResources.Emitter fx = ed.fxc();
				float[] lt = fx.local();
				int j = fx.header().length > 0 ? fx.header()[0] : -1;
				float[] d = fx.subtype() == 1 || fx.subtype() == 8 ? fx.dir() : new float[]{0, 1, 0};
				float speedMul = Math.min(s, 4f);
				boolean jointEmitter = jointWorld != null && j >= 0 && j < jointWorld.length;
				if (jointEmitter) {
					// 관절 이미터: (행벡터) FXC 로컬 · 관절 월드 · GO 월드 = 열벡터 GO · 관절 · 로컬 (LevelEmitters 와 같은 곱, 로컬 이동을 자르지 않음)
					Matrix4f em = new Matrix4f(world).mul(new Matrix4f().set(jointWorld[j])).mul(new Matrix4f().set(lt));
					em.getTranslation(e.pos);
					e.space.set(world).mul(new Matrix4f().set(jointWorld[j])).normalize3x3();
					e.normal.set(planeNormal);
					Vector3f ld = em.transformDirection(new Vector3f(d[0], d[1], d[2]));
					if (ld.lengthSquared() > 1e-8f) {
						e.dir.set(ld).normalize();
					}
					// 레벨 GO (Animator · 하늘): 속력은 이미터 월드 행렬 배율로 (0x122C88 → 0x122E78, LevelEmitters 와 같은 규칙) + CPU 동역학 [확실]
					float dl = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
					if (fx.subtype() == 1 || fx.subtype() == 2) {
						speedMul = dl > 1e-6f ? ld.length() / dl : 1;
					}
					e.cpuMotion = true;
					if (e.def != null && e.def.cpuMotion()) {
						fieldAccel(jointWorld, e.fieldAccel);
					}
				} else {
					Vector3f lp = new Vector3f(lt[12], lt[13], lt[14]);
					if (lp.length() > 64) {
						lp.zero(); // Maya 장면 좌표가 남은 이미터 (Fsliceemit 407,0,1344 등) → 원점 [근사]
					}
					world.transformPosition(lp, e.pos);
					// PTC 0x10/0x20 (파티클이 소유자 회전/이동을 따름) 용 시스템 행렬 = 이펙트 월드 (배율 제거) [확실: 0x15BE18]
					e.space.set(world).normalize3x3();
					e.normal.set(planeNormal);
					// 방향: FXC 로컬 회전(정규화) · dir, 그다음 이펙트 월드 회전
					Vector3f ld = localDir(lt, d);
					tmp.set(world).transformDirection(ld);
					if (ld.lengthSquared() > 1e-8f) {
						e.dir.set(ld).normalize();
					}
				}
				float at = act == def.anim() ? time : actTime;
				float ct = act != null && act.looping() && act.duration > 0 ? at % act.duration : at;
				e.rate = (rateOverride >= 0 ? rateOverride : def.rate(act, ed, at)) * lodScale * rateScale;
				if (emitterOff != null && emitterOff.test(ed)) {
					e.rate = 0;
				}
				if (emitterRate != null) {
					e.rate *= (float) emitterRate.applyAsDouble(ed);
				}
				e.speed = def.param(act, ed, 4, ct, fx.speed()) * 16 * speedMul * speedScale;
				e.speedRnd = def.param(act, ed, 5, ct, fx.speedRnd()) * 16 * speedMul * speedScale;
				if (fx.subtype() == 1 || fx.subtype() == 8) {
					e.cone = def.param(act, ed, 3, ct, fx.spread());
				}
				e.sizeScale = Math.min(s, 6f);
			}
		}

		/**
		 * 레벨 GO 의 필드 노드 가속 (월드/초²): 중력·균일 필드 방향 = normalize(dir · 필드 로컬 · 관절 · GO 월드), 크기 × 16 — {@code LevelEmitters#fieldAccel}
		 * 과 같은 규칙 (0x12B530) [확실: 방향·배율 / 근사: 상수 가속]. CPU 동역학 파티클만 쓴다.
		 */
		private void fieldAccel(float[][] jointWorld, Vector3f out) {
			out.zero();
			for (FxResources.Field f : def.fields()) {
				if (f.kind() != 3 && f.kind() != 6) {
					continue;
				}
				int fj = f.joint() >= 0 && f.joint() < jointWorld.length ? f.joint() : 0;
				Vector3f dir = new Matrix4f(world).mul(new Matrix4f().set(jointWorld[fj])).mul(new Matrix4f().set(f.local()))
						.transformDirection(new Vector3f(f.dir()[0], f.dir()[1], f.dir()[2]));
				float l = dir.length();
				if (l > 1e-6f) {
					out.fma(f.magnitude() * 16 / l, dir);
				}
			}
		}

		/** SEM_ 이미터 슬롯 1 소리 (GO 생성 때 1번, 위치는 매 프레임 이펙트 원점 + SEM 로컬 이동) [추정: 슬롯 1·생성 시점]. */
		private void sound() {
			if (sounds == null || def.sem() == null) {
				return;
			}
			Vector3f p = world.getTranslation(new Vector3f());
			if (!soundStarted) {
				soundStarted = true;
				String snd = def.sem().sound(1);
				if (snd != null && !silent) {
					soundHandle = sounds.play(snd, p, def.sem().near(), def.sem().far());
				}
			} else if (soundHandle > 0) {
				sounds.move(soundHandle, p);
			}
		}

		void draw(Camera cam, float aspect, Texture white) {
			if (stopped || def.model() == null || hideMesh) {
				return;
			}
			AnimatedModel m = model(def);
			if (m == null) {
				return;
			}
			float at = act == def.anim() ? time : actTime;
			Pose pose = act != null && act.hasSkeletalData()
					? act.sample(def.skeleton(), at, act.looping()) : Pose.idle(def.skeleton());
			m.hiddenJoints = jointVisibility && act != null ? act.hiddenJoints(def.skeleton(), at) : null;
			if (jointMask != null) {
				if (m.hiddenJoints == null) {
					m.hiddenJoints = jointMask;
				} else {
					boolean[] h = m.hiddenJoints.clone();
					for (int i = 0; i < h.length && i < jointMask.length; i++) {
						h[i] |= jointMask[i];
					}
					m.hiddenJoints = h;
				}
			}
			if (debrisPose != null) {
				m.draw(cam, aspect, world, debrisPose, white, at, alpha);
			} else {
				m.draw(cam, aspect, world, pose, white, at, alpha);
			}
			m.hiddenJoints = null;
		}
	}

	private final EffectLibrary lib = new EffectLibrary();
	private final Map<WadFile, WadAssets> assets = new IdentityHashMap<>();
	private final List<WadAssets> owned = new ArrayList<>();
	/** 경로로 연 WAD ({@link #add(java.nio.file.Path)}) — 경로 → 그 WadFile. */
	private final Map<java.nio.file.Path, List<WadFile>> byPath = new HashMap<>();

	/** 경로로 연 WAD 들을 목록에서 꺼낸다 (없으면 빈 목록 — 이어서 {@link #remove} 로 뺀다). */
	public List<WadFile> takeWadsAt(java.nio.file.Path p) {
		List<WadFile> l = byPath.remove(p.toAbsolutePath().normalize());
		return l != null ? l : List.of();
	}
	private final Map<String, AnimatedModel> models = new HashMap<>();
	private final Map<EffectLibrary.EmitterDef, ParticleDef> pdefs = new IdentityHashMap<>();
	private final List<Effect> live = new ArrayList<>();
	private final ParticleSystem ps;
	private final Shader skinned;
	/** -Dgow.fxlog (gradle -PgowFxLog) 면 재생마다 해석 결과를 출력 (스크린샷 검증용). */
	static final boolean LOG = System.getProperty("gow.fxlog") != null;
	/** 생성률 배율 (성능 여유, 1 = 원작). */
	public float lodScale = 1;
	/** SEM 소리 출력 (없으면 소리 없음). */
	public Sounds sounds;

	/** 렌더용 (GL 컨텍스트 필요). */
	public EffectSystem(int maxParticles) {
		this.ps = new ParticleSystem(maxParticles);
		this.skinned = new Shader("skinned.vert", "basic.frag");
	}

	private EffectSystem() {
		this.ps = null;
		this.skinned = null;
	}

	/** GL 없이 해석·수명만 (테스트). */
	public static EffectSystem headless() {
		return new EffectSystem();
	}

	/** WAD 의 이펙트 GO 를 등록 (헤드리스: 해석만). */
	public void add(WadFile wad) {
		lib.add(wad);
	}

	/** 렌더 모드: WAD 를 열어 텍스처·메시와 함께 등록 (없거나 실패하면 false). 이 시스템이 닫는다. */
	public boolean add(java.nio.file.Path wadPath) {
		if (!java.nio.file.Files.exists(wadPath)) {
			return false;
		}
		try {
			WadAssets a = new WadAssets(wadPath);
			owned.add(a);
			lib.add(a.wad());
			assets.put(a.wad(), a);
			byPath.computeIfAbsent(wadPath.toAbsolutePath().normalize(), k -> new ArrayList<>()).add(a.wad());
			return true;
		} catch (java.io.IOException | RuntimeException ex) {
			return false;
		}
	}

	/** 이미 열린 WAD 자산(레벨·적 WAD)의 이펙트 GO 를 등록. 자산은 소유자가 닫는다. 이미 등록한 WAD 면 false. */
	public boolean add(WadAssets a) {
		if (a == null || assets.containsKey(a.wad())) {
			return false;
		}
		lib.add(a.wad());
		assets.put(a.wad(), a);
		return true;
	}

	/**
	 * WAD 등록을 뺀다: 그 WAD 정의로 돌던 이펙트 (멈춤) · 이미 나온 파티클 · 모델/파티클 정의 캐시를 지우고, 같은 이름은 남은 WAD 정의로 돌아간다.
	 * 엔진 전용 (43 §27): 이음이 씬을 잇게 되면서 두 슬롯에서 모두 내려간 레벨 장면을 닫기 전에 부른다 — 닫힌 자산의 GL 이름을 캐시가 쥐고 있지 않게.
	 * 이 시스템이 경로로 연 자산이면 닫는다 (다른 소유자 자산은 그 소유자가 닫음).
	 */
	public void remove(WadFile wad) {
		if (wad == null) {
			return;
		}
		WadAssets a = assets.remove(wad);
		List<EffectLibrary.EffectDef> gone = lib.remove(wad);
		if (a == null && gone.isEmpty()) {
			return;
		}
		for (int i = live.size() - 1; i >= 0; i--) {
			Effect e = live.get(i);
			if (e.def.wad() == wad) {
				e.stop();
				live.remove(i);
			}
		}
		String prefix = wad.name() + "/";
		models.entrySet().removeIf(en -> {
			if (!en.getKey().startsWith(prefix)) {
				return false;
			}
			if (en.getValue() != null) {
				en.getValue().close();
			}
			return true;
		});
		java.util.Set<ParticleDef> doomed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		for (EffectLibrary.EffectDef d : gone) {
			for (EffectLibrary.EmitterDef ed : d.emitters()) {
				ParticleDef pd = pdefs.remove(ed);
				if (pd != null) {
					doomed.add(pd);
				}
			}
		}
		if (ps != null && !doomed.isEmpty()) {
			ps.purge(doomed::contains);
		}
		if (a != null && owned.remove(a)) {
			a.close();
		}
	}

	/**
	 * 레벨 WAD 를 등록하거나, 이미 있으면 맨 뒤로 (같은 이름은 이 WAD 가 이긴다 — 다시 해석하지 않음). 이음으로 현재가 된 레벨 (43 §27): 새 씬의 enter 가
	 * 그 레벨을 마지막에 더하던 것과 같은 우선순위. 처음 등록이면 true.
	 */
	public boolean addOrPromote(WadAssets a) {
		if (a == null) {
			return false;
		}
		if (assets.containsKey(a.wad())) {
			lib.promote(a.wad());
			return false;
		}
		return add(a);
	}

	public EffectLibrary library() {
		return lib;
	}

	public ParticleSystem particles() {
		return ps;
	}

	public List<Effect> live() {
		return live;
	}

	/**
	 * 이펙트 시작. 1회성은 act 길이, 반복·애니 없음은 {@link Effect#stop()} 까지.
	 *
	 * @return 정의가 없으면 null (호출자가 근사로 대신)
	 */
	public Effect play(String name, Anchor anchor) {
		return play(name, anchor, -1);
	}

	/**
	 * @param life 수명 강제 (초, ≤ 0 이면 정의대로). 애니 없는 이펙트를 1회로 쓸 때 PTC 수명 등
	 */
	public Effect play(String name, Anchor anchor, float life) {
		EffectLibrary.EffectDef d = lib.resolve(name);
		if (LOG) {
			System.out.printf("이펙트 %s → %s%n", name, d == null ? "없음 (근사)" : d.object() + " @" + d.wad().name()
					+ (d.oneShot() ? String.format(" %.2fs", d.lifetime()) : " 반복"));
		}
		if (d == null) {
			return null;
		}
		float l = life > 0 ? life : d.lifetime();
		Effect e = new Effect(d, anchor, l);
		live.add(e);
		e.tick(0);
		return e;
	}

	public void update(float dt, Vector3f camEye) {
		for (int i = live.size() - 1; i >= 0; i--) {
			Effect e = live.get(i);
			e.tick(dt);
			if (e.done()) {
				live.remove(i);
			}
		}
		if (ps != null) {
			ps.update(dt, camEye);
		}
	}

	public void render(Camera cam, float aspect, Texture white) {
		if (ps == null) {
			return;
		}
		// 이펙트 GO 모델은 원작 층 2 (안개 노드 = 층 2 칸 5 앞) — 불투명은 조각 깊이, 반투명은 장면 깊이로 안개 (SceneFog, AnimatedModel.sceneFog)
		SceneFog sf = live.isEmpty() ? null : SceneFog.current();
		boolean fog = sf != null && sf.applyModel(skinned);
		for (Effect e : live) {
			e.draw(cam, aspect, white);
		}
		if (fog) {
			skinned.use();
			FxEnvironment.disableFog(skinned);
		}
		ps.render(cam, aspect);
	}

	/** 하늘 이펙트 ({@link Effect#sky}) 파티클만 — 레벨 하늘 단계에서 (세계보다 먼저, 깊이 쓰기 없이). */
	public void renderSky(Camera cam, float aspect) {
		if (ps != null) {
			ps.renderSky(cam, aspect);
		}
	}

	private AnimatedModel model(EffectLibrary.EffectDef d) {
		String key = d.wad().name() + "/" + d.object();
		if (models.containsKey(key)) {
			return models.get(key);
		}
		AnimatedModel m = null;
		WadAssets a = assets.get(d.wad());
		if (a != null && d.skeleton().size() <= AnimatedModel.MAX_JOINTS) {
			GowModel gm = a.gowModel(d.model());
			if (gm != null) {
				try {
					m = new AnimatedModel(a, gm, d.skeleton(), skinned);
					m.sceneFog = true;
				} catch (RuntimeException ex) {
					m = null;
				}
			}
		}
		models.put(key, m);
		return m;
	}

	/** FXC 로컬 행렬의 회전(스케일 제거) · d. */
	static Vector3f localDir(float[] m, float[] d) {
		Vector3f out = new Vector3f();
		for (int c = 0; c < 3; c++) {
			float l = (float) Math.sqrt(m[c * 4] * m[c * 4] + m[c * 4 + 1] * m[c * 4 + 1] + m[c * 4 + 2] * m[c * 4 + 2]);
			if (l < 1e-6f) {
				continue;
			}
			// 행벡터 규약: v' = v · M → 행 c 가 축 c 의 상
			out.x += d[c] * m[c * 4] / l;
			out.y += d[c] * m[c * 4 + 1] / l;
			out.z += d[c] * m[c * 4 + 2] / l;
		}
		return out;
	}

	/**
	 * 이펙트 이미터 → 파티클 정의. 색·텍스처·블렌드·알파 곡선은 레벨 이미터와 같은 해석 ({@link LevelEmitters#def}),
	 * 크기 |PTC+0x74| (≥ 800 → /128 (VU 4 는 /256), ≥ 160 → /64, 양수 → /16, 음수 → 2.5) [근사], 가속 = 필드 합 ({@link EffectLibrary#fieldAccel}).
	 */
	private ParticleDef particleDef(EffectLibrary.EffectDef d, EffectLibrary.EmitterDef ed) {
		return pdefs.computeIfAbsent(ed, k -> build(ed, assets.get(d.wad()), d.fields()));
	}

	private ParticleDef build(EffectLibrary.EmitterDef ed, WadAssets a, List<FxResources.Field> fields) {
		FxResources.Emitter em = ed.fxc();
		FxResources.Shape sh = ed.ptc();
		ParticleDef base = a != null ? LevelEmitters.def(em, sh, a, 1f) : null;
		float s = Math.abs(sh.size());
		// VU 4 (반짝이 점) 는 VU 3 (연기·불) 보다 작게 그린다 — 원작 크기 곡선(VU 상수) 미해석 [근사]
		float size = sh.size() < 0 ? 2.5f : s >= 800 ? s / (sh.vuType() == 4 ? 256f : 128f) : s >= 160 ? s / 64f : Math.max(s / 16f, 0.5f);
		float life = sh.lifespan() > 0 ? sh.lifespan() : 0.5f;
		int sub = em.subtype();
		boolean cone = sub == 1 || sub == 8;
		float spread = cone ? em.spread() : 2f; // 2 = 전방향 구 (π)
		float r = Math.max(em.maxDist(), 0) * 16 * 0.5f + (sub >= 5 && sub <= 9 ? 4 : 0);
		float[] acc = EffectLibrary.fieldAccel(fields);
		float[] c0 = base != null ? base.color0() : new float[]{1, 1, 1, 1};
		float[] c1 = base != null ? base.color1() : new float[]{1, 1, 1, 0};
		int fade = base != null ? base.fadeCurve() : 1;
		float size1 = size * 0.6f;
		float[] v3 = sh.vu3();
		if (v3 != null) {
			// VU 3 상수: 색 = q5 + q6·u (RGBA, 0x80 = 1), 가속 = q9, 크기 = (q10.w + q11.w·u) × 3 [근사: 단위]
			c0 = new float[]{v3[0] / 128f, v3[1] / 128f, v3[2] / 128f, Math.min(v3[3] / 128f, 1.5f)};
			c1 = new float[]{Math.max(v3[0] + v3[4], 0) / 128f, Math.max(v3[1] + v3[5], 0) / 128f, Math.max(v3[2] + v3[6], 0) / 128f,
					Math.max(v3[3] + v3[7], 0) / 128f};
			fade = 0;
			acc = new float[]{v3[10], v3[11], v3[12]};
			if (v3[8] > 0 || v3[9] > 0) {
				size = Math.max(v3[8] * 3, 0.5f);
				size1 = Math.max((v3[8] + v3[9]) * 3, 0.5f);
			}
		}
		// 블렌드: PTC 플래그 0x8000 이 있는 모양(피 SKLpart 0x8680, 먼지 dustpart 0x88680)은 일반 알파, 없으면 가산
		// (불 Tfire 0x691, 번개 0x80281, 베기 반짝임 0x80680) [추정: 플래그-재질 대응]
		devple.gow.engine.asset.RenderModel.Blend blend = (sh.flags() & 0x8000) != 0 ? devple.gow.engine.asset.RenderModel.Blend.ALPHA
				: devple.gow.engine.asset.RenderModel.Blend.ADD;
		ParticleDef pd = new ParticleDef(em.name(), Math.max(em.rate(), 0), life, 0.15f, new float[]{r, r, r}, new float[3], new float[3], acc,
				0.3f, size, size1, c0, c1, 0.02f, sh.renderMode() == 6 ? size * 2 : 0, 1.5f,
				base != null ? base.texture() : null, 1, blend,
				spread, em.speed() * 16, em.speedRnd() * 16, fade,
				base != null ? base.alphaA() : 0, base != null ? base.alphaB() : 0);
		// 원작 PTC VU 식이 있으면 색·크기·위치·블렌드를 그것으로 (16 문서 §2.4) — 위 근사값은 VU 없는 모양용
		return sh.vu() != null ? pd.withPtc(sh) : pd;
	}

	@Override
	public void close() {
		models.values().forEach(m -> {
			if (m != null) {
				m.close();
			}
		});
		owned.forEach(WadAssets::close);
		if (ps != null) {
			ps.close();
			skinned.close();
		}
	}
}
