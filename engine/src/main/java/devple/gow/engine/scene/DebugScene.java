package devple.gow.engine.scene;

import devple.gow.engine.core.Engine;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Mesh;
import devple.gow.engine.render.MeshBuilder;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL41C.GL_LINES;
import static org.lwjgl.opengl.GL41C.GL_TRIANGLES;

/** 엔진 동작 확인용 씬: 바닥 그리드 + 이동 가능한 박스 + 추적 카메라. START 로 씬 전환. */
public final class DebugScene implements Scene {

	private final float hue;
	private final Camera cam = new Camera();
	private final Vector3f pos = new Vector3f();
	private final Vector3f prevPos = new Vector3f();
	private final Matrix4f model = new Matrix4f();
	private Mesh grid;
	private Mesh box;

	public DebugScene(float hue) {
		this.hue = hue;
	}

	@Override
	public void enter(Engine e) {
		MeshBuilder g = new MeshBuilder();
		for (int i = -20; i <= 20; i++) {
			float c = i == 0 ? 0.8f : 0.3f;
			g.line(i, 0, -20, i, 0, 20, c, c, c);
			g.line(-20, 0, i, 20, 0, i, c, c, c);
		}
		grid = g.build(GL_LINES);
		box = new MeshBuilder().box(0, 1, 0, 0.4f, 1, 0.3f, 0.8f, hue, 0.2f).build(GL_TRIANGLES);
	}

	@Override
	public void update(Engine e, float dt) {
		prevPos.set(pos);
		pos.add(e.input().leftX() * 6 * dt, 0, -e.input().leftY() * 6 * dt);
		if (e.input().pressed(PadButton.START)) {
			e.scenes().transitionTo(new DebugScene(hue > 0.5f ? 0.2f : 0.8f));
		}
	}

	@Override
	public void render(Engine e, float alpha) {
		Vector3f p = new Vector3f(prevPos).lerp(pos, alpha);
		cam.target.set(p).add(0, 1, 0);
		cam.eye.set(p).add(0, 4, 9);
		e.renderer().begin3d(cam);
		e.renderer().draw(grid, model.identity(), false);
		e.renderer().draw(box, model.translation(p), true);
	}

	@Override
	public void exit(Engine e) {
		grid.close();
		box.close();
	}
}
