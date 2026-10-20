package devple.gow.engine.fx;

import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Shader;
import devple.gow.engine.render.Texture;
import devple.gow.tools.fx.EnvParams;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.opengl.GL41.*;

/**
 * SCR_LensFlare 렌더 (46 문서, 계산은 {@link LensFlare}). 원작 GS 패스를 GL 로 옮긴다:
 * <ol>
 *   <li>첫 패스: 플레어 텍스처 × ½, 고정 가산, 빛의 깊이로 화소 단위 깊이 시험 (깊이 쓰기 없음).</li>
 *   <li>가림: 판정 상자의 장면 깊이를 읽어 (원작: GS 알파 누적 + 쌍선형 축소) 보이는 비율을 구하고,
 *       플레어 텍스처 × 1.5·보이는 비율·자름 비율 을 깊이 시험 없이 가산 (Cs·As + Cd).</li>
 * </ol>
 * 끄기: {@code -Dgow.nolensflare} 또는 환경 변수 {@code GOW_LENSFLARE=off}.
 */
public final class LensFlares implements AutoCloseable {

	public static final boolean ENABLED = System.getProperty("gow.nolensflare") == null
			&& !"off".equalsIgnoreCase(System.getenv("GOW_LENSFLARE"));

	private record Flare(LensFlare.Def def, Vector3f pos, Texture tex, devple.gow.tools.fx.MaterialAnim.Anim anim) {
	}

	/** 원작 자원 이름 조회는 대문자 해시 (Hash127Upper) → 대소문자 무시. 데이터: "FLARE" → MAT_flare. */
	static String materialName(WadAssets a, String mat) {
		for (String n : new String[]{mat, "MAT_" + mat.substring(4).toLowerCase(Locale.ROOT), mat.toUpperCase(Locale.ROOT)}) {
			if (a.material(n) != null) {
				return n;
			}
		}
		return mat;
	}

	private final long t0 = System.nanoTime();

	private final List<Flare> flares = new ArrayList<>();
	private Shader shader;
	private int vao;
	/** 마지막 프레임 값 (디버그·확인용): 보이는 비율, 세기. */
	public float lastVisible, lastIntensity;

	public LensFlares(EnvParams env, LevelData data, WadAssets assets) {
		for (LensFlare.Def d : LensFlare.defs(env)) {
			Vector3f p = null;
			for (LevelLayout.Placement pl : data.layout.placements()) {
				if (pl.instance().name().equalsIgnoreCase(d.owner())) {
					float[] w = pl.jointWorld()[0];
					p = new Vector3f(w[12], w[13], w[14]);
					break;
				}
			}
			String mat = materialName(assets, d.material());
			String txr = assets.materialTexture(mat);
			Texture t = txr == null ? null : assets.texture(txr);
			if (p == null || t == null) {
				System.out.printf("[렌즈플레어] %s: 위치 %s / 텍스처 %s 없음%n", d.owner(), p, txr);
				continue;
			}
			flares.add(new Flare(d, p, t, assets.materialAnim(mat)));
		}
		if (!flares.isEmpty()) {
			System.out.printf(Locale.ROOT, "[렌즈플레어] %d개: %s%n", flares.size(),
					flares.stream().map(f -> f.def().owner() + "(" + f.def().material() + " " + f.def().size() + "/" + f.def().occlusion() + ")").toList());
		}
	}

	public boolean isEmpty() {
		return flares.isEmpty();
	}

	/** 캐릭터·이펙트를 그린 뒤 (깊이 버퍼가 완성된 뒤) 호출. */
	public void render(Camera cam, float aspect) {
		if (flares.isEmpty() || !ENABLED || cam.mirror != null) {
			return;
		}
		if (shader == null) {
			shader = new Shader("lensflare.vert", "lensflare.frag");
			vao = devple.gow.engine.render.GlStats.vao(glGenVertexArrays());
		}
		int vw, vh;
		try (MemoryStack st = MemoryStack.stackPush()) {
			IntBuffer vp = st.mallocInt(4);
			glGetIntegerv(GL_VIEWPORT, vp);
			vw = vp.get(2);
			vh = vp.get(3);
		}
		Matrix4f vpm = new Matrix4f(cam.viewProj(aspect));
		float scale = vh / LensFlare.PS2_HEIGHT;
		boolean depthWas = glIsEnabled(GL_DEPTH_TEST), blendWas = glIsEnabled(GL_BLEND), cullWas = glIsEnabled(GL_CULL_FACE);
		glDisable(GL_CULL_FACE);
		glEnable(GL_BLEND);
		glBlendEquation(GL_FUNC_ADD);
		glBlendFunc(GL_ONE, GL_ONE);
		glDepthMask(false);
		shader.use();
		shader.set("uTex", 0);
		glBindVertexArray(vao);
		for (Flare f : flares) {
			Vector4f c = vpm.transform(new Vector4f(f.pos(), 1));
			if (c.w <= 0) {
				continue;
			}
			float nx = c.x / c.w, ny = c.y / c.w, nz = c.z / c.w;
			if (nz < -1 || nz > 1) {
				continue; // 근/원 평면 밖 (0x20E9D8)
			}
			float sx = (nx * 0.5f + 0.5f) * vw, sy = (ny * 0.5f + 0.5f) * vh;
			float half = (int) (f.def().size() * 0.5f) * scale;
			float occ = (int) (f.def().occlusion() * 0.5f) * scale;
			if (!LensFlare.onScreen(sx, sy, half, vw, vh)) {
				continue;
			}
			float depth = nz * 0.5f + 0.5f;
			float[] box = LensFlare.clip(sx, sy, occ, vw, vh);
			float vis = visible(box, depth);
			float inten = LensFlare.intensity(vis, LensFlare.clipRatio(box, occ));
			lastVisible = vis;
			lastIntensity = inten;
			f.tex().bind(0);
			shader.set("uCenter", nx, ny);
			shader.set("uHalf", 2 * half / vw, 2 * half / vh);
			shader.set("uZ", nz);
			// MAT_starFlare: ANM_starFlare 층 0 슬롯 5 = 0 → 2π, 1.67초 반복 → UV 회전 [추정: 슬롯 5 = 회전 라디안]
			float rot = 0;
			if (f.anim() != null && f.anim().duration() > 0) {
				float t = (System.nanoTime() - t0) * 1e-9f % f.anim().duration();
				rot = f.anim().value("L0s5", t, 0);
			}
			shader.set("uRot", rot);
			// 1) 화소 단위 깊이 시험한 반 밝기 (0x20EC00)
			glEnable(GL_DEPTH_TEST);
			glDepthFunc(GL_LEQUAL);
			shader.set("uScale", LensFlare.DIRECT_SCALE);
			glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
			// 2) 가림 비율로 변조한 전체 (0x20F5A8..)
			if (inten > 0) {
				glDisable(GL_DEPTH_TEST);
				shader.set("uScale", inten);
				glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
			}
		}
		glBindVertexArray(0);
		glDepthMask(true);
		glDepthFunc(GL_LESS);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		if (depthWas) {
			glEnable(GL_DEPTH_TEST);
		} else {
			glDisable(GL_DEPTH_TEST);
		}
		if (!blendWas) {
			glDisable(GL_BLEND);
		}
		if (cullWas) {
			glEnable(GL_CULL_FACE);
		}
	}

	/** 판정 상자의 장면 깊이를 읽어 보이는 비율. */
	private static float visible(float[] box, float depth) {
		int x0 = (int) Math.floor(box[0]), y0 = (int) Math.floor(box[1]);
		int w = (int) Math.ceil(box[2]) - x0 + 1, h = (int) Math.ceil(box[3]) - y0 + 1;
		if (box[2] <= box[0] || box[3] <= box[1] || w <= 0 || h <= 0) {
			return 0;
		}
		float[] d = new float[w * h];
		try (MemoryStack st = MemoryStack.stackPush()) {
			FloatBuffer fb = w * h <= 16384 ? st.mallocFloat(w * h) : org.lwjgl.BufferUtils.createFloatBuffer(w * h);
			glReadPixels(x0, y0, w, h, GL_DEPTH_COMPONENT, GL_FLOAT, fb);
			fb.get(d);
		}
		return LensFlare.visibleFraction(d, d.length, depth);
	}

	@Override
	public void close() {
		if (shader != null) {
			shader.close();
			devple.gow.engine.render.GlStats.delVao(vao);
		}
	}
}
