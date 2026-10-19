package devple.gow.engine.level;

import devple.gow.engine.asset.RenderModel;
import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.core.Engine;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Mesh;
import devple.gow.engine.render.MeshBuilder;
import devple.gow.engine.scene.Scene;
import devple.gow.tools.anim.Mat4;
import devple.gow.tools.level.GoInstance;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.level.PlacedMesh;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.model.TriMesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL41C.*;

/**
 * 레벨 WAD 하나를 배치 그대로 그리는 씬 (12 / 23 문서).
 * <pre>
 * 조작  WASD 이동, 방향키 시선, Q/E 하강/상승, Shift 빠르게
 *       1 = 충돌 시트 와이어 (초록 바닥 / 빨강 벽)   2 = 볼륨 와이어 (CMV 파랑, ENV 노랑, SEV 보라, CDV 주황)
 *       Tab(SELECT) = 카메라 모드 순환: 자유 → 레벨 카메라 0..n-1      3 = 시작 위치 기준 CMV 자동 카메라
 * </pre>
 * 하늘(이름/텍스처에 "sky")은 원작 SCR_Sky 처럼 카메라 위치를 따라가게 그린다.
 */
public final class LevelScene implements Scene {

	/** 배치된 렌더 인스턴스. sky 면 카메라를 따라간다 (중심 = offset). */
	public record Instance(String name, RenderModel model, boolean sky, Vector3f offset) {
	}

	private final Path wadPath;
	private final int startCam;
	private boolean showCollision;
	private boolean showVolumes;
	private WadAssets assets;
	private LevelData data;
	private LevelCollision collision;
	private final List<Instance> instances = new ArrayList<>();
	private Mesh collisionLines;
	private Mesh volumeLines;
	private final Camera cam = new Camera();
	private final Vector3f pos = new Vector3f();
	private float yaw;
	private float pitch = -0.25f;
	/** -1 = 자유 카메라, 0.. = 레벨 카메라 번호, -2 = CMV 자동. */
	private int camMode;
	private final boolean[] keyPrev = new boolean[3];
	private final Matrix4f tmp = new Matrix4f();

	/**
	 * @param startCam    -1 자유, -2 CMV 자동, 0.. 레벨 카메라 번호
	 * @param collisionOn 충돌 와이어 처음부터 표시
	 */
	public LevelScene(Path wadPath, int startCam, boolean collisionOn) {
		this.wadPath = wadPath;
		this.startCam = startCam;
		this.showCollision = collisionOn;
		this.showVolumes = collisionOn;
	}

	public LevelData data() {
		return data;
	}

	public LevelCollision collision() {
		return collision;
	}

	public List<Instance> instances() {
		return instances;
	}

	@Override
	public void enter(Engine e) {
		try {
			assets = new WadAssets(wadPath);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		data = new LevelData(assets.wad());
		collision = new LevelCollision(data.sheets);
		List<TriMesh> all = new ArrayList<>();
		for (LevelLayout.Placement p : data.layout.placements()) {
			String mdl = p.model();
			GowModel gm = mdl == null ? null : assets.gowModel(mdl);
			if (gm == null || p.instance().scale() == 0) {
				continue;
			}
			TriMesh tm = PlacedMesh.build(gm, p.object().skeleton(), p.jointWorld(), 0);
			if (tm.subs.isEmpty()) {
				continue;
			}
			float[] b = tm.bounds();
			Vector3f off = new Vector3f((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
			// 하늘 돔: 이름/텍스처에 sky + 원점 중심(XZ)의 큰 메시. PAND01A 의 "sky" 처럼 레벨 안에 놓인 하늘 조각은 제외
			float r = Math.max(b[3] - b[0], b[5] - b[2]) / 2;
			boolean sky = isSky(p, gm) && r > 500 && Math.hypot(off.x, off.z) < r * 0.1f;
			if (!sky) {
				all.add(tm);
			}
			instances.add(new Instance(p.instance().name(), assets.build(p.instance().name(), tm), sky, off));
		}
		collisionLines = buildCollisionLines();
		volumeLines = buildVolumeLines();
		cam.near = 2f;
		cam.far = 30000f;
		cam.fovDeg = 60f;
		placeStart(all);
		camMode = startCam;
		System.out.printf(Locale.ROOT, "레벨 %s: 렌더 인스턴스 %d (하늘 %d), 충돌 삼각형 %d, 볼륨 %d, 카메라 %d%n", assets.wad().name(),
				instances.size(), instances.stream().filter(Instance::sky).count(), collision.triangles().size(),
				data.volumes.size(), data.placedCameras.size());
	}

	private boolean isSky(LevelLayout.Placement p, GowModel gm) {
		if (p.object().name().toLowerCase(Locale.ROOT).contains("sky")) {
			return true;
		}
		for (String m : gm.materials) {
			String t = assets.materialTexture(m);
			if (t != null && t.toLowerCase(Locale.ROOT).contains("sky")) {
				return true;
			}
		}
		return false;
	}

	/** 자유 카메라 시작: 충돌 시트 AABB 를 비스듬히 내려다보는 위치, 없으면 지오메트리 중심. */
	private void placeStart(List<TriMesh> meshes) {
		if (!data.sheets.isEmpty()) {
			float[] mn = data.sheets.getFirst().min, mx = data.sheets.getFirst().max;
			// 깊은 낙사 바닥(y 가 크게 낮은 면)은 무시하고 바닥 근처 높이를 쓴다
			GoInstance start = data.layout.instances.stream().filter(g -> g.object().startsWith("start")).findFirst().orElse(null);
			float cx = (mn[0] + mx[0]) / 2, cz = (mn[2] + mx[2]) / 2;
			float cy = start != null ? start.pos()[1] : mx[1];
			float ext = Math.min(Math.max(mx[0] - mn[0], mx[2] - mn[2]), 1500);
			pitch = -0.6f;
			yaw = 0;
			pos.set(cx, cy + ext * 0.55f, cz + ext * 0.8f);
		} else if (!meshes.isEmpty()) {
			float[] b = PlacedMesh.bounds(meshes);
			pos.set((b[0] + b[3]) / 2, b[4] + 50, b[5] + 100);
			yaw = 0;
		}
	}

	private Mesh buildCollisionLines() {
		MeshBuilder mb = new MeshBuilder();
		for (LevelCollision.Tri t : collision.triangles()) {
			float r = t.floor() ? 0.2f : 1f, g = t.floor() ? 1f : 0.25f, b = 0.2f;
			mb.line(t.a().x, t.a().y, t.a().z, t.b().x, t.b().y, t.b().z, r, g, b);
			mb.line(t.b().x, t.b().y, t.b().z, t.c().x, t.c().y, t.c().z, r, g, b);
			mb.line(t.c().x, t.c().y, t.c().z, t.a().x, t.a().y, t.a().z, r, g, b);
		}
		return mb.build(GL_LINES);
	}

	private Mesh buildVolumeLines() {
		MeshBuilder mb = new MeshBuilder();
		float[] a = new float[3];
		float[] b = new float[3];
		for (LevelData.PlacedVolume v : data.volumes) {
			float[] c = switch (v.hull().kind) {
				case 0 -> new float[]{1f, 0.55f, 0.1f};
				case 1 -> new float[]{0.3f, 0.5f, 1f};
				case 2 -> new float[]{1f, 1f, 0.2f};
				default -> new float[]{0.8f, 0.3f, 1f};
			};
			if (v.wire() != null) {
				float[][] vs = v.wire().vertices;
				int[] l = v.wire().lines;
				for (int i = 0; i + 1 < l.length; i += 2) {
					if (l[i] >= vs.length || l[i + 1] >= vs.length) {
						continue;
					}
					Mat4.transformPoint(v.world(), vs[l[i]][0], vs[l[i]][1], vs[l[i]][2], a);
					Mat4.transformPoint(v.world(), vs[l[i + 1]][0], vs[l[i + 1]][1], vs[l[i + 1]][2], b);
					mb.line(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]);
				}
			}
			for (float[] ball : v.hull().balls) {
				for (int k = 0; k < 3; k++) {
					for (int s = 0; s < 24; s++) {
						double t0 = s * Math.PI / 12, t1 = (s + 1) * Math.PI / 12;
						float[] p0 = circle(ball, k, t0), p1 = circle(ball, k, t1);
						Mat4.transformPoint(v.world(), p0[0], p0[1], p0[2], a);
						Mat4.transformPoint(v.world(), p1[0], p1[1], p1[2], b);
						mb.line(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]);
					}
				}
			}
		}
		return mb.build(GL_LINES);
	}

	private static float[] circle(float[] s, int axis, double t) {
		float u = (float) Math.cos(t) * s[3], w = (float) Math.sin(t) * s[3];
		return switch (axis) {
			case 0 -> new float[]{s[0] + u, s[1] + w, s[2]};
			case 1 -> new float[]{s[0] + u, s[1], s[2] + w};
			default -> new float[]{s[0], s[1] + u, s[2] + w};
		};
	}

	private boolean edge(Engine e, int idx, int key) {
		boolean down = glfwGetKey(e.window().handle(), key) == GLFW_PRESS;
		boolean r = down && !keyPrev[idx];
		keyPrev[idx] = down;
		return r;
	}

	@Override
	public void update(Engine e, float dt) {
		if (edge(e, 0, GLFW_KEY_1)) {
			showCollision = !showCollision;
		}
		if (edge(e, 1, GLFW_KEY_2)) {
			showVolumes = !showVolumes;
		}
		if (edge(e, 2, GLFW_KEY_3)) {
			camMode = camMode == -2 ? -1 : -2;
		}
		if (e.input().pressed(PadButton.SELECT)) {
			camMode = camMode + 1 >= data.placedCameras.size() ? -1 : camMode + 1;
			if (camMode >= 0) {
				System.out.println("레벨 카메라 " + camMode + ": " + data.placedCameras.get(camMode).def().name());
			}
		}
		yaw -= e.input().rightX() * dt * 1.6f;
		pitch = Math.clamp(pitch + e.input().rightY() * dt * 1.2f, -1.5f, 1.5f);
		float speed = (e.input().held(PadButton.L3) ? 600 : 150) * dt;
		Vector3f f = forward();
		Vector3f r = new Vector3f(f).cross(0, 1, 0).normalize();
		pos.fma(e.input().leftY() * speed, f).fma(e.input().leftX() * speed, r);
		if (e.input().held(PadButton.R1)) {
			pos.y += speed;
		}
		if (e.input().held(PadButton.L1)) {
			pos.y -= speed;
		}
	}

	private Vector3f forward() {
		return new Vector3f((float) (-Math.sin(yaw) * Math.cos(pitch)), (float) Math.sin(pitch), (float) (-Math.cos(yaw) * Math.cos(pitch)));
	}

	private void applyCamera() {
		LevelData.PlacedCamera lc = null;
		if (camMode >= 0 && camMode < data.placedCameras.size()) {
			lc = data.placedCameras.get(camMode);
		} else if (camMode == -2) {
			lc = data.activeCamera(pos.x, pos.y, pos.z);
			if (lc == null) {
				GoInstance s = data.layout.instances.stream().filter(g -> g.object().startsWith("start")).findFirst().orElse(null);
				if (s != null) {
					lc = data.activeCamera(s.pos()[0], s.pos()[1] + 10, s.pos()[2]);
				}
			}
			if (lc == null && !data.placedCameras.isEmpty()) {
				lc = data.placedCameras.getFirst();
			}
		}
		if (lc != null) {
			float[] eye = lc.eye();
			float[] fw = lc.forward();
			cam.eye.set(eye[0], eye[1], eye[2]);
			cam.target.set(eye[0] + fw[0] * 100, eye[1] + fw[1] * 100, eye[2] + fw[2] * 100);
		} else {
			cam.eye.set(pos);
			cam.target.set(pos).add(forward());
		}
	}

	@Override
	public void render(Engine e, float alpha) {
		applyCamera();
		e.renderer().begin3d(cam);
		// 하늘: 카메라 중심, 깊이 쓰기 끔 → 항상 맨 뒤
		glDepthMask(false);
		for (Instance i : instances) {
			if (i.sky()) {
				tmp.translation(cam.eye.x - i.offset().x, cam.eye.y - i.offset().y, cam.eye.z - i.offset().z);
				e.renderer().draw(i.model(), tmp);
			}
		}
		glDepthMask(true);
		tmp.identity();
		for (Instance i : instances) {
			if (!i.sky()) {
				e.renderer().draw(i.model(), tmp);
			}
		}
		if (showCollision || showVolumes) {
			e.renderer().begin3d(cam);
			glDisable(GL_DEPTH_TEST);
			if (showCollision) {
				e.renderer().draw(collisionLines, tmp.identity(), false);
			}
			if (showVolumes) {
				e.renderer().draw(volumeLines, tmp.identity(), false);
			}
			glEnable(GL_DEPTH_TEST);
		}
	}

	@Override
	public void exit(Engine e) {
		collisionLines.close();
		volumeLines.close();
		instances.forEach(i -> i.model().close());
		assets.close();
	}
}
