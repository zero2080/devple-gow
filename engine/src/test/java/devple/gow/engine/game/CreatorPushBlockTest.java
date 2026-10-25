package devple.gow.engine.game;

import devple.gow.engine.nav.Interactables;
import devple.gow.engine.nav.Platforms;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.content.Content;
import devple.gow.tools.level.BallHull;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 43 §32: DEST02 돌 상자 퍼즐 (영상 2558–2670) — 문장 문 (PushBlockLiftDoor) 을 들어 올리면 벽감에 돌 상자, 그것을 끌어내 홀을 가로질러 동쪽 턱 벽까지
 * 밀고, 올라서서 위층 (384) 으로. 상자는 Creator PushBlock-BlockOnFloor 가 만든다: 원작 0x1A5298 은 Creator 슬롯 3 ≥ 0 이면 새 GO 에 PushPull
 * (템플릿 = 슬롯 3, id = 슬롯 6), 슬롯 4 ≥ 0 이면 Breakable 을 붙인다 (클래스 이름 포인터 0x29D8EC "PushPull" · 0x29D8F0 "Breakable" · 0x29D8F4
 * "IO_CSM" · 0x29D8F8 "SavePoint"). 예전 엔진은 이것을 이펙트 GO 로만 만들어 (충돌·R2 없음, 부서지는 act 를 돌려 파편이 흩어진 채) 퍼즐을 풀 수 없었다.
 */
class CreatorPushBlockTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static LevelData level(String name) throws Exception {
		Path p = Content.file(ROM, name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new LevelData(WadFile.load(p));
	}

	private static LevelLayout.Placement placement(LevelData lv, String name) {
		return lv.layout.placements().stream().filter(p -> p.instance().name().equalsIgnoreCase(name)).findFirst()
				.orElseThrow(() -> new AssertionError(name));
	}

	@Test
	void dest02BlockIsAPushBoxWithCollision() throws Exception {
		LevelData lv = level("DEST02");
		List<ScriptEntity> created = new ArrayList<>();
		ScriptWorld w = new ScriptWorld(lv, new ScriptHost(), new ScriptListener() {
			@Override
			public void create(ScriptEntity source) {
				created.add(source);
			}
		});
		w.start();
		ScriptEntity floor = created.stream().filter(e -> e.name().equals("PushBlock-BlockOnFloor")).findFirst()
				.orElseThrow(() -> new AssertionError("CreateAtStart 상자 " + created));
		assertTrue(created.stream().noneMatch(e -> e.name().equals("PushBlock-BlockCreator")), "다시 만드는 쪽은 부서진 뒤에만 (LevelData8 == 0)");
		assertEquals("PushPullBlock", w.slot(floor, "Name0"));
		assertEquals(211, ((Number) w.slot(floor, "unk3")).intValue(), "슬롯 3 = PushPull 템플릿");
		assertEquals(950, ((Number) w.slot(floor, "unk4")).intValue(), "슬롯 4 = Breakable 템플릿");
		assertEquals(-1, ((Number) w.slot(floor, "unk5")).intValue(), "슬롯 5 = IO_CSM 없음");
		assertEquals(0, ((Number) w.slot(floor, "unk6")).intValue(), "슬롯 6 = PushPull id");

		Tweaks tw = new Tweaks();
		tw.load(lv.wad);
		LevelLayout.Placement proto = placement(lv, "gopushpullblock"); // 레벨 원점의 원형 (비헤이비어 없음)
		assertNull(lv.behaviors.get("gopushpullblock"), "원형 GO 에는 BHV 가 없다 — 비헤이비어는 Creator 슬롯이 정한다");
		Skeleton sk = proto.object().skeleton();
		BallHull cdv = lv.hulls.get("CDV_PushPullBlock");
		assertNotNull(cdv);
		float[] inst = SceneNav.creatorMatrix(floor);
		Interactables items = new Interactables();
		Interactables.Item box = items.addPush("pushblock-blockonfloor", new Vector3f(), 211, 0, tw, 27.5f);
		assertNotNull(box);
		assertEquals(Interactables.Kind.PUSH, box.kind);
		assertEquals("DesertPush", box.label);
		assertEquals(16f, box.boxHalf, 1e-3f, "Radius 1.0 × 16");
		assertEquals(32f, box.boxHeight, 1e-3f, "#044ed4a7 2.0 × 16");
		Interactables.placeCreated(box, sk, inst, tw);
		assertTrue(box.handles.isEmpty(), "손잡이 관절 없음 → 옆면 잡기 (0x1C3CD8)");
		assertEquals(inst[13] + 15.9f, box.pos.y, 0.1f, "아이템 위치 = 관절 0 (GO 원점 위 15.9)");
		assertArrayEquals(new float[]{2768, 277.3f, 6816}, new float[]{box.goMatrix[12], box.goMatrix[13], box.goMatrix[14]}, 0.5f,
				"벽감 안 (문 PushBlockLiftDoor 뒤, 바닥 276)");
		// 벽감 앞 (문 쪽, −z) 에서 정면으로 잡힌다: 영웅 발 (2768, 276, 6800 − 9.6) 이 +z 를 보면 앞 점 = 발 + 위·17.6 + 정면·9.6
		float[] face = Interactables.faceGrab(box, 2768, 276 + Interactables.GRAB_UP, 6800 - 9.6f + Interactables.GRAB_FWD,
				new Vector3f(0, 0, 1));
		assertNotNull(face, "남쪽 면");
		assertEquals(-1f, face[4], 1e-3f, "바깥 축 −z");

		// 충돌 몸: 위에 설 수 있고 (윗면 = 밑면 + 32), 밀면 몸이 같이 간다
		Platforms ps = new Platforms();
		Platforms.Body b = ps.add(box.owner, sk, inst, cdv);
		Platforms.Support top = ps.floor(2768, 400, 6816, 200);
		assertNotNull(top, "상자 윗면");
		assertEquals(inst[13] + 32f, top.y(), 1.5f);
		assertTrue(ps.shift(box.owner, 0, 0, -132), "끌어냄 (영상 2566–2615)");
		assertNull(ps.floor(2768, 400, 6816, 200), "벽감은 비었다");
		assertNotNull(ps.floor(2768, 400, 6684, 200), "홀에 상자");
		assertEquals(6684f, b.goMatrix()[14], 0.5f, "렌더 기준 GO 행렬도 옮겨짐");

		// 동쪽 턱 (x ≥ 3152, 390 · 위층 384) 은 홀 바닥 274 위 116 — 맨몸 이단 점프로는 손 (발 + 35.2) 이 닿지 않고 상자 윗면 (306) 에서는 닿는다
		float ledge = 390f, hall = 274f, hand = 35.2f, doubleJump = 69f;
		assertTrue(hall + doubleJump + hand < ledge, "바닥에서는 턱에 닿지 않는다");
		assertTrue(hall + 32 + doubleJump + hand > ledge, "상자 위에서는 닿는다");
	}

	/** 같은 Creator 경로의 다른 상자: ATHN01D Crate (PushPullBox, Push 030 "HydraPushBox" · Brkb 919) 도 밀기 상자 (Type 하위 바이트 0). */
	@Test
	void athn01dCrateIsAPushBoxToo() throws Exception {
		LevelData lv = level("ATHN01D");
		ScriptWorld w = new ScriptWorld(lv, new ScriptHost(), new ScriptListener() {
		});
		ScriptEntity crate = w.entities().stream().filter(e -> e.name().equals("Crate")).findFirst().orElseThrow();
		assertEquals(30, ((Number) w.slot(crate, "unk3")).intValue());
		assertEquals(919, ((Number) w.slot(crate, "unk4")).intValue());
		Tweaks tw = new Tweaks();
		tw.load(lv.wad);
		Interactables.Item box = new Interactables().addPush("crate", new Vector3f(), 30, 0, tw, 24f);
		assertNotNull(box);
		assertEquals("HydraPushBox", box.label);
		assertEquals(12f, box.boxHalf, 1e-3f, "Radius 0.75 × 16");
	}
}
