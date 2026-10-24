package devple.gow.engine.game;

import devple.gow.engine.nav.Interactables;
import devple.gow.engine.nav.Platforms;
import devple.gow.engine.script.GameScriptHost;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.anim.SkinnedModel;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.model.GowMesh;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 아테네 발리스타 방 · 제우스 석상 구간 (ATHN05B / 05BB, 43 문서 §22, QA vs PS2 영상 7351–8066 초): 메시 혼합 노드 (제우스 얼굴), 영웅 스크립트
 * 접촉 관절 (pelvis), 스크립트 볼륨 선분 통과, 밀기 상자 몸 크기·이동·발판 회전 (둥근 판 = goladder 관절 15).
 */
class AthensCatapultTest {

	static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	static WadFile wad(String name) throws Exception {
		Path p = ROM.resolve(name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	static GowModel model(WadFile w, String mdl) {
		return GowModel.load(w).stream().filter(m -> m.name.equals(mdl)).findFirst().orElseThrow();
	}

	static Skeleton skeleton(WadFile w, String mdl) {
		for (Skeleton s : Skeleton.loadAll(w)) {
			if (Skeleton.groupRefs(w, s.name).contains(mdl)) {
				return s;
			}
		}
		throw new AssertionError(mdl);
	}

	/** 메시 +0x2C 혼합 노드 표 (레코드 뒤, 노드 = {n, (관절, 가중) × n}, 단어 수 = +0x30) — 제우스 얼굴 MAmagicDoor_0 의 첫 노드 [확실: 데이터]. */
	@Test
	void zeusFaceMeshHasBlendNodes() throws Exception {
		WadFile w = wad("ATHN05B");
		GowMesh m = model(w, "MDL_MAmagicDoor").meshes.getFirst();
		assertEquals(31, m.jointCount);
		assertEquals(32, m.blendJoints.length, "혼합 노드 32 (+0x2C)");
		assertArrayEquals(new int[]{23, 28, 30}, m.blendJoints[0]);
		assertArrayEquals(new float[]{0.125f, 0.125f, 0.75f}, m.blendWeights[0], 1e-6f);
		for (float[] ws : m.blendWeights) {
			float s = 0;
			for (float x : ws) {
				s += x;
			}
			assertEquals(1f, s, 1e-3f);
		}
		// vuOr 0x40 (혼합 스키닝 프로그램) 파트는 부착 관절이 있어도 모델 공간 — 예전엔 머리 관절 로컬로 한 번 더 밀려 문 밖에 그려졌다
		Skeleton sk = skeleton(w, "MDL_MAmagicDoor");
		SkinnedModel sm = new SkinnedModel(model(w, "MDL_MAmagicDoor"), 0, 0);
		int skin = 0;
		for (SkinnedModel.Part p : sm.parts) {
			if (p.skinProgram && p.attachJoint != 0) {
				assertFalse(p.jointLocal(sk), p.name);
				assertTrue(p.hasBlend());
				skin++;
			}
		}
		assertTrue(skin > 0, "얼굴 파트 (부착 2 head, vuOr 0x50)");
	}

	/** 팔레트: 관절 행렬 뒤에 혼합 노드 = Σ 가중 × 관절 행렬 (R_MIN 미노타우로스 노드 55 = 24×0.75 + 25×0.25, 56 = 11×0.75 + 12×0.25). */
	@Test
	void blendPaletteMixesJointMatrices() throws Exception {
		WadFile w = wad("R_MIN");
		SkinnedModel sm = new SkinnedModel(model(w, "MDL_MIN"), 0, 0);
		SkinnedModel.Part p = sm.parts.stream().filter(SkinnedModel.Part::hasBlend).findFirst().orElseThrow();
		assertEquals(55, p.blendBase);
		float[][] mats = new float[55][];
		for (int j = 0; j < mats.length; j++) {
			mats[j] = new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, j, 2 * j, 0, 1};
		}
		float[][] pal = p.palette(mats);
		assertEquals(57, pal.length);
		assertEquals(24.25f, pal[55][12], 1e-4f);
		assertEquals(48.5f, pal[55][13], 1e-4f);
		assertEquals(11.25f, pal[56][12], 1e-4f);
		assertEquals(1f, pal[56][15], 1e-6f, "가중 합 1 → 동차 성분 1");
	}

	/** 영웅 스크립트 레코드 관절 = 메시 [0] +0x34 (0x1ADD74): hero_0 = 1 "pelvis", 바인드 높이 21.65 [확실: 코드 · 데이터]. */
	@Test
	void heroScriptContactJointIsThePelvis() throws Exception {
		WadFile w = wad("R_HERO0");
		GowMesh m = model(w, "MDL_hero").meshes.getFirst();
		assertEquals(1, m.contactJoint);
		Skeleton sk = Skeleton.loadAll(w).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		assertEquals("pelvis", sk.joints.get(1).name());
		assertEquals(21.65f, sk.bindWorld()[1][13], 0.05f);
	}

	/**
	 * 바닥 위에서 시작하는 볼륨 (ATHN05B HandleSeenGroup: 가운데 수직선에서 y 494.75 – 636, 바닥 480) 은 발로는 못 들어가고 pelvis (+21.65) 로
	 * 들어간다 — 진입 센서 조건 GlobalData "HandleSeen" (05B 번호 7) 이 참이면 CheckPoint.
	 */
	@Test
	void volumeAboveTheFloorIsEnteredByThePelvis() throws Exception {
		LevelData lv = new LevelData(wad("ATHN05B"));
		List<String> cps = new ArrayList<>();
		ScriptHost host = new ScriptHost();
		ScriptWorld w = new ScriptWorld(lv, host, new ScriptListener() {
			@Override
			public void checkpoint(ScriptEntity source) {
				cps.add(source.name());
			}
		});
		w.start();
		host.globalData().put(GameScriptHost.globalKey("HandleSeen"), true);
		String seen = "HandleSeenGroup-nurbsSphere4-EntrySensor1";
		w.tick(1 / 60f, -1216, 480, 3700);
		for (int i = 0; i < 30; i++) {
			w.tick(1 / 60f, -1216, 480, 3888); // 발
		}
		assertFalse(cps.contains(seen), cps.toString());
		for (int i = 0; i < 30; i++) {
			w.tick(1 / 60f, -1216, 480 + 21.65f, 3888); // pelvis
		}
		assertTrue(cps.contains(seen), cps.toString());
	}

	/**
	 * 한 틱에 얇은 볼륨을 건너뛰어도 진입 센서가 선다 — 원작은 지난 점 → 지금 점 선분 질의 (0x1A7FFC → 0x11E190, 콜백 0x1A8508) 로 접촉 목록에 없는
	 * 볼륨에 진입 (2) 과 이탈 (4) 을 보낸다 [확실: 코드]. ATHN05B toMagicDoor (z 4894 – 4906, 두께 12) → LevelData8.
	 */
	@Test
	void crossingAThinVolumeInOneTickFiresTheEntrySensor() throws Exception {
		LevelData lv = new LevelData(wad("ATHN05B"));
		ScriptHost host = new ScriptHost();
		ScriptWorld w = new ScriptWorld(lv, host, new ScriptListener() {
		});
		w.start();
		w.tick(1 / 60f, -862, 501, 4880);
		w.tick(1 / 60f, -862, 501, 4880);
		assertNotEquals(Boolean.TRUE, host.levelData().get(8));
		w.tick(1 / 60f, -862, 501, 4920);
		assertEquals(Boolean.TRUE, host.levelData().get(8), "toMagicDoor-pCube4412-magicDoorVar");
	}

	static Interactables items(LevelData lv) {
		Tweaks tw = new Tweaks();
		tw.load(lv.wad);
		return Interactables.of(lv, tw);
	}

	/** Push 템플릿 025 "BallistaPlatfor": Radius 1.76 × 16 = 반 너비 28.16, #044ed4a7 2 × 16 = 높이 32 (0x20F9F8 몸 +0x120) [확실: 코드 · 데이터]. */
	@Test
	void ballistaBoxIsTemplateSized() throws Exception {
		Interactables.Item b = items(new LevelData(wad("ATHN05B"))).byOwner("goballistapushpull");
		assertEquals(Interactables.Kind.PUSH, b.kind);
		assertEquals(28.16f, b.boxHalf, 1e-3f);
		assertEquals(32f, b.boxHeight, 1e-3f);
	}

	static float[] arrowAxis(Platforms ps) {
		float[] m = ps.jointWorld("gobiggun1crank", "arrowjoint");
		return new float[]{m[8], m[9], m[10]};
	}

	/**
	 * 둥근 판 (goladder 관절 15 group2920, act "ladder" 3.67 초에 yaw 0 → 90°) 위의 발리스타는 판과 같이 돈다 (0x1E6248). 처음 볼트 = 남쪽 (−z, 0x222B24:
	 * 방향 = Aim Joint 행 2) → 판 정방향 한 번 = 서쪽 → 내려놓고 빈 판을 되돌린 뒤 다시 올려 한 번 더 = 북쪽 (홀 끝 BrkbDoor, 영상 7816 – 7840).
	 */
	@Test
	void turntableTurnsTheBallistaTowardsTheDoor() throws Exception {
		LevelData lv = new LevelData(wad("ATHN05B"));
		Interactables its = items(lv);
		Interactables.Item box = its.byOwner("goballistapushpull");
		Platforms ps = Platforms.of(lv, Set.of("goladder"), SceneNav.bodyOwners(its));
		ps.update(0);
		float[] a0 = arrowAxis(ps);
		assertEquals(-1f, a0[2], 1e-3f, "처음 볼트는 남쪽");
		float[] crank0 = ps.jointWorld("gobiggun1crank", "arrowjoint").clone();

		// 밀어서 판 가운데로 (상자 몸 + 크랭크 자식이 같이)
		float dx = 14.5f - box.pos.x, dz = 4728f - box.pos.z, dy = 433.3f - 5.46f - box.pos.y;
		assertTrue(ps.shift(box.owner, dx, dy, dz));
		box.pos.add(dx, dy, dz);
		ps.update(0);
		assertEquals(crank0[12] + dx, ps.jointWorld("gobiggun1crank", "arrowjoint")[12], 0.01f, "자식 GO (부모 링크) 도 이동");

		turn(ps, its, 0x8);
		float[] a1 = arrowAxis(ps);
		assertEquals(1f, Math.abs(a1[0]), 1e-2f, "한 번 돌면 동서");
		assertEquals(14.5f, box.pos.x, 1f, "판 가운데에 실린 상자는 제자리에서 돈다");
		assertEquals(4728f, box.pos.z, 1f);

		// 내려놓고 빈 판을 되돌린 뒤 다시 올려 한 번 더
		assertTrue(ps.shift(box.owner, -100, 0, 0));
		box.pos.add(-100, 0, 0);
		turn(ps, its, 0x9);
		assertArrayEquals(a1, arrowAxis(ps), 1e-3f, "판 밖 상자는 그대로");
		assertTrue(ps.shift(box.owner, 100, 0, 0));
		box.pos.add(100, 0, 0);
		turn(ps, its, 0x8);
		float[] a2 = arrowAxis(ps);
		assertEquals(1f, a2[2], 1e-2f, "두 번 = 북쪽 (홀 끝 문)");
	}

	/**
	 * 다른 슬롯 물체: ATHN05B 에서 쏜 발리스타 볼트 (ArrowEmitter/201 → Arrow/100) 가 ATHN05BB 홀 끝 BrkbDoor (Brkb, Marker600) 를 부수고, 그 슬롯
	 * 스크립트 BrkbDoor-DestructionSensor1 (모드 3, Filter 600) → LevelData5 · SwitchCamera TestCamera (영상 7857 – 7858). 원작은 두 슬롯 GO 가
	 * 한 세계에 있다 — 예전 엔진은 현재 슬롯 물체만 맞혀 볼트가 문을 지나쳤다.
	 */
	@Test
	void boltBreaksTheDoorInTheOtherSlot() throws Exception {
		LevelData b = new LevelData(wad("ATHN05B")), bb = new LevelData(wad("ATHN05BB"));
		Tweaks twB = new Tweaks();
		twB.load(b.wad);
		Tweaks twBB = new Tweaks();
		twBB.load(bb.wad);
		ScriptHost host = new ScriptHost();
		List<String> cams = new ArrayList<>();
		ScriptWorld w2 = new ScriptWorld(bb, host, new ScriptListener() {
			@Override
			public void camera(String name) {
				cams.add(name);
			}
		});
		w2.start();
		w2.tick(1 / 60f, 1e6f, 1e6f, 1e6f);
		devple.gow.engine.game.Breakables other = new devple.gow.engine.game.Breakables(bb, twBB);
		assertNotNull(other.item("gobrkbdoor"));
		float[] aim = devple.gow.tools.anim.Mat4.identity(); // 두 번 돈 발리스타 arrowjoint: +Z = 북쪽 (r15 111.86 초 (−80, 459, 4673))
		aim[12] = -80;
		aim[13] = 459;
		aim[14] = 4673;
		Integer emitter = b.arrowEmitters.get("gobiggun1crank");
		assertEquals(Integer.valueOf(201), emitter, "Creator 201 = ArrowEmitter/201 → Arrow/100");
		ArrowShots shots = new ArrowShots();
		assertNotNull(shots.fire(twB, emitter, aim, Set.of(), null));
		var col = new devple.gow.engine.level.LevelCollision(b.sheets);
		var col2 = new devple.gow.engine.level.LevelCollision(bb.sheets);
		List<String> broken = new ArrayList<>();
		for (int i = 0; i < 120 && !shots.shots().isEmpty(); i++) {
			shots.update(1 / 60f, new ArrowShots.Targets(null, null, null, List.of(), null, col, col2, null, null, null, null, null, other, w2,
					broken::add));
		}
		for (int i = 0; i < 10; i++) {
			w2.tick(1 / 60f, 1e6f, 1e6f, 1e6f);
		}
		assertEquals(List.of("gobrkbdoor"), broken);
		assertEquals(Boolean.TRUE, host.levelData().get(5), "BrkbDoor-DestructionSensor1 → LevelData5");
		assertTrue(cams.contains("TestCamera"), cams.toString());
	}

	private static void turn(Platforms ps, Interactables its, int mode) {
		assertTrue(ps.play("goladder", "!0", mode, 1, 0));
		for (int i = 0; i < 60 * 5; i++) {
			ps.update(1 / 60f);
			SceneNav.rideBoxes(its, ps, null);
		}
	}
}
