package devple.gow.engine.render;

import devple.gow.engine.core.Engine;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import static org.lwjgl.opengl.GL41C.*;

/** 프레임 시작/끝, 공용 셰이더, 화면 페이드. */
public final class Renderer implements AutoCloseable {

	private final Engine engine;
	private final Shader basic;
	private final Mesh fullscreen;
	private final Matrix4f identity = new Matrix4f();
	private final Texture white;

	public Renderer(Engine engine) {
		this.engine = engine;
		this.basic = new Shader("basic.vert", "basic.frag");
		this.white = Texture.white();
		this.fullscreen = new MeshBuilder()
				.vertex(-1, -1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1)
				.vertex(1, -1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 1)
				.vertex(1, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0, 1)
				.vertex(-1, -1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1)
				.vertex(1, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0, 1)
				.vertex(-1, 1, 0, 0, 0, 1, 0, 1, 0, 0, 0, 1)
				.build(GL_TRIANGLES);
	}

	public Shader basic() {
		return basic;
	}

	public void beginFrame() {
		glViewport(0, 0, engine.window().width(), engine.window().height());
		glClearColor(0.05f, 0.04f, 0.04f, 1f);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
	}

	/** 3D 드로우 공통 설정. */
	public void begin3d(Camera cam) {
		basic.use();
		white.bind(0);
		basic.set("uTex", 0);
		basic.set("uViewProj", cam.viewProj(engine.window().aspect()));
		basic.set("uTint", new Vector4f(1, 1, 1, 1));
		basic.set("uUseTex", 0);
		basic.set("uLit", 1);
	}

	public void draw(Mesh m, Matrix4f model, boolean lit) {
		basic.set("uModel", model);
		basic.set("uLit", lit ? 1 : 0);
		m.draw();
	}

	public void endFrame(float fade) {
		if (fade <= 0f) {
			return;
		}
		glDisable(GL_DEPTH_TEST);
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		basic.use();
		basic.set("uViewProj", identity);
		basic.set("uModel", identity);
		basic.set("uUseTex", 0);
		basic.set("uLit", 0);
		basic.set("uTint", new Vector4f(1, 1, 1, fade));
		fullscreen.draw();
		glDisable(GL_BLEND);
		glEnable(GL_DEPTH_TEST);
	}

	@Override
	public void close() {
		fullscreen.close();
		white.close();
		basic.close();
	}
}
