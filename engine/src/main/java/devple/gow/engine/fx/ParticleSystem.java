package devple.gow.engine.fx;

import devple.gow.engine.asset.RenderModel;
import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Shader;
import devple.gow.engine.render.Texture;
import devple.gow.tools.fx.FxResources;
import devple.gow.tools.fx.PtcVu;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.lwjgl.opengl.GL41C.*;

/**
 * 범용 파티클 시스템 (31 문서 §4). CPU 시뮬레이션 + 동적 VBO 한 개로 사각형을 그린다 (M 시리즈에서 수천 개 여유).
 * 이미터({@link Emitter})는 {@link ParticleDef} 로 기술되며 PTC_ 해석값 또는 SCR_Weather 에서 만든다.
 * <pre>
 * ParticleSystem ps = new ParticleSystem(8192);
 * ParticleSystem.Emitter em = ps.add(def, position);   // 이미터 (위치 이동은 em.pos)
 * ps.update(dt, cam.eye); ps.render(cam, aspect);
 * </pre>
 */
public final class ParticleSystem implements AutoCloseable {

	/** 정점: pos3 uv2 color4. */
	private static final int STRIDE = 9;

	/** 하나의 이미터. 위치는 소유자가 매 프레임 옮겨도 된다. */
	public static final class Emitter {
		public final ParticleDef def;
		public final Vector3f pos = new Vector3f();
		/** 카메라를 따라가는 생성 상자 (비/눈): true 면 pos 를 무시하고 카메라 주변에 만든다. */
		public boolean followCamera;
		public boolean active = true;
		/** 레벨 배치 이미터의 소유 인스턴스 (소문자, 가시 칸 컨텍스트로 끈다 — {@link LevelFx#setContextHidden}). null = 없음. */
		public String owner;
		/**
		 * 하늘 GO 의 이미터 (SCR_Sky 소유 GO — 도시 화톳불, 아레스의 불덩이·재): 0x218848 이 GO 의 모델 (0x330514) 과 파티클 (0x330524) 부품을
		 * 모두 0x218778 → 0x150738(부품, 1) = 층 1 로 둔다 → 세계 (층 2–4) 보다 먼저 그려지고 세계가 덮는다 [확실: 부품 목록 둘 / 추정: 0x330524 = 파티클].
		 * 일반 패스 ({@link #render}) 는 건너뛰고 하늘 단계 ({@link #renderSky}) 에서만 그린다.
		 */
		public boolean sky;
		/** 원뿔 모드 방향 (월드, 정규화). */
		public final Vector3f dir = new Vector3f(0, 1, 0);
		/**
		 * 커브 이미터 (FXC 서브타입 4, 원작 0x1249C8): 커브 위 점 (월드, t 를 고르게 나눈 표본 xyz…) 과 접선 (정규화 xyz…). null = 커브가 아님.
		 * 생성: t = 균일 난수 → 위치 P(t), 방향 b = dir + T·접선 속력 + N·법선 속력 (N = (난수 − ½) × T 정규화, P+0x24 / P+0x28),
		 * 속도 = b × (speed ± rnd)·16 (퍼짐 0) / 퍼짐 &gt; 0 이면 b 둘레 원뿔, 위치 += b̂ × lerp(최소, 최대 거리)·16 [확실: 흐름 / 근사: 원뿔·정규화 세부].
		 */
		public float[] curve, curveTan;
		/** 커브 이미터 접선 / 법선 속력 (P+0x24 / P+0x28, dir 에 대한 비), 생성 거리 최소·최대 (×16 월드). */
		public float tangentSpeed, normalSpeed, distMin, distMax;
		/**
		 * 원뿔·전방향 이미터 (FXC 서브타입 1/2) 의 생성 거리 (월드): 위치 = 이미터 원점 + 뽑은 방향 · lerp(최소, 최대) (0x122E78 이 P+0x18/0x1C × 16 을
		 * 이미터 월드 행렬로 옮긴다 — 배율 포함) [확실]. 둘 다 0 이면 예전 상자 생성 ({@link ParticleDef#box}).
		 */
		public float coneDistMin, coneDistMax;
		/**
		 * CPU 동역학을 쓰는가 ({@link ParticleDef#cpuMotion} 과 함께): 레벨 배치 이미터 · 레벨 GO 이펙트 (관절 이미터) 는 켠다. 근사 속력을 쓰는 이펙트
		 * (R_PERMK 물보라 등 — 이미터 행렬 배율을 속력에 넣지 않았다) 는 예전처럼 끈다 [근사].
		 */
		public boolean cpuMotion;
		/** CPU 동역학 파티클의 필드 가속 (월드/초², 소유 GO 의 중력·균일 필드 합 — 0x12B530) [근사: 상수 가속]. */
		public final Vector3f fieldAccel = new Vector3f();
		/** LOD (FXC 0xDC/0xE0, ×16 월드): 생성률 × (far − d/16)/(far − near), near &lt; 0 = 끔. */
		public float lodNear = -1, lodFar = -1;
		/** followCamera 일 때 상자 중심을 카메라 앞으로 이 거리만큼 (원작 셀/2 − near). */
		public float ahead;
		/** SCR_Weather 원작 경로 (null 이 아니면 생성률 대신 최대 개수를 한 번 채우고 원작 갱신 fn 으로 움직인다, {@link Weather}). */
		public Weather.Sim weather;
		final float[] weatherMove = new float[3];
		/**
		 * 이펙트 GO 가 매 프레임 덮어쓰는 FXC P 블록 값 (40 문서 §3, ANM 타입 10 채널). 음수/NaN = 정의 값 사용.
		 * rate = 초당 생성 수, speed / speedRnd / cone = 원뿔 모드 속력·폭·퍼짐, sizeScale = 크기 배율 (인스턴스 스케일).
		 */
		public float rate = -1, speed = Float.NaN, speedRnd = Float.NaN, cone = Float.NaN, sizeScale = 1;
		/**
		 * 소유자 월드 행렬 (회전 + 이동, 배율 없음). PTC 플래그 0x10 / 0x20 이면 파티클이 이 행렬의 회전 / 이동을 따라간다
		 * (원작 0x15BE18: 시스템 행렬 +0x70 에서 0x10 이 없으면 회전 행을 단위로, 0x20 이 없으면 이동을 0 으로; 이미터는 그 역행렬 공간에
		 * 생성 0x1227C8, 렌더는 시스템 행렬 · 뷰 0x15E2F8) [확실]. 기본 단위 행렬 = 월드.
		 */
		public final Matrix4f space = new Matrix4f();
		/** 평면 무늬 (VU 종류 9: 피 자국·파문) 의 바닥 법선 (월드). 원작 0x1F8990 은 바닥 충돌 법선으로 축을 만든다 [확실]. */
		public final Vector3f normal = new Vector3f(0, 1, 0);
		/** 이 프레임의 파티클 공간 (플래그 반영) 과 역행렬. */
		final Matrix4f cur = new Matrix4f(), curInv = new Matrix4f();
		boolean local;
		float carry;

		Emitter(ParticleDef def) {
			this.def = def;
		}
	}

	private final int max;
	private final float[] px, py, pz, vx, vy, vz, age, life, seed;
	/** 생성 위치·속도 (VU 식 레코드: q0 = (위치, 출생), q1 = (속도, 0)). */
	private final float[] p0x, p0y, p0z, v0x, v0y, v0z;
	private final Emitter[] owner;
	private int count;
	private final List<Emitter> emitters = new ArrayList<>();
	private final Random rnd = new Random(1234);
	private final Shader shader;
	private final int vao, vbo;
	private final FloatBuffer buf;
	/** 정점 모으기 (자바 배열, put) → upload 때 buf 로 한 번에 복사. sp = 다음 칸. */
	private float[] stage;
	private int sp;
	private final Vector3f eye = new Vector3f();
	private final Vector3f fwd = new Vector3f(0, 0, -1);
	private final Vector3f centre = new Vector3f();

	/** 카메라 시선 방향 (상자 이미터 중심 계산용). */
	public void setForward(Vector3f f) {
		if (f.lengthSquared() > 1e-8f) {
			fwd.set(f).normalize();
		}
	}

	private Vector3f centre(Emitter e) {
		return centre.set(fwd).mul(e.ahead).add(eye);
	}

	public ParticleSystem(int max) {
		this.max = max;
		px = new float[max];
		py = new float[max];
		pz = new float[max];
		vx = new float[max];
		vy = new float[max];
		vz = new float[max];
		age = new float[max];
		life = new float[max];
		seed = new float[max];
		p0x = new float[max];
		p0y = new float[max];
		p0z = new float[max];
		v0x = new float[max];
		v0y = new float[max];
		v0z = new float[max];
		owner = new Emitter[max];
		shader = new Shader("particle.vert", "particle.frag");
		buf = MemoryUtil.memAllocFloat(max * 6 * STRIDE);
		stage = new float[Math.min(max * 6, 1024) * STRIDE]; // 필요하면 buf 용량까지 늘린다 (레벨마다 수 MB 를 미리 잡지 않게)
		vao = devple.gow.engine.render.GlStats.vao(glGenVertexArrays());
		glBindVertexArray(vao);
		vbo = devple.gow.engine.render.GlStats.buf(glGenBuffers());
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		glBufferData(GL_ARRAY_BUFFER, (long) max * 6 * STRIDE * Float.BYTES, GL_STREAM_DRAW);
		int s = STRIDE * Float.BYTES;
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, s, 0);
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 2, GL_FLOAT, false, s, 3L * Float.BYTES);
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 4, GL_FLOAT, false, s, 5L * Float.BYTES);
		glBindVertexArray(0);
	}

	public Emitter add(ParticleDef def, float x, float y, float z) {
		Emitter e = new Emitter(def);
		e.pos.set(x, y, z);
		emitters.add(e);
		return e;
	}

	public List<Emitter> emitters() {
		return emitters;
	}

	/**
	 * 정의가 doomed 인 이미터와 이미 나온 그 파티클을 지운다 — 텍스처를 가진 WAD 가 내려갈 때 (EffectSystem#remove, 43 §27). 다른 파티클 순서는
	 * 수명 끝 제거와 같은 방식 (마지막 것을 빈자리로).
	 */
	public void purge(java.util.function.Predicate<ParticleDef> doomed) {
		emitters.removeIf(e -> doomed.test(e.def));
		for (int i = 0; i < count; i++) {
			if (doomed.test(owner[i].def)) {
				int l = --count;
				copy(l, i);
				i--;
			}
		}
	}

	public int count() {
		return count;
	}

	/** 시뮬레이션: 생성(초당 rate), 중력/항력, 수명 종료 제거. */
	public void update(float dt, Vector3f camEye) {
		eye.set(camEye);
		dt = Math.min(dt, 0.1f);
		for (Emitter e : emitters) {
			updateSpace(e);
			if (!e.active) {
				continue;
			}
			if (e.weather != null) {
				Weather.Sim w = e.weather;
				w.frameMove(dt, e.weatherMove);
				if (!w.filled) {
					w.filled = true;
					fillWeather(e);
				}
				continue;
			}
			float lod = 1;
			if (e.lodNear >= 0 && e.lodFar > e.lodNear && !e.followCamera) {
				float d = eye.distance(e.pos) / 16f;
				lod = Math.clamp((e.lodFar - d) / (e.lodFar - e.lodNear), 0f, 1f);
			}
			e.carry += (e.rate >= 0 ? e.rate : e.def.rate()) * dt * lod;
			int n = (int) e.carry;
			e.carry -= n;
			for (int k = 0; k < n && count < max; k++) {
				spawn(e);
			}
		}
		for (int i = 0; i < count; i++) {
			ParticleDef d = owner[i].def;
			if (owner[i].weather != null) {
				stepWeather(i, owner[i], dt);
				continue;
			}
			age[i] += dt;
			if (age[i] >= life[i]) {
				int l = --count;
				copy(l, i);
				i--;
				continue;
			}
			if (d.vu()) {
				continue; // 위치·색·크기는 렌더 때 VU 식으로 (원작도 매 프레임 레코드 + 나이로 다시 계산)
			}
			vx[i] += d.accel()[0] * dt;
			vy[i] += d.accel()[1] * dt;
			vz[i] += d.accel()[2] * dt;
			if (d.drag() > 0) {
				float k = Math.max(0, 1 - d.drag() * dt);
				vx[i] *= k;
				vy[i] *= k;
				vz[i] *= k;
			}
			px[i] += vx[i] * dt;
			py[i] += vy[i] * dt;
			pz[i] += vz[i] * dt;
			if (owner[i].followCamera) {
				wrap(i, d);
			}
		}
	}

	/** 원작 날씨 초기화 (0x21AFD8 / 0x21BB38 / 0x21BED0): 정육면체 안 균일 난수 위치, 속도 0. */
	private void fillWeather(Emitter e) {
		Weather.Sim w = e.weather;
		Vector3f c = w.track ? centre(e) : e.pos;
		for (int k = 0; k < w.count && count < max; k++) {
			int i = count++;
			owner[i] = e;
			px[i] = c.x + r(w.half);
			py[i] = c.y + r(w.half);
			pz[i] = c.z + r(w.half);
			vx[i] = vy[i] = vz[i] = 0;
			life[i] = Float.MAX_VALUE;
			seed[i] = rnd.nextFloat();
			stepWeather(i, e, 0);
		}
	}

	/**
	 * 원작 날씨 갱신 (비 0x21AD60, 플랑크톤 0x21B768, 먼지 0x21BCB0): 공통 이동 (+ 플랑크톤 난수 걸음), 정육면체로 감싸기,
	 * 볼륨 상자 밖이면 숨김 (나이 −1 = 원작 출생 −1). 레코드 = (위치, 출생) + 비는 방향 (속성 6).
	 */
	private void stepWeather(int i, Emitter e, float dt) {
		Weather.Sim w = e.weather;
		float mx = e.weatherMove[0], my = e.weatherMove[1], mz = e.weatherMove[2];
		if (w.type == 2 && dt > 0) {
			float f = dt * 60;   // 원작은 프레임 단위 (월드/프레임)
			vx[i] = Math.clamp(vx[i] + r(Weather.PLANKTON_STEP), -Weather.PLANKTON_MAX, Weather.PLANKTON_MAX);
			vy[i] = Math.clamp(vy[i] + r(Weather.PLANKTON_STEP), -Weather.PLANKTON_MAX, Weather.PLANKTON_MAX);
			vz[i] = Math.clamp(vz[i] + r(Weather.PLANKTON_STEP), -Weather.PLANKTON_MAX, Weather.PLANKTON_MAX);
			mx += vx[i] * f;
			my += vy[i] * f;
			mz += vz[i] * f;
		}
		Vector3f c = w.track ? centre(e) : e.pos;
		px[i] = c.x + wrap1(px[i] + mx - c.x, w.half);
		py[i] = c.y + wrap1(py[i] + my - c.y, w.half);
		pz[i] = c.z + wrap1(pz[i] + mz - c.z, w.half);
		age[i] = w.inside(px[i], py[i], pz[i]) ? w.age : -1;
		p0x[i] = px[i];
		p0y[i] = py[i];
		p0z[i] = pz[i];
		if (w.type == 0) {
			v0x[i] = w.dir[0];
			v0y[i] = w.dir[1];
			v0z[i] = w.dir[2];
		} else {
			v0x[i] = vx[i];
			v0y[i] = vy[i];
			v0z[i] = vz[i];
		}
	}

	/** PTC 플래그 0x10 (회전) / 0x20 (이동) → 파티클 공간 행렬 (0x15BE18). */
	private static void updateSpace(Emitter e) {
		int fl = e.def.ptc() != null && e.def.vu() ? e.def.ptc().flags() & 0x30 : 0;
		e.local = fl != 0;
		if (!e.local) {
			return;
		}
		e.cur.identity();
		if ((fl & 0x10) != 0) {
			e.cur.set3x3(new Matrix4f(e.space).normalize3x3());
		}
		if ((fl & 0x20) != 0) {
			e.cur.setTranslation(e.space.m30(), e.space.m31(), e.space.m32());
		}
		e.cur.invertAffine(e.curInv);
	}

	/** 카메라 상자 밖으로 나간 비/눈은 반대편으로 되돌린다 (원작: 상자 안 반복). */
	private void wrap(int i, ParticleDef d) {
		float[] h = d.box();
		Vector3f c = centre(owner[i]);
		px[i] = c.x + wrap1(px[i] - c.x, h[0]);
		py[i] = c.y + wrap1(py[i] - c.y, h[1]);
		pz[i] = c.z + wrap1(pz[i] - c.z, h[2]);
	}

	private static float wrap1(float v, float h) {
		float w = 2 * h;
		return v - (float) Math.floor((v + h) / w) * w;
	}

	private void copy(int from, int to) {
		px[to] = px[from];
		py[to] = py[from];
		pz[to] = pz[from];
		vx[to] = vx[from];
		vy[to] = vy[from];
		vz[to] = vz[from];
		age[to] = age[from];
		life[to] = life[from];
		seed[to] = seed[from];
		p0x[to] = p0x[from];
		p0y[to] = p0y[from];
		p0z[to] = p0z[from];
		v0x[to] = v0x[from];
		v0y[to] = v0y[from];
		v0z[to] = v0z[from];
		owner[to] = owner[from];
	}

	private float r(float a) {
		return (rnd.nextFloat() * 2 - 1) * a;
	}

	private void spawn(Emitter e) {
		ParticleDef d = e.def;
		int i = count++;
		owner[i] = e;
		float[] b = d.box();
		Vector3f c = e.followCamera ? centre(e) : e.pos;
		px[i] = c.x + r(b[0]);
		py[i] = c.y + r(b[1]);
		pz[i] = c.z + r(b[2]);
		if (e.curve != null && e.curve.length >= 6) {
			spawnOnCurve(e, i);
		} else if (d.cone() >= 0) {
			// FXC 서브타입 1 (0x122E78): +Y 기준 원뿔 → 이미터 방향으로 회전
			float coneW = Float.isNaN(e.cone) ? d.cone() : e.cone;
			double th = rnd.nextFloat() * coneW * Math.PI / 2, ph = rnd.nextFloat() * 2 * Math.PI;
			float lx = (float) (Math.sin(ph) * Math.sin(th)), ly = (float) Math.cos(th), lz = (float) (Math.cos(ph) * Math.sin(th));
			Vector3f up = e.dir;
			Vector3f t1 = Math.abs(up.y) < 0.99f ? new Vector3f(0, 1, 0).cross(up).normalize() : new Vector3f(1, 0, 0);
			Vector3f t2 = new Vector3f(up).cross(t1);
			float spd = Float.isNaN(e.speed) ? d.speed() : e.speed, sr = Float.isNaN(e.speedRnd) ? d.speedRnd() : e.speedRnd;
			float sp = spd - sr / 2 + rnd.nextFloat() * sr;
			float wx = t1.x * lx + up.x * ly + t2.x * lz, wy = t1.y * lx + up.y * ly + t2.y * lz, wz = t1.z * lx + up.z * ly + t2.z * lz;
			vx[i] = wx * sp;
			vy[i] = wy * sp;
			vz[i] = wz * sp;
			if (!e.followCamera && (e.coneDistMin != 0 || e.coneDistMax != 0)) {
				// 생성 거리 (0x122E78): 원점 + 뽑은 방향 · lerp(최소, 최대) — 상자 생성 대신
				float dist = e.coneDistMin + rnd.nextFloat() * (e.coneDistMax - e.coneDistMin);
				px[i] = c.x + wx * dist;
				py[i] = c.y + wy * dist;
				pz[i] = c.z + wz * dist;
			}
		} else {
			float[] v = d.velocity();
			float[] s = d.spread();
			vx[i] = v[0] + r(s[0]);
			vy[i] = v[1] + r(s[1]);
			vz[i] = v[2] + r(s[2]);
		}
		age[i] = 0;
		life[i] = d.life() * (1 + r(d.lifeJitter()));
		seed[i] = rnd.nextFloat();
		p0x[i] = px[i];
		p0y[i] = py[i];
		p0z[i] = pz[i];
		v0x[i] = vx[i];
		v0y[i] = vy[i];
		v0z[i] = vz[i];
		if (e.local) {
			// 이미터 공간 = 시스템 행렬의 역 (0x1227C8): 레코드는 파티클 공간 좌표
			Vector3f q = e.curInv.transformPosition(new Vector3f(px[i], py[i], pz[i]));
			Vector3f w = e.curInv.transformDirection(new Vector3f(vx[i], vy[i], vz[i]));
			p0x[i] = q.x;
			p0y[i] = q.y;
			p0z[i] = q.z;
			v0x[i] = w.x;
			v0y[i] = w.y;
			v0z[i] = w.z;
		}
	}

	/**
	 * 커브 이미터 한 파티클 (원작 0x1249C8) [확실: 흐름 — t 균일, P(t)·T(t), 방향 = dir + T·ts + N·ns, 속도 = 방향 × 속력 / 근사: 퍼짐 원뿔].
	 * 위치·접선은 {@link Emitter#curve} 표본 사이 선형 보간 (표본은 t 를 고르게 나눔 — 원작은 다항식을 그 t 에서 바로 계산).
	 */
	private void spawnOnCurve(Emitter e, int i) {
		ParticleDef d = e.def;
		int n = e.curve.length / 3;
		float u = rnd.nextFloat() * (n - 1);
		int k = Math.min((int) u, n - 2);
		float f = u - k;
		float[] cv = e.curve, ct = e.curveTan;
		float x = cv[3 * k] + (cv[3 * k + 3] - cv[3 * k]) * f;
		float y = cv[3 * k + 1] + (cv[3 * k + 4] - cv[3 * k + 1]) * f;
		float z = cv[3 * k + 2] + (cv[3 * k + 5] - cv[3 * k + 2]) * f;
		Vector3f t = new Vector3f(ct[3 * k] + (ct[3 * k + 3] - ct[3 * k]) * f, ct[3 * k + 1] + (ct[3 * k + 4] - ct[3 * k + 1]) * f,
				ct[3 * k + 2] + (ct[3 * k + 5] - ct[3 * k + 2]) * f);
		if (t.lengthSquared() > 1e-12f) {
			t.normalize();
		}
		// 방향 b = dir + T·접선 속력 + N·법선 속력, N = ((난수 − ½)³ × T) 정규화 (0x124C90 – 0x124D9C)
		Vector3f bdir = new Vector3f(e.dir);
		if (e.tangentSpeed != 0) {
			bdir.fma(e.tangentSpeed, t);
		}
		if (e.normalSpeed != 0) {
			Vector3f nrm = new Vector3f(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).cross(t);
			if (nrm.lengthSquared() > 1e-12f) {
				bdir.fma(e.normalSpeed, nrm.normalize());
			}
		}
		float bl = bdir.length();
		if ((e.distMin != 0 || e.distMax != 0) && bl > 1e-6f) {
			// 생성 거리: 방향으로 (최소 + 난수 · (최대 − 최소))·16 (0x124E2C – 0x124E8C)
			float dist = (e.distMin + rnd.nextFloat() * (e.distMax - e.distMin)) * 16f;
			x += bdir.x / bl * dist;
			y += bdir.y / bl * dist;
			z += bdir.z / bl * dist;
		}
		px[i] = x;
		py[i] = y;
		pz[i] = z;
		float spd = Float.isNaN(e.speed) ? d.speed() : e.speed, sr = Float.isNaN(e.speedRnd) ? d.speedRnd() : e.speedRnd;
		float sp = spd - sr / 2 + rnd.nextFloat() * sr;
		float coneW = Float.isNaN(e.cone) ? d.cone() : e.cone;
		if (coneW > 0 && bl > 1e-6f) {
			// 퍼짐 &gt; 0 (0x124EF8): b 둘레 원뿔 θ = 난수·퍼짐·π/2 [근사: 원작 축 구성은 미해석]
			Vector3f up = new Vector3f(bdir).div(bl);
			double th = rnd.nextFloat() * coneW * Math.PI / 2, ph = rnd.nextFloat() * 2 * Math.PI;
			float lx = (float) (Math.sin(ph) * Math.sin(th)), ly = (float) Math.cos(th), lz = (float) (Math.cos(ph) * Math.sin(th));
			Vector3f t1 = Math.abs(up.y) < 0.99f ? new Vector3f(0, 1, 0).cross(up).normalize() : new Vector3f(1, 0, 0);
			Vector3f t2 = new Vector3f(up).cross(t1);
			float m = bl * sp;
			vx[i] = (t1.x * lx + up.x * ly + t2.x * lz) * m;
			vy[i] = (t1.y * lx + up.y * ly + t2.y * lz) * m;
			vz[i] = (t1.z * lx + up.z * ly + t2.z * lz) * m;
		} else {
			// 퍼짐 0 (0x1250C8): 속도 = b × 속력 (b 는 정규화하지 않는다)
			vx[i] = bdir.x * sp;
			vy[i] = bdir.y * sp;
			vz[i] = bdir.z * sp;
		}
	}

	/** 미리 채우기: 비처럼 시작부터 화면에 차 있어야 하는 이미터. */
	public void prewarm(float seconds, Vector3f camEye) {
		for (float t = 0; t < seconds; t += 1 / 30f) {
			update(1 / 30f, camEye);
		}
	}

	/** 반투명 패스. 깊이 테스트 O, 쓰기 X. 이미터 정의별로 텍스처/블렌드를 바꿔 그린다. */
	public void render(Camera cam, float aspect) {
		renderPass(cam, aspect, false);
	}

	/** 하늘 이미터 ({@link Emitter#sky}) 의 파티클만 — 하늘 단계 (세계보다 먼저) 에서 부른다 (43 §17). */
	public void renderSky(Camera cam, float aspect) {
		renderPass(cam, aspect, true);
	}

	/** 지금 그리는 쪽 (하늘 단계 = true): {@link #bucket} 이 소유 이미터의 {@link Emitter#sky} 가 같은 파티클만 모은다. */
	private boolean skyPass;

	private void renderPass(Camera cam, float aspect, boolean sky) {
		if (count == 0) {
			return;
		}
		if (sky) {
			boolean any = false;
			for (int i = 0; i < count && !any; i++) {
				any = owner[i].sky;
			}
			if (!any) {
				return;
			}
		}
		skyPass = sky;
		long t0 = System.nanoTime();
		try {
			renderAll(cam, aspect);
		} finally {
			renderNanos += System.nanoTime() - t0;
			skyPass = false;
		}
	}

	/** 모든 파티클 시스템의 render 누적 시간 (진단, 메인 스레드). */
	private static long renderNanos, renderNanosMark, framesMark, frames;

	static {
		// -Dgow.perf 줄에 프레임당 파티클 그리기 ms (CPU + GL 호출, 48 문서 §9)
		devple.gow.engine.core.PerfMonitor.gauge("ptcMs", () -> {
			long df = frames - framesMark, dn = renderNanos - renderNanosMark;
			framesMark = frames;
			renderNanosMark = renderNanos;
			return String.format(java.util.Locale.ROOT, "%.2f", df == 0 ? 0 : dn / 1e6 / df);
		});
	}

	private void renderAll(Camera cam, float aspect) {
		Matrix4f vp = cam.viewProj(aspect);
		Vector3f fw = new Vector3f(cam.target).sub(cam.eye).normalize();
		Vector3f right = new Vector3f(fw).cross(0, 1, 0);
		if (right.lengthSquared() < 1e-6f) {
			right.set(1, 0, 0);
		}
		right.normalize();
		Vector3f up = new Vector3f(right).cross(fw).normalize();
		shader.use();
		shader.set("uViewProj", vp);
		shader.set("uTex", 0);
		// 안개 (SceneFog): 원작 안개 패스는 층 2 파티클 버킷 0–5 뒤 → 그 파티클만 장면 깊이의 계수 (하늘 단계는 하늘과 같은 배경 계수)
		fogScene = SceneFog.current();
		fogPass = fogScene != null ? fogScene.applyParticles(shader, skyPass) : SceneFog.PASS_NONE;
		fogSet = -1;
		fogDepthBound = false;
		glEnable(GL_BLEND);
		glDepthMask(false);
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		// 정의 목록은 살아 있는 파티클에서 모은다 (이미터를 지운 뒤에도 남은 파티클은 수명까지 그린다). 목록은 equals 로 합치고 (같은 내용의
		// 다른 정의 객체는 처음 것만 — 그리기 루프는 동일성 비교라 나머지 파티클은 그리지 않는다, 예전 그대로), 정의 객체마다 파티클 색인을
		// 한 번에 나눠 둔다 (48 문서 §9: 정의마다 전체 파티클을 훑던 것과 같은 순서). contains (깊은 equals) 는 정의 객체마다 한 번만
		List<ParticleDef> defs = this.defs;
		defs.clear();
		bucket();
		for (int k = 0; k < nSlots; k++) {
			if (!defs.contains(slotDefs[k])) {
				defs.add(slotDefs[k]);
			}
		}
		Vector3f a = new Vector3f(), bAx = new Vector3f(), tmp = new Vector3f();
		// 열기 아지랑이 (종류 5) 는 렌더 버킷 6 (flags &amp; 0x4040) = 마지막 → 다른 파티클을 다 그린 뒤 프레임을 복사해 쓴다
		defs.sort(java.util.Comparator.comparingInt(d -> d.vu() && d.ptc().vu().kind == 5 ? 1 : 0));
		boolean copied = false;
		this.aspect = aspect;
		for (ParticleDef d : defs) {
			if (!copied && d.vu() && d.ptc().vu().kind == 5 && !HEAT_OFF) {
				copied = true;
				copyFrame();
				glActiveTexture(GL_TEXTURE1);
				glBindTexture(GL_TEXTURE_2D, frameTex);
				glActiveTexture(GL_TEXTURE0);
				shader.set("uFrame", 1);
				glBindVertexArray(vao);
				glBindBuffer(GL_ARRAY_BUFFER, vbo);
			}
			if (d.vu()) {
				int slot = slotOf.get(d);
				renderVu(d, cam, up, right, slotStart[slot], slotStart[slot + 1]);
				continue;
			}
			shader.set("uScreen", 0);
			sp = 0;
			int verts = 0;
			int slot = slotOf.get(d);
			for (int q = slotStart[slot], qe = slotStart[slot + 1]; q < qe; q++) {
				int i = order[q];
				float t = age[i] / life[i];
				float size = (d.size0() + (d.size1() - d.size0()) * t) * owner[i].sizeScale;
				float[] c0 = d.color0(), c1 = d.color1();
				float fade = Math.min(1, Math.min(age[i] / Math.max(d.fadeIn(), 1e-4f), 1));
				float cr = c0[0] + (c1[0] - c0[0]) * t, cg = c0[1] + (c1[1] - c0[1]) * t, cb = c0[2] + (c1[2] - c0[2]) * t;
				float ca = (c0[3] + (c1[3] - c0[3]) * t) * fade;
				if (d.fadeCurve() == 1) {
					ca = c0[3] * Math.min(1, 4 * t * (1 - t) * 1.5f);
				} else if (d.fadeCurve() == 2) {
					ca = c0[3] * Math.clamp((d.alphaA() * t + d.alphaB() * t * t) / 128f, 0f, 1f);
				}
				if (d.stretch() > 0) {
					// 속도 방향으로 늘인 줄기 (비): 축 = 속도, 폭 = 시선과 수직
					tmp.set(vx[i], vy[i], vz[i]);
					if (tmp.lengthSquared() < 1e-8f) {
						tmp.set(0, -1, 0);
					}
					a.set(tmp).normalize(d.stretch() * 0.5f);
					Vector3f view = new Vector3f(px[i], py[i], pz[i]).sub(cam.eye);
					bAx.set(a).cross(view);
					if (bAx.lengthSquared() < 1e-8f) {
						bAx.set(right);
					}
					bAx.normalize(size * 0.5f);
				} else {
					float ang = d.spin() * age[i] + seed[i] * 6.2832f;
					float cs = (float) Math.cos(ang) * size * 0.5f, sn = (float) Math.sin(ang) * size * 0.5f;
					a.set(up).mul(cs).fma(sn, right);
					bAx.set(right).mul(cs).fma(-sn, up);
				}
				float x = px[i], y = py[i], z = pz[i];
				int frame = d.frames() > 1 ? Math.min((int) (t * d.frames()), d.frames() - 1) : 0;
				float v0 = (float) frame / Math.max(d.frames(), 1), v1 = (float) (frame + 1) / Math.max(d.frames(), 1);
				// 사각형 2 삼각형: (-b,-a) (b,-a) (b,a) / (-b,-a) (b,a) (-b,a)
				put(x - bAx.x - a.x, y - bAx.y - a.y, z - bAx.z - a.z, 0, v1, cr, cg, cb, ca);
				put(x + bAx.x - a.x, y + bAx.y - a.y, z + bAx.z - a.z, 1, v1, cr, cg, cb, ca);
				put(x + bAx.x + a.x, y + bAx.y + a.y, z + bAx.z + a.z, 1, v0, cr, cg, cb, ca);
				put(x - bAx.x - a.x, y - bAx.y - a.y, z - bAx.z - a.z, 0, v1, cr, cg, cb, ca);
				put(x + bAx.x + a.x, y + bAx.y + a.y, z + bAx.z + a.z, 1, v0, cr, cg, cb, ca);
				put(x - bAx.x + a.x, y - bAx.y + a.y, z - bAx.z + a.z, 0, v0, cr, cg, cb, ca);
				verts += 6;
			}
			if (verts == 0) {
				continue;
			}
			upload(verts);
			Texture tex = d.texture();
			if (tex != null) {
				tex.bind(0);
				shader.set("uUseTex", 1);
			} else {
				shader.set("uUseTex", 0);
			}
			shader.set("uSoft", 1);
			shader.set("uGs", 0);
			glBlendEquation(GL_FUNC_ADD);
			if (d.blend() == RenderModel.Blend.ADD) {
				glBlendFunc(GL_SRC_ALPHA, GL_ONE);
			} else {
				glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
			}
			fogFor(d, d.blend() != RenderModel.Blend.ADD);
			glDrawArrays(GL_TRIANGLES, 0, verts);
		}
		glBindVertexArray(0);
		glDepthMask(true);
		glDisable(GL_BLEND);
		glBlendEquation(GL_FUNC_ADD);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
	}

	/** 이번 renderAll 의 안개 패스 ({@link SceneFog#applyParticles}) 와 셰이더에 마지막으로 넣은 (uFogPass, uFogDst) 묶음 (−1 = 아직). */
	private int fogPass, fogSet = -1;
	private SceneFog fogScene;
	/** 이번 renderAll 에서 장면 깊이 사본을 묶었는가 (안개 앞 정의를 처음 그릴 때). */
	private boolean fogDepthBound;

	/**
	 * 정의 하나의 안개 (16 §1.2 / {@link SceneFog}): 원작 안개 앞 PTC (층 2 버킷 0–5) 만 — 버킷 6 (flags &amp; 0x4040, 열기 아지랑이 포함) ·
	 * 층 3 (flags 0x80000) · 근사 정의는 안개 없음.
	 *
	 * @param alphaBlend 알파 블렌드 (대상 계수 1 − As) — 가산·감산이면 false
	 */
	private void fogFor(ParticleDef d, boolean alphaBlend) {
		int pass = fogPass != SceneFog.PASS_NONE && SceneFog.preFog(d.ptc()) ? fogPass : SceneFog.PASS_NONE;
		if (pass == SceneFog.PASS_SCENE && !fogDepthBound) {
			fogScene.bindSceneDepth(shader);
			fogDepthBound = true;
		}
		int key = pass * 2 + (alphaBlend ? 1 : 0);
		if (key != fogSet) {
			shader.set("uFogPass", pass);
			shader.set("uFogDst", alphaBlend ? 1 : 0);
			fogSet = key;
		}
	}

	/** 그리기 정의 목록 (프레임마다 다시). */
	private final List<ParticleDef> defs = new ArrayList<>();
	/** 정의 객체 (동일성) → 칸, 칸 → 정의, 칸별 파티클 색인 [slotStart[k], slotStart[k+1]) 의 order (색인 오름차순). */
	private final java.util.IdentityHashMap<ParticleDef, Integer> slotOf = new java.util.IdentityHashMap<>();
	private ParticleDef[] slotDefs = new ParticleDef[16];
	private int[] slotStart = new int[17], slotFill = new int[16];
	private int[] order = new int[0], pslot = new int[0];
	private int nSlots;

	/** 살아 있는 파티클을 정의 객체별로 나눈다 (계수 정렬, 칸 = 처음 나온 순서, 칸 안은 색인 순). */
	private void bucket() {
		slotOf.clear();
		nSlots = 0;
		if (pslot.length < count) {
			pslot = new int[max];
			order = new int[max];
		}
		ParticleDef last = null;
		int lastSlot = -1;
		for (int i = 0; i < count; i++) {
			if (owner[i].sky != skyPass) {
				pslot[i] = -1; // 다른 단계 (하늘 / 세계) 의 파티클
				continue;
			}
			ParticleDef d = owner[i].def;
			if (d != last) {
				Integer k = slotOf.get(d);
				if (k == null) {
					k = nSlots++;
					slotOf.put(d, k);
					if (k >= slotDefs.length) {
						slotDefs = java.util.Arrays.copyOf(slotDefs, k * 2);
						slotFill = java.util.Arrays.copyOf(slotFill, k * 2);
						slotStart = java.util.Arrays.copyOf(slotStart, k * 2 + 1);
					}
					slotDefs[k] = d;
				}
				lastSlot = k;
				last = d;
			}
			pslot[i] = lastSlot;
		}
		java.util.Arrays.fill(slotFill, 0, nSlots, 0);
		for (int i = 0; i < count; i++) {
			if (pslot[i] >= 0) {
				slotFill[pslot[i]]++;
			}
		}
		slotStart[0] = 0;
		for (int k = 0; k < nSlots; k++) {
			slotStart[k + 1] = slotStart[k] + slotFill[k];
			slotFill[k] = slotStart[k];
		}
		for (int i = 0; i < count; i++) {
			if (pslot[i] >= 0) {
				order[slotFill[pslot[i]]++] = i;
			}
		}
	}

	// ---- 원작 VU 식 렌더 (16 문서 §2.4) ----

	private float aspect = 1;
	private final float[][] rec = new float[6][4];
	private final java.util.Map<ParticleDef, PtcVu.Eval> evals = new java.util.IdentityHashMap<>();
	private int vuVerts;

	/** PS2 화면 높이 (선/점 1 화소 폭 환산). */
	private static final float GS_LINES = 448f;
	/** 원작 q21.z = 1/60 (이전 프레임 위치, 선 길이). */
	private static final float VU_DT = 1 / 60f;
	private static final boolean DEBUG = System.getenv("GOW_PTCVU_LOG") != null;
	private static final boolean HEAT_OFF = "off".equals(System.getenv("GOW_HEAT"));

	/**
	 * 원작 파티클 마이크로코드 그대로: 출력 0x222 색 (0x80 = 1.0), 위치·크기·방향은 종류별 ({@link PtcVu}). 블렌드 = PTC flags&amp;3
	 * (0x44 알파 / 0x48 가산 / 0x42 감산), TEX0.TCC/TFX = flags 0x400 / bits 2–3, 깊이 쓰기 = flags 0x20000.
	 */
	private void renderVu(ParticleDef d, Camera cam, Vector3f up, Vector3f right, int from, int to) {
		FxResources.Shape sh = d.ptc();
		PtcVu vu = sh.vu();
		if (vu.kind == 5 && (frameTex == 0 || HEAT_OFF)) {
			return; // 프레임 복사본이 없으면 (화면 크기 0) 생략, GOW_HEAT=off 비교용
		}
		PtcVu.Eval ev = evals.computeIfAbsent(d, k -> vu.newEval());
		Texture tex = vu.kind == 5 ? null : d.texture();
		shader.set("uGs", 1);
		shader.set("uScreen", vu.kind == 5 ? 1 : 0);
		shader.set("uUseTex", tex != null ? 1 : 0);
		shader.set("uTfx", sh.tfx());
		shader.set("uTcc", sh.textureAlpha() ? 1 : 0);
		if (tex != null) {
			tex.bind(0);
		}
		switch (sh.gsBlend()) {
			case 0x48 -> {
				glBlendEquation(GL_FUNC_ADD);
				glBlendFunc(GL_ONE, GL_ONE);
			}
			case 0x42 -> {
				glBlendEquation(GL_FUNC_REVERSE_SUBTRACT);
				glBlendFunc(GL_ONE, GL_ONE);
			}
			default -> {
				glBlendEquation(GL_FUNC_ADD);
				glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
			}
		}
		fogFor(d, sh.gsBlend() == 0x44);
		glDepthMask(sh.depthWrite());
		float texW = tex != null ? tex.width() * 16f : 1024f, texH = tex != null ? tex.height() * 16f : 1024f;
		float tanHalf = (float) Math.tan(Math.toRadians(cam.fovDeg) / 2);
		sp = 0;
		vuVerts = 0;
		Vector3f a = new Vector3f(), b = new Vector3f(), p = new Vector3f(), t = new Vector3f();
		int kind = vu.kind;
		int segs = kind == 4 || kind == 5 ? vu.fanSegments() : 0;
		Vector3f fw = new Vector3f(cam.target).sub(cam.eye).normalize();
		// 꼬리 +1/+2 = GS UV (텍셀×16, PRIM FST=1): 보통 (1024,0)/(0,1024) = 64×64 텍스처 전체 [확실: SKLpart 64×64]
		float du0 = 0, dv0 = 0, du1 = 1, dv1 = 1;
		if (tex != null && kind != 4 && kind != 7 && kind != 0) {
			int a0 = vu.tailBits(1, 0), b0 = vu.tailBits(1, 1), a1 = vu.tailBits(2, 0), b1 = vu.tailBits(2, 1);
			if (a0 >= 0 && a1 >= 0 && b0 >= 0 && b1 >= 0 && Math.max(a0, a1) <= 0x4000 && Math.max(b0, b1) <= 0x4000 && a0 != a1 && b0 != b1) {
				du0 = Math.min(a0, a1) / texW;
				du1 = Math.max(a0, a1) / texW;
				dv0 = Math.min(b0, b1) / texH;
				dv1 = Math.max(b0, b1) / texH;
			}
		}
		for (int q = from; q < to; q++) {
			int i = order[q]; // 이 정의 객체의 파티클 (색인 순, bucket)
			if (age[i] < 0) {
				continue;   // 날씨: 볼륨 밖 (원작 출생 −1 → VU 가 건너뜀)
			}
			record(vu, i);
			vu.eval(ev, rec, Float.floatToRawIntBits(seed[i]) * 2654435761L, age[i], VU_DT);
			float[][] o = ev.out;
			if (owner[i].local) {
				toWorld(owner[i].cur, kind, o);
			}
			float sc = owner[i].sizeScale;
			float u0 = du0, v0 = dv0, u1 = du1, v1 = dv1;
			if (ev.uvSet) {
				u0 = ev.uv[0] / texW;
				v0 = ev.uv[1] / texH;
				u1 = ev.uv[2] / texW;
				v1 = ev.uv[3] / texH;
			}
			float[] c = o[0];
			float cr = c[0] / 128f, cg = c[1] / 128f, cb = c[2] / 128f, ca = c[3] / 128f;
			if (DEBUG && i % 50 == 0) {
				System.err.printf(java.util.Locale.ROOT, "vu %s tex %s uv %d,%d %d,%d k%d age %.2f c=(%.0f,%.0f,%.0f,%.0f) o1=(%.1f,%.1f,%.1f,%.2f) o2w=%.2f%n", d.name(), tex == null ? "-" : tex.width() + "x" + tex.height(),
						vu.tailBits(1, 0), vu.tailBits(1, 1), vu.tailBits(2, 0), vu.tailBits(2, 1), kind,
						age[i], c[0], c[1], c[2], c[3], o[1][0], o[1][1], o[1][2], o[1][3], o[2][3]);
			}
			switch (kind) {
				case 0 -> { // 점: 1 화소
					p.set(o[1][0], o[1][1], o[1][2]);
					float w = pixel(cam, p, tanHalf);
					quad(p, a.set(up).mul(w), b.set(right).mul(w), cr, cg, cb, ca, u0, v0, u1, v1);
				}
				case 1, 2, 3 -> { // 화면 사각형, 반폭 = 0x223.w (월드). 3 은 0x224.w 회전 (월드 위쪽 기준)
					p.set(o[1][0], o[1][1], o[1][2]);
					float s = o[1][3] * sc;
					if (s <= 0) {
						continue;
					}
					float ang = kind == 3 ? o[2][3] : 0;
					float cs = (float) Math.cos(ang) * s, sn = (float) Math.sin(ang) * s;
					a.set(up).mul(cs).fma(sn, right);
					b.set(right).mul(cs).fma(-sn, up);
					quad(p, a, b, cr, cg, cb, ca, u0, v0, u1, v1);
				}
				case 4 -> { // 원반: 중심색 0x222, 테두리색 0x223, 중심·반지름 0x224
					p.set(o[2][0], o[2][1], o[2][2]);
					float s = o[2][3] * sc;
					if (s <= 0) {
						continue;
					}
					float[] e = o[1];
					fan(p, up, right, s, segs, cr, cg, cb, ca, e[0] / 128f, e[1] / 128f, e[2] / 128f, e[3] / 128f);
				}
				case 7 -> { // 선: 이전(0x224)→현재(0x225), 길이 배율 min(나이/dt, 0x224.w), 머리색 0x222 / 꼬리색 0x223
					float f = Math.min(age[i] / VU_DT, o[2][3]);
					t.set(o[3][0] - o[2][0], o[3][1] - o[2][1], o[3][2] - o[2][2]).mul((2 * f - 1) * 0.5f);
					p.set(o[2][0] + o[3][0], o[2][1] + o[3][1], o[2][2] + o[3][2]).mul(0.5f);
					float w = pixel(cam, p, tanHalf);
					float[] e = o[1];
					line(cam, p, t, w, e[0] / 128f, e[1] / 128f, e[2] / 128f, e[3] / 128f, cr, cg, cb, ca);
				}
				case 8 -> { // 방향 줄기: 위치 0x223 (.w 배율), 방향 0x224, 폭 = 시선×방향
					p.set(o[1][0], o[1][1], o[1][2]);
					float s = o[1][3] * sc;
					a.set(o[2][0], o[2][1], o[2][2]).mul(s);
					t.set(p).sub(cam.eye);
					b.set(t).cross(a);
					if (b.lengthSquared() < 1e-10f) {
						continue;
					}
					b.normalize(Math.abs(s));
					corners(vu, p, a, b, cr, cg, cb, ca, u0, v0, u1, v1);
				}
				case 5 -> { // 열기 아지랑이 (0x3DA): 원반, 텍스처 = 프레임 버퍼 (DECAL), 중심 UV 만 굴절량 0x225.w × 화면 반지름만큼 x 로 민다
					p.set(o[2][0], o[2][1], o[2][2]);
					float s = o[2][3] * sc;
					if (s <= 0) {
						continue;
					}
					float depth = t.set(p).sub(cam.eye).dot(fw);
					if (depth < cam.near) {
						continue;
					}
					// 화면 반지름 (UV 비율): P00·r/w (0x3C9 mulw.x vf1 = P00 · 반지름), UV 0..1 은 화면 폭
					float ru = 0.5f * s / (depth * tanHalf * aspect);
					float[] e = o[1];
					heat(p, up, right, s, segs, -o[3][3] * ru, ca, e[3] / 128f);
				}
				case 9 -> { // 평면 사각형: 위치 0x223 (.w 배율), 축 0x224 (x), 0x225 (y)
					p.set(o[1][0], o[1][1], o[1][2]);
					float s = o[1][3] * sc;
					a.set(o[3][0], o[3][1], o[3][2]).mul(s);
					b.set(o[2][0], o[2][1], o[2][2]).mul(s);
					corners(vu, p, a, b, cr, cg, cb, ca, u0, v0, u1, v1);
				}
				default -> {
				}
			}
		}
		flush();
		glDepthMask(false);
	}

	/** 레코드 = 일반 배치 {(위치, 출생), (속도, 0), (속도 방향)} — 종류 9 는 {(위치), 축 A, 축 B} (수평면, 임의 회전) [근사]. */
	private void record(PtcVu vu, int i) {
		for (float[] q : rec) {
			java.util.Arrays.fill(q, 0);
		}
		rec[0][0] = p0x[i];
		rec[0][1] = p0y[i];
		rec[0][2] = p0z[i];
		Emitter o = owner[i];
		if (o.cpuMotion && o.def.cpuMotion()) {
			// CPU 동역학 (0x15C0F0 → 0x12B3F0): 레코드 위치 = 처음 위치 + 속도 · 나이 (+ 필드 가속 · 나이²/2) — 파티클 공간 [확실: 속도 / 근사: 필드는 상수 가속]
			float t = age[i];
			Vector3f a = o.local ? o.curInv.transformDirection(nTmp.set(o.fieldAccel)) : nTmp.set(o.fieldAccel);
			float h = 0.5f * t * t;
			rec[0][0] += v0x[i] * t + a.x * h;
			rec[0][1] += v0y[i] * t + a.y * h;
			rec[0][2] += v0z[i] * t + a.z * h;
		}
		if (vu.kind == 9) {
			// 바닥 무늬 (피 자국·파문): 원작 0x1F8990 은 바닥 충돌(0x11E190) 법선 n 으로 평면 축 A = norm(d × n), B = norm(A × n) 을 만들고
			// 파티클마다 n 축 임의 회전 (0x183D00) [확실]. 축은 레코드 q1 / q2.
			Emitter e = owner[i];
			Vector3f n = e.local ? e.curInv.transformDirection(nTmp.set(e.normal)) : nTmp.set(e.normal);
			if (n.lengthSquared() < 1e-8f) {
				n.set(0, 1, 0);
			}
			n.normalize();
			if (!e.local) {
				// 원작은 위치도 평면 위에 놓는다 (충돌점 + A·u + B·v + n·Offset): 생성 위치를 이미터를 지나는 평면에 투영
				float h = (rec[0][0] - e.pos.x) * n.x + (rec[0][1] - e.pos.y) * n.y + (rec[0][2] - e.pos.z) * n.z;
				rec[0][0] -= n.x * h;
				rec[0][1] -= n.y * h;
				rec[0][2] -= n.z * h;
			}
			Vector3f a = aTmp.set(Math.abs(n.x) < 0.9f ? 1 : 0, Math.abs(n.x) < 0.9f ? 0 : 1, 0).cross(n).normalize();
			Vector3f b = bTmp.set(a).cross(n).normalize();
			float th = seed[i] * 6.2832f;
			float cs = (float) Math.cos(th), sn = (float) Math.sin(th);
			for (int c = 0; c < 3; c++) {
				float ax = a.get(c) * cs + b.get(c) * sn, bx = b.get(c) * cs - a.get(c) * sn;
				rec[1][c] = ax;
				rec[2][c] = bx;
				rec[3][c] = ax;
			}
		} else if (vu.recordQword(PtcVu.ATTR_POS) == 0) {
			// PTC +0x8C 속성 배치대로: 위치 q0, 속도 (스트림 1 이면 보통 q1), 방향 (속성 6: 비 q1, 줄기류 q2). 비·먼지는 속도가 CPU 스트림 0.
			int qv = vu.recordQword(PtcVu.ATTR_VEL), qd = vu.recordQword(PtcVu.ATTR_DIR);
			if (qv > 0 && qv < rec.length) {
				int l0 = vu.recordLane(PtcVu.ATTR_VEL);
				rec[qv][l0] = v0x[i];
				rec[qv][l0 + 1] = v0y[i];
				rec[qv][l0 + 2] = v0z[i];
			}
			if (qd > 0 && qd < rec.length) {
				dirTo(rec[qd], vu.recordLane(PtcVu.ATTR_DIR), i);
			} else if (qv == 1) {
				dirTo(rec[2], 0, i);   // 이전 가정 유지 (q2 = 속도 방향; 속성 3–5 는 미해석)
			}
		} else {
			rec[1][0] = v0x[i];
			rec[1][1] = v0y[i];
			rec[1][2] = v0z[i];
			dirTo(rec[2], 0, i);
		}
	}

	/** 속도 방향 (단위, 0 이면 아래). */
	private void dirTo(float[] q, int l0, int i) {
		float l = (float) Math.sqrt(v0x[i] * v0x[i] + v0y[i] * v0y[i] + v0z[i] * v0z[i]);
		if (l > 1e-6f) {
			q[l0] = v0x[i] / l;
			q[l0 + 1] = v0y[i] / l;
			q[l0 + 2] = v0z[i] / l;
		} else {
			q[l0 + 1] = -1;
		}
	}

	private final Vector3f nTmp = new Vector3f(), aTmp = new Vector3f(), bTmp = new Vector3f(), wTmp = new Vector3f();

	/** 파티클 공간 출력 → 월드 (종류별 위치는 점, 방향·축은 벡터). */
	private void toWorld(Matrix4f m, int kind, float[][] o) {
		switch (kind) {
			case 0, 1, 2, 3 -> pos(m, o[1]);
			case 4, 5 -> pos(m, o[2]);
			case 7 -> {
				pos(m, o[2]);
				pos(m, o[3]);
			}
			case 8 -> {
				pos(m, o[1]);
				dir(m, o[2]);
			}
			case 9 -> {
				pos(m, o[1]);
				dir(m, o[2]);
				dir(m, o[3]);
			}
			default -> {
			}
		}
	}

	private void pos(Matrix4f m, float[] v) {
		m.transformPosition(wTmp.set(v[0], v[1], v[2]));
		v[0] = wTmp.x;
		v[1] = wTmp.y;
		v[2] = wTmp.z;
	}

	private void dir(Matrix4f m, float[] v) {
		m.transformDirection(wTmp.set(v[0], v[1], v[2]));
		v[0] = wTmp.x;
		v[1] = wTmp.y;
		v[2] = wTmp.z;
	}

	/**
	 * 열기 아지랑이 원반 (종류 5): 정점 UV = 그 정점의 화면 위치 (셰이더가 계산) + 오프셋. 중심만 (du, 0) 만큼 밀고 테두리는 0 →
	 * 원반 안의 화면이 중심 쪽으로 끌려 휜다. 알파 = 중심 0x222.a → 테두리 0x223.a (0x44 블렌드), 색은 프레임 그대로 (DECAL).
	 */
	private void heat(Vector3f p, Vector3f up, Vector3f right, float s, int segs, float du, float ca, float ea) {
		ensure(segs * 3);
		float[][] cs = circle(segs);
		for (int k = 0; k < segs; k++) {
			float c0 = cs[0][k] * s, s0 = cs[1][k] * s, c1 = cs[0][k + 1] * s, s1 = cs[1][k + 1] * s;
			put(p.x, p.y, p.z, du, 0, 1, 1, 1, ca);
			put(p.x + right.x * c0 + up.x * s0, p.y + right.y * c0 + up.y * s0, p.z + right.z * c0 + up.z * s0, 0, 0, 1, 1, 1, ea);
			put(p.x + right.x * c1 + up.x * s1, p.y + right.y * c1 + up.y * s1, p.z + right.z * c1 + up.z * s1, 0, 0, 1, 1, 1, ea);
		}
		vuVerts += segs * 3;
	}

	// ---- 프레임 버퍼 복사 (종류 5: 원작은 TEXFLUSH 후 프레임 자신을 텍스처로, 0x401) ----
	private int frameTex, frameW, frameH;
	private final int[] viewport = new int[4];

	/** 지금 그려진 화면을 텍스처로 복사 (GL 4.1: glCopyTexSubImage2D). */
	private void copyFrame() {
		glGetIntegerv(GL_VIEWPORT, viewport);
		int w = viewport[2], h = viewport[3];
		if (w <= 0 || h <= 0) {
			return;
		}
		if (frameTex == 0) {
			frameTex = devple.gow.engine.render.GlStats.tex(glGenTextures());
		}
		glActiveTexture(GL_TEXTURE1);
		glBindTexture(GL_TEXTURE_2D, frameTex);
		if (w != frameW || h != frameH) {
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			frameW = w;
			frameH = h;
		}
		glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, viewport[0], viewport[1], w, h);
		glActiveTexture(GL_TEXTURE0);
	}

	/** PS2 448 줄 기준 1 화소 (×1.5) 의 월드 반폭. */
	private static float pixel(Camera cam, Vector3f p, float tanHalf) {
		float dist = Math.max(p.distance(cam.eye), 1f);
		return dist * tanHalf / GS_LINES * 1.5f;
	}

	private void ensure(int verts) {
		if ((vuVerts + verts) * STRIDE > buf.capacity()) { // 나눠 그리는 경계는 예전과 같은 GL 버퍼 용량 기준
			flush();
		}
	}

	private void flush() {
		if (vuVerts > 0) {
			upload(vuVerts);
			glDrawArrays(GL_TRIANGLES, 0, vuVerts);
		}
		sp = 0;
		vuVerts = 0;
	}

	/**
	 * 모은 정점 (stage[0, sp)) 을 VBO 로. 48 문서 §9: 예전 glBufferSubData(0) 는 같은 프레임의 앞 그리기가 아직 읽는 버퍼를 덮어 GPU 를
	 * 기다렸다 (PAND03G 메인 스레드 시간의 대부분) → glBufferData 로 저장소를 새로 받아 (orphan) 올린다. 올리는 바이트·그리기는 같다.
	 */
	private void upload(int verts) {
		buf.clear();
		buf.put(stage, 0, sp);
		buf.flip();
		hashUpload(buf, verts);
		glBufferData(GL_ARRAY_BUFFER, buf, GL_STREAM_DRAW);
	}

	/** 사각형 p ± a ± b (a = 세로, b = 가로). */
	private void quad(Vector3f p, Vector3f a, Vector3f b, float r, float g, float bl, float al, float u0, float v0, float u1, float v1) {
		ensure(6);
		float x = p.x, y = p.y, z = p.z;
		put(x - b.x - a.x, y - b.y - a.y, z - b.z - a.z, u0, v1, r, g, bl, al);
		put(x + b.x - a.x, y + b.y - a.y, z + b.z - a.z, u1, v1, r, g, bl, al);
		put(x + b.x + a.x, y + b.y + a.y, z + b.z + a.z, u1, v0, r, g, bl, al);
		put(x - b.x - a.x, y - b.y - a.y, z - b.z - a.z, u0, v1, r, g, bl, al);
		put(x + b.x + a.x, y + b.y + a.y, z + b.z + a.z, u1, v0, r, g, bl, al);
		put(x - b.x + a.x, y - b.y + a.y, z - b.z + a.z, u0, v0, r, g, bl, al);
		vuVerts += 6;
	}

	/** 종류 8/9 모서리: p + a·c.y + b·c.x, c = 꼬리+3 / 꼬리+4 (UV 도 같은 짝, 0x2FC / 0x35E). */
	private void corners(PtcVu vu, Vector3f p, Vector3f a, Vector3f b, float r, float g, float bl, float al,
						 float u0, float v0, float u1, float v1) {
		float x0 = vu.tailFloat(3, 0), y0 = vu.tailFloat(3, 1), x1 = vu.tailFloat(4, 0), y1 = vu.tailFloat(4, 1);
		if (x0 == x1 && y0 == y1) {
			x0 = y0 = 1;
			x1 = y1 = -1;
		}
		ensure(6);
		// 모서리 0..3 = (y0,x0) (y0,x1) (y1,x0) (y1,x1), 삼각형 0-1-3, 0-3-2 — 파티클마다 배열을 만들지 않게 직접 (48 문서 §3)
		float c0x = p.x + a.x * y0 + b.x * x0, c0y = p.y + a.y * y0 + b.y * x0, c0z = p.z + a.z * y0 + b.z * x0;
		float c1x = p.x + a.x * y0 + b.x * x1, c1y = p.y + a.y * y0 + b.y * x1, c1z = p.z + a.z * y0 + b.z * x1;
		float c2x = p.x + a.x * y1 + b.x * x0, c2y = p.y + a.y * y1 + b.y * x0, c2z = p.z + a.z * y1 + b.z * x0;
		float c3x = p.x + a.x * y1 + b.x * x1, c3y = p.y + a.y * y1 + b.y * x1, c3z = p.z + a.z * y1 + b.z * x1;
		put(c0x, c0y, c0z, u0, v0, r, g, bl, al);
		put(c1x, c1y, c1z, u1, v0, r, g, bl, al);
		put(c3x, c3y, c3z, u1, v1, r, g, bl, al);
		put(c0x, c0y, c0z, u0, v0, r, g, bl, al);
		put(c3x, c3y, c3z, u1, v1, r, g, bl, al);
		put(c2x, c2y, c2z, u0, v1, r, g, bl, al);
		vuVerts += 6;
	}

	/** 원 분할 표 (segs → cos/sin(2πk/segs), k = 0..segs): 원반·아지랑이 파티클마다 삼각함수를 다시 부르지 않게. 값은 같은 식 그대로. */
	private final java.util.Map<Integer, float[][]> circles = new java.util.HashMap<>();

	private float[][] circle(int segs) {
		float[][] c = circles.get(segs);
		if (c == null) {
			c = new float[2][segs + 1];
			for (int k = 0; k <= segs; k++) {
				double ang = 2 * Math.PI * k / segs;
				c[0][k] = (float) Math.cos(ang);
				c[1][k] = (float) Math.sin(ang);
			}
			circles.put(segs, c);
		}
		return c;
	}

	/** 원반 (종류 4, 0x3A1): 화면을 향한 부채꼴, 중심색 → 테두리색. */
	private void fan(Vector3f p, Vector3f up, Vector3f right, float s, int segs, float r, float g, float bl, float al,
					 float er, float eg, float eb, float ea) {
		ensure(segs * 3);
		float[][] cs = circle(segs);
		for (int k = 0; k < segs; k++) {
			float c0 = cs[0][k] * s, s0 = cs[1][k] * s, c1 = cs[0][k + 1] * s, s1 = cs[1][k + 1] * s;
			put(p.x, p.y, p.z, 0.5f, 0.5f, r, g, bl, al);
			put(p.x + right.x * c0 + up.x * s0, p.y + right.y * c0 + up.y * s0, p.z + right.z * c0 + up.z * s0, 0.5f, 0.5f, er, eg, eb, ea);
			put(p.x + right.x * c1 + up.x * s1, p.y + right.y * c1 + up.y * s1, p.z + right.z * c1 + up.z * s1, 0.5f, 0.5f, er, eg, eb, ea);
		}
		vuVerts += segs * 3;
	}

	/** 선 (종류 7, 0x1E5): 중심 p ± h, 폭 w (화면 1 화소), 꼬리색 → 머리색. */
	private void line(Camera cam, Vector3f p, Vector3f h, float w, float tr, float tg, float tb, float ta,
					  float r, float g, float bl, float al) {
		Vector3f side = new Vector3f(p).sub(cam.eye).cross(h);
		if (side.lengthSquared() < 1e-10f) {
			return;
		}
		side.normalize(w);
		ensure(6);
		float ax = p.x - h.x, ay = p.y - h.y, az = p.z - h.z, bx = p.x + h.x, by = p.y + h.y, bz = p.z + h.z;
		put(ax - side.x, ay - side.y, az - side.z, 0, 0, tr, tg, tb, ta);
		put(ax + side.x, ay + side.y, az + side.z, 0, 1, tr, tg, tb, ta);
		put(bx + side.x, by + side.y, bz + side.z, 1, 1, r, g, bl, al);
		put(ax - side.x, ay - side.y, az - side.z, 0, 0, tr, tg, tb, ta);
		put(bx + side.x, by + side.y, bz + side.z, 1, 1, r, g, bl, al);
		put(bx - side.x, by - side.y, bz - side.z, 1, 0, r, g, bl, al);
		vuVerts += 6;
	}

	private void put(float x, float y, float z, float u, float v, float r, float g, float b, float a) {
		float[] st = stage; // 정점 9 개 float — 직접 배열에 (FloatBuffer 낱개 put 의 경계 검사 없이, 48 §9)
		int o = sp;
		if (o + STRIDE > st.length) {
			st = stage = java.util.Arrays.copyOf(st, Math.min(buf.capacity(), Math.max(o + STRIDE, st.length * 2)));
		}
		st[o] = x;
		st[o + 1] = y;
		st[o + 2] = z;
		st[o + 3] = u;
		st[o + 4] = v;
		st[o + 5] = r;
		st[o + 6] = g;
		st[o + 7] = b;
		st[o + 8] = a;
		sp = o + 9;
	}

	// ---- 검증: 정점 버퍼 해시 (-Dgow.ptcHash=파일, 48 문서 §9) — 최적화 전후 같은 출력인지 프레임마다 비교 ----
	private static final java.io.PrintStream HASH_OUT = openHash();
	private static long hash = 17, hashDraws, hashVerts;

	private static java.io.PrintStream openHash() {
		String f = System.getProperty("gow.ptcHash");
		try {
			return f == null ? null : new java.io.PrintStream(new java.io.FileOutputStream(f), true);
		} catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

	/** 올리는 정점 (flip 된 buf 전체) 과 그리기 정점 수를 누적 해시에. */
	private static void hashUpload(FloatBuffer b, int verts) {
		if (HASH_OUT == null) {
			return;
		}
		long h = hash * 31 + verts;
		for (int k = b.position(), n = b.limit(); k < n; k++) {
			h = h * 1_000_003L ^ Float.floatToRawIntBits(b.get(k));
		}
		hash = h;
		hashDraws++;
		hashVerts += verts;
	}

	/** 프레임 끝 (Engine): 프레임 수 (ptcMs 게이지) + gow.ptcHash 면 "프레임 그리기 정점 해시" 한 줄. */
	public static void hashFrame(long frame) {
		frames++;
		if (HASH_OUT != null) {
			HASH_OUT.printf("%d %d %d %016x%n", frame, hashDraws, hashVerts, hash);
		}
	}

	@Override
	public void close() {
		if (frameTex != 0) {
			devple.gow.engine.render.GlStats.delTex(frameTex);
		}
		devple.gow.engine.render.GlStats.delBuf(vbo);
		devple.gow.engine.render.GlStats.delVao(vao);
		MemoryUtil.memFree(buf);
		shader.close();
	}
}
