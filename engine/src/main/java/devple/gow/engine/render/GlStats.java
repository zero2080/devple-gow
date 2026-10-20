package devple.gow.engine.render;

import static org.lwjgl.opengl.GL41C.*;

/**
 * GL 객체 살아 있는 수 (48 문서: 장시간·레벨 전환 누수 측정). 생성·삭제 지점을 이 함수로 감싸 센다 — GL 스레드 하나라 잠금 없음.
 * 원작에는 없는 진단용 [엔진 전용].
 */
public final class GlStats {

	private static int textures, buffers, vaos, fbos, programs, queries;

	private GlStats() {
	}

	public static int tex(int id) {
		textures++;
		return id;
	}

	public static int buf(int id) {
		buffers++;
		return id;
	}

	public static int vao(int id) {
		vaos++;
		return id;
	}

	public static int fbo(int id) {
		fbos++;
		return id;
	}

	public static int program(int id) {
		programs++;
		return id;
	}

	public static int query(int id) {
		queries++;
		return id;
	}

	public static void delTex(int id) {
		if (id != 0) {
			glDeleteTextures(id);
			textures--;
		}
	}

	public static void delTex(int[] ids) {
		for (int id : ids) {
			delTex(id);
		}
	}

	public static void delBuf(int id) {
		if (id != 0) {
			glDeleteBuffers(id);
			buffers--;
		}
	}

	public static void delVao(int id) {
		if (id != 0) {
			glDeleteVertexArrays(id);
			vaos--;
		}
	}

	public static void delFbo(int id) {
		if (id != 0) {
			glDeleteFramebuffers(id);
			fbos--;
		}
	}

	public static void delProgram(int id) {
		if (id != 0) {
			glDeleteProgram(id);
			programs--;
		}
	}

	public static void delQuery(int id) {
		if (id != 0) {
			glDeleteQueries(id);
			queries--;
		}
	}

	/** "tex=… buf=… vao=… fbo=… prog=… query=…". */
	public static String describe() {
		return "tex=" + textures + " buf=" + buffers + " vao=" + vaos + " fbo=" + fbos + " prog=" + programs + " query=" + queries;
	}
}
