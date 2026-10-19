package devple.gow.engine.core;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL41C.*;

/** GLFW 창 + OpenGL 4.1 core 컨텍스트 (macOS 상한). */
public final class Window implements AutoCloseable {

	private final long handle;
	private int fbWidth;
	private int fbHeight;

	public Window(String title, int width, int height) {
		GLFWErrorCallback.createPrint(System.err).set();
		if (!glfwInit()) {
			throw new IllegalStateException("GLFW 초기화 실패");
		}
		glfwDefaultWindowHints();
		glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
		glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
		glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
		glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
		glfwWindowHint(GLFW_COCOA_RETINA_FRAMEBUFFER, GLFW_TRUE);
		handle = glfwCreateWindow(width, height, title, 0, 0);
		if (handle == 0) {
			throw new IllegalStateException("창 생성 실패");
		}
		glfwMakeContextCurrent(handle);
		glfwSwapInterval(1);
		GL.createCapabilities();
		glfwSetFramebufferSizeCallback(handle, (w, fw, fh) -> {
			fbWidth = fw;
			fbHeight = fh;
		});
		int[] fw = new int[1];
		int[] fh = new int[1];
		glfwGetFramebufferSize(handle, fw, fh);
		fbWidth = fw[0];
		fbHeight = fh[0];
		glEnable(GL_DEPTH_TEST);
	}

	public long handle() {
		return handle;
	}

	public int width() {
		return fbWidth;
	}

	public int height() {
		return fbHeight;
	}

	public float aspect() {
		return fbHeight == 0 ? 1f : (float) fbWidth / fbHeight;
	}

	public boolean shouldClose() {
		return glfwWindowShouldClose(handle);
	}

	public void requestClose() {
		glfwSetWindowShouldClose(handle, true);
	}

	public void swap() {
		glfwSwapBuffers(handle);
		glfwPollEvents();
	}

	@Override
	public void close() {
		glfwDestroyWindow(handle);
		glfwTerminate();
	}
}
