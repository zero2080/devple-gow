package devple.gow.engine.scene;

import devple.gow.engine.asset.RenderModel;
import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.core.Engine;
import devple.gow.engine.render.Camera;
import org.joml.Matrix4f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 원본 WAD 의 모델을 텍스처/머티리얼 그대로 보는 뷰어. 좌스틱(WASD) = 회전/줌.
 * 모델 이름이 "*" 이면 WAD 의 모든 모델을 배치 변환 없이 함께 그린다 (레벨 정적 메시 좌표계 확인용).
 */
public final class ModelViewerScene implements Scene {

	private final Path wadPath;
	private final String mdlName;
	private final Camera cam = new Camera();
	private final Matrix4f world = new Matrix4f();
	private WadAssets assets;
	private final List<RenderModel> models = new ArrayList<>();
	private float[] mn;
	private float[] mx;
	private float yaw = 0.6f;
	private float dist = 1.6f;

	public ModelViewerScene(Path wadPath, String mdlName) {
		this.wadPath = wadPath;
		this.mdlName = mdlName;
	}

	@Override
	public void enter(Engine e) {
		try {
			assets = new WadAssets(wadPath);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		System.out.println("모델 목록: " + assets.modelNames());
		List<String> names = "*".equals(mdlName) ? assets.modelNames()
				: List.of(mdlName != null ? mdlName : assets.modelNames().getFirst());
		mn = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		mx = new float[]{-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (String n : names) {
			RenderModel m = assets.model(n);
			models.add(m);
			for (int k = 0; k < 3; k++) {
				mn[k] = Math.min(mn[k], m.min()[k]);
				mx[k] = Math.max(mx[k], m.max()[k]);
			}
		}
	}

	@Override
	public void update(Engine e, float dt) {
		yaw += e.input().leftX() * dt * 2;
		dist = Math.max(0.2f, dist - e.input().leftY() * dt);
	}

	@Override
	public void render(Engine e, float alpha) {
		float cx = (mn[0] + mx[0]) / 2, cy = (mn[1] + mx[1]) / 2, cz = (mn[2] + mx[2]) / 2;
		float r = Math.max(mx[0] - mn[0], Math.max(mx[1] - mn[1], mx[2] - mn[2]));
		cam.near = r * 0.01f;
		cam.far = r * 20;
		cam.target.set(cx, cy, cz);
		cam.eye.set(cx + (float) Math.sin(yaw) * r * dist, cy + r * 0.15f, cz + (float) Math.cos(yaw) * r * dist);
		e.renderer().begin3d(cam);
		for (RenderModel m : models) {
			e.renderer().draw(m, world.identity());
		}
	}

	@Override
	public void exit(Engine e) {
		assets.close();
	}
}
