package devple.gow.engine.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL41C.*;

/**
 * GLSL 프로그램. 소스는 클래스패스 /shaders/ 에서 로드.
 * <p>
 * 프로그램 풀 (48 문서 §9): 닫은 프로그램을 지우지 않고 (정점, 조각) 소스 쌍별로 두었다가 다음 {@code new Shader} 가 다시 쓴다. 레벨 전환마다
 * 같은 셰이더 8–10 개를 컴파일·링크하던 비용 (전환 enter 의 약 1/4) 을 없앤다. 다시 쓸 때 모든 활성 유니폼을 링크 직후 값으로 되돌리므로
 * 새로 링크한 프로그램과 상태가 같다 (유니폼 블록·속성 바인딩은 쓰지 않음). GL 스레드 전용.
 */
public final class Shader implements AutoCloseable {

	private final int program;
	private final Map<String, Integer> uniforms = new HashMap<>();
	private final String key;
	private final Defaults defaults;
	private boolean closed;

	/** 풀 (소스 쌍 → 쉬는 프로그램). 쌍마다 최대 {@link #POOL_MAX} 개, 넘치면 지운다. */
	private static final Map<String, java.util.ArrayDeque<Shader.Idle>> POOL = new HashMap<>();
	private static final int POOL_MAX = 8;
	/** -Dgow.shaderPool=false 면 예전처럼 매번 컴파일·삭제 (비교용). */
	private static final boolean POOLING = !"false".equals(System.getProperty("gow.shaderPool"));

	private record Idle(int program, Defaults defaults) {
	}

	public Shader(String vertRes, String fragRes) {
		key = vertRes + "|" + fragRes;
		java.util.ArrayDeque<Idle> q = POOLING ? POOL.get(key) : null;
		Idle idle = q != null ? q.pollLast() : null;
		if (idle != null) {
			program = idle.program;
			defaults = idle.defaults;
			defaults.restore(program);
			return;
		}
		int vs = compile(GL_VERTEX_SHADER, load(vertRes));
		int fs = compile(GL_FRAGMENT_SHADER, load(fragRes));
		program = GlStats.program(glCreateProgram());
		glAttachShader(program, vs);
		glAttachShader(program, fs);
		glLinkProgram(program);
		if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
			throw new IllegalStateException("셰이더 링크 실패: " + glGetProgramInfoLog(program));
		}
		glDeleteShader(vs);
		glDeleteShader(fs);
		defaults = POOLING ? Defaults.capture(program) : null;
	}

	/** 쉬는 프로그램을 모두 지운다 (창을 닫을 때 등). */
	public static void clearPool() {
		POOL.values().forEach(q -> q.forEach(i -> GlStats.delProgram(i.program)));
		POOL.clear();
	}

	/** 풀에서 쉬는 프로그램 수 (진단). */
	public static int pooled() {
		return POOL.values().stream().mapToInt(java.util.ArrayDeque::size).sum();
	}

	/**
	 * 링크 직후 활성 유니폼 값 (배열은 원소마다). 새 프로그램의 유니폼은 링크 때 정해진 값 (초기값 없으면 0) 이므로 이것으로 되돌리면 새 프로그램과 같다.
	 */
	private static final class Defaults {
		private final int[] location, type, count;
		private final float[][] f;
		private final int[][] i;

		private Defaults(int n) {
			location = new int[n];
			type = new int[n];
			count = new int[n];
			f = new float[n][];
			i = new int[n][];
		}

		static Defaults capture(int program) {
			int n = glGetProgrami(program, GL_ACTIVE_UNIFORMS);
			Defaults d = new Defaults(n);
			try (MemoryStack st = MemoryStack.stackPush()) {
				java.nio.IntBuffer size = st.mallocInt(1), type = st.mallocInt(1);
				for (int u = 0; u < n; u++) {
					String name = glGetActiveUniform(program, u, size, type);
					String base = name.endsWith("[0]") ? name.substring(0, name.length() - 3) : name;
					int cnt = size.get(0), t = type.get(0), comps = components(t);
					d.location[u] = glGetUniformLocation(program, cnt > 1 ? base + "[0]" : name);
					d.type[u] = t;
					d.count[u] = cnt;
					boolean isFloat = floatType(t);
					if (isFloat) {
						d.f[u] = new float[cnt * comps];
					} else {
						d.i[u] = new int[cnt * comps];
					}
					java.nio.FloatBuffer fb = st.mallocFloat(16);
					java.nio.IntBuffer ib = st.mallocInt(16);
					for (int k = 0; k < cnt; k++) {
						int loc = cnt > 1 ? glGetUniformLocation(program, base + "[" + k + "]") : d.location[u];
						if (loc < 0) {
							continue;
						}
						if (isFloat) {
							fb.clear();
							glGetUniformfv(program, loc, fb);
							fb.get(0, d.f[u], k * comps, comps);
						} else {
							ib.clear();
							glGetUniformiv(program, loc, ib);
							ib.get(0, d.i[u], k * comps, comps);
						}
					}
				}
			}
			return d;
		}

		void restore(int program) {
			for (int u = 0; u < location.length; u++) {
				int loc = location[u];
				if (loc < 0) {
					continue;
				}
				switch (type[u]) {
					case GL_FLOAT -> glProgramUniform1fv(program, loc, f[u]);
					case GL_FLOAT_VEC2 -> glProgramUniform2fv(program, loc, f[u]);
					case GL_FLOAT_VEC3 -> glProgramUniform3fv(program, loc, f[u]);
					case GL_FLOAT_VEC4 -> glProgramUniform4fv(program, loc, f[u]);
					case GL_FLOAT_MAT2 -> glProgramUniformMatrix2fv(program, loc, false, f[u]);
					case GL_FLOAT_MAT3 -> glProgramUniformMatrix3fv(program, loc, false, f[u]);
					case GL_FLOAT_MAT4 -> glProgramUniformMatrix4fv(program, loc, false, f[u]);
					case GL_INT_VEC2, GL_BOOL_VEC2 -> glProgramUniform2iv(program, loc, i[u]);
					case GL_INT_VEC3, GL_BOOL_VEC3 -> glProgramUniform3iv(program, loc, i[u]);
					case GL_INT_VEC4, GL_BOOL_VEC4 -> glProgramUniform4iv(program, loc, i[u]);
					default -> glProgramUniform1iv(program, loc, i[u]); // int · bool · 샘플러
				}
			}
		}

		private static boolean floatType(int t) {
			return switch (t) {
				case GL_FLOAT, GL_FLOAT_VEC2, GL_FLOAT_VEC3, GL_FLOAT_VEC4, GL_FLOAT_MAT2, GL_FLOAT_MAT3, GL_FLOAT_MAT4 -> true;
				default -> false;
			};
		}

		private static int components(int t) {
			return switch (t) {
				case GL_FLOAT_VEC2, GL_INT_VEC2, GL_BOOL_VEC2, GL_FLOAT_MAT2 -> t == GL_FLOAT_MAT2 ? 4 : 2;
				case GL_FLOAT_VEC3, GL_INT_VEC3, GL_BOOL_VEC3 -> 3;
				case GL_FLOAT_VEC4, GL_INT_VEC4, GL_BOOL_VEC4 -> 4;
				case GL_FLOAT_MAT3 -> 9;
				case GL_FLOAT_MAT4 -> 16;
				default -> 1;
			};
		}

		/** 지원하지 않는 형 (비정방 행렬 등) 이 있으면 false → 풀에 넣지 않는다. */
		boolean supported() {
			for (int t : type) {
				switch (t) {
					case GL_FLOAT, GL_FLOAT_VEC2, GL_FLOAT_VEC3, GL_FLOAT_VEC4, GL_FLOAT_MAT2, GL_FLOAT_MAT3, GL_FLOAT_MAT4, GL_INT, GL_INT_VEC2,
						 GL_INT_VEC3, GL_INT_VEC4, GL_BOOL, GL_BOOL_VEC2, GL_BOOL_VEC3, GL_BOOL_VEC4, GL_SAMPLER_2D, GL_SAMPLER_CUBE,
						 GL_SAMPLER_3D, GL_SAMPLER_2D_SHADOW -> {
					}
					default -> {
						return false;
					}
				}
			}
			return true;
		}
	}

	/**
	 * 클래스패스 /shaders/ 소스. {@code #include "파일"} 줄은 그 파일 내용으로 바꾼다 (GLSL 410 에 없는 지시어 — 안개 공용 코드 fog.glsl 을
	 * basic.frag · particle.frag · lensflare.frag 가 함께 쓴다, 31 문서 §3.1). 같은 파일은 한 번만.
	 */
	public static String source(String res) {
		return expand(res, new java.util.HashSet<>());
	}

	private static String expand(String res, java.util.Set<String> seen) {
		String src;
		try (InputStream in = Shader.class.getResourceAsStream("/shaders/" + res)) {
			if (in == null) {
				throw new IllegalArgumentException("셰이더 없음: " + res);
			}
			src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (!src.contains("#include")) {
			return src;
		}
		StringBuilder out = new StringBuilder(src.length() + 4096);
		for (String line : src.split("\n", -1)) {
			String t = line.strip();
			if (t.startsWith("#include \"") && t.endsWith("\"")) {
				String inc = t.substring(10, t.length() - 1);
				if (seen.add(inc)) {
					out.append(expand(inc, seen));
				}
				out.append('\n');
			} else {
				out.append(line).append('\n');
			}
		}
		out.setLength(out.length() - 1);
		return out.toString();
	}

	private static String load(String res) {
		return source(res);
	}

	private static int compile(int type, String src) {
		int s = glCreateShader(type);
		glShaderSource(s, src);
		glCompileShader(s);
		if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE) {
			throw new IllegalStateException("셰이더 컴파일 실패: " + glGetShaderInfoLog(s));
		}
		return s;
	}

	public void use() {
		glUseProgram(program);
	}

	public int location(String name) {
		return loc(name);
	}

	private int loc(String name) {
		// computeIfAbsent 의 람다가 this 를 잡아 호출마다 할당 → 유니폼 설정마다 객체 (48 문서 §3)
		Integer l = uniforms.get(name);
		if (l == null) {
			l = glGetUniformLocation(program, name);
			uniforms.put(name, l);
		}
		return l;
	}

	public void set(String name, Matrix4f m) {
		try (MemoryStack st = MemoryStack.stackPush()) {
			glUniformMatrix4fv(loc(name), false, m.get(st.mallocFloat(16)));
		}
	}

	public void set(String name, Vector4f v) {
		glUniform4f(loc(name), v.x, v.y, v.z, v.w);
	}

	public void set(String name, int v) {
		glUniform1i(loc(name), v);
	}

	public void set(String name, float v) {
		glUniform1f(loc(name), v);
	}

	public void set(String name, float x, float y) {
		glUniform2f(loc(name), x, y);
	}

	public void set(String name, float x, float y, float z) {
		glUniform3f(loc(name), x, y, z);
	}

	public void set(String name, float x, float y, float z, float w) {
		glUniform4f(loc(name), x, y, z, w);
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		if (defaults != null && defaults.supported()) {
			java.util.ArrayDeque<Idle> q = POOL.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
			if (q.size() < POOL_MAX) {
				q.addLast(new Idle(program, defaults));
				return;
			}
		}
		GlStats.delProgram(program);
	}
}
