package devple.gow.engine.boss;

import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.level.CameraRig;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static devple.gow.engine.boss.HydraFightTest.bossMove;
import static devple.gow.engine.boss.HydraFightTest.contact;
import static devple.gow.engine.boss.HydraFightTest.until;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 스크립트 카메라 (30 문서 §6): 히드라 잡기·처형 카메라 (ESC BossLeftFight/BossRightFight/BossFInalGame 의 SwitchCamera + 질의 끔,
 * 관절 애니 카메라), 아레스 AresCamera1 (아레스 클래스 매 프레임 요청). 크레토스가 화면 안에 있는지까지 본다.
 */
class BossCameraTest {

	/** 현재 카메라 상태에서 점 p 가 화면(4:3) 안이고 거리 maxDist 이내인가. */
	static boolean onScreen(CameraRig rig, Vector3f p, float maxDist) {
		CameraRig.Pose pose = rig.pose();
		Vector3f eye = rig.eye();
		Vector3f fwd = new Vector3f(pose.target).sub(eye).normalize();
		Vector3f right = new Vector3f(fwd).cross(0, 1, 0).normalize();
		Vector3f up = new Vector3f(right).cross(fwd).normalize();
		Vector3f v = new Vector3f(p).sub(eye);
		float z = v.dot(fwd);
		if (z <= 1 || v.length() > maxDist) {
			return false;
		}
		double tv = Math.tan(Math.toRadians(pose.fovDeg) / 2), th = tv * 4 / 3;
		return Math.abs(v.dot(right) / z) < th && Math.abs(v.dot(up) / z) < tv;
	}

	static Vector3f heroChest(BossRig r) {
		return new Vector3f(r.ctl.position).add(0, 20, 0);
	}

	@Test
	void hydraGrabAndKillUseScriptCameras() throws Exception {
		BossRig r = HydraFightTest.spawned();
		EnemyFighter b = r.fight.boss();
		// 인트로 카메라 연출(SpawnCam, camGRPdeckCine-VisCamOFFidle Delay 7.2 → 질의 켬)이 끝난 뒤 싸움
		r.run(6);
		assertTrue(r.log.stream().anyMatch(l -> l.startsWith("cam query true")), r.log.toString());
		r.hydraZone(1);
		contact(r, 71, b.hp - 240);
		assertTrue(until(r, 3, () -> bossMove(r) == 53 || bossMove(r) == 54));
		int slam = bossMove(r);
		assertTrue(until(r, 4, () -> r.fight.boss().combat().progress() >= 0.4f));
		r.run(0.05f);
		contact(r, 200, 0);
		r.tick();
		int grab = bossMove(r);
		assertEquals(slam == 53 ? 806 : 802, grab);
		String grabCam = grab == 806 ? "grabLeftCam" : "grabRightCam";
		// 1035(806/802) → BossLeftFight-grabLeftSensor: Internal 0x9(false) + SwitchCamera → 다음 틱 카메라
		assertTrue(until(r, 1, () -> grabCam.equals(r.cam.current())), "잡기 카메라: " + r.cams);
		r.run(0.5f);
		assertEquals(grabCam, r.cam.current(), "질의 끔 → 볼륨 카메라로 안 돌아감");
		assertTrue(r.cam.animTime() > 0.4f && r.cam.animTime() < 0.6f, "0x1000: 활성화 시 ANM_bossleftfight 처음부터 " + r.cam.animTime());
		assertTrue(onScreen(r.cam, heroChest(r), 1500), "잡기 카메라 안의 크레토스 " + r.ctl.position + " eye " + r.cam.eye());
		// 연타 → 807/803 struggle 카메라 → 성공 809/805 win 카메라 → 3.33초 뒤 질의 켬 → 볼륨 카메라
		String strug = grab == 806 ? "leftStrugCam" : "rightStrugCam", win = grab == 806 ? "leftWinCam" : "rightWinCam";
		assertTrue(until(r, 3, () -> strug.equals(r.cam.current())), "연타 카메라: " + r.cams + " " + r.log + " boss " + r.bossMoves + " hero " + r.heroMoves);
		r.run(1.1f); // 전환 1.0초 (CAM 0xB8)
		assertEquals(strug, r.cam.current());
		assertTrue(onScreen(r.cam, heroChest(r), 1500), "연타 카메라 안의 크레토스 " + r.ctl.position + " eye " + r.cam.eye());
		assertTrue(until(r, 12, () -> win.equals(r.cam.current())), "성공 카메라: " + r.cams);
		assertTrue(until(r, 6, () -> !r.cam.current().equals(win)), "Delay 3.33 → Internal 0x9(true) → 볼륨 카메라: " + r.cams);
		assertFalse(r.cam.current().toLowerCase().contains("grab") || r.cam.current().toLowerCase().contains("strug"));
	}

	@Test
	void hydraKillCamera() throws Exception {
		BossRig r = HydraFightTest.spawned();
		EnemyFighter b = r.fight.boss();
		r.hydraZone(1);
		// 돛대 3단계를 건너뛰고 기절 → 처형 (스크립트 조건은 데이터대로: LevelData39 = 3)
		r.host.levelData().put(39, 3);
		contact(r, 71, b.hp - 100);
		assertTrue(until(r, 10, () -> bossMove(r) == 120), "기절 루프: " + r.bossMoves + " LD39 " + r.levelData(39));
		r.tick();
		for (int k = 0; k < 20 && !(r.hero.active() && r.hero.move().id() == 800); k++) {
			until(r, 3, () -> !r.hero.active() || r.hero.move().id() == 80);
			r.pad.down.add(devple.gow.engine.input.PadButton.CIRCLE);
			r.tick();
		}
		contact(r, 800, 0);
		r.tick();
		assertEquals(810, bossMove(r));
		// 1035(810) → BossFInalGame-bossKillSensor → bossKillCam (관절 bossKillCam, ANM_bossfinalgame 12.5초)
		assertTrue(until(r, 1, () -> "bossKillCam".equals(r.cam.current())), "처형 카메라: " + r.cams);
		r.run(0.3f);
		assertTrue(onScreen(r.cam, heroChest(r), 2000), "처형 카메라 안의 크레토스 " + r.ctl.position + " eye " + r.cam.eye());
		// 811 → "bossKillStrug" 은 CAM_bossKillStrugCam 과 이름이 달라 원작 조회(0x17A7B8)도 실패 → bossKillCam 유지
		assertTrue(until(r, 20, () -> r.bossMoves.contains(812)), r.bossMoves.toString());
		assertTrue(r.cams.contains("bossKillCam") && !r.cams.contains("bossKillStrugCam"), r.cams.toString());
		// 812 → BossKillWinCam (goshit 오브젝트 관절)
		assertTrue(until(r, 1, () -> "BossKillWinCam".equalsIgnoreCase(r.cam.current())), "KILLWIN 카메라: " + r.cams);
	}

	@Test
	void aresRequestsAresCamera1() throws Exception {
		BossRig r = AresFightTest.spawned("ATHN08A");
		r.run(0.5f);
		assertEquals(CameraRig.ARES_CAMERA, r.cam.current(), "아레스 클래스 매 프레임 요청: " + r.cams);
		EnemyFighter b = r.fight.boss();
		assertTrue(onScreen(r.cam, heroChest(r), 1500), "AresCamera1 안의 크레토스 " + r.ctl.position + " eye " + r.cam.eye());
		// 주시점이 영웅에서 아레스 쪽으로 0.35
		Vector3f t = r.cam.pose().target;
		Vector3f want = new Vector3f(r.ctl.position).add(0, CameraRig.TARGET_HEIGHT, 0)
				.lerp(new Vector3f(b.pos).add(0, CameraRig.TARGET_HEIGHT, 0), CameraRig.ARES_TARGET_WEIGHT);
		assertTrue(t.distance(want) < 30, "주시점 " + t + " vs " + want);
		// 잡기 동기 중에도 유지
		b.hp = 480;
		assertTrue(until(r, 20, () -> bossMove(r) == 290 || bossMove(r) == 94 || bossMove(r) == 95), r.bossMoves.toString());
		contact(r, 200, 0);
		r.tick();
		assertTrue(until(r, 2, () -> r.fight.sync().active()), "동기 " + r.bossMoves);
		r.run(0.5f);
		assertEquals(CameraRig.ARES_CAMERA, r.cam.current());
		assertTrue(onScreen(r.cam, heroChest(r), 1500), "잡기 중 크레토스 " + r.ctl.position + " eye " + r.cam.eye());
	}

	@Test
	void ares3RequestsAresCamera1() throws Exception {
		BossRig r = AresFightTest.spawned("ATHN08C");
		r.run(0.5f);
		assertEquals(CameraRig.ARES_CAMERA, r.cam.current(), r.cams.toString());
		assertTrue(onScreen(r.cam, heroChest(r), 1500), "AresCamera1 안의 크레토스 " + r.ctl.position + " eye " + r.cam.eye());
	}
}
