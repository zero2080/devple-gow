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
		/** 원뿔 모드 방향 (월드, 정규화). */
		public final Vector3f dir = new Vector3f(0, 1, 0);
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
		vao = glGenVertexArrays();
		glBindVertexArray(vao);
		vbo = glGenBuffers();
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
		if (d.cone() >= 0) {
			// FXC 서브타입 1 (0x122E78): +Y 기준 원뿔 → 이미터 방향으로 회전
			float coneW = Float.isNaN(e.cone) ? d.cone() : e.cone;
			double th = rnd.nextFloat() * coneW * Math.PI / 2, ph = rnd.nextFloat() * 2 * Math.PI;
			float lx = (float) (Math.sin(ph) * Math.sin(th)), ly = (float) Math.cos(th), lz = (float) (Math.cos(ph) * Math.sin(th));
			Vector3f up = e.dir;
			Vector3f t1 = Math.abs(up.y) < 0.99f ? new Vector3f(0, 1, 0).cross(up).normalize() : new Vector3f(1, 0, 0);
			Vector3f t2 = new Vector3f(up).cross(t1);
			float spd = Float.isNaN(e.speed) ? d.speed() : e.speed, sr = Float.isNaN(e.speedRnd) ? d.speedRnd() : e.speedRnd;
			float sp = spd - sr / 2 + rnd.nextFloat() * sr;
			vx[i] = (t1.x * lx + up.x * ly + t2.x * lz) * sp;
			vy[i] = (t1.y * lx + up.y * ly + t2.y * lz) * sp;
			vz[i] = (t1.z * lx + up.z * ly + t2.z * lz) * sp;
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

	/** 미리 채우기: 비처럼 시작부터 화면에 차 있어야 하는 이미터. */
	public void prewarm(float seconds, Vector3f camEye) {
		for (float t = 0; t < seconds; t += 1 / 30f) {
			update(1 / 30f, camEye);
		}
	}

	/** 반투명 패스. 깊이 테스트 O, 쓰기 X. 이미터 정의별로 텍스처/블렌드를 바꿔 그린다. */
	public void render(Camera cam, float aspect) {
		if (count == 0) {
			return;
		}
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
		glEnable(GL_BLEND);
		glDepthMask(false);
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		// 정의 목록은 살아 있는 파티클에서 모은다 (이미터를 지운 뒤에도 남은 파티클은 수명까지 그린다)
		List<ParticleDef> defs = new ArrayList<>();
		ParticleDef last = null;
		for (int i = 0; i < count; i++) {
			ParticleDef d = owner[i].def;
			if (d != last && !defs.contains(d)) {
				defs.add(d);
			}
			last = d;
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
				renderVu(d, cam, up, right);
				continue;
			}
			shader.set("uScreen", 0);
			buf.clear();
			int verts = 0;
			for (int i = 0; i < count; i++) {
				if (owner[i].def != d) {
					continue;
				}
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
			buf.flip();
			glBufferSubData(GL_ARRAY_BUFFER, 0, buf);
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
			glDrawArrays(GL_TRIANGLES, 0, verts);
		}
		glBindVertexArray(0);
		glDepthMask(true);
		glDisable(GL_BLEND);
		glBlendEquation(GL_FUNC_ADD);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
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
	private void renderVu(ParticleDef d, Camera cam, Vector3f up, Vector3f right) {
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
		glDepthMask(sh.depthWrite());
		float texW = tex != null ? tex.width() * 16f : 1024f, texH = tex != null ? tex.height() * 16f : 1024f;
		float tanHalf = (float) Math.tan(Math.toRadians(cam.fovDeg) / 2);
		buf.clear();
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
		for (int i = 0; i < count; i++) {
			if (owner[i].def != d || age[i] < 0) {
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
				float h = (p0x[i] - e.pos.x) * n.x + (p0y[i] - e.pos.y) * n.y + (p0z[i] - e.pos.z) * n.z;
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
		for (int k = 0; k < segs; k++) {
			double a0 = 2 * Math.PI * k / segs, a1 = 2 * Math.PI * (k + 1) / segs;
			float c0 = (float) Math.cos(a0) * s, s0 = (float) Math.sin(a0) * s, c1 = (float) Math.cos(a1) * s, s1 = (float) Math.sin(a1) * s;
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
			frameTex = glGenTextures();
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
		if ((vuVerts + verts) * STRIDE > buf.capacity()) {
			flush();
		}
	}

	private void flush() {
		if (vuVerts > 0) {
			buf.flip();
			glBufferSubData(GL_ARRAY_BUFFER, 0, buf);
			glDrawArrays(GL_TRIANGLES, 0, vuVerts);
		}
		buf.clear();
		vuVerts = 0;
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
		float[][] v = {
				{p.x + a.x * y0 + b.x * x0, p.y + a.y * y0 + b.y * x0, p.z + a.z * y0 + b.z * x0, u0, v0},
				{p.x + a.x * y0 + b.x * x1, p.y + a.y * y0 + b.y * x1, p.z + a.z * y0 + b.z * x1, u1, v0},
				{p.x + a.x * y1 + b.x * x0, p.y + a.y * y1 + b.y * x0, p.z + a.z * y1 + b.z * x0, u0, v1},
				{p.x + a.x * y1 + b.x * x1, p.y + a.y * y1 + b.y * x1, p.z + a.z * y1 + b.z * x1, u1, v1}};
		for (int k : QUAD) {
			put(v[k][0], v[k][1], v[k][2], v[k][3], v[k][4], r, g, bl, al);
		}
		vuVerts += 6;
	}

	private static final int[] QUAD = {0, 1, 3, 0, 3, 2};

	/** 원반 (종류 4, 0x3A1): 화면을 향한 부채꼴, 중심색 → 테두리색. */
	private void fan(Vector3f p, Vector3f up, Vector3f right, float s, int segs, float r, float g, float bl, float al,
					 float er, float eg, float eb, float ea) {
		ensure(segs * 3);
		for (int k = 0; k < segs; k++) {
			double a0 = 2 * Math.PI * k / segs, a1 = 2 * Math.PI * (k + 1) / segs;
			float c0 = (float) Math.cos(a0) * s, s0 = (float) Math.sin(a0) * s, c1 = (float) Math.cos(a1) * s, s1 = (float) Math.sin(a1) * s;
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
		buf.put(x).put(y).put(z).put(u).put(v).put(r).put(g).put(b).put(a);
	}

	@Override
	public void close() {
		if (frameTex != 0) {
			glDeleteTextures(frameTex);
		}
		glDeleteBuffers(vbo);
		glDeleteVertexArrays(vao);
		MemoryUtil.memFree(buf);
		shader.close();
	}
}
