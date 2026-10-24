package devple.gow.engine.level;

import devple.gow.tools.level.CameraDef;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.NcvCurve;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 레벨 카메라: NCV 레일 포맷, CAM 파라미터, CMV 선택/레일 추적/전환 (30 문서). */
class CameraRigTest {

	private static LevelData load(String level) throws Exception {
		Path wad = Path.of(System.getProperty("gow.rom", "../gow-rom"), level + ".WAD");
		Assumptions.assumeTrue(Files.exists(wad), "gow-rom 없음");
		return new LevelData(WadFile.load(wad));
	}

	@Test
	void railIsContinuousWorldCurve() throws Exception {
		LevelData d = load("ATHN01A");
		NcvCurve r = d.curves.get("NCV_Boat1Rail1");
		assertNotNull(r);
		assertEquals(5, r.seg.length);
		// 구간 경계에서 이어진다 (전역 t 다항식)
		float[] a = new float[3], b = new float[3];
		for (int i = 0; i < r.knots.length - 1; i++) {
			float k = r.knots[i];
			r.eval(k - 1e-4f, a);
			r.eval(k + 1e-4f, b);
			assertEquals(a[2], b[2], 0.5f, "매듭 " + k);
		}
		// Boat1Cam1 월드 위치가 레일 위 (레일 = 월드 좌표)
		LevelData.PlacedCamera c = d.camera("Boat1Cam1");
		float[] e = c.eye();
		assertEquals(1314f, e[0], 1f);
		assertEquals(80.9f, e[1], 1f);
		assertSame(r, c.rail());
	}

	@Test
	void cameraParams() throws Exception {
		LevelData d = load("ATHN01A");
		CameraDef.Params p = d.camera("Boat1Cam4").def().p();
		assertEquals(5, p.priority());
		assertEquals(0.8f, p.damping(), 1e-4f);
		assertEquals(3, p.pitchMode());
		assertEquals(3, p.yawMode());
		assertEquals(160f, p.railMax(), 1e-3f);
		assertTrue(d.camera("IdleCam").def().p().animated());
		CameraDef c1 = d.camera("Boat1Cam1").def();
		assertEquals(2, c1.transitions().size());
		assertEquals("Boat1Cam2", c1.transitions().getFirst().from());
		assertEquals(1.3f, c1.transitions().getFirst().blend(), 1e-4f);
	}

	@Test
	void boatRunFramesKratos() throws Exception {
		LevelData d = load("ATHN01A");
		CameraRig rig = new CameraRig(d);
		Vector3f hero = new Vector3f(1536, 19.2f, 1956.6f);
		// 시작 인트로 (ESC_IdleCamGroup / ESC_EnemyIdleCamGroup): 생성 센서가 Idle2Cam 볼륨을 끄고, 시작 지점의 관절 애니 카메라 IdleCam (우선순위 50)
		rig.setOwnerVolumes("goenemyidlecamgroup", false);
		rig.update(1 / 60f, hero, 0, false);
		assertEquals("IdleCam", rig.current());
		// 9.5 초 뒤 IdleCamGroup-VisOff → 볼륨 카메라 Boat1Cam4
		rig.setOwnerVolumes("goidlecamgroup", false);
		rig.update(1 / 60f, hero, 0, false);
		assertEquals("Boat1Cam4", rig.current());
		// 갑판을 따라 −z 로 달린다: Boat1Cam1 로 전환(전환표 1.5초), 레일을 따라 같은 거리로 따라간다
		Vector3f eye = new Vector3f();
		for (int i = 0; i < 240; i++) {
			hero.set(1600, 0, 1840 - i * 0.6f);
			rig.update(1 / 60f, hero, 0, false);
			CameraRig.Pose p = rig.pose();
			p.eye(eye);
			float dist = eye.distance(p.target);
			assertTrue(dist > 20 && dist <= 1600.5f, "거리 " + dist);
		}
		assertEquals("Boat1Cam1", rig.current());
		CameraRig.Pose p = rig.pose();
		assertTrue(p.dist <= 160.5f, "Boat1Cam1 최대 거리 160: " + p.dist);
		// 설계 요 고정 (요 범위 0): 카메라 −Z 와 같은 요
		float[] f = d.camera("Boat1Cam1").forward();
		assertEquals(CameraRig.yawOf(new Vector3f(f[0], f[1], f[2])), p.yaw, 0.5f);
	}

	@Test
	void scriptRequestAndVolumeOff() throws Exception {
		LevelData d = load("ATHN01A");
		CameraRig rig = new CameraRig(d);
		Vector3f hero = new Vector3f(1600, 0, 1800);
		rig.update(1 / 60f, hero, 0, false);
		rig.setVolumeQuery(false);
		rig.request("RCM_LiftCam");
		rig.update(1 / 60f, hero, 0, false);
		assertEquals("LiftCam", rig.current());
		for (int i = 0; i < 120; i++) {
			rig.update(1 / 60f, hero, 0, false); // 후보 없음 → 유지
		}
		assertEquals("LiftCam", rig.current());
	}

	@Test
	void arenaFollowsAtDesignerAngle() throws Exception {
		LevelData d = load("ARENA06");
		CameraRig rig = new CameraRig(d);
		Vector3f hero = new Vector3f(-13, 21, 242);
		rig.update(1 / 60f, hero, 0, false);
		assertEquals("camera1", rig.current());
		assertEquals(160f, rig.pose().dist, 0.01f); // 거리 [10,10]×16
		assertEquals(hero.y + CameraRig.TARGET_HEIGHT, rig.pose().target.y, 1e-3f);
	}
	/**
	 * PAND05B camEleEnter (43 §13.11): 관절 카메라 갱신 경로 해독 — 0x1F3768 (0x100 없음, 0x40000) 은 카메라 GO 월드 행렬 (0x1303D0: CAM 로컬 ×
	 * 소유 GO 애니 관절 행렬 0x136990) 을 그대로 상태로 쓰고 주시점 = 행렬 × (0,0,−567.8). 활성화 0x1F3A38 는 카메라 노드 +0x104 (0x13EC10:
	 * 애니 관절에 붙으면 1) 이고 플래그 0x1000 이면 소유 GO 애니를 처음부터 (0x1004B8 · 0x131038).
	 * 관절 camEleEnter 오일러 (656, 2249) → (−959, 0) = 피치 +14.4° → −21.1°, 요 49.4° → 0° (1 초 정지 뒤 패닝, 11.33 초).
	 * <b>정정 (43 §16, 05 §2.8.1)</b>: ANM_startwad 는 데이터 타입 [0(p2 3)] 하나라 디스크립터 1 = 관절 이동 — 예전 배치는 회전만 읽어 눈이 (1080.6, 20.8, 1311.5)
	 * 에 고정이었다 (그래서 영웅이 2 초 뒤 화면 밖). 원작 배치로는 눈이 1–3.5 초에 엘리베이터 판 높이 (y ≈ 180) 로 올라 가운데 (x 869.9) 쪽으로 가고
	 * 내려오는 엘리베이터를 따라 (870, 79, 1227) 까지 내려간다.
	 */
	@Test
	void eleEnterJointCameraPans() throws Exception {
		LevelData d = load("PAND05B");
		CameraRig rig = new CameraRig(d);
		Vector3f hero = new Vector3f(832, 288, 960); // WarpFrom05A (엘리베이터 위)
		rig.update(0, hero, 0, false);
		rig.setVolumeQuery(false); // startWad-start: Internal 0x9(false) + SwitchCamera camEleEnter
		rig.request("camEleEnter");
		rig.update(1 / 60f, hero, 0, false);
		assertEquals("camEleEnter", rig.current());
		assertEquals(0, rig.animTime(), 0.05f, "0x1000: 활성화 때 애니 처음부터");
		Vector3f e0 = rig.eye(), f0 = rig.pose().forward(new Vector3f());
		assertEquals(1080.6f, e0.x, 0.5f);
		assertEquals(20.8f, e0.y, 0.5f);
		assertEquals(1311.5f, e0.z, 0.5f);
		assertEquals(14.4, Math.toDegrees(Math.asin(f0.y)), 0.5);
		float topY = -1e9f;
		for (int i = 0; i < 12 * 60; i++) {
			rig.update(1 / 60f, hero, 0, false);
			topY = Math.max(topY, rig.eye().y);
		}
		assertEquals("camEleEnter", rig.current(), "질의 끔 → 유지");
		Vector3f e1 = rig.eye(), f1 = rig.pose().forward(new Vector3f());
		assertTrue(topY > 175 && topY < 185, "엘리베이터 판 높이까지 오름 " + topY);
		assertEquals(869.9f, e1.x, 1f, "엘리베이터 가운데 x (gostartwad 869.9)");
		assertEquals(78.6f, e1.y, 1f, "내려오는 엘리베이터를 따라 " + e1);
		assertEquals(1227f, e1.z, 1.5f);
		assertEquals(-21.1, Math.toDegrees(Math.asin(f1.y)), 0.5);
		assertEquals(0, Math.toDegrees(Math.atan2(-f1.x, -f1.z)), 1.0, "요 0 (−z)");
	}

	/**
	 * 전투 카메라 덮어쓰기 (이벤트 23, 0x1F46D0 / 0x1F3768 / 0x1EF078 / 0x1EFB58): 가중치 0.01/프레임, 0x2000 카메라 눈 멈춤, 대상 T 가 AimJoint 쪽,
	 * 줌 반경 = |Width − Aim|, 위 벡터 = 롤, 선택 생략 (볼륨을 넘어가도 카메라 유지), 해제하면 바로 원래 화각.
	 */
	@Test
	void combatCameraOverride() throws Exception {
		LevelData d = load("ATHN01A");
		CameraRig rig = new CameraRig(d);
		Vector3f hero = new Vector3f(1600, 0, 1840);
		rig.setOwnerVolumes("goidlecamgroup", false);
		rig.setOwnerVolumes("goenemyidlecamgroup", false);
		for (int i = 0; i < 120; i++) {
			rig.update(1 / 60f, hero, 0, false);
		}
		String cam0 = rig.current();
		assertTrue((d.camera(cam0).def().p().flags() & CameraRig.F_FRAME_TARGET) != 0, cam0 + " 는 0x2000");
		devple.gow.engine.render.Camera c = new devple.gow.engine.render.Camera();
		rig.apply(c, 1);
		float fov0 = c.fovDeg;
		Vector3f eye0 = new Vector3f(c.eye);
		Vector3f aim = new Vector3f(hero).add(30, 40, -20);
		Vector3f up = new Vector3f(0.3f, 1, 0).normalize();
		for (int i = 0; i < 160; i++) {
			hero.z -= 0.6f; // 갑판을 따라 움직여도 (다른 볼륨) 선택은 멈춘다
			rig.combatCamera(aim, 30, up);
			rig.update(1 / 60f, hero, 0, false);
		}
		assertEquals(1f, rig.combatWeight(), 1e-4f, "0.01/프레임 → 100 프레임에 1");
		assertEquals(cam0, rig.current(), "프레이밍 중 선택 생략");
		rig.apply(c, 1);
		assertTrue(c.eye.distance(eye0) < 0.5f, "0x2000 카메라 눈 멈춤: " + c.eye + " / " + eye0);
		Vector3f fwd = new Vector3f(c.target).sub(c.eye).normalize();
		Vector3f toAim = new Vector3f(aim).sub(c.eye).normalize();
		assertTrue(fwd.dot(toAim) > 0.995f, "시선 = AimJoint: " + fwd.dot(toAim));
		float want = CameraRig.verticalFov((float) (2 * Math.atan(30 / c.eye.distance(aim))));
		assertEquals(want, c.fovDeg, 1.5f, "줌: 반경 30 이 화면 가로를 채움 (원래 " + fov0 + ")");
		assertTrue(c.up.x > 0.1f, "CamPos Y 축 롤: " + c.up);
		rig.update(1 / 60f, hero, 0, false); // 부르지 않은 틱 → 해제 (0x1EF058: 줌 바로 0)
		rig.apply(c, 1);
		assertEquals(0, rig.combatWeight());
		assertEquals(0, c.up.x, 1e-6f);
		assertTrue(Math.abs(c.fovDeg - fov0) < 1f, "원래 화각으로 바로: " + c.fovDeg + " / " + fov0);
	}

	/**
	 * 점프 대상 높이 (0x1F2850, CAM 0x800/0x4000): 공중에서 T 는 이륙 높이 위로 오르지 않고 아래로는 따라 내려간다 (max(0, ·) 오프셋), 낮은 곳에
	 * 내리면 오프셋 0, 높은 곳에 내리면 프레임당 ×0.9 로 따라 오른다.
	 */
	@Test
	void jumpHoldFollowsDownNotUp() throws Exception {
		LevelData d = load("ATHN02A");
		LevelData.PlacedCamera hold = d.camera("ScafCam1"); // 0x43BFE (0x800)
		assertTrue((hold.def().p().flags() & 0x800) != 0);
		CameraRig rig = new CameraRig(d);
		rig.setVolumeQuery(false);
		Vector3f hero = new Vector3f(1600, 0, 1840);
		rig.request(hold.def().shortName());
		rig.update(1 / 60f, hero, 0, false);
		assertEquals(hold.def().shortName(), rig.current());
		rig.update(1 / 60f, hero, 0, false);
		rig.update(1 / 60f, hero.add(0, 40, 0), 0, true); // 위로 뜸
		assertEquals(CameraRig.TARGET_HEIGHT, rig.pose().target.y, 1e-3f, "이륙 높이 위로 안 오름");
		// 대상 슬롯은 반지름 1.6 끈으로 끌려온다 (0x1F4380) — 한 틱에 크게 움직이면 원하는 자리에서 1.6 뒤
		float r = CameraRig.SLOT_RADIUS;
		rig.update(1 / 60f, hero.set(1600, -60, 1840), 0, true); // 이륙보다 아래로 떨어짐
		assertEquals(-60 + CameraRig.TARGET_HEIGHT + r, rig.pose().target.y, 0.01f, "아래로는 따라감");
		rig.update(1 / 60f, hero, 0, false); // 낮은 곳에 착지 → 오프셋 0
		assertEquals(-60 + CameraRig.TARGET_HEIGHT + r, rig.pose().target.y, 0.01f);
		rig.update(1 / 60f, hero, 0, true);
		rig.update(1 / 60f, hero.set(1600, 0, 1840), 0, true);
		float low = -60 + CameraRig.TARGET_HEIGHT + r, high = CameraRig.TARGET_HEIGHT - r;
		assertEquals(low, rig.pose().target.y, 0.01f);
		rig.update(1 / 60f, hero, 0, false); // 높은 곳에 착지 → 오프셋 (high − low) 에서 ×0.9
		assertEquals(low, rig.pose().target.y, 0.01f);
		rig.update(1 / 60f, hero, 0, false);
		assertEquals(high - 0.9f * (high - low), rig.pose().target.y, 0.01f, "×0.9");
	}

	/**
	 * 상태 보간 연쇄 (0x1F3C90): 상태 둘이면 smoothstep, 앞 보간의 도함수가 v0 — 도함수 = 0x1F3BC0 의 dp/ds.
	 */
	@Test
	void hermiteChainCarriesDerivative() {
		CameraRig.Pose a = new CameraRig.Pose(), b = new CameraRig.Pose(), c = new CameraRig.Pose(), out = new CameraRig.Pose(), v = new CameraRig.Pose();
		a.dist = 0;
		b.dist = 100;
		CameraRig.blend(a, null, b, 0.5f, out, v);
		assertEquals(50, out.dist, 1e-4f);
		assertEquals(150, v.dist, 1e-3f, "smoothstep 도함수 6s(1−s)·Δ = 1.5·100");
		c.dist = 100;
		CameraRig.Pose o2 = new CameraRig.Pose();
		CameraRig.blend(out, v, c, 0.25f, o2, null);
		float s = 0.25f, h00 = 2 * s * s * s - 3 * s * s + 1, h10 = s * s * s - 2 * s * s + s, h01 = -2 * s * s * s + 3 * s * s;
		assertEquals(h00 * 50 + h10 * 150 + h01 * 100, o2.dist, 1e-3f);
		a.yaw = 170;
		b.yaw = -170;
		CameraRig.blend(a, null, b, 0.5f, out, null);
		assertEquals(180, Math.abs(out.yaw), 1e-3f, "요는 짧은 쪽 (감김)");
	}
}
