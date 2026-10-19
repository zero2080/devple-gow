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

/** GLSL 프로그램. 소스는 클래스패스 /shaders/ 에서 로드. */
public final class Shader implements AutoCloseable {

	private final int program;
	private final Map<String, Integer> uniforms = new HashMap<>();

	public Shader(String vertRes, String fragRes) {
		int vs = compile(GL_VERTEX_SHADER, load(vertRes));
		int fs = compile(GL_FRAGMENT_SHADER, load(fragRes));
		program = glCreateProgram();
		glAttachShader(program, vs);
		glAttachShader(program, fs);
		glLinkProgram(program);
		if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
			throw new IllegalStateException("셰이더 링크 실패: " + glGetProgramInfoLog(program));
		}
		glDeleteShader(vs);
		glDeleteShader(fs);
	}

	private static String load(String res) {
		try (InputStream in = Shader.class.getResourceAsStream("/shaders/" + res)) {
			if (in == null) {
				throw new IllegalArgumentException("셰이더 없음: " + res);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
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
		return uniforms.computeIfAbsent(name, n -> glGetUniformLocation(program, n));
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

	@Override
	public void close() {
		glDeleteProgram(program);
	}
}
