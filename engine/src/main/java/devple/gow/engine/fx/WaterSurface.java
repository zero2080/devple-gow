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
 * 그리기 = basic 셰이더 정점 레이아웃, UV = 법선 기반 구면 환경맵 (REFLENVM), 반투명.
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
	public WaterSurface(EnvParams.Water def, float[] corner, Texture env) {
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

	public void update(float dt, Vector3f viewer) {
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

	public void render(Shader basic, Texture white) {
		buf.clear();
		for (int z = 0; z < nz; z++) {
			for (int x = 0; x < nx; x++) {
				float hl = sim.height(Math.max(x - 1, 0), z), hr = sim.height(Math.min(x + 1, nx - 1), z);
				float hd = sim.height(x, Math.max(z - 1, 0)), hu = sim.height(x, Math.min(z + 1, nz - 1));
				float gx = (hl - hr) / (2 * cell), gz = (hd - hu) / (2 * cell);
				float l = (float) Math.sqrt(gx * gx + 1 + gz * gz);
				float nX = gx / l, nY = 1 / l, nZ = gz / l;
				// 구면 환경맵 UV (법선 xz) + 위치에 따른 약한 이동 → 반사가 흔들린다
				float u = 0.5f + nX * 4f + x * 0.02f, v = 0.5f + nZ * 4f + z * 0.02f;
				buf.put(x0 + x * cell).put(y0 + sim.height(x, z)).put(z0 + z * cell).put(nX).put(nY).put(nZ).put(u).put(v)
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
