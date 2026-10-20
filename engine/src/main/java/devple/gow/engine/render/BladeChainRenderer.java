package devple.gow.engine.render;

import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.game.BladeChain;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

/**
 * 블레이드 사슬 고리·사슬 빛·궤적 렌더 (45 문서 §3). 머티리얼은 현재 무기 WAD (R_WPN&lt;c&gt;_&lt;n&gt;) 의
 * MAT_chainlink (알파 블렌드) / MAT_chainglow (가산) / MAT_swordtrail (가산), 신들의 분노 중 god 판 (0x1FAA10 이 목록 마지막 원소를 바꿈).
 * 정점은 {@link BladeChain} 이 만든 월드 좌표, 셰이더는 파티클 것 (위치·UV·색, 텍스처 곱).
 */
public final class BladeChainRenderer implements AutoCloseable {

	private static final int STRIDE = 9;
	private static final int MAX_VERTS = 2 * 3 * (BladeChain.MAX_SAMPLES * 2 + 32);
	private final Shader shader = new Shader("particle.vert", "particle.frag");
	private final int vao, vbo;
	private final FloatBuffer buf = MemoryUtil.memAllocFloat(MAX_VERTS * STRIDE);
	private final Texture[] link = new Texture[2], glow = new Texture[2], trail = new Texture[2];

	public BladeChainRenderer(WadAssets wpn) {
		String[][] mats = {{"MAT_chainlink", "MAT_chainglow", "MAT_swordtrail"}, {"MAT_godchainlink", "MAT_godchainglow", "MAT_godswordtrail"}};
		for (int g = 0; g < 2; g++) {
			link[g] = tex(wpn, mats[g][0]);
			glow[g] = tex(wpn, mats[g][1]);
			trail[g] = tex(wpn, mats[g][2]);
		}
		vao = GlStats.vao(glGenVertexArrays());
		glBindVertexArray(vao);
		vbo = GlStats.buf(glGenBuffers());
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		glBufferData(GL_ARRAY_BUFFER, (long) MAX_VERTS * STRIDE * Float.BYTES, GL_STREAM_DRAW);
		int s = STRIDE * Float.BYTES;
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, s, 0);
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 2, GL_FLOAT, false, s, 3L * Float.BYTES);
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 4, GL_FLOAT, false, s, 5L * Float.BYTES);
		glBindVertexArray(0);
	}

	private static Texture tex(WadAssets a, String mat) {
		if (a == null) {
			return null;
		}
		String t = a.materialTexture(mat);
		return t != null ? a.texture(t) : null;
	}

	/**
	 * @param fix     보간 보정 (렌더 영웅 행렬 × 틱 영웅 행렬⁻¹)
	 * @param god     god 판 머티리얼
	 * @param tint    궤적 색 RGBA (Trail Tint / God Mode Trail Tint)
	 * @param trailOn 블레이드별 궤적 켜짐 (기술 이벤트 17/18)
	 * @param bladeLen 블레이드 길이 (Weapon Length × 16)
	 */
	public void draw(Camera cam, float aspect, Matrix4f fix, BladeChain chain, boolean god, float[] tint, boolean[] trailOn,
					 float bladeLen) {
		int g = god ? 1 : 0;
		Vector3f eye = new Vector3f(cam.eye);
		Vector3f back = new Vector3f(cam.eye).sub(cam.target).normalize();
		Matrix4f inv = new Matrix4f(fix).invert();
		inv.transformPosition(eye); // 틱 좌표에서 계산하고 보정은 행렬로
		inv.transformDirection(back).normalize();
		shader.use();
		shader.set("uViewProj", new Matrix4f(cam.viewProj(aspect)).mul(fix));
		shader.set("uTex", 0);
		shader.set("uSoft", 0);
		shader.set("uGs", 0);
		shader.set("uScreen", 0);
		boolean cull = glIsEnabled(GL_CULL_FACE);
		glDisable(GL_CULL_FACE);
		glEnable(GL_BLEND);
		glEnable(GL_DEPTH_TEST);
		glDepthMask(false);
		glBlendEquation(GL_FUNC_ADD);
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		for (BladeChain.Side s : chain.sides) {
			if (!s.valid()) {
				continue;
			}
			if (s.chainVisible) {
				float n = chain.segments(s.ctrl);
				List<float[]> sm = BladeChain.samples(s.ctrl, s.endDir, eye, n);
				if (sm.size() >= 2) {
					// 사슬 고리: P ± 옆 · Link Diameter·8, u = 마디/16 · (1 − t) (텍스처 512 폭에 고리 16 개), v 0/1
					float hl = chain.linkDiameter * 8f, hg = chain.glowDiameter * 8f;
					float ul = n / 16f;
					buf.clear();
					ribbon(sm, hl, null, ul, new float[]{1, 1, 1, 1});
					flush(link[g] != null ? link[g] : link[0], false);
					// 사슬 빛: P + 카메라 뒤쪽 1 단위 ± 옆 · Glow Diameter·8, u = 1 − t, 색 = 마디/16 (가산)
					float k = BladeChain.glowLevel(n);
					buf.clear();
					ribbon(sm, hg, back, 1f, new float[]{k, k, k, 1});
					flush(glow[g] != null ? glow[g] : glow[0], true);
				}
			}
			if (trailOn[s.index] && tint[3] > 0) {
				buf.clear();
				for (float[][] strip : BladeChain.trail(s.ctrl, s.bladeZ, bladeLen)) {
					for (int[] tri : BladeChain.stripTriangles(strip.length)) {
						for (int i : tri) {
							float[] v = strip[i];
							// v = 1 (원작) — 맨 아래 행 [근사: GS CLAMP 미확인, 텍스처 가장자리 행을 쓴다]
							put(v[0], v[1], v[2], v[3], 1f - 0.5f / 32f, tint);
						}
					}
				}
				flush(trail[g] != null ? trail[g] : trail[0], true);
			}
		}
		glBindVertexArray(0);
		glDepthMask(true);
		glDisable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		if (cull) {
			glEnable(GL_CULL_FACE);
		}
	}

	private void ribbon(List<float[]> sm, float half, Vector3f offset, float uScale, float[] color) {
		float[] prevA = null, prevB = null;
		for (float[] s : sm) {
			float ox = offset != null ? offset.x : 0, oy = offset != null ? offset.y : 0, oz = offset != null ? offset.z : 0;
			float u = uScale * (1f - s[6]);
			float[] a = {s[0] + ox + s[3] * half, s[1] + oy + s[4] * half, s[2] + oz + s[5] * half, u, 0};
			float[] b = {s[0] + ox - s[3] * half, s[1] + oy - s[4] * half, s[2] + oz - s[5] * half, u, 1};
			if (prevA != null) {
				quad(prevA, prevB, a, b, color);
			}
			prevA = a;
			prevB = b;
		}
	}

	private void quad(float[] a0, float[] b0, float[] a1, float[] b1, float[] c) {
		for (float[] v : new float[][]{a0, b0, a1, b0, b1, a1}) {
			put(v[0], v[1], v[2], v[3], v[4], c);
		}
	}

	private void put(float x, float y, float z, float u, float v, float[] c) {
		if (buf.remaining() < STRIDE) {
			return;
		}
		buf.put(x).put(y).put(z).put(u).put(v).put(c[0]).put(c[1]).put(c[2]).put(c[3]);
	}

	private void flush(Texture t, boolean additive) {
		int verts = buf.position() / STRIDE;
		if (verts == 0) {
			return;
		}
		buf.flip();
		// 48 문서 §9: 같은 프레임에 여러 번 덮어쓰므로 저장소를 새로 받는다 (orphan) — glBufferSubData(0) 는 GPU 를 기다렸다.
		glBufferData(GL_ARRAY_BUFFER, buf, GL_STREAM_DRAW);
		if (t != null) {
			t.bind(0);
			shader.set("uUseTex", 1);
		} else {
			shader.set("uUseTex", 0);
		}
		if (additive) {
			glBlendFunc(GL_SRC_ALPHA, GL_ONE);
		} else {
			glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		}
		glDrawArrays(GL_TRIANGLES, 0, verts);
		buf.clear();
	}

	@Override
	public void close() {
		GlStats.delBuf(vbo);
		GlStats.delVao(vao);
		MemoryUtil.memFree(buf);
		shader.close();
	}
}
