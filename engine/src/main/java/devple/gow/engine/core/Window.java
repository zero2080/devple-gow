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
		glfwSwapInterval(System.getProperty("gow.novsync") != null ? 0 : 1); // -Dgow.novsync: 측정용 (48 문서)
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
		// macOS: Gradle 데몬 등 터미널 밖에서 띄운 프로세스는 앞으로 오지 않아 키 입력이 터미널로 갈 수 있다 → 창을 앞으로, 포커스 변화 로그
		glfwShowWindow(handle);
		activateApp();
		glfwFocusWindow(handle);
		glfwSetWindowFocusCallback(handle, (w, focused) ->
				System.out.println(focused ? "창 포커스: 키 입력 받음" : "창 포커스 잃음: 창을 클릭하면 키 입력이 다시 들어온다"));
		if (System.getProperty("gow.keylog") != null) { // 키 진단: -Dgow.keylog (Gradle -PgowKeyLog)
			glfwSetKeyCallback(handle, (w, key, scancode, action, mods) -> {
				if (action != GLFW_REPEAT) {
					System.out.printf("키 %s %s (scancode %d)%n", glfwGetKeyName(key, scancode) != null ? glfwGetKeyName(key, scancode) : key,
							action == GLFW_PRESS ? "누름" : "뗌", scancode);
				}
			});
		}
	}

	/**
	 * macOS: [NSApp activateIgnoringOtherApps:YES] — 터미널·Gradle 에서 띄운 번들 없는 프로세스는 최근 macOS 에서 스스로 앞으로 오지 않아
	 * 창이 보여도 키 입력이 들어오지 않는다. 다른 OS 에서는 아무것도 안 한다.
	 */
	private static void activateApp() {
		if (org.lwjgl.system.Platform.get() != org.lwjgl.system.Platform.MACOSX) {
			return;
		}
		try {
			long send = org.lwjgl.system.macosx.ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
			long nsApp = org.lwjgl.system.JNI.invokePPP(org.lwjgl.system.macosx.ObjCRuntime.objc_getClass("NSApplication"),
					org.lwjgl.system.macosx.ObjCRuntime.sel_getUid("sharedApplication"), send);
			org.lwjgl.system.JNI.invokePPV(nsApp, org.lwjgl.system.macosx.ObjCRuntime.sel_getUid("activateIgnoringOtherApps:"), true, send);
		} catch (RuntimeException | UnsatisfiedLinkError ex) {
			System.out.println("앱 활성화 실패: " + ex.getMessage());
		}
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
