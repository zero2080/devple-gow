package devple.gow.engine.video;

import devple.gow.engine.render.Shader;
import devple.gow.tools.video.VideoFrame;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL41C.*;

/**
 * YUV 4:2:0 프레임 → GL. Y/U/V 를 R8 텍스처 3장으로 올리고 셰이더(movie.frag)에서 BT.601 변환한다
 * (CPU 변환 대비 업로드량 절반, 변환 비용 0). 화면 비율이 다르면 검은 띠(레터/필러박스).
 */
public final class MovieRenderer implements AutoCloseable {

	private final Shader shader = new Shader("movie.vert", "movie.frag");
	private final int vao = devple.gow.engine.render.GlStats.vao(glGenVertexArrays());
	private final int[] tex = new int[3];
	private ByteBuffer staging;
	private int texW, texH;
	private VideoFrame uploaded;

	public MovieRenderer() {
		for (int i = 0; i < 3; i++) {
			tex[i] = devple.gow.engine.render.GlStats.tex(glGenTextures());
		}
	}

	private void allocate(int w, int h) {
		texW = w;
		texH = h;
		for (int i = 0; i < 3; i++) {
			glBindTexture(GL_TEXTURE_2D, tex[i]);
			int pw = i == 0 ? w : w / 2, ph = i == 0 ? h : h / 2;
			glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, pw, ph, 0, GL_RED, GL_UNSIGNED_BYTE, (ByteBuffer) null);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		}
		if (staging != null) {
			MemoryUtil.memFree(staging);
		}
		staging = MemoryUtil.memAlloc(w * h);
	}

	private void upload(VideoFrame f) {
		if (f.width() != texW || f.height() != texH) {
			allocate(f.width(), f.height());
		}
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		byte[][] planes = {f.y(), f.u(), f.v()};
		for (int i = 0; i < 3; i++) {
			int pw = i == 0 ? texW : texW / 2, ph = i == 0 ? texH : texH / 2;
			staging.clear();
			staging.put(planes[i], 0, pw * ph).flip();
			glBindTexture(GL_TEXTURE_2D, tex[i]);
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, pw, ph, GL_RED, GL_UNSIGNED_BYTE, staging);
		}
		uploaded = f;
	}

	/**
	 * 화면(fbW×fbH) 전체를 검게 지우고 aspect 비율 상자 안에 프레임을 그린다.
	 * alpha &lt; 1 이면 지우지 않고 반투명으로 덮는다 (페이드).
	 */
	public void draw(VideoFrame f, int fbW, int fbH, float aspect, float alpha) {
		boolean depth = glIsEnabled(GL_DEPTH_TEST);
		glDisable(GL_DEPTH_TEST);
		if (alpha >= 1f) {
			glClearColor(0, 0, 0, 1);
			glClear(GL_COLOR_BUFFER_BIT);
		}
		if (f != null) {
			if (f != uploaded) {
				upload(f);
			}
			int w = fbW, h = Math.round(fbW / aspect);
			if (h > fbH) {
				h = fbH;
				w = Math.round(fbH * aspect);
			}
			glViewport((fbW - w) / 2, (fbH - h) / 2, w, h);
			if (alpha < 1f) {
				glEnable(GL_BLEND);
				glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
			}
			shader.use();
			for (int i = 0; i < 3; i++) {
				glActiveTexture(GL_TEXTURE0 + i);
				glBindTexture(GL_TEXTURE_2D, tex[i]);
			}
			shader.set("uY", 0);
			shader.set("uU", 1);
			shader.set("uV", 2);
			shader.set("uAlpha", alpha);
			glBindVertexArray(vao);
			glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
			glBindVertexArray(0);
			glActiveTexture(GL_TEXTURE0);
			if (alpha < 1f) {
				glDisable(GL_BLEND);
			}
			glViewport(0, 0, fbW, fbH);
		}
		if (depth) {
			glEnable(GL_DEPTH_TEST);
		}
	}

	@Override
	public void close() {
		devple.gow.engine.render.GlStats.delTex(tex);
		devple.gow.engine.render.GlStats.delVao(vao);
		shader.close();
		if (staging != null) {
			MemoryUtil.memFree(staging);
		}
	}
}
