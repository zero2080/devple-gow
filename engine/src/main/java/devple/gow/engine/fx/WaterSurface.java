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
import java.util.Random;

import static org.lwjgl.opengl.GL41C.*;

/**
 * SCR_Water 파문 수면 (16 문서 §1.7, 0x21A758): 격자 높이장 파동 방정식 + 무작위 물방울 + 반사 환경맵.
 * <pre>
 * k = (2·dt/cell)², h' = (k·(N+S+E+W) + (2−4k)·h − h_prev)·0.99   (dt = 1/60 고정)
 * 활성도 a = clamp((R + 480 − 거리)/160, 0, 1), R = √(nx²+nz²)·8, a = 0 이면 정지
 * </pre>
 * 그리기: basic 셰이더 정점 레이아웃 그대로, UV = 법선 기반 구면 환경맵 (REFLENVM), 반투명.
 */
public final class WaterSurface implements AutoCloseable {

	private final EnvParams.Water def;
	private final int nx, nz;
	private final float cell;
	private final float x0, y0, z0;
	private float[] h, prev;
	private final Texture env;
	private final int vao, vbo, ebo, indexCount;
	private final FloatBuffer buf;
	private final Random rnd = new Random(7);
	private float dropCarry, acc;
	private boolean active = true;

	/** @param centre 소유 GO 관절 0 월드 위치 */
	public WaterSurface(EnvParams.Water def, float[] centre, Texture env) {
		this.def = def;
		this.nx = def.nx();
		this.nz = def.nz();
		this.cell = def.cell() * 16;
		this.x0 = centre[0] - (nx - 1) * cell / 2;
		this.y0 = centre[1];
		this.z0 = centre[2] - (nz - 1) * cell / 2;
		this.env = env;
		h = new float[nx * nz];
		prev = new float[nx * nz];
		buf = MemoryUtil.memAllocFloat(nx * nz * Mesh.STRIDE);
		vao = glGenVertexArrays();
		glBindVertexArray(vao);
		vbo = glGenBuffers();
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
				int a = z * nx + x;
				ib.put(a).put(a + nx).put(a + 1).put(a + 1).put(a + nx).put(a + nx + 1);
			}
		}
		indexCount = ib.position();
		ib.flip();
		ebo = glGenBuffers();
		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
		glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_STATIC_DRAW);
		MemoryUtil.memFree(ib);
		glBindVertexArray(0);
	}

	public void update(float dt, Vector3f viewer) {
		float r = (float) Math.sqrt(nx * nx + nz * nz) * 8;
		float cx = x0 + (nx - 1) * cell / 2, cz = z0 + (nz - 1) * cell / 2;
		float dist = (float) Math.hypot(viewer.x - cx, viewer.z - cz);
		active = (r + 480 - dist) / 160 > 0;
		if (!active) {
			return;
		}
		acc += Math.min(dt, 0.1f);
		while (acc >= 1 / 60f) {
			acc -= 1 / 60f;
			step();
		}
	}

	private void step() {
		dropCarry += def.dropRate() / 60f;
		while (dropCarry >= 1) {
			dropCarry -= 1;
			// 물방울: 영역 (오프셋 + 무작위·크기) ×16 을 격자 중심 기준으로 [추정 원점]
			float dx = (def.dropOff()[0] + (rnd.nextFloat() - 0.5f) * def.dropSize()[0]) * 16;
			float dz = (def.dropOff()[1] + (rnd.nextFloat() - 0.5f) * def.dropSize()[1]) * 16;
			int ix = Math.round(dx / cell + (nx - 1) / 2f), iz = Math.round(dz / cell + (nz - 1) / 2f);
			if (ix > 0 && iz > 0 && ix < nx - 1 && iz < nz - 1) {
				// 충격 세기 [추정 단위]: 3×3 으로 나눠 뾰족한 스파이크를 피한다
				float f = def.strength() * 4;
				h[iz * nx + ix] -= f;
				h[iz * nx + ix - 1] -= f * 0.5f;
				h[iz * nx + ix + 1] -= f * 0.5f;
				h[(iz - 1) * nx + ix] -= f * 0.5f;
				h[(iz + 1) * nx + ix] -= f * 0.5f;
			}
		}
		float k = (2f / 60f / def.cell()) * (2f / 60f / def.cell());
		k = Math.min(k, 0.24f);
		float[] next = prev;
		for (int z = 1; z + 1 < nz; z++) {
			for (int x = 1; x + 1 < nx; x++) {
				int i = z * nx + x;
				next[i] = (k * (h[i - 1] + h[i + 1] + h[i - nx] + h[i + nx]) + (2 - 4 * k) * h[i] - prev[i]) * 0.99f;
			}
		}
		prev = h;
		h = next;
	}

	public void render(Shader basic, Texture white) {
		buf.clear();
		for (int z = 0; z < nz; z++) {
			for (int x = 0; x < nx; x++) {
				int i = z * nx + x;
				float hl = h[z * nx + Math.max(x - 1, 0)], hr = h[z * nx + Math.min(x + 1, nx - 1)];
				float hd = h[Math.max(z - 1, 0) * nx + x], hu = h[Math.min(z + 1, nz - 1) * nx + x];
				float gx = (hl - hr) / (2 * cell), gz = (hd - hu) / (2 * cell);
				float l = (float) Math.sqrt(gx * gx + 1 + gz * gz);
				float nX = gx / l, nY = 1 / l, nZ = gz / l;
				// 구면 환경맵 UV (법선 xz) + 위치에 따른 약한 이동 → 반사가 흔들린다
				float u = 0.5f + nX * 4f + x * 0.02f, v = 0.5f + nZ * 4f + z * 0.02f;
				buf.put(x0 + x * cell).put(y0 + h[i]).put(z0 + z * cell).put(nX).put(nY).put(nZ).put(u).put(v)
						.put(0.7f).put(0.75f).put(0.8f).put(0.4f);
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
		glDeleteBuffers(vbo);
		glDeleteBuffers(ebo);
		glDeleteVertexArrays(vao);
		MemoryUtil.memFree(buf);
	}
}
