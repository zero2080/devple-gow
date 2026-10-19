package devple.gow.engine.fx;

import devple.gow.engine.asset.RenderModel;
import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Shader;
import devple.gow.engine.render.Texture;
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
		/**
		 * 이펙트 GO 가 매 프레임 덮어쓰는 FXC P 블록 값 (40 문서 §3, ANM 타입 10 채널). 음수/NaN = 정의 값 사용.
		 * rate = 초당 생성 수, speed / speedRnd / cone = 원뿔 모드 속력·폭·퍼짐, sizeScale = 크기 배율 (인스턴스 스케일).
		 */
		public float rate = -1, speed = Float.NaN, speedRnd = Float.NaN, cone = Float.NaN, sizeScale = 1;
		float carry;

		Emitter(ParticleDef def) {
			this.def = def;
		}
	}

	private final int max;
	private final float[] px, py, pz, vx, vy, vz, age, life, seed;
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
			if (!e.active) {
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
			age[i] += dt;
			if (age[i] >= life[i]) {
				int l = --count;
				copy(l, i);
				i--;
				continue;
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
		for (ParticleDef d : defs) {
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
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
	}

	private void put(float x, float y, float z, float u, float v, float r, float g, float b, float a) {
		buf.put(x).put(y).put(z).put(u).put(v).put(r).put(g).put(b).put(a);
	}

	@Override
	public void close() {
		glDeleteBuffers(vbo);
		glDeleteVertexArrays(vao);
		MemoryUtil.memFree(buf);
		shader.close();
	}
}
