package devple.gow.engine.level;

import devple.gow.tools.level.GoInstance;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** ATHN01A(배 갑판) 충돌 시트로 캐릭터 질의 검증. */
class LevelCollisionTest {

	private static LevelData data;
	private static LevelCollision col;

	@BeforeAll
	static void load() throws Exception {
		Path wad = Path.of(System.getProperty("gow.rom", "../gow-rom"), "ATHN01A.WAD");
		Assumptions.assumeTrue(Files.exists(wad), "gow-rom 없음");
		data = new LevelData(WadFile.load(wad));
		col = new LevelCollision(data.sheets);
	}

	private static GoInstance start() {
		return data.layout.instances.stream().filter(g -> g.object().equals("start")).findFirst().orElseThrow();
	}

	@Test
	void groundUnderStart() {
		float[] p = start().pos();
		float y = col.groundHeight(p[0], p[1] + 20, p[2], 200);
		assertFalse(Float.isNaN(y), "시작 위치 아래 바닥 없음");
		assertTrue(Math.abs(y - p[1]) < 25, "바닥 높이 " + y + " vs 시작 " + p[1]);
	}

	@Test
	void raycastDownMatchesGround() {
		float[] p = start().pos();
		LevelCollision.Hit h = col.raycast(new Vector3f(p[0], p[1] + 20, p[2]), new Vector3f(0, -1, 0), 300);
		assertNotNull(h);
		assertEquals(col.groundHeight(p[0], p[1] + 20, p[2], 300), h.point().y, 0.01f);
	}

	@Test
	void wallsPushOut() {
		// 벽 삼각형 하나를 골라 그 면 위 점에서 구를 밀어낸다
		LevelCollision.Tri wall = col.triangles().stream().filter(t -> !t.floor()).findFirst().orElseThrow();
		Vector3f c = new Vector3f(wall.a()).add(wall.b()).add(wall.c()).div(3);
		Vector3f before = new Vector3f(c);
		assertTrue(col.collideSphere(c, 5));
		assertTrue(c.distance(before) > 1f);
	}

	@Test
	void cameraVolumesSelectCamera() {
		assertFalse(data.placedCameras.isEmpty());
		long cmv = data.volumes.stream().filter(v -> v.hull().kind == 1).count();
		assertTrue(cmv >= 1);
		// Boat1Cams 볼륨의 hull 중심은 그 hull 이 가리키는 카메라를 활성화해야 한다
		LevelData.PlacedVolume v = data.volumes.stream().filter(x -> x.hull().name.equals("CMV_Boat1Cams")).findFirst().orElseThrow();
		float[] hc = v.hull().hulls.getFirst().center();
		float[] w = new float[3];
		devple.gow.tools.anim.Mat4.transformPoint(v.world(), hc[0], hc[1], hc[2], w);
		assertEquals(0, v.find(w[0], w[1], w[2]));
		assertNotNull(data.activeCamera(w[0], w[1], w[2]));
	}
}
