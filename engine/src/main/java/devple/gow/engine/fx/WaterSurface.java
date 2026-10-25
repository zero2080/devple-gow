package devple.gow.engine.fx;

import devple.gow.engine.render.Mesh;
import devple.gow.engine.render.Shader;
import devple.gow.engine.render.Texture;
import devple.gow.tools.fx.EnvParams;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL41C.*;

/**
 * SCR_Water 파문 수면 (16 문서 §1.6, 0x219DE8): 높이장 = {@link WaterSim} (격자 원점 = 소유 GO 위치가 최소 모서리, 물방울·충격·파동은 원작 그대로),
 * 그리기 = basic 셰이더 정점 레이아웃, UV = 카메라 반사 벡터의 구면 환경맵 (REFLENVM, {@link #sphereUv}), 반투명.
 * 플래그 (+0x0C) 1 = 격자에 내접한 타원 밖 칸은 그리지 않는다 (둥근 분수, {@link #insideEllipse}).
 * 활성도 a = clamp((R + 480 − 거리)/160, 0, 1), R = √(nx²+nz²)·8, 중심 = 위치 + (nx·cell·8, nz·cell·8) — a = 0 이면 정지 (0x219A90).
 */
public final class WaterSurface implements AutoCloseable {

	private final WaterSim sim;
	private final int nx, nz;
	private final float cell;
	private final float x0, y0, z0;
	private final Texture env;
	private final int vao, vbo, ebo, indexCount;
	private final FloatBuffer buf;
	private float acc;
	private boolean active = true;
	/** 거리 감쇠 a (0x219F28–0x219F4C) — GO 색 +0xB0 의 w 로 들어가 물이 멀어지며 옅어진다 (0x219F54–0x219F98) [확실]. */
	private float fade = 1;

	/** @param corner 소유 GO 관절 0 월드 위치 = 격자 최소 모서리 (0x219D88) */
	private final EnvParams.Water def;

	public WaterSurface(EnvParams.Water def, float[] corner, Texture env) {
		this.def = def;
		this.sim = new WaterSim(def, corner, 7);
		this.nx = sim.nx;
		this.nz = sim.nz;
		this.cell = sim.cellW;
		this.x0 = sim.x0;
		this.y0 = sim.y0;
		this.z0 = sim.z0;
		this.env = env;
		buf = MemoryUtil.memAllocFloat(nx * nz * Mesh.STRIDE);
		vao = devple.gow.engine.render.GlStats.vao(glGenVertexArrays());
		glBindVertexArray(vao);
		vbo = devple.gow.engine.render.GlStats.buf(glGenBuffers());
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		glBufferData(GL_ARRAY_BUFFER, (long) nx * nz * Mesh.STRIDE * Float.BYTES, GL_STREAM_DRAW);
		int s = Mesh.STRIDE * Float.BYTES;
		int[] sizes = {3, 3, 2, 4};
		int off = 0;
		for (int i = 0; i < 4; i++) {
			glEnableVertexAttribArray(i);
			glVertexAttribPointer(i, sizes[i], GL_FLOAT, false, s, (long) off * Float.BYTES);
			off += sizes[i];
		}
		IntBuffer ib = MemoryUtil.memAllocInt((nx - 1) * (nz - 1) * 6);
		for (int z = 0; z + 1 < nz; z++) {
			for (int x = 0; x + 1 < nx; x++) {
				if (def.flag() == 1 && !insideEllipse(x, z, nx, nz)) {
					continue;
				}
				int a = z * nx + x;
				ib.put(a).put(a + nx).put(a + 1).put(a + 1).put(a + nx).put(a + nx + 1);
			}
		}
		indexCount = ib.position();
		ib.flip();
		ebo = devple.gow.engine.render.GlStats.buf(glGenBuffers());
		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
		glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_STATIC_DRAW);
		MemoryUtil.memFree(ib);
		glBindVertexArray(0);
	}

	/**
	 * 타원 물 (SCR_Water +0x0C = 1): 메시 만들기 0x21A1D0 이 띠 정점마다 (열 x, 줄 z) 의 왼쪽 칸 가운데 (x − 0.5, z + 0.5) 를
	 * ((2·(cx − (nx−1)/2)/(nx−1))² + (2·(cz − (nz−1)/2)/(nz−1))² &gt; 1) 이면 두 정점에 ADC (0x8000) 를 세워 그 칸을 그리지 않는다
	 * (0x21A454 – 0x21A4F0) [확실]. 여기서는 칸 (x, z) – (x+1, z+1) 의 가운데 (x + 0.5, z + 0.5) 로 같은 판정.
	 */
	static boolean insideEllipse(int x, int z, int nx, int nz) {
		float a = 2 * (x + 0.5f - (nx - 1) * 0.5f) / (nx - 1), b = 2 * (z + 0.5f - (nz - 1) * 0.5f) / (nz - 1);
		return a * a + b * b <= 1;
	}

	/**
	 * 구면 환경맵 UV (갱신 0x21A888, 0x21AA0C – 0x21AB3C) [확실]: D = 정점 − 카메라 위치 (카메라 관리자 0x29F16C +8 의 월드 행렬 행 3),
	 * 법선 N = (h[x−1] − h[x+1], 4·칸, h[z−1] − h[z+1]), 반사 R = D − 2N(D·N)/|N|², r = R/|D|, m = √(rx² + (ry + 1)² + rz²),
	 * (u, v) = (0.5 + 0.5·rx/m, 0.5 + 0.5·rz/m) (ST 2048 + 2048·r/m, 4096 = 1). 위에서 내려다보면 가운데 (밝은 하늘색), 비스듬하면 바깥 고리 (어두운 갈색).
	 * 예전 엔진은 0.5 + 4·법선 + 0.02·격자 번호 라 수면 위에 갈색 얼룩이 흘렀다 (43 §42).
	 */
	static void sphereUv(float dx, float dy, float dz, float nx, float ny, float nz, float[] out) {
		float nn = nx * nx + ny * ny + nz * nz, dn = dx * nx + dy * ny + dz * nz;
		float k = nn > 1e-12f ? 2 * dn / nn : 0;
		float rx = dx - nx * k, ry = dy - ny * k, rz = dz - nz * k;
		float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (d < 1e-6f) {
			out[0] = out[1] = 0.5f;
			return;
		}
		rx /= d;
		ry /= d;
		rz /= d;
		float m = (float) Math.sqrt(rx * rx + (ry + 1) * (ry + 1) + rz * rz);
		if (m < 1e-6f) {
			out[0] = out[1] = 0.5f;
			return;
		}
		out[0] = 0.5f + 0.5f * rx / m;
		out[1] = 0.5f + 0.5f * rz / m;
	}

	private final Vector3f eye = new Vector3f();

	public void update(float dt, Vector3f viewer) {
		eye.set(viewer);
		float dist = (float) Math.hypot(viewer.x - sim.centreX(), viewer.z - sim.centreZ());
		fade = Math.max(0, Math.min(1, (sim.radius() + 480 - dist) / 160));
		active = fade > 0;
		if (!active) {
			return;
		}
		acc += Math.min(dt, 0.1f);
		while (acc >= 1 / 60f) {
			acc -= 1 / 60f;
			sim.step(1 / 60f);
		}
	}

	private final float[] uv = new float[2];

	public void render(Shader basic, Texture white) {
		buf.clear();
		for (int z = 0; z < nz; z++) {
			for (int x = 0; x < nx; x++) {
				float hl = sim.height(Math.max(x - 1, 0), z), hr = sim.height(Math.min(x + 1, nx - 1), z);
				float hd = sim.height(x, Math.max(z - 1, 0)), hu = sim.height(x, Math.min(z + 1, nz - 1));
				float gx = hl - hr, gy = 4 * cell, gz = hd - hu; // 0x21A9CC – 0x21AA08: (h[x−1] − h[x+1], 4·칸, h[z−1] − h[z+1])
				float l = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
				float nX = gx / l, nY = gy / l, nZ = gz / l;
				float px = x0 + x * cell, py = y0 + sim.height(x, z), pz = z0 + z * cell;
				sphereUv(px - eye.x, py - eye.y, pz - eye.z, gx, gy, gz, uv);
				buf.put(px).put(py).put(pz).put(nX).put(nY).put(nZ).put(uv[0]).put(uv[1])
						.put(1f).put(1f).put(1f).put(fade); // 원작 물 메시는 정점 색을 쓰지 않는다 (0x21A1D0·0x21A888) — 색·알파 = 텍스처 × 감쇠
			}
		}
		buf.flip();
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		glBufferSubData(GL_ARRAY_BUFFER, 0, buf);
		basic.set("uModel", new Matrix4f());
		basic.set("uLit", 0);
		basic.set("uTint", new Vector4f(1, 1, 1, 1));
		basic.set("uUvOffset", 0f, 0f);
		if (env != null) {
			env.bind(0);
			basic.set("uUseTex", 1);
		} else {
			white.bind(0);
			basic.set("uUseTex", 0);
		}
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glDepthMask(false);
		glBindVertexArray(vao);
		glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
		glBindVertexArray(0);
		glDepthMask(true);
		glDisable(GL_BLEND);
	}

	@Override
	public void close() {
		devple.gow.engine.render.GlStats.delBuf(vbo);
		devple.gow.engine.render.GlStats.delBuf(ebo);
		devple.gow.engine.render.GlStats.delVao(vao);
		MemoryUtil.memFree(buf);
	}
}
